package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.pay.config.PayDebitModeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** 新建模式只在整套入口及恢复能力显式开启后放行。 */
class PayTccPublicConfigurationTest {
  @Test
  void defaultRemainsLegacyAndExplicitTccRequiresEveryDependency() {
    PayDebitModeConfiguration configuration = new PayDebitModeConfiguration();
    assertEquals("LEGACY", configuration.newPaymentMode());
    ReflectionTestUtils.setField(configuration, "configuredMode", "TCC");
    MockEnvironment environment = new MockEnvironment();
    ReflectionTestUtils.setField(configuration, "environment", environment);
    for (String feature :
        new String[] {"entry", "coordinator", "receipt", "bridge", "recovery", "delivery"}) {
      assertThrows(IllegalStateException.class, configuration::validate);
      environment.setProperty("cloudmall.pay.tcc-" + feature + ".enabled", "true");
    }
    assertThrows(IllegalStateException.class, configuration::validate);
    environment.setProperty("seata.enabled", "true");
    assertThrows(IllegalStateException.class, configuration::validate);
    environment.setProperty("seata.enable-auto-data-source-proxy", "false");
    assertEquals("TCC", configuration.newPaymentMode());
    for (String feature :
        new String[] {"entry", "coordinator", "receipt", "bridge", "recovery", "delivery"}) {
      environment.setProperty("cloudmall.pay.tcc-" + feature + ".enabled", "false");
      assertThrows(IllegalStateException.class, configuration::validate);
      environment.setProperty("cloudmall.pay.tcc-" + feature + ".enabled", "true");
    }
  }
}
