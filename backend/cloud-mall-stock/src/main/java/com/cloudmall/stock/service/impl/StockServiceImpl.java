package com.cloudmall.stock.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.domain.vo.StockReservationItemVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/stock")
public class StockServiceImpl implements StockService {
  /** 记录普通库存投影和旧预扣标记清理失败。 */
  private static final Logger log = LoggerFactory.getLogger(StockServiceImpl.class);

  /** 保存 PREFIX 的业务状态或配置。 */
  private static final String PREFIX = "stock:available:", RES = "stock:reservation:";

  private static final String SECKILL =
      "if redis.call('EXISTS',KEYS[3])==1 then return 3 end; local now=tonumber(ARGV[3]); local"
          + " start=tonumber(ARGV[4]); local finish=tonumber(ARGV[5]); if now<start then return 4"
          + " end; if now>=finish then return 5 end; local used=tonumber(redis.call('GET',KEYS[2])"
          + " or '0'); if used>=tonumber(ARGV[2]) then return 2 end; local"
          + " n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n<1 then return 0 end;"
          + " redis.call('DECR',KEYS[1]); redis.call('INCRBY',KEYS[2],1);"
          + " redis.call('EXPIRE',KEYS[2],ARGV[6]); redis.call('SET',KEYS[3],ARGV[1],'EX',ARGV[6]);"
          + " return 1";
  private static final String SECKILL_ROLLBACK =
      "if redis.call('EXISTS',KEYS[3])==0 then return 0 end; redis.call('INCRBY',KEYS[1],1); local"
          + " used=tonumber(redis.call('GET',KEYS[2]) or '0'); if used<=1 then"
          + " redis.call('DEL',KEYS[2]) else redis.call('DECR',KEYS[2]) end;"
          + " redis.call('DEL',KEYS[3]); return 1";

  /** 保存 redis 的业务状态或配置。 */
  @Autowired private StringRedisTemplate redis;

  /** 保存 db 的业务状态或配置。 */
  @Autowired private StockSqlMapper stockSqlMapper;

  /** 保存 rabbit 的业务状态或配置。 */
  @Autowired private RabbitTemplate rabbit;

  /** 保存 mapper 的业务状态或配置。 */
  @Autowired private ObjectMapper mapper;

  /** 先于远程库存事务持久化订单号与规范化 SKU 快照。 */
  @Autowired private StockReservationRequestService reservationRequestService;

  private final DefaultRedisScript<Long>
      seckillScript = new DefaultRedisScript<>(SECKILL, Long.class),
      seckillRollbackScript = new DefaultRedisScript<>(SECKILL_ROLLBACK, Long.class);

  /** 创建由 Spring 字段注入依赖的库存服务实例。 */
  public StockServiceImpl() {}

  /** 创建 StockServiceImpl 实例。 */
  public StockServiceImpl(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      RabbitTemplate rabbit,
      ObjectMapper mapper) {
    // 1. 接收并整理 StockController 的业务请求。
    // 2. 执行 StockController 的核心业务校验与状态处理。
    // 3. 返回 StockController 的处理结果。
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
    this.rabbit = rabbit;
    this.mapper = mapper;
  }

  @GetMapping("/skus/{skuId}")
  /** 执行 get 相关操作。 */
  public ApiResponse<?> get(@PathVariable("skuId") Long skuId) {
    // 1. 接收并整理 get 的业务请求。
    // 2. 执行 get 的核心业务校验与状态处理。
    // 3. 返回 get 的处理结果。
    return ApiResponse.ok(new StockQuantityVO(skuId, quantity(skuId)));
  }

  @Transactional(noRollbackFor = StockReservationRejectedException.class)
  @PostMapping("/reservations")
  /** 执行 reserve 相关操作。 */
  public ApiResponse<?> reserve(@RequestBody StockReservationDTO request) {
    // 1. 接收并整理 reserve 的业务请求。
    // 2. 执行 reserve 的核心业务校验与状态处理。
    // 3. 返回 reserve 的处理结果。
    validate(request);
    List<StockLineDTO> lines =
        merge(request.items).stream()
            .sorted(Comparator.comparing(line -> line.skuId))
            .toList();
    String scene = request.scene == null || request.scene.isBlank() ? "NORMAL" : request.scene;
    if (!"NORMAL".equals(scene))
      throw new BizException(ErrorCodes.INVALID, "普通预扣场景无效", 400);
    String itemsSnapshot = toJson(lines);
    String itemsHash = sha256(itemsSnapshot);

    // 旧版本只有按 SKU 的流水；迁移期间仍须识别原订单，禁止再次占用库存。
    if (stockSqlMapper.findReservationRequest(request.orderNo) == null) {
      Map<Long, int[]> legacySnapshot = stockSnapshot(request.orderNo);
      if (!legacySnapshot.isEmpty()) {
        if (legacySnapshot.size() != lines.size())
          throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
        for (StockLineDTO line : lines) {
          int[] persistedLine = legacySnapshot.get(line.skuId);
          if (persistedLine == null || persistedLine[0] != line.quantity)
            throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
          if (persistedLine[1] > 0)
            throw new BizException(ErrorCodes.DUPLICATE, "同一订单库存已结算", 409);
        }
        return alreadyReserved(request.orderNo);
      }
    }

    // 1. 在独立事务中把原订单号绑定到唯一 SKU 快照。
    reservationRequestService.claim(request.orderNo, scene, itemsSnapshot, itemsHash);
    Map<String, Object> requestRow =
        stockSqlMapper.findReservationRequestForUpdate(request.orderNo);
    if (requestRow == null) throw new BizException(ErrorCodes.INTERNAL, "库存请求认领不存在", 500);
    if (!itemsHash.equals(String.valueOf(requestRow.get("items_hash"))))
      throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
    String currentStatus = String.valueOf(requestRow.get("status"));
    if ("RESERVED".equals(currentStatus)) return alreadyReserved(request.orderNo);
    if ("REJECTED".equals(currentStatus))
      throw new StockReservationRejectedException("库存预扣已拒绝");
    if (!"INIT".equals(currentStatus))
      throw new BizException(ErrorCodes.DUPLICATE, "同一订单库存已结算", 409);

    // 2. 升序锁定所有 SKU 并完成整笔可用量校验，拒绝时不修改任何数量。
    List<Map<String, Object>> stockRows = new ArrayList<>();
    for (StockLineDTO line : lines) {
      Map<String, Object> stockRow = stockSqlMapper.findStockForUpdate(line.skuId);
      if (stockRow == null
          || ((Number) stockRow.get("available_quantity")).intValue() < line.quantity) {
        stockSqlMapper.updateReservationRequestStatus(
            request.orderNo, "INIT", "REJECTED", ErrorCodes.STOCK, now());
        throw new StockReservationRejectedException("库存不足");
      }
      stockRows.add(stockRow);
    }

    // 3. MySQL 数量、幂等流水、Redis 修复意图和请求状态同事务提交。
    OffsetDateTime occurredAt = now();
    for (int index = 0; index < lines.size(); index++) {
      StockLineDTO line = lines.get(index);
      Map<String, Object> stockRow = stockRows.get(index);
      int version = ((Number) stockRow.get("version")).intValue();
      if (stockSqlMapper.updateAvailableWithVersion(
              line.skuId, line.quantity, version, occurredAt)
          != 1) throw new BizException(ErrorCodes.CONFLICT, "库存版本已变化", 409);
      flow(line, request.orderNo, "RESERVE", request.orderNo + ":RESERVE:" + line.skuId);
      int available = ((Number) stockRow.get("available_quantity")).intValue() - line.quantity;
      stockSqlMapper.insertCacheRepair(line.skuId, available, version + 1, occurredAt);
    }
    if (stockSqlMapper.updateReservationRequestStatus(
            request.orderNo, "INIT", "RESERVED", null, occurredAt)
        != 1) throw new BizException(ErrorCodes.CONFLICT, "库存请求状态已变化", 409);
    return reservationResult(request.orderNo);
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/confirm")
  /** 执行 confirm 相关操作。 */
  public ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 confirm 的业务请求。
    // 2. 执行 confirm 的核心业务校验与状态处理。
    // 3. 返回 confirm 的处理结果。
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequestForUpdate(orderNo);
    if (requestRow != null) {
      String status = String.valueOf(requestRow.get("status"));
      if ("CONFIRMED".equals(status))
        return ApiResponse.ok(Map.of("confirmed", true, "idempotent", true));
      if (!"RESERVED".equals(status))
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣状态不允许确认", 409);
    }
    List<Map<String, Object>> lines = stockSqlMapper.findOpenReservations(orderNo);
    if (lines == null) lines = List.of();
    for (Map<String, Object> line : lines) settleNormalLine(orderNo, line, "CONFIRM");
    if (requestRow != null
        && stockSqlMapper.updateReservationRequestStatus(
                orderNo, "RESERVED", "CONFIRMED", null, now())
            != 1) throw new BizException(ErrorCodes.CONFLICT, "库存确认状态已变化", 409);
    clearLegacyReservationAfterCommit(orderNo);
    return ApiResponse.ok(Map.of("confirmed", true, "idempotent", lines.isEmpty()));
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/rollback")
  /** 执行 rollback 相关操作。 */
  public ApiResponse<?> rollback(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 rollback 的业务请求。
    // 2. 执行 rollback 的核心业务校验与状态处理。
    // 3. 返回 rollback 的处理结果。
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequestForUpdate(orderNo);
    if (requestRow != null) {
      String status = String.valueOf(requestRow.get("status"));
      if ("ROLLED_BACK".equals(status) || "REJECTED".equals(status))
        return ApiResponse.ok(Map.of("rolledBack", true, "idempotent", true));
      if (!"RESERVED".equals(status))
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣结果尚未确定", 409);
    }
    List<Map<String, Object>> lines = stockSqlMapper.findOpenReservations(orderNo);
    if (lines == null) lines = List.of();
    for (Map<String, Object> line : lines) settleNormalLine(orderNo, line, "ROLLBACK");
    if (requestRow != null
        && stockSqlMapper.updateReservationRequestStatus(
                orderNo, "RESERVED", "ROLLED_BACK", null, now())
            != 1) throw new BizException(ErrorCodes.CONFLICT, "库存回滚状态已变化", 409);
    clearLegacyReservationAfterCommit(orderNo);
    return ApiResponse.ok(Map.of("rolledBack", true, "idempotent", lines.isEmpty()));
  }

  /** 把单个未完成预扣在 MySQL 中确认或回滚，并同事务排队版本投影。 */
  private void settleNormalLine(String orderNo, Map<String, Object> line, String flowType) {
    long skuId = ((Number) line.get("sku_id")).longValue();
    int quantity = ((Number) line.get("quantity")).intValue();
    Map<String, Object> stockRow = stockSqlMapper.findStockForUpdate(skuId);
    if (stockRow == null)
      throw new BizException(ErrorCodes.STOCK, "预扣库存 SKU 不存在", 409);
    int version = ((Number) stockRow.get("version")).intValue();
    OffsetDateTime changedAt = now();
    if (stockSqlMapper.updateSettlementWithVersion(
            skuId, flowType, quantity, version, changedAt)
        != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或版本冲突", 409);
    stockSqlMapper.insertFlow(
        skuId, orderNo, flowType, quantity, orderNo + ":" + flowType + ":" + skuId, changedAt);
    int targetAvailable = ((Number) stockRow.get("available_quantity")).intValue();
    if ("ROLLBACK".equals(flowType)) targetAvailable += quantity;
    stockSqlMapper.insertCacheRepair(skuId, targetAvailable, version + 1, changedAt);
  }

  /** 查询订单库存预扣的持久化状态，供远程写结果未知时恢复。 */
  @GetMapping("/reservations/{orderNo}")
  public ApiResponse<?> reservation(@PathVariable("orderNo") String orderNo) {
    return reservationResult(orderNo);
  }

  private ApiResponse<StockReservationVO> reservationResult(String orderNo) {
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequest(orderNo);
    List<Map<String, Object>> rows = stockSqlMapper.findReservationFlows(orderNo);
    if ((rows == null || rows.isEmpty()) && requestRow == null) {
      return ApiResponse.ok(new StockReservationVO(orderNo, "NOT_FOUND", List.of()));
    }
    if (rows == null || rows.isEmpty()) {
      String requestStatus = String.valueOf(requestRow.get("status"));
      if ("RESERVED".equals(requestStatus)
          || "CONFIRMED".equals(requestStatus)
          || "ROLLED_BACK".equals(requestStatus)) {
        throw new IllegalStateException("库存请求状态与持久流水不一致: " + orderNo);
      }
      return ApiResponse.ok(
          new StockReservationVO(
              orderNo,
              requestStatus,
              itemsFromSnapshot(String.valueOf(requestRow.get("items_snapshot")), requestStatus)));
    }
    String status = "RESERVED";
    List<StockReservationItemVO> items = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      int reserved = number(row.get("reserved_quantity"));
      int confirmed = number(row.get("confirmed_quantity"));
      int rolledBack = number(row.get("rolled_back_quantity"));
      int settled = confirmed + rolledBack;
      String itemStatus = confirmed > 0 ? "CONFIRMED" : (rolledBack > 0 ? "ROLLED_BACK" : "RESERVED");
      if (settled >= reserved && confirmed > 0) status = "CONFIRMED";
      else if (settled >= reserved && rolledBack > 0 && !"CONFIRMED".equals(status)) status = "ROLLED_BACK";
      items.add(new StockReservationItemVO(((Number) row.get("sku_id")).longValue(), reserved, itemStatus));
    }
    if (requestRow != null) status = String.valueOf(requestRow.get("status"));
    return ApiResponse.ok(new StockReservationVO(orderNo, status, items));
  }

  /** 在尚未写入库存流水时从不可变请求快照展示预扣明细。 */
  private List<StockReservationItemVO> itemsFromSnapshot(String snapshot, String status) {
    try {
      List<StockLineDTO> lines =
          mapper.readValue(
              snapshot,
              new com.fasterxml.jackson.core.type.TypeReference<List<StockLineDTO>>() {});
      return lines.stream()
          .map(line -> new StockReservationItemVO(line.skuId, line.quantity, status))
          .toList();
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformedSnapshot) {
      throw new IllegalStateException("库存预扣快照无法解析", malformedSnapshot);
    }
  }

  /** 返回同一订单号、同一 SKU 快照的幂等预扣结果。 */
  private ApiResponse<StockReservationVO> alreadyReserved(String orderNo) {
    ApiResponse<StockReservationVO> response = reservationResult(orderNo);
    response.data.status = "ALREADY_RESERVED";
    return response;
  }

  /** 将排序后的 SKU/数量快照写成稳定 JSON。 */
  private String toJson(List<StockLineDTO> lines) {
    try {
      return mapper.writeValueAsString(lines);
    } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
      throw new BizException(ErrorCodes.INVALID, "库存明细无法序列化", 400);
    }
  }

  /** 计算预扣明细的不可变 SHA-256 请求摘要。 */
  private static String sha256(String itemsSnapshot) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(itemsSnapshot.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException unavailableAlgorithm) {
      throw new IllegalStateException("JDK 缺少 SHA-256", unavailableAlgorithm);
    }
  }

  private static int number(Object value) { return value == null ? 0 : ((Number) value).intValue(); }

  @PostMapping("/seckill/reservations")
  /** 执行 seckill 相关操作。 */
  public ApiResponse<?> seckill(@RequestBody SeckillReservationDTO request) {
    // 1. 接收并整理 seckill 的业务请求。
    // 2. 执行 seckill 的核心业务校验与状态处理。
    // 3. 返回 seckill 的处理结果。
    long userId = AuthContext.requireUserId();
    String activityId = String.valueOf(request == null ? null : request.activityId),
        skuId = String.valueOf(request == null ? null : request.skuId),
        requestUserId = String.valueOf(request == null ? null : request.userId),
        idempotencyKey = String.valueOf(request == null ? null : request.idempotencyKey);
    if ("null".equals(activityId)
        || "null".equals(skuId)
        || "null".equals(requestUserId)
        || "null".equals(idempotencyKey))
      throw new BizException(ErrorCodes.INVALID, "秒杀参数不完整", 400);
    if (!String.valueOf(userId).equals(requestUserId))
      throw new BizException(ErrorCodes.FORBIDDEN, "秒杀用户归属校验失败", 403);
    Map<Object, Object> meta =
        redis.opsForHash().entries("seckill:meta:" + activityId + ":" + skuId);
    long start = epoch(meta.get("startAt")), end = epoch(meta.get("endAt"));
    int limit = Integer.parseInt(String.valueOf(meta.getOrDefault("perUserLimit", "1")));
    long ttl = Math.max(1, (end - System.currentTimeMillis() / 1000));
    String
        orderKey =
            "seckill:accepted:"
                + activityId
                + ":"
                + skuId
                + ":"
                + requestUserId
                + ":"
                + idempotencyKey,
        orderNo =
            now().format(DateTimeFormatter.ofPattern("yyyyMM"))
                + UUID.randomUUID().toString().replace("-", "");
    recordSeckillPending(
        Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey, orderNo);
    List<String> keys =
        List.of(
            "seckill:stock:" + activityId + ":" + skuId,
            "seckill:user:" + activityId + ":" + requestUserId,
            orderKey);
    Long result =
        redis.execute(
            seckillScript,
            keys,
            orderNo,
            String.valueOf(limit),
            String.valueOf(System.currentTimeMillis() / 1000),
            String.valueOf(start),
            String.valueOf(end),
            String.valueOf(ttl));
    if (Objects.equals(result, 3L)) {
      String existing = redis.opsForValue().get(orderKey);
      markSeckillAccepted(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      return accepted(existing);
    }
    if (Objects.equals(result, 2L)) {
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException(ErrorCodes.DUPLICATE, "超过用户限购", 429);
    }
    if (Objects.equals(result, 4L)) {
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException("SECKILL_NOT_STARTED", "秒杀未开始", 409);
    }
    if (Objects.equals(result, 5L)) {
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException("SECKILL_ENDED", "秒杀已结束", 409);
    }
    if (Objects.equals(result, 0L)) {
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException("SECKILL_SOLD_OUT", "秒杀库存耗尽", 409);
    }
    String no = redis.opsForValue().get(orderKey);
    try {
      rabbit.convertAndSend(
          "cloudmall.seckill.order.exchange",
          "",
          Map.of(
              "eventType",
              "SECKILL_ORDER_ACCEPTED",
              "orderNo",
              no,
              "activityId",
              Long.valueOf(activityId),
              "skuId",
              Long.valueOf(skuId),
              "userId",
              userId,
              "idempotencyKey",
              idempotencyKey,
              "addressSnapshot",
              request.addressSnapshot,
              "occurredAt",
              now().toString()));
    } catch (Exception e) {
      redis.execute(seckillRollbackScript, keys);
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException(ErrorCodes.INTERNAL, "秒杀订单入队失败", 500);
    }
    markSeckillAccepted(Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
    return accepted(no);
  }

  /** 执行 recordSeckillPending 相关操作。 */
  private void recordSeckillPending(
      long activityId, long skuId, long userId, String idempotencyKey, String orderNo) {
    // 1. 接收并整理 recordSeckillPending 的业务请求。
    // 2. 执行 recordSeckillPending 的核心业务校验与状态处理。
    // 3. 返回 recordSeckillPending 的处理结果。
    OffsetDateTime now = now();
    stockSqlMapper.saveSeckillPending(activityId, skuId, userId, orderNo, idempotencyKey, now);
  }

  /** 执行 markSeckillAccepted 相关操作。 */
  private void markSeckillAccepted(
      long activityId, long skuId, long userId, String idempotencyKey) {
    // 1. 接收并整理 markSeckillAccepted 的业务请求。
    // 2. 执行 markSeckillAccepted 的核心业务校验与状态处理。
    // 3. 返回 markSeckillAccepted 的处理结果。
    stockSqlMapper.markSeckillAccepted(activityId, skuId, userId, idempotencyKey, now());
  }

  /** 执行 markSeckillRejected 相关操作。 */
  private void markSeckillRejected(
      long activityId, long skuId, long userId, String idempotencyKey) {
    // 1. 接收并整理 markSeckillRejected 的业务请求。
    // 2. 执行 markSeckillRejected 的核心业务校验与状态处理。
    // 3. 返回 markSeckillRejected 的处理结果。
    stockSqlMapper.markSeckillRejected(activityId, skuId, userId, idempotencyKey, now());
  }

  /** 执行 accepted 相关操作。 */
  private ApiResponse<?> accepted(String no) {
    // 1. 接收并整理 accepted 的业务请求。
    // 2. 执行 accepted 的核心业务校验与状态处理。
    // 3. 返回 accepted 的处理结果。
    return ApiResponse.ok(Map.of("accepted", true, "orderNo", no, "status", "PENDING_PAYMENT"));
  }

  /** 读取 durable stock_flow 中指定订单的预扣快照，阻止 Redis 标记丢失后的重复预扣。 */
  private Map<Long, int[]> stockSnapshot(String orderNo) {
    Map<Long, int[]> snapshot = new LinkedHashMap<>();
    List<Map<String, Object>> durableSnapshot = stockSqlMapper.findReservationSnapshot(orderNo);
    if (durableSnapshot == null) return snapshot;
    for (Map<String, Object> row : durableSnapshot) {
      Object skuId = row.get("sku_id");
      Object reserved = row.get("reserved_quantity");
      Object settled = row.get("settled_quantity");
      if (skuId instanceof Number sku && reserved instanceof Number reserve && settled instanceof Number done) {
        snapshot.put(sku.longValue(), new int[] {reserve.intValue(), done.intValue()});
      }
    }
    return snapshot;
  }

  /** 库存事实提交后清理旧协议标记；Redis 故障不改变普通库存事务结论。 */
  private void clearLegacyReservationAfterCommit(String orderNo) {
    Runnable release =
        () -> {
          try {
            redis.delete(RES + orderNo);
            redis.delete(RES + orderNo + ":lines");
          } catch (RuntimeException unavailableRedis) {
            log.warn("旧版库存预扣标记清理失败，orderNo={}", orderNo, unavailableRedis);
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      release.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            release.run();
          }
        });
  }

  /** 执行 flow 相关操作。 */
  private void flow(StockLineDTO x, String order, String type, String key) {
    // 1. 接收并整理 flow 的业务请求。
    // 2. 执行 flow 的核心业务校验与状态处理。
    // 3. 返回 flow 的处理结果。
    stockSqlMapper.insertFlow(x.skuId, order, type, x.quantity, key, now());
  }

  /** 执行 epoch 相关操作。 */
  private static long epoch(Object value) {
    // 1. 接收并整理 epoch 的业务请求。
    // 2. 执行 epoch 的核心业务校验与状态处理。
    // 3. 返回 epoch 的处理结果。
    try {
      return Instant.parse(String.valueOf(value)).getEpochSecond();
    } catch (Exception e) {
      return 0;
    }
  }

  /** 执行 quantity 相关操作。 */
  private int quantity(Long sku) {
    // 1. 接收并整理 quantity 的业务请求。
    // 2. 执行 quantity 的核心业务校验与状态处理。
    // 3. 返回 quantity 的处理结果。
    String stockKey = PREFIX + sku;
    try {
      if (redis.type(stockKey) == DataType.HASH) {
        Object projected = redis.opsForHash().get(stockKey, "quantity");
        if (projected != null) return Integer.parseInt(String.valueOf(projected));
      }
    } catch (RuntimeException unavailableProjection) {
      // Redis 只是普通库存投影，读接口仍可回退到 MySQL 事实。
    }
    Map<String, Object> durableStock = stockSqlMapper.findStock(sku);
    return durableStock == null ? 0 : ((Number) durableStock.get("available_quantity")).intValue();
  }

  /** 执行 merge 相关操作。 */
  private static List<StockLineDTO> merge(List<StockLineDTO> in) {
    // 1. 接收并整理 merge 的业务请求。
    // 2. 执行 merge 的核心业务校验与状态处理。
    // 3. 返回 merge 的处理结果。
    Map<Long, Integer> m = new LinkedHashMap<>();
    for (StockLineDTO x : in) {
      if (x == null || x.skuId == null || x.quantity < 1)
        throw new BizException(ErrorCodes.INVALID, "库存数量必须为正数", 400);
      m.merge(x.skuId, x.quantity, Math::addExact);
    }
    return m.entrySet().stream()
        .map(e -> new StockLineDTO(e.getKey(), e.getValue()))
        .collect(Collectors.toList());
  }

  /** 执行 validate 相关操作。 */
  private static void validate(StockReservationDTO r) {
    // 1. 接收并整理 validate 的业务请求。
    // 2. 执行 validate 的核心业务校验与状态处理。
    // 3. 返回 validate 的处理结果。
    if (r == null
        || r.orderNo == null
        || r.orderNo.isBlank()
        || r.items == null
        || r.items.isEmpty()) throw new BizException(ErrorCodes.INVALID, "库存预扣参数不完整", 400);
  }

  /** 执行 now 相关操作。 */
  private static OffsetDateTime now() {
    // 1. 接收并整理 now 的业务请求。
    // 2. 执行 now 的核心业务校验与状态处理。
    // 3. 返回 now 的处理结果。
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 执行 id 相关操作。 */
  private static long id() {
    // 1. 接收并整理 id 的业务请求。
    // 2. 执行 id 的核心业务校验与状态处理。
    // 3. 返回 id 的处理结果。
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
