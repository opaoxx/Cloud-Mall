package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.service.impl.SeckillEventTimeResolver;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 投递前的时间和不可变事实核验，避免将缺失证据当作当前时间。 */
class SeckillEventTimeResolverTest {
  @Test
  void explicitPayloadIdentityCannotBeSilentlyReplaced() {
    for (String field : List.of("eventId", "requestHash")) {
      for (Object invalid : new Object[] {null, "", " ", 123, "different"}) {
        Map<String, Object> payload = payload();
        payload.put(field, invalid);
        Map<String, Object> before = new LinkedHashMap<>(payload);
        assertUnverified(outbox(), payload, reservation());
        assertEquals(before, payload);
      }
    }
    for (String invalidEvent :
        List.of(
            SeckillRequestFingerprint.eventId(ORDER).toUpperCase(java.util.Locale.ROOT),
            SeckillRequestFingerprint.eventId(ORDER) + " ")) {
      Map<String, Object> payload = payload();
      payload.put("eventId", invalidEvent);
      assertUnverified(outbox(), payload, reservation());
    }
  }

  @Test
  void matchingExplicitMetadataAndAbsentLegacyFieldsRemainCompatible() {
    Map<String, Object> payload = payload();
    assertEquals(
        CREATED.atOffset(java.time.ZoneOffset.ofHours(8)),
        SeckillEventTimeResolver.resolve(outbox(), payload, reservation()));
    payload.put("eventId", SeckillRequestFingerprint.eventId(ORDER));
    payload.put("requestHash", HASH.toUpperCase(java.util.Locale.ROOT));
    Map<String, Object> before = new LinkedHashMap<>(payload);
    assertEquals(
        CREATED.atOffset(java.time.ZoneOffset.ofHours(8)),
        SeckillEventTimeResolver.resolve(outbox(), payload, reservation()));
    assertEquals(before, payload);
  }

  /** 同月份且已对齐毫秒的首次创建时间。 */
  private static final LocalDateTime CREATED = LocalDateTime.parse("2026-12-31T23:59:59.999");

  /** 原订单号和事件键。 */
  private static final String ORDER = "202612-original";

  /** 覆盖业务维度和原地址的标准指纹。 */
  private static final String HASH = SeckillRequestFingerprint.hash(1, 2, 3, 1, "{}");

  @Test
  void normalizeUsesShanghaiInstantAndFloorsRatherThanRounds() {
    assertEquals(
        OffsetDateTime.parse("2026-12-31T23:59:59.999+08:00"),
        SeckillEventTimeResolver.normalize(OffsetDateTime.parse("2026-12-31T15:59:59.999999Z")));
    for (OffsetDateTime invalid :
        new OffsetDateTime[] {
          null, OffsetDateTime.MAX, OffsetDateTime.parse("0999-12-31T00:00:00+08:00")
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> SeckillEventTimeResolver.normalize(invalid));
    }
  }

  @Test
  void legacyMissingFieldUsesOnlyPersistentClaimAndDoesNotMutateMaps() {
    Map<String, Object> payload = payload();
    Map<String, Object> original = new LinkedHashMap<>(payload);
    assertEquals(
        CREATED.atOffset(java.time.ZoneOffset.ofHours(8)),
        SeckillEventTimeResolver.resolve(outbox(), payload, reservation()));
    assertEquals(original, payload);
    assertFalse(payload.containsKey("occurredAt"));
  }

  @Test
  void explicitUtcAndTimestampFactsProduceCanonicalPersistentTime() {
    Map<String, Object> payload = payload();
    payload.put("occurredAt", "2026-12-31T15:59:59.999Z");
    Map<String, Object> reservation = reservation();
    reservation.put("created_at", Timestamp.valueOf(CREATED));
    assertEquals(
        CREATED.atOffset(java.time.ZoneOffset.ofHours(8)),
        SeckillEventTimeResolver.resolve(outbox(), payload, reservation));
  }

  @Test
  void explicitNullBlankMalformedOrConflictingTimeNeverFallsBack() {
    for (Object invalid : new Object[] {null, "", "not-a-time", "2027-01-01T00:00:00+08:00", 12}) {
      Map<String, Object> payload = payload();
      payload.put("occurredAt", invalid);
      assertUnverified(outbox(), payload, reservation());
    }
  }

  @Test
  void untrustedClaimDatesAndMissingReservationAreRejected() {
    assertUnverified(outbox(), payload(), null);
    for (Object invalid :
        new Object[] {
          null,
          CREATED.toString(),
          CREATED.plusNanos(1),
          CREATED.plusDays(1),
          LocalDateTime.of(999, 12, 31, 23, 59)
        }) {
      Map<String, Object> reservation = reservation();
      reservation.put("created_at", invalid);
      assertUnverified(outbox(), payload(), reservation);
    }
  }

  @Test
  void everyImmutableIdentityDimensionAndFingerprintMustAgree() {
    for (String field : List.of("order_no", "event_id", "idempotency_key", "request_hash")) {
      Map<String, Object> outbox = outbox();
      outbox.put(field, "different");
      assertUnverified(outbox, payload(), reservation());
    }
    for (String field :
        List.of(
            "orderNo",
            "activityId",
            "skuId",
            "userId",
            "quantity",
            "idempotencyKey",
            "addressSnapshot")) {
      Map<String, Object> payload = payload();
      payload.put(field, "different");
      assertUnverified(outbox(), payload, reservation());
    }
    Map<String, Object> payload = payload();
    payload.put("addressSnapshot", "{\"changed\":true}");
    assertUnverified(outbox(), payload, reservation());
  }

  @Test
  void onlyCommittedAcceptanceStatesMayPublish() {
    for (String state : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED")) {
      Map<String, Object> reservation = reservation();
      reservation.put("status", state);
      assertNotNull(SeckillEventTimeResolver.resolve(outbox(), payload(), reservation));
    }
    for (String state : List.of("PENDING", "UNKNOWN", "REJECTED", "")) {
      Map<String, Object> reservation = reservation();
      reservation.put("status", state);
      assertUnverified(outbox(), payload(), reservation);
    }
  }

  private Map<String, Object> outbox() {
    return new LinkedHashMap<>(
        Map.of(
            "order_no",
            ORDER,
            "event_id",
            SeckillRequestFingerprint.eventId(ORDER),
            "idempotency_key",
            "key",
            "request_hash",
            HASH));
  }

  private Map<String, Object> payload() {
    return new LinkedHashMap<>(
        Map.of(
            "orderNo",
            ORDER,
            "activityId",
            1L,
            "skuId",
            2L,
            "userId",
            3L,
            "quantity",
            1,
            "idempotencyKey",
            "key",
            "addressSnapshot",
            "{}"));
  }

  private Map<String, Object> reservation() {
    return new LinkedHashMap<>(
        Map.of(
            "order_no",
            ORDER,
            "activity_id",
            1L,
            "sku_id",
            2L,
            "user_id",
            3L,
            "idempotency_key",
            "key",
            "request_hash",
            HASH,
            "status",
            "ACCEPTED",
            "created_at",
            CREATED));
  }

  private void assertUnverified(
      Map<String, Object> outbox, Map<String, Object> payload, Map<String, Object> reservation) {
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> SeckillEventTimeResolver.resolve(outbox, payload, reservation))
            .getMessage()
            .startsWith("SECKILL_EVENT_TIME_UNVERIFIED:"));
  }
}
