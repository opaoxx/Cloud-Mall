package com.cloudmall.stock.service.impl;

import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/** 秒杀事件创建时间的纯事实核验，不读取时钟、数据库或修改历史载荷。 */
public final class SeckillEventTimeResolver {
  /** 项目固定的上海业务偏移。 */
  private static final ZoneOffset BUSINESS_OFFSET = ZoneOffset.ofHours(8);

  /** 订单号中的物理月份格式。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 已持久化受理事实才允许发送订单事件。 */
  private static final Set<String> ACCEPTED_STATES =
      Set.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED");

  private SeckillEventTimeResolver() {}

  /**
   * 规范首次认领时间，与订单消费者一致地转为上海毫秒，避免数据库舍入跨月。
   *
   * @param timestamp 首次采样的时间
   * @return 合法 DATETIME(3) 上海时间
   * @throws IllegalArgumentException 时间为空、转换越界或数据库年份非法
   */
  public static OffsetDateTime normalize(OffsetDateTime timestamp) {
    if (timestamp == null) throw new IllegalArgumentException("SECKILL_EVENT_TIME_INVALID");
    try {
      OffsetDateTime normalized =
          timestamp.withOffsetSameInstant(BUSINESS_OFFSET).truncatedTo(ChronoUnit.MILLIS);
      if (normalized.getYear() < 1000 || normalized.getYear() > 9999) {
        throw new IllegalArgumentException("SECKILL_EVENT_TIME_INVALID");
      }
      return normalized;
    } catch (DateTimeException failure) {
      throw new IllegalArgumentException("SECKILL_EVENT_TIME_INVALID", failure);
    }
  }

  /**
   * 核对 outbox、受理记录和载荷归属后返回不随投递重试变化的创建时间。
   *
   * @param outbox 本次认领的 outbox 行，包含原事件号/订单号/幂等键/哈希
   * @param payload outbox 中反序列化的历史 JSON，已有事件号/指纹必须匹配，不在此修改
   * @param reservation 同订单号唯一受理记录，含持久 created_at
   * @return 已核验的持久时间；旧载荷缺字段时仅供调用方补发送 envelope
   * @throws IllegalStateException 证据不足或冲突；调用方应保留原事件并退避，不发布
   */
  public static OffsetDateTime resolve(
      Map<String, Object> outbox, Map<String, Object> payload, Map<String, Object> reservation) {
    // 1. 完整核对原号、事件、键、状态与三方归属，不用 outbox 调度时间替代创建时间。
    if (outbox == null || payload == null || reservation == null) throw unverified("missing facts");
    String orderNo = text(outbox, "order_no");
    if (!orderNo.matches("[1-9][0-9]{3}(0[1-9]|1[0-2])[A-Za-z0-9-]+")
        || !orderNo.equals(text(reservation, "order_no"))
        || !orderNo.equals(text(payload, "orderNo"))
        || !SeckillRequestFingerprint.eventId(orderNo).equals(text(outbox, "event_id"))) {
      throw unverified("order or event mismatch");
    }
    String key = text(outbox, "idempotency_key");
    String hash = text(outbox, "request_hash");
    if (!key.equals(text(reservation, "idempotency_key"))
        || !key.equals(text(payload, "idempotencyKey"))
        || !hash.equals(text(reservation, "request_hash"))
        || !ACCEPTED_STATES.contains(text(reservation, "status"))) {
      throw unverified("claim identity or state mismatch");
    }
    long activityId = number(payload, "activityId");
    long skuId = number(payload, "skuId");
    long userId = number(payload, "userId");
    if (activityId != number(reservation, "activity_id")
        || skuId != number(reservation, "sku_id")
        || userId != number(reservation, "user_id")
        || number(payload, "quantity") != 1) {
      throw unverified("payload ownership mismatch");
    }
    try {
      if (!hash.equals(
          SeckillRequestFingerprint.hash(
              activityId, skuId, userId, 1, text(payload, "addressSnapshot")))) {
        throw unverified("payload hash mismatch");
      }
    } catch (IllegalArgumentException invalidPayload) {
      throw unverified("invalid payload fingerprint");
    }

    // 缺字段沿用旧载荷兼容，但显式字段不能被 envelope 覆盖后掩盖冲突。
    if (payload.containsKey("eventId")
        && !text(outbox, "event_id").equals(text(payload, "eventId"))) {
      throw unverified("explicit payload event mismatch");
    }
    if (payload.containsKey("requestHash")
        && !hash.equalsIgnoreCase(text(payload, "requestHash"))) {
      throw unverified("explicit payload hash mismatch");
    }

    // 2. 受理表 DATETIME 本地字段必须已是合法毫秒，且属于原订单号的月份。
    Object raw = reservation.get("created_at");
    LocalDateTime local;
    if (raw instanceof LocalDateTime dateTime) local = dateTime;
    else if (raw instanceof Timestamp timestamp) local = timestamp.toLocalDateTime();
    else throw unverified("missing or unsupported claim time");
    if (local.getYear() < 1000
        || local.getYear() > 9999
        || local.getNano() % 1_000_000 != 0
        || !local.format(MONTH).equals(orderNo.substring(0, 6))) {
      throw unverified("claim time precision or month mismatch");
    }
    OffsetDateTime persisted = local.atOffset(BUSINESS_OFFSET);

    // 3. 显式字段必须和持久事实一致；只有字段确实不存在才允许旧载荷兼容补值。
    if (payload.containsKey("occurredAt")) {
      try {
        OffsetDateTime explicit = normalize(OffsetDateTime.parse(text(payload, "occurredAt")));
        if (!persisted.equals(explicit)) throw unverified("explicit time conflicts with claim");
      } catch (IllegalArgumentException invalidTime) {
        throw unverified("invalid explicit time");
      } catch (DateTimeException invalidTime) {
        throw unverified("invalid explicit time");
      }
    }
    return persisted;
  }

  private static String text(Map<String, Object> facts, String key) {
    Object value = facts.get(key);
    if (!(value instanceof String text) || text.isBlank()) throw unverified("missing " + key);
    return text;
  }

  private static long number(Map<String, Object> facts, String key) {
    try {
      return Long.parseLong(String.valueOf(facts.get(key)));
    } catch (NumberFormatException invalidNumber) {
      throw unverified("invalid " + key);
    }
  }

  private static IllegalStateException unverified(String reason) {
    return new IllegalStateException("SECKILL_EVENT_TIME_UNVERIFIED: " + reason);
  }
}
