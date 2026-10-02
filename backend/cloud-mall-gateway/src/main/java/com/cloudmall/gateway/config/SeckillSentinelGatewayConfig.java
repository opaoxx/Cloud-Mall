package com.cloudmall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.server.ServerResponse;

/** 为独立秒杀路由安装 Sentinel 默认保护和统一的 429 响应。 */
@Configuration
@EnableScheduling
public class SeckillSentinelGatewayConfig {
  /** 秒杀订单在 Gateway 中的独立路由资源名。 */
  public static final String SECKILL_ROUTE = "seckill-order";

  /** Sentinel 规则加载和拦截日志。 */
  private static final Logger log = LoggerFactory.getLogger(SeckillSentinelGatewayConfig.class);

  /** Sentinel 拦截次数与固定维度指标。 */
  private final MeterRegistry meterRegistry;

  /** 解析 Nacos 规则 JSON，并拒绝未知字段或无效规则。 */
  private final ObjectMapper objectMapper;

  /** Nacos 配置客户端，服务停止时关闭。 */
  private volatile ConfigService configService;

  /** 是否已订阅秒杀 Gateway 规则。 */
  private volatile boolean subscribed;

  /** Nacos 规则变化时只发布经过完整校验的规则。 */
  private final Listener nacosListener = new Listener() {
    @Override
    public Executor getExecutor() {
      return null;
    }

    @Override
    public void receiveConfigInfo(String content) {
      applyNacosRules(content);
    }
  };

  /** 本地规则源不可用时每个 Gateway 实例允许的秒杀请求 QPS。 */
  @Value("${cloudmall.sentinel.seckill.gateway-qps:20}")
  private double defaultGatewayQps;

  /** 开发环境 Nacos 规则源地址。 */
  @Value("${spring.cloud.nacos.discovery.server-addr:localhost:18848}")
  private String nacosServerAddress;

  /** 读取 Nacos 配置的开发环境用户名。 */
  @Value("${spring.cloud.nacos.discovery.username:root}")
  private String nacosUsername;

  /** 读取 Nacos 配置的开发环境密码。 */
  @Value("${spring.cloud.nacos.discovery.password:root}")
  private String nacosPassword;

  /** Gateway 流控规则的 Nacos Data ID。 */
  @Value("${cloudmall.sentinel.nacos.gateway-flow-data-id:cloudmall-gateway-flow-rules}")
  private String flowDataId;

  /** 开发环境规则组。 */
  @Value("${cloudmall.sentinel.nacos.group:CLOUDMALL_DEV}")
  private String ruleGroup;

  /** 创建 Gateway Sentinel 规则配置。 */
  public SeckillSentinelGatewayConfig(MeterRegistry meterRegistry, ObjectMapper objectMapper) {
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
  }

  /** 在 Nacos 数据源订阅前提供启动默认规则。 */
  @PostConstruct
  public void initializeDefaultRule() {
    ensureDefaultRule();
  }

  /** Nacos 缺席或刷新为无秒杀规则时保持本地保护。 */
  public void ensureDefaultRule() {
    if (defaultGatewayQps <= 0 || !Double.isFinite(defaultGatewayQps)) {
      throw new IllegalArgumentException("cloudmall.sentinel.seckill.gateway-qps must be positive");
    }
    Set<GatewayFlowRule> currentRules = GatewayRuleManager.getRules();
    if (currentRules != null && currentRules.stream()
        .anyMatch(rule -> SECKILL_ROUTE.equals(rule.getResource()))) return;
    Set<GatewayFlowRule> protectedRules = new HashSet<>();
    if (currentRules != null) protectedRules.addAll(currentRules);
    protectedRules.add(new GatewayFlowRule(SECKILL_ROUTE).setCount(defaultGatewayQps)
        .setIntervalSec(1));
    GatewayRuleManager.loadRules(protectedRules);
    log.warn("Sentinel 秒杀路由规则缺失，已应用本地默认 QPS={}", defaultGatewayQps);
  }

  /** 周期核验安全默认值，并在 Nacos 初次不可达后继续尝试订阅。 */
  @Scheduled(initialDelay = 5000, fixedDelay = 10000)
  public void maintainRules() {
    ensureDefaultRule();
    subscribeNacos();
  }

  /** 仅将可解析且包含秒杀路由的 Nacos 规则作为完整新快照发布。 */
  public boolean applyNacosRules(String content) {
    if (content == null || content.isBlank()) {
      log.warn("Nacos 秒杀 Gateway 规则为空，保留最近有效规则");
      return false;
    }
    try {
      ObjectMapper strictMapper = objectMapper.copy()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
      JsonNode root = strictMapper.readTree(content);
      if (root == null || !root.isArray() || root.isEmpty())
        throw new IllegalArgumentException("Gateway rules must be a non-empty JSON array");
      Set<GatewayFlowRule> parsed = new HashSet<>();
      for (JsonNode node : root) {
        if (!node.isObject() || !node.hasNonNull("resource") || !node.has("count"))
          throw new IllegalArgumentException("Gateway rule missing resource or count");
        GatewayFlowRule rule = strictMapper.treeToValue(node, GatewayFlowRule.class);
        if (!GatewayRuleManager.isValidRule(rule) || rule.getCount() <= 0)
          throw new IllegalArgumentException("Gateway rule is invalid");
        parsed.add(rule);
      }
      if (parsed.stream().noneMatch(rule -> SECKILL_ROUTE.equals(rule.getResource())))
        throw new IllegalArgumentException("Gateway rules lack seckill-order resource");
      GatewayRuleManager.loadRules(parsed);
      log.info("Nacos Sentinel Gateway 规则已更新，规则数={}", parsed.size());
      return true;
    } catch (Exception invalidRules) {
      log.warn("Nacos Sentinel Gateway 规则无效，保留最近有效规则", invalidRules);
      return false;
    }
  }

  /** 订阅 Nacos 的持久 Gateway 规则，首次连接失败时继续使用默认值。 */
  private synchronized void subscribeNacos() {
    if (subscribed) return;
    try {
      if (configService == null) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, nacosServerAddress);
        properties.setProperty(PropertyKeyConst.USERNAME, nacosUsername);
        properties.setProperty(PropertyKeyConst.PASSWORD, nacosPassword);
        configService = NacosFactory.createConfigService(properties);
      }
      String initialRules = configService.getConfigAndSignListener(
          flowDataId, ruleGroup, 3000L, nacosListener);
      subscribed = true;
      applyNacosRules(initialRules);
    } catch (Exception unavailableNacos) {
      log.warn("Nacos Sentinel Gateway 规则订阅失败，继续使用最近有效规则", unavailableNacos);
    }
  }

  /** 释放独立的 Nacos 配置订阅连接。 */
  @PreDestroy
  public void closeNacosSubscription() {
    ConfigService service = configService;
    if (service == null) return;
    service.removeListener(flowDataId, ruleGroup, nacosListener);
    try {
      service.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn("关闭 Nacos Sentinel 规则订阅失败", shutdownFailure);
    }
  }

  /** 覆盖 Sentinel 默认纯文本拦截体，返回项目统一 JSON 契约。 */
  @EventListener(ApplicationReadyEvent.class)
  public void installBlockHandler() {
    GatewayCallbackManager.setBlockHandler((exchange, cause) -> {
      String ruleType = cause.getClass().getSimpleName();
      Counter.builder("sentinel.block")
          .tag("service", "gateway")
          .tag("resource", SECKILL_ROUTE)
          .tag("rule_type", ruleType)
          .tag("status", "429")
          .register(meterRegistry)
          .increment();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("code", "GATEWAY_RATE_LIMITED");
      body.put("message", "秒杀请求过于频繁，请稍后重试");
      body.put("data", null);
      body.put("requestId", UUID.randomUUID().toString());
      return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body);
    });
    subscribeNacos();
  }
}
