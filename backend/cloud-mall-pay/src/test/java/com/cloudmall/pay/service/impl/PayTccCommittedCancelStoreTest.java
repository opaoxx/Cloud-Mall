package com.cloudmall.pay.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 已提交 TCC 未发起交付窗口取消意图存储测试。 */
class PayTccCommittedCancelStoreTest {
  private PayRecordMapper payments;
  private PayTccDeliveryMapper deliveries;
  private PayTccExecutionMapper executions;
  private PayTccDispatchMapper dispatches;
  private PayTccExecutionEvidence evidence;
  private PayTccCancelMapper cancellations;
  private PayDebitIntentMapper intents;
  private PayTccCommittedCancelStore store;

  @BeforeEach
  void setUp() {
    payments = mock(PayRecordMapper.class);
    deliveries = mock(PayTccDeliveryMapper.class);
    executions = mock(PayTccExecutionMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    evidence = mock(PayTccExecutionEvidence.class);
    cancellations = mock(PayTccCancelMapper.class);
    intents = mock(PayDebitIntentMapper.class);
    store =
        new PayTccCommittedCancelStore(
            payments, deliveries, executions, dispatches, evidence, cancellations, intents);
  }

  @Test
  void missingDeliveryCreatesCancellationIntentWithImmutableTuple() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    prepare(payment, execution, null);
    when(deliveries.insert(any(PayTccDeliveryPO.class))).thenReturn(1);

    store.request(payment);

    verify(deliveries)
        .insert(
            argThat(
                row ->
                    "CANCEL_REQUESTED".equals(row.state)
                        && row.payNo.equals(payment.payNo)
                        && row.xid.equals(execution.xid)
                        && row.refundOperationKey.equals("refund:" + payment.payNo)
                        && row.attempts == 0));
  }

  @Test
  void readyUnclaimedDeliveryUsesStateCasWithoutChangingLease() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO ready = delivery("READY", 0, null, null);
    prepare(payment, execution, ready);
    when(deliveries.requestCancellation(eq(payment.payNo), any())).thenReturn(1);

    store.request(payment);

    verify(deliveries).requestCancellation(eq(payment.payNo), any());
    verify(deliveries, never()).insert(any());
  }

  @Test
  void readyExpiredClaimedDeliveryCanCasWithoutTakingItsOldLease() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO expired =
        delivery("READY", 3, "old-worker", java.time.LocalDateTime.now().minusSeconds(1));
    prepare(payment, execution, expired);
    when(deliveries.requestCancellation(eq(payment.payNo), any())).thenReturn(1);

    store.request(payment);

    verify(deliveries).requestCancellation(eq(payment.payNo), any());
    verify(deliveries, never()).insert(any());
  }

  @Test
  void readyActiveClaimedDeliveryCanCasWithoutTakingItsLease() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO active =
        delivery("READY", 2, "active-worker", java.time.LocalDateTime.now().plusMinutes(1));
    prepare(payment, execution, active);
    when(deliveries.requestCancellation(eq(payment.payNo), any())).thenReturn(1);

    store.request(payment);

    verify(deliveries).requestCancellation(eq(payment.payNo), any());
    verify(deliveries, never()).insert(any());
  }

  @Test
  void readyPartialBlankNullAndNegativeAttemptsAreRejected() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO missingAttempts = delivery("READY", 0, null, null);
    missingAttempts.attempts = null;
    for (PayTccDeliveryPO candidate :
        List.of(
            missingAttempts,
            delivery("READY", -1, null, null),
            delivery("READY", 1, "worker", null),
            delivery("READY", 1, null, java.time.LocalDateTime.now().minusSeconds(1)),
            delivery("READY", 1, " ", java.time.LocalDateTime.now().minusSeconds(1)))) {
      prepare(payment, execution, candidate);
      assertThrows(BizException.class, () -> store.request(payment));
      verify(deliveries, never()).requestCancellation(any(), any());
      reset(deliveries);
    }
  }

  @Test
  void existingCancellationRequestIsIdempotentWithoutLeaseMutation() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO requested = delivery("CANCEL_REQUESTED", 0, null, null);
    prepare(payment, execution, requested);

    store.request(payment);

    verify(deliveries, never()).insert(any());
    verify(deliveries, never()).requestCancellation(any(), any());
  }

  @Test
  void claimedOrLegacyOrOldCancelFactsBlockDirectionChange() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    PayTccDeliveryPO claimed = delivery("READY", 1, "worker", null);
    prepare(payment, execution, claimed);
    assertThrows(BizException.class, () -> store.request(payment));
    verify(deliveries, never()).requestCancellation(any(), any());

    reset(deliveries, cancellations, intents);
    prepare(payment, execution, null);
    when(cancellations.selectForUpdate(payment.payNo)).thenReturn(new PayTccCancelPO());
    assertThrows(BizException.class, () -> store.request(payment));

    reset(deliveries, cancellations, intents);
    prepare(payment, execution, null);
    when(intents.findByPayNo(payment.payNo))
        .thenReturn(mock(com.cloudmall.pay.domain.po.PayDebitIntentPO.class));
    assertThrows(BizException.class, () -> store.request(payment));
  }

  @Test
  void mismatchedEvidenceAndTupleCannotCreateIntent() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution();
    execution.amount = new BigDecimal("11.00");
    prepare(payment, execution, null);

    assertThrows(BizException.class, () -> store.request(payment));
    verify(deliveries, never()).insert(any());
  }

  private void prepare(PayVO payment, PayTccExecutionPO execution, PayTccDeliveryPO delivery) {
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(paymentRecord()));
    when(deliveries.selectForUpdate(payment.payNo)).thenReturn(delivery);
    when(cancellations.selectForUpdate(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);
    when(executions.selectForUpdate(payment.payNo)).thenReturn(execution);
    when(dispatches.selectByPayNo(payment.payNo)).thenReturn(dispatch());
    when(evidence.classify(eq(execution), any())).thenReturn("COMMITTED");
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "committed-cancel-test";
    payment.userId = 7L;
    payment.orderNo = "order-committed-cancel-test";
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    return payment;
  }

  private PayRecordPO paymentRecord() {
    PayRecordPO record = new PayRecordPO();
    record.payNo = payment().payNo;
    record.userId = 7L;
    record.orderNo = payment().orderNo;
    record.amount = new BigDecimal("10.00");
    record.status = "PENDING";
    record.debitMode = "TCC";
    return record;
  }

  private PayTccExecutionPO execution() {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = payment().payNo;
    execution.xid = "xid-committed-cancel-test";
    execution.userId = 7L;
    execution.orderNo = payment().orderNo;
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:" + execution.payNo;
    execution.ledgerKey = execution.payNo;
    execution.state = "COMMITTED";
    execution.decision = "COMMIT";
    return execution;
  }

  private PayTccDeliveryPO delivery(
      String state, int attempts, String owner, java.time.LocalDateTime until) {
    PayTccDeliveryPO delivery = new PayTccDeliveryPO();
    delivery.payNo = payment().payNo;
    delivery.xid = execution().xid;
    delivery.userId = 7L;
    delivery.orderNo = payment().orderNo;
    delivery.amount = new BigDecimal("10.00");
    delivery.state = state;
    delivery.refundOperationKey = "refund:" + delivery.payNo;
    delivery.attempts = attempts;
    delivery.leaseOwner = owner;
    delivery.leaseUntil = until;
    return delivery;
  }

  private PayTccDispatchPO dispatch() {
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.payNo = payment().payNo;
    dispatch.userId = 7L;
    dispatch.orderNo = payment().orderNo;
    dispatch.amount = new BigDecimal("10.00");
    dispatch.operationKey = "tcc-debit:" + dispatch.payNo;
    dispatch.ledgerKey = dispatch.payNo;
    dispatch.xid = execution().xid;
    return dispatch;
  }
}
