package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.OrderSeckillMessageListener;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.OrderSeckillCreationService;
import com.cloudmall.order.service.impl.OrderSeckillCreationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 独立MySQL/Rabbit实验：真实监听转换、业务代理、SQL提交和原重试/DLX；Product为替身。 */
class OrderSeckillMessageMySqlRabbitIT {
  @Test
  void realDeliveryCommitsAllFactsAndCommittedReplaySkipsProductAndTimeValidation()
      throws Exception {
    try (var fixture = new Fixture()) {
      var event = event("normal");
      fixture.holdRecovery(event);
      fixture.send(event);
      await(
          () -> fixture.count("order_outbox") == 1 && fixture.recoveryStatus().equals("RESOLVED"));
      assertEquals(
          "COMMITTED",
          fixture.jdbc.queryForObject("SELECT status FROM seckill_order_dedup", String.class));
      assertEquals(1, fixture.count("mall_order_202611"));
      assertEquals(1, fixture.count("mall_order_item_202611"));
      var order = fixture.jdbc.queryForMap("SELECT * FROM mall_order_202611");
      assertEquals("PENDING_PAYMENT", order.get("status"));
      assertEquals(new BigDecimal("12.30"), order.get("pay_amount"));
      assertEquals(
          LocalDateTime.of(2026, 11, 1, 0, 0, 0, 123_000_000),
          fixture.jdbc.queryForObject(
              "SELECT created_at FROM mall_order_202611", LocalDateTime.class));
      assertEquals(
          LocalDateTime.of(2026, 11, 1, 0, 30, 0, 123_000_000),
          fixture.jdbc.queryForObject(
              "SELECT expire_at FROM mall_order_202611", LocalDateTime.class));
      event.remove("occurredAt");
      fixture.send(event);
      // 同通道发送一个前置冲突作完成屏障，只有前面的重复消息已返回才会处理到它。
      var barrier = event("barrier");
      barrier.put("userId", 7.5);
      fixture.send(barrier);
      await(() -> fixture.count("seckill_order_dedup_conflict") == 1);
      assertEquals(1, fixture.productCalls.get());
      assertEquals(List.of(true), fixture.productTransactions);
      assertEquals(1, fixture.count("order_outbox"));
      assertEquals(1, fixture.count("seckill_order_dedup"));
      fixture.verified = true;
    }
  }

  @Test
  void productFailureRollsBackEveryAttemptAndDeadLettersOriginalMessageAfterThreeAttempts()
      throws Exception {
    try (var fixture = new Fixture()) {
      var event = event("bad-product");
      fixture.holdRecovery(event);
      fixture.productResponse =
          ApiResponse.ok(
              new ProductClient.SkuView(99L, 9L, "wrong", "SKU-99", BigDecimal.ONE, Map.of()));
      fixture.send(event);
      Message deadLetter = fixture.receiveDeadLetter();
      assertEquals(3, fixture.productCalls.get());
      assertEquals(List.of(true, true, true), fixture.productTransactions);
      fixture.assertNoOrderFacts();
      assertEquals("REPLAY_PENDING", fixture.recoveryStatus());
      assertEquals(
          fixture.json.readTree(fixture.json.writeValueAsBytes(event)),
          fixture.json.readTree(deadLetter.getBody()));
      assertEquals(
          "java.util.LinkedHashMap", deadLetter.getMessageProperties().getHeader("__TypeId__"));
      List<Map<String, Object>> deaths = deadLetter.getMessageProperties().getHeader("x-death");
      assertEquals(1L, ((Number) deaths.get(0).get("count")).longValue());
      assertEquals("rejected", String.valueOf(deaths.get(0).get("reason")));
      fixture.productResponse = goodProduct();
      fixture.rabbit.send(OrderMessagingConfiguration.SECKILL_EXCHANGE, "", deadLetter);
      await(
          () -> fixture.count("order_outbox") == 1 && fixture.recoveryStatus().equals("RESOLVED"));
      assertEquals(4, fixture.productCalls.get());
      fixture.verified = true;
    }
  }

  @Test
  void lateDedupFailureRollsBackOrderItemOutboxAndClaimBeforeDeadLetterAndOriginalReplay()
      throws Exception {
    try (var fixture = new Fixture()) {
      var event = event("commit-gate");
      fixture.holdRecovery(event);
      fixture.jdbc.execute(
          "CREATE TRIGGER reject_dedup_commit BEFORE UPDATE ON seckill_order_dedup FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFACTOR_DEDUP_COMMIT_REJECTED'");
      fixture.send(event);
      Message deadLetter = fixture.receiveDeadLetter();
      assertEquals(3, fixture.productCalls.get());
      fixture.assertNoOrderFacts();
      assertEquals("REPLAY_PENDING", fixture.recoveryStatus());
      fixture.jdbc.execute("DROP TRIGGER reject_dedup_commit");
      fixture.rabbit.send(OrderMessagingConfiguration.SECKILL_EXCHANGE, "", deadLetter);
      await(
          () -> fixture.count("order_outbox") == 1 && fixture.recoveryStatus().equals("RESOLVED"));
      assertEquals(
          "COMMITTED",
          fixture.jdbc.queryForObject("SELECT status FROM seckill_order_dedup", String.class));
      assertEquals(4, fixture.productCalls.get());
      assertTrue(fixture.productTransactions.stream().allMatch(Boolean::booleanValue));
      fixture.verified = true;
    }
  }

  @Test
  void invalidIdentityCommitsConflictEvidenceWithoutCreatingOrderOrCallingProduct()
      throws Exception {
    try (var fixture = new Fixture()) {
      var event = event("conflict");
      event.put("userId", 7.5);
      fixture.send(event);
      await(() -> fixture.count("seckill_order_dedup_conflict") == 1);
      var conflict = fixture.jdbc.queryForMap("SELECT * FROM seckill_order_dedup_conflict");
      assertEquals(0L, ((Number) conflict.get("user_id")).longValue());
      assertEquals(event.get("eventId"), conflict.get("event_id"));
      assertEquals(
          fixture.json.readTree(fixture.json.writeValueAsBytes(event)),
          fixture.json.readTree(String.valueOf(conflict.get("raw_payload"))));
      fixture.assertNoOrderFacts();
      assertEquals(0, fixture.productCalls.get());
      fixture.verified = true;
    }
  }

  private static LinkedHashMap<String, Object> event(String suffix) {
    var event = new LinkedHashMap<String, Object>();
    event.put("eventType", "SECKILL_ORDER_REQUEST");
    event.put("orderNo", "202611-refactor-" + suffix);
    event.put("eventId", "event-refactor-" + suffix);
    event.put("activityId", 11L);
    event.put("skuId", 7L);
    event.put("userId", 8L);
    event.put("idempotencyKey", "key-refactor-" + suffix);
    event.put("addressSnapshot", "{\"receiver\":\"小明\"}");
    event.put(
        "requestHash",
        SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, (String) event.get("addressSnapshot")));
    event.put("occurredAt", "2026-10-31T16:00:00.123900Z");
    return event;
  }

  private static ApiResponse<ProductClient.SkuView> goodProduct() {
    return ApiResponse.ok(
        new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", new BigDecimal("12.30"), Map.of()));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) fail("等待真实消息/事务事实超时");
      Thread.sleep(50);
    }
  }

  /** 只有本IT显式激活，使用真实Spring事务与AMQP监听注册机制。 */
  @Configuration
  @Profile("order-seckill-message-it")
  @EnableRabbit
  @EnableTransactionManagement
  static class Infrastructure {}

  /** 每例拥有一个UUID数据库；Rabbit是外部启动的专属新实验实例。失败保留现场。 */
  static class Fixture implements AutoCloseable {
    /** 本例隔离数据库名。 */
    private final String schema =
        "order_seckill_msg_" + UUID.randomUUID().toString().replace("-", "");

    /** 仅创建/删除自有库的管理连接。 */
    private final JdbcTemplate administrator;

    /** 有界SQL连接池，避免为每次轮询创建socket。 */
    private final HikariDataSource dataSource = new HikariDataSource();

    /** 仅本例准备及读取事实使用的SQL入口。 */
    private final JdbcTemplate jdbc;

    /** 原消费JSON序列化配置。 */
    private final ObjectMapper json = new ObjectMapper();

    /** 专属broker连接，关闭上下文后销毁。 */
    private final CachingConnectionFactory connection;

    /** 直接生产装配上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 真实消息发布/读取客户端。 */
    private final RabbitTemplate rabbit;

    /** 原拓扑声明与队列事实读取入口。 */
    private final RabbitAdmin admin;

    /** Product调用计数，可观测三次原重试。 */
    private final AtomicInteger productCalls = new AtomicInteger();

    /** Product调用时的真实本地事务标志。 */
    private final List<Boolean> productTransactions = new CopyOnWriteArrayList<>();

    /** 故障注入仅替换Product响应。 */
    private volatile ApiResponse<ProductClient.SkuView> productResponse = goodProduct();

    /** 全部业务断言完成后才允许删除本例事实。 */
    private boolean verified;

    Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      String port = System.getProperty("cloudmall.rabbit.test.port");
      if (url == null
          || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_order")
          || port == null) throw new IllegalStateException("必须指定独立MySQL/Rabbit实验地址");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      dataSource.setJdbcUrl(configured(url.replace("/cloudmall_order", "/" + schema)));
      dataSource.setUsername("root");
      dataSource.setPassword("root");
      dataSource.setMaximumPoolSize(4);
      jdbc = new JdbcTemplate(dataSource);
      connection = new CachingConnectionFactory("127.0.0.1", Integer.parseInt(port));
      connection.setUsername("root");
      connection.setPassword("root");
      rabbit = new RabbitTemplate(connection);
      admin = new RabbitAdmin(connection);
      try {
        jdbc.execute("CREATE TABLE mall_order_202611 LIKE cloudmall_order.mall_order_template");
        jdbc.execute(
            "CREATE TABLE mall_order_item_202611 LIKE cloudmall_order.mall_order_item_template");
        var dedup = new SeckillOrderDedupMapper(jdbc);
        var recovery = new SeckillMessageRecoveryMapper(jdbc);
        var outbox = new OrderOutboxMapper(jdbc);
        dedup.ensureTable();
        recovery.ensureTable();
        outbox.ensureTable();
        ProductClient products = mock(ProductClient.class);
        when(products.sku(7L))
            .thenAnswer(
                invocation -> {
                  productTransactions.add(
                      TransactionSynchronizationManager.isActualTransactionActive());
                  productCalls.incrementAndGet();
                  return productResponse;
                });
        context.getEnvironment().setActiveProfiles("order-seckill-message-it");
        for (var source :
            new YamlPropertySourceLoader()
                .load("original-order", new ClassPathResource("application.yml")))
          context.getEnvironment().getPropertySources().addLast(source);
        context.register(
            Infrastructure.class,
            OrderMessagingConfiguration.class,
            OrderSeckillCreationServiceImpl.class,
            OrderSeckillMessageListener.class,
            OrderController.class);
        context.registerBean(ConnectionFactory.class, () -> connection);
        context.registerBean("rabbitAdmin", RabbitAdmin.class, () -> admin);
        context.registerBean(ProductClient.class, () -> products);
        context.registerBean(OrderSqlMapper.class, () -> new OrderSqlMapper(jdbc));
        context.registerBean(OrderStockActionMapper.class, () -> new OrderStockActionMapper(jdbc));
        context.registerBean(OrderOutboxMapper.class, () -> outbox);
        context.registerBean(SeckillOrderDedupMapper.class, () -> dedup);
        context.registerBean(SeckillMessageRecoveryMapper.class, () -> recovery);
        context.registerBean(ObjectMapper.class, () -> json);
        context.registerBean(
            "transactionManager",
            DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(
            "rabbitListenerContainerFactory",
            SimpleRabbitListenerContainerFactory.class,
            () -> {
              RabbitProperties properties =
                  Binder.get(context.getEnvironment())
                      .bind("spring.rabbitmq", RabbitProperties.class)
                      .get();
              assertTrue(properties.getListener().getSimple().getRetry().isEnabled());
              assertEquals(3, properties.getListener().getSimple().getRetry().getMaxAttempts());
              assertEquals(
                  1000,
                  properties.getListener().getSimple().getRetry().getInitialInterval().toMillis());
              assertEquals(2.0, properties.getListener().getSimple().getRetry().getMultiplier());
              assertEquals(
                  Boolean.FALSE, properties.getListener().getSimple().getDefaultRequeueRejected());
              var factory = new SimpleRabbitListenerContainerFactory();
              new SimpleRabbitListenerContainerFactoryConfigurer(properties)
                  .configure(factory, connection);
              factory.setMessageConverter(context.getBean(Jackson2JsonMessageConverter.class));
              factory.setAutoStartup(false);
              return factory;
            });
        context.refresh();
        rabbit.setMessageConverter(context.getBean(Jackson2JsonMessageConverter.class));
        admin.initialize();
        assertTrue(AopUtils.isAopProxy(context.getBean(OrderSeckillCreationService.class)));
        assertFalse(AopUtils.isAopProxy(context.getBean(OrderSeckillMessageListener.class)));
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        assertEquals(2, registry.getListenerContainers().size());
        var queueNames = new java.util.HashSet<String>();
        for (var container : registry.getListenerContainers()) {
          var actual = (AbstractMessageListenerContainer) container;
          assertEquals(1, actual.getQueueNames().length);
          assertTrue(queueNames.add(actual.getQueueNames()[0]), "duplicate endpoint");
          assertEquals(AcknowledgeMode.AUTO, actual.getAcknowledgeMode());
        }
        assertEquals(
            Set.of(
                OrderMessagingConfiguration.SECKILL_QUEUE, OrderMessagingConfiguration.TIMEOUT_DLQ),
            queueNames);
        assertQueuesEmpty();
        registry.start();
      } catch (Exception | Error failure) {
        context.close();
        connection.destroy();
        dataSource.close();
        System.out.println("保留失败秒杀消息隔离库: " + schema);
        throw failure;
      }
    }

    private void send(Map<String, Object> event) {
      rabbit.convertAndSend(OrderMessagingConfiguration.SECKILL_EXCHANGE, "", event);
    }

    private void holdRecovery(Map<String, Object> event) throws Exception {
      context
          .getBean(SeckillMessageRecoveryMapper.class)
          .saveDlq(
              1L,
              (String) event.get("eventId"),
              (String) event.get("requestHash"),
              json.writeValueAsString(event),
              "REPLAY_PENDING",
              LocalDateTime.now());
    }

    private Message receiveDeadLetter() {
      Message message = rabbit.receive(OrderMessagingConfiguration.SECKILL_DLQ, 20000);
      assertNotNull(message, "三次失败后必须进入原死信队列");
      return message;
    }

    private int count(String table) {
      if (!Set.of(
              "mall_order_202611",
              "mall_order_item_202611",
              "order_outbox",
              "seckill_order_dedup",
              "seckill_order_dedup_conflict")
          .contains(table)) throw new IllegalArgumentException("未知测试表");
      return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String recoveryStatus() {
      return jdbc.queryForObject("SELECT status FROM seckill_message_recovery", String.class);
    }

    private void assertNoOrderFacts() {
      for (String table :
          List.of(
              "mall_order_202611", "mall_order_item_202611", "order_outbox", "seckill_order_dedup"))
        assertEquals(0, count(table), table);
    }

    private void assertQueuesEmpty() {
      for (Queue queue : context.getBeansOfType(Queue.class).values()) {
        var properties = admin.getQueueProperties(queue.getName());
        assertNotNull(properties, queue.getName());
        assertEquals(0, properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT), queue.getName());
      }
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先停止消费者再核验已确认队列；仅通过全部断言的自有UUID库允许删除。 */
    @Override
    public void close() {
      try {
        context.getBean(RabbitListenerEndpointRegistry.class).stop();
        if (verified) {
          assertQueuesEmpty();
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertTrue(schema.matches("order_seckill_msg_[0-9a-f]{32}"));
          administrator.execute("DROP DATABASE " + schema);
          assertEquals(
              0,
              administrator.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                  Integer.class,
                  schema));
          System.out.println("已清理秒杀消息隔离库: " + schema);
        } else System.out.println("保留失败秒杀消息隔离库: " + schema);
      } finally {
        context.close();
        connection.destroy();
        dataSource.close();
      }
    }
  }
}
