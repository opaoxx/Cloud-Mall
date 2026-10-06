package com.cloudmall.pay.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 取消是独立 opt-in，依赖完整公开 TCC 入口，但不改变 16D 的默认门槛。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
@DependsOn("payTccCancelTableInitializer")
public class PayTccCancelConfiguration {
  /** 缺少已验证的公开 TCC 入口时启动即失败。 */
  public PayTccCancelConfiguration(PayTccEntryConfiguration entry) {}
}
