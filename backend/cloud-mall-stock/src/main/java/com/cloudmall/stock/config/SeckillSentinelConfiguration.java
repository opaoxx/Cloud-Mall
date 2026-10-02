package com.cloudmall.stock.config;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;

/** Registers a deliberately broad local guard for internal stock reservations. */
@Configuration
public class SeckillSentinelConfiguration {
  /** 库存内部秒杀预扣的 Sentinel 资源名。 */
  public static final String RESOURCE = "seckill-stock-reservation";

  /** Install the fixed stock rule; dynamic stock rules are outside this batch. */
  public SeckillSentinelConfiguration() {
    if (FlowRuleManager.getRules().stream().noneMatch(rule -> RESOURCE.equals(rule.getResource()))) {
      FlowRule rule = new FlowRule(RESOURCE);
      rule.setCount(200);
      rule.setGrade(1);
      List<FlowRule> rules = new ArrayList<>(FlowRuleManager.getRules());
      rules.add(rule);
      FlowRuleManager.loadRules(rules);
    }
  }

  /** Enter the internal stock resource after caller ownership checks. */
  public static com.alibaba.csp.sentinel.Entry enter() throws BlockException {
    return SphU.entry(RESOURCE, EntryType.IN);
  }

  /** Record a blocked internal reservation without request payload data. */
  public static void recordBlock() {
    Metrics.counter("sentinel.block", "service", "stock", "resource", RESOURCE,
        "rule_type", "flow", "status", "429").increment();
  }
}
