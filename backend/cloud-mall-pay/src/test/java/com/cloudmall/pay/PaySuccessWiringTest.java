package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PaySuccessService;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 新成功入口的实际Spring装配，验证原无事务及锁内身份边界。 */
class PaySuccessWiringTest {
  @Test
  void noNewTransactionAndExistingCallerResourcesRemainBoundDuringOrchestration() {
    try (var fixture = new Fixture()) {
      AuthContext.set(7L, "USER");
      var row = new PayRecordPO();
      row.payNo = "pay-1";
      row.userId = 7L;
      row.amount = new BigDecimal("10.00");
      row.status = "PENDING";
      row.debitMode = "LEGACY";
      when(fixture.records.selectByPayNo("pay-1")).thenReturn(List.of(row));
      when(fixture.legacy.initiate(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return "UNKNOWN";
              });
      assertEquals(
          202,
          assertThrows(BizException.class, () -> fixture.controller.success("pay-1"))
              .getHttpStatus());
      assertEquals(0, fixture.transactions.begins);
      Object callerResource = new Object();
      doAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertSame(
                    callerResource, TransactionSynchronizationManager.getResource(fixture.records));
                return "UNKNOWN";
              })
          .when(fixture.legacy)
          .initiate(any());
      new TransactionTemplate(fixture.transactions)
          .execute(
              status -> {
                TransactionSynchronizationManager.bindResource(fixture.records, callerResource);
                try {
                  assertEquals(
                      202,
                      assertThrows(BizException.class, () -> fixture.controller.success("pay-1"))
                          .getHttpStatus());
                  assertSame(
                      callerResource,
                      TransactionSynchronizationManager.getResource(fixture.records));
                } finally {
                  TransactionSynchronizationManager.unbindResource(fixture.records);
                }
                return null;
              });
      assertEquals(1, fixture.transactions.begins);
      assertEquals(1, fixture.transactions.commits);
      verify(fixture.legacy, times(2)).initiate(any());
    }
  }

  @Test
  void unauthenticatedRequestWaitsForSharedLockBeforeReadingIdentity() throws Exception {
    try (var fixture = new Fixture()) {
      var executor = Executors.newSingleThreadExecutor();
      var thread = new AtomicReference<Thread>();
      Future<BizException> result;
      try {
        synchronized (fixture.monitor) {
          result =
              executor.submit(
                  () -> {
                    thread.set(Thread.currentThread());
                    AuthContext.clear();
                    return assertThrows(
                        BizException.class, () -> fixture.controller.success("pay-1"));
                  });
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          while (thread.get() == null || thread.get().getState() != Thread.State.BLOCKED) {
            assertFalse(result.isDone(), "authentication must not precede original monitor");
            assertTrue(System.nanoTime() < deadline, "entry must attempt shared monitor");
            Thread.sleep(5);
          }
          verifyNoInteractions(fixture.records, fixture.legacy);
          assertEquals(0, fixture.transactions.begins);
        }
        assertEquals(401, result.get(3, TimeUnit.SECONDS).getHttpStatus());
        verifyNoInteractions(fixture.records, fixture.legacy);
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
      }
    }
  }

  /** 仅本类显式启用的事务拦截配置。 */
  @Configuration
  @Profile("pay-success-wiring-test")
  @EnableTransactionManagement
  static class Transactions {}

  /** 记录事务边界，无数据库或远端资源。 */
  static class ObservedTransactions extends AbstractPlatformTransactionManager {
    /** 调用者事务开始次数。 */
    private int begins;

    /** 调用者事务提交次数。 */
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      fail("unexpected rollback");
    }
  }

  /** 实际构造器注入，不导入数据源或服务自动配置。 */
  static class Fixture implements AutoCloseable {
    /** 独立Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 原查询替身。 */
    private final PayRecordMapper records = mock(PayRecordMapper.class);

    /** 原Legacy编排替身。 */
    private final PayConsistencyService legacy = mock(PayConsistencyService.class);

    /** 共享监视器身份。 */
    private final PayWriteMonitor monitor = new PayWriteMonitor();

    /** 原事务观察器。 */
    private final ObservedTransactions transactions = new ObservedTransactions();

    /** 新成功HTTP入口。 */
    private final PaySuccessController controller;

    Fixture() {
      context.getEnvironment().setActiveProfiles("pay-success-wiring-test");
      context.register(Transactions.class, PaySuccessController.class, PaySuccessServiceImpl.class);
      var beans = context.getBeanFactory();
      beans.registerSingleton("records", records);
      beans.registerSingleton("intents", mock(PayDebitIntentMapper.class));
      beans.registerSingleton("legacy", legacy);
      beans.registerSingleton("metrics", PayProtocolMetrics.noop());
      beans.registerSingleton("payWriteMonitor", monitor);
      beans.registerSingleton("transactionManager", transactions);
      context.refresh();
      controller = context.getBean(PaySuccessController.class);
      assertFalse(AopUtils.isAopProxy(context.getBean(PaySuccessService.class)));
      assertFalse(AopUtils.isAopProxy(controller));
      assertEquals(1, context.getBeansOfType(PaySuccessService.class).size());
    }

    /** 关闭上下文并确认没有线程事务泄漏。 */
    @Override
    public void close() {
      context.close();
      AuthContext.clear();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }
}
