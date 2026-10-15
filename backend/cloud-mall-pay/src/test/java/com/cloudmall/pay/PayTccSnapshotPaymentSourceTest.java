package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 快照观察的原Pay锁内来源核对，不代表观察返回后的TC/RPC窗口已被锁定。 */
class PayTccSnapshotPaymentSourceTest {
  /** execution保持合法匹配时，缺失、重复或不匹配来源仍必须拒绝三种观察。 */
  @Test
  void invalidLockedPaymentSourcesRejectMatchingSnapshots() {
    for (String mutation :
        List.of(
            "missing",
            "duplicate",
            "null-row",
            "pay-null",
            "pay-other",
            "pay-case",
            "pay-space",
            "mode-null",
            "mode-legacy",
            "mode-case",
            "mode-space",
            "user-null",
            "user-other",
            "order-null",
            "order-other",
            "order-case",
            "order-space",
            "amount-null",
            "amount-other"))
      for (String observation : List.of("COMMITTED", "ROLLEDBACK", "UNKNOWN")) {
        var fixture = new Fixture();
        switch (mutation) {
          case "pay-null" -> fixture.source.payNo = null;
          case "pay-other" -> fixture.source.payNo = "another-pay";
          case "pay-case" -> fixture.source.payNo = "PAY-SOURCE-LOCK-1";
          case "pay-space" -> fixture.source.payNo += " ";
          case "mode-null" -> fixture.source.debitMode = null;
          case "mode-legacy" -> fixture.source.debitMode = "LEGACY";
          case "mode-case" -> fixture.source.debitMode = "tcc";
          case "mode-space" -> fixture.source.debitMode = "TCC ";
          case "user-null" -> fixture.source.userId = null;
          case "user-other" -> fixture.source.userId = 8L;
          case "order-null" -> fixture.source.orderNo = null;
          case "order-other" -> fixture.source.orderNo = "another-order";
          case "order-case" -> fixture.source.orderNo = "ORDER-SOURCE-LOCK-1";
          case "order-space" -> fixture.source.orderNo += " ";
          case "amount-null" -> fixture.source.amount = null;
          case "amount-other" -> fixture.source.amount = new BigDecimal("10.01");
          default -> {}
        }
        List<PayRecordPO> sources =
            switch (mutation) {
              case "missing" -> List.of();
              case "duplicate" -> List.of(fixture.source, payment());
              case "null-row" -> java.util.Collections.singletonList(null);
              default -> List.of(fixture.source);
            };
        when(fixture.payments.selectForUpdate(fixture.current.payNo)).thenReturn(sources);
        var expected = execution();
        var before = snapshot(fixture.current, fixture.source, expected);
        assertConflict(() -> fixture.store.observeSnapshot(expected, observation, "old result"));
        assertEquals(before, snapshot(fixture.current, fixture.source, expected));
        fixture.verifyLockOrder();
        fixture.verifyNoOtherInteractions();
      }
  }

  /** Pay进度与金额表示不影响匹配；更新和读回仅发生在两次锁读之后。 */
  @Test
  void validLockedSourcesKeepProgressAndNumericRepresentation() {
    for (String status :
        List.of("PENDING", "SUCCESS", "REFUNDED", "FAILED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING"))
      for (String amount : List.of("10", "10.000", "1E+1"))
        for (String observation : List.of("COMMITTED", "ROLLEDBACK", "UNKNOWN")) {
          var fixture = new Fixture();
          fixture.source.status = status;
          fixture.source.amount = new BigDecimal(amount);
          var expected = execution();
          var updated = execution();
          updated.state = observation;
          when(fixture.executions.updateObservation(any(), any(), any(), any())).thenReturn(1);
          when(fixture.executions.selectByPayNo(expected.payNo)).thenReturn(updated);
          var before = snapshot(fixture.current, fixture.source, expected);
          assertSame(updated, fixture.store.observeSnapshot(expected, observation, "matching"));
          assertEquals(before, snapshot(fixture.current, fixture.source, expected));
          var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
          order.verify(fixture.payments).selectForUpdate(expected.payNo);
          order.verify(fixture.executions).selectForUpdate(expected.payNo);
          order
              .verify(fixture.executions)
              .updateObservation(eq(expected.payNo), eq(observation), eq("matching"), any());
          order.verify(fixture.executions).selectByPayNo(expected.payNo);
          fixture.verifyNoOtherInteractions();
        }
  }

  /** NULL快照无访问；Pay DAO先于execution，原域与anchor仍先于来源校验和merge。 */
  @Test
  void sourceLockAndExecutionValidationKeepExplicitPriority() {
    var empty = new Fixture();
    assertConflict(() -> empty.store.observeSnapshot(null, "UNKNOWN", "missing"));
    empty.verifyNoOtherInteractions();
    var unavailable = new Fixture();
    var expected = execution();
    expected.amount = null;
    var daoFailure = new IllegalStateException("Pay lock unavailable");
    when(unavailable.payments.selectForUpdate(expected.payNo)).thenThrow(daoFailure);
    assertSame(
        daoFailure,
        assertThrows(
            IllegalStateException.class,
            () -> unavailable.store.observeSnapshot(expected, "UNKNOWN", "busy")));
    verify(unavailable.payments).selectForUpdate(expected.payNo);
    unavailable.verifyNoOtherInteractions();
    for (String mutation : List.of("missing-current", "bad-current", "bad-expected", "anchor")) {
      var fixture = new Fixture();
      var original = execution();
      when(fixture.payments.selectForUpdate(original.payNo)).thenReturn(List.of());
      switch (mutation) {
        case "missing-current" ->
            when(fixture.executions.selectForUpdate(original.payNo)).thenReturn(null);
        case "bad-current" -> fixture.current.operationKey = "bad";
        case "bad-expected" -> original.amount = null;
        case "anchor" -> fixture.current.xid = "another-xid";
        default -> throw new IllegalArgumentException(mutation);
      }
      assertConflict(() -> fixture.store.observeSnapshot(original, "UNKNOWN", "priority"));
      fixture.verifyLockOrder();
      fixture.verifyNoOtherInteractions();
    }
  }

  /** 有效执行身份；current与expected分别构造，避免同对象变异掩盖漂移。 */
  private static PayTccExecutionPO execution() {
    var execution = new PayTccExecutionPO();
    execution.id = 1L;
    execution.payNo = "pay-source-lock-1";
    execution.userId = 7L;
    execution.orderNo = "order-source-lock-1";
    execution.amount = new BigDecimal("10.00");
    execution.xid = "source-lock-xid";
    execution.operationKey = "tcc-debit:" + execution.payNo;
    execution.ledgerKey = execution.payNo;
    execution.state = "RUNNING";
    execution.decision = "NONE";
    return execution;
  }

  /** 匹配的原TCC支付；不绑定其当前status。 */
  private static PayRecordPO payment() {
    var payment = new PayRecordPO();
    payment.payNo = "pay-source-lock-1";
    payment.userId = 7L;
    payment.orderNo = "order-source-lock-1";
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = "TCC";
    payment.status = "PENDING";
    return payment;
  }

  /** 不规范化原PO或金额表示。 */
  private String snapshot(Object... records) {
    return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(records));
  }

  /** 来源冲突沿用既有业务码及HTTP状态。 */
  private void assertConflict(org.junit.jupiter.api.function.Executable action) {
    var failure = assertThrows(BizException.class, action);
    assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
    assertEquals(409, failure.getHttpStatus());
  }

  /** 独立mock持久资源，仅两锁、允许的原更新与读回可被访问。 */
  private static final class Fixture {
    /** 执行Mapper。 */
    private final PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);

    /** 原PayMapper。 */
    private final PayRecordMapper payments = mock(PayRecordMapper.class);

    /** 派发Mapper。 */
    private final PayTccDispatchMapper dispatches = mock(PayTccDispatchMapper.class);

    /** receipt Mapper。 */
    private final PayTccReceiptMapper receipts = mock(PayTccReceiptMapper.class);

    /** 取消Mapper。 */
    private final PayTccCancelMapper cancels = mock(PayTccCancelMapper.class);

    /** 锁读的当前执行。 */
    private final PayTccExecutionPO current = execution();

    /** 锁读的原Pay。 */
    private final PayRecordPO source = payment();

    /** 实际被测Store。 */
    private final PayTccExecutionStore store =
        new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);

    /** 准备两个独立且匹配的持久事实。 */
    private Fixture() {
      when(payments.selectForUpdate(current.payNo)).thenReturn(List.of(source));
      when(executions.selectForUpdate(current.payNo)).thenReturn(current);
    }

    /** 固定Pay→execution锁序。 */
    private void verifyLockOrder() {
      var order = org.mockito.Mockito.inOrder(payments, executions);
      order.verify(payments).selectForUpdate(current.payNo);
      order.verify(executions).selectForUpdate(current.payNo);
    }

    /** 未授权访问、无锁查询及其它资源调用均不允许。 */
    private void verifyNoOtherInteractions() {
      verifyNoMoreInteractions(payments, executions);
      verifyNoInteractions(dispatches, receipts, cancels);
    }
  }
}
