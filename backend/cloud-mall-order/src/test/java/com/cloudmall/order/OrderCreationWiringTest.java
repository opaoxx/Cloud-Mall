package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.controller.OrderCreationController;
import com.cloudmall.order.controller.OrderStateController;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.OrderCreationService;
import com.cloudmall.order.service.OrderStateService;
import com.cloudmall.order.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 直接装配生产Controller/Service及真实Spring事务拦截器；不经过历史混合测试夹具。 */
class OrderCreationWiringTest {
  /** 固定原单身份，测试仅重放事实，不发远程写请求。 */
  private static final String ORDER = "202611existing";

  @Test
  void createCancelAndPaidShareOneMonitorAndBeginBeforeWaitingCommitAfterUnlock() throws Exception {
    try (var fixture = new Fixture()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.transactions.expectedBegins = new CountDownLatch(3);
      when(fixture.orders.findIdempotentOrders(7L, "key"))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return List.of(idempotency());
              });
      when(fixture.orders.findOrder(anyString(), eq(ORDER)))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return List.of(order("PENDING_PAYMENT"));
              });
      when(fixture.orders.updateStatus(anyString(), eq(ORDER), anyString(), anyString(), any()))
          .thenReturn(1);
      var executor = Executors.newFixedThreadPool(3);
      try {
        var create =
            executor.submit(() -> authenticated(() -> fixture.creation().create("key", null)));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var cancel = executor.submit(() -> authenticated(() -> fixture.writes().cancel(ORDER, 7L)));
        var paid = executor.submit(() -> authenticated(() -> fixture.writes().paid(ORDER)));
        assertTrue(fixture.transactions.expectedBegins.await(5, TimeUnit.SECONDS));
        assertFalse(cancel.isDone());
        assertFalse(paid.isDone());
        verify(fixture.orders, never()).findOrder(anyString(), anyString());
        release.countDown();
        assertEquals("0", create.get(5, TimeUnit.SECONDS).code);
        assertEquals("0", cancel.get(5, TimeUnit.SECONDS).code);
        assertEquals("0", paid.get(5, TimeUnit.SECONDS).code);
        assertEquals(3, fixture.transactions.commits.get());
        assertEquals(0, fixture.transactions.rollbacks.get());
      } finally {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void identityFailureReleasesMonitorBeforeRollbackAndDoesNotBlockNextCall() {
    try (var fixture = new Fixture()) {
      var service = fixture.context.getBean(OrderCreationService.class);
      var failure = new BizException("TEST_UNAUTHORIZED", "identity", 401);
      assertSame(
          failure,
          assertThrows(
              BizException.class,
              () ->
                  service.create(
                      "key",
                      null,
                      () -> {
                        assertTrue(Thread.holdsLock(fixture.monitor));
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        throw failure;
                      })));
      verifyNoInteractions(fixture.orders);
      assertEquals(1, fixture.transactions.rollbacks.get());
      when(fixture.orders.findIdempotentOrders(7L, "key")).thenReturn(List.of(idempotency()));
      when(fixture.orders.findOrder(anyString(), eq(ORDER)))
          .thenReturn(List.of(order("PENDING_PAYMENT")));
      assertEquals(ORDER, service.create("key", null, () -> 7L).orderNo);
      assertEquals(1, fixture.transactions.commits.get());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  @Test
  void confirmKeepsOriginalNonTransactionalNonSynchronizedBoundary() throws Exception {
    try (var fixture = new Fixture()) {
      when(fixture.orders.findOrder(anyString(), eq(ORDER)))
          .thenAnswer(
              invocation -> {
                assertFalse(Thread.holdsLock(fixture.monitor));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return List.of(order("COMPLETED"));
              });
      var executor = Executors.newSingleThreadExecutor();
      try {
        synchronized (fixture.monitor) {
          var result = executor.submit(() -> authenticated(() -> fixture.writes().confirm(ORDER)));
          assertEquals("0", result.get(5, TimeUnit.SECONDS).code);
        }
        assertEquals(0, fixture.transactions.begins.get());
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void productionHttpBindingReturnsOriginalReplayShapeAndRejectsMissingHeader() throws Exception {
    try (var fixture = new Fixture()) {
      when(fixture.orders.findIdempotentOrders(7L, "key")).thenReturn(List.of(idempotency()));
      when(fixture.orders.findOrder(anyString(), eq(ORDER)))
          .thenReturn(List.of(order("PENDING_PAYMENT")));
      var builder = new Jackson2ObjectMapperBuilder();
      new CommonWebConfiguration().moneyAsString().customize(builder);
      var mvc =
          MockMvcBuilders.standaloneSetup(fixture.creation(), fixture.writes())
              .setControllerAdvice(new GlobalExceptionHandler())
              .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
              .build();
      AuthContext.set(7L, "USER");
      try {
        mvc.perform(
                post("/api/orders")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.orderNo").value(ORDER))
            .andExpect(jsonPath("$.data.payAmount").value("12.30"));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        assertEquals(1, fixture.transactions.begins.get());
      } finally {
        AuthContext.clear();
      }
    }
  }

  private static <T> T authenticated(java.util.concurrent.Callable<T> call) throws Exception {
    AuthContext.set(7L, "USER");
    try {
      return call.call();
    } finally {
      AuthContext.clear();
    }
  }

  @Test
  void failedCommitCannotReturnSuccessOrStartStockCompensation() throws Exception {
    try (var fixture = new Fixture()) {
      when(fixture.orders.findIdempotentOrders(7L, "key")).thenReturn(List.of(idempotency()));
      when(fixture.orders.findOrder(anyString(), eq(ORDER)))
          .thenReturn(List.of(order("PENDING_PAYMENT")));
      fixture.transactions.failCommit = true;
      var mvc =
          MockMvcBuilders.standaloneSetup(fixture.creation())
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      AuthContext.set(7L, "USER");
      try {
        mvc.perform(
                post("/api/orders")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
        verifyNoInteractions(fixture.context.getBean(StockClient.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      } finally {
        AuthContext.clear();
      }
    }
  }

  private static OrderIdempotencyPO idempotency() {
    var row = new OrderIdempotencyPO();
    row.orderNo = ORDER;
    row.userId = 7L;
    row.idempotencyKey = "key";
    return row;
  }

  private static Map<String, Object> order(String status) {
    return Map.of(
        "order_no",
        ORDER,
        "user_id",
        7L,
        "status",
        status,
        "total_amount",
        new BigDecimal("12.30"),
        "pay_amount",
        new BigDecimal("12.30"),
        "address_snapshot",
        "{}",
        "created_at",
        LocalDateTime.of(2026, 11, 1, 12, 0));
  }

  /** 仅本测试显式激活，避免生产组件扫描加载测试事务配置。 */
  @Configuration
  @Profile("order-creation-wiring-test")
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Transactions {}

  /** 观察真实Spring拦截器生命周期；实际数据库原子性由实库测试另验。 */
  static class ObservedTransactions extends AbstractPlatformTransactionManager {
    /** 当前用例是否在提交阶段注入故障。 */
    private boolean failCommit;

    /** 真实生产共享监视器。 */
    private final OrderWriteMonitor monitor;

    /** 每个测试独立的开始/完成计数。 */
    private final AtomicInteger begins = new AtomicInteger(),
        commits = new AtomicInteger(),
        rollbacks = new AtomicInteger();

    /** 并发用例需要观察的三个事务入口。 */
    private volatile CountDownLatch expectedBegins = new CountDownLatch(0);

    ObservedTransactions(OrderWriteMonitor monitor) {
      this.monitor = monitor;
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertFalse(Thread.holdsLock(monitor));
      assertFalse(definition.isReadOnly());
      assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, definition.getPropagationBehavior());
      begins.incrementAndGet();
      expectedBegins.countDown();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertFalse(Thread.holdsLock(monitor));
      commits.incrementAndGet();
      if (failCommit)
        throw new org.springframework.transaction.TransactionSystemException("TEST_COMMIT_FAILURE");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      assertFalse(Thread.holdsLock(monitor));
      rollbacks.incrementAndGet();
    }
  }

  /** 直接按生产类型装配，所有远程/SQL依赖为行为受控替身。 */
  static class Fixture implements AutoCloseable {
    /** 当前上下文共享的唯一监视器。 */
    private final OrderWriteMonitor monitor = new OrderWriteMonitor();

    /** 受控原订单持久层。 */
    private final OrderSqlMapper orders = mock(OrderSqlMapper.class);

    /** 观察真实事务代理时序。 */
    private final ObservedTransactions transactions = new ObservedTransactions(monitor);

    /** 本测试专属Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    Fixture() {
      context.getEnvironment().setActiveProfiles("order-creation-wiring-test");
      context.register(
          Transactions.class,
          OrderCreationServiceImpl.class,
          OrderCreationController.class,
          OrderController.class,
          OrderStateController.class,
          OrderStateServiceImpl.class);
      context.registerBean(OrderWriteMonitor.class, () -> monitor);
      context.registerBean("transactionManager", ObservedTransactions.class, () -> transactions);
      context.registerBean(OrderSqlMapper.class, () -> orders);
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(StockClient.class, () -> mock(StockClient.class));
      context.registerBean(ProductClient.class, () -> mock(ProductClient.class));
      context.registerBean(UserClient.class, () -> mock(UserClient.class));
      context.registerBean(CartClient.class, () -> mock(CartClient.class));
      context.registerBean(
          OrderReservationIntentService.class, () -> mock(OrderReservationIntentService.class));
      context.registerBean(
          OrderReservationIntentMapper.class, () -> mock(OrderReservationIntentMapper.class));
      context.registerBean(OrderOutboxMapper.class, () -> mock(OrderOutboxMapper.class));
      context.registerBean(OrderStockActionMapper.class, () -> mock(OrderStockActionMapper.class));
      context.registerBean(
          SeckillOrderDedupMapper.class, () -> mock(SeckillOrderDedupMapper.class));
      context.registerBean(
          SeckillMessageRecoveryMapper.class, () -> mock(SeckillMessageRecoveryMapper.class));
      try {
        context.refresh();
      } catch (RuntimeException failure) {
        context.close();
        throw failure;
      }
      assertTrue(AopUtils.isAopProxy(context.getBean(OrderCreationService.class)));
      assertFalse(AopUtils.isAopProxy(writes()));
      assertTrue(AopUtils.isAopProxy(context.getBean(OrderStateService.class)));
      assertFalse(AopUtils.isAopProxy(creation()));
      assertSame(
          monitor,
          ReflectionTestUtils.getField(context.getBean(OrderStateService.class), "writeMonitor"));
      assertSame(
          monitor,
          ReflectionTestUtils.getField(
              context.getBean(OrderCreationService.class), "writeMonitor"));
    }

    private OrderCreationController creation() {
      return context.getBean(OrderCreationController.class);
    }

    private OrderStateController writes() {
      return context.getBean(OrderStateController.class);
    }

    /** 释放专属上下文，线程由各用例自行关闭。 */
    @Override
    public void close() {
      context.close();
    }
  }
}
