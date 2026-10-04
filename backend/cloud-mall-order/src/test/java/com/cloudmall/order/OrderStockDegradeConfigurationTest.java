package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.cloudmall.order.config.OrderStockDegradeConfiguration;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 四个 Order→Stock 资源的完整快照和 Nacos 故障兜底测试。 */
class OrderStockDegradeConfigurationTest {
  /** 清理 Sentinel 进程级规则，避免污染后续测试。 */
  @AfterEach
  void restoreRules() {
    DegradeRuleManager.loadRules(List.of());
  }

  /** 合法四资源快照仅替换自有规则，其它业务资源保留。 */
  @Test
  void validSnapshotReplacesOnlyOwnedResources() {
    DegradeRule other = new DegradeRule("unrelated-degrade");
    other.setGrade(1).setCount(0.5).setMinRequestAmount(5).setTimeWindow(5);
    DegradeRuleManager.loadRules(List.of(other));
    OrderStockDegradeConfiguration configuration = new OrderStockDegradeConfiguration();

    assertTrue(configuration.applyRules(validRules(0.3)));

    assertEquals(5, DegradeRuleManager.getRules().size());
    assertEquals(0.3, rule(OrderStockCircuitBreaker.SECKILL).getCount());
    assertEquals(3000.0, rule(OrderStockCircuitBreaker.RESERVE).getCount());
    assertEquals(0.5, rule("unrelated-degrade").getCount());
  }

  /** 空、未知字段、缺失、重复和错误比例都不能清除最近有效规则。 */
  @Test
  void invalidSnapshotKeepsLastValidRules() {
    OrderStockDegradeConfiguration configuration = new OrderStockDegradeConfiguration();
    assertTrue(configuration.applyRules(validRules(0.3)));
    for (String invalid : List.of("", "not-json", "[]", "[{}]",
        validRules(2.0), validRules(0.3).replace("order-stock-action", "order-stock-status"),
        validRules(0.3).replace("\"grade\":1", "\"grade\":2"),
        validRules(0.3).replace("\"timeWindow\":10", "\"timeWindow\":0"),
        validRules(0.3).replace("\"count\":0.3", "\"count\":0.3,\"unknown\":1"))) {
      assertFalse(configuration.applyRules(invalid));
      assertEquals(0.3, rule(OrderStockCircuitBreaker.SECKILL).getCount());
    }
  }

  /** Nacos 首次不可用后重试成功，且只注册一次 listener。 */
  @Test
  void unavailableNacosRetriesAndClosesSubscription() throws Exception {
    OrderStockDegradeConfiguration configuration = new OrderStockDegradeConfiguration();
    ConfigService nacos = mock(ConfigService.class);
    ReflectionTestUtils.setField(configuration, "configService", nacos);
    when(nacos.getConfigAndSignListener(eq("cloudmall-order-degrade-rules"),
        eq("CLOUDMALL_DEV"), eq(3000L), any(Listener.class)))
        .thenThrow(new IllegalStateException("Nacos down"))
        .thenReturn(validRules(0.4));

    configuration.subscribeToNacos();
    assertEquals(0.2, rule(OrderStockCircuitBreaker.SECKILL).getCount());
    configuration.retrySubscription();
    configuration.retrySubscription();
    assertEquals(0.4, rule(OrderStockCircuitBreaker.SECKILL).getCount());
    verify(nacos, times(2)).getConfigAndSignListener(eq("cloudmall-order-degrade-rules"),
        eq("CLOUDMALL_DEV"), eq(3000L), any(Listener.class));
    configuration.closeSubscription();
    verify(nacos).removeListener(eq("cloudmall-order-degrade-rules"),
        eq("CLOUDMALL_DEV"), any(Listener.class));
    verify(nacos).shutDown();
  }

  /** 构造完整四资源规则，异常比例由调用方控制。 */
  private static String validRules(double ratio) {
    return "["
        + "{\"resource\":\"order-stock-reserve\",\"grade\":0,\"count\":3000,"
        + "\"slowRatioThreshold\":0.5,\"minRequestAmount\":20,"
        + "\"statIntervalMs\":60000,\"timeWindow\":10},"
        + "{\"resource\":\"order-stock-seckill\",\"grade\":1,\"count\":" + ratio
        + ",\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10},"
        + "{\"resource\":\"order-stock-status\",\"grade\":1,\"count\":" + ratio
        + ",\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10},"
        + "{\"resource\":\"order-stock-action\",\"grade\":1,\"count\":" + ratio
        + ",\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10}"
        + "]";
  }

  /** 读取指定资源当前规则。 */
  private static DegradeRule rule(String resource) {
    return DegradeRuleManager.getRules().stream()
        .filter(candidate -> resource.equals(candidate.getResource()))
        .findFirst().orElseThrow();
  }
}
