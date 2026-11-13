package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.OrderTimeoutMessageListener;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderTimeoutService;
import com.cloudmall.order.service.impl.OrderTimeoutServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实TTL/DLX、原重试链和MySQL事务验收；不启动Stock远端或outbox调度器。 */
class OrderTimeoutMySqlRabbitIT {
  @Test
  void ttlDeliveryCommitsCancelAndRollbackOnceAndLegacyRedeliveriesAreNoOps() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.seed(1L, "20261142", "PENDING_PAYMENT", LocalDateTime.of(2000, 1, 1, 0, 0));
      var properties = new MessageProperties();
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      properties.setExpiration("1");
      fixture.rabbit.send(
          OrderMessagingConfiguration.TIMEOUT_EXCHANGE,
          "",
          new Message(envelope("20261142"), properties));
      await(() -> fixture.transactions.commits.get() == 1);
      assertEquals("CANCELLED", fixture.status("20261142"));
      assertEquals(1, fixture.actionCount());
      assertEquals(
          "ROLLBACK",
          fixture.jdbc.queryForObject("SELECT action_type FROM order_stock_action", String.class));
      fixture.send("\"20261142\"".getBytes(StandardCharsets.UTF_8));
      fixture.send(" 20261142 ".getBytes(StandardCharsets.UTF_8));
      await(() -> fixture.transactions.commits.get() == 3);
      assertEquals(1, fixture.actionCount());
      assertEquals(0, fixture.transactions.rollbacks.get());
      assertEquals(List.of(true, true, true), fixture.transactionAtRead);
      fixture.verified = true;
    }
  }

  @Test
  void actionInsertFailureRollsBackEachOfThreeAttemptsAndSameEventCanRetry() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.seed(1L, "20261143", "PENDING_PAYMENT", LocalDateTime.of(2000, 1, 1, 0, 0));
      fixture.jdbc.execute(
          "CREATE TRIGGER reject_timeout_action BEFORE INSERT ON order_stock_action FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='REFACTOR_TIMEOUT_ACTION_REJECTED'");
      fixture.send(envelope("20261143"));
      await(() -> fixture.transactions.rollbacks.get() == 3);
      fixture.stopConsumers();
      fixture.assertQueuesEmpty();
      assertEquals("PENDING_PAYMENT", fixture.status("20261143"));
      assertEquals(0, fixture.actionCount());
      assertEquals(0, fixture.transactions.commits.get());
      assertEquals(List.of(true, true, true), fixture.transactionAtRead);
      fixture.jdbc.execute("DROP TRIGGER reject_timeout_action");
      fixture.startConsumers();
      fixture.send(envelope("20261143"));
      await(() -> fixture.transactions.commits.get() == 1);
      assertEquals("CANCELLED", fixture.status("20261143"));
      assertEquals(1, fixture.actionCount());
      fixture.verified = true;
    }
  }

  @Test
  void malformedBytesDuplicateKeysAndMultipleRootsFailInsideTransactionBeforeSql()
      throws Exception {
    try (var fixture = new Fixture()) {
      // 原JSON转换器先拒绝此消息，不进入Service事务；其他消息仍按原顺序处理。
      fixture.send(new byte[] {(byte) 0xC3, 0x28});
      // text/plain不由原JSON转换器解析，原字节进入Service后触发严格UTF-8拒绝。
      var rawProperties = new MessageProperties();
      rawProperties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
      fixture.rabbit.send(
          OrderMessagingConfiguration.DLX,
          "",
          new Message(new byte[] {(byte) 0xC3, 0x28}, rawProperties));
      for (byte[] payload :
          List.of(
              "{\"orderNo\":\"20261142\",\"orderNo\":\"20261143\"}"
                  .getBytes(StandardCharsets.UTF_8),
              "\"20261142\" {}".getBytes(StandardCharsets.UTF_8))) {
        fixture.send(payload);
      }
      await(() -> fixture.transactions.rollbacks.get() == 9);
      fixture.stopConsumers();
      fixture.assertQueuesEmpty();
      assertEquals(9, fixture.transactions.begins.get());
      assertEquals(0, fixture.transactions.commits.get());
      verifyNoInteractions(fixture.orders);
      assertEquals(0, fixture.actionCount());
      fixture.verified = true;
    }
  }

  @Test
  void messageDeadlineCannotOverrideMissingOrFutureDatabaseDeadlineAndTerminalStatesStayUntouched()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.seed(1L, "20261144", "PENDING_PAYMENT", null);
      fixture.seed(2L, "20261145", "PENDING_PAYMENT", LocalDateTime.of(2099, 1, 1, 0, 0));
      fixture.send(envelope("20261144"));
      fixture.send(envelope("20261145"));
      int identifier = 3;
      for (String status : List.of("PAID", "CANCELLED", "COMPLETED", "OTHER")) {
        String orderNo = "202611terminal" + identifier;
        fixture.seed(identifier++, orderNo, status, null);
        fixture.send(envelope(orderNo));
      }
      await(
          () ->
              fixture.transactions.rollbacks.get() == 6 && fixture.transactions.commits.get() == 4);
      assertEquals("PENDING_PAYMENT", fixture.status("20261144"));
      assertEquals("PENDING_PAYMENT", fixture.status("20261145"));
      assertEquals(0, fixture.actionCount());
      verify(fixture.orders, never())
          .updateStatus(anyString(), anyString(), anyString(), anyString(), any());
      assertEquals(10, fixture.transactionAtRead.size());
      assertTrue(fixture.transactionAtRead.stream().allMatch(Boolean::booleanValue));
      fixture.verified = true;
    }
  }

  private static byte[] envelope(String orderNo) throws Exception {
    return new ObjectMapper()
        .writeValueAsBytes(
            Map.of(
                "eventId",
                "timeout:" + orderNo,
                "eventType",
                "ORDER_TIMEOUT",
                "orderNo",
                orderNo,
                "expireAt",
                "2000-01-01T00:00:00+08:00"));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) fail("等待真实超时消费事实超时");
      Thread.sleep(25);
    }
  }

  /** 仅本IT启用的真实事务与AMQP基础设施。 */
  @Configuration
  @Profile("order-timeout-message-it")
  @EnableRabbit
  @EnableTransactionManagement
  static class Infrastructure {}

  /** 使用实际数据源提交/回滚，计数在成功完成原操作后增加。 */
  static class ObservedTransactions extends DataSourceTransactionManager {
    /** 真实事务开启次数。 */
    private final AtomicInteger begins = new AtomicInteger();

    /** 实际完成提交次数。 */
    private final AtomicInteger commits = new AtomicInteger();

    /** 实际完成回滚次数。 */
    private final AtomicInteger rollbacks = new AtomicInteger();

    ObservedTransactions(HikariDataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      super.doBegin(transaction, definition);
      begins.incrementAndGet();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      super.doCommit(status);
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      super.doRollback(status);
      rollbacks.incrementAndGet();
    }
  }

  /** 只写本例UUID库与外部提供的全新实验broker，失败保留库与队列现场。 */
  static class Fixture implements AutoCloseable {
    /** 本例唯一数据库名。 */
    private final String schema =
        "order_timeout_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 仅管理本例库生命周期的入口。 */
    private final JdbcTemplate administrator;

    /** 有界本例连接池。 */
    private final HikariDataSource dataSource = new HikariDataSource();

    /** 准备和读取本例SQL事实。 */
    private final JdbcTemplate jdbc;

    /** 保留原SQL，只观测是否进入读取。 */
    private final OrderSqlMapper orders;

    /** 记录各次订单查询时的事务状态。 */
    private final List<Boolean> transactionAtRead = new CopyOnWriteArrayList<>();

    /** 原事务管理器的完成观察点。 */
    private final ObservedTransactions transactions;

    /** 本例Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 本例Rabbit连接。 */
    private final CachingConnectionFactory connection;

    /** 原拓扑管理和队列只读核验。 */
    private final RabbitAdmin admin;

    /** 原始消息投递客户端。 */
    private final RabbitTemplate rabbit;

    /** 全部断言通过后才删除本例库。 */
    private boolean verified;

    Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      String port = System.getProperty("cloudmall.rabbit.test.port");
      if (url == null
          || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_order")
          || port == null) throw new IllegalStateException("需要独立MySQL/Rabbit实验地址");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      dataSource.setJdbcUrl(configured(url.replace("/cloudmall_order", "/" + schema)));
      dataSource.setUsername("root");
      dataSource.setPassword("root");
      dataSource.setMaximumPoolSize(4);
      jdbc = new JdbcTemplate(dataSource);
      transactions = new ObservedTransactions(dataSource);
      orders = spy(new OrderSqlMapper(jdbc));
      connection = new CachingConnectionFactory("127.0.0.1", Integer.parseInt(port));
      connection.setUsername("root");
      connection.setPassword("root");
      admin = new RabbitAdmin(connection);
      rabbit = new RabbitTemplate(connection);
      try {
        jdbc.execute("CREATE TABLE mall_order_202611 LIKE cloudmall_order.mall_order_template");
        jdbc.execute(
            "CREATE TABLE mall_order_item_202611 LIKE cloudmall_order.mall_order_item_template");
        jdbc.execute("CREATE TABLE order_stock_action LIKE cloudmall_order.order_stock_action");
        doAnswer(
                invocation -> {
                  transactionAtRead.add(
                      TransactionSynchronizationManager.isActualTransactionActive());
                  return invocation.callRealMethod();
                })
            .when(orders)
            .findOrder(anyString(), anyString());
        context.getEnvironment().setActiveProfiles("order-timeout-message-it");
        for (var source :
            new YamlPropertySourceLoader()
                .load("original-order", new ClassPathResource("application.yml")))
          context.getEnvironment().getPropertySources().addLast(source);
        context.register(
            Infrastructure.class,
            OrderMessagingConfiguration.class,
            OrderTimeoutMessageListener.class,
            OrderTimeoutServiceImpl.class);
        context.registerBean(ConnectionFactory.class, () -> connection);
        context.registerBean("rabbitAdmin", RabbitAdmin.class, () -> admin);
        context.registerBean(OrderSqlMapper.class, () -> orders);
        context.registerBean(OrderStockActionMapper.class, () -> new OrderStockActionMapper(jdbc));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean("transactionManager", ObservedTransactions.class, () -> transactions);
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
        admin.initialize();
        assertTrue(AopUtils.isAopProxy(context.getBean(OrderTimeoutService.class)));
        assertFalse(AopUtils.isAopProxy(context.getBean(OrderTimeoutMessageListener.class)));
        var containers =
            context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers();
        assertEquals(1, containers.size());
        var container = (AbstractMessageListenerContainer) containers.iterator().next();
        assertArrayEquals(
            new String[] {OrderMessagingConfiguration.TIMEOUT_DLQ}, container.getQueueNames());
        assertEquals(AcknowledgeMode.AUTO, container.getAcknowledgeMode());
        assertTrue(context.getBean("timeoutDeadLetterQueue", Queue.class).getArguments().isEmpty());
        assertQueuesEmpty();
        startConsumers();
      } catch (Exception | Error failure) {
        context.close();
        connection.destroy();
        dataSource.close();
        System.out.println("保留失败超时测试库: " + schema);
        throw failure;
      }
    }

    private void seed(long identifier, String orderNo, String status, LocalDateTime deadline) {
      jdbc.update(
          "INSERT INTO mall_order_202611 (id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at) VALUES(?,?,8,?,12.30,12.30,'{}',?,'2026-11-01 00:00:00.123','2026-11-01 00:00:00.123')",
          identifier,
          orderNo,
          status,
          deadline);
    }

    private void send(byte[] payload) {
      var properties = new MessageProperties();
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      rabbit.send(OrderMessagingConfiguration.DLX, "", new Message(payload, properties));
    }

    private void stopConsumers() {
      context.getBean(RabbitListenerEndpointRegistry.class).stop();
    }

    private void startConsumers() {
      context.getBean(RabbitListenerEndpointRegistry.class).start();
    }

    private String status(String orderNo) {
      return jdbc.queryForObject(
          "SELECT status FROM mall_order_202611 WHERE order_no=?", String.class, orderNo);
    }

    private int actionCount() {
      return jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_action", Integer.class);
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

    /** 停止消费者并核验原队列为空，成功时仅删除本例库；失败保留现场。 */
    @Override
    public void close() {
      try {
        stopConsumers();
        if (verified) {
          assertQueuesEmpty();
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertTrue(schema.matches("order_timeout_refactor_[0-9a-f]{32}"));
          administrator.execute("DROP DATABASE " + schema);
          assertEquals(
              0,
              administrator.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                  Integer.class,
                  schema));
          System.out.println("已清理超时测试库: " + schema);
        } else System.out.println("保留失败超时测试库: " + schema);
      } finally {
        context.close();
        connection.destroy();
        dataSource.close();
      }
    }
  }
}
