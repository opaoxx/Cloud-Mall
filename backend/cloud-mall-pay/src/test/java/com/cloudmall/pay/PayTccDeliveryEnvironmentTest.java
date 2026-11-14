package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** 交付夹具的纯准入回归；拒绝回退到共享资源，不启动业务或中间件。 */
class PayTccDeliveryEnvironmentTest {
  @Test
  void everyEndpointAndEvidenceContainerMustBeExplicit() {
    for (String name : valid().stringPropertyNames()) {
      Properties properties = valid();
      properties.remove(name);
      assertThrows(
          IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
      properties.setProperty(name, " ");
      assertThrows(
          IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
    }
  }

  @Test
  void inheritedBridgeAddressAndSourceGuardsRemainInForce() {
    for (var replacement :
        Map.of(
                "cloudmall.mysql.test.url", "jdbc:mysql://127.0.0.1:13307/cloudmall_user",
                "cloudmall.nacos.test.address", "192.0.2.1:20002",
                "cloudmall.seata.test.tc", "127.0.0.1:18091",
                "cloudmall.redis.test.port", "16379")
            .entrySet()) {
      Properties properties = valid();
      properties.setProperty(replacement.getKey(), replacement.getValue());
      assertThrows(
          IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
    }
  }

  @Test
  void rabbitRejectsDefaultRemoteAndMalformedPorts() {
    for (String name :
        List.of("cloudmall.rabbit.test.port", "cloudmall.rabbit.management.test.port")) {
      for (String value :
          List.of(
              "5672",
              "15673",
              "15674",
              "0",
              "-1",
              "65536",
              "99999999999",
              "20004/path",
              " 20004",
              "127.0.0.1:20004")) {
        Properties properties = valid();
        properties.setProperty(name, value);
        assertThrows(
            IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
      }
    }
  }

  @Test
  void rabbitCannotCollideWithAnyDirectOrDerivedEndpoint() {
    for (String name :
        List.of("cloudmall.rabbit.test.port", "cloudmall.rabbit.management.test.port")) {
      for (String port : List.of("20000", "20001", "20002", "20003", "21002", "21003")) {
        Properties properties = valid();
        properties.setProperty(name, port);
        assertThrows(
            IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
      }
    }
    Properties properties = valid();
    properties.setProperty("cloudmall.rabbit.management.test.port", "20004");
    assertThrows(IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
  }

  @Test
  void evidenceRequiresFullImmutableContainerIdentityInsteadOfNameOrPrefix() {
    for (String container :
        List.of(
            "cloud-mall-seata",
            "a".repeat(12),
            "A".repeat(64),
            "g".repeat(64),
            "a".repeat(65),
            "--all",
            "a".repeat(64) + " ")) {
      Properties properties = valid();
      properties.setProperty("cloudmall.seata.test.container-id", container);
      assertThrows(
          IllegalArgumentException.class, () -> PayTccDeliveryEnvironment.read(properties));
    }
    assertEquals("a".repeat(64), PayTccDeliveryEnvironment.read(valid()).tcContainerId());
  }

  @Test
  void namespaceMustBeAnOwnedDeliveryFixture() {
    PayTccDeliveryEnvironment environment = PayTccDeliveryEnvironment.read(valid());
    for (String batch :
        List.of(
            "/",
            "DEFAULT_GROUP",
            "cm16c_",
            "cm16c_" + "A".repeat(32),
            "cm16c_" + "a".repeat(32) + "/")) {
      assertThrows(IllegalArgumentException.class, () -> environment.applicationArguments(batch));
    }
    assertThrows(IllegalArgumentException.class, () -> environment.applicationArguments(null));
  }

  @Test
  void allProcessesReceiveOneConsistentImmutableEnvironment() {
    Properties properties = valid();
    PayTccDeliveryEnvironment environment = PayTccDeliveryEnvironment.read(properties);
    properties.setProperty("cloudmall.rabbit.test.port", "15673");
    properties.setProperty("cloudmall.nacos.test.address", "127.0.0.1:18848");
    String batch = "cm16c_" + "b".repeat(32);
    List<String> arguments = environment.applicationArguments(batch);
    Map<String, String> options = new HashMap<>();
    for (String argument : arguments) {
      String[] option = argument.split("=", 2);
      assertNull(options.put(option[0], option[1]), "duplicate configuration: " + option[0]);
    }
    assertEquals("127.0.0.1:20002", options.get("--spring.cloud.nacos.discovery.server-addr"));
    assertEquals("127.0.0.1:20001", options.get("--seata.service.grouplist.default"));
    assertEquals("20003", options.get("--spring.redis.port"));
    assertEquals("20004", options.get("--spring.rabbitmq.port"));
    assertEquals("127.0.0.1", options.get("--spring.rabbitmq.host"));
    assertEquals("root", options.get("--spring.rabbitmq.username"));
    assertEquals("root", options.get("--spring.rabbitmq.password"));
    assertEquals(batch, options.get("--spring.rabbitmq.virtual-host"));
    assertFalse(options.containsKey("--management.health.rabbit.enabled"));
    assertEquals(20005, environment.rabbitManagementPort());
    assertEquals(20004, environment.rabbitPort());
    assertEquals(20003, environment.redisPort());
    assertEquals(20000, environment.mysqlPort());
    assertEquals("127.0.0.1:20002", environment.nacosAddress());
    assertEquals(valid().getProperty("cloudmall.mysql.test.url"), environment.mysqlUrl());
    assertThrows(UnsupportedOperationException.class, () -> arguments.add("unexpected override"));
  }

  /** 每例使用自己的配置副本，不修改当前测试 JVM 的系统属性。 */
  private Properties valid() {
    Properties properties = new Properties();
    properties.setProperty(
        "cloudmall.mysql.test.url",
        "jdbc:mysql://127.0.0.1:20000/cloudmall_user?allowPublicKeyRetrieval=true");
    properties.setProperty("cloudmall.seata.test.tc", "127.0.0.1:20001");
    properties.setProperty("cloudmall.nacos.test.address", "127.0.0.1:20002");
    properties.setProperty("cloudmall.redis.test.port", "20003");
    properties.setProperty("cloudmall.rabbit.test.port", "20004");
    properties.setProperty("cloudmall.rabbit.management.test.port", "20005");
    properties.setProperty("cloudmall.seata.test.container-id", "a".repeat(64));
    return properties;
  }
}
