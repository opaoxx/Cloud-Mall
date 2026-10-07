package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.config.MonthlyOrderShardingAlgorithm;
import com.cloudmall.order.util.OrderCreationTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.shardingsphere.api.sharding.standard.PreciseShardingValue;
import org.junit.jupiter.api.Test;

/** 创建时间先统一瞬时解释、再向下取毫秒，不允许数据库舍入跨月。 */
class OrderCreationTimeTest {
  @Test
  void yearEndRemainsInOriginalMonthAfterTruncation() {
    assertEquals(
        OffsetDateTime.parse("2026-12-31T23:59:59.999+08:00"),
        OrderCreationTime.normalize(OffsetDateTime.parse("2026-12-31T23:59:59.999999999+08:00")));
  }

  @Test
  void utcAndOtherOffsetsUseSameInstantBeforeTruncation() {
    OffsetDateTime expected = OffsetDateTime.parse("2027-01-01T00:00:00+08:00");
    assertEquals(
        expected, OrderCreationTime.normalize(OffsetDateTime.parse("2026-12-31T16:00:00.000900Z")));
    assertEquals(
        expected,
        OrderCreationTime.normalize(OffsetDateTime.parse("2027-01-01T01:00:00.000900+09:00")));
    assertEquals(
        OffsetDateTime.parse("2026-12-31T23:30:00+08:00"),
        OrderCreationTime.normalize(OffsetDateTime.parse("2027-01-01T00:30:00+09:00")));
  }

  @Test
  void exactMillisecondIsUnchangedAndNormalizationIsIdempotent() {
    OffsetDateTime exact = OffsetDateTime.parse("2027-01-01T00:00:00.123+08:00");
    assertEquals(exact, OrderCreationTime.normalize(exact));
    OffsetDateTime normalized = OrderCreationTime.normalize(exact.plusNanos(900_000));
    assertEquals(normalized, OrderCreationTime.normalize(normalized));
  }

  @Test
  void truncationNeverMovesForwardAndChangesLessThanOneMillisecond() {
    OffsetDateTime origin = OffsetDateTime.parse("2026-12-31T15:59:59Z");
    for (int nanos : new int[] {0, 1, 999_999, 1_000_000, 500_100_999, 999_500_000, 999_999_999}) {
      OffsetDateTime input = origin.withNano(nanos);
      OffsetDateTime normalized = OrderCreationTime.normalize(input);
      long lostNanos = Duration.between(normalized.toInstant(), input.toInstant()).toNanos();
      assertTrue(lostNanos >= 0 && lostNanos < 1_000_000);
      assertEquals(0, normalized.getNano() % 1_000_000);
      assertEquals(ZoneOffset.ofHours(8), normalized.getOffset());
    }
  }

  @Test
  void invalidDatabaseYearsAndConversionOverflowFailExplicitly() {
    for (OffsetDateTime input :
        new OffsetDateTime[] {
          null,
          OffsetDateTime.parse("0999-12-31T00:00:00+08:00"),
          OffsetDateTime.parse("9999-12-31T23:00:00-01:00"),
          OffsetDateTime.MAX
        }) {
      assertEquals(
          "ORDER_CREATION_TIME_INVALID",
          assertThrows(IllegalArgumentException.class, () -> OrderCreationTime.normalize(input))
              .getMessage());
    }
    assertEquals(
        1000,
        OrderCreationTime.normalize(OffsetDateTime.parse("1000-01-01T00:00:00+08:00")).getYear());
    assertEquals(
        9999,
        OrderCreationTime.normalize(OffsetDateTime.parse("9999-12-31T23:59:59.999999+08:00"))
            .getYear());
  }

  @Test
  void normalizedCreationAndShardingAlgorithmChooseSameMonth() {
    OffsetDateTime created =
        OrderCreationTime.normalize(OffsetDateTime.parse("2026-12-31T23:59:59.999500+08:00"));
    assertEquals(
        "mall_order_202612",
        new MonthlyOrderShardingAlgorithm()
            .doSharding(
                List.of("mall_order_202612", "mall_order_202701"),
                new PreciseShardingValue<>("mall_order", "created_at", created.toLocalDateTime())));
  }
}
