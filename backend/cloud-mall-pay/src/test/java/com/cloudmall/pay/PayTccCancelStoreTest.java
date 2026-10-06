package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccCancelStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 主动取消 Store 的 gate、重放和状态 CAS 测试。 */
class PayTccCancelStoreTest {
  private PayTccCancelMapper cancels;
  private PayRecordMapper payments;
  private PayTccExecutionMapper executions;
  private PayTccDispatchMapper dispatches;
  private PayTccReceiptMapper receipts;
  private PayDebitIntentMapper intents;
  private PayTccCancelStore store;

  /** 准备取消 Store 的六个必需 Mapper。 */
  @BeforeEach
  void setUp() {
    cancels = mock(PayTccCancelMapper.class);
    payments = mock(PayRecordMapper.class);
    executions = mock(PayTccExecutionMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    receipts = mock(PayTccReceiptMapper.class);
    intents = mock(PayDebitIntentMapper.class);
    store = new PayTccCancelStore(cancels, payments, executions, dispatches, receipts, intents);
  }

  /** 已有 execution/dispatch/receipt 时不插入取消台账。 */
  @Test
  void existingFundsFactBlocksNewCancelInsert() {
    PayVO payment = payment();
    when(payments.selectForUpdate(payment.payNo))
        .thenReturn(List.of(paymentRecord("TCC", "PENDING")));
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(null);
    when(executions.selectByPayNo(payment.payNo))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccExecutionPO());

    assertThrows(BizException.class, () -> store.claimOrRead(payment));
    verify(cancels, never()).insertRequested(any());
  }

  /** 已完成取消但 Pay 仍 PENDING 时不能伪造完成重放。 */
  @Test
  void completedCancelWithPendingPaymentIsRejected() {
    PayVO payment = payment();
    when(payments.selectForUpdate(payment.payNo))
        .thenReturn(List.of(paymentRecord("TCC", "PENDING")));
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(cancel("COMPLETED", null));
    when(executions.selectByPayNo(payment.payNo)).thenReturn(null);
    when(dispatches.selectByPayNo(payment.payNo)).thenReturn(null);
    when(receipts.selectByPayNo(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);

    assertThrows(BizException.class, () -> store.claimOrRead(payment));
    verify(cancels, never()).insertRequested(any());
  }

  /** COMPLETED 且 Pay 已 FAILED 时允许同 tuple 幂等重放。 */
  @Test
  void completedCancelWithFailedPaymentIsIdempotent() {
    PayVO payment = payment();
    PayTccCancelPO completed = cancel("COMPLETED", null);
    when(payments.selectForUpdate(payment.payNo))
        .thenReturn(List.of(paymentRecord("TCC", "FAILED")));
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(completed);
    when(executions.selectByPayNo(payment.payNo)).thenReturn(null);
    when(dispatches.selectByPayNo(payment.payNo)).thenReturn(null);
    when(receipts.selectByPayNo(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);

    PayTccCancelPO result = store.claimOrRead(payment);

    org.junit.jupiter.api.Assertions.assertSame(completed, result);
    verify(cancels, never()).insertRequested(any());
  }

  /** 旧 token、过期 lease 和终态都不得写 Pay 或取消状态。 */
  @Test
  void staleLeaseAndTerminalDoNotWrite() {
    PayVO payment = payment();
    PayRecordPO record = paymentRecord("TCC", "PENDING");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(record));
    PayTccCancelPO requested = cancel("REQUESTED", "new-token");
    requested.leaseUntil = LocalDateTime.now().plusMinutes(1);
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(requested);
    assertFalse(store.stage(payment.payNo, "old-token", "COMPLETED", null));
    verify(payments, never()).markFailed(any(), any());
    verify(cancels, never()).updateState(any(), any(), any(), any(), any());

    requested.leaseOwner = "new-token";
    requested.leaseUntil = LocalDateTime.now().minusSeconds(1);
    assertFalse(store.stage(payment.payNo, "new-token", "COMPLETED", null));
    verify(payments, never()).markFailed(any(), any());

    requested.state = "COMPLETED";
    requested.leaseUntil = LocalDateTime.now().plusMinutes(1);
    assertFalse(store.stage(payment.payNo, "new-token", "COMPLETED", null));
    verify(cancels, never()).updateState(any(), any(), any(), any(), any());
  }

  /** owner本身异常为空时也不能凭相同的空token推进支付。 */
  @Test
  void nullAndBlankStageTokensAreRejected() {
    when(payments.selectForUpdate(payment().payNo))
        .thenReturn(List.of(paymentRecord("TCC", "PENDING")));
    for (String token : java.util.Arrays.asList(null, "", " ")) {
      PayTccCancelPO requested = cancel("REQUESTED", token);
      requested.leaseUntil = LocalDateTime.now().plusMinutes(1);
      when(cancels.selectForUpdate(payment().payNo)).thenReturn(requested);
      assertFalse(store.stage(payment().payNo, token, "COMPLETED", null));
    }
    verify(payments, never()).markFailed(any(), any());
    verify(cancels, never()).updateState(any(), any(), any(), any(), any());
  }

  /** Pay FAILED 成功但取消台账 CAS=0 时必须抛异常触发事务回滚。 */
  @Test
  void failedPaymentProjectionRequiresCancelCas() {
    PayVO payment = payment();
    when(payments.selectForUpdate(payment.payNo))
        .thenReturn(List.of(paymentRecord("TCC", "PENDING")));
    PayTccCancelPO requested = cancel("REQUESTED", "token");
    requested.leaseUntil = LocalDateTime.now().plusMinutes(1);
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(requested);
    when(executions.selectByPayNo(payment.payNo)).thenReturn(null);
    when(dispatches.selectByPayNo(payment.payNo)).thenReturn(null);
    when(receipts.selectByPayNo(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);
    when(payments.markFailed(eq(payment.payNo), any(LocalDateTime.class))).thenReturn(1);
    when(cancels.updateState(any(), any(), any(), any(), any())).thenReturn(0);

    assertThrows(
        IllegalStateException.class, () -> store.stage(payment.payNo, "token", "COMPLETED", null));
    verify(payments).markFailed(eq(payment.payNo), any(LocalDateTime.class));
  }

  /** Legacy 模式和 tuple 变化均不能创建取消台账。 */
  @Test
  void modeAndTupleChangesAreRejected() {
    PayVO payment = payment();
    when(payments.selectForUpdate(payment.payNo))
        .thenReturn(List.of(paymentRecord("LEGACY", "PENDING")));
    assertThrows(BizException.class, () -> store.claimOrRead(payment));
    verify(cancels, never()).insertRequested(any());

    PayRecordPO tcc = paymentRecord("TCC", "PENDING");
    tcc.amount = new BigDecimal("11.00");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(tcc));
    assertThrows(BizException.class, () -> store.claimOrRead(payment));
    verify(cancels, never()).insertRequested(any());
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "pay-cancel-store";
    payment.userId = 7L;
    payment.orderNo = "order-cancel-store";
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    return payment;
  }

  private PayRecordPO paymentRecord(String mode, String status) {
    PayRecordPO record = new PayRecordPO();
    record.payNo = "pay-cancel-store";
    record.userId = 7L;
    record.orderNo = "order-cancel-store";
    record.amount = new BigDecimal("10.00");
    record.debitMode = mode;
    record.status = status;
    return record;
  }

  private PayTccCancelPO cancel(String state, String owner) {
    PayTccCancelPO cancel = new PayTccCancelPO();
    cancel.payNo = "pay-cancel-store";
    cancel.userId = 7L;
    cancel.orderNo = "order-cancel-store";
    cancel.amount = new BigDecimal("10.00");
    cancel.state = state;
    cancel.leaseOwner = owner;
    return cancel;
  }
}
