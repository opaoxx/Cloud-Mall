package com.cloudmall.product.config;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 启动时记录索引读写开关，便于核对本地回填发布状态。 */
@Component
public class ProductIndexFeatureConfiguration {
  /** 商品索引配置审计日志。 */
  private static final Logger log = LoggerFactory.getLogger(ProductIndexFeatureConfiguration.class);

  /** Spring 最终解析后的商品索引配置。 */
  private final Environment environment;

  /** 创建索引配置审计组件。 */
  public ProductIndexFeatureConfiguration(Environment environment) {
    this.environment = environment;
  }

  /** 启动时写明搜索、投递、消费、死信重放和回填的实际开关。 */
  @PostConstruct
  public void logEffectiveConfiguration() {
    boolean onlineEnabled =
        environment.getProperty("cloudmall.product.metrics.enabled", Boolean.class, false);
    String onlineSort = environment.getProperty("cloudmall.product.metrics.sort-mode", "LEGACY");
    if (!java.util.Set.of("LEGACY", "ONLINE").contains(onlineSort)
        || ("ONLINE".equals(onlineSort) && !onlineEnabled))
      throw new IllegalStateException("METRIC_SORT_MODE_INVALID");
    if (onlineEnabled
        && "ONLINE".equals(onlineSort)
        && environment.getProperty("cloudmall.product.search.enabled", Boolean.class, false)
        && !environment
            .getProperty("cloudmall.product.elasticsearch.index", "cloudmall_product")
            .matches("cm21_[0-9a-f]{32}_product"))
      throw new IllegalStateException("METRIC_INDEX_NAME_INVALID");
    if (onlineEnabled
        && "ONLINE".equals(onlineSort)
        && environment.getProperty("cloudmall.product.search.enabled", Boolean.class, false)) {
      if (!environment.getProperty("cloudmall.product.index.dispatch-enabled", Boolean.class, true)
          || !environment.getProperty(
              "cloudmall.product.index.consumer-enabled", Boolean.class, true)
          || !environment.getProperty(
              "cloudmall.product.index.rebuild-enabled", Boolean.class, false))
        throw new IllegalStateException("METRIC_ONLINE_SEARCH_COMPONENT_REQUIRED");
    }
    // 1. 使用最终解析的配置而非猜测配置文件默认值。
    // 2. 一次性打印全部影响搜索发布的开关和物理索引名。
    // 3. 搜索关闭时不宣称 ES 商品搜索已生效。
    log.info(
        "商品索引配置 index={} searchEnabled={} dispatchEnabled={} consumerEnabled={} "
            + "dlqReplayEnabled={} rebuildEnabled={}",
        environment.getProperty("cloudmall.product.elasticsearch.index", "cloudmall_product"),
        environment.getProperty("cloudmall.product.search.enabled", Boolean.class, false),
        environment.getProperty("cloudmall.product.index.dispatch-enabled", Boolean.class, true),
        environment.getProperty("cloudmall.product.index.consumer-enabled", Boolean.class, true),
        environment.getProperty("cloudmall.product.index.dlq-replay-enabled", Boolean.class, true),
        environment.getProperty("cloudmall.product.index.rebuild-enabled", Boolean.class, false));
  }
}
