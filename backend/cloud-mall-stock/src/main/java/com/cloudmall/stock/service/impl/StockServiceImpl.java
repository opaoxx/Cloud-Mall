package com.cloudmall.stock.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/stock")
public class StockServiceImpl implements StockService {
  /** 保存 PREFIX 的业务状态或配置。 */
  private static final String PREFIX = "stock:available:", RES = "stock:reservation:";

  private static final String RESERVE =
      "if redis.call('EXISTS',KEYS[1])==1 then return 2 end; for i=2,#KEYS-1 do if"
          + " tonumber(redis.call('GET',KEYS[i]) or '0') < tonumber(ARGV[(i-2)*2+2]) then return 0"
          + " end end; for i=2,#KEYS-1 do redis.call('DECRBY',KEYS[i],ARGV[(i-2)*2+2]);"
          + " redis.call('HSET',KEYS[#KEYS],ARGV[(i-2)*2+1],ARGV[(i-2)*2+2]) end;"
          + " redis.call('SET',KEYS[1],'RESERVED','EX',86400); return 1";
  private static final String ROLLBACK =
      "if redis.call('EXISTS',KEYS[1])==0 then return 0 end; for i=2,#KEYS do"
          + " redis.call('INCRBY',KEYS[i],ARGV[i-1]) end; redis.call('DEL',KEYS[1]); return 1";
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

  private final DefaultRedisScript<Long>
      reserveScript = new DefaultRedisScript<>(RESERVE, Long.class),
      rollbackScript = new DefaultRedisScript<>(ROLLBACK, Long.class),
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

  @Transactional
  @PostMapping("/reservations")
  /** 执行 reserve 相关操作。 */
  public ApiResponse<?> reserve(@RequestBody StockReservationDTO request) {
    // 1. 接收并整理 reserve 的业务请求。
    // 2. 执行 reserve 的核心业务校验与状态处理。
    // 3. 返回 reserve 的处理结果。
    validate(request);
    List<StockLineDTO> lines = merge(request.items);
    String key = RES + request.orderNo;
    List<String> keys = new ArrayList<>(List.of(key));
    keys.addAll(lines.stream().map(x -> PREFIX + x.skuId).toList());
    keys.add(key + ":lines");
    List<String> args = new ArrayList<>();
    for (StockLineDTO x : lines) {
      args.add(String.valueOf(x.skuId));
      args.add(String.valueOf(x.quantity));
    }
    Long result = redis.execute(reserveScript, keys, args.toArray());
    if (Objects.equals(result, 0L)) throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
    if (Objects.equals(result, 2L))
      return ApiResponse.ok(Map.of("reserved", true, "idempotent", true));
    try {
      for (StockLineDTO x : lines) {
        int changed = stockSqlMapper.reserveQuantity(x.skuId, x.quantity, now());
        if (changed != 1) throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
        flow(x, request.orderNo, "RESERVE", request.orderNo + ":RESERVE:" + x.skuId);
      }
    } catch (RuntimeException e) {
      compensateReservation(key, lines);
      throw e;
    }
    return ApiResponse.ok(Map.of("reserved", true, "idempotent", false));
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/confirm")
  /** 执行 confirm 相关操作。 */
  public ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 confirm 的业务请求。
    // 2. 执行 confirm 的核心业务校验与状态处理。
    // 3. 返回 confirm 的处理结果。
    Map<Object, Object> lines = redis.opsForHash().entries(RES + orderNo + ":lines");
    try {
      for (Map.Entry<Object, Object> e : lines.entrySet()) {
        long sku = Long.parseLong(String.valueOf(e.getKey()));
        int q = Integer.parseInt(String.valueOf(e.getValue()));
        String k = orderNo + ":CONFIRM:" + sku;
        if (!existsFlow(k)) {
          int changed = stockSqlMapper.confirmQuantity(sku, q, now());
          if (changed != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或不足", 409);
          flow(new StockLineDTO(sku, q), orderNo, "CONFIRM", k);
        }
      }
    } catch (RuntimeException e) {
      throw e;
    }
    redis.delete(RES + orderNo);
    redis.delete(RES + orderNo + ":lines");
    return ApiResponse.ok(Map.of("confirmed", true, "idempotent", lines.isEmpty()));
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/rollback")
  /** 执行 rollback 相关操作。 */
  public ApiResponse<?> rollback(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 rollback 的业务请求。
    // 2. 执行 rollback 的核心业务校验与状态处理。
    // 3. 返回 rollback 的处理结果。
    Map<Object, Object> lines = redis.opsForHash().entries(RES + orderNo + ":lines");
    for (Map.Entry<Object, Object> e : lines.entrySet()) {
      long sku = Long.parseLong(String.valueOf(e.getKey()));
      int q = Integer.parseInt(String.valueOf(e.getValue()));
      String k = orderNo + ":ROLLBACK:" + sku;
      if (!existsFlow(k)) {
        int changed = stockSqlMapper.rollbackQuantity(sku, q, now());
        if (changed != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或不足", 409);
        flow(new StockLineDTO(sku, q), orderNo, "ROLLBACK", k);
      }
    }
    redis.delete(RES + orderNo);
    redis.delete(RES + orderNo + ":lines");
    return ApiResponse.ok(Map.of("rolledBack", true, "idempotent", lines.isEmpty()));
  }

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

  /** 执行 existsFlow 相关操作。 */
  private boolean existsFlow(String k) {
    // 1. 接收并整理 existsFlow 的业务请求。
    // 2. 执行 existsFlow 的核心业务校验与状态处理。
    // 3. 返回 existsFlow 的处理结果。
    return stockSqlMapper.flowExists(k);
  }

  /** 执行 flow 相关操作。 */
  private void flow(StockLineDTO x, String order, String type, String key) {
    // 1. 接收并整理 flow 的业务请求。
    // 2. 执行 flow 的核心业务校验与状态处理。
    // 3. 返回 flow 的处理结果。
    stockSqlMapper.insertFlow(x.skuId, order, type, x.quantity, key, now());
  }

  /** 执行 compensateReservation 相关操作。 */
  private void compensateReservation(String key, List<StockLineDTO> lines) {
    // 1. 接收并整理 compensateReservation 的业务请求。
    // 2. 执行 compensateReservation 的核心业务校验与状态处理。
    // 3. 返回 compensateReservation 的处理结果。
    for (StockLineDTO x : lines) redis.opsForValue().increment(PREFIX + x.skuId, x.quantity);
    redis.delete(key);
    redis.delete(key + ":lines");
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
    String v = redis.opsForValue().get(PREFIX + sku);
    return v == null ? 0 : Integer.parseInt(v);
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
