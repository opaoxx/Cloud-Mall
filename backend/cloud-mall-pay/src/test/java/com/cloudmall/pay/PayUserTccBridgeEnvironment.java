package com.cloudmall.pay;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 派发桥原生验收的显式本机环境；解析过程不创建数据库、网络客户端或子进程。
 *
 * @param mysqlUrl 仅用于克隆空源表的本轮MySQL URL
 * @param nacosAddress 本轮Nacos HTTP地址，其gRPC端口按原约定派生
 * @param tcAddress 本轮file注册模式的TC地址
 * @param redisPort 本轮独占Redis端口
 */
record PayUserTccBridgeEnvironment(
    String mysqlUrl, String nacosAddress, String tcAddress, int redisPort) {
  /** 限定原夹具源库名与唯一需要的MySQL连接选项。 */
  private static final Pattern MYSQL_URL =
      Pattern.compile(
          "jdbc:mysql://127\\.0\\.0\\.1:([0-9]+)/cloudmall_user(?:\\?allowPublicKeyRetrieval=true)?");

  /** 仅接受本机数值端口，禁止DNS地址、多服务列表及URL路径。 */
  private static final Pattern ADDRESS = Pattern.compile("127\\.0\\.0\\.1:([0-9]+)");

  /** 在任何外部操作前核验全部必需属性和端口关系。 */
  static PayUserTccBridgeEnvironment read(Properties properties) {
    String mysql = required(properties, "cloudmall.mysql.test.url");
    String nacos = required(properties, "cloudmall.nacos.test.address");
    String tc = required(properties, "cloudmall.seata.test.tc");
    int mysqlPort = matchedPort(MYSQL_URL, mysql);
    int nacosPort = matchedPort(ADDRESS, nacos);
    int tcPort = matchedPort(ADDRESS, tc);
    int redis = isolatedPort(required(properties, "cloudmall.redis.test.port"));
    if (nacosPort + 1001 > 65535
        || new HashSet<>(
                    List.of(
                        mysqlPort, nacosPort, tcPort, redis, nacosPort + 1000, nacosPort + 1001))
                .size()
            != 6) {
      throw new IllegalArgumentException("隔离端口重复或Nacos gRPC端口越界");
    }
    return new PayUserTccBridgeEnvironment(mysql, nacos, tc, redis);
  }

  /** 父Pay和两个子User共用同一解析结果，不继承各自application.yml的默认地址。 */
  List<String> applicationArguments() {
    return List.of(
        "--spring.cloud.nacos.discovery.server-addr=" + nacosAddress,
        "--spring.cloud.nacos.discovery.username=root",
        "--spring.cloud.nacos.discovery.password=root",
        "--seata.service.grouplist.default=" + tcAddress,
        "--spring.redis.host=127.0.0.1",
        "--spring.redis.port=" + redisPort,
        "--spring.redis.password=root",
        "--spring.rabbitmq.host=127.0.0.1",
        "--spring.rabbitmq.port=1",
        "--management.health.rabbit.enabled=false",
        "--cloudmall.internal.callback-token=cloudmall-local-callback");
  }

  /** 缺少显式配置时拒绝运行，不回退到默认业务中间件。 */
  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少隔离测试配置: " + name);
    return value;
  }

  /** 完整匹配目标地址，保持源库和loopback约束。 */
  private static int matchedPort(Pattern pattern, String address) {
    Matcher matcher = pattern.matcher(address);
    if (!matcher.matches()) throw new IllegalArgumentException("测试地址必须为显式本机隔离地址");
    return isolatedPort(matcher.group(1));
  }

  /** 只允许非默认高位端口，不自动选择或修正不合法值。 */
  private static int isolatedPort(String text) {
    if (!text.matches("[0-9]+")) throw new IllegalArgumentException("无效隔离端口");
    int value = Integer.parseInt(text);
    if (value < 19000 || value > 65535) throw new IllegalArgumentException("隔离端口必须为19000..65535");
    return value;
  }
}
