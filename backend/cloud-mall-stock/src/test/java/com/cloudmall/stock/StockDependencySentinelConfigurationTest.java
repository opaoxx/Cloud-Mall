package com.cloudmall.stock;

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
import com.cloudmall.stock.config.StockDependencySentinelConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Stock 内部依赖降级规则的完整快照、严格校验和 Nacos 重试测试。 */
class StockDependencySentinelConfigurationTest {
  /** 清理 Sentinel 进程级规则，避免测试间相互污染。 */
  @AfterEach
  void restoreRules() {
    DegradeRuleManager.loadRules(List.of());
  }

  /** 合法快照包含五个资源的两种策略，并保留其它服务规则。 */
  @Test
  void validSnapshotReplacesOnlyStockDependencyRules() {
    DegradeRule unrelated = new DegradeRule("unrelated-degrade");
    unrelated.setGrade(1).setCount(0.2).setMinRequestAmount(5).setTimeWindow(5);
    DegradeRuleManager.loadRules(List.of(unrelated));
    StockDependencySentinelConfiguration configuration = new StockDependencySentinelConfiguration();

    assertTrue(configuration.applyRules(validRules(0.4)));

    assertEquals(11, DegradeRuleManager.getRules().size());
    assertEquals(500.0, rule("stock-redis-seckill-write", 0).getCount());
    assertEquals(0.4, rule("stock-mysql-recovery", 1).getCount());
    assertEquals(0.2, rule("unrelated-degrade", 1).getCount());
  }

  /** 空、未知字段、重复策略、缺资源和非法阈值均保留最近有效快照。 */
  @Test
  void invalidSnapshotKeepsLastValidRules() {
    StockDependencySentinelConfiguration configuration = new StockDependencySentinelConfiguration();
    assertTrue(configuration.applyRules(validRules(0.4)));
    String duplicate =
        validRules(0.4)
            .replace(
                "{\"resource\":\"stock-mysql-read\",\"limitApp\":\"default\",\"grade\":1,\"count\":0.4,\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10}",
                "{\"resource\":\"stock-mysql-read\",\"limitApp\":\"default\",\"grade\":0,\"count\":500,\"slowRatioThreshold\":0.5,\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10}");
    for (String invalid :
        List.of(
            "",
            "not-json",
            "[]",
            validRules(0.4).replace("stock-mysql-read", "unknown"),
            duplicate,
            validRules(1.2),
            validRules(0.4).replace("\"count\":500", "\"count\":0"),
            validRules(0.4).replace("\"limitApp\":\"default\"", "\"limitApp\":\"other\""),
            validRules(0.4).replace("\"timeWindow\":10", "\"timeWindow\":0"),
            validRules(0.4).replace("\"count\":0.4", "\"count\":0.4,\"unknown\":1"))) {
      assertFalse(configuration.applyRules(invalid));
      assertEquals(0.4, rule("stock-redis-seckill-write", 1).getCount());
    }
  }

  /** Nacos 首次失败后可重试成功，并且关闭时只移除本 listener。 */
  @Test
  void unavailableNacosRetriesAndClosesSubscription() throws Exception {
    StockDependencySentinelConfiguration configuration = new StockDependencySentinelConfiguration();
    ConfigService nacos = mock(ConfigService.class);
    ReflectionTestUtils.setField(configuration, "configService", nacos);
    when(nacos.getConfigAndSignListener(
            eq("cloudmall-stock-degrade-rules"),
            eq("CLOUDMALL_DEV"),
            eq(3000L),
            any(Listener.class)))
        .thenThrow(new IllegalStateException("Nacos unavailable"))
        .thenReturn(validRules(0.3));

    configuration.subscribeToNacos();
    assertEquals(0.5, rule("stock-redis-seckill-write", 1).getCount());
    configuration.retrySubscription();
    configuration.retrySubscription();
    assertEquals(0.3, rule("stock-redis-seckill-write", 1).getCount());
    verify(nacos, times(2))
        .getConfigAndSignListener(
            eq("cloudmall-stock-degrade-rules"),
            eq("CLOUDMALL_DEV"),
            eq(3000L),
            any(Listener.class));
    configuration.closeSubscription();
    verify(nacos)
        .removeListener(
            eq("cloudmall-stock-degrade-rules"), eq("CLOUDMALL_DEV"), any(Listener.class));
    verify(nacos).shutDown();
  }

  /** 默认配置生成完整的五资源十规则安全快照。 */
  @Test
  void localDefaultsContainAllDependencyRules() {
    StockDependencySentinelConfiguration configuration = new StockDependencySentinelConfiguration();
    assertEquals(10, DegradeRuleManager.getRules().size());
    assertEquals(2000.0, rule("stock-mysql-recovery", 0).getCount());
    assertEquals(0.5, rule("stock-mysql-recovery", 0).getSlowRatioThreshold());
    assertEquals(0.5, rule("stock-mysql-recovery", 1).getCount());
  }

  /** 构造与 Nacos 文件一致的五资源完整规则快照。 */
  private static String validRules(double exceptionRatio) {
    return "["
        + ruleJson("stock-redis-seckill-write", 0, 500, true)
        + ","
        + ruleJson("stock-redis-seckill-write", 1, exceptionRatio, false)
        + ","
        + ruleJson("stock-redis-projection", 0, 500, true)
        + ","
        + ruleJson("stock-redis-projection", 1, exceptionRatio, false)
        + ","
        + ruleJson("stock-mysql-reservation", 0, 1000, true)
        + ","
        + ruleJson("stock-mysql-reservation", 1, exceptionRatio, false)
        + ","
        + ruleJson("stock-mysql-read", 0, 500, true)
        + ","
        + ruleJson("stock-mysql-read", 1, exceptionRatio, false)
        + ","
        + ruleJson("stock-mysql-recovery", 0, 2000, true)
        + ","
        + ruleJson("stock-mysql-recovery", 1, exceptionRatio, false)
        + "]";
  }

  /** 生成单条慢调用比例或异常比例规则。 */
  private static String ruleJson(String resource, int grade, double count, boolean slow) {
    return "{\"resource\":\""
        + resource
        + "\",\"limitApp\":\"default\",\"grade\":"
        + grade
        + ",\"count\":"
        + count
        + (slow ? ",\"slowRatioThreshold\":0.5" : "")
        + ",\"minRequestAmount\":20,\"statIntervalMs\":60000,\"timeWindow\":10}";
  }

  /** 读取资源指定等级的当前规则。 */
  private static DegradeRule rule(String resource, int grade) {
    return DegradeRuleManager.getRules().stream()
        .filter(
            candidate -> resource.equals(candidate.getResource()) && candidate.getGrade() == grade)
        .findFirst()
        .orElseThrow();
  }
}
