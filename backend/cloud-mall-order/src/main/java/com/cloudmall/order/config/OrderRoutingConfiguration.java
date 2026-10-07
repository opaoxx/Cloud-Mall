package com.cloudmall.order.config;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 订单路由模式的早期启动门，避免不支持的 Sharding 数据源先被实例化。 */
@Configuration
public class OrderRoutingConfiguration {
  /** 固定支持的动态月表路由模式。 */
  static final String DYNAMIC_MONTHLY = "dynamic-monthly";

  /** 固定错误码，不包含任何配置值。 */
  static final String UNSUPPORTED = "ORDER_ROUTING_UNSUPPORTED";

  /** 注册静态 BeanFactoryPostProcessor，在数据源 Bean 创建前拒绝不支持的路由。 */
  @Bean
  public static BeanFactoryPostProcessor orderRoutingProfileGuard(Environment environment) {
    return beanFactory -> validate(environment);
  }

  /** 校验 profile 和 routing.mode；该方法不访问 Bean、数据源或连接。 */
  static void validate(Environment environment) {
    boolean shardingProfile =
        Arrays.stream(
                environment.getActiveProfiles().length == 0
                    ? environment.getDefaultProfiles()
                    : environment.getActiveProfiles())
            .map(profile -> profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT))
            .anyMatch("sharding"::equals);
    String routingMode = environment.getProperty("cloudmall.order.routing.mode", DYNAMIC_MONTHLY);
    if (shardingProfile || routingMode.isBlank()) throw unsupported();
    if (!DYNAMIC_MONTHLY.equals(routingMode.trim())) throw unsupported();
  }

  /** 返回不回显配置内容的固定异常。 */
  private static IllegalStateException unsupported() {
    return new IllegalStateException(UNSUPPORTED);
  }
}
