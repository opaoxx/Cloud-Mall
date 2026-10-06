package com.cloudmall.pay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
import com.cloudmall.pay.service.impl.PayTccRollbackProjectionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

/** TCC 普通恢复与 ROLLEDBACK 投影调度的公平、预算和租约测试。 */
class PayTccRollbackProjectionSchedulerTest {
  @Mock private PayTccExecutionRecoveryStore store;
  @Mock private PayTccCoordinatorService coordinator;
  @Mock private PayTccRollbackProjectionService projections;

  private PayTccRecoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(store.findDue(any(), any(Integer.class))).thenReturn(List.of());
    when(store.findDueRollbackProjection(any(), any(Integer.class))).thenReturn(List.of());
    scheduler = new PayTccRecoveryScheduler(store, coordinator, projections, 2, 60, 10_000);
  }

  /** 同一轮普通恢复优先且共享 batch 上限，未消费容量才查询另一阶段。 */
  @Test
  void firstPhaseConsumesSharedBatchBeforeProjection() {
    PayTccExecutionPO first = candidate("ordinary-1");
    PayTccExecutionPO second = candidate("ordinary-2");
    when(store.findDue(any(), eq(2))).thenReturn(List.of(first, second));
    when(store.claimRecovery(anyString(), anyString(), any(), any())).thenReturn(null);

    scheduler.recoverDue();

    verify(store).findDue(any(), eq(2));
    verify(store, never()).findDueRollbackProjection(any(), any(Integer.class));
  }

  /** 下一轮切换投影优先，避免普通恢复长期占满预算。 */
  @Test
  void nextRoundPrioritizesRollbackProjection() {
    PayTccExecutionPO projection = candidate("rollback-1");
    when(store.findDueRollbackProjection(any(), eq(2))).thenReturn(List.of(projection));
    when(store.claimRollbackProjection(anyString(), anyString(), any(), any())).thenReturn(null);

    scheduler.recoverDue();
    scheduler.recoverDue();

    InOrder order = org.mockito.Mockito.inOrder(store);
    order.verify(store).findDue(any(), eq(2));
    order.verify(store).findDueRollbackProjection(any(), eq(2));
    order.verify(store).findDueRollbackProjection(any(), eq(2));
    order.verify(store).findDue(any(), eq(1));
  }

  /** 普通阶段查询异常不能阻断另一阶段继续扫描。 */
  @Test
  void ordinaryScanFailureStillRunsProjectionPhase() {
    when(store.findDue(any(), eq(2))).thenThrow(new IllegalStateException("ordinary scan down"));
    PayTccExecutionPO projection = candidate("rollback-after-error");
    when(store.findDueRollbackProjection(any(), eq(2))).thenReturn(List.of(projection));
    when(store.claimRollbackProjection(anyString(), anyString(), any(), any())).thenReturn(null);

    scheduler.recoverDue();

    verify(store).findDueRollbackProjection(any(), eq(2));
  }

  /** 第一阶段 RPC 消耗预算后，下一阶段本轮不再启动。 */
  @Test
  void exhaustedBudgetSkipsOtherPhase() {
    PayTccExecutionPO ordinary = candidate("ordinary-slow");
    when(store.findDue(any(), eq(2))).thenReturn(List.of(ordinary));
    when(store.claimRecovery(anyString(), anyString(), any(), any())).thenReturn(ordinary);
    doAnswer(
            invocation -> {
              Thread.sleep(20);
              return executionView("UNKNOWN");
            })
        .when(coordinator)
        .recover(ordinary.payNo);

    scheduler = new PayTccRecoveryScheduler(store, coordinator, projections, 2, 60, 1);
    scheduler.recoverDue();

    verify(store, never()).findDueRollbackProjection(any(), any(Integer.class));
  }

  /** 投影任务认领失败时不调用投影服务。 */
  @Test
  void failedRollbackClaimDoesNotProject() {
    PayTccExecutionPO projection = candidate("rollback-no-claim");
    ReflectionTestUtils.setField(scheduler, "rollbackFirst", true);
    when(store.findDueRollbackProjection(any(), eq(2))).thenReturn(List.of(projection));
    when(store.claimRollbackProjection(anyString(), anyString(), any(), any())).thenReturn(null);

    scheduler.recoverDue();

    verify(projections, never()).projectClaimed(any(), anyString());
    verify(coordinator, never()).recover(anyString());
  }

  /** 投影异常仍用同一个认领 token 释放 recovery lease。 */
  @Test
  void rollbackProjectionFailureFinishesSameToken() {
    PayTccExecutionPO projection = candidate("rollback-project-failure");
    ReflectionTestUtils.setField(scheduler, "rollbackFirst", true);
    when(store.findDueRollbackProjection(any(), eq(2))).thenReturn(List.of(projection));
    when(store.claimRollbackProjection(anyString(), anyString(), any(), any()))
        .thenReturn(projection);
    when(projections.projectClaimed(any(), anyString()))
        .thenThrow(new BizException("PAY_ORDER_SYNC_UNKNOWN", "evidence pending", 202));

    scheduler.recoverDue();

    var token = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(store).claimRollbackProjection(eq(projection.payNo), token.capture(), any(), any());
    verify(projections).projectClaimed(any(), eq(token.getValue()));
    verify(store)
        .finishRecovery(
            eq(projection.payNo),
            eq(token.getValue()),
            any(LocalDateTime.class),
            eq("PAY_ORDER_SYNC_UNKNOWN"));
    verify(coordinator, never()).recover(anyString());
  }

  /** ROLLEDBACK 投影阶段不调用原 coordinator recover。 */
  @Test
  void rollbackProjectionNeverCallsCoordinator() {
    PayTccExecutionPO projection = candidate("rollback-no-tc");
    ReflectionTestUtils.setField(scheduler, "rollbackFirst", true);
    when(store.findDueRollbackProjection(any(), eq(2))).thenReturn(List.of(projection));
    when(store.claimRollbackProjection(anyString(), anyString(), any(), any()))
        .thenReturn(projection);
    when(projections.projectClaimed(any(), anyString())).thenReturn("FAILED");

    scheduler.recoverDue();

    verify(projections).projectClaimed(any(), anyString());
    verify(coordinator, never()).recover(anyString());
  }

  private PayTccExecutionPO candidate(String payNo) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = payNo;
    execution.userId = 7L;
    execution.orderNo = "order-" + payNo;
    execution.amount = new java.math.BigDecimal("10.00");
    execution.xid = "xid-" + payNo;
    execution.recoveryAttempts = 1;
    return execution;
  }

  private PayTccExecutionVO executionView(String state) {
    PayTccExecutionVO view = new PayTccExecutionVO();
    view.state = state;
    return view;
  }
}
