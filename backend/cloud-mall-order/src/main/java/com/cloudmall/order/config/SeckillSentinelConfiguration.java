package com.cloudmall.order.config;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers the local fail-safe rules used before Nacos rules become available. */
@Configuration
public class SeckillSentinelConfiguration {
  /** 订单秒杀提交的 Sentinel 资源名。 */
  public static final String RESOURCE = "seckill-order-submit";
  /** 订单普通流控规则的 Nacos Data ID。 */
  private static final String FLOW_DATA_ID = "cloudmall-order-flow-rules";
  /** 订单热点参数规则的 Nacos Data ID。 */
  private static final String PARAM_FLOW_DATA_ID = "cloudmall-order-param-flow-rules";
  /** 开发环境规则组。 */
  private static final String GROUP = "CLOUDMALL_DEV";
  /** 规则加载和回退日志。 */
  private static final Logger log = LoggerFactory.getLogger(SeckillSentinelConfiguration.class);

  /** 每个订单实例的默认秒杀总 QPS。 */
  @Value("${cloudmall.order.seckill.sentinel.flow-qps:20}")
  private int defaultFlowQps = 20;
  /** 同一用户的默认秒杀 QPS。 */
  @Value("${cloudmall.order.seckill.sentinel.user-qps:20}")
  private int defaultUserQps = 20;
  /** 同一活动的默认秒杀 QPS。 */
  @Value("${cloudmall.order.seckill.sentinel.activity-qps:20}")
  private int defaultActivityQps = 20;
  /** 本服务创建的 Nacos 配置客户端。 */
  private volatile ConfigService configService;
  /** 普通流控规则的订阅状态。 */
  private volatile boolean flowSubscribed;
  /** 热点参数规则的订阅状态。 */
  private volatile boolean paramSubscribed;

  /** 普通流控规则变更时的验证与发布回调。 */
  private final AbstractListener flowListener = new AbstractListener() {
    @Override
    public void receiveConfigInfo(String content) {
      applyFlowRules(content);
    }
  };

  /** 热点参数规则变更时的验证与发布回调。 */
  private final AbstractListener paramListener = new AbstractListener() {
    @Override
    public void receiveConfigInfo(String content) {
      applyParamFlowRules(content);
    }
  };

  /** 开发环境 Nacos 地址。 */
  @Value("${spring.cloud.nacos.discovery.server-addr:localhost:18848}")
  private String nacosServerAddress;
  /** Nacos 开发环境用户名。 */
  @Value("${spring.cloud.nacos.discovery.username:root}")
  private String nacosUsername;
  /** Nacos 开发环境密码。 */
  @Value("${spring.cloud.nacos.discovery.password:root}")
  private String nacosPassword;

  /** Install conservative defaults without replacing rules already supplied by Nacos. */
  public SeckillSentinelConfiguration() {
    if (FlowRuleManager.getRules().stream().noneMatch(rule -> RESOURCE.equals(rule.getResource()))) {
      FlowRule rule = new FlowRule(RESOURCE);
      rule.setCount(defaultFlowQps > 0 ? defaultFlowQps : 20);
      rule.setGrade(1);
      List<FlowRule> rules = new ArrayList<>(FlowRuleManager.getRules());
      rules.add(rule);
      FlowRuleManager.loadRules(rules);
    }
    if (ParamFlowRuleManager.getRules().stream().noneMatch(rule -> RESOURCE.equals(rule.getResource()))) {
      ParamFlowRule userRule = new ParamFlowRule(RESOURCE).setParamIdx(0)
          .setCount(defaultUserQps > 0 ? defaultUserQps : 20);
      ParamFlowRule activityRule = new ParamFlowRule(RESOURCE).setParamIdx(1)
          .setCount(defaultActivityQps > 0 ? defaultActivityQps : 20);
      List<ParamFlowRule> rules = new ArrayList<>(ParamFlowRuleManager.getRules());
      rules.add(userRule);
      rules.add(activityRule);
      ParamFlowRuleManager.loadRules(rules);
    }
  }

  /** Subscribe to Nacos only after validating a complete replacement. */
  @PostConstruct
  public void initialize() {
    applyConfiguredDefaults();
    subscribeToNacos();
  }

  /** Subscribe to both validated Nacos rule streams. */
  public synchronized void subscribeToNacos() {
    if (flowSubscribed && paramSubscribed) return;
    try {
      if (configService == null) {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", nacosServerAddress);
        properties.setProperty("username", nacosUsername);
        properties.setProperty("password", nacosPassword);
        configService = NacosFactory.createConfigService(properties);
      }
      if (!flowSubscribed) {
        String content = configService.getConfigAndSignListener(
            FLOW_DATA_ID, GROUP, 3000, flowListener);
        flowSubscribed = true;
        applyFlowRules(content);
      }
      if (!paramSubscribed) {
        String content = configService.getConfigAndSignListener(
            PARAM_FLOW_DATA_ID, GROUP, 3000, paramListener);
        paramSubscribed = true;
        applyParamFlowRules(content);
      }
    } catch (Exception unavailable) {
      log.warn("Order Sentinel Nacos 规则订阅失败，保留当前有效规则并等待重试", unavailable);
    }
  }

  /** 关闭本服务创建的 Nacos 长轮询连接。 */
  @PreDestroy
  public void closeNacosSubscription() {
    ConfigService service = configService;
    if (service == null) return;
    if (flowSubscribed) service.removeListener(FLOW_DATA_ID, GROUP, flowListener);
    if (paramSubscribed) service.removeListener(PARAM_FLOW_DATA_ID, GROUP, paramListener);
    try {
      service.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn("关闭 Order Sentinel Nacos 订阅失败", shutdownFailure);
    }
  }

  /** Retry Nacos subscription after a temporary startup outage. */
  @Scheduled(fixedDelayString = "${cloudmall.order.sentinel.nacos-retry-ms:5000}")
  public void retryNacosSubscription() {
    subscribeToNacos();
  }

  private void applyConfiguredDefaults() {
    if (defaultFlowQps <= 0 || defaultUserQps <= 0 || defaultActivityQps <= 0)
      throw new IllegalArgumentException("Order Sentinel default QPS must be positive");
    List<FlowRule> flowRules = new ArrayList<>(FlowRuleManager.getRules());
    flowRules.removeIf(rule -> RESOURCE.equals(rule.getResource()));
    FlowRule flowRule = new FlowRule(RESOURCE);
    flowRule.setCount(defaultFlowQps);
    flowRule.setGrade(1);
    flowRules.add(flowRule);
    FlowRuleManager.loadRules(flowRules);
    List<ParamFlowRule> paramRules = new ArrayList<>(ParamFlowRuleManager.getRules());
    paramRules.removeIf(rule -> RESOURCE.equals(rule.getResource()));
    paramRules.add(new ParamFlowRule(RESOURCE).setParamIdx(0).setCount(defaultUserQps));
    paramRules.add(new ParamFlowRule(RESOURCE).setParamIdx(1).setCount(defaultActivityQps));
    ParamFlowRuleManager.loadRules(paramRules);
  }

  /** Replace flow rules only when the Nacos payload is non-empty and valid. */
  public void applyFlowRules(String content) {
    try {
      List<FlowRule> incoming = parse(content, new TypeReference<>() {});
      if (incoming.isEmpty() || incoming.stream().anyMatch(rule -> rule == null
          || !RESOURCE.equals(rule.getResource()) || rule.getCount() <= 0)) {
        log.warn("Order Sentinel flow 规则无效，保留最近有效规则");
        return;
      }
      List<FlowRule> retained = new ArrayList<>(FlowRuleManager.getRules().stream()
          .filter(rule -> !RESOURCE.equals(rule.getResource())).toList());
      retained.addAll(incoming);
      FlowRuleManager.loadRules(retained);
    } catch (Exception ignored) {
      log.warn("Order Sentinel flow 规则无效，保留最近有效规则");
    }
  }

  /** Replace parameter rules only when the Nacos payload is non-empty and valid. */
  public void applyParamFlowRules(String content) {
    try {
      List<ParamFlowRule> incoming = parse(content, new TypeReference<>() {});
      if (incoming.size() != 2 || incoming.stream().anyMatch(rule -> rule == null
          || !RESOURCE.equals(rule.getResource()) || rule.getCount() <= 0
          || rule.getParamIdx() < 0 || rule.getParamIdx() > 1)
          || incoming.stream().map(ParamFlowRule::getParamIdx).distinct().count() != 2) {
        log.warn("Order Sentinel param-flow 规则无效，保留最近有效规则");
        return;
      }
      List<ParamFlowRule> retained = new ArrayList<>(ParamFlowRuleManager.getRules().stream()
          .filter(rule -> !RESOURCE.equals(rule.getResource())).toList());
      retained.addAll(incoming);
      ParamFlowRuleManager.loadRules(retained);
    } catch (Exception ignored) {
      log.warn("Order Sentinel param-flow 规则无效，保留最近有效规则");
    }
  }

  private static <T> List<T> parse(String content, TypeReference<List<T>> type) throws Exception {
    if (content == null || content.isBlank()) return List.of();
    return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .readValue(content, type);
  }

  /** Enter the order resource with user and activity as hot parameters. */
  public static com.alibaba.csp.sentinel.Entry enter(long userId, long activityId)
      throws BlockException {
    return SphU.entry(RESOURCE, EntryType.IN, 1, userId, activityId);
  }

  /** Record a blocked order request without including user identifiers. */
  public static void recordBlock() {
    Metrics.counter("sentinel.block", "service", "order", "resource", RESOURCE,
        "rule_type", "flow", "status", "429").increment();
  }
}
