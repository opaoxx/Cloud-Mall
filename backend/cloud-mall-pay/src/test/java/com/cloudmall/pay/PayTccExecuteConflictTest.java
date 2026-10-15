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

/** 首次execute明确冲突的传播与普通失败回归；Store/TC仅为编排替身。 */
class PayTccExecuteConflictTest {
  /** 六阶段的明确冲突原对象传播，冲突之后不再诊断、决议或恢复读取。 */
  @Test
  void explicitConflictsExitBeforeDiagnosticsAndDecision() throws Exception {
    for (String phase : List.of("begin", "bind", "receipt", "bridge", "evidence", "decide"))
      for (String code :
          List.of(
              "PAY_TCC_EXECUTION_CONFLICT", "USER_TCC_RECEIVE_CONFLICT", "owned-CONFLICT-code")) {
        var failure = new BizException(code, "owned conflict", 422);
        var fixture = new Fixture(phase, failure);
        assertSame(
            failure,
            assertThrows(BizException.class, () -> fixture.coordinator.execute(fixture.request)));
        assertEquals(422, failure.getHttpStatus());
        assertNull(RootContext.getXID());
        verify(fixture.store).claimOwned(fixture.request);
        verify(fixture.transactions).begin();
        if (!phase.equals("begin"))
          verify(fixture.store).bindOwnedXid(same(fixture.execution), eq("unit-execute-xid"));
        if (List.of("receipt", "bridge", "evidence", "decide").contains(phase))
          verify(fixture.receipts).prepare(fixture.request);
        if (List.of("bridge", "evidence", "decide").contains(phase))
          verify(fixture.bridge).dispatch(fixture.request);
        if (List.of("evidence", "decide").contains(phase)) {
          verify(fixture.store).read(fixture.request.payNo);
          verify(fixture.evidence).classify(fixture.execution, fixture.user);
        }
        if (phase.equals("decide"))
          verify(fixture.store).decideOwned(fixture.execution, "unit-execute-xid", "COMMIT");
        verifyNoMoreInteractions(
            fixture.store,
            fixture.receipts,
            fixture.bridge,
            fixture.evidence,
            fixture.transactions);
      }
  }

  /** 其它业务码、NULL码及普通异常保原流程；正常Try提交，明确非TRYING仍回滚。 */
  @Test
  void ordinaryFailuresAndSuccessfulTryKeepDecisionFlow() throws Exception {
    for (String phase : List.of("begin", "bind", "receipt", "bridge", "evidence", "decide"))
      for (Exception failure :
          List.of(
              new IllegalStateException("ordinary unavailable"),
              new BizException("OTHER_FAILURE", "ordinary business", 409),
              new BizException(null, "missing business code", 503),
              new BizException("lower_conflict", "case-sensitive code", 409)))
        assertOrdinaryFailure(phase, failure);
    assertOrdinaryFailure("begin", new Exception("checked begin failure"));
    for (boolean declined : List.of(false, true)) {
      var fixture = new Fixture("none", null);
      fixture.declinedReceipt = declined;
      var result = fixture.coordinator.execute(fixture.request);
      String decision = declined ? "ROLLBACK" : "COMMIT";
      assertEquals(decision, result.decision);
      assertEquals(decision + "_REQUESTED", result.state);
      verify(fixture.store).decideOwned(fixture.execution, "unit-execute-xid", decision);
      verify(fixture.transactions).begin();
      if (declined) verify(fixture.transactions).rollback("unit-execute-xid");
      else verify(fixture.transactions).commit("unit-execute-xid");
      verifyNoMoreInteractions(fixture.transactions);
      verify(fixture.store, never()).observe(any(), any(), any());
      assertNull(RootContext.getXID());
    }
  }

  /** 普通begin/bind/decide失败也持原claim/bound快照末次核对，不重读payNo。 */
  @Test
  void ordinaryFailuresKeepOwnedSnapshotForFinalObservation() throws Exception {
    for (String phase : List.of("begin", "bind", "decide")) {
      Exception failure =
          phase.equals("begin")
              ? new Exception("begin response lost")
              : new IllegalStateException("ordinary failure");
      var fixture = new Fixture(phase, failure);
      var result = fixture.coordinator.execute(fixture.request);
      assertEquals(phase.equals("decide") ? "UNKNOWN" : "BEGIN_UNKNOWN", result.state);
      verify(fixture.store).verifyDecisionSnapshot(same(fixture.execution));
      verify(fixture.store, times(phase.equals("decide") ? 1 : 0)).read(fixture.request.payNo);
      verify(fixture.transactions).begin();
      verifyNoMoreInteractions(fixture.transactions);
      assertNull(RootContext.getXID());
    }
  }

  /** 调用者上下文保持；已有终态重放与claim冲突不获得新的begin或Try权。 */
  @Test
  void ambientContextsAndReplayKeepOriginalOwnership() throws Exception {
    var ambient = new Fixture("none", null);
    RootContext.bind("caller-xid");
    try {
      var failure =
          assertThrows(BizException.class, () -> ambient.coordinator.execute(ambient.request));
      assertEquals("PAY_TCC_CONTEXT_INVALID", failure.getCode());
      assertEquals("caller-xid", RootContext.getXID());
      ambient.verifyNoCalls();
    } finally {
      RootContext.unbind();
    }
    var local = new Fixture("none", null);
    var previousTransaction =
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive();
    org.springframework.transaction.support.TransactionSynchronizationManager
        .setActualTransactionActive(true);
    try {
      assertThrows(BizException.class, () -> local.coordinator.execute(local.request));
      local.verifyNoCalls();
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(previousTransaction);
    }
    var replay = new Fixture("none", null);
    when(replay.store.claimOwned(replay.request)).thenReturn(null);
    replay.execution.xid = "original-xid";
    replay.execution.state = "COMMITTED";
    replay.execution.decision = "COMMIT";
    assertEquals("COMMITTED", replay.coordinator.execute(replay.request).state);
    verify(replay.store).claimOwned(replay.request);
    verify(replay.store).read(replay.request.payNo);
    verifyNoMoreInteractions(replay.store);
    verifyNoInteractions(replay.receipts, replay.bridge, replay.evidence, replay.transactions);
    var rejected = new Fixture("none", null);
    var conflict = new BizException("PAY_TCC_EXECUTION_CONFLICT", "claim conflict", 409);
    when(rejected.store.claimOwned(rejected.request)).thenThrow(conflict);
    assertSame(
        conflict,
        assertThrows(BizException.class, () -> rejected.coordinator.execute(rejected.request)));
    verify(rejected.store).claimOwned(rejected.request);
    verifyNoMoreInteractions(rejected.store);
    verifyNoInteractions(
        rejected.receipts, rejected.bridge, rejected.evidence, rejected.transactions);
    assertNull(RootContext.getXID());
  }

  /** 非冲突故障只验证编排选择，不把替身状态当作真实资金结果。 */
  private void assertOrdinaryFailure(String phase, Exception failure) throws Exception {
    var fixture = new Fixture(phase, failure);
    var result = fixture.coordinator.execute(fixture.request);
    String decision =
        List.of("receipt", "bridge", "evidence").contains(phase) ? "ROLLBACK" : "NONE";
    assertEquals(decision, result.decision);
    assertEquals(
        List.of("begin", "bind").contains(phase)
            ? "BEGIN_UNKNOWN"
            : decision.equals("NONE") ? "UNKNOWN" : "ROLLBACK_REQUESTED",
        result.state);
    verify(fixture.store)
        .observeSnapshot(
            same(fixture.execution), eq("UNKNOWN"), eq(failure.getClass().getSimpleName()));
    verify(fixture.transactions).begin();
    if (decision.equals("ROLLBACK")) {
      verify(fixture.store).decideOwned(fixture.execution, "unit-execute-xid", "ROLLBACK");
      verify(fixture.transactions).rollback("unit-execute-xid");
    } else if (phase.equals("decide"))
      verify(fixture.store).decideOwned(fixture.execution, "unit-execute-xid", "COMMIT");
    else verify(fixture.store, never()).decideOwned(any(), any(), any());
    verifyNoMoreInteractions(fixture.transactions);
    assertNull(RootContext.getXID());
  }

  /** 每例独立编排依赖；故障仅抛一次，避免末次恢复故障掩盖先前副作用。 */
  private static final class Fixture {
    /** 实例内故障阶段。 */
    private final String phase;

    /** 受控故障对象。 */
    private final Exception failure;

    /** 一次故障注入标记。 */
    private boolean raised;

    /** receipt明确非TRYING的健康回滚控制。 */
    private boolean declinedReceipt;

    /** 执行事实Store替身。 */
    private final PayTccExecutionStore store = mock(PayTccExecutionStore.class);

    /** receipt Try替身。 */
    private final PayTccReceiptService receipts = mock(PayTccReceiptService.class);

    /** User桥替身。 */
    private final PayTccBridgeService bridge = mock(PayTccBridgeService.class);

    /** 证据核对替身。 */
    private final PayTccExecutionEvidence evidence = mock(PayTccExecutionEvidence.class);

    /** TC窄接口替身。 */
    private final PayTccTransactionClient transactions = mock(PayTccTransactionClient.class);

    /** 原请求。 */
    private final PayTccDispatchDTO request = new PayTccDispatchDTO();

    /** 编排返回的原执行对象，不作为Store持久化校验测试。 */
    private final PayTccExecutionPO execution = new PayTccExecutionPO();

    /** Try镜像替身。 */
    private final PayTccDispatchVO user = new PayTccDispatchVO();

    /** 实际被测Coordinator。 */
    private final PayTccCoordinatorServiceImpl coordinator =
        new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions);

    /** 配置受控流程，模拟Store返回进度而不复制其SQL或身份保护。 */
    private Fixture(String phase, Exception failure) throws Exception {
      this.phase = phase;
      this.failure = failure;
      request.payNo = "pay-execute-conflict";
      request.userId = 7L;
      request.orderNo = "order-execute-conflict";
      request.amount = new BigDecimal("10.00");
      execution.id = 1L;
      execution.payNo = request.payNo;
      execution.userId = request.userId;
      execution.orderNo = request.orderNo;
      execution.amount = request.amount;
      execution.operationKey = "tcc-debit:" + request.payNo;
      execution.ledgerKey = request.payNo;
      execution.state = "CLAIMED";
      execution.decision = "NONE";
      when(store.claimOwned(request)).thenReturn(execution);
      when(store.verifyDecisionSnapshot(execution)).thenReturn(execution);
      when(store.read(request.payNo)).thenReturn(execution);
      when(transactions.begin())
          .thenAnswer(
              invocation -> {
                RootContext.bind("unit-execute-xid");
                failAt("begin");
                return "unit-execute-xid";
              });
      when(store.bindOwnedXid(any(), any()))
          .thenAnswer(
              invocation -> {
                failAt("bind");
                execution.xid = invocation.getArgument(1);
                execution.state = "RUNNING";
                return execution;
              });
      when(receipts.prepare(request))
          .thenAnswer(
              invocation -> {
                failAt("receipt");
                var receipt = new PayTccReceiptVO();
                receipt.state = declinedReceipt ? "DECLINED" : "TRYING";
                return receipt;
              });
      when(bridge.dispatch(request))
          .thenAnswer(
              invocation -> {
                failAt("bridge");
                return user;
              });
      when(bridge.recover(request.payNo)).thenReturn(user);
      when(evidence.classify(any(), any()))
          .thenAnswer(
              invocation -> {
                failAt("evidence");
                return "TRYING";
              });
      when(store.decideOwned(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                failAt("decide");
                execution.decision = invocation.getArgument(2);
                execution.state = execution.decision + "_REQUESTED";
                return execution;
              });
      org.mockito.stubbing.Answer<PayTccExecutionPO> observation =
          invocation -> {
            if ("UNKNOWN".equals(invocation.getArgument(1)) && invocation.getArgument(2) != null)
              execution.state = execution.xid == null ? "BEGIN_UNKNOWN" : "UNKNOWN";
            return execution;
          };
      when(store.observeSnapshot(any(), any(), any())).thenAnswer(observation);
      when(store.observeDecidedSnapshot(any(), any(), any())).thenAnswer(observation);
    }

    /** 故障后不再注入第二错误，便于检测不应发生的后续动作。 */
    private void failAt(String point) throws Exception {
      if (failure != null && phase.equals(point) && !raised) {
        raised = true;
        throw failure;
      }
    }

    /** 上下文入口拒绝不允许触及任何依赖。 */
    private void verifyNoCalls() {
      verifyNoInteractions(store, receipts, bridge, evidence, transactions);
    }
  }
}
