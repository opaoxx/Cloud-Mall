package com.cloudmall.pay;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** 四服务交付测试的显式环境；只解析参数，不连接中间件或管理资源生命周期。 */
final class PayTccDeliveryEnvironment {
  /** 复用已经验证的 MySQL、Nacos、TC 和 Redis 本机隔离地址规则。 */
  private final PayUserTccBridgeEnvironment bridge;

  /** 本轮 Rabbit AMQP 端口，业务进程与测试发布者必须使用同一入口。 */
  private final int rabbitPort;

  /** 本轮 Rabbit 管理端口，vhost 管理和只读阶段证据必须使用同一入口。 */
  private final int rabbitManagementPort;

  /** 本轮 TC 的完整 Docker 容器 ID，日志采集不通过可复用的容器名查找。 */
  private final String tcContainerId;

  private PayTccDeliveryEnvironment(
      PayUserTccBridgeEnvironment bridge,
      int rabbitPort,
      int rabbitManagementPort,
      String tcContainerId) {
    this.bridge = bridge;
    this.rabbitPort = rabbitPort;
    this.rabbitManagementPort = rabbitManagementPort;
    this.tcContainerId = tcContainerId;
  }

  /** 所有必需配置均在建库、HTTP 或子进程启动前校验；缺项时不使用业务默认值。 */
  static PayTccDeliveryEnvironment read(Properties properties) {
    // 1. 保留原桥接测试对地址、源库和 Nacos 派生端口的约束。
    PayUserTccBridgeEnvironment bridge = PayUserTccBridgeEnvironment.read(properties);
    int rabbit = port(properties, "cloudmall.rabbit.test.port");
    int management = port(properties, "cloudmall.rabbit.management.test.port");
    String container = properties.getProperty("cloudmall.seata.test.container-id");
    if (container == null || !container.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("需要本轮TC的完整64位小写容器ID");
    }

    // 2. 业务入口和证据入口不能与任何直接或派生端口重叠。
    int nacos = URI.create("http://" + bridge.nacosAddress()).getPort();
    Set<Integer> ports =
        new HashSet<>(
            List.of(
                URI.create(bridge.mysqlUrl().substring(5)).getPort(),
                nacos,
                nacos + 1000,
                nacos + 1001,
                URI.create("http://" + bridge.tcAddress()).getPort(),
                bridge.redisPort(),
                rabbit,
                management));
    if (ports.size() != 8) throw new IllegalArgumentException("交付测试的业务和证据端口不能重复");
    return new PayTccDeliveryEnvironment(bridge, rabbit, management, container);
  }

  /** 仅供克隆本轮空源表，不从默认业务数据源读取。 */
  String mysqlUrl() {
    return bridge.mysqlUrl();
  }

  /** 四个 owned 数据库必须与本轮源库共用此精确端口，禁止证据读取其它实例。 */
  int mysqlPort() {
    return URI.create(bridge.mysqlUrl().substring(5)).getPort();
  }

  /** NamingService 与各服务共用的发现入口。 */
  String nacosAddress() {
    return bridge.nacosAddress();
  }

  /** 已由外部 Compose 准备的 Redis；本夹具不创建或删除该实例。 */
  int redisPort() {
    return bridge.redisPort();
  }

  /** 真实消息发布者使用的 AMQP 入口。 */
  int rabbitPort() {
    return rabbitPort;
  }

  /** vhost 管理与只读证据共用的管理入口。 */
  int rabbitManagementPort() {
    return rabbitManagementPort;
  }

  /** TC 日志和身份只读采集的精确容器目标。 */
  String tcContainerId() {
    return tcContainerId;
  }

  /** 父 Pay、所有子 JVM 及 Order helper 共用，调用者只补充各服务的功能配置。 */
  List<String> applicationArguments(String batch) {
    if (batch == null || !batch.matches("cm16c_[0-9a-f]{32}")) {
      throw new IllegalArgumentException("需要本轮交付fixture的独立group/vhost");
    }
    List<String> arguments = new ArrayList<>();
    for (String argument : bridge.applicationArguments()) {
      if (!argument.startsWith("--spring.rabbitmq.")
          && !argument.startsWith("--management.health.rabbit.")) arguments.add(argument);
    }
    arguments.addAll(
        List.of(
            "--spring.rabbitmq.host=127.0.0.1",
            "--spring.rabbitmq.port=" + rabbitPort,
            "--spring.rabbitmq.username=root",
            "--spring.rabbitmq.password=root",
            "--spring.rabbitmq.virtual-host=" + batch));
    return List.copyOf(arguments);
  }

  /** Rabbit 的两个入口均要求显式高位端口，不自动修正或选择端口。 */
  private static int port(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || !value.matches("[0-9]{1,5}")) {
      throw new IllegalArgumentException("缺少或无效隔离端口: " + name);
    }
    int port = Integer.parseInt(value);
    if (port < 19000 || port > 65535) throw new IllegalArgumentException("隔离端口必须为19000..65535");
    return port;
  }
}
