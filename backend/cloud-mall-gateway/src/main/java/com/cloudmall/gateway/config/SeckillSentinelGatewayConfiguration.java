package com.cloudmall.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.cloudmall.gateway.util.GatewayFlowRuleParser;
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
@Configuration("seckillSentinelGatewayConfig")
@EnableScheduling
public class SeckillSentinelGatewayConfiguration {
  /** 秒杀订单在 Gateway 中的独立路由资源名。 */
  public static final String SECKILL_ROUTE = "seckill-order";

  /** Sentinel 规则加载和拦截日志。 */
  private static final Logger log =
      LoggerFactory.getLogger(SeckillSentinelGatewayConfiguration.class);

  /** Sentinel 拦截次数与固定维度指标。 */
  private final MeterRegistry meterRegistry;

  /** 将 Nacos 原始内容转换为经过完整校验的规则快照。 */
  private final GatewayFlowRuleParser ruleParser;

  /** Nacos 配置客户端，服务停止时关闭。 */
  private volatile ConfigService configService;

  /** 是否已订阅秒杀 Gateway 规则。 */
  private volatile boolean subscribed;

  /** Nacos 规则变化时只发布经过完整校验的规则。 */
  private final Listener nacosListener =
      new Listener() {
        /** 使用 Nacos 原有监听执行器处理更新。 */
        @Override
        public Executor getExecutor() {
          return null;
        }

        /** 将更新内容交给原完整规则发布入口。 */
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
  public SeckillSentinelGatewayConfiguration(
      MeterRegistry meterRegistry, GatewayFlowRuleParser ruleParser) {
    this.meterRegistry = meterRegistry;
    this.ruleParser = ruleParser;
  }

  /** 在 Nacos 数据源订阅前提供启动默认规则。 */
  @PostConstruct
  public void initializeDefaultRule() {
    ensureDefaultRule();
  }

  /** Nacos 缺席或刷新为无秒杀规则时保持本地保护。 */
  public void ensureDefaultRule() {
    // 1. 校验本地默认值，配置错误在操作全局规则前明确失败。
    if (defaultGatewayQps <= 0 || !Double.isFinite(defaultGatewayQps)) {
      throw new IllegalArgumentException("cloudmall.sentinel.seckill.gateway-qps must be positive");
    }
    Set<GatewayFlowRule> currentRules = GatewayRuleManager.getRules();
    // 2. 已有秒杀规则时直接保留，避免覆盖动态配置。
    if (currentRules != null
        && currentRules.stream().anyMatch(rule -> SECKILL_ROUTE.equals(rule.getResource()))) return;
    Set<GatewayFlowRule> protectedRules = new HashSet<>();
    // 3. 在其他路由规则基础上补充秒杀默认保护。
    if (currentRules != null) protectedRules.addAll(currentRules);
    protectedRules.add(
        new GatewayFlowRule(SECKILL_ROUTE).setCount(defaultGatewayQps).setIntervalSec(1));
    GatewayRuleManager.loadRules(protectedRules);
    log.warn("Sentinel 秒杀路由规则缺失，已应用本地默认 QPS={}", defaultGatewayQps);
  }

  /** 周期核验安全默认值，并在 Nacos 初次不可达后继续尝试订阅。 */
  @Scheduled(initialDelay = 5000, fixedDelay = 10000)
  public void maintainRules() {
    // 1. 先确保本地秒杀保护仍然存在。
    ensureDefaultRule();
    // 2. 继续尝试未成功建立的配置订阅。
    subscribeNacos();
  }

  /** 仅将可解析且包含秒杀路由的 Nacos 规则作为完整新快照发布。 */
  public boolean applyNacosRules(String content) {
    // 1. 空内容保留最近有效快照。
    if (content == null || content.isBlank()) {
      log.warn("Nacos 秒杀 Gateway 规则为空，保留最近有效规则");
      return false;
    }
    try {
      // 2. 所有规则校验通过后才获得新快照，解析不修改全局规则。
      Set<GatewayFlowRule> parsedRules = ruleParser.parse(content, SECKILL_ROUTE);
      // 3. 一次发布完整集合，失败沿原异常处理保留最近有效规则。
      GatewayRuleManager.loadRules(parsedRules);
      log.info("Nacos Sentinel Gateway 规则已更新，规则数={}", parsedRules.size());
      return true;
    } catch (Exception invalidRules) {
      log.warn("Nacos Sentinel Gateway 规则无效，保留最近有效规则", invalidRules);
      return false;
    }
  }

  /** 订阅 Nacos 的持久 Gateway 规则，首次连接失败时继续使用默认值。 */
  private synchronized void subscribeNacos() {
    // 1. 已建立的订阅继续复用同一个监听器。
    if (subscribed) return;
    try {
      // 2. 按原配置创建客户端，首次失败后保留重试入口。
      if (configService == null) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, nacosServerAddress);
        properties.setProperty(PropertyKeyConst.USERNAME, nacosUsername);
        properties.setProperty(PropertyKeyConst.PASSWORD, nacosPassword);
        configService = NacosFactory.createConfigService(properties);
      }
      // 3. 同次获取初始快照并注册监听，随后使用统一发布入口。
      String initialRules =
          configService.getConfigAndSignListener(flowDataId, ruleGroup, 3000L, nacosListener);
      subscribed = true;
      applyNacosRules(initialRules);
    } catch (Exception unavailableNacos) {
      log.warn("Nacos Sentinel Gateway 规则订阅失败，继续使用最近有效规则", unavailableNacos);
    }
  }

  /** 释放独立的 Nacos 配置订阅连接。 */
  @PreDestroy
  public void closeNacosSubscription() {
    // 1. 固定本次关闭的客户端，并先移除原监听。
    ConfigService currentConfigService = configService;
    if (currentConfigService == null) return;
    currentConfigService.removeListener(flowDataId, ruleGroup, nacosListener);
    try {
      // 2. 释放该客户端，保留原关闭异常记录方式。
      currentConfigService.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn("关闭 Nacos Sentinel 规则订阅失败", shutdownFailure);
    }
  }

  /** 覆盖 Sentinel 默认纯文本拦截体，返回项目统一 JSON 契约。 */
  @EventListener(ApplicationReadyEvent.class)
  public void installBlockHandler() {
    // 1. 安装原拦截计数与429 JSON响应处理器。
    GatewayCallbackManager.setBlockHandler(
        (exchange, cause) -> {
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
    // 2. 应用就绪时启动原配置订阅流程。
    subscribeNacos();
  }
}
