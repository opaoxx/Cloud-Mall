package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccAbortProofVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortStore;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 主动回滚 Store 的双分支 TRYING 证明和单向决议 CAS 测试。 */
class PayTccAbortStoreTest {
  private PayRecordMapper payments;
  private PayTccExecutionMapper executions;
  private PayTccDispatchMapper dispatches;
  private PayTccReceiptMapper receipts;
  private PayTccExecutionEvidence evidence;
  private PayTccCancelMapper cancellations;
  private PayDebitIntentMapper intents;
  private PayTccAbortStore store;

  /** 准备七个必需 Store 依赖。 */
  @BeforeEach
  void setUp() {
    payments = mock(PayRecordMapper.class);
    executions = mock(PayTccExecutionMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    receipts = mock(PayTccReceiptMapper.class);
    evidence = mock(PayTccExecutionEvidence.class);
    cancellations = mock(PayTccCancelMapper.class);
    intents = mock(PayDebitIntentMapper.class);
    store =
        new PayTccAbortStore(
            payments, executions, dispatches, receipts, evidence, cancellations, intents);
  }

  /** 仅 PENDING/UNKNOWN Pay、RUNNING/UNKNOWN execution、NONE decision 和完整双分支 TRYING 可生成 proof。 */
  @Test
  void validTryingFactsProduceProof() {
    stubValidFacts("RUNNING", "NONE");

    PayTccAbortProofVO proof = store.preparedProof(payment());

    org.junit.jupiter.api.Assertions.assertEquals("xid-abort-store", proof.xid);
    org.junit.jupiter.api.Assertions.assertEquals(41L, proof.payBranchId);
    org.junit.jupiter.api.Assertions.assertEquals(31L, proof.userBranchId);
  }

  /** COMMIT、已回滚、无 XID、无 branch、取消或 Legacy intent 均阻止 proof。 */
  @Test
  void invalidFactsDoNotProduceProof() {
    stubValidFacts("RUNNING", "COMMIT");
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
    stubValidFacts("ROLLEDBACK", "NONE");
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
    stubValidFacts("RUNNING", "NONE");
    PayTccExecutionPO missingXid = execution("RUNNING", "NONE");
    missingXid.xid = null;
    when(executions.selectForUpdate(payment().payNo)).thenReturn(missingXid);
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
    stubValidFacts("RUNNING", "NONE");
    PayTccDispatchPO missingBranch = dispatch();
    missingBranch.userBranchId = null;
    when(dispatches.selectByPayNo(payment().payNo)).thenReturn(missingBranch);
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
    stubValidFacts("RUNNING", "NONE");
    when(cancellations.selectByPayNo(payment().payNo))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccCancelPO());
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
    stubValidFacts("RUNNING", "NONE");
    when(intents.findByPayNo(payment().payNo)).thenReturn(new PayDebitIntentPO());
    assertThrows(BizException.class, () -> store.preparedProof(payment()));
  }

  /** 初始 proof 后 COMMIT 已先赢时，ROLLBACK CAS 不得覆盖原决议。 */
  @Test
  void commitWinnerCannotBeOverwrittenByRollback() {
    stubValidFacts("RUNNING", "NONE");
    PayTccAbortProofVO expected = store.preparedProof(payment());
    PayTccExecutionPO committed = execution();
    committed.decision = "COMMIT";
    committed.state = "COMMIT_REQUESTED";
    when(executions.requestRollback(any(), any(), any())).thenReturn(0);
    when(executions.selectForUpdate(payment().payNo)).thenReturn(committed);

    assertThrows(BizException.class, () -> store.requestRollback(payment(), expected));
    verify(executions, never()).requestRollback(any(), any(), any());
  }

  /** Pay/User branch 发生变化时拒绝用旧 proof 选择回滚。 */
  @Test
  void branchChangeInvalidatesRollbackProof() {
    stubValidFacts("RUNNING", "NONE");
    PayTccAbortProofVO expected = store.preparedProof(payment());
    expected.userBranchId = 99L;

    assertThrows(BizException.class, () -> store.requestRollback(payment(), expected));
    verify(executions, never()).requestRollback(any(), any(), any());
  }

  @Test
  void completeProofOnlyWritesOriginalRollbackDecision() {
    stubValidFacts("RUNNING", "NONE");
    PayTccAbortProofVO proof = store.preparedProof(payment());
    when(executions.requestRollback(any(), any(), any())).thenReturn(1);
    store.requestRollback(payment(), proof);
    verify(executions)
        .requestRollback(
            org.mockito.ArgumentMatchers.eq(proof.payNo),
            org.mockito.ArgumentMatchers.eq(proof.xid),
            any());
    verify(payments, never()).markFailed(any(), any());
  }

  private void stubValidFacts(String state, String decision) {
    PayVO payment = payment();
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(paymentRecord()));
    when(executions.selectForUpdate(payment.payNo)).thenReturn(execution(state, decision));
    when(dispatches.selectByPayNo(payment.payNo)).thenReturn(dispatch());
    when(receipts.selectByPayNo(payment.payNo)).thenReturn(receipt());
    when(cancellations.selectByPayNo(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);
    when(evidence.classify(any(), any())).thenReturn("TRYING");
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "pay-abort-store";
    payment.userId = 7L;
    payment.orderNo = "order-abort-store";
    payment.amount = new BigDecimal("10.00");
    return payment;
  }

  private PayRecordPO paymentRecord() {
    PayRecordPO record = new PayRecordPO();
    record.payNo = "pay-abort-store";
    record.userId = 7L;
    record.orderNo = "order-abort-store";
    record.amount = new BigDecimal("10.00");
    record.debitMode = "TCC";
    record.status = "PENDING";
    return record;
  }

  private PayTccExecutionPO execution() {
    return execution("RUNNING", "NONE");
  }

  private PayTccExecutionPO execution(String state, String decision) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay-abort-store";
    execution.xid = "xid-abort-store";
    execution.userId = 7L;
    execution.orderNo = "order-abort-store";
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:pay-abort-store";
    execution.ledgerKey = "pay-abort-store";
    execution.state = state;
    execution.decision = decision;
    return execution;
  }

  private PayTccDispatchPO dispatch() {
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.payNo = "pay-abort-store";
    dispatch.xid = "xid-abort-store";
    dispatch.userId = 7L;
    dispatch.orderNo = "order-abort-store";
    dispatch.amount = new BigDecimal("10.00");
    dispatch.operationKey = "tcc-debit:pay-abort-store";
    dispatch.ledgerKey = "pay-abort-store";
    dispatch.state = "TRY_ACCEPTED";
    dispatch.userBranchId = 31L;
    return dispatch;
  }

  private PayTccReceiptPO receipt() {
    PayTccReceiptPO receipt = new PayTccReceiptPO();
    receipt.payNo = "pay-abort-store";
    receipt.xid = "xid-abort-store";
    receipt.branchId = 41L;
    return receipt;
  }
}
