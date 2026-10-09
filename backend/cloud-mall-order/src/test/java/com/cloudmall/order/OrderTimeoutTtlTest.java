package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.util.OrderTimeoutTtl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 订单超时消息的数学TTL边界；固定时长输入，不依赖墙钟或反射。 */
class OrderTimeoutTtlTest {
  @Test
  void positiveDurationsUseWholeCeilingMillisecondsWithoutShorteningTheRemainingTime() {
    Map<Duration, Long> cases =
        Map.ofEntries(
            Map.entry(Duration.ofNanos(1), 1L),
            Map.entry(Duration.ofNanos(999_999), 1L),
            Map.entry(Duration.ofMillis(1), 1L),
            Map.entry(Duration.ofNanos(1_000_001), 2L),
            Map.entry(Duration.ofNanos(1_999_999), 2L),
            Map.entry(Duration.ofNanos(999_999_999), 1000L),
            Map.entry(Duration.ofSeconds(1), 1000L),
            Map.entry(Duration.ofSeconds(1).plusNanos(1), 1001L),
            Map.entry(Duration.ofMinutes(30).plusNanos(1), 1_800_001L),
            Map.entry(Duration.ofMillis(Long.MAX_VALUE), Long.MAX_VALUE),
            Map.entry(Duration.ofMillis(Long.MAX_VALUE - 1).plusNanos(999_999), Long.MAX_VALUE),
            Map.entry(Duration.ofMillis(Long.MAX_VALUE - 1).plusNanos(1), Long.MAX_VALUE));
    List<Executable> checks = new ArrayList<>();
    for (var sample : cases.entrySet()) {
      checks.add(
          () ->
              assertEquals(
                  sample.getValue().longValue(),
                  OrderTimeoutTtl.remainingMillis(sample.getKey()),
                  sample.getKey().toString()));
    }
    assertAll(checks);
  }

  @Test
  void dueAndNegativeDurationsRemainZeroEvenBeyondTheMillisecondRange() {
    for (Duration duration :
        List.of(
            Duration.ZERO,
            Duration.ofNanos(-1),
            Duration.ofMillis(-1),
            Duration.ofSeconds(Long.MIN_VALUE))) {
      assertEquals(0L, OrderTimeoutTtl.remainingMillis(duration), duration.toString());
    }
    assertThrows(NullPointerException.class, () -> OrderTimeoutTtl.remainingMillis(null));
  }

  @Test
  void conversionAndCeilingOverflowRemainFailuresInsteadOfPretendingToBeDue() {
    assertThrows(
        ArithmeticException.class,
        () -> OrderTimeoutTtl.remainingMillis(Duration.ofSeconds(Long.MAX_VALUE)));
    assertThrows(
        ArithmeticException.class,
        () -> OrderTimeoutTtl.remainingMillis(Duration.ofMillis(Long.MAX_VALUE).plusNanos(1)));
  }
}
