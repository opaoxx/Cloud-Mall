package com.cloudmall.pay.config;

import com.cloudmall.pay.util.PayDebitModes;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** 新支付协议选择与已有支付事实隔离，12A 阶段只允许已实现的 LEGACY。 */
@Configuration
public class PayDebitModeConfiguration {
  /** 只影响新建支付单的模式；不修改已有记录和恢复意图。 */
  @Value("${cloudmall.pay.debit-mode:LEGACY}")
  private String configuredMode = PayDebitModes.LEGACY;

  /** 不允许通过配置启用尚未接入 TC/资金回调的 TCC 路径。 */
  @PostConstruct
  public void validate() {
    if (!PayDebitModes.LEGACY.equals(configuredMode))
      throw new IllegalStateException("cloudmall.pay.debit-mode 目前只支持 LEGACY；TCC 资金分支尚未启用");
  }

  /** 为本次新建支付单返回固定模式，之后只从数据库读取。 */
  public String newPaymentMode() {
    validate();
    return configuredMode;
  }
}
