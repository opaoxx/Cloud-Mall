package com.cloudmall.stock.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 为 Stock 内部 Redis/MySQL 依赖提供可动态刷新的 Sentinel 降级规则。 */
@Configuration
public class StockDependencySentinelConfiguration {
  /** 独立于 Stock flow 规则的 Nacos Data ID。 */
  public static final String DATA_ID = "cloudmall-stock-degrade-rules";

  /** 开发/测试环境隔离规则组。 */
  private static final String DEFAULT_RULE_GROUP = "CLOUDMALL_DEV";

  /** 资源固定集合及其默认慢调用阈值。 */
  private static final Map<String, Double> DEFAULT_SLOW_MILLIS =
      Map.of(
          "stock-redis-seckill-write", 500.0,
          "stock-redis-projection", 500.0,
          "stock-mysql-reservation", 1000.0,
          "stock-mysql-read", 500.0,
          "stock-mysql-recovery", 2000.0);

  /** 本配置实例的日志。 */
  private static final Logger log =
      LoggerFactory.getLogger(StockDependencySentinelConfiguration.class);

  /** 所有依赖规则的慢调用比例阈值。 */
  @Value("${cloudmall.stock.dependency-degrade.slow-ratio-threshold:0.5}")
  private double slowRatioThreshold = 0.5;

  /** 秒杀写路径慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.redis-seckill-write-slow-ms:500}")
  private double redisSeckillWriteSlowMillis = 500.0;

  /** Redis 投影慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.redis-projection-slow-ms:500}")
  private double redisProjectionSlowMillis = 500.0;

  /** 预扣、确认和回滚事务慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.mysql-reservation-slow-ms:1000}")
  private double mysqlReservationSlowMillis = 1000.0;

  /** MySQL 事实读取慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.mysql-read-slow-ms:500}")
  private double mysqlReadSlowMillis = 500.0;

  /** 后台恢复批次慢调用阈值，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.mysql-recovery-slow-ms:2000}")
  private double mysqlRecoverySlowMillis = 2000.0;

  /** 所有依赖规则的异常比例阈值。 */
  @Value("${cloudmall.stock.dependency-degrade.exception-ratio:0.5}")
  private double exceptionRatio = 0.5;

  /** 进入 Sentinel 统计窗口所需的最小请求数。 */
  @Value("${cloudmall.stock.dependency-degrade.min-request-amount:20}")
  private int minRequestAmount = 20;

  /** Sentinel 统计窗口，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.stat-interval-ms:60000}")
  private int statIntervalMillis = 60000;

  /** 熔断窗口，单位秒。 */
  @Value("${cloudmall.stock.dependency-degrade.time-window-seconds:10}")
  private int timeWindowSeconds = 10;

  /** Nacos 订阅重试间隔，单位毫秒。 */
  @Value("${cloudmall.stock.dependency-degrade.nacos-retry-ms:5000}")
  private long nacosRetryMillis = 5000L;

  /** Nacos 规则组。 */
  @Value("${cloudmall.stock.dependency-degrade.rule-group:CLOUDMALL_DEV}")
  private String ruleGroup = DEFAULT_RULE_GROUP;

  /** Nacos 地址。 */
  @Value("${spring.cloud.nacos.discovery.server-addr:localhost:18848}")
  private String nacosServerAddress;

  /** Nacos 用户名。 */
  @Value("${spring.cloud.nacos.discovery.username:root}")
  private String nacosUsername;

  /** Nacos 密码，仅用于创建配置客户端。 */
  @Value("${spring.cloud.nacos.discovery.password:root}")
  private String nacosPassword;

  /** 为本配置创建的 Nacos 客户端。 */
  private volatile ConfigService configService;

  /** 是否已经成功注册 Nacos listener。 */
  private volatile boolean subscribed;

  /** 统一处理 Nacos 首次下发和后续刷新。 */
  private final AbstractListener listener =
      new AbstractListener() {
        /** 校验并应用下发的依赖熔断规则完整快照；校验失败时保留当前规则。 */
        @Override
        public void receiveConfigInfo(String content) {
          applyRules(content);
        }
      };

  /** 构造期间先安装内置安全规则，避免配置订阅前出现保护空窗。 */
  public StockDependencySentinelConfiguration() {
    if (DegradeRuleManager.getRules().stream()
        .noneMatch(rule -> DEFAULT_SLOW_MILLIS.containsKey(rule.getResource()))) {
      replaceOwnRules(defaultRules());
    }
  }

  /** 校验本地默认值，安装默认规则并订阅 Nacos。 */
  @PostConstruct
  public void initialize() {
    if (!validDefaults()) {
      throw new IllegalArgumentException(
          "Stock dependency Sentinel default parameters are invalid");
    }
    replaceOwnRules(defaultRules());
    subscribeToNacos();
  }

  /** 首次连接失败时保留本地规则，由定时任务继续重试。 */
  public synchronized void subscribeToNacos() {
    if (subscribed) {
      return;
    }
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
          "Stock dependency Sentinel Nacos subscription failed, dataId={}, group={}; keeping"
              + " current rules",
          DATA_ID,
          ruleGroup,
          unavailable);
    }
  }

  /** Nacos 恢复后按固定间隔重试，已订阅时不重复注册 listener。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.dependency-degrade.nacos-retry-ms:5000}")
  public void retrySubscription() {
    subscribeToNacos();
  }

  /** 严格校验完整快照后替换本配置拥有的规则。 */
  public synchronized boolean applyRules(String content) {
    if (content == null || content.isBlank()) {
      log.warn(
          "Stock dependency Sentinel rules are empty, dataId={}, group={}; keeping current rules",
          DATA_ID,
          ruleGroup);
      return false;
    }
    try {
      ObjectMapper mapper =
          new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
      List<DegradeRule> incoming = mapper.readValue(content, new TypeReference<>() {});
      if (!validSnapshot(incoming)) {
        log.warn(
            "Stock dependency Sentinel rules are invalid, dataId={}, group={}; keeping current"
                + " rules",
            DATA_ID,
            ruleGroup);
        return false;
      }
      replaceOwnRules(incoming);
      log.info(
          "Stock dependency Sentinel rules refreshed, dataId={}, group={}, rules={}",
          DATA_ID,
          ruleGroup,
          incoming.size());
      return true;
    } catch (Exception invalidRules) {
      log.warn(
          "Stock dependency Sentinel rules cannot be parsed, dataId={}, group={}; keeping current"
              + " rules",
          DATA_ID,
          ruleGroup,
          invalidRules);
      return false;
    }
  }

  /** 校验每个固定资源恰好包含慢调用比例和异常比例两条规则。 */
  static boolean validSnapshot(List<DegradeRule> rules) {
    if (rules == null || rules.size() != DEFAULT_SLOW_MILLIS.size() * 2) {
      return false;
    }
    Set<String> seen = new HashSet<>();
    for (DegradeRule rule : rules) {
      if (rule == null
          || !DEFAULT_SLOW_MILLIS.containsKey(rule.getResource())
          || !"default".equals(rule.getLimitApp())
          || rule.getMinRequestAmount() < 1
          || rule.getMinRequestAmount() > 10000
          || rule.getStatIntervalMs() < 1000
          || rule.getStatIntervalMs() > 600000
          || rule.getTimeWindow() < 1
          || rule.getTimeWindow() > 3600
          || !Double.isFinite(rule.getCount())
          || !seen.add(rule.getResource() + "#" + rule.getGrade())) {
        return false;
      }
      if (rule.getGrade() == RuleConstant.DEGRADE_GRADE_RT) {
        if (rule.getCount() <= 0
            || rule.getCount() > 60000
            || !Double.isFinite(rule.getSlowRatioThreshold())
            || rule.getSlowRatioThreshold() <= 0
            || rule.getSlowRatioThreshold() > 1) {
          return false;
        }
      } else if (rule.getGrade() == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
        if (rule.getCount() <= 0 || rule.getCount() > 1) {
          return false;
        }
      } else {
        return false;
      }
    }
    for (String resource : DEFAULT_SLOW_MILLIS.keySet()) {
      if (!seen.contains(resource + "#" + RuleConstant.DEGRADE_GRADE_RT)
          || !seen.contains(resource + "#" + RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)) {
        return false;
      }
    }
    return true;
  }

  /** 校验本地默认参数，避免生成无法被同一校验器接受的快照。 */
  private boolean validDefaults() {
    return Double.isFinite(redisSeckillWriteSlowMillis)
        && redisSeckillWriteSlowMillis > 0
        && redisSeckillWriteSlowMillis <= 60000
        && Double.isFinite(redisProjectionSlowMillis)
        && redisProjectionSlowMillis > 0
        && redisProjectionSlowMillis <= 60000
        && Double.isFinite(mysqlReservationSlowMillis)
        && mysqlReservationSlowMillis > 0
        && mysqlReservationSlowMillis <= 60000
        && Double.isFinite(mysqlReadSlowMillis)
        && mysqlReadSlowMillis > 0
        && mysqlReadSlowMillis <= 60000
        && Double.isFinite(mysqlRecoverySlowMillis)
        && mysqlRecoverySlowMillis > 0
        && mysqlRecoverySlowMillis <= 60000
        && Double.isFinite(slowRatioThreshold)
        && slowRatioThreshold > 0
        && slowRatioThreshold <= 1
        && Double.isFinite(exceptionRatio)
        && exceptionRatio > 0
        && exceptionRatio <= 1
        && minRequestAmount >= 1
        && minRequestAmount <= 10000
        && statIntervalMillis >= 1000
        && statIntervalMillis <= 600000
        && timeWindowSeconds >= 1
        && timeWindowSeconds <= 3600
        && nacosRetryMillis > 0;
  }

  /** 只替换 Stock 依赖资源，保留 flow、param 和其它 degrade 规则。 */
  private static synchronized void replaceOwnRules(List<DegradeRule> replacements) {
    List<DegradeRule> retained =
        new ArrayList<>(
            DegradeRuleManager.getRules().stream()
                .filter(rule -> !DEFAULT_SLOW_MILLIS.containsKey(rule.getResource()))
                .toList());
    retained.addAll(replacements);
    DegradeRuleManager.loadRules(retained);
  }

  /** 根据 application.yml 生成 5 个资源的完整本地默认快照。 */
  private List<DegradeRule> defaultRules() {
    List<DegradeRule> defaults = new ArrayList<>();
    for (Map.Entry<String, Double> entry : DEFAULT_SLOW_MILLIS.entrySet()) {
      defaults.add(
          createRule(
              entry.getKey(), RuleConstant.DEGRADE_GRADE_RT, configuredSlowMillis(entry.getKey())));
      defaults.add(
          createRule(entry.getKey(), RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO, exceptionRatio));
    }
    return defaults;
  }

  /** 按资源读取 application.yml 覆盖值。 */
  private double configuredSlowMillis(String resource) {
    return switch (resource) {
      case "stock-redis-seckill-write" -> redisSeckillWriteSlowMillis;
      case "stock-redis-projection" -> redisProjectionSlowMillis;
      case "stock-mysql-reservation" -> mysqlReservationSlowMillis;
      case "stock-mysql-read" -> mysqlReadSlowMillis;
      case "stock-mysql-recovery" -> mysqlRecoverySlowMillis;
      default ->
          throw new IllegalArgumentException("Unknown Stock dependency resource: " + resource);
    };
  }

  /** 构造一条仅携带本规则所需 Sentinel 字段的默认规则。 */
  private DegradeRule createRule(String resource, int grade, double count) {
    DegradeRule rule = new DegradeRule(resource);
    rule.setLimitApp("default");
    rule.setGrade(grade);
    rule.setCount(count);
    rule.setMinRequestAmount(minRequestAmount);
    rule.setStatIntervalMs(statIntervalMillis);
    rule.setTimeWindow(timeWindowSeconds);
    if (grade == RuleConstant.DEGRADE_GRADE_RT) {
      rule.setSlowRatioThreshold(slowRatioThreshold);
    }
    return rule;
  }

  /** 停止时移除 listener 并关闭本配置创建的 Nacos 客户端。 */
  @PreDestroy
  public void closeSubscription() {
    ConfigService service = configService;
    if (service == null) {
      return;
    }
    try {
      if (subscribed) {
        service.removeListener(DATA_ID, ruleGroup, listener);
      }
    } catch (Exception removalFailure) {
      log.warn(
          "Stock dependency Sentinel listener removal failed, dataId={}, group={}",
          DATA_ID,
          ruleGroup,
          removalFailure);
    }
    try {
      service.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn(
          "Stock dependency Sentinel Nacos client shutdown failed, dataId={}, group={}",
          DATA_ID,
          ruleGroup,
          shutdownFailure);
    }
  }
}
