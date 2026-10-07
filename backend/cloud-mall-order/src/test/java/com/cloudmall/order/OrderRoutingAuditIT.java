package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用真正打包的Order进程验证路由审计；数据库、Redis和Rabbit vhost均隔离。 */
class OrderRoutingAuditIT {
  /** 原业务库只读连接。 */
  private static JdbcTemplate source;

  /** 原表名和校验和。 */
  private static List<String> sourceTables;

  private static List<Map<String, Object>> originalChecksums;

  /** 原库连接地址，仅用于替换为随机测试库。 */
  private static String sourceUrl;

  /** 本机只读验证HTTP客户端。 */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 审计响应与清理证据序列化器。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 当前用例唯一资源标识。 */
  private String batch;

  /** 测试数据库连接。 */
  private JdbcTemplate fixture;

  /** 持久化日志目录。 */
  private Path artifacts;

  /** 当前用例独立Order进程及回环监听地址。 */
  private Process application;

  private int port;

  /** 测试Redis容器随机映射端口。 */
  private int redisPort;

  /** 预先删除的未来月表，验证拒绝profile不会先执行DDL。 */
  private String futureSuffix;

  @BeforeAll
  static void readSource() {
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要cloudmall_order测试URL");
    source = jdbc(sourceUrl);
    sourceTables = source.queryForList("SHOW TABLES", String.class);
    for (String table : sourceTables)
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
    originalChecksums = checksums();
  }

  @BeforeEach
  void prepare() throws Exception {
    batch = "cm17a_" + UUID.randomUUID().toString().replace("-", "");
    artifacts = Path.of("target", "cm17a-routing", batch).toAbsolutePath();
    Files.createDirectories(artifacts);
    source.execute(
        "CREATE DATABASE " + batch + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    fixture = jdbc(sourceUrl.replace("/cloudmall_order", "/" + batch));
    for (String table : sourceTables)
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    futureSuffix =
        YearMonth.now(ZoneId.of("Asia/Shanghai"))
            .plusMonths(2)
            .format(DateTimeFormatter.ofPattern("yyyyMM"));
    for (String prefix : List.of("mall_order_", "mall_order_item_", "order_status_log_"))
      fixture.execute("DROP TABLE IF EXISTS " + prefix + futureSuffix);
    command(
        "docker",
        "run",
        "-d",
        "--name",
        batch + "-redis",
        "--label",
        "cloudmall.test=" + batch,
        "-p",
        "127.0.0.1::6379",
        "redis:7.0",
        "redis-server",
        "--requirepass",
        "root");
    String mapping = command("docker", "port", batch + "-redis", "6379").trim();
    redisPort = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
    rabbit("PUT", "/api/vhosts/" + batch, "{}");
    rabbit(
        "PUT",
        "/api/permissions/" + batch + "/root",
        "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
    Files.writeString(
        artifacts.resolve("fixture.json"),
        JSON.writeValueAsString(
            Map.of("database", batch, "redis", batch + "-redis", "vhost", batch)));
  }

  @AfterEach
  void cleanup() throws Exception {
    if (application != null) {
      application.destroyForcibly();
      assertTrue(application.waitFor(20, TimeUnit.SECONDS));
    }
    if (batch == null || !batch.matches("cm17a_[0-9a-f]{32}")) return;
    assertEquals(originalChecksums, checksums());
    if (fixture != null)
      Files.writeString(
          artifacts.resolve("tables.json"),
          JSON.writeValueAsString(fixture.queryForList("SHOW TABLES", String.class)));
    source.execute("DROP DATABASE IF EXISTS " + batch);
    rabbit("DELETE", "/api/vhosts/" + batch, null);
    var inspected = JSON.readTree(command("docker", "inspect", batch + "-redis"));
    assertEquals(
        batch, inspected.get(0).path("Config").path("Labels").path("cloudmall.test").asText());
    command("docker", "rm", "-f", batch + "-redis");
  }

  @Test
  void unsupportedShardingProfileFailsBeforeRollingTableDdl() throws Exception {
    start("--spring.profiles.active=sharding");
    awaitExitOrHealthy();
    assertFalse(application.isAlive(), "未接入的sharding profile仍成功启动，日志: " + artifacts);
    assertNotEquals(0, application.exitValue());
    assertTrue(
        Files.readString(artifacts.resolve("order.log")).contains("ORDER_ROUTING_UNSUPPORTED"));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",
            Integer.class,
            batch,
            "mall_order_" + futureSuffix));
  }

  @Test
  void defaultProcessReportsPhysicalRoutingAndExpectedWindow() throws Exception {
    start();
    assertRoutingInfo();
  }

  /** 使用真实Seata自动代理配置，确认审计不会误拒绝原默认事务绑定。 */
  @Test
  void seataEnabledProcessRetainsValidTransactionBinding() throws Exception {
    start("--seata.enabled=true", "--seata.application-id=" + batch);
    assertRoutingInfo();
  }

  private void assertRoutingInfo() throws Exception {
    awaitExitOrHealthy();
    assertTrue(application.isAlive(), "默认Order启动失败，日志: " + artifacts);
    HttpResponse<String> response = get("/actuator/info");
    assertEquals(200, response.statusCode(), response.body());
    Files.writeString(artifacts.resolve("info.json"), response.body());
    var routing = JSON.readTree(response.body()).path("orderRouting");
    assertEquals("dynamic-monthly", routing.path("routingMode").asText());
    assertEquals("HikariDataSource", routing.path("underlyingDataSourceType").asText());
    assertTrue(routing.path("transactionManagerType").asText().contains("TransactionManager"));
    assertEquals(4, routing.path("expectedMonths").size());
    assertEquals(futureSuffix, routing.path("expectedMonths").get(3).asText());
    assertFalse(response.body().contains("jdbc:"));
    assertFalse(response.body().contains("password"));
    assertFalse(response.body().contains("root"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",
            Integer.class,
            batch,
            "mall_order_" + futureSuffix));
  }

  private void start(String... additional) throws Exception {
    port = availablePort();
    List<String> arguments =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-jar",
                Path.of("target", "cloud-mall-order-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.datasource.url=" + sourceUrl.replace("/cloudmall_order", "/" + batch),
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--spring.cloud.sentinel.eager=false",
                "--cloudmall.order.stock-degrade.rule-group=" + batch,
                "--cloudmall.order.seckill.sentinel.rule-group=" + batch,
                "--spring.rabbitmq.virtual-host=" + batch,
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.redis.port=" + redisPort,
                "--spring.redis.password=root",
                "--seata.enabled=false"));
    for (String option : additional) {
      String prefix = option.substring(0, option.indexOf('=') + 1);
      arguments.removeIf(existing -> existing.startsWith(prefix));
      arguments.add(option);
    }
    application =
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve("order.log").toFile())
            .start();
  }

  private void awaitExitOrHealthy() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (System.nanoTime() < deadline) {
      if (!application.isAlive()) return;
      try {
        if (get("/actuator/health").statusCode() == 200) return;
      } catch (Exception unavailable) {
        /* 等待当前用例自己的服务启动。 */
      }
      Thread.sleep(150);
    }
    fail("Order启动检查超时: " + artifacts);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void rabbit(String method, String path, String body) throws Exception {
    String credentials =
        Base64.getEncoder()
            .encodeToString("root:root".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    // 仅重试本测试自有资源的幂等PUT/DELETE，连接中断不能掩盖业务验收。
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        HttpResponse<String> response =
            HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:15674" + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json")
                    .method(
                        method,
                        body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(
            response.statusCode() >= 200 && response.statusCode() < 300
                || "DELETE".equals(method) && response.statusCode() == 404,
            response.body());
        return;
      } catch (java.io.IOException unavailable) {
        if (attempt == 2) throw unavailable;
        Thread.sleep(300);
      }
    }
  }

  private static String command(String... arguments) throws Exception {
    // inspect输出可能超过Windows管道容量，不能先wait再读未排空的管道。
    Path output = Files.createTempFile("cloudmall-routing-fixture-", ".log");
    Process process = null;
    try {
      process =
          new ProcessBuilder(arguments)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      assertTrue(process.waitFor(40, TimeUnit.SECONDS), "夹具命令超时");
      String result = Files.readString(output);
      assertEquals(0, process.exitValue(), result);
      return result;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      Files.deleteIfExists(output);
    }
  }

  private static int availablePort() throws Exception {
    for (int attempt = 0; attempt < 128; attempt++) {
      try (ServerSocket socket =
          new ServerSocket(ThreadLocalRandom.current().nextInt(22000, 42000))) {
        return socket.getLocalPort();
      } catch (java.io.IOException unavailable) {
        /* 换一个独立测试端口。 */
      }
    }
    throw new IllegalStateException("没有可用测试端口");
  }

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
  }

  private static List<Map<String, Object>> checksums() {
    return source.queryForList(
        "CHECKSUM TABLE "
            + sourceTables.stream()
                .map(table -> "cloudmall_order." + table)
                .collect(java.util.stream.Collectors.joining(",")));
  }
}
