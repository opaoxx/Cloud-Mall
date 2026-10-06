package com.cloudmall.pay.config;

import com.cloudmall.pay.service.impl.PayTccDeliveryScheduler;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** 公开 TCC 入口默认关闭，保留旧记录时也必须保留恢复与交付依赖。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.pay.tcc-entry.enabled", havingValue = "true")
public class PayTccEntryConfiguration {
  /** 缺少已校验的资源/恢复/交付依赖时启动即失败。 */
  public PayTccEntryConfiguration(
      PayTccReceiptConfiguration receipt,
      PayTccRecoveryScheduler recovery,
      PayTccDeliveryScheduler delivery) {
    receipt.validate();
  }
}
