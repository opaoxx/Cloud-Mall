package com.cloudmall.order.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 为 Order→Stock 四类调用装载严格校验的 Sentinel 熔断规则。 */
@Configuration
public class OrderStockDegradeConfiguration {
  /** 独立于秒杀 flow/param 规则的 Nacos Data ID。 */
  private static final String DATA_ID = "cloudmall-order-degrade-rules";

  /** 当前配置类负责的四个依赖资源。 */
  private static final Set<String> RESOURCES =
      Set.of(
          OrderStockCircuitBreaker.RESERVE, OrderStockCircuitBreaker.SECKILL,
          OrderStockCircuitBreaker.STATUS, OrderStockCircuitBreaker.ACTION);

  /** Nacos 刷新、故障和默认规则日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderStockDegradeConfiguration.class);

  /** 普通预扣慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.order.stock-degrade.reserve-slow-ms:3000}")
  private double reserveSlowMillis = 3000.0;

  /** 普通预扣慢调用触发比例。 */
  @Value("${cloudmall.order.stock-degrade.reserve-slow-ratio:0.5}")
  private double reserveSlowRatio = 0.5;

  /** 秒杀、状态和动作调用的系统异常触发比例。 */
  @Value("${cloudmall.order.stock-degrade.exception-ratio:0.2}")
  private double exceptionRatio = 0.2;

  /** 最低统计请求数。 */
  @Value("${cloudmall.order.stock-degrade.min-request-amount:20}")
  private int minRequestAmount = 20;

  /** 统计窗口毫秒数。 */
  @Value("${cloudmall.order.stock-degrade.stat-interval-ms:60000}")
  private int statIntervalMillis = 60000;

  /** 熔断开启窗口秒数。 */
  @Value("${cloudmall.order.stock-degrade.time-window-seconds:10}")
  private int timeWindowSeconds = 10;

  /** Nacos 订阅重试间隔，必须为正。 */
  @Value("${cloudmall.order.stock-degrade.nacos-retry-ms:5000}")
  private long nacosRetryMillis = 5000L;

  /** 开发/测试环境隔离规则组。 */
  @Value("${cloudmall.order.stock-degrade.rule-group:CLOUDMALL_DEV}")
  private String ruleGroup = "CLOUDMALL_DEV";

  /** Nacos 地址。 */
  @Value("${spring.cloud.nacos.discovery.server-addr:localhost:18848}")
  private String nacosServerAddress;

  /** Nacos 用户名。 */
  @Value("${spring.cloud.nacos.discovery.username:root}")
  private String nacosUsername;

  /** Nacos 密码，只用于创建客户端。 */
  @Value("${spring.cloud.nacos.discovery.password:root}")
  private String nacosPassword;

  /** 本服务创建的 Nacos 配置客户端。 */
  private volatile ConfigService configService;

  /** 是否已订阅完整规则快照。 */
  private volatile boolean subscribed;

  /** 收到配置时在发布规则前执行同一严格校验。 */
  private final AbstractListener listener =
      new AbstractListener() {
        /** 按原校验流程处理Stock依赖熔断快照，无效内容保留最近有效规则。 */
        @Override
        public void receiveConfigInfo(String content) {
          applyRules(content);
        }
      };

  /** 构造期提供初始本地保护，配置注入后会用可配置默认值替换。 */
  public OrderStockDegradeConfiguration() {
    if (DegradeRuleManager.getRules().stream()
        .noneMatch(rule -> RESOURCES.contains(rule.getResource()))) replaceOwnRules(defaultRules());
  }

  /** 启动时验证本地阈值，安装默认规则后订阅持久来源。 */
  @PostConstruct
  public void initialize() {
    if (nacosRetryMillis <= 0 || !validSnapshot(defaultRules()))
      throw new IllegalArgumentException("Order→Stock 默认熔断参数无效");
    replaceOwnRules(defaultRules());
    subscribeToNacos();
  }

  /** 首次连接失败时保留本地默认并允许后续调度重试。 */
  public synchronized void subscribeToNacos() {
    if (subscribed) return;
    try {
      if (configService == null) {
        Properties properties = new Properties();
        properties.setProperty("serverAddr", nacosServerAddress);
        properties.setProperty("username", nacosUsername);
        properties.setProperty("password", nacosPassword);
        configService = NacosFactory.createConfigService(properties);
      }
      String initial = configService.getConfigAndSignListener(DATA_ID, ruleGroup, 3000L, listener);
      subscribed = true;
      applyRules(initial);
    } catch (Exception unavailable) {
      log.warn(
          "Order→Stock 熔断规则订阅失败，dataId={}，group={}，保留当前规则并重试", DATA_ID, ruleGroup, unavailable);
    }
  }

  /** Nacos 恢复后订阅一次，已订阅实例不重复创建监听器。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.stock-degrade.nacos-retry-ms:5000}")
  public void retrySubscription() {
    subscribeToNacos();
  }

  /** 仅接受四个资源完整、无重复、阈值正确的 JSON 快照。 */
  public synchronized boolean applyRules(String content) {
    if (content == null || content.isBlank()) {
      log.warn("Order→Stock 熔断规则为空，dataId={}，group={}，保留最近有效规则", DATA_ID, ruleGroup);
      return false;
    }
    try {
      List<DegradeRule> incoming =
          new ObjectMapper()
              .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
              .readValue(content, new TypeReference<>() {});
      if (!validSnapshot(incoming)) {
        log.warn("Order→Stock 熔断规则无效，dataId={}，group={}，保留最近有效规则", DATA_ID, ruleGroup);
        return false;
      }
      replaceOwnRules(incoming);
      log.info(
          "Order→Stock 熔断规则已刷新，dataId={}，group={}，rules={}", DATA_ID, ruleGroup, incoming.size());
      return true;
    } catch (Exception invalidRules) {
      log.warn(
          "Order→Stock 熔断规则解析失败，dataId={}，group={}，保留最近有效规则", DATA_ID, ruleGroup, invalidRules);
      return false;
    }
  }

  /** 严格检查资源集合、策略和有界窗口，拒绝部分替换。 */
  private static boolean validSnapshot(List<DegradeRule> rules) {
    if (rules == null || rules.size() != RESOURCES.size()) return false;
    Set<String> seen = new HashSet<>();
    for (DegradeRule rule : rules) {
      if (rule == null
          || !RESOURCES.contains(rule.getResource())
          || !seen.add(rule.getResource())
          || !Double.isFinite(rule.getCount())
          || rule.getMinRequestAmount() < 1
          || rule.getMinRequestAmount() > 10000
          || rule.getStatIntervalMs() < 1000
          || rule.getStatIntervalMs() > 600000
          || rule.getTimeWindow() < 1
          || rule.getTimeWindow() > 3600) return false;
      if (OrderStockCircuitBreaker.RESERVE.equals(rule.getResource())) {
        if (rule.getGrade() != RuleConstant.DEGRADE_GRADE_RT
            || rule.getCount() <= 0
            || rule.getCount() > 60000
            || !Double.isFinite(rule.getSlowRatioThreshold())
            || rule.getSlowRatioThreshold() <= 0
            || rule.getSlowRatioThreshold() > 1) return false;
      } else if (rule.getGrade() != RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO
          || rule.getCount() <= 0
          || rule.getCount() > 1) {
        return false;
      }
    }
    return true;
  }

  /** 替换四个依赖资源时保留同 JVM 的其它 Sentinel 降级规则。 */
  private static synchronized void replaceOwnRules(List<DegradeRule> replacements) {
    List<DegradeRule> retained =
        new ArrayList<>(
            DegradeRuleManager.getRules().stream()
                .filter(rule -> !RESOURCES.contains(rule.getResource()))
                .toList());
    retained.addAll(replacements);
    DegradeRuleManager.loadRules(retained);
  }

  /** 创建与仓库 Nacos JSON 一致的保守单实例默认规则。 */
  private List<DegradeRule> defaultRules() {
    DegradeRule reserve =
        new DegradeRule(OrderStockCircuitBreaker.RESERVE)
            .setGrade(RuleConstant.DEGRADE_GRADE_RT)
            .setCount(reserveSlowMillis)
            .setSlowRatioThreshold(reserveSlowRatio);
    List<DegradeRule> defaults = new ArrayList<>();
    defaults.add(reserve);
    for (String resource :
        List.of(
            OrderStockCircuitBreaker.SECKILL,
            OrderStockCircuitBreaker.STATUS,
            OrderStockCircuitBreaker.ACTION))
      defaults.add(
          new DegradeRule(resource)
              .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
              .setCount(exceptionRatio));
    for (DegradeRule rule : defaults) {
      rule.setMinRequestAmount(minRequestAmount);
      rule.setStatIntervalMs(statIntervalMillis);
      rule.setTimeWindow(timeWindowSeconds);
    }
    return defaults;
  }

  /** 停止服务时移除 listener 并关闭本服务创建的 Nacos 长轮询客户端。 */
  @PreDestroy
  public void closeSubscription() {
    ConfigService service = configService;
    if (service == null) return;
    try {
      if (subscribed) service.removeListener(DATA_ID, ruleGroup, listener);
    } catch (Exception removalFailure) {
      log.warn(
          "Order→Stock 熔断 listener 移除失败，dataId={}，group={}", DATA_ID, ruleGroup, removalFailure);
    }
    try {
      service.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn(
          "Order→Stock 熔断 Nacos 客户端关闭失败，dataId={}，group={}", DATA_ID, ruleGroup, shutdownFailure);
    }
  }
}
