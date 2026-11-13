package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.controller.PayCallbackController;
import com.cloudmall.pay.controller.PayCreationController;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.controller.PayQueryController;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCallbackService;
import com.cloudmall.pay.service.PayCreationService;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PaySuccessService;
import com.cloudmall.pay.service.impl.*;
import com.cloudmall.pay.service.impl.PayCallbackServiceImpl;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实Spring代理与线程屏障验证创建事务、共享写监视器和原不加锁入口。 */
class PayCreationWiringTest {
  @Test
  void transactionBeginsBeforeWaitingAndIdentityRunsInsideLockWithCompletionOutside()
      throws Exception {
    try (var fixture = new Fixture()) {
      AtomicInteger identities = new AtomicInteger();
      when(fixture.records.selectByPayNo(anyString()))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return List.of(payment());
              });
      RunningCall<PayVO> creation;
      synchronized (fixture.monitor) {
        creation =
            fixture.submit(
                () ->
                    fixture.service.create(
                        "key",
                        request(),
                        () -> {
                          identities.incrementAndGet();
                          assertTrue(Thread.holdsLock(fixture.monitor));
                          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                          return 7L;
                        }));
        assertTrue(fixture.transactions.begun.await(3, TimeUnit.SECONDS));
        creation.awaitBlocked();
        assertEquals(0, identities.get());
        verifyNoInteractions(fixture.records);
      }
      assertEquals("PENDING", creation.future.get(3, TimeUnit.SECONDS).status);
      assertEquals(1, identities.get());
      assertEquals(1, fixture.transactions.commits.get());
      AuthContext.clear();
      assertEquals(
          401,
          assertThrows(BizException.class, () -> fixture.controller.create("key", request()))
              .getHttpStatus());
      assertEquals(2, fixture.transactions.begins.get());
      assertEquals(1, fixture.transactions.rollbacks.get());
      verify(fixture.records).selectByPayNo(anyString());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  @Test
  void creationSuccessAndCallbackShareOneLockWhileFailureAndQueryRemainIndependent()
      throws Exception {
    try (var fixture = new Fixture()) {
      when(fixture.records.selectByPayNo(anyString()))
          .thenAnswer(
              invocation -> {
                if (Thread.currentThread().getName().equals("unlocked-entries")) {
                  assertFalse(Thread.holdsLock(fixture.monitor));
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                } else {
                  assertTrue(Thread.holdsLock(fixture.monitor));
                }
                PayRecordPO row = payment();
                row.status = "SUCCESS";
                row.debitMode = "LEGACY";
                return List.of(row);
              });
      when(fixture.records.selectByOrderNoAndUserId("order-1", 7L)).thenReturn(List.of(payment()));
      when(fixture.failure.fail(any(PayVO.class)))
          .thenAnswer(
              invocation -> {
                assertFalse(Thread.holdsLock(fixture.monitor));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return invocation.getArgument(0);
              });
      RunningCall<?> creation;
      RunningCall<?> success;
      RunningCall<?> callback;
      synchronized (fixture.monitor) {
        creation = fixture.submit(() -> fixture.controller.create("key", request()));
        success = fixture.submit(() -> fixture.success.success("pay-1"));
        callback =
            fixture.submit(
                () ->
                    assertThrows(
                        BizException.class,
                        () -> fixture.callback.callback("invalid-token", null)));
        creation.awaitBlocked();
        success.awaitBlocked();
        callback.awaitBlocked();
        verifyNoInteractions(fixture.records, fixture.failure);
        var independent =
            fixture.submit(
                () -> {
                  Thread.currentThread().setName("unlocked-entries");
                  fixture.query.get("order-1");
                  fixture.failureController.fail("pay-1");
                  return true;
                });
        assertTrue(independent.future.get(3, TimeUnit.SECONDS));
        assertFalse(creation.future.isDone());
        assertFalse(success.future.isDone());
        assertFalse(callback.future.isDone());
      }
      creation.future.get(3, TimeUnit.SECONDS);
      success.future.get(3, TimeUnit.SECONDS);
      assertEquals(403, ((BizException) callback.future.get(3, TimeUnit.SECONDS)).getHttpStatus());
      assertEquals(1, fixture.transactions.begins.get());
      assertEquals(1, fixture.transactions.commits.get());
    }
  }

  private static PayCreateDTO request() {
    var request = new PayCreateDTO();
    request.orderNo = "order-1";
    request.payAmount = new BigDecimal("10.00");
    return request;
  }

  private static PayRecordPO payment() {
    var row = new PayRecordPO();
    row.payNo = "pay-1";
    row.orderNo = "order-1";
    row.userId = 7L;
    row.amount = new BigDecimal("10.00");
    row.status = "PENDING";
    return row;
  }

  /** 只供本测试显式启用事务拦截。 */
  @Configuration
  @Profile("pay-creation-wiring-test")
  @EnableTransactionManagement
  static class Transactions {}

  /** 在真实Spring事务生命周期回调处核验监视器状态，不模拟业务锁。 */
  static class ObservedTransactions extends AbstractPlatformTransactionManager {
    /** 被观测的生产监视器。 */
    private final PayWriteMonitor monitor;

    /** 启动屏障。 */
    private final CountDownLatch begun = new CountDownLatch(1);

    /** 生命周期计数。 */
    private final AtomicInteger begins = new AtomicInteger(),
        commits = new AtomicInteger(),
        rollbacks = new AtomicInteger();

    ObservedTransactions(PayWriteMonitor monitor) {
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
      begun.countDown();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertFalse(Thread.holdsLock(monitor));
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      assertFalse(Thread.holdsLock(monitor));
      rollbacks.incrementAndGet();
    }
  }

  /** 有界等待实际BLOCKED状态，避免用固定sleep推断是否已请求锁。 */
  static class RunningCall<T> {
    /** 实际执行线程。 */
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    /** 调用结果。 */
    private Future<T> future;

    void awaitBlocked() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        Thread worker = thread.get();
        if (worker != null && worker.getState() == Thread.State.BLOCKED) return;
        if (future.isDone()) fail("entry completed without acquiring shared monitor");
        Thread.sleep(5);
      }
      fail("entry did not wait on shared monitor");
    }
  }

  /** 实际新旧Bean共存，远端及SQL仅受控替身；不启用任何调度。 */
  static class Fixture implements AutoCloseable {
    /** 测试生命周期内的Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 并发入口线程池。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 唯一生产监视器。 */
    private final PayWriteMonitor monitor = new PayWriteMonitor();

    /** 事务观察点。 */
    private final ObservedTransactions transactions = new ObservedTransactions(monitor);

    /** 数据读取观察点。 */
    private final PayRecordMapper records = mock(PayRecordMapper.class);

    /** 原失败流程替身。 */
    private final PayLegacyFailureService failure = mock(PayLegacyFailureService.class);

    /** 创建代理。 */
    private final PayCreationService service;

    /** 新创建入口。 */
    private final PayCreationController controller;

    /** 原成功/失败/回调入口。 */
    private final PayCallbackController callback;

    /** 新成功入口，与创建和回调共用同一个锁。 */
    private final PaySuccessController success;

    /** 独立失败HTTP入口，不持共享锁。 */
    private final PayFailureController failureController;

    /** 原查询入口。 */
    private final PayQueryController query;

    Fixture() throws Exception {
      context.getEnvironment().setActiveProfiles("pay-creation-wiring-test");
      context.register(
          Transactions.class,
          PayCreationController.class,
          PayCreationServiceImpl.class,
          PayCallbackController.class,
          PayCallbackServiceImpl.class,
          PaySuccessController.class,
          PaySuccessServiceImpl.class,
          PayFailureController.class,
          PayFailureServiceImpl.class,
          PayQueryController.class,
          PayQueryServiceImpl.class);
      var beans = context.getBeanFactory();
      beans.registerSingleton("payWriteMonitor", monitor);
      beans.registerSingleton("transactionManager", transactions);
      beans.registerSingleton("records", records);
      beans.registerSingleton("failure", failure);
      beans.registerSingleton("mode", mock(PayDebitModeConfiguration.class));
      beans.registerSingleton("orders", mock(OrderClient.class));
      beans.registerSingleton("users", mock(UserClient.class));
      beans.registerSingleton("logs", mock(PayCallbackLogMapper.class));
      beans.registerSingleton("intents", mock(PayDebitIntentMapper.class));
      beans.registerSingleton("consistency", mock(PayConsistencyService.class));
      beans.registerSingleton("receipts", mock(PayCallbackReceiptService.class));
      beans.registerSingleton("metrics", PayProtocolMetrics.noop());
      context.refresh();
      service = context.getBean(PayCreationService.class);
      controller = context.getBean(PayCreationController.class);
      callback = context.getBean(PayCallbackController.class);
      success = context.getBean(PaySuccessController.class);
      failureController = context.getBean(PayFailureController.class);
      query = context.getBean(PayQueryController.class);
      assertTrue(AopUtils.isAopProxy(service));
      assertFalse(AopUtils.isAopProxy(context.getBean(PayCallbackService.class)));
      Object creationTarget = AopTestUtils.getTargetObject(service);
      assertSame(monitor, ReflectionTestUtils.getField(creationTarget, "writeMonitor"));
      assertSame(
          monitor,
          ReflectionTestUtils.getField(context.getBean(PayCallbackService.class), "writeMonitor"));
      assertSame(
          monitor,
          ReflectionTestUtils.getField(context.getBean(PaySuccessService.class), "writeMonitor"));
      assertEquals(1, context.getBeansOfType(PayWriteMonitor.class).size());
    }

    <T> RunningCall<T> submit(Callable<T> work) {
      var call = new RunningCall<T>();
      call.future =
          executor.submit(
              () -> {
                call.thread.set(Thread.currentThread());
                AuthContext.set(7L, "USER");
                try {
                  return work.call();
                } finally {
                  AuthContext.clear();
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                }
              });
      return call;
    }

    /** 释放测试线程及上下文，保留任何异常为失败结果。 */
    @Override
    public void close() throws Exception {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
      context.close();
      AuthContext.clear();
    }
  }
}
