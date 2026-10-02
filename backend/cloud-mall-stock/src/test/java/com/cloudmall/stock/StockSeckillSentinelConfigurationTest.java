package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
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
}
