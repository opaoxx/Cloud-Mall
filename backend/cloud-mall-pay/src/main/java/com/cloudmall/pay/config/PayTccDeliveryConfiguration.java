package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import javax.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 交付默认关闭；启用必须同时具备已校验的 coordinator 与资源配置。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.pay.tcc-delivery.enabled", havingValue = "true")
@DependsOn("payTccExecutionTableInitializer")
public class PayTccDeliveryConfiguration {
  /** 新旧数据库的交付表初始化入口。 */
  private final PayTccDeliveryMapper deliveries;

  /** 创建配置，coordinator 依赖不存在时启动失败。 */
  public PayTccDeliveryConfiguration(
      PayTccDeliveryMapper deliveries, PayTccCoordinatorService coordinator) {
    this.deliveries = deliveries;
  }

  /** 只幂等建交付表，不改变旧支付或任何资金状态。 */
  @PostConstruct
  public void prepareTable() {
    deliveries.ensureTable();
  }
}
