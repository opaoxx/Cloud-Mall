package com.cloudmall.gateway.util;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 解析并完整校验 Gateway 规则快照，由配置入口负责发布有效结果。 */
@Component
public class GatewayFlowRuleParser {
  /** 用于创建本次严格解析副本的应用 JSON 映射器。 */
  private final ObjectMapper objectMapper;

  /** 注入应用映射器，解析时保留原副本配置方式。 */
  public GatewayFlowRuleParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * 完整解析并验证含必需资源的规则集。
   *
   * @param content Nacos 返回的原始 JSON 内容
   * @param requiredResource 新快照必须包含的路由资源名
   * @return 全部校验通过的规则集合
   * @throws JsonProcessingException JSON 解析失败或包含不支持的规则字段
   * @throws IllegalArgumentException 数组、规则字段或必需资源不满足原契约
   */
  public Set<GatewayFlowRule> parse(String content, String requiredResource)
      throws JsonProcessingException {
    // 1. 每次使用应用映射器副本，拒绝未知规则字段。
    ObjectMapper strictMapper =
        objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    JsonNode rulesNode = strictMapper.readTree(content);
    if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty())
      throw new IllegalArgumentException("Gateway rules must be a non-empty JSON array");
    // 2. 任意一项失败即拒绝整个快照。
    Set<GatewayFlowRule> parsedRules = new HashSet<>();
    for (JsonNode ruleNode : rulesNode) {
      if (!ruleNode.isObject() || !ruleNode.hasNonNull("resource") || !ruleNode.has("count"))
        throw new IllegalArgumentException("Gateway rule missing resource or count");
      GatewayFlowRule rule = strictMapper.treeToValue(ruleNode, GatewayFlowRule.class);
      if (!GatewayRuleManager.isValidRule(rule) || rule.getCount() <= 0)
        throw new IllegalArgumentException("Gateway rule is invalid");
      parsedRules.add(rule);
    }
    // 3. 必需路由具备保护后才返回可发布的完整规则集。
    if (parsedRules.stream().noneMatch(rule -> requiredResource.equals(rule.getResource())))
      throw new IllegalArgumentException("Gateway rules lack " + requiredResource + " resource");
    return parsedRules;
  }
}
