package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.config.SeckillSentinelConfiguration;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StockSeckillSentinelConfigurationTest {
  @AfterEach
  void restoreRules() {
    FlowRuleManager.loadRules(List.of());
    AuthContext.clear();
  }

  @Test
  void fixedInternalRuleIsBroadAndBlockHasNoBusinessEntry() {
    FlowRule unrelatedRule = new FlowRule("unrelated-resource");
    unrelatedRule.setCount(3);
    unrelatedRule.setGrade(1);
    FlowRuleManager.loadRules(List.of(unrelatedRule));
    new SeckillSentinelConfiguration();
    FlowRule rule = FlowRuleManager.getRules().stream()
        .filter(candidate -> SeckillSentinelConfiguration.RESOURCE.equals(candidate.getResource()))
        .findFirst().orElseThrow();
    assertEquals(200, rule.getCount());
    assertEquals(1, rule.getGrade());
    assertEquals(2, FlowRuleManager.getRules().size());
  }

  @Test
  void blockedInternalRequestCannotReachClaimOrRedis() {
    FlowRule blockingRule = new FlowRule(SeckillSentinelConfiguration.RESOURCE);
    blockingRule.setGrade(1);
    blockingRule.setCount(0);
    FlowRuleManager.loadRules(List.of(blockingRule));
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    StockServiceImpl stockService = new StockServiceImpl(
        redis, stockSqlMapper, mock(RabbitTemplate.class), new ObjectMapper());
    SeckillReservationDTO request = new SeckillReservationDTO();
    request.activityId = 11L;
    request.skuId = 22L;
    request.userId = 33L;
    request.idempotencyKey = "cm17-blocked";
    request.addressSnapshot = "not-json";
    AuthContext.set(33L, "USER");

    boolean blocked = false;
    for (int attempt = 0; attempt < 20 && !blocked; attempt++) {
      try {
        stockService.seckill(request);
      } catch (BizException result) {
        blocked = "GATEWAY_RATE_LIMITED".equals(result.getCode());
      }
    }

    assertTrue(blocked);
    verifyNoInteractions(redis, stockSqlMapper);
  }

  @Test
  void validNacosSnapshotReplacesOnlyStockResource() {
    FlowRule unrelatedRule = new FlowRule("unrelated-resource");
    unrelatedRule.setCount(3);
    unrelatedRule.setGrade(1);
    FlowRuleManager.loadRules(List.of(unrelatedRule));
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();

    assertTrue(configuration.applyFlowRules(validRule(7)));

    assertEquals(7.0, stockRule().getCount());
    assertEquals(2, FlowRuleManager.getRules().size());
    assertEquals(3.0, FlowRuleManager.getRules().stream()
        .filter(rule -> "unrelated-resource".equals(rule.getResource()))
        .findFirst().orElseThrow().getCount());
  }

  @Test
  void invalidRefreshKeepsTheLastValidQps() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    assertTrue(configuration.applyFlowRules(validRule(7)));

    for (String invalid : List.of("", "not-json", "[]",
        "[{\"resource\":\"other\",\"grade\":1,\"count\":3}]",
        "[{\"resource\":\"seckill-stock-reservation\",\"grade\":1,\"count\":-1}]",
        "[{\"resource\":\"seckill-stock-reservation\",\"grade\":0,\"count\":3}]",
        "[{\"resource\":\"seckill-stock-reservation\",\"grade\":1,\"count\":3,\"unexpected\":1}]",
        "[" + validRule(3).substring(1, validRule(3).length() - 1) + ","
            + validRule(4).substring(1, validRule(4).length() - 1) + "]")) {
      assertFalse(configuration.applyFlowRules(invalid));
      assertEquals(7.0, stockRule().getCount());
    }
  }

  @Test
  void unavailableNacosIsRetriedWithoutDroppingTheLastRule() throws Exception {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    ConfigService nacos = mock(ConfigService.class);
    ReflectionTestUtils.setField(configuration, "configService", nacos);
    when(nacos.getConfigAndSignListener(eq("cloudmall-stock-flow-rules"),
        eq("CLOUDMALL_DEV"), eq(3000L), any(Listener.class)))
        .thenThrow(new IllegalStateException("Nacos unavailable"))
        .thenReturn(validRule(9));

    configuration.subscribeToNacos();
    assertEquals(200.0, stockRule().getCount());
    configuration.retryNacosSubscription();
    configuration.retryNacosSubscription();
    assertEquals(9.0, stockRule().getCount());
    verify(nacos, times(2)).getConfigAndSignListener(eq("cloudmall-stock-flow-rules"),
        eq("CLOUDMALL_DEV"), eq(3000L), any(Listener.class));
    configuration.closeNacosSubscription();
    verify(nacos).removeListener(eq("cloudmall-stock-flow-rules"),
        eq("CLOUDMALL_DEV"), any(Listener.class));
    verify(nacos).shutDown();
  }

  @Test
  void configuredLocalFallbackIsAppliedBeforeRemoteRules() throws Exception {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    ConfigService nacos = mock(ConfigService.class);
    ReflectionTestUtils.setField(configuration, "configService", nacos);
    ReflectionTestUtils.setField(configuration, "defaultFlowQps", 123.0);

    configuration.initialize();

    assertEquals(123.0, stockRule().getCount());
    configuration.closeNacosSubscription();
  }

  @Test
  void nonFiniteDefaultCannotStartWithoutAUsableFallback() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    ReflectionTestUtils.setField(configuration, "defaultFlowQps", Double.NaN);

    assertThrows(IllegalArgumentException.class, configuration::initialize);
  }

  @Test
  void nonPositiveNacosRetryIntervalIsRejected() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    ReflectionTestUtils.setField(configuration, "nacosRetryMs", 0L);

    assertThrows(IllegalArgumentException.class, configuration::initialize);
  }

  /** 构造当前资源的有效 Nacos JSON 规则。 */
  private static String validRule(int qps) {
    return "[{\"resource\":\"seckill-stock-reservation\",\"grade\":1,"
        + "\"count\":" + qps + ",\"limitApp\":\"default\",\"strategy\":0,"
        + "\"controlBehavior\":0}]";
  }

  /** 从 Sentinel 进程级管理器读取当前 Stock 资源规则。 */
  private static FlowRule stockRule() {
    return FlowRuleManager.getRules().stream()
        .filter(rule -> SeckillSentinelConfiguration.RESOURCE.equals(rule.getResource()))
        .findFirst().orElseThrow();
  }
}
