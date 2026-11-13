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
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import com.cloudmall.pay.service.impl.PayTccReceiptStore;
import com.cloudmall.pay.service.impl.dispatch.PayTccDispatchPersistence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 16E cancel gate 与首次 TCC 资金认领互斥测试。 */
class PayTccCancelGateTest {
  /** Execution Mapper。 */
  private PayTccExecutionMapper executions;

  /** Pay record Mapper。 */
  private PayRecordMapper payments;

  /** Dispatch Mapper。 */
  private PayTccDispatchMapper dispatches;

  /** Receipt Mapper。 */
  private PayTccReceiptMapper receipts;

  /** Cancel gate Mapper。 */
  private PayTccCancelMapper cancels;

  /** 准备所有首次认领入口依赖。 */
  @BeforeEach
  void setUp() {
    executions = mock(PayTccExecutionMapper.class);
    payments = mock(PayRecordMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    receipts = mock(PayTccReceiptMapper.class);
    cancels = mock(PayTccCancelMapper.class);
  }

  /** 已有 cancel 台账阻止新 execution，且不写 execution。 */
  @Test
  void cancelGateBlocksExecutionClaim() {
    PayTccExecutionStore store =
        new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);
    PayRecordPO payment = payment("TCC", "PENDING");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(executions.selectForUpdate(payment.payNo)).thenReturn(null);
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(mock(PayTccCancelPO.class));

    BizException pending = assertThrows(BizException.class, () -> store.claim(request()));
    org.junit.jupiter.api.Assertions.assertEquals(202, pending.getHttpStatus());
    verify(executions, never()).insert(any());
  }

  /** 已有 cancel 台账阻止 dispatch prepare，不写 dispatch。 */
  @Test
  void cancelGateBlocksDispatchPrepare() {
    PayTccDispatchPersistence persistence =
        new PayTccDispatchPersistence(dispatches, payments, cancels);
    PayRecordPO payment = payment("TCC", "PENDING");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(mock(PayTccCancelPO.class));

    assertThrows(BizException.class, () -> persistence.prepare(dispatch()));
    verify(dispatches, never()).insert(any());
  }

  /** 已有 cancel 台账阻止 POST_ATTEMPTED CAS。 */
  @Test
  void cancelGateBlocksPostAttemptedCas() {
    PayTccDispatchPersistence persistence =
        new PayTccDispatchPersistence(dispatches, payments, cancels);
    PayRecordPO payment = payment("TCC", "PENDING");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(mock(PayTccCancelPO.class));

    org.junit.jupiter.api.Assertions.assertFalse(
        persistence.markPostAttempted(dispatch(), LocalDateTime.now()));
    verify(dispatches, never()).markPostAttempted(any(), any(), any());
  }

  /** 已有 cancel 台账阻止 receipt claim，不写 receipt。 */
  @Test
  void cancelGateBlocksReceiptClaim() {
    PayTccReceiptStore store = new PayTccReceiptStore(receipts, payments, dispatches, cancels);
    PayRecordPO payment = payment("TCC", "PENDING");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(dispatches.selectForUpdate(payment.payNo)).thenReturn(dispatch());
    when(cancels.selectForUpdate(payment.payNo)).thenReturn(new PayTccCancelPO());

    assertThrows(BizException.class, () -> store.claim(dispatchView()));
    verify(receipts, never()).claim(any());
  }

  /** Dispatch prepare 在 pay_record 行锁内拒绝 Legacy、非 PENDING 和 tuple 不一致。 */
  @Test
  void dispatchPrepareValidatesLockedPaymentTuple() {
    PayTccDispatchPersistence persistence =
        new PayTccDispatchPersistence(dispatches, payments, cancels);
    PayTccDispatchPO dispatch = dispatch();
    PayRecordPO payment = payment("LEGACY", "PENDING");
    when(payments.selectForUpdate(dispatch.payNo)).thenReturn(List.of(payment));
    assertThrows(BizException.class, () -> persistence.prepare(dispatch));

    payment.debitMode = "TCC";
    payment.status = "SUCCESS";
    assertThrows(BizException.class, () -> persistence.prepare(dispatch));

    payment.status = "PENDING";
    payment.amount = new BigDecimal("11.00");
    assertThrows(BizException.class, () -> persistence.prepare(dispatch));
    verify(dispatches, never()).insert(any());
  }

  private PayTccDispatchDTO request() {
    PayTccDispatchDTO request = new PayTccDispatchDTO();
    request.payNo = "pay-cancel-gate";
    request.userId = 7L;
    request.orderNo = "order-cancel-gate";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private PayTccDispatchPO dispatch() {
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.payNo = "pay-cancel-gate";
    dispatch.xid = "xid-cancel-gate";
    dispatch.userId = 7L;
    dispatch.orderNo = "order-cancel-gate";
    dispatch.amount = new BigDecimal("10.00");
    dispatch.operationKey = "tcc-debit:pay-cancel-gate";
    dispatch.ledgerKey = "pay-cancel-gate";
    dispatch.state = "PREPARED";
    return dispatch;
  }

  private PayTccDispatchVO dispatchView() {
    PayTccDispatchVO view = new PayTccDispatchVO();
    view.payNo = "pay-cancel-gate";
    view.xid = "xid-cancel-gate";
    view.userId = 7L;
    view.orderNo = "order-cancel-gate";
    view.amount = "10.00";
    view.operationKey = "tcc-debit:pay-cancel-gate";
    view.ledgerKey = "pay-cancel-gate";
    view.state = "PREPARED";
    return view;
  }

  private PayRecordPO payment(String mode, String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-cancel-gate";
    payment.userId = 7L;
    payment.orderNo = "order-cancel-gate";
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = mode;
    payment.status = status;
    return payment;
  }
}
