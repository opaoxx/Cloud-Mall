package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import com.cloudmall.order.service.impl.StockCircuitOpenException;
import feign.FeignException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Stock 业务拒绝不驱动熔断，系统异常和慢调用会打开依赖保护。 */
class OrderStockCircuitBreakerTest {
  /** 避免 Sentinel 静态规则污染其它测试。 */
  @AfterEach
  void clearRules() {
    DegradeRuleManager.loadRules(List.of());
  }

  /** 明确 409 业务结果不能被当成 Stock 系统异常。 */
  @Test
  void businessConflictDoesNotOpenExceptionBreaker() {
    DegradeRuleManager.loadRules(List.of(errorRule(OrderStockCircuitBreaker.SECKILL)));
    FeignException conflict = mock(FeignException.class);
    when(conflict.status()).thenReturn(409);
    when(conflict.contentUTF8()).thenReturn("{\"code\":\"SECKILL_NOT_STARTED\"}");

    for (int attempt = 0; attempt < 8; attempt++) {
      assertThrows(FeignException.class,
          () -> OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.SECKILL,
              () -> { throw conflict; }));
    }
    assertEquals("0", OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.SECKILL,
        () -> ApiResponse.ok("allowed")).code);
  }

  /** 连续 503 在达到最低样本数后阻断 Feign，阻断本身不发出远程请求。 */
  @Test
  void systemFailuresOpenBreakerBeforeNextRemoteCall() {
    DegradeRuleManager.loadRules(List.of(errorRule(OrderStockCircuitBreaker.STATUS)));
    FeignException unavailable = mock(FeignException.class);
    when(unavailable.status()).thenReturn(503);
    AtomicInteger calls = new AtomicInteger();
    boolean opened = false;
    for (int attempt = 0; attempt < 12 && !opened; attempt++) {
      try {
        OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.STATUS, () -> {
          calls.incrementAndGet();
          throw unavailable;
        });
      } catch (StockCircuitOpenException blocked) {
        opened = true;
      } catch (FeignException expectedRemoteFailure) {
        // 本轮请求到达 Stock 后才成为 Sentinel 的异常统计。
      }
    }
    assertTrue(opened);
    assertTrue(calls.get() >= 2);
    assertFalse(calls.get() >= 12);
  }

  /** 普通预扣的慢调用比例到达阈值后，后续写请求在 Feign 前阻断。 */
  @Test
  void slowReserveCallsOpenBreakerWithoutASecondWriteAttempt() {
    DegradeRule slowRule = new DegradeRule(OrderStockCircuitBreaker.RESERVE)
        .setGrade(RuleConstant.DEGRADE_GRADE_RT).setCount(10)
        .setSlowRatioThreshold(0.5).setMinRequestAmount(2)
        .setStatIntervalMs(1000).setTimeWindow(2);
    DegradeRuleManager.loadRules(List.of(slowRule));
    AtomicInteger calls = new AtomicInteger();
    boolean opened = false;
    for (int attempt = 0; attempt < 8 && !opened; attempt++) {
      try {
        OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.RESERVE, () -> {
          calls.incrementAndGet();
          try {
            Thread.sleep(30L);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试慢调用被中断", interrupted);
          }
          return ApiResponse.ok("reserved");
        });
      } catch (StockCircuitOpenException blocked) {
        opened = true;
      }
    }
    assertTrue(opened);
    assertTrue(calls.get() >= 2);
    assertFalse(calls.get() >= 8);
  }

  /** 构造低样本门槛的测试用异常比例规则。 */
  private static DegradeRule errorRule(String resource) {
    return new DegradeRule(resource)
        .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
        .setCount(0.5).setMinRequestAmount(2).setStatIntervalMs(1000).setTimeWindow(2);
  }
}
