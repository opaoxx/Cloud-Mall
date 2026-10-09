package com.cloudmall.stock.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.config.SeckillSentinelConfiguration;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationItemVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockService;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
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

  /** 秒杀首次认领的单次时钟来源，隔离测试可注入跨月固定时刻。 */
  private Clock seckillClaimClock = Clock.system(ZoneOffset.ofHours(8));

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

  /** 将 Redis 预扣结果与 ACCEPTED/outbox 在同一本地事务中提交。 */
  @Autowired private SeckillAcceptanceService seckillAcceptanceService;

  /** 在独立事务中原子认领秒杀用户幂等键。 */
  @Autowired private SeckillRequestClaimService seckillRequestClaimService;

  /** 独立秒杀配额终态与原订单库存动作协议的适配服务。 */
  @Autowired private SeckillQuotaSettlementService seckillSettlementService;

  /** 同时扣配额并写入唯一结算回执的受理 Lua。 */
  private final DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();

  {
    seckillScript.setLocation(new ClassPathResource("lua/seckill-reserve.lua"));
    seckillScript.setResultType(Long.class);
  }

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
    this.seckillAcceptanceService = new SeckillAcceptanceService(stockSqlMapper);
    this.seckillRequestClaimService = new SeckillRequestClaimService(stockSqlMapper);
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
  /** 按原操作键确认秒杀配额或普通库存，重复动作读取同一终态。 */
  public ApiResponse<?> confirm(
      @PathVariable("orderNo") String orderNo,
      @RequestBody(required = false) StockActionDTO action) {
    // 1. 校验稳定动作键，秒杀结算由独立持久状态机处理。
    validateActionKey(orderNo, "CONFIRM", action);
    if (seckillSettlementService != null) {
      ApiResponse<?> seckillResult = seckillSettlementService.settle(orderNo, "CONFIRM");
      if (seckillResult != null) return seckillResult;
      requireNoUnsettledSeckillFact(orderNo);
    }
    // 2. 普通库存按持久请求状态及流水确认，拒绝相反终态。
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
  /** 按原操作键释放秒杀配额或普通预扣，秒杀 Redis 投影由提交后恢复器执行。 */
  public ApiResponse<?> rollback(
      @PathVariable("orderNo") String orderNo,
      @RequestBody(required = false) StockActionDTO action) {
    // 1. 校验稳定动作键，秒杀仅提交唯一释放意图。
    validateActionKey(orderNo, "ROLLBACK", action);
    if (seckillSettlementService != null) {
      ApiResponse<?> seckillResult = seckillSettlementService.settle(orderNo, "ROLLBACK");
      if (seckillResult != null) return seckillResult;
      requireNoUnsettledSeckillFact(orderNo);
    }
    // 2. 普通预扣沿用 MySQL 流水和版本投影，不操作秒杀配额。
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

  /** 兼容已有内部调用，统一使用订单号标识同一库存动作。 */
  @Transactional
  public ApiResponse<?> confirm(String orderNo) {
    return confirm(orderNo, null);
  }

  /** 兼容已有内部调用，统一使用订单号标识同一库存动作。 */
  @Transactional
  public ApiResponse<?> rollback(String orderNo) {
    return rollback(orderNo, null);
  }

  /** 拒绝新协议中与订单号和动作不匹配的幂等键。 */
  private static void validateActionKey(String orderNo, String type, StockActionDTO action) {
    if (action != null && !Objects.equals(action.operationKey, orderNo + ":" + type))
      throw new BizException(ErrorCodes.CONFLICT, "库存动作键与订单不匹配", 409);
  }

  /** 秒杀认领未受理或结算行损坏时不能落入普通空流水的幂等成功分支。 */
  private void requireNoUnsettledSeckillFact(String orderNo) {
    Map<String, Object> fact = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (fact != null && !fact.isEmpty())
      throw new BizException("SECKILL_SETTLEMENT_UNKNOWN", "秒杀受理结算事实尚未确认", 503);
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
    if (seckillSettlementService != null) {
      StockReservationVO seckillResult = seckillSettlementService.reservation(orderNo);
      if (seckillResult != null) return ApiResponse.ok(seckillResult);
    }
    Map<String, Object> seckillFact = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (seckillFact != null && !seckillFact.isEmpty()) {
      String acceptedStatus = String.valueOf(seckillFact.get("status"));
      String status =
          "ACCEPTED".equals(acceptedStatus)
              ? "RESERVED"
              : ("REJECTED".equals(acceptedStatus) ? "REJECTED" : "UNKNOWN");
      // 只读兼容存量台账；GET 不补表、不造回执，也不猜测最终结算。
      return ApiResponse.ok(
          new StockReservationVO(
              orderNo,
              status,
              List.of(
                  new StockReservationItemVO(
                      ((Number) seckillFact.get("sku_id")).longValue(), 1, status))));
    }
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
    return ApiResponse.ok(normalReservationProof(orderNo, requestRow, rows));
  }

  /** 核验普通流水和存在的原请求快照；全部SKU同向且原载荷一致才有已知状态。 */
  private StockReservationVO normalReservationProof(
      String orderNo, Map<String, Object> requestRow, List<Map<String, Object>> rows) {
    // 1. 精确读取原SKU/数量，拒绝缺值、截断、负数、超容量与超原RESERVE量。
    List<StockReservationItemVO> items = new ArrayList<>();
    String aggregateStatus = null;
    for (Map<String, Object> row : rows) {
      if (row == null) return unknownReservationProof(orderNo);
      try {
        // 原SUM不能证明来源：字节身份须唯一且精确，非法原行不能被抵消或忽略。
        if (!Objects.equals(orderNo, row.get("original_order_no"))
            || exactReservationNumber(row.get("order_identity_count")).longValueExact() != 1
            || exactReservationNumber(row.get("invalid_flow_count")).longValueExact() != 0)
          return unknownReservationProof(orderNo);
        long skuId = exactReservationNumber(row.get("sku_id")).longValueExact();
        int reserved = exactReservationNumber(row.get("reserved_quantity")).intValueExact();
        int confirmed = exactReservationNumber(row.get("confirmed_quantity")).intValueExact();
        int rolledBack = exactReservationNumber(row.get("rolled_back_quantity")).intValueExact();
        if (skuId <= 0
            || reserved <= 0
            || confirmed < 0
            || rolledBack < 0
            || (long) confirmed + rolledBack > reserved) return unknownReservationProof(orderNo);
        // 2. 单SKU只有全量同向或未结算才有已知状态，跨SKU混合与部分结清均阻塞。
        String itemStatus =
            confirmed == reserved && rolledBack == 0
                ? "CONFIRMED"
                : rolledBack == reserved && confirmed == 0
                    ? "ROLLED_BACK"
                    : confirmed == 0 && rolledBack == 0 ? "RESERVED" : "UNKNOWN";
        if ("UNKNOWN".equals(itemStatus)
            || aggregateStatus != null && !aggregateStatus.equals(itemStatus))
          return unknownReservationProof(orderNo);
        aggregateStatus = itemStatus;
        items.add(new StockReservationItemVO(skuId, reserved, itemStatus));
      } catch (IllegalArgumentException | ArithmeticException invalidNumber) {
        return unknownReservationProof(orderNo);
      }
    }
    // 3. 验证原请求状态和完整SKU快照；读中竞争或历史矛盾保留原事实。
    if (aggregateStatus == null
        || requestRow != null
            && (!Objects.equals(aggregateStatus, requestRow.get("status"))
                || !requestSnapshotMatches(orderNo, requestRow, items)))
      return unknownReservationProof(orderNo);
    return new StockReservationVO(orderNo, aggregateStatus, items);
  }

  /** 验证原规范快照和完整SKU/数量集合；MySQL JSON规范化后仍沿原writer重建hash。 */
  private boolean requestSnapshotMatches(
      String orderNo, Map<String, Object> requestRow, List<StockReservationItemVO> observedItems) {
    // 1. 不借排序规则别名或其他场景的请求为当前普通库存提供证据。
    if (orderNo == null
        || orderNo.isBlank()
        || !Objects.equals(orderNo, requestRow.get("order_no"))
        || !"NORMAL".equals(requestRow.get("scene"))
        || !(requestRow.get("items_snapshot") instanceof String snapshot)
        || snapshot.isBlank()
        || !(requestRow.get("items_hash") instanceof String persistedHash)) return false;
    try {
      // 2. 仅在独立副本启用严格解析；不得改变整个服务的JSON请求/序列化策略。
      JsonNode root =
          mapper
              .copy()
              .enable(
                  DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                  DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(snapshot);
      if (root == null || !root.isArray() || root.isEmpty()) return false;
      List<StockLineDTO> lines = new ArrayList<>();
      Map<Long, Integer> expectedQuantities = new HashMap<>();
      long previousSkuId = 0;
      for (JsonNode entry : root) {
        if (!entry.isObject() || entry.size() != 2) return false;
        JsonNode skuNode = entry.get("skuId");
        JsonNode quantityNode = entry.get("quantity");
        if (skuNode == null
            || !skuNode.isIntegralNumber()
            || !skuNode.canConvertToLong()
            || quantityNode == null
            || !quantityNode.isIntegralNumber()
            || !quantityNode.canConvertToInt()) return false;
        long skuId = skuNode.longValue();
        int quantity = quantityNode.intValue();
        if (skuId <= previousSkuId || quantity <= 0) return false;
        previousSkuId = skuId;
        lines.add(new StockLineDTO(skuId, quantity));
        expectedQuantities.put(skuId, quantity);
      }
      // 3. 重用原DTO序列化规则，不能对读取时带空白/重排key的JSON直接hash。
      if (!sha256(toJson(lines)).equals(persistedHash)) return false;
      // 4. 消费完整原集合，缺/多/重复SKU或数量不一致都不能成为终态证据。
      if (expectedQuantities.size() != observedItems.size()) return false;
      for (StockReservationItemVO item : observedItems) {
        if (!Objects.equals(expectedQuantities.remove(item.skuId), item.quantity)) return false;
      }
      return expectedQuantities.isEmpty();
    } catch (JsonProcessingException | BizException malformedSnapshot) {
      return false;
    }
  }

  /** JDBC整数/DECIMAL以精确值处理，不接收浮点、文本或null来补造库存事实。 */
  private static BigDecimal exactReservationNumber(Object value) {
    if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
      throw new IllegalArgumentException("普通库存流水数值不可信");
    return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
  }

  /** 缺证不得回显看似终态的部分明细；不删除或修改任何原流水。 */
  private static StockReservationVO unknownReservationProof(String orderNo) {
    return new StockReservationVO(orderNo, "UNKNOWN", List.of());
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
    ApiResponse<StockReservationVO> response;
    try {
      response = reservationResult(orderNo);
    } catch (RuntimeException unavailableProof) {
      throw reservationProofUnavailable();
    }
    if (response == null || response.data == null || !"RESERVED".equals(response.data.status))
      throw reservationProofUnavailable();
    response.data.status = "ALREADY_RESERVED";
    return response;
  }

  /** 重放证据缺失保持原事实，不返回底层错误或强制ACK成功。 */
  private static BizException reservationProofUnavailable() {
    return new BizException("STOCK_RESERVATION_PROOF_UNAVAILABLE", "库存预扣持久事实待核验", 503);
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
    if (idempotencyKey.isBlank()
        || idempotencyKey.codePointCount(0, idempotencyKey.length()) > 128) {
      throw new BizException(ErrorCodes.INVALID, "秒杀幂等键不能为空且不能超过128个字符", 400);
    }
    Entry sentinelEntry;
    try {
      sentinelEntry = SeckillSentinelConfiguration.enter();
    } catch (BlockException blocked) {
      SeckillSentinelConfiguration.recordBlock();
      throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
    }
    try {
      return seckillAfterSentinel(request, userId, activityId, skuId, requestUserId, idempotencyKey);
    } finally {
      sentinelEntry.exit();
    }
  }

  /** Sentinel 放行后才进入指纹、持久认领、Redis 预扣与 outbox 受理流程。 */
  private ApiResponse<?> seckillAfterSentinel(SeckillReservationDTO request, long userId,
      String activityId, String skuId, String requestUserId, String idempotencyKey) {
    String requestHash;
    try {
      requestHash =
          SeckillRequestFingerprint.hash(
              request.activityId, request.skuId, userId, 1, request.addressSnapshot);
    } catch (IllegalArgumentException invalidFingerprint) {
      throw new BizException(ErrorCodes.INVALID, invalidFingerprint.getMessage(), 400);
    }
    OffsetDateTime claimedAt =
        SeckillEventTimeResolver.normalize(OffsetDateTime.now(seckillClaimClock));
    String candidateOrderNo =
        claimedAt.format(DateTimeFormatter.ofPattern("yyyyMM"))
            + UUID.randomUUID().toString().replace("-", "");
    String candidatePayload = toSeckillPayload(request, candidateOrderNo, claimedAt);
    Map<String, Object> claimed =
        seckillRequestClaimService.claim(
            request.activityId,
            request.skuId,
            userId,
            candidateOrderNo,
            request.idempotencyKey,
            candidatePayload,
            requestHash,
            claimedAt);
    if (!requestHash.equals(String.valueOf(claimed.get("request_hash"))))
      throw new BizException("SECKILL_IDEMPOTENCY_CONFLICT", "秒杀幂等键载荷已被占用", 409);
    String orderNo = String.valueOf(claimed.get("order_no"));
    if (orderNo.isBlank() || "null".equals(orderNo))
      throw new BizException("SECKILL_CLAIM_CONFLICT", "秒杀请求认领事实无效", 409);
    String claimedStatus = String.valueOf(claimed.get("status"));
    if ("ACCEPTED".equals(claimedStatus)) {
      return acceptedOrRecovering(claimed, orderNo, requestHash);
    }
    if ("REJECTED".equals(claimedStatus)) {
      return ApiResponse.ok(Map.of("accepted", false, "orderNo", orderNo, "eventId",
          SeckillRequestFingerprint.eventId(orderNo), "reservationStatus", claimedStatus));
    }
    String requestPayload = String.valueOf(claimed.get("request_payload"));
    if (requestPayload.isBlank() || "null".equals(requestPayload)
        || !payloadMatchesOrder(requestPayload, orderNo))
      return recoveryRequired(orderNo, SeckillRequestFingerprint.eventId(orderNo),
          "SECKILL_CLAIM_PAYLOAD_INVALID");
    Map<Object, Object> meta =
        redisOperation(
            () -> {
              Map<Object, Object> metadata =
                  redis.opsForHash().entries("seckill:meta:" + activityId + ":" + skuId);
              if (metadata == null
                  || !metadata.containsKey("stockLimit")
                  || !metadata.containsKey("perUserLimit"))
                throw StockDependencyGuard.redisProtocolFailure();
              long beginsAt = epoch(metadata.get("startAt"));
              long endsAt = epoch(metadata.get("endAt"));
              try {
                if (beginsAt <= 0
                    || endsAt <= beginsAt
                    || Integer.parseInt(String.valueOf(metadata.get("stockLimit"))) < 1
                    || Integer.parseInt(String.valueOf(metadata.get("perUserLimit"))) < 1)
                  throw StockDependencyGuard.redisProtocolFailure();
              } catch (NumberFormatException invalidMetadata) {
                throw StockDependencyGuard.redisProtocolFailure();
              }
              return metadata;
            });
    if (meta == null || !meta.containsKey("stockLimit") || !meta.containsKey("perUserLimit"))
      throw redisUnavailable();
    long start = epoch(meta.get("startAt")), end = epoch(meta.get("endAt"));
    if (start <= 0 || end <= start) throw redisUnavailable();
    int limit;
    int stockLimit;
    try {
      limit = Integer.parseInt(String.valueOf(meta.get("perUserLimit")));
      stockLimit = Integer.parseInt(String.valueOf(meta.get("stockLimit")));
    } catch (NumberFormatException invalidLimit) {
      throw redisUnavailable();
    }
    if (limit < 1 || stockLimit < 1) throw redisUnavailable();
    long ttl = Math.max(1, (end - System.currentTimeMillis() / 1000));
    String orderKey = "seckill:accepted:" + activityId + ":" + skuId + ":"
        + requestUserId + ":" + idempotencyKey;
    List<String> keys =
        List.of(
            "seckill:stock:" + activityId + ":" + skuId,
            "seckill:user:" + activityId + ":" + requestUserId,
            orderKey,
            "seckill:quota:op:" + orderNo,
            "seckill:quota:revision:" + activityId + ":" + skuId);
    Long result =
        redisOperation(
            () ->
                checkedSeckillLua(
                    redis.execute(
                        seckillScript,
                        keys,
                        orderNo,
                        String.valueOf(limit),
                        String.valueOf(System.currentTimeMillis() / 1000),
                        String.valueOf(start),
                        String.valueOf(end),
                        String.valueOf(ttl),
                        String.valueOf(stockLimit))));
    if (Objects.equals(result, 3L)) {
      String existingOrderNo = redisOperation(() -> checkedOrderMarker(orderKey, orderNo));
      if (!orderNo.equals(existingOrderNo)) throw redisUnavailable();
      return acceptAfterLua(
          userId, idempotencyKey, existingOrderNo, requestPayload, requestHash);
    }
    if (Objects.equals(result, 2L)) {
      markSeckillRejected(
          Long.parseLong(activityId), Long.parseLong(skuId), userId, idempotencyKey);
      throw new BizException("SECKILL_LIMIT_REACHED", "超过用户限购", 429);
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
    if (!Objects.equals(result, 1L)) throw redisUnavailable();
    String confirmedOrderNo = redisOperation(() -> checkedOrderMarker(orderKey, orderNo));
    if (!orderNo.equals(confirmedOrderNo)) throw redisUnavailable();
    return acceptAfterLua(userId, idempotencyKey, confirmedOrderNo, requestPayload, requestHash);
  }

  /** 只把 Redis 读写异常映射为依赖故障，保留 MySQL 错误的真实归属。 */
  private <T> T redisOperation(Supplier<T> operation) {
    return StockDependencyGuard.redis(StockDependencyGuard.REDIS_SECKILL, operation);
  }

  /** 非业务 Lua 返回值在同一个调用 Entry 内记协议异常，原认领保留等待核验。 */
  private static Long checkedSeckillLua(Long result) {
    if (result == null || result < 0 || result > 5)
      throw StockDependencyGuard.redisProtocolFailure();
    return result;
  }

  /** 原受理 marker 必须指向同一订单号，不能在响应未知时重新认领或伪装售罄。 */
  private String checkedOrderMarker(String markerKey, String orderNo) {
    String marker = redis.opsForValue().get(markerKey);
    if (!orderNo.equals(marker)) throw StockDependencyGuard.redisProtocolFailure();
    return marker;
  }

  /** 认领行中的不可变载荷必须指向同一原订单号。 */
  private boolean payloadMatchesOrder(String requestPayload, String orderNo) {
    try {
      return orderNo.equals(mapper.readTree(requestPayload).path("orderNo").asText());
    } catch (Exception malformedPayload) {
      return false;
    }
  }

  /** Redis 结果无法确认时保留原幂等键供安全重试。 */
  private static BizException redisUnavailable() {
    return new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
  }

  /** 执行 recordSeckillPending 相关操作。 */
  private void recordSeckillPending(
      long activityId, long skuId, long userId, String idempotencyKey, String orderNo,
      String requestPayload, String requestHash) {
    // 1. 接收并整理 recordSeckillPending 的业务请求。
    // 2. 执行 recordSeckillPending 的核心业务校验与状态处理。
    // 3. 返回 recordSeckillPending 的处理结果。
    OffsetDateTime now = now();
    stockSqlMapper.saveSeckillPending(
        activityId, skuId, userId, orderNo, idempotencyKey, requestPayload, requestHash, now);
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
  private ApiResponse<?> accepted(String no, String reservationStatus, String eventId) {
    // 1. 接收并整理 accepted 的业务请求。
    // 2. 执行 accepted 的核心业务校验与状态处理。
    // 3. 返回 accepted 的处理结果。
    return ApiResponse.ok(Map.of("accepted", true, "orderNo", no, "eventId", eventId,
        "reservationStatus", reservationStatus, "status", "PENDING_PAYMENT"));
  }

  /** 旧版 ACCEPTED 记录必须先确认同一 eventId 的 outbox 已持久化。 */
  private ApiResponse<?> acceptedOrRecovering(
      Map<String, Object> claimed, String orderNo, String requestHash) {
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    try {
      Map<String, Object> outbox = stockSqlMapper.findSeckillOutboxByOrderNo(orderNo);
      if (outbox != null) {
        if (!eventId.equals(String.valueOf(outbox.get("event_id")))
            || !requestHash.equals(String.valueOf(outbox.get("request_hash")))) {
          return recoveryRequired(orderNo, eventId, "SECKILL_OUTBOX_EVENT_CONFLICT");
        }
        return accepted(orderNo, "ACCEPTED", eventId);
      }
      String payload = String.valueOf(claimed.get("request_payload"));
      if (payload.isBlank() || "null".equals(payload)) {
        return recoveryRequired(orderNo, eventId, "SECKILL_ACCEPTED_PAYLOAD_MISSING");
      }
      Map<String, Object> parsed =
          mapper.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      if (!orderNo.equals(String.valueOf(parsed.get("orderNo")))) {
        return recoveryRequired(orderNo, eventId, "SECKILL_ACCEPTED_PAYLOAD_CONFLICT");
      }
      if (!requestHash.equals(String.valueOf(claimed.get("request_hash")))) {
        return recoveryRequired(orderNo, eventId, "SECKILL_ACCEPTED_HASH_CONFLICT");
      }
      if (stockSqlMapper.insertSeckillOutboxIfAbsent(
              eventId,
              orderNo,
              String.valueOf(claimed.get("idempotency_key")),
              payload,
              requestHash,
              now())
          != 1) {
        return recoveryRequired(orderNo, eventId, "SECKILL_OUTBOX_RECOVERY_PENDING");
      }
      return accepted(orderNo, "ACCEPTED", eventId);
    } catch (Exception recoveryFailure) {
      log.warn("旧版 ACCEPTED 秒杀记录无法恢复 outbox，orderNo={}", orderNo, recoveryFailure);
      return recoveryRequired(orderNo, eventId, "SECKILL_OUTBOX_RECOVERY_PENDING");
    }
  }

  private ApiResponse<?> recoveryRequired(String orderNo, String eventId, String reason) {
    return ApiResponse.ok(
        Map.of(
            "accepted", false,
            "orderNo", orderNo,
            "eventId", eventId,
            "reservationStatus", "RECOVERY_REQUIRED",
            "status", "ORDER_RECOVERY_PENDING",
            "reason", reason));
  }

  /** 在 Redis Lua 成功后以同一 eventId 提交受理台账和 outbox。 */
  private ApiResponse<?> acceptAfterLua(
      long userId, String idempotencyKey, String orderNo, String payload, String requestHash) {
    try {
      String eventId = SeckillRequestFingerprint.eventId(orderNo);
      seckillAcceptanceService.accept(
          userId, idempotencyKey, orderNo, eventId, payload, requestHash, now());
      return accepted(orderNo, "ACCEPTED", eventId);
    } catch (RuntimeException persistenceFailure) {
      // acceptance 失败可能只是事务响应丢失；先读取 durable 事实，禁止盲目回补并发请求已扣的配额。
      try {
        Map<String, Object> reservation = stockSqlMapper.findSeckillByOrderNo(orderNo);
        Map<String, Object> outbox = stockSqlMapper.findSeckillOutboxByOrderNo(orderNo);
        String eventId = SeckillRequestFingerprint.eventId(orderNo);
        if (reservation != null
            && "ACCEPTED".equals(String.valueOf(reservation.get("status")))
            && outbox != null
            && eventId.equals(String.valueOf(outbox.get("event_id")))
            && requestHash.equals(String.valueOf(outbox.get("request_hash")))) {
          return accepted(orderNo, "ACCEPTED", eventId);
        }
      } catch (RuntimeException durableLookupFailure) {
        log.warn("秒杀受理结果查询失败，保留 Redis marker 等待恢复，orderNo={}", orderNo, durableLookupFailure);
      }
      try {
        stockSqlMapper.markSeckillUnknown(
            userId, idempotencyKey, "ACCEPTANCE_RESULT_UNKNOWN_REDIS_MARKER_RETAINED", now());
      } catch (RuntimeException ledgerFailure) {
        log.error("秒杀未知受理结果无法写入台账，orderNo={}", orderNo, ledgerFailure);
      }
      return recoveryRequired(
          orderNo, SeckillRequestFingerprint.eventId(orderNo), "SECKILL_ACCEPTANCE_RESULT_UNKNOWN");
    }
  }

  /** 保存可在进程重启后重建事件的完整受理载荷。 */
  private String toSeckillPayload(
      SeckillReservationDTO request, String orderNo, OffsetDateTime claimedAt) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("activityId", request.activityId);
      payload.put("skuId", request.skuId);
      payload.put("userId", request.userId);
      payload.put("quantity", 1);
      payload.put("idempotencyKey", request.idempotencyKey);
      payload.put("addressSnapshot", request.addressSnapshot);
      payload.put("orderNo", orderNo);
      payload.put("occurredAt", claimedAt.toString());
      return mapper.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
      throw new BizException(ErrorCodes.INVALID, "秒杀请求无法序列化", 400);
    }
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
    Map<String, Object> durableStock =
        StockDependencyGuard.call(
            StockDependencyGuard.MYSQL_READ, () -> stockSqlMapper.findStock(sku));
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
