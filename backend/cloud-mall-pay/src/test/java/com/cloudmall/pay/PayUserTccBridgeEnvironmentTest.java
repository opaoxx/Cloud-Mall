package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** 无中间件依赖的准入测试，防止真实夹具误连默认资源或丢失子进程参数。 */
class PayUserTccBridgeEnvironmentTest {
  @Test
  void everyExternalEndpointIsRequiredBeforeSetup() {
    for (String name : valid().stringPropertyNames()) {
      Properties missing = valid();
      missing.remove(name);
      assertThrows(IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(missing));
      missing.setProperty(name, " ");
      assertThrows(IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(missing));
    }
  }

  @Test
  void mysqlCannotSelectRemoteDefaultOrDifferentSourceSchema() {
    for (String url :
        List.of(
            "jdbc:mysql://localhost:19980/cloudmall_user",
            "jdbc:mysql://127.0.0.1:13307/cloudmall_user",
            "jdbc:mysql://192.0.2.1:19980/cloudmall_user",
            "jdbc:mysql://127.0.0.1:19980/mysql",
            "jdbc:mysql://127.0.0.1:19980/cloudmall_user?allowPublicKeyRetrieval=true&other=true")) {
      Properties properties = valid();
      properties.setProperty("cloudmall.mysql.test.url", url);
      assertThrows(
          IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(properties));
    }
  }

  @Test
  void discoveryAndTcRejectFallbacksRemoteListsAndInvalidPorts() {
    for (String name : List.of("cloudmall.nacos.test.address", "cloudmall.seata.test.tc")) {
      for (String address :
          List.of(
              "127.0.0.1:18091",
              "localhost:19982",
              "192.0.2.1:19982",
              "127.0.0.1:19982/path",
              "127.0.0.1:19982,127.0.0.1:19983",
              "127.0.0.1:65536")) {
        Properties properties = valid();
        properties.setProperty(name, address);
        assertThrows(
            IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(properties));
      }
    }
    for (String value : List.of("16379", "0", "-1", "65536", "invalid")) {
      Properties properties = valid();
      properties.setProperty("cloudmall.redis.test.port", value);
      assertThrows(
          IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(properties));
    }
  }

  @Test
  void directAndDerivedGrpcPortCollisionsAreRejected() {
    for (Map.Entry<String, String> replacement :
        Map.of(
                "cloudmall.redis.test.port", "20982",
                "cloudmall.seata.test.tc", "127.0.0.1:19980",
                "cloudmall.nacos.test.address", "127.0.0.1:65000")
            .entrySet()) {
      Properties properties = valid();
      properties.setProperty(replacement.getKey(), replacement.getValue());
      assertThrows(
          IllegalArgumentException.class, () -> PayUserTccBridgeEnvironment.read(properties));
    }
  }

  @Test
  void sameExplicitArgumentsAreReusableByParentAndChildApplications() {
    PayUserTccBridgeEnvironment environment = PayUserTccBridgeEnvironment.read(valid());
    assertEquals("127.0.0.1:19982", environment.nacosAddress());
    assertEquals("127.0.0.1:19981", environment.tcAddress());
    assertEquals(19983, environment.redisPort());
    List<String> arguments = environment.applicationArguments();
    assertTrue(arguments.contains("--spring.cloud.nacos.discovery.server-addr=127.0.0.1:19982"));
    assertTrue(arguments.contains("--seata.service.grouplist.default=127.0.0.1:19981"));
    assertTrue(arguments.contains("--spring.redis.port=19983"));
    assertTrue(arguments.contains("--spring.cloud.nacos.discovery.username=root"));
    assertTrue(arguments.contains("--spring.cloud.nacos.discovery.password=root"));
    assertFalse(
        arguments.stream()
            .anyMatch(
                argument ->
                    argument.contains("18848")
                        || argument.contains("18091")
                        || argument.contains("16379")));
    assertThrows(UnsupportedOperationException.class, () -> arguments.add("unexpected override"));
  }

  /** 每例复制独立属性，避免测试之间或当前JVM系统属性污染。 */
  private Properties valid() {
    Properties properties = new Properties();
    properties.setProperty(
        "cloudmall.mysql.test.url",
        "jdbc:mysql://127.0.0.1:19980/cloudmall_user?allowPublicKeyRetrieval=true");
    properties.setProperty("cloudmall.nacos.test.address", "127.0.0.1:19982");
    properties.setProperty("cloudmall.seata.test.tc", "127.0.0.1:19981");
    properties.setProperty("cloudmall.redis.test.port", "19983");
    return properties;
  }
}
