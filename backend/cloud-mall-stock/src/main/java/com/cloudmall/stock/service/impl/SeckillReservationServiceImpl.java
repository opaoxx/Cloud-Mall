package com.cloudmall.stock.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.config.SeckillSentinelConfiguration;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationService;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 秒杀请求认领、Redis配额受理与持久事件确认。 */
@Service
public class SeckillReservationServiceImpl implements SeckillReservationService {
  /** 记录受理结果未知和旧outbox恢复失败。 */
  private static final Logger log = LoggerFactory.getLogger(SeckillReservationServiceImpl.class);

  /** 首次认领的单次时钟，测试可注入跨月时刻。 */
  private final Clock seckillClaimClock;

  /** 秒杀元数据、配额Lua与原订单marker。 */
  private final StringRedisTemplate redis;

  /** 秒杀认领/outbox持久事实和结果未知状态。 */
  private final StockSqlMapper stockSqlMapper;

  /** 原受理事件载荷的JSON编解码。 */
  private final ObjectMapper mapper;

  /** 独立本地事务提交ACCEPTED、结算行与outbox。 */
  private final SeckillAcceptanceService seckillAcceptanceService;

  /** 独立事务认领用户幂等键和不可变请求指纹。 */
  private final SeckillRequestClaimService seckillRequestClaimService;

  /** 同时预扣配额并写入唯一回执的原Lua脚本。 */
  private final DefaultRedisScript<Long> seckillScript = new DefaultRedisScript<>();

  /** 创建使用本地业务时区时钟的秒杀受理入口。 */
  @org.springframework.beans.factory.annotation.Autowired
  public SeckillReservationServiceImpl(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      ObjectMapper mapper,
      SeckillAcceptanceService seckillAcceptanceService,
      SeckillRequestClaimService seckillRequestClaimService) {
    this(
        redis,
        stockSqlMapper,
        mapper,
        seckillAcceptanceService,
        seckillRequestClaimService,
        Clock.system(ZoneOffset.ofHours(8)));
  }

  /** 创建使用指定时钟的受理入口，保留原Lua资源和返回值类型。 */
  public SeckillReservationServiceImpl(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      ObjectMapper mapper,
      SeckillAcceptanceService seckillAcceptanceService,
      SeckillRequestClaimService seckillRequestClaimService,
      Clock seckillClaimClock) {
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
    this.mapper = mapper;
    this.seckillAcceptanceService = seckillAcceptanceService;
    this.seckillRequestClaimService = seckillRequestClaimService;
    this.seckillClaimClock = seckillClaimClock;
    seckillScript.setLocation(new ClassPathResource("lua/seckill-reserve.lua"));
    seckillScript.setResultType(Long.class);
  }

  /** 校验请求归属后进入限流、持久认领和Lua受理。 */
  @Override
  public ApiResponse<?> seckill(SeckillReservationDTO request, long userId) {
    // 1. 依次验证必填维度、身份归属及幂等键，尚不触及持久认领。
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
    // 2. 进入原秒杀限流资源；拒绝时不产生数据库或Redis副作用。
    Entry sentinelEntry;
    try {
      sentinelEntry = SeckillSentinelConfiguration.enter();
    } catch (BlockException blocked) {
      SeckillSentinelConfiguration.recordBlock();
      throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
    }
    // 3. 放行后执行受理链，任何返回或异常都释放当前Entry。
    try {
      return seckillAfterSentinel(
          request, userId, activityId, skuId, requestUserId, idempotencyKey);
    } finally {
      sentinelEntry.exit();
    }
  }

  /** Sentinel 放行后才进入指纹、持久认领、Redis 预扣与 outbox 受理流程。 */
  private ApiResponse<?> seckillAfterSentinel(
      SeckillReservationDTO request,
      long userId,
      String activityId,
      String skuId,
      String requestUserId,
      String idempotencyKey) {
    // 1. 计算不可变指纹，并从同一次时钟生成候选订单号与事件时间。
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
    // 2. 独立认领原幂等键，重放始终沿用已持久化的订单与载荷。
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
      return ApiResponse.ok(
          Map.of(
              "accepted",
              false,
              "orderNo",
              orderNo,
              "eventId",
              SeckillRequestFingerprint.eventId(orderNo),
              "reservationStatus",
              claimedStatus));
    }
    String requestPayload = String.valueOf(claimed.get("request_payload"));
    if (requestPayload.isBlank()
        || "null".equals(requestPayload)
        || !payloadMatchesOrder(requestPayload, orderNo))
      return recoveryRequired(
          orderNo, SeckillRequestFingerprint.eventId(orderNo), "SECKILL_CLAIM_PAYLOAD_INVALID");
    // 3. 核对Redis元数据后执行原子预扣；结果未知时保留marker供恢复。
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
    String orderKey =
        "seckill:accepted:" + activityId + ":" + skuId + ":" + requestUserId + ":" + idempotencyKey;
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
      return acceptAfterLua(userId, idempotencyKey, existingOrderNo, requestPayload, requestHash);
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

  /** 保留原认领身份并标记确定的秒杀业务拒绝。 */
  private void markSeckillRejected(
      long activityId, long skuId, long userId, String idempotencyKey) {
    stockSqlMapper.markSeckillRejected(activityId, skuId, userId, idempotencyKey, now());
  }

  /** 构造沿用原事件号和支付待处理状态的受理响应。 */
  private ApiResponse<?> accepted(String orderNo, String reservationStatus, String eventId) {
    return ApiResponse.ok(
        Map.of(
            "accepted",
            true,
            "orderNo",
            orderNo,
            "eventId",
            eventId,
            "reservationStatus",
            reservationStatus,
            "status",
            "PENDING_PAYMENT"));
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

  /** 保留原订单与事件身份，返回可重查的未知受理状态。 */
  private ApiResponse<?> recoveryRequired(String orderNo, String eventId, String reason) {
    return ApiResponse.ok(
        Map.of(
            "accepted",
            false,
            "orderNo",
            orderNo,
            "eventId",
            eventId,
            "reservationStatus",
            "RECOVERY_REQUIRED",
            "status",
            "ORDER_RECOVERY_PENDING",
            "reason",
            reason));
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

  /** 解析活动时间；非法原值返回零，由调用方按原协议拒绝。 */
  private static long epoch(Object value) {
    try {
      return Instant.parse(String.valueOf(value)).getEpochSecond();
    } catch (Exception invalidTime) {
      return 0;
    }
  }

  /** 读取Asia/Shanghai偏移下的当前业务时间。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }
}
