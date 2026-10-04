package com.cloudmall.stock.config;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 为库存内部秒杀资源提供可动态刷新的 Sentinel 流控和本地兜底。 */
@Configuration
public class SeckillSentinelConfiguration {
  /** 库存内部秒杀预扣的 Sentinel 资源名。 */
  public static final String RESOURCE = "seckill-stock-reservation";

  /** 只包含 Stock 内部秒杀流控规则的 Nacos Data ID。 */
  private static final String FLOW_DATA_ID = "cloudmall-stock-flow-rules";

  /** Stock 规则加载和拒绝原因日志。 */
  private static final Logger log = LoggerFactory.getLogger(SeckillSentinelConfiguration.class);

  /** 单个 Stock 实例的本地默认秒杀 QPS。 */
  @Value("${cloudmall.stock.seckill.sentinel.flow-qps:200}")
  private double defaultFlowQps = 200.0;

  /** Nacos 首次失败后的重试间隔，必须为正数。 */
  @Value("${cloudmall.stock.seckill.sentinel.nacos-retry-ms:5000}")
  private long nacosRetryMs = 5000L;

  /** Nacos 规则来源地址，沿用本地注册中心配置。 */
  @Value("${spring.cloud.nacos.discovery.server-addr:localhost:18848}")
  private String nacosServerAddress;

  /** Nacos 本地开发环境用户名。 */
  @Value("${spring.cloud.nacos.discovery.username:root}")
  private String nacosUsername;

  /** Nacos 本地开发环境密码。 */
  @Value("${spring.cloud.nacos.discovery.password:root}")
  private String nacosPassword;

  /** 隔离规则环境的 Nacos Group，默认使用已评审开发组。 */
  @Value("${cloudmall.stock.seckill.sentinel.rule-group:CLOUDMALL_DEV}")
  private String ruleGroup = "CLOUDMALL_DEV";

  /** 供服务停止时移除 listener 的专用 Nacos 客户端。 */
  private volatile ConfigService configService;

  /** 是否已成功建立当前 Data ID 的规则订阅。 */
  private volatile boolean subscribed;

  /** Nacos 新完整快照到达后先校验再发布到 Sentinel。 */
  private final AbstractListener flowListener = new AbstractListener() {
    @Override
    public void receiveConfigInfo(String content) {
      applyFlowRules(content);
    }
  };

  /** 构造时先安装保守本地规则，避免配置订阅前存在保护空窗。 */
  public SeckillSentinelConfiguration() {
    if (FlowRuleManager.getRules().stream().noneMatch(rule -> RESOURCE.equals(rule.getResource()))) {
      replaceOwnRule(defaultRule(200.0));
    }
  }

  /** 完成配置注入后加载可配置默认规则并订阅 Nacos。 */
  @PostConstruct
  public void initialize() {
    // 1. 拒绝没有保护意义的本地阈值，先安装可配置的实例级兜底。
    if (!Double.isFinite(defaultFlowQps) || defaultFlowQps <= 0)
      throw new IllegalArgumentException("Stock Sentinel default QPS must be finite and positive");
    if (nacosRetryMs <= 0)
      throw new IllegalArgumentException("Stock Sentinel Nacos retry interval must be positive");
    replaceOwnRule(defaultRule(defaultFlowQps));
    // 2. Nacos 首次不可用时保留默认规则，后续调度重试订阅。
    subscribeToNacos();
  }

  /** 订阅唯一 Stock flow Data ID，成功后用同一校验器处理首次完整快照。 */
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
      String content = configService.getConfigAndSignListener(
          FLOW_DATA_ID, ruleGroup, 3000L, flowListener);
      subscribed = true;
      applyFlowRules(content);
    } catch (Exception unavailable) {
      log.warn("Stock Sentinel Nacos 规则订阅失败，dataId={}，group={}，保留当前规则并重试",
          FLOW_DATA_ID, ruleGroup, unavailable);
    }
  }

  /** 首次连接失败后每五秒重试；已订阅时不重复创建 listener。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.seckill.sentinel.nacos-retry-ms:5000}")
  public void retryNacosSubscription() {
    subscribeToNacos();
  }

  /** 拒绝非法或不完整规则，只将有效目标快照替换进全局规则管理器。 */
  public synchronized boolean applyFlowRules(String content) {
    if (content == null || content.isBlank()) {
      log.warn("Stock Sentinel Nacos flow 规则为空，dataId={}，group={}，保留最近有效规则",
          FLOW_DATA_ID, ruleGroup);
      return false;
    }
    try {
      ObjectMapper mapper = new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
      List<FlowRule> incoming = mapper.readValue(content, new TypeReference<>() {});
      if (incoming == null || incoming.size() != 1 || !validRule(incoming.get(0))) {
        log.warn("Stock Sentinel Nacos flow 规则无效，dataId={}，group={}，保留最近有效规则",
            FLOW_DATA_ID, ruleGroup);
        return false;
      }
      replaceOwnRule(incoming.get(0));
      log.info("Stock Sentinel flow 规则已刷新，dataId={}，group={}，instanceQps={}",
          FLOW_DATA_ID, ruleGroup, incoming.get(0).getCount());
      return true;
    } catch (Exception invalidRules) {
      log.warn("Stock Sentinel Nacos flow 规则无法解析，dataId={}，group={}，保留最近有效规则",
          FLOW_DATA_ID, ruleGroup, invalidRules);
      return false;
    }
  }

  /** 只允许当前明确支持的 QPS 直接拒绝规则，拒绝潜在危险的其他模式。 */
  private static boolean validRule(FlowRule rule) {
    return rule != null && RESOURCE.equals(rule.getResource())
        && Double.isFinite(rule.getCount()) && rule.getCount() > 0
        && rule.getGrade() == 1 && rule.getStrategy() == 0
        && rule.getControlBehavior() == 0 && rule.getWarmUpPeriodSec() >= 0
        && rule.getMaxQueueingTimeMs() >= 0 && !rule.isClusterMode()
        && "default".equals(rule.getLimitApp());
  }

  /** 替换本服务资源时保留同 JVM 中所有其它 Sentinel 资源。 */
  private static synchronized void replaceOwnRule(FlowRule replacement) {
    List<FlowRule> retained = new ArrayList<>(FlowRuleManager.getRules().stream()
        .filter(rule -> !RESOURCE.equals(rule.getResource())).toList());
    retained.add(replacement);
    FlowRuleManager.loadRules(retained);
  }

  /** 构造固定 QPS 且单实例生效的本地兜底规则。 */
  private static FlowRule defaultRule(double qps) {
    FlowRule rule = new FlowRule(RESOURCE);
    rule.setGrade(1);
    rule.setCount(qps);
    return rule;
  }

  /** 停止时移除 listener 并关闭本服务创建的 Nacos 长轮询客户端。 */
  @PreDestroy
  public void closeNacosSubscription() {
    ConfigService service = configService;
    if (service == null) return;
    try {
      if (subscribed) service.removeListener(FLOW_DATA_ID, ruleGroup, flowListener);
    } catch (Exception removalFailure) {
      log.warn("Stock Sentinel Nacos listener 移除失败，dataId={}，group={}",
          FLOW_DATA_ID, ruleGroup, removalFailure);
    }
    try {
      service.shutDown();
    } catch (Exception shutdownFailure) {
      log.warn("Stock Sentinel Nacos 客户端关闭失败，dataId={}，group={}",
          FLOW_DATA_ID, ruleGroup, shutdownFailure);
    }
  }

  /** 在身份与归属校验后进入库存内部秒杀资源。 */
  public static com.alibaba.csp.sentinel.Entry enter() throws BlockException {
    return SphU.entry(RESOURCE, EntryType.IN);
  }

  /** 不记录请求载荷地统计已被阻断的库存预扣。 */
  public static void recordBlock() {
    Metrics.counter("sentinel.block", "service", "stock", "resource", RESOURCE,
        "rule_type", "flow", "status", "429").increment();
  }
}
