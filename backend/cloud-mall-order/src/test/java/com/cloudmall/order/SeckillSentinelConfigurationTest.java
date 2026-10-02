package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.cloudmall.order.config.SeckillSentinelConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SeckillSentinelConfigurationTest {
  @AfterEach
  void restoreRules() {
    FlowRuleManager.loadRules(List.of());
    ParamFlowRuleManager.loadRules(List.of());
  }

  @Test
  void defaultRulesProtectUserAndActivityBeforeBusinessSideEffects() {
    new SeckillSentinelConfiguration();

    assertEquals(20, FlowRuleManager.getRules().stream()
        .filter(rule -> SeckillSentinelConfiguration.RESOURCE.equals(rule.getResource()))
        .findFirst().orElseThrow().getCount());
    assertEquals(2, ParamFlowRuleManager.getRules().stream()
        .filter(rule -> SeckillSentinelConfiguration.RESOURCE.equals(rule.getResource())).count());
    FlowRuleManager.loadRules(List.of(blockAll()));
    boolean blocked = false;
    for (int attempt = 0; attempt < 10 && !blocked; attempt++) {
      try {
        var entry = SeckillSentinelConfiguration.enter(7L, 11L);
        entry.exit(1, 7L, 11L);
      } catch (BlockException expected) {
        blocked = true;
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(blocked);
  }

  @Test
  void missingOrMalformedNacosRulesKeepTheExistingDefaults() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    int flowCount = FlowRuleManager.getRules().size();
    int paramCount = ParamFlowRuleManager.getRules().size();

    configuration.applyFlowRules("");
    configuration.applyFlowRules("not-json");
    configuration.applyParamFlowRules("[]");
    configuration.applyParamFlowRules("{bad-json}");

    assertEquals(flowCount, FlowRuleManager.getRules().size());
    assertEquals(paramCount, ParamFlowRuleManager.getRules().size());
  }

  @Test
  void validNacosRulesReplaceDefaultsOnlyAfterValidation() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();

    configuration.applyFlowRules("[{\"resource\":\"seckill-order-submit\",\"grade\":1,\"count\":7}]");
    configuration.applyParamFlowRules("[{\"resource\":\"seckill-order-submit\",\"paramIdx\":0,\"count\":3},"
        + "{\"resource\":\"seckill-order-submit\",\"paramIdx\":1,\"count\":5}]");

    assertEquals(7, FlowRuleManager.getRules().get(0).getCount());
    assertEquals(3, ParamFlowRuleManager.getRules().stream()
        .filter(rule -> rule.getParamIdx() == 0).findFirst().orElseThrow().getCount());
    assertEquals(2, ParamFlowRuleManager.getRules().size());
  }

  @Test
  void userAndActivityHotParametersAreInstalledOnTheSameEntry() throws Exception {
    new SeckillSentinelConfiguration();
    ParamFlowRule userRule = new ParamFlowRule(SeckillSentinelConfiguration.RESOURCE)
        .setParamIdx(0).setCount(1).setDurationInSec(60);
    ParamFlowRule activityRule = new ParamFlowRule(SeckillSentinelConfiguration.RESOURCE)
        .setParamIdx(1).setCount(1).setDurationInSec(60);
    ParamFlowRuleManager.loadRules(List.of(userRule, activityRule));

    var first = SeckillSentinelConfiguration.enter(7L, 11L);
    first.exit(1, 7L, 11L);
    org.junit.jupiter.api.Assertions.assertThrows(BlockException.class,
        () -> SeckillSentinelConfiguration.enter(7L, 11L));
  }

  @Test
  void incompleteActivityRuleCannotRemoveDefaultActivityProtection() {
    SeckillSentinelConfiguration configuration = new SeckillSentinelConfiguration();
    long originalRuleCount = ParamFlowRuleManager.getRules().stream()
        .filter(rule -> SeckillSentinelConfiguration.RESOURCE.equals(rule.getResource())).count();

    configuration.applyParamFlowRules(
        "[{\"resource\":\"seckill-order-submit\",\"paramIdx\":0,\"count\":2}]");

    assertEquals(originalRuleCount, ParamFlowRuleManager.getRules().stream()
        .filter(rule -> SeckillSentinelConfiguration.RESOURCE.equals(rule.getResource())).count());
  }

  private static FlowRule blockAll() {
    FlowRule rule = new FlowRule(SeckillSentinelConfiguration.RESOURCE);
    rule.setCount(0);
    rule.setGrade(1);
    return rule;
  }
}
