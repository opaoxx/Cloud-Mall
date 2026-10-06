package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
import io.seata.core.context.RootContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 恢复调度的 lease、同向 recover 和边界退避测试。 */
class PayTccRecoverySchedulerTest {
  /** 恢复 lease 存储。 */
  private PayTccExecutionRecoveryStore store;

  /** 只读原 XID recover 协调器。 */
  private PayTccCoordinatorService coordinator;

  /** 准备调度依赖。 */
  @BeforeEach
  void setUp() {
    store = mock(PayTccExecutionRecoveryStore.class);
    coordinator = mock(PayTccCoordinatorService.class);
    RootContext.unbind();
  }

  /** 清理测试线程 XID。 */
  @AfterEach
  void tearDown() {
    RootContext.unbind();
  }

  /** 认领失败时不调用 coordinator recover。 */
  @Test
  void failedClaimDoesNotCallCoordinator() {
    PayTccExecutionPO candidate = candidate();
    when(store.findDue(any(), eq(20))).thenReturn(List.of(candidate));
    when(store.claimRecovery(anyString(), anyString(), any(), any())).thenReturn(null);
    PayTccRecoveryScheduler scheduler = scheduler(20, 60, 10_000);

    scheduler.recoverDue();

    verify(coordinator, never()).recover(anyString());
    verify(store, never()).finishRecovery(anyString(), anyString(), any(), any());
  }

  /** 普通异常和 CONFLICT 都只释放调度元数据，不调用 execute。 */
  @Test
  void recoverFailureFinishesMetadataAndNeverExecutes() {
    PayTccExecutionPO candidate = candidate();
    when(store.findDue(any(), eq(20))).thenReturn(List.of(candidate));
    when(store.claimRecovery(anyString(), anyString(), any(), any())).thenReturn(candidate);
    when(coordinator.recover(candidate.payNo))
        .thenThrow(new BizException("PAY_TCC_CONFLICT", "conflict", 409));
    PayTccRecoveryScheduler scheduler = scheduler(20, 60, 10_000);

    scheduler.recoverDue();

    verify(store)
        .finishRecovery(
            eq(candidate.payNo), anyString(), any(LocalDateTime.class), eq("PAY_TCC_CONFLICT"));
    verify(coordinator, never()).execute(any());
  }

  /** 同一调度器重入时只允许一个线程发出 recover。 */
  @Test
  void reentrantRunDoesNotOverlapRpc() throws Exception {
    PayTccExecutionPO candidate = candidate();
    CountDownLatch rpcEntered = new CountDownLatch(1);
    CountDownLatch releaseRpc = new CountDownLatch(1);
    when(store.findDue(any(), eq(20))).thenReturn(List.of(candidate));
    when(store.claimRecovery(anyString(), anyString(), any(), any())).thenReturn(candidate);
    doAnswer(
            invocation -> {
              rpcEntered.countDown();
              releaseRpc.await(2, TimeUnit.SECONDS);
              return executionView();
            })
        .when(coordinator)
        .recover(candidate.payNo);
    PayTccRecoveryScheduler scheduler = scheduler(20, 60, 10_000);
    Thread first = new Thread(scheduler::recoverDue);
    first.start();
    org.junit.jupiter.api.Assertions.assertTrue(rpcEntered.await(1, TimeUnit.SECONDS));
    scheduler.recoverDue();
    releaseRpc.countDown();
    first.join(2_000);

    verify(coordinator).recover(candidate.payNo);
  }

  /** 已有 RootContext 时直接拒绝调度，不能继承外部事务。 */
  @Test
  void existingRootContextIsRejected() {
    RootContext.bind("foreign-xid");
    PayTccRecoveryScheduler scheduler = scheduler(20, 60, 10_000);

    assertThrows(IllegalStateException.class, scheduler::recoverDue);
    verify(store, never()).findDue(any(), anyInt());
  }

  /** 极短预算会在开始下一条前停止批处理。 */
  @Test
  void budgetStopsBatchBeforeClaim() {
    when(store.findDue(any(), eq(20)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(15);
              return List.of(candidate());
            });
    PayTccRecoveryScheduler scheduler = scheduler(20, 60, 1);

    scheduler.recoverDue();

    verify(store, never()).claimRecovery(anyString(), anyString(), any(), any());
  }

  /** 退避从 5 秒增长并在 300 秒封顶，超大次数不溢出。 */
  @Test
  void retryDelayHasBoundedExponentialEdges() {
    assertEquals(5L, PayTccRecoveryScheduler.retryDelaySeconds(1));
    assertEquals(10L, PayTccRecoveryScheduler.retryDelaySeconds(2));
    assertEquals(300L, PayTccRecoveryScheduler.retryDelaySeconds(7));
    assertEquals(300L, PayTccRecoveryScheduler.retryDelaySeconds(31));
    assertEquals(300L, PayTccRecoveryScheduler.retryDelaySeconds(Integer.MAX_VALUE));
    assertEquals(5L, PayTccRecoveryScheduler.retryDelaySeconds(Integer.MIN_VALUE));
  }

  private PayTccRecoveryScheduler scheduler(int batchSize, int leaseSeconds, long budgetMillis) {
    return new PayTccRecoveryScheduler(
        store,
        coordinator,
        mock(com.cloudmall.pay.service.impl.PayTccRollbackProjectionService.class),
        batchSize,
        leaseSeconds,
        budgetMillis);
  }

  private PayTccExecutionPO candidate() {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay-recovery-test";
    execution.state = "COMMIT_REQUESTED";
    execution.decision = "COMMIT";
    execution.xid = "xid-recovery-test";
    execution.recoveryAttempts = 1;
    return execution;
  }

  private PayTccExecutionVO executionView() {
    PayTccExecutionVO view = new PayTccExecutionVO();
    view.state = "UNKNOWN";
    return view;
  }
}
