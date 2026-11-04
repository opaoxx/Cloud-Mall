package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.cloudmall.gateway.config.SeckillSentinelGatewayConfiguration;
import com.cloudmall.gateway.filter.AuthenticationFilter;
import com.cloudmall.gateway.util.GatewayFlowRuleParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;

/** 验证重命名后的实际 Spring 扫描、Bean 名及构造器装配。 */
class GatewayBeanRegistrationTest {
  @Test
  void componentScanPreservesConfigurationBeanNameAndInitializesDefaultProtection() {
    Set<GatewayFlowRule> originalRules = new HashSet<>(GatewayRuleManager.getRules());
    GatewayRuleManager.loadRules(Set.of());
    ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    // 定时注册仍走真实 Spring 基础设施，替身调度器仅登记任务。
    TaskScheduler scheduler = mock(TaskScheduler.class);
    Instant registrationTime = Instant.parse("2026-11-04T00:00:00Z");
    when(scheduler.getClock()).thenReturn(Clock.fixed(registrationTime, ZoneOffset.UTC));
    ScheduledFuture<?> scheduledTask = mock(ScheduledFuture.class);
    doReturn(scheduledTask)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Date.class), eq(10000L));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(MeterRegistry.class, () -> meters);
      context.registerBean(ReactiveStringRedisTemplate.class, () -> redisTemplate);
      context.registerBean("taskScheduler", TaskScheduler.class, () -> scheduler);
      context.scan(
          "com.cloudmall.gateway.config",
          "com.cloudmall.gateway.filter",
          "com.cloudmall.gateway.util");
      context.refresh();
      assertSame(
          context.getBean(SeckillSentinelGatewayConfiguration.class),
          context.getBean("seckillSentinelGatewayConfig"));
      assertFalse(context.containsBean("seckillSentinelGatewayConfiguration"));
      assertEquals(1, context.getBeansOfType(GatewayFlowRuleParser.class).size());
      assertEquals(
          Ordered.HIGHEST_PRECEDENCE, context.getBean(AuthenticationFilter.class).getOrder());
      assertEquals(
          20.0,
          GatewayRuleManager.getRulesForResource("seckill-order").iterator().next().getCount());
      verifyNoInteractions(redisTemplate);
      verify(scheduler)
          .scheduleWithFixedDelay(
              any(Runnable.class), eq(Date.from(registrationTime.plusSeconds(5))), eq(10000L));
    } finally {
      meters.close();
      GatewayRuleManager.loadRules(originalRules);
    }
  }
}
