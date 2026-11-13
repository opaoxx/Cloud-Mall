package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptRecoveryScheduler;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实Spring代理事务边界测试；资源为内存记录，不冒充MySQL锁或数据库集成。 */
class PayCallbackReceiptRecoveryTransactionTest {
  /** 每条跨Bean调用均挂起外层事务，坏行独立回滚，正常行独立提交且重扫不重写。 */
  @Test
  void eachReceiptUsesRequiresNewAndSurvivesOuterRollback() {
    PayCallbackLogMapper logs = mock(PayCallbackLogMapper.class);
    PayRecordMapper payments = mock(PayRecordMapper.class);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    PayCallbackLogPO bad = receipt(1L, "bad");
    bad.payload = "{}";
    PayCallbackLogPO good = receipt(2L, "good");
    Map<String, PayCallbackLogPO> rows = Map.of("bad", bad, "good", good);
    List<Integer> paymentTransactions = new ArrayList<>();
    List<String> lockOrder = new ArrayList<>();
    when(logs.findCompletableAfter(null, 50)).thenReturn(List.of(bad, good));
    when(logs.selectByCallbackId(anyString()))
        .thenAnswer(invocation -> List.of(rows.get(invocation.getArgument(0))));
    when(payments.selectForUpdate(anyString()))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              paymentTransactions.add(transactions.currentIdentity());
              String payNo = invocation.getArgument(0);
              lockOrder.add("payment:" + payNo);
              PayRecordPO payment = new PayRecordPO();
              payment.payNo = payNo;
              payment.userId = 7L;
              payment.amount = new BigDecimal("10.00");
              payment.status = "SUCCESS";
              payment.debitMode = "LEGACY";
              return List.of(payment);
            });
    when(logs.lockedSelect(anyString()))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              String callbackId = invocation.getArgument(0);
              lockOrder.add("callback:" + callbackId);
              return rows.get(callbackId);
            });
    when(logs.markProcessed(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              LocalDateTime completedAt = invocation.getArgument(3);
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    /** 只有真实Spring提交同步触发后，模拟日志写入才对其它事务可见。 */
                    @Override
                    public void afterCommit() {
                      good.callbackStatus = "SUCCESS";
                      good.processedAt = completedAt;
                    }
                  });
              return 1;
            });

    PayCallbackReceiptService target =
        new PayCallbackReceiptService(logs, payments, new ObjectMapper());
    TransactionInterceptor advice = new TransactionInterceptor();
    advice.setTransactionManager(transactions);
    advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    advice.afterPropertiesSet();
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(advice);
    PayCallbackReceiptService proxy = (PayCallbackReceiptService) factory.getProxy();
    PayCallbackReceiptRecoveryScheduler scheduler =
        new PayCallbackReceiptRecoveryScheduler(logs, proxy);
    AtomicInteger outerIdentity = new AtomicInteger();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            outer -> {
              outerIdentity.set(transactions.currentIdentity());
              scheduler.recoverDue();
              assertEquals(outerIdentity.get(), transactions.currentIdentity());
              assertEquals("SUCCESS", good.callbackStatus);
              outer.setRollbackOnly();
            });

    assertEquals(1, transactions.commits.get());
    assertEquals(2, transactions.rollbacks.get());
    assertNotEquals(outerIdentity.get(), paymentTransactions.get(0));
    assertNotEquals(outerIdentity.get(), paymentTransactions.get(1));
    assertNotEquals(paymentTransactions.get(0), paymentTransactions.get(1));
    assertEquals(
        List.of("payment:bad", "callback:bad", "payment:good", "callback:good"), lockOrder);
    assertEquals("RECEIVED_SUCCESS", bad.callbackStatus);
    LocalDateTime completedAt = good.processedAt;
    new PayCallbackReceiptRecoveryScheduler(logs, proxy).recoverDue();
    assertEquals(completedAt, good.processedAt);
    verify(logs, times(1)).markProcessed(any(), any(), any(), any());
  }

  private static PayCallbackLogPO receipt(Long identity, String callbackId) {
    PayCallbackLogPO row = new PayCallbackLogPO();
    row.id = identity;
    row.callbackId = callbackId;
    row.payNo = callbackId;
    row.callbackStatus = "RECEIVED_SUCCESS";
    row.payload =
        "{\"payNo\":\"" + callbackId + "\",\"userId\":7,\"amount\":\"10.00\",\"success\":true}";
    return row;
  }

  /** 只记录Spring实际begin/commit/rollback/suspend/resume，模拟资源在afterCommit后可见。 */
  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    /** 继承事务管理器的序列化版本。 */
    private static final long serialVersionUID = 1L;

    /** 当前线程事务，供真实REQUIRES_NEW挂起和恢复。 */
    private final ThreadLocal<RecordingResource> current = new ThreadLocal<>();

    /** 分配不同事务的本地观察编号。 */
    private final AtomicInteger identities = new AtomicInteger();

    /** 实际提交回调次数。 */
    private final AtomicInteger commits = new AtomicInteger();

    /** 实际回滚回调次数。 */
    private final AtomicInteger rollbacks = new AtomicInteger();

    private int currentIdentity() {
      return current.get().identity;
    }

    @Override
    protected Object doGetTransaction() {
      return new RecordingTransaction(current.get());
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      RecordingResource resource = ((RecordingTransaction) transaction).resource;
      return resource != null && resource.active;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      RecordingResource started = new RecordingResource();
      started.identity = identities.incrementAndGet();
      started.active = true;
      ((RecordingTransaction) transaction).resource = started;
      current.set(started);
    }

    @Override
    protected Object doSuspend(Object transaction) {
      RecordingTransaction holder = (RecordingTransaction) transaction;
      RecordingResource suspended = holder.resource;
      holder.resource = null;
      current.remove();
      return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
      RecordingResource resumed = (RecordingResource) suspendedResources;
      if (transaction != null) ((RecordingTransaction) transaction).resource = resumed;
      current.set(resumed);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertEquals(((RecordingTransaction) status.getTransaction()).resource, current.get());
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      assertEquals(((RecordingTransaction) status.getTransaction()).resource, current.get());
      rollbacks.incrementAndGet();
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
      ((RecordingTransaction) transaction).resource.active = false;
      current.remove();
    }
  }

  /** 每次getTransaction的独立包装，挂起不得改变外层包装持有的资源。 */
  private static final class RecordingTransaction {
    /** 当前包装引用的事务资源，挂起后为空。 */
    private RecordingResource resource;

    private RecordingTransaction(RecordingResource resource) {
      this.resource = resource;
    }
  }

  /** 内存资源标识和存活事实，仅用于本测试管理器。 */
  private static final class RecordingResource {
    /** 每次新事务独立分配的观察编号。 */
    private int identity;

    /** 是否已begin且尚未cleanup。 */
    private boolean active;
  }
}
