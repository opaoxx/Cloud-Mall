package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.mapper.ProductIndexConsumptionMapper;
import com.cloudmall.product.mapper.ProductIndexDeadLetterMapper;
import com.cloudmall.product.mapper.ProductIndexMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.cloudmall.product.service.impl.index.ElasticsearchProductIndexWriter;
import com.cloudmall.product.service.impl.index.ProductIndexConsumer;
import com.cloudmall.product.service.impl.index.ProductIndexConsumptionService;
import com.cloudmall.product.service.impl.index.ProductIndexDlqReplayer;
import com.cloudmall.product.service.impl.index.ProductIndexDocumentLoader;
import com.cloudmall.product.service.impl.index.ProductIndexOutboxDispatcher;
import com.cloudmall.product.service.impl.index.ProductIndexOutboxService;
import com.cloudmall.product.service.impl.search.ElasticsearchProductKeywordSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.http.HttpHost;
import org.apache.ibatis.session.SqlSessionFactory;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 在显式独占中间件上验证常规商品索引；失败保留UUID数据库、索引及消息。 */
@SuppressWarnings("deprecation")
class ProductIndexSynchronizationIT {
  @Test
  void committedEventsKeepEnvelopeReplayCurrentFactsAndVisibility() throws Exception {
    try (Fixture fixture = new Fixture("delivery")) {
      // 1. 本地回滚同时撤销事实与原outbox，提交后才能从broker观察消息。
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.transaction.executeWithoutResult(
                  status -> {
                    fixture.seed(42);
                    fixture.append(42, 1, "PRODUCT_CHANGED");
                    throw new IllegalStateException("rollback probe");
                  }));
      assertEquals(0, fixture.count("product"));
      assertEquals(0, fixture.count("product_index_outbox"));
      String eventId =
          fixture.transaction.execute(
              status -> {
                fixture.seed(41);
                return fixture.append(41, 1, "PRODUCT_CHANGED");
              });
      fixture.dispatch();
      Message published =
          fixture.rabbit.receive(ProductMessagingConfiguration.PRODUCT_INDEX_QUEUE, 5000);
      assertNotNull(published);
      assertEquals("application/json", published.getMessageProperties().getContentType());
      assertEquals(eventId, published.getMessageProperties().getMessageId());
      assertEquals(fixture.envelope(eventId), fixture.json.readTree(published.getBody()));
      Map<String, Object> beforeConsumption = fixture.outbox(eventId);
      assertEquals("PUBLISHED", beforeConsumption.get("status"));
      assertNotNull(beforeConsumption.get("ack_deadline_at"));
      fixture.evidence(
          "published-envelope",
          Map.of("body", fixture.envelope(eventId), "outbox", beforeConsumption));

      // 2. 原监听器消费和ACK；重复事件后用同一队列的后续事件作为完成屏障。
      fixture.send(published.getBody(), "application/json", eventId);
      fixture.startConsumers();
      fixture.awaitStatus(eventId, "APPLIED");
      assertEquals(1, fixture.document(41).get("productVersion"));
      fixture.assertAcknowledged(eventId);
      fixture.send(published.getBody(), "application/json", eventId);
      String staleId =
          fixture.transaction.execute(
              status -> {
                fixture.jdbc.update("UPDATE product SET version=3,price=23.45 WHERE id=41");
                return fixture.append(41, 2, "PRODUCT_CHANGED");
              });
      fixture.dispatch();
      fixture.awaitStatus(staleId, "STALE");
      assertEquals(
          1,
          fixture.jdbc.queryForObject(
              "SELECT attempts FROM product_index_consumption WHERE event_id=?",
              Integer.class,
              eventId));
      assertEquals(3, fixture.document(41).get("productVersion"));
      assertEquals(
          new BigDecimal("23.45"), new BigDecimal(fixture.document(41).get("price").toString()));
      assertEquals(
          3,
          fixture.jdbc.queryForObject(
              "SELECT applied_version FROM product_index_consumption WHERE event_id=?",
              Integer.class,
              staleId));

      // 3. 下架投影保留当前版本，真实搜索按原status/visible过滤。
      fixture.refresh();
      assertEquals(1, fixture.search().search("backpack", 7L, 1, 0, 10, "price_asc").total());
      String hiddenId =
          fixture.transaction.execute(
              status -> {
                fixture.jdbc.update("UPDATE product SET status=0,version=4 WHERE id=41");
                return fixture.append(41, 4, "HIDE");
              });
      fixture.dispatch();
      fixture.awaitStatus(hiddenId, "APPLIED");
      assertEquals(false, fixture.document(41).get("visible"));
      fixture.refresh();
      assertEquals(0, fixture.search().search("backpack", 7L, 1, 0, 10, "latest").total());
      assertEquals(1, fixture.search().search("backpack", 7L, null, 0, 10, "latest").total());
      fixture.assertAcknowledged(hiddenId);
      fixture.passed = true;
    }
  }

  @Test
  void externalVersionsRejectOldSingleAndBulkWritesAndUntrustedConflicts() throws Exception {
    try (Fixture fixture = new Fixture("versions")) {
      // 1. 新版本已存在时，真实external_gte冲突不能覆盖其内容。
      fixture.seed(41);
      ProductIndexDocument oldDocument = fixture.loader().load(41).orElseThrow();
      fixture.jdbc.update("UPDATE product SET name='new backpack',version=5 WHERE id=41");
      ProductIndexDocument current = fixture.loader().load(41).orElseThrow();
      fixture.writer().upsert(current);
      fixture.writer().upsert(oldDocument);
      fixture.writer().upsertBatch(List.of(oldDocument));
      assertEquals("new backpack", fixture.document(41).get("name"));
      assertEquals(5, fixture.document(41).get("productVersion"));
      assertEquals(
          6L,
          fixture.es.get(new GetRequest(fixture.index, "41"), RequestOptions.DEFAULT).getVersion());

      // 2. 内部版本较高但没有可信业务版本的文档必须报错。
      fixture.seed(99);
      ProductIndexDocument untrusted = fixture.loader().load(99).orElseThrow();
      for (int iteration = 0; iteration < 3; iteration++) {
        fixture.es.index(
            new IndexRequest(fixture.index).id("99").source(Map.of("name", "internal")),
            RequestOptions.DEFAULT);
      }
      IllegalStateException conflict =
          assertThrows(IllegalStateException.class, () -> fixture.writer().upsert(untrusted));
      assertTrue(conflict.getMessage().contains("VERSION_SEMANTICS_INCOMPATIBLE"));
      assertThrows(
          IllegalStateException.class, () -> fixture.writer().upsertBatch(List.of(untrusted)));
      assertFalse(fixture.document(99).containsKey("productVersion"));
      fixture.passed = true;
    }
  }

  @Test
  void blockedElasticsearchRetriesThenReplaysOriginalDeadLetter() throws Exception {
    try (Fixture fixture = new Fixture("recovery")) {
      // 1. 仅阻断本例索引写入，原消费工厂执行三次真实ES失败。
      fixture.writer().ensureIndex();
      fixture.writeBlock(true);
      fixture.startConsumers();
      String eventId =
          fixture.transaction.execute(
              status -> {
                fixture.seed(41);
                return fixture.append(41, 1, "PRODUCT_CHANGED");
              });
      try {
        fixture.dispatch();
        await("durable dead letter", () -> fixture.count("product_index_dlq_quarantine") == 1);
        fixture.awaitStatus(eventId, "RETRY");
        assertEquals(
            3,
            fixture.jdbc.queryForObject(
                "SELECT attempts FROM product_index_consumption WHERE event_id=?",
                Integer.class,
                eventId));
        assertNotNull(fixture.outbox(eventId).get("ack_deadline_at"));
        byte[] raw =
            fixture.jdbc.queryForObject(
                "SELECT raw_payload FROM product_index_dlq_quarantine WHERE quarantine_id=?",
                byte[].class,
                eventId);
        assertEquals(fixture.envelope(eventId), fixture.json.readTree(raw));
        fixture.assertQuarantineBytes(eventId, raw);
        fixture.evidence("failed-before-replay", fixture.facts());
      } finally {
        fixture.writeBlock(false);
      }

      // 2. 原replayer核对outbox后重放同一eventId，最终消费与ACK共同落库。
      fixture.context.getBean(ProductIndexDlqReplayer.class).replay();
      fixture.awaitStatus(eventId, "APPLIED");
      assertEquals(
          "REPLAYED",
          fixture.jdbc.queryForObject(
              "SELECT status FROM product_index_dlq_quarantine WHERE quarantine_id=?",
              String.class,
              eventId));
      assertEquals(
          4,
          fixture.jdbc.queryForObject(
              "SELECT attempts FROM product_index_consumption WHERE event_id=?",
              Integer.class,
              eventId));
      assertEquals(1, fixture.document(41).get("productVersion"));
      assertEquals(1, fixture.count("product_index_outbox"));
      fixture.assertAcknowledged(eventId);
      fixture.passed = true;
    }
  }

  @Test
  void invalidMessagesKeepOriginalBytesAndCannotBeBlindlyReplayed() throws Exception {
    try (Fixture fixture = new Fixture("quarantine")) {
      // 1. 原始Java格式前缀与不一致业务主键分别经过真实主队列/死信队列。
      fixture.writer().ensureIndex();
      fixture.startConsumers();
      byte[] legacy = {(byte) 0xac, (byte) 0xed, 0, 5, 1, 2, 3};
      byte[] invalid =
          fixture.json.writeValueAsBytes(
              Map.of(
                  "eventId",
                  "untrusted-json",
                  "eventType",
                  "UPSERT",
                  "traceId",
                  "invalid-trace",
                  "occurredAt",
                  "2026-11-14T12:00:00+08:00",
                  "productId",
                  41,
                  "productVersion",
                  1,
                  "businessKey",
                  Map.of("name", "productId", "value", 42),
                  "payload",
                  Map.of("productId", 41)));
      fixture.send(legacy, "application/x-java-serialized-object", "legacy-bytes");
      fixture.send(invalid, "application/json", "untrusted-json");
      await("two quarantined messages", () -> fixture.count("product_index_dlq_quarantine") == 2);
      fixture.assertQuarantineBytes("legacy-bytes", legacy);
      fixture.assertQuarantineBytes("untrusted-json", invalid);

      // 2. 无法与outbox核对的JSON保持隔离；非JSON原文不会进入重放候选。
      fixture.context.getBean(ProductIndexDlqReplayer.class).replay();
      assertEquals(
          "unverifiable-envelope",
          fixture.jdbc.queryForObject(
              "SELECT reason FROM product_index_dlq_quarantine WHERE quarantine_id='untrusted-json'",
              String.class));
      assertEquals(
          "dead-letter",
          fixture.jdbc.queryForObject(
              "SELECT reason FROM product_index_dlq_quarantine WHERE quarantine_id='legacy-bytes'",
              String.class));
      assertEquals(0, fixture.count("product_index_outbox"));
      assertEquals(0, fixture.count("product_index_consumption"));
      fixture.refresh();
      assertEquals(0L, fixture.writer().countDocuments());
      fixture.passed = true;
    }
  }

  /** 只等待被验证的事实，有界超时保留失败现场，不吞掉查询错误。 */
  private static void await(String description, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) fail("Timed out: " + description);
      Thread.sleep(100);
    }
  }

  /** 启用原Rabbit监听及真实本地事务；调度器单独受控，不开启其他业务扫描。 */
  @Configuration
  @EnableRabbit
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestConfiguration {}

  /** 每例独占数据库与索引；成功归档后才清理，失败仅关闭连接并保留资源。 */
  private static final class Fixture implements AutoCloseable {
    /** 本轮允许创建的五张真实表。 */
    private static final List<String> TABLES =
        List.of(
            "product",
            "product_hot_stat",
            "product_index_outbox",
            "product_index_consumption",
            "product_index_dlq_quarantine");

    /** 原broker中的三个队列，成功结束时必须排空。 */
    private static final List<String> QUEUES =
        List.of(
            ProductMessagingConfiguration.PRODUCT_INDEX_QUEUE,
            ProductMessagingConfiguration.PRODUCT_INDEX_DLQ,
            ProductMessagingConfiguration.PRODUCT_INDEX_DLQ_RETRY);

    /** 原JSON时间语义及本例证据序列化器。 */
    private final ObjectMapper json =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 仅用于本次随机资源的标识。 */
    private final String suffix = UUID.randomUUID().toString().replace("-", "");

    /** 本例数据库，只有CREATE成功后才有清理权限。 */
    private final String database = "product_index_refactor_" + suffix;

    /** 本例ES索引，不触及缺省索引。 */
    private final String index = "product_index_refactor_" + suffix;

    /** 本例完整事实与清理报告目录。 */
    private final Path evidence;

    /** 显式隔离数据库的管理URL。 */
    private final String adminUrl;

    /** 独占broker管理端口。 */
    private final int managementPort;

    /** 真实Spring上下文，关闭时销毁AMQP/ES客户端。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 仅查询/布置本例UUID数据库。 */
    private final JdbcTemplate jdbc;

    /** 与Mapper共用同一数据源的本地事务。 */
    private final TransactionTemplate transaction;

    /** 开启确认与returns的真实Rabbit客户端。 */
    private final RabbitTemplate rabbit;

    /** 仅连接本轮集群的ES客户端。 */
    private final RestHighLevelClient es;

    /** 自有资源成功创建标记，防止清理未知资源。 */
    private boolean databaseCreated;

    /** 测试全部断言成功后的清理门。 */
    private boolean passed;

    /** 核对显式端口/服务器身份，创建隔离资源并装配原实现。 */
    private Fixture(String scenario) throws Exception {
      int mysqlPort = port("mysql.port");
      int rabbitPort = port("rabbit.port");
      managementPort = port("management.port");
      int esPort = port("es.port");
      assertEquals(4, java.util.Set.of(mysqlPort, rabbitPort, managementPort, esPort).size());
      String runId = required("run-id");
      assertTrue(runId.matches("refactor-product-index-[0-9]{8}-[0-9a-f]{8}"));
      String options =
          "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&connectTimeout=2000&socketTimeout=5000";
      String server = "jdbc:mysql://127.0.0.1:" + mysqlPort + "/";
      adminUrl = server + options;
      evidence = Path.of(required("evidence")).toAbsolutePath().resolve(scenario + "-" + suffix);
      Files.createDirectories(evidence);
      DriverManagerDataSource source =
          new DriverManagerDataSource(server + database + options, "root", "root");
      jdbc = new JdbcTemplate(source);
      DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
      transaction = new TransactionTemplate(manager);
      CachingConnectionFactory connectionFactory =
          new CachingConnectionFactory("127.0.0.1", rabbitPort);
      connectionFactory.setUsername("root");
      connectionFactory.setPassword("root");
      connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connectionFactory.setPublisherReturns(true);
      connectionFactory.setConnectionTimeout(3000);
      rabbit = new RabbitTemplate(connectionFactory);
      rabbit.setMandatory(true);
      es =
          new RestHighLevelClient(
              RestClient.builder(new HttpHost("127.0.0.1", esPort))
                  .setRequestConfigCallback(
                      builder -> builder.setConnectTimeout(2000).setSocketTimeout(5000)));
      try {
        assertEquals("cm21-" + runId, es.info(RequestOptions.DEFAULT).getClusterName());
        assertFalse(
            es.indices()
                .exists(
                    new org.elasticsearch.client.indices.GetIndexRequest(index),
                    RequestOptions.DEFAULT));
        assertQuietBroker();
        try (Connection connection = DriverManager.getConnection(adminUrl, "root", "root");
            Statement statement = connection.createStatement()) {
          try (var result = statement.executeQuery("SELECT @@server_uuid")) {
            assertTrue(result.next());
            assertEquals(required("mysql.uuid"), result.getString(1));
          }
          statement.execute(
              "CREATE DATABASE `"
                  + database
                  + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
          databaseCreated = true;
        }
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null
            && !Files.isRegularFile(repository.resolve("backend/database/schema.sql")))
          repository = repository.getParent();
        String schema =
            Files.readString(
                Objects.requireNonNull(repository).resolve("backend/database/schema.sql"));
        for (String table : TABLES) {
          java.util.regex.Matcher definition =
              java.util.regex.Pattern.compile(
                      "CREATE TABLE IF NOT EXISTS " + table + " \\(.*?;",
                      java.util.regex.Pattern.DOTALL)
                  .matcher(schema);
          assertTrue(definition.find(), table);
          jdbc.execute(definition.group());
          assertFalse(definition.find(), "ambiguous DDL: " + table);
        }
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "isolated-index",
                    Map.of(
                        "cloudmall.product.metrics.enabled", "false",
                        "cloudmall.product.elasticsearch.index", index,
                        "cloudmall.product.index.consumer-enabled", "false")));
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(CachingConnectionFactory.class, () -> connectionFactory);
        context.registerBean(RabbitTemplate.class, () -> rabbit);
        context.registerBean(RabbitAdmin.class, () -> new RabbitAdmin(connectionFactory));
        context.registerBean(RestHighLevelClient.class, () -> es);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemDefaultZone());
        context.registerBean("taskScheduler", TaskScheduler.class, () -> scheduler);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        SqlSessionFactory sessions = Objects.requireNonNull(factory.getObject());
        sessions.getConfiguration().addMapper(ProductIndexMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sessions);
        context.registerBean(
            ProductIndexMapper.class, () -> template.getMapper(ProductIndexMapper.class));
        context.register(
            TestConfiguration.class,
            ProductMessagingConfiguration.class,
            ProductIndexOutboxMapper.class,
            ProductIndexConsumptionMapper.class,
            ProductIndexDeadLetterMapper.class,
            ProductIndexOutboxService.class,
            ProductIndexOutboxDispatcher.class,
            ProductIndexDocumentLoader.class,
            ProductIndexConsumptionService.class,
            ProductIndexConsumer.class,
            ProductIndexDlqReplayer.class,
            ElasticsearchProductIndexWriter.class,
            ElasticsearchProductKeywordSearch.class);
        context.refresh();
        context.getBean(RabbitAdmin.class).initialize();
        assertTrue(AopUtils.isAopProxy(loader()));
        assertTrue(AopUtils.isAopProxy(context.getBean(ProductIndexConsumptionService.class)));
        assertEquals(
            2,
            context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers().size());
        evidence(
            "started",
            Map.of("database", database, "index", index, "runId", runId, "scenario", scenario));
      } catch (Throwable failure) {
        try {
          context.close();
        } catch (Throwable closing) {
          failure.addSuppressed(closing);
        }
        try {
          connectionFactory.destroy();
        } catch (Throwable closing) {
          failure.addSuppressed(closing);
        }
        try {
          es.close();
        } catch (Throwable closing) {
          failure.addSuppressed(closing);
        }
        evidence(
            "setup-failed",
            Map.of(
                "database",
                database,
                "databaseCreated",
                databaseCreated,
                "index",
                index,
                "failure",
                failure.toString()));
        throw failure;
      }
    }

    /** 显式高位端口防止测试误用缺省中间件。 */
    private static int port(String name) {
      int value = Integer.parseInt(required(name));
      if (value < 19000 || value > 65535)
        throw new IllegalArgumentException("Explicit isolated high port required: " + name);
      return value;
    }

    /** 不允许系统属性缺省或静默回退至应用配置。 */
    private static String required(String name) {
      return Objects.requireNonNull(
          System.getProperty("cloudmall.product.index.it." + name),
          "Missing isolated property: " + name);
    }

    /** 布置固定毫秒时间与可检索内容，SQL仅在测试夹具中使用。 */
    private void seed(long productId) {
      jdbc.update(
          "INSERT INTO product(id,category_id,name,description,price,status,version,created_at,updated_at) VALUES(?,7,'travel backpack','test backpack',12.30,1,1,'2026-11-14 12:00:00.123','2026-11-14 12:00:00.123')",
          productId);
    }

    /** 从原业务outbox入口生成不可变JSON。 */
    private String append(long productId, long version, String type) {
      return context
          .getBean(ProductIndexOutboxService.class)
          .append(type, productId, version, "index-refactor-trace");
    }

    /** 手动推进一个原调度入口，保持其租约、confirm与退避实现。 */
    private void dispatch() {
      context.getBean(ProductIndexOutboxDispatcher.class).dispatch();
    }

    /** 启动两个原监听器，保持原重试/确认工厂。 */
    private void startConsumers() {
      context
          .getBean(RabbitListenerEndpointRegistry.class)
          .getListenerContainers()
          .forEach(container -> container.start());
    }

    /** 使用原交换机/路由键发送原字节并验证broker确认。 */
    private void send(byte[] body, String contentType, String eventId) throws Exception {
      MessageProperties properties = new MessageProperties();
      properties.setMessageId(eventId);
      properties.setContentType(contentType);
      CorrelationData correlation = new CorrelationData(eventId);
      rabbit.send(
          ProductMessagingConfiguration.PRODUCT_EXCHANGE,
          ProductMessagingConfiguration.PRODUCT_ROUTING_KEY,
          new Message(body, properties),
          correlation);
      assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck());
      assertNull(correlation.getReturnedMessage());
    }

    /** 以已提交SQL消费状态等待异步完成。 */
    private void awaitStatus(String eventId, String expected) throws Exception {
      await(
          expected + ": " + eventId,
          () ->
              expected.equals(
                  context.getBean(ProductIndexConsumptionMapper.class).findStatus(eventId)));
    }

    /** 校验消费终态和outbox确认在原事务中完成后的事实。 */
    private void assertAcknowledged(String eventId) {
      Map<String, Object> row = outbox(eventId);
      assertEquals("PUBLISHED", row.get("status"));
      assertNull(row.get("ack_deadline_at"));
      assertNull(row.get("lease_owner"));
      assertNull(row.get("lease_until"));
    }

    /** 原消息字节、长度、SHA必须一致，不能截断或盲目反序列化。 */
    private void assertQuarantineBytes(String eventId, byte[] expected) throws Exception {
      Map<String, Object> row =
          jdbc.queryForMap(
              "SELECT raw_payload,payload_size,payload_sha256,status FROM product_index_dlq_quarantine WHERE quarantine_id=?",
              eventId);
      assertArrayEquals(expected, (byte[]) row.get("raw_payload"));
      assertEquals(expected.length, ((Number) row.get("payload_size")).intValue());
      assertEquals(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)),
          row.get("payload_sha256"));
      assertEquals("QUARANTINED", row.get("status"));
    }

    /** 读取当前持久化envelope，用于和真实broker字节核对。 */
    private JsonNode envelope(String eventId) throws Exception {
      return json.readTree(
          context.getBean(ProductIndexOutboxMapper.class).findEnvelopeJson(eventId));
    }

    /** 只读取本例指定事件的outbox事实。 */
    private Map<String, Object> outbox(String eventId) {
      return jdbc.queryForMap("SELECT * FROM product_index_outbox WHERE event_id=?", eventId);
    }

    /** 返回真实ES writer。 */
    private ElasticsearchProductIndexWriter writer() {
      return context.getBean(ElasticsearchProductIndexWriter.class);
    }

    /** 返回真实MyBatis事实加载代理。 */
    private ProductIndexDocumentLoader loader() {
      return context.getBean(ProductIndexDocumentLoader.class);
    }

    /** 返回真实关键词搜索适配器。 */
    private ElasticsearchProductKeywordSearch search() {
      return context.getBean(ElasticsearchProductKeywordSearch.class);
    }

    /** 直接读取已写入的ES文档，不依赖刷新延迟。 */
    private Map<String, Object> document(long productId) throws Exception {
      return es.get(new GetRequest(index, String.valueOf(productId)), RequestOptions.DEFAULT)
          .getSourceAsMap();
    }

    /** 搜索断言前显式刷新本例索引，生产写入策略不变。 */
    private void refresh() throws Exception {
      es.indices().refresh(new RefreshRequest(index), RequestOptions.DEFAULT);
    }

    /** 只允许统计本例固定五表。 */
    private int count(String table) {
      if (!TABLES.contains(table)) throw new IllegalArgumentException(table);
      return jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    /** 故障注入仅修改本例索引的写开关，finally恢复。 */
    private void writeBlock(boolean blocked) throws Exception {
      Request request = new Request("PUT", "/" + index + "/_settings");
      request.setJsonEntity("{\"index.blocks.write\":" + blocked + "}");
      es.getLowLevelClient().performRequest(request);
    }

    /** 读取本轮broker管理事实，连接和读超时均有界。 */
    private JsonNode rabbitApi(String path) throws Exception {
      HttpURLConnection connection =
          (HttpURLConnection)
              new URL("http://127.0.0.1:" + managementPort + "/api/" + path).openConnection();
      connection.setConnectTimeout(2000);
      connection.setReadTimeout(5000);
      connection.setRequestProperty(
          "Authorization",
          "Basic "
              + Base64.getEncoder().encodeToString("root:root".getBytes(StandardCharsets.UTF_8)));
      try (var input = connection.getInputStream()) {
        return json.readTree(input);
      } finally {
        connection.disconnect();
      }
    }

    /** 统计可能滞后，等待原队列全部确认且连接/消费者消失，不purge消息。 */
    private void assertQuietBroker() throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
      while (true) {
        JsonNode queues = rabbitApi("queues/%2F");
        boolean empty = true;
        for (JsonNode queue : queues) {
          assertTrue(QUEUES.contains(queue.path("name").asText()), queue.toString());
          for (String field : List.of("messages_ready", "messages_unacknowledged", "consumers"))
            empty &= queue.has(field) && queue.get(field).asInt(-1) == 0;
        }
        if (empty && rabbitApi("connections").isEmpty() && rabbitApi("consumers/%2F").isEmpty())
          return;
        if (System.nanoTime() >= deadline) fail("Broker not quiet: " + queues);
        Thread.sleep(250);
      }
    }

    /** 归档五表及当前索引，不把不支持的JDBC对象直接交给JSON序列化。 */
    private Map<String, Object> facts() throws Exception {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("database", database);
      result.put("index", index);
      for (String table : TABLES) {
        result.put(
            table,
            jdbc.query(
                "SELECT * FROM `" + table + "`",
                (rows, rowNumber) -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    Object value = rows.getObject(column);
                    row.put(
                        rows.getMetaData().getColumnLabel(column),
                        value instanceof byte[] bytes
                            ? Base64.getEncoder().encodeToString(bytes)
                            : value == null ? null : value.toString());
                  }
                  return row;
                }));
      }
      if (writer().indexExists()) {
        refresh();
        Request request = new Request("GET", "/" + index + "/_search?size=100");
        try (var stream = es.getLowLevelClient().performRequest(request).getEntity().getContent()) {
          result.put("elasticsearch", json.readTree(stream));
        }
      }
      result.put("rabbitQueues", rabbitApi("queues/%2F"));
      return result;
    }

    /** 每阶段单独落盘，保留第一次失败证据。 */
    private void evidence(String name, Object value) throws Exception {
      json.writerWithDefaultPrettyPrinter()
          .writeValue(evidence.resolve(name + ".json").toFile(), value);
    }

    /** 停止监听后归档；仅全部断言和资源检查成功才删除本例已创建的库/索引。 */
    @Override
    public void close() throws Exception {
      boolean cleaned = false;
      try {
        context.getBean(RabbitListenerEndpointRegistry.class).stop();
        evidence("before-cleanup", facts());
        if (passed) {
          context.getBean(CachingConnectionFactory.class).destroy();
          assertQuietBroker();
          assertTrue(databaseCreated && database.matches("product_index_refactor_[0-9a-f]{32}"));
          if (writer().indexExists())
            assertTrue(
                es.indices()
                    .delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT)
                    .isAcknowledged());
          assertFalse(writer().indexExists());
          try (Connection connection = DriverManager.getConnection(adminUrl, "root", "root");
              Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
          }
          cleaned = true;
        }
      } finally {
        try {
          context.close();
        } finally {
          evidence(
              "finished",
              Map.of(
                  "database",
                  database,
                  "index",
                  index,
                  "assertionsPassed",
                  passed,
                  "deleted",
                  cleaned));
        }
      }
    }
  }
}
