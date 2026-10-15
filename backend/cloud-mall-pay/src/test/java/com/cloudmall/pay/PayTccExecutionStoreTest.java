package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay coordinator execution store 的单向决议和一次性认领测试。 */
class PayTccExecutionStoreTest {
  /** Execution Mapper。 */
  private PayTccExecutionMapper executions;

  /** Pay record Mapper。 */
  private PayRecordMapper payments;

  /** 旧 dispatch Mapper。 */
  private PayTccDispatchMapper dispatches;

  /** 旧 receipt Mapper。 */
  private PayTccReceiptMapper receipts;

  /** 主动取消 gate Mapper。 */
  private PayTccCancelMapper cancels;

  /** 被测 Store。 */
  private PayTccExecutionStore store;

  /** 准备四个必需 Mapper。 */
  @BeforeEach
  void setUp() {
    executions = mock(PayTccExecutionMapper.class);
    payments = mock(PayRecordMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    receipts = mock(PayTccReceiptMapper.class);
    cancels = mock(PayTccCancelMapper.class);
    when(cancels.selectByPayNo(any())).thenReturn(null);
    store = new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);
  }

  /** 相反 COMMIT/ROLLBACK 决议冲突，且不写决议 SQL。 */
  @Test
  void oppositeDecisionIsRejectedWithoutSqlUpdate() {
    PayTccExecutionPO execution = execution("COMMIT_REQUESTED", "COMMIT", "xid-store-1");
    when(executions.selectForUpdate(execution.payNo)).thenReturn(execution);

    assertThrows(
        BizException.class, () -> store.decide(execution.payNo, execution.xid, "ROLLBACK"));

    verify(executions, never()).setDecision(any(), any(), any(), any(), any());
  }

  /** 相同决议重复请求直接返回原事实，不重复 CAS。 */
  @Test
  void sameDecisionIsIdempotent() {
    PayTccExecutionPO execution = execution("COMMIT_REQUESTED", "COMMIT", "xid-store-1");
    when(executions.selectForUpdate(execution.payNo)).thenReturn(execution);

    store.decide(execution.payNo, execution.xid, "COMMIT");

    verify(executions, never()).setDecision(any(), any(), any(), any(), any());
  }

  /** 已提交终态不能被 UNKNOWN 观察结果倒退。 */
  @Test
  void terminalUnknownDoesNotRegress() {
    PayTccExecutionPO execution = execution("COMMITTED", "COMMIT", "xid-store-1");
    when(executions.selectForUpdate(execution.payNo)).thenReturn(execution);

    store.observe(execution.payNo, "UNKNOWN", "late response");

    verify(executions, never()).updateObservation(any(), any(), any(), any());
  }

  /** 相反终态观察结果明确冲突。 */
  @Test
  void oppositeTerminalObservationIsRejected() {
    PayTccExecutionPO execution = execution("COMMITTED", "COMMIT", "xid-store-1");
    when(executions.selectForUpdate(execution.payNo)).thenReturn(execution);

    assertThrows(
        BizException.class,
        () -> store.observe(execution.payNo, "ROLLEDBACK", "opposite terminal"));
    verify(executions, never()).updateObservation(any(), any(), any(), any());
  }

  /** 已绑定相同 XID 的重复绑定幂等，不允许换绑其他 XID。 */
  @Test
  void bindSameXidIsIdempotentButDifferentXidConflicts() {
    PayTccExecutionPO execution = execution("RUNNING", "NONE", "xid-store-1");
    when(executions.selectForUpdate(execution.payNo)).thenReturn(execution);

    store.bindXid(execution.payNo, execution.xid);
    assertThrows(BizException.class, () -> store.bindXid(execution.payNo, "xid-other"));

    verify(executions, never()).bindXid(any(), any(), any());
  }

  /** LEGACY 支付不能创建 coordinator execution。 */
  @Test
  void legacyPaymentDoesNotClaimExecution() {
    PayTccDispatchDTO request = request();
    PayRecordPO payment = payment("LEGACY");
    when(payments.selectForUpdate(request.payNo)).thenReturn(List.of(payment));

    assertThrows(BizException.class, () -> store.claim(request));
    verify(executions, never()).insert(any());
  }

  /** 已有旧 dispatch 或 receipt 时不能新建第二个 execution。 */
  @Test
  void oldDispatchOrReceiptBlocksNewExecution() {
    PayTccDispatchDTO request = request();
    when(payments.selectForUpdate(request.payNo)).thenReturn(List.of(payment("TCC")));
    when(executions.selectForUpdate(request.payNo)).thenReturn(null);
    when(dispatches.selectByPayNo(request.payNo))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccDispatchPO());

    assertThrows(BizException.class, () -> store.claim(request));
    verify(executions, never()).insert(any());

    when(dispatches.selectByPayNo(request.payNo)).thenReturn(null);
    when(receipts.selectByPayNo(request.payNo)).thenReturn(new PayTccReceiptPO());
    assertThrows(BizException.class, () -> store.claim(request));
    verify(executions, never()).insert(any());
  }

  /** 已有 execution 的载荷不一致时只拒绝重放，不覆盖原事实。 */
  @Test
  void replayPayloadMismatchDoesNotInsertOrOverwrite() {
    PayTccDispatchDTO request = request();
    PayTccExecutionPO existing = execution("CLAIMED", "NONE", null);
    existing.amount = new BigDecimal("11.00");
    when(payments.selectForUpdate(request.payNo)).thenReturn(List.of(payment("TCC")));
    when(executions.selectForUpdate(request.payNo)).thenReturn(existing);

    assertThrows(BizException.class, () -> store.claim(request));
    verify(executions, never()).insert(any());
  }

  /** 未真正更新唯一记录必须冲突，不用readback伪造观察保存成功。 */
  @Test
  void nonOneObservationWriteRejectsWithoutReadback() {
    for (int affectedRows : List.of(0, -1, 2)) {
      org.mockito.Mockito.reset(executions);
      var current = execution("COMMIT_REQUESTED", "COMMIT", "xid-store-1");
      when(executions.selectForUpdate(current.payNo)).thenReturn(current);
      when(executions.updateObservation(any(), any(), any(), any())).thenReturn(affectedRows);
      var failure =
          assertThrows(
              BizException.class, () -> store.observe(current.payNo, "UNKNOWN", "new observation"));
      org.junit.jupiter.api.Assertions.assertEquals(
          "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      verify(executions, never()).selectByPayNo(any());
      org.junit.jupiter.api.Assertions.assertEquals("COMMIT_REQUESTED", current.state);
      org.junit.jupiter.api.Assertions.assertEquals("COMMIT", current.decision);
    }
  }

  /** 恰好一行时保原目标计算、Unicode前缀与成功readback返回的新记录。 */
  @Test
  void oneObservationWriteKeepsTargetAndFreshReadback() {
    String[][] examples = {
      {"CLAIMED", "NONE", null, "UNKNOWN", "BEGIN_UNKNOWN"},
      {"RUNNING", "NONE", "xid-store-1", "UNKNOWN", "UNKNOWN"},
      {"COMMIT_REQUESTED", "COMMIT", "xid-store-1", "UNKNOWN", "COMMIT_REQUESTED"},
      {"ROLLBACK_REQUESTED", "ROLLBACK", "xid-store-1", "UNKNOWN", "ROLLBACK_REQUESTED"},
      {"RUNNING", "NONE", "xid-store-1", "COMMITTED", "COMMITTED"},
      {"RUNNING", "NONE", "xid-store-1", "ROLLEDBACK", "ROLLEDBACK"},
      {"RUNNING", "NONE", "xid-store-1", "CONFLICT", "CONFLICT"}
    };
    for (String[] example : examples) {
      org.mockito.Mockito.reset(executions);
      var current = execution(example[0], example[1], example[2]);
      var reloaded = execution(example[4], example[1], example[2]);
      when(executions.selectForUpdate(current.payNo)).thenReturn(current);
      when(executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
      when(executions.selectByPayNo(current.payNo)).thenReturn(reloaded);
      org.junit.jupiter.api.Assertions.assertSame(
          reloaded, store.observe(current.payNo, example[3], "😀".repeat(513)));
      verify(executions)
          .updateObservation(
              org.mockito.ArgumentMatchers.eq(current.payNo),
              org.mockito.ArgumentMatchers.eq(example[4]),
              org.mockito.ArgumentMatchers.eq("😀".repeat(512)),
              any());
      verify(executions).selectByPayNo(current.payNo);
      verify(executions, never()).bindXid(any(), any(), any());
      verify(executions, never()).setDecision(any(), any(), any(), any(), any());
      org.junit.jupiter.api.Assertions.assertEquals(example[0], current.state);
    }
  }

  /** DAO异常仍原样传播且不readback，不能被改写为普通观察成功。 */
  @Test
  void observationDaoFailurePropagatesWithoutReadback() {
    var current = execution("RUNNING", "NONE", "xid-store-1");
    when(executions.selectForUpdate(current.payNo)).thenReturn(current);
    var failure = new IllegalStateException("owned observation write fault");
    when(executions.updateObservation(any(), any(), any(), any())).thenThrow(failure);
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> store.observe(current.payNo, "UNKNOWN", "new observation")));
    verify(executions, never()).selectByPayNo(any());
  }

  /** 读与锁定返回对象的错误/NULL原支付号必须在早返回或写入前拒绝。 */
  @Test
  void lookupRowIdentityIsRequiredBeforeReadOrLockedActions() {
    for (String mutation : List.of("foreign", "case", "space", "null"))
      for (String action : List.of("read", "bind", "decide", "observe")) {
        org.mockito.Mockito.reset(executions);
        var row =
            execution(
                action.equals("bind") ? "CLAIMED" : "RUNNING",
                "NONE",
                action.equals("bind") ? null : "xid-store-1");
        row.payNo =
            switch (mutation) {
              case "foreign" -> "other-pay";
              case "case" -> "PAY-STORE-1";
              case "space" -> "pay-store-1 ";
              default -> null;
            };
        when(executions.selectByPayNo("pay-store-1")).thenReturn(row);
        when(executions.selectForUpdate("pay-store-1")).thenReturn(row);
        when(executions.bindXid(any(), any(), any())).thenReturn(1);
        when(executions.setDecision(any(), any(), any(), any(), any())).thenReturn(1);
        when(executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
        var before =
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row));
        var failure =
            assertThrows(
                BizException.class,
                () -> {
                  switch (action) {
                    case "read" -> store.read("pay-store-1");
                    case "bind" -> store.bindXid("pay-store-1", "xid-store-1");
                    case "decide" -> store.decide("pay-store-1", "xid-store-1", "COMMIT");
                    default -> store.observe("pay-store-1", "UNKNOWN", "lookup mismatch");
                  }
                });
        org.junit.jupiter.api.Assertions.assertEquals(
            "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
        org.junit.jupiter.api.Assertions.assertEquals(
            before,
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row)));
        verify(executions, never()).bindXid(any(), any(), any());
        verify(executions, never()).setDecision(any(), any(), any(), any(), any());
        verify(executions, never()).updateObservation(any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(payments, dispatches, receipts, cancels);
      }
  }

  /** 精确行只读返回保持对象身份，缺行404/锁内409仍不写。 */
  @Test
  void canonicalAndMissingLookupSemanticsRemain() {
    for (String state : List.of("RUNNING", "COMMITTED", "ROLLEDBACK", "CONFLICT")) {
      var row = execution(state, "COMMIT", "xid-store-1");
      when(executions.selectByPayNo(row.payNo)).thenReturn(row);
      org.junit.jupiter.api.Assertions.assertSame(row, store.read(row.payNo));
    }
    when(executions.selectByPayNo("absent-pay")).thenReturn(null);
    var missing = assertThrows(BizException.class, () -> store.read("absent-pay"));
    org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_EXECUTION_NOT_FOUND", missing.getCode());
    org.junit.jupiter.api.Assertions.assertEquals(404, missing.getHttpStatus());
    for (String action : List.of("bind", "decide", "observe")) {
      var failure =
          assertThrows(
              BizException.class,
              () -> {
                if (action.equals("bind")) store.bindXid("absent-pay", "xid-store-1");
                else if (action.equals("decide"))
                  store.decide("absent-pay", "xid-store-1", "COMMIT");
                else store.observe("absent-pay", "UNKNOWN", "missing");
              });
      org.junit.jupiter.api.Assertions.assertEquals(
          "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
    }
    verify(executions, never()).bindXid(any(), any(), any());
    verify(executions, never()).setDecision(any(), any(), any(), any(), any());
    verify(executions, never()).updateObservation(any(), any(), any(), any());
  }

  /** 真实Coordinator和Store拒绝异源终态，不能返回其支付号或触发桥/TC/观察。 */
  @Test
  void coordinatorRejectsAlienTerminalBeforeAnyRecoverySideEffect() {
    for (String terminal : List.of("COMMITTED", "ROLLEDBACK", "CONFLICT")) {
      var row = execution(terminal, "COMMIT", "xid-store-1");
      row.payNo = "PAY-STORE-1";
      when(executions.selectByPayNo("pay-store-1")).thenReturn(row);
      var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
      var receiptService = mock(com.cloudmall.pay.service.PayTccReceiptService.class);
      var evidence = mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class);
      var transactions = mock(com.cloudmall.pay.service.PayTccTransactionClient.class);
      var coordinator =
          new com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl(
              store, receiptService, bridge, evidence, transactions);
      var failure = assertThrows(BizException.class, () -> coordinator.recover("pay-store-1"));
      org.junit.jupiter.api.Assertions.assertEquals(
          "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      verify(executions, never()).selectForUpdate(any());
      verify(executions, never()).updateObservation(any(), any(), any(), any());
      org.mockito.Mockito.verifyNoInteractions(bridge, receiptService, evidence, transactions);
    }
  }

  /** 非NULL坏XID在活动/终态和所有决议中均拒绝，read只有一次原查询。 */
  @Test
  void invalidBoundXidReadRejectsBeforeAnyRecoveryAction() {
    for (String xid : List.of("", "   ", "\t\n", "x".repeat(129)))
      for (String state :
          List.of(
              "RUNNING",
              "COMMIT_REQUESTED",
              "ROLLBACK_REQUESTED",
              "COMMITTED",
              "ROLLEDBACK",
              "CONFLICT"))
        for (String decision : List.of("NONE", "COMMIT", "ROLLBACK")) {
          org.mockito.Mockito.reset(executions);
          var row = execution(state, decision, xid);
          when(executions.selectByPayNo(row.payNo)).thenReturn(row);
          var before = snapshotExecution(row);
          var failure = assertThrows(BizException.class, () -> store.read(row.payNo));
          org.junit.jupiter.api.Assertions.assertEquals(
              "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
          org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
          org.junit.jupiter.api.Assertions.assertEquals(before, snapshotExecution(row));
          verify(executions).selectByPayNo(row.payNo);
          org.mockito.Mockito.verifyNoMoreInteractions(executions);
          org.mockito.Mockito.verifyNoInteractions(payments, dispatches, receipts, cancels);
        }
  }

  /** 真实Store/Coordinator拒绝坏已绑定XID，先于桥503、观察SQL和TC同向重试。 */
  @Test
  void coordinatorRejectsBadBoundXidBeforeBridgeEvenWhenUnavailable() {
    for (String xid : List.of("", "   ", "\t\n", "x".repeat(129)))
      for (String decision : List.of("COMMIT", "ROLLBACK"))
        for (boolean terminal : List.of(false, true)) {
          org.mockito.Mockito.reset(executions);
          String state =
              terminal
                  ? decision.equals("COMMIT") ? "COMMITTED" : "ROLLEDBACK"
                  : decision + "_REQUESTED";
          var row = execution(state, decision, xid);
          when(executions.selectByPayNo(row.payNo)).thenReturn(row);
          var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
          var receiptService = mock(com.cloudmall.pay.service.PayTccReceiptService.class);
          var evidence = mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class);
          var transactions = mock(com.cloudmall.pay.service.PayTccTransactionClient.class);
          when(bridge.recover(row.payNo))
              .thenThrow(new BizException("REMOTE_UNAVAILABLE", "owned Bridge 503", 503));
          var coordinator =
              new com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl(
                  store, receiptService, bridge, evidence, transactions);
          var before = snapshotExecution(row);
          var failure = assertThrows(BizException.class, () -> coordinator.recover(row.payNo));
          org.junit.jupiter.api.Assertions.assertEquals(
              "PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
          org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
          org.junit.jupiter.api.Assertions.assertEquals(before, snapshotExecution(row));
          verify(executions).selectByPayNo(row.payNo);
          org.mockito.Mockito.verifyNoMoreInteractions(executions);
          org.mockito.Mockito.verifyNoInteractions(
              bridge,
              receiptService,
              evidence,
              transactions,
              payments,
              dispatches,
              receipts,
              cancels);
        }
  }

  /** NULL及非空白原文本保持；NULL恢复仍只观察BEGIN_UNKNOWN，不begin/桥/TC。 */
  @Test
  void validReadXidTextAndNullPrebeginSemanticsRemain() {
    for (String xid :
        java.util.Arrays.asList(null, "x", " xid-with-space ", "x".repeat(128), "事务:one"))
      for (String state : List.of("CLAIMED", "RUNNING", "COMMITTED", "ROLLEDBACK", "CONFLICT")) {
        org.mockito.Mockito.reset(executions);
        var row = execution(state, "NONE", xid);
        when(executions.selectByPayNo(row.payNo)).thenReturn(row);
        var before = snapshotExecution(row);
        org.junit.jupiter.api.Assertions.assertSame(row, store.read(row.payNo));
        org.junit.jupiter.api.Assertions.assertEquals(xid, row.xid);
        org.junit.jupiter.api.Assertions.assertEquals(before, snapshotExecution(row));
        verify(executions).selectByPayNo(row.payNo);
        org.mockito.Mockito.verifyNoMoreInteractions(executions);
      }
    org.mockito.Mockito.reset(executions);
    var row = execution("CLAIMED", "NONE", null);
    var observed = execution("BEGIN_UNKNOWN", "NONE", null);
    when(executions.selectByPayNo(row.payNo)).thenReturn(row, observed);
    when(executions.selectForUpdate(row.payNo)).thenReturn(row);
    when(executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    var receiptService = mock(com.cloudmall.pay.service.PayTccReceiptService.class);
    var evidence = mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class);
    var transactions = mock(com.cloudmall.pay.service.PayTccTransactionClient.class);
    var coordinator =
        new com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl(
            store, receiptService, bridge, evidence, transactions);
    var before = snapshotExecution(row);
    var recovered = coordinator.recover(row.payNo);
    org.junit.jupiter.api.Assertions.assertNull(recovered.xid);
    org.junit.jupiter.api.Assertions.assertEquals("BEGIN_UNKNOWN", recovered.state);
    org.junit.jupiter.api.Assertions.assertEquals("NONE", recovered.decision);
    org.junit.jupiter.api.Assertions.assertEquals(before, snapshotExecution(row));
    verify(executions)
        .updateObservation(
            org.mockito.ArgumentMatchers.eq(row.payNo),
                org.mockito.ArgumentMatchers.eq("BEGIN_UNKNOWN"),
            org.mockito.ArgumentMatchers.eq("begin XID 尚无持久证据"), any());
    org.mockito.Mockito.verifyNoInteractions(
        bridge, receiptService, evidence, transactions, payments, dispatches, receipts, cancels);
  }

  /** 保留所有公开字段文本快照，验证读路径不改写任何载荷或诊断。 */
  private String snapshotExecution(PayTccExecutionPO execution) {
    return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(execution));
  }

  private PayTccDispatchDTO request() {
    PayTccDispatchDTO request = new PayTccDispatchDTO();
    request.payNo = "pay-store-1";
    request.userId = 7L;
    request.orderNo = "order-store-1";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private PayRecordPO payment(String debitMode) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-store-1";
    payment.userId = 7L;
    payment.orderNo = "order-store-1";
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = debitMode;
    payment.status = "PENDING";
    return payment;
  }

  private PayTccExecutionPO execution(String state, String decision, String xid) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay-store-1";
    execution.userId = 7L;
    execution.orderNo = "order-store-1";
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:pay-store-1";
    execution.ledgerKey = "pay-store-1";
    execution.state = state;
    execution.decision = decision;
    execution.xid = xid;
    return execution;
  }
}
