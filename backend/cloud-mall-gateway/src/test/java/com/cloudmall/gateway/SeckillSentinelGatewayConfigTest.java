package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.cloudmall.gateway.config.SeckillSentinelGatewayConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/** Sentinel Gateway 规则兜底和统一拦截响应测试。 */
class SeckillSentinelGatewayConfigTest {
  /** 测试开始前的全局规则快照。 */
  private final Set<GatewayFlowRule> originalRules = new HashSet<>(GatewayRuleManager.getRules());

  /** 独立的指标注册表。 */
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /** 实际规则与订阅管理入口。 */
  private final SeckillSentinelGatewayConfig config =
      new SeckillSentinelGatewayConfig(meterRegistry, new ObjectMapper());

  /** Nacos 网络边界替身。 */
  private final ConfigService configService = mock(ConfigService.class);

  @BeforeEach
  void useIsolatedConfigurationSource() {
    ReflectionTestUtils.setField(config, "configService", configService);
    ReflectionTestUtils.setField(config, "flowDataId", "gateway-flow-rules-test");
    ReflectionTestUtils.setField(config, "ruleGroup", "TEST");
    ReflectionTestUtils.setField(config, "defaultGatewayQps", 20.0);
  }

  @AfterEach
  void restoreSentinelGlobals() {
    GatewayRuleManager.loadRules(originalRules);
    GatewayCallbackManager.resetBlockHandler();
    meterRegistry.close();
  }

  @Test
  void missingRuleGetsBoundedDefaultWithoutDroppingOtherGatewayRules() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("other-route").setCount(3)));
    ReflectionTestUtils.setField(config, "defaultGatewayQps", 20.0);

    config.ensureDefaultRule();

    assertEquals(2, GatewayRuleManager.getRules().size());
    assertEquals(
        20.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
  }

  @Test
  void configuredRuleIsNotOverwrittenByFallback() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("seckill-order").setCount(4)));
    ReflectionTestUtils.setField(config, "defaultGatewayQps", 20.0);

    config.ensureDefaultRule();

    assertEquals(
        4.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
  }

  @Test
  void invalidOrMissingNacosContentPreservesLastValidRule() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("seckill-order").setCount(4)));

    assertTrue(!config.applyNacosRules(""));
    assertTrue(!config.applyNacosRules("not-json"));
    assertTrue(
        !config.applyNacosRules("[{\"resource\":\"seckill-order\",\"count\":6,\"unexpected\":1}]"));
    assertEquals(
        4.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
    assertTrue(config.applyNacosRules("[{\"resource\":\"seckill-order\",\"count\":6}]"));
    assertEquals(
        6.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
  }

  @Test
  void partiallyInvalidSnapshotsNeverPublishAndMissingRouteKeepsThePreviousRules() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("seckill-order").setCount(4)));
    for (String content :
        List.of(
            "[]",
            "{}",
            "null",
            "[{\"resource\":\"other-route\",\"count\":6}]",
            "[{\"resource\":\"seckill-order\",\"count\":6},{\"resource\":\"other-route\"}]",
            "[{\"resource\":\"seckill-order\",\"count\":6},{\"resource\":\"other-route\",\"count\":0}]")) {
      assertFalse(config.applyNacosRules(content), content);
      assertEquals(1, GatewayRuleManager.getRules().size());
      assertEquals(
          4.0,
          GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
    }
  }

  @Test
  void invalidLocalDefaultFailsBeforeChangingAnyRule() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("other-route").setCount(4)));
    for (double defaultQps : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      ReflectionTestUtils.setField(config, "defaultGatewayQps", defaultQps);
      assertThrows(IllegalArgumentException.class, config::ensureDefaultRule);
      assertEquals(1, GatewayRuleManager.getRules().size());
      assertTrue(GatewayRuleManager.getRulesForResource("seckill-order").isEmpty());
    }
  }

  @Test
  void failedSubscriptionRetriesThenUsesTheSameListenerUntilShutdown() throws Exception {
    GatewayRuleManager.loadRules(Set.of());
    when(configService.getConfigAndSignListener(
            eq("gateway-flow-rules-test"), eq("TEST"), eq(3000L), any(Listener.class)))
        .thenThrow(new NacosException(500, "unavailable"))
        .thenReturn("[{\"resource\":\"seckill-order\",\"count\":6}]");
    config.maintainRules();
    assertEquals(
        20.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
    config.maintainRules();
    config.maintainRules();
    assertEquals(
        6.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
    ArgumentCaptor<Listener> captured = ArgumentCaptor.forClass(Listener.class);
    verify(configService, times(2))
        .getConfigAndSignListener(
            eq("gateway-flow-rules-test"), eq("TEST"), eq(3000L), captured.capture());
    Listener listener = captured.getValue();
    assertNull(listener.getExecutor());
    listener.receiveConfigInfo("[{\"resource\":\"seckill-order\",\"count\":7}]");
    assertEquals(
        7.0, GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
    config.closeNacosSubscription();
    var ordered = inOrder(configService);
    ordered
        .verify(configService, times(2))
        .getConfigAndSignListener(anyString(), anyString(), anyLong(), any(Listener.class));
    ordered.verify(configService).removeListener("gateway-flow-rules-test", "TEST", listener);
    ordered.verify(configService).shutDown();
  }

  @Test
  void blockedRequestReturnsProjectJsonAndNoStore() {
    config.installBlockHandler();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/seckill/orders"));
    ServerResponse response =
        GatewayCallbackManager.getBlockHandler()
            .handleRequest(exchange, new FlowException("blocked"))
            .block();

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode());
    assertEquals("no-store", response.headers().getCacheControl());
    ServerResponse.Context context =
        new ServerResponse.Context() {
          @Override
          public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
            return HandlerStrategies.withDefaults().messageWriters();
          }

          @Override
          public List<ViewResolver> viewResolvers() {
            return List.of();
          }
        };
    response.writeTo(exchange, context).block();
    assertTrue(
        exchange
            .getResponse()
            .getBodyAsString()
            .block()
            .contains("\"code\":\"GATEWAY_RATE_LIMITED\""));
    assertEquals(1.0, meterRegistry.get("sentinel.block").counter().count());
  }
}
