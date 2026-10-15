package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 恢复异常的快照与业务码处理；实际Coordinator；持久化及外部依赖全部为替身。 */
class PayTccRecoveryFailureTest {
  /** 双入口双向TC异常使用原chosen对象；诊断冲突传播后不得末次恢复或按key写入。 */
  @Test
  void decisionFailuresUseChosenSnapshotAndStopOnDrift() throws Exception {
    for (String entry : List.of("execute", "recover"))
      for (String decision : List.of("COMMIT", "ROLLBACK"))
        for (boolean rejectDiagnostic : List.of(false, true)) {
          var fixture =
              new DecisionFixture(
                  entry, decision, rejectDiagnostic, new Exception("response lost"));
          if (rejectDiagnostic)
            assertSame(fixture.conflict, assertThrows(BizException.class, fixture::call));
          else assertEquals(decision + "_REQUESTED", fixture.call().state);
          verify(fixture.store)
              .observeDecidedSnapshot(same(fixture.chosen), eq("UNKNOWN"), eq("Exception"));
          verify(fixture.store, never()).observe(any(), any(), any());
          int readsBeforeFailure = entry.equals("recover") || decision.equals("COMMIT") ? 1 : 0;
          verify(fixture.store, times(readsBeforeFailure)).read(fixture.request.payNo);
          verify(fixture.store, times(rejectDiagnostic ? 1 : 2))
              .verifyDecisionSnapshot(same(fixture.chosen));
          assertEquals(1, fixture.diagnosticCalls);
          fixture.verifyTcCalls();
          assertNull(RootContext.getXID());
        }
  }

  /** NULL/其它/小写业务码保UNKNOWN与已有同向重试；明确冲突原对象传播。 */
  @Test
  void recoveryBusinessCodesKeepUnknownAndConflictSemantics() throws Exception {
    for (String decision : List.of("NONE", "COMMIT", "ROLLBACK"))
      for (String code :
          java.util.Arrays.asList(
              null, "REMOTE_UNAVAILABLE", "lower_conflict", "PAY_TCC_EXECUTION_CONFLICT")) {
        var store = mock(PayTccExecutionStore.class);
        var receipts = mock(PayTccReceiptService.class);
        var bridge = mock(PayTccBridgeService.class);
        var evidence = mock(PayTccExecutionEvidence.class);
        var transactions = mock(PayTccTransactionClient.class);
        var current =
            execution(decision.equals("NONE") ? "RUNNING" : decision + "_REQUESTED", decision);
        var updated =
            execution(decision.equals("NONE") ? "UNKNOWN" : decision + "_REQUESTED", decision);
        var failure = new BizException(code, "controlled Bridge failure", 409);
        when(store.read(current.payNo)).thenReturn(current);
        when(store.observeSnapshot(any(), any(), any())).thenReturn(updated);
        when(store.observeDecidedSnapshot(any(), any(), any())).thenReturn(updated);
        when(store.verifyDecisionSnapshot(updated)).thenReturn(updated);
        when(bridge.recover(current.payNo)).thenThrow(failure);
        var coordinator =
            new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions);
        boolean conflict = "PAY_TCC_EXECUTION_CONFLICT".equals(code);
        int observations = conflict ? 0 : decision.equals("NONE") ? 1 : 2;
        if (conflict)
          assertSame(
              failure, assertThrows(BizException.class, () -> coordinator.recover(current.payNo)));
        else assertEquals(updated.state, coordinator.recover(current.payNo).state);
        verify(store).read(current.payNo);
        verify(bridge, times(conflict ? 1 : observations)).recover(current.payNo);
        if (!conflict && decision.equals("NONE"))
          verify(store).observeSnapshot(same(current), eq("UNKNOWN"), eq(code));
        if (!conflict && !decision.equals("NONE")) {
          verify(store).observeDecidedSnapshot(same(current), eq("UNKNOWN"), eq(code));
          verify(store).observeDecidedSnapshot(same(updated), eq("UNKNOWN"), eq(code));
          verify(store, times(2)).verifyDecisionSnapshot(updated);
        }
        verifyNoMoreInteractions(store, bridge);
        verifyNoInteractions(receipts, evidence);
        if (!conflict && decision.equals("COMMIT")) verify(transactions).commit(current.xid);
        if (!conflict && decision.equals("ROLLBACK")) verify(transactions).rollback(current.xid);
        verifyNoMoreInteractions(transactions);
        assertNull(RootContext.getXID());
      }
  }

  /** 不按TC业务码猜资金结果；普通Runtime、既有证据冲突和缓存终态保持原语义。 */
  @Test
  void transportAndExistingRecoveryBranchesRemain() throws Exception {
    for (Exception transport :
        List.of(
            new IllegalStateException("TC unavailable"),
            new BizException(null, "TC code missing", 503),
            new BizException("TC_CONFLICT", "TC response conflict", 409))) {
      var fixture = new DecisionFixture("recover", "COMMIT", false, transport);
      assertEquals("COMMIT_REQUESTED", fixture.call().state);
      verify(fixture.store)
          .observeDecidedSnapshot(
              same(fixture.chosen), eq("UNKNOWN"), eq(transport.getClass().getSimpleName()));
      verify(fixture.store, never()).observe(any(), any(), any());
      fixture.verifyTcCalls();
    }
    for (boolean evidenceConflict : List.of(false, true)) {
      var store = mock(PayTccExecutionStore.class);
      var receipts = mock(PayTccReceiptService.class);
      var bridge = mock(PayTccBridgeService.class);
      var evidence = mock(PayTccExecutionEvidence.class);
      var transactions = mock(PayTccTransactionClient.class);
      var current = execution("COMMIT_REQUESTED", "COMMIT");
      var user = new PayTccDispatchVO();
      when(store.read(current.payNo)).thenReturn(current);
      when(store.observeSnapshot(any(), any(), any())).thenReturn(current);
      when(store.observeDecidedSnapshot(any(), any(), any())).thenReturn(current);
      when(store.verifyDecisionSnapshot(current)).thenReturn(current);
      var conflict = new BizException("PAY_TCC_EXECUTION_CONFLICT", "evidence conflict", 409);
      if (evidenceConflict) {
        when(bridge.recover(current.payNo)).thenReturn(user);
        when(evidence.classify(current, user)).thenThrow(conflict);
      } else
        when(bridge.recover(current.payNo))
            .thenThrow(new IllegalStateException("Bridge unavailable"));
      var coordinator =
          new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions);
      if (evidenceConflict) {
        assertSame(
            conflict, assertThrows(BizException.class, () -> coordinator.recover(current.payNo)));
        verify(store, never()).observeSnapshot(any(), any(), any());
        verify(store, never()).observeDecidedSnapshot(any(), any(), any());
        verifyNoInteractions(transactions);
      } else {
        assertEquals("COMMIT_REQUESTED", coordinator.recover(current.payNo).state);
        verify(store, times(2))
            .observeDecidedSnapshot(same(current), eq("UNKNOWN"), eq("IllegalStateException"));
        verify(transactions).commit(current.xid);
        verifyNoMoreInteractions(transactions);
      }
      verify(store, never()).observe(any(), any(), any());
      verifyNoInteractions(receipts);
    }
    var store = mock(PayTccExecutionStore.class);
    var receipts = mock(PayTccReceiptService.class);
    var bridge = mock(PayTccBridgeService.class);
    var evidence = mock(PayTccExecutionEvidence.class);
    var transactions = mock(PayTccTransactionClient.class);
    var terminal = execution("COMMITTED", "COMMIT");
    when(store.read(terminal.payNo)).thenReturn(terminal);
    assertEquals(
        "COMMITTED",
        new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions)
            .recover(terminal.payNo)
            .state);
    verify(store).read(terminal.payNo);
    verifyNoMoreInteractions(store);
    verifyNoInteractions(receipts, bridge, evidence, transactions);
  }

  /** gate在TC异常catch之外；原冲突传播，最新同向终态不再发TC。 */
  @Test
  void decisionGateConflictsAndLatestTerminalStopBeforeRpc() throws Exception {
    for (String entry : List.of("execute", "recover"))
      for (String decision : List.of("COMMIT", "ROLLBACK"))
        for (boolean reject : List.of(false, true)) {
          var fixture = new DecisionFixture(entry, decision, false, new Exception("must not send"));
          var terminal = execution("COMMITTED", decision);
          var verified = new java.util.concurrent.atomic.AtomicBoolean();
          when(fixture.store.read(fixture.request.payNo))
              .thenAnswer(invocation -> verified.get() ? terminal : fixture.original);
          when(fixture.store.verifyDecisionSnapshot(fixture.chosen))
              .thenAnswer(
                  invocation -> {
                    if (reject) throw fixture.conflict;
                    verified.set(true);
                    return terminal;
                  });
          if (reject) assertSame(fixture.conflict, assertThrows(BizException.class, fixture::call));
          else assertEquals("COMMITTED", fixture.call().state);
          verify(fixture.store, times(reject ? 1 : 2)).verifyDecisionSnapshot(same(fixture.chosen));
          assertEquals(0, fixture.diagnosticCalls);
          if (entry.equals("execute")) verify(fixture.transactions).begin();
          verifyNoMoreInteractions(fixture.transactions);
          assertNull(RootContext.getXID());
        }
    var fixture = new DecisionFixture("recover", "COMMIT", false, new Exception("must not send"));
    var unavailable =
        new org.springframework.dao.DataAccessResourceFailureException("gate read unavailable");
    when(fixture.store.verifyDecisionSnapshot(fixture.chosen)).thenThrow(unavailable);
    assertSame(
        unavailable,
        assertThrows(
            org.springframework.dao.DataAccessResourceFailureException.class, fixture::call));
    assertEquals(0, fixture.diagnosticCalls);
    verifyNoInteractions(fixture.transactions);
  }

  /** TC已成功返回时末次核对仍持chosen；冲突不读新owner，最新终态免Bridge。 */
  @Test
  void finalObservationKeepsChosenSnapshotAfterSuccessfulRpc() throws Exception {
    for (String entry : List.of("execute", "recover"))
      for (String decision : List.of("COMMIT", "ROLLBACK"))
        for (boolean reject : List.of(false, true)) {
          var fixture = new DecisionFixture(entry, decision, false, new Exception("overridden"));
          var sent = new java.util.concurrent.atomic.AtomicBoolean();
          org.mockito.stubbing.Answer<Void> successful =
              invocation -> {
                sent.set(true);
                return null;
              };
          if (decision.equals("COMMIT"))
            org.mockito.Mockito.doAnswer(successful)
                .when(fixture.transactions)
                .commit(fixture.original.xid);
          else
            org.mockito.Mockito.doAnswer(successful)
                .when(fixture.transactions)
                .rollback(fixture.original.xid);
          var latest = execution("COMMITTED", decision);
          when(fixture.store.verifyDecisionSnapshot(fixture.chosen))
              .thenAnswer(
                  invocation -> {
                    if (!sent.get()) return fixture.chosen;
                    if (reject) throw fixture.conflict;
                    return latest;
                  });
          if (reject) assertSame(fixture.conflict, assertThrows(BizException.class, fixture::call));
          else assertEquals("COMMITTED", fixture.call().state);
          verify(fixture.store, times(2)).verifyDecisionSnapshot(same(fixture.chosen));
          int initialReads = entry.equals("recover") || decision.equals("COMMIT") ? 1 : 0;
          verify(fixture.store, times(initialReads)).read(fixture.request.payNo);
          verify(fixture.bridge, times(entry.equals("recover") ? 1 : 0))
              .recover(fixture.request.payNo);
          verify(fixture.store, times(entry.equals("recover") ? 1 : 0))
              .observeSnapshot(any(), any(), any());
          assertEquals(0, fixture.diagnosticCalls);
          fixture.verifyTcCalls();
          assertNull(RootContext.getXID());
        }
  }

  /** 完整原执行载荷，用独立对象区分read结果与观察返回的已决议chosen。 */
  private static PayTccExecutionPO execution(String state, String decision) {
    var execution = new PayTccExecutionPO();
    execution.id = 1L;
    execution.payNo = "pay-recovery-failure";
    execution.xid = "recovery-failure-xid";
    execution.userId = 7L;
    execution.orderNo = "order-recovery-failure";
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:" + execution.payNo;
    execution.ledgerKey = execution.payNo;
    execution.state = state;
    execution.decision = decision;
    return execution;
  }

  /** 编排替身只提供观察结果，不模拟真实Store锁与资金事实。 */
  private static final class DecisionFixture {
    /** 首次执行或恢复入口。 */
    private final String entry;

    /** 原持久决议。 */
    private final String decision;

    /** TC调用失败后的阶段标志。 */
    private boolean rpcFailed;

    /** 原chosen的失败诊断调用次数。 */
    private int diagnosticCalls;

    /** 持久化替身。 */
    private final PayTccExecutionStore store = mock(PayTccExecutionStore.class);

    /** receipt替身。 */
    private final PayTccReceiptService receipts = mock(PayTccReceiptService.class);

    /** 桥替身。 */
    private final PayTccBridgeService bridge = mock(PayTccBridgeService.class);

    /** 证据替身。 */
    private final PayTccExecutionEvidence evidence = mock(PayTccExecutionEvidence.class);

    /** TC接口替身。 */
    private final PayTccTransactionClient transactions = mock(PayTccTransactionClient.class);

    /** 原请求。 */
    private final PayTccDispatchDTO request = new PayTccDispatchDTO();

    /** 原read返回的对象。 */
    private final PayTccExecutionPO original = execution("RUNNING", "NONE");

    /** Store返回的原已决议对象，必须被sendDecision传给失败诊断。 */
    private final PayTccExecutionPO chosen;

    /** 锁内身份漂移冲突替身。 */
    private final BizException conflict =
        new BizException("PAY_TCC_EXECUTION_CONFLICT", "snapshot changed", 409);

    /** 实际被测Coordinator。 */
    private final PayTccCoordinatorServiceImpl coordinator =
        new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions);

    /** 配置原chosen、已发TC及其失败之后的snapshot诊断。 */
    private DecisionFixture(
        String entry, String decision, boolean rejectDiagnostic, Exception transport)
        throws Exception {
      this.entry = entry;
      this.decision = decision;
      chosen = execution(decision + "_REQUESTED", decision);
      request.payNo = original.payNo;
      request.userId = original.userId;
      request.orderNo = original.orderNo;
      request.amount = original.amount;
      var initialOwner = execution("CLAIMED", "NONE");
      initialOwner.xid = null;
      when(store.claimOwned(request)).thenReturn(initialOwner);
      when(store.bindOwnedXid(any(), any())).thenReturn(original);
      when(store.read(original.payNo)).thenReturn(original);
      when(store.decideOwned(any(), any(), any())).thenReturn(chosen);
      when(store.verifyDecisionSnapshot(chosen)).thenReturn(chosen);
      org.mockito.stubbing.Answer<PayTccExecutionPO> observation =
          invocation -> {
            if (rpcFailed
                && invocation.getArgument(0) == chosen
                && "UNKNOWN".equals(invocation.getArgument(1))
                && transport.getClass().getSimpleName().equals(invocation.getArgument(2))) {
              diagnosticCalls++;
              if (rejectDiagnostic) throw conflict;
            }
            return chosen;
          };
      when(store.observeSnapshot(any(), any(), any())).thenAnswer(observation);
      when(store.observeDecidedSnapshot(any(), any(), any())).thenAnswer(observation);
      var receipt = new PayTccReceiptVO();
      receipt.state = decision.equals("COMMIT") ? "TRYING" : "CLAIMED";
      var user = new PayTccDispatchVO();
      when(receipts.prepare(request)).thenReturn(receipt);
      when(bridge.dispatch(request)).thenReturn(user);
      when(bridge.recover(original.payNo)).thenReturn(user);
      when(evidence.classify(any(), any())).thenReturn("TRYING");
      when(transactions.begin())
          .thenAnswer(
              invocation -> {
                RootContext.bind(original.xid);
                return original.xid;
              });
      org.mockito.stubbing.Answer<Void> lostResponse =
          invocation -> {
            rpcFailed = true;
            throw transport;
          };
      if (decision.equals("COMMIT"))
        org.mockito.Mockito.doAnswer(lostResponse).when(transactions).commit(original.xid);
      else org.mockito.Mockito.doAnswer(lostResponse).when(transactions).rollback(original.xid);
    }

    /** 调用当前入口；执行入口的finally应清理本次begin上下文。 */
    private com.cloudmall.pay.domain.vo.PayTccExecutionVO call() {
      return entry.equals("execute")
          ? coordinator.execute(request)
          : coordinator.recover(request.payNo);
    }

    /** 如实保留已经发出的单次TC决议，不允许新begin或多发相反方向。 */
    private void verifyTcCalls() throws Exception {
      if (entry.equals("execute")) verify(transactions).begin();
      if (decision.equals("COMMIT")) verify(transactions).commit(original.xid);
      else verify(transactions).rollback(original.xid);
      verifyNoMoreInteractions(transactions);
    }
  }
}
