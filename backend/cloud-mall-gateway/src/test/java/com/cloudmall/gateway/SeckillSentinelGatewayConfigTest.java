package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.cloudmall.gateway.config.SeckillSentinelGatewayConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/** Sentinel Gateway 规则兜底和统一拦截响应测试。 */
class SeckillSentinelGatewayConfigTest {
  private final Set<GatewayFlowRule> originalRules = new HashSet<>(GatewayRuleManager.getRules());
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final SeckillSentinelGatewayConfig config =
      new SeckillSentinelGatewayConfig(meterRegistry, new ObjectMapper());

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
    assertEquals(20.0, GatewayRuleManager.getRulesForResource("seckill-order")
        .iterator().next().getCount());
  }

  @Test
  void configuredRuleIsNotOverwrittenByFallback() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("seckill-order").setCount(4)));
    ReflectionTestUtils.setField(config, "defaultGatewayQps", 20.0);

    config.ensureDefaultRule();

    assertEquals(4.0, GatewayRuleManager.getRulesForResource("seckill-order")
        .iterator().next().getCount());
  }

  @Test
  void invalidOrMissingNacosContentPreservesLastValidRule() {
    GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("seckill-order").setCount(4)));

    assertTrue(!config.applyNacosRules(""));
    assertTrue(!config.applyNacosRules("not-json"));
    assertTrue(!config.applyNacosRules(
        "[{\"resource\":\"seckill-order\",\"count\":6,\"unexpected\":1}]"));
    assertEquals(4.0, GatewayRuleManager.getRulesForResource("seckill-order")
        .iterator().next().getCount());
    assertTrue(config.applyNacosRules("[{\"resource\":\"seckill-order\",\"count\":6}]") );
    assertEquals(6.0, GatewayRuleManager.getRulesForResource("seckill-order")
        .iterator().next().getCount());
  }

  @Test
  void blockedRequestReturnsProjectJsonAndNoStore() {
    config.installBlockHandler();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/api/seckill/orders"));
    ServerResponse response = GatewayCallbackManager.getBlockHandler()
        .handleRequest(exchange, new FlowException("blocked")).block();

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode());
    assertEquals("no-store", response.headers().getCacheControl());
    ServerResponse.Context context = new ServerResponse.Context() {
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
    assertTrue(exchange.getResponse().getBodyAsString().block()
        .contains("\"code\":\"GATEWAY_RATE_LIMITED\""));
    assertEquals(1.0, meterRegistry.get("sentinel.block").counter().count());
  }
}
