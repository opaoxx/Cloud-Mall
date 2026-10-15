package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** read关联观察的执行身份保护；只验证本地快照，不代表远端资金fencing。 */
class PayTccExecutionSnapshotTest {
  /** 合法身份漂移在非终态与终态均先拒绝，不借terminal早返回掩盖旧证据。 */
  @Test
  void changedExecutionIdentityRejectsSnapshotObservation() {
    for (String mutation : List.of("id", "xid", "user", "order", "amount"))
      for (String currentState : List.of("RUNNING", "COMMITTED"))
        for (String observation : List.of("COMMITTED", "ROLLEDBACK", "CONFLICT", "UNKNOWN")) {
          var fixture = new Fixture();
          var expected = execution("RUNNING", "NONE", "old-xid");
          var current =
              execution(
                  currentState, currentState.equals("RUNNING") ? "NONE" : "COMMIT", "old-xid");
          switch (mutation) {
            case "id" -> current.id = 2L;
            case "xid" -> current.xid = "new-xid";
            case "user" -> current.userId = 8L;
            case "order" -> current.orderNo = "another-order";
            case "amount" -> current.amount = new BigDecimal("10.01");
            default -> throw new IllegalArgumentException(mutation);
          }
          when(fixture.executions.selectForUpdate(expected.payNo)).thenReturn(current);
          var before = snapshot(current);
          assertConflict(() -> fixture.store.observeSnapshot(expected, observation, "old result"));
          assertEquals(before, snapshot(current));
          verify(fixture.executions).selectForUpdate(expected.payNo);
          fixture.assertNoOtherInteractions();
        }
  }

  /** 相同身份按当前可变进度合并；决议意图不覆盖实际相反终态，金额保持数值等价。 */
  @Test
  void snapshotObservationKeepsConcurrentProgress() {
    String[][] cases = {
      {"RUNNING", "NONE", "UNKNOWN", "UNKNOWN"},
      {"COMMIT_REQUESTED", "COMMIT", "UNKNOWN", "COMMIT_REQUESTED"},
      {"ROLLBACK_REQUESTED", "ROLLBACK", "UNKNOWN", "ROLLBACK_REQUESTED"},
      {"COMMITTED", "COMMIT", "UNKNOWN", "COMMITTED"},
      {"ROLLEDBACK", "ROLLBACK", "UNKNOWN", "ROLLEDBACK"},
      {"CONFLICT", "NONE", "UNKNOWN", "CONFLICT"},
      {"COMMIT_REQUESTED", "COMMIT", "ROLLEDBACK", "ROLLEDBACK"},
      {"ROLLBACK_REQUESTED", "ROLLBACK", "COMMITTED", "COMMITTED"},
      {"RUNNING", "NONE", "CONFLICT", "CONFLICT"}
    };
    for (String[] scenario : cases) {
      var fixture = new Fixture();
      var expected = execution("RUNNING", "NONE", "old-xid");
      var current = execution(scenario[0], scenario[1], "old-xid");
      current.amount = new BigDecimal("10.000");
      current.lastError = "peer diagnostic";
      current.recoveryAttempts = 9;
      current.recoveryOwner = "peer-worker";
      expected.recoveryAttempts = 1;
      expected.recoveryOwner = "old-worker";
      when(fixture.executions.selectForUpdate(expected.payNo)).thenReturn(current);
      var before = snapshot(current);
      if (PayTccExecutionStore.terminal(current.state)) {
        assertSame(current, fixture.store.observeSnapshot(expected, scenario[2], "late"));
      } else {
        var updated = execution(scenario[3], scenario[1], "old-xid");
        when(fixture.executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
        when(fixture.executions.selectByPayNo(expected.payNo)).thenReturn(updated);
        assertSame(updated, fixture.store.observeSnapshot(expected, scenario[2], "late"));
        verify(fixture.executions)
            .updateObservation(eq(expected.payNo), eq(scenario[3]), eq("late"), any());
        verify(fixture.executions).selectByPayNo(expected.payNo);
      }
      assertEquals(before, snapshot(current));
      verify(fixture.executions).selectForUpdate(expected.payNo);
      fixture.assertNoOtherInteractions();
    }
    for (String state : List.of("CLAIMED", "BEGIN_UNKNOWN"))
      for (String observation : List.of("UNKNOWN", "TRYING")) {
        var fixture = new Fixture();
        var expected = execution(state, "NONE", null);
        var current = execution(state, "NONE", null);
        var updated = execution("BEGIN_UNKNOWN", "NONE", null);
        when(fixture.executions.selectForUpdate(expected.payNo)).thenReturn(current);
        when(fixture.executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
        when(fixture.executions.selectByPayNo(expected.payNo)).thenReturn(updated);
        assertSame(updated, fixture.store.observeSnapshot(expected, observation, null));
        verify(fixture.executions).selectForUpdate(expected.payNo);
        verify(fixture.executions)
            .updateObservation(eq(expected.payNo), eq("BEGIN_UNKNOWN"), eq(null), any());
        verify(fixture.executions).selectByPayNo(expected.payNo);
        fixture.assertNoOtherInteractions();
      }
  }

  /** 无快照、坏域及原合并异常保持409；固定键非法仍由原域先拒绝。 */
  @Test
  void invalidSnapshotAndExistingTerminalRulesRemain() {
    for (String mutation :
        List.of(
            "null-expected",
            "missing-current",
            "bad-expected-amount",
            "bad-current-domain",
            "null-incoming",
            "unbound-terminal",
            "opposite-terminal",
            "zero-update",
            "expected-pay-alias",
            "expected-operation",
            "expected-ledger")) {
      var fixture = new Fixture();
      var expected = execution("RUNNING", "NONE", "old-xid");
      var current = execution("RUNNING", "NONE", "old-xid");
      String incoming = "COMMITTED";
      switch (mutation) {
        case "bad-expected-amount" -> expected.amount = null;
        case "bad-current-domain" -> current.operationKey = "bad";
        case "null-incoming" -> incoming = null;
        case "unbound-terminal" -> {
          expected.state = current.state = "CLAIMED";
          expected.xid = current.xid = null;
        }
        case "opposite-terminal" -> current.state = "ROLLEDBACK";
        case "expected-pay-alias" -> expected.payNo = "PAY-SNAPSHOT-1";
        case "expected-operation" -> expected.operationKey = "tcc-debit:another";
        case "expected-ledger" -> expected.ledgerKey = "another";
        default -> {}
      }
      when(fixture.executions.selectForUpdate(expected.payNo))
          .thenReturn(mutation.equals("missing-current") ? null : current);
      var input = mutation.equals("null-expected") ? null : expected;
      var observation = incoming;
      assertConflict(() -> fixture.store.observeSnapshot(input, observation, "diagnostic"));
      if (!mutation.equals("null-expected"))
        verify(fixture.executions).selectForUpdate(expected.payNo);
      if (mutation.equals("zero-update"))
        verify(fixture.executions)
            .updateObservation(eq(expected.payNo), eq("COMMITTED"), eq("diagnostic"), any());
      fixture.assertNoOtherInteractions();
    }
  }

  /** Coordinator四条read关联路径均传递原快照，不退回按支付号写入。 */
  @Test
  void coordinatorUsesSnapshotOnEveryReadBasedObservationPath() {
    for (String scenario : List.of("prebegin", "trying", "unknown", "biz", "runtime")) {
      var store = mock(PayTccExecutionStore.class);
      var bridge = mock(PayTccBridgeService.class);
      var receipts = mock(PayTccReceiptService.class);
      var evidence = mock(PayTccExecutionEvidence.class);
      var transactions = mock(PayTccTransactionClient.class);
      var expected =
          execution(
              scenario.equals("prebegin") ? "CLAIMED" : "RUNNING",
              "NONE",
              scenario.equals("prebegin") ? null : "old-xid");
      var updated =
          execution(
              scenario.equals("prebegin") ? "BEGIN_UNKNOWN" : "UNKNOWN", "NONE", expected.xid);
      var user = new PayTccDispatchVO();
      when(store.read(expected.payNo)).thenReturn(expected);
      when(store.observeSnapshot(same(expected), any(), any())).thenReturn(updated);
      String observation = "UNKNOWN";
      String error;
      switch (scenario) {
        case "prebegin" -> error = "begin XID 尚无持久证据";
        case "biz" -> {
          error = "REMOTE_UNAVAILABLE";
          when(bridge.recover(expected.payNo))
              .thenThrow(new BizException(error, "Bridge unavailable", 503));
        }
        case "runtime" -> {
          error = "IllegalStateException";
          when(bridge.recover(expected.payNo))
              .thenThrow(new IllegalStateException("Bridge unavailable"));
        }
        default -> {
          observation = scenario.equals("trying") ? "TRYING" : "UNKNOWN";
          error = scenario.equals("trying") ? null : "两端永久终态尚未齐全";
          when(bridge.recover(expected.payNo)).thenReturn(user);
          when(evidence.classify(expected, user)).thenReturn(observation);
        }
      }
      var coordinator =
          new PayTccCoordinatorServiceImpl(store, receipts, bridge, evidence, transactions);
      assertEquals(updated.state, coordinator.recover(expected.payNo).state);
      verify(store).read(expected.payNo);
      verify(store).observeSnapshot(same(expected), eq(observation), eq(error));
      verifyNoMoreInteractions(store);
      verifyNoInteractions(receipts, transactions);
      if (scenario.equals("prebegin")) verifyNoInteractions(bridge, evidence);
      else {
        verify(bridge).recover(expected.payNo);
        if (List.of("trying", "unknown").contains(scenario))
          verify(evidence).classify(expected, user);
        else verifyNoInteractions(evidence);
        verifyNoMoreInteractions(bridge, evidence);
      }
    }
  }

  /** 有效不可变执行载荷，各测试独立实例，不共享可变状态。 */
  private PayTccExecutionPO execution(String state, String decision, String xid) {
    var execution = new PayTccExecutionPO();
    execution.id = 1L;
    execution.payNo = "pay-snapshot-1";
    execution.xid = xid;
    execution.userId = 7L;
    execution.orderNo = "order-snapshot-1";
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:" + execution.payNo;
    execution.ledgerKey = execution.payNo;
    execution.state = state;
    execution.decision = decision;
    return execution;
  }

  /** 检查不改写传入或锁定的原对象。 */
  private String snapshot(PayTccExecutionPO execution) {
    return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(execution));
  }

  /** 冲突保持既有业务码和HTTP状态。 */
  private void assertConflict(org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(BizException.class, action);
    assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
    assertEquals(409, failure.getHttpStatus());
  }

  /** 显式区分唯一执行锁与未参与本观察门的其它持久资源。 */
  private static final class Fixture {
    /** 执行Mapper替身。 */
    private final PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);

    /** 原PayMapper，快照写回不新增来源查询或锁。 */
    private final PayRecordMapper payments = mock(PayRecordMapper.class);

    /** 派发资源替身。 */
    private final PayTccDispatchMapper dispatches = mock(PayTccDispatchMapper.class);

    /** receipt资源替身。 */
    private final PayTccReceiptMapper receipts = mock(PayTccReceiptMapper.class);

    /** 取消资源替身。 */
    private final PayTccCancelMapper cancels = mock(PayTccCancelMapper.class);

    /** 实际被测Store。 */
    private final PayTccExecutionStore store =
        new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);

    /** 已核过的调用之外不允许SQL或其它资源交互。 */
    private void assertNoOtherInteractions() {
      verifyNoMoreInteractions(executions);
      verifyNoInteractions(payments, dispatches, receipts, cancels);
    }
  }
}
