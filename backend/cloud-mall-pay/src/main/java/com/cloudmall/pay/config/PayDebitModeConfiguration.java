package com.cloudmall.pay.config;

import com.cloudmall.pay.util.PayDebitModes;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 新支付协议显式选择；既有支付始终以持久模式路由。 */
@Configuration
public class PayDebitModeConfiguration {
  /** 只影响新建支付单的模式；不修改已有记录和恢复意图。 */
  @Value("${cloudmall.pay.debit-mode:LEGACY}")
  private String configuredMode = PayDebitModes.LEGACY;

  /** 验证显式 TCC 新建模式的本机完整依赖，不修改任何持久模式。 */
  @Autowired private Environment environment;

  /** 缺省保持 Legacy；显式 TCC 必须具备入口、资源、恢复及交付开关。 */
  @PostConstruct
  public void validate() {
    if (PayDebitModes.LEGACY.equals(configuredMode)) return;
    if (!PayDebitModes.TCC.equals(configuredMode) || environment == null)
      throw new IllegalStateException("未知支付模式或 TCC 依赖未配置");
    for (String feature :
        new String[] {"entry", "coordinator", "receipt", "bridge", "recovery", "delivery"})
      if (!environment.getProperty(
          "cloudmall.pay.tcc-" + feature + ".enabled", Boolean.class, false))
        throw new IllegalStateException("TCC 新支付要求启用 tcc-" + feature);
    if (!environment.getProperty("seata.enabled", Boolean.class, false)
        || environment.getProperty("seata.enable-auto-data-source-proxy", Boolean.class, true))
      throw new IllegalStateException("TCC 新支付要求启用 Seata 并关闭 AT 自动代理");
  }

  /** 为本次新建支付单返回固定模式，之后只从数据库读取。 */
  public String newPaymentMode() {
    validate();
    return configuredMode;
  }
}
