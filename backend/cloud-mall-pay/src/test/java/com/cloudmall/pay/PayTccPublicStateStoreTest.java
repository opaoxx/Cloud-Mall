package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.service.impl.projection.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 失败投影只依据实际回滚，不修改原决议或覆盖终态。 */
class PayTccPublicStateStoreTest {
  @ParameterizedTest
  @ValueSource(strings = {"NONE", "COMMIT", "ROLLBACK"})
  void actualRollbackCanProjectFailureWithoutChangingRequestedDecision(String decision) {
    PayRecordMapper payments = mock(PayRecordMapper.class);
    PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);
    PayVO payment = payment();
    PayTccExecutionPO execution = execution(decision);
    when(executions.selectByPayNo("pay")).thenReturn(execution);
    when(payments.selectForUpdate("pay")).thenReturn(List.of(record("PENDING")));
    when(payments.markFailed(eq("pay"), any())).thenReturn(1);
    assertEquals("FAILED", store(payments, executions).projectFailed(payment, "xid"));
    assertEquals(decision, execution.decision);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "REFUNDED", "REFUND_PENDING"})
  void terminalOrRefundingPaymentCannotBeOverwritten(String status) {
    PayRecordMapper payments = mock(PayRecordMapper.class);
    PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);
    when(executions.selectByPayNo("pay")).thenReturn(execution("ROLLBACK"));
    when(payments.selectForUpdate("pay")).thenReturn(List.of(record(status)));
    assertThrows(
        BizException.class, () -> store(payments, executions).projectFailed(payment(), "xid"));
    verify(payments, never()).markFailed(any(), any());
  }

  @org.junit.jupiter.api.Test
  void cancellationLedgerCannotCoexistWithRollbackProjection() {
    PayRecordMapper payments = mock(PayRecordMapper.class);
    PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);
    var cancellations = mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class);
    when(payments.selectByPayNo("pay")).thenReturn(List.of(record("PENDING")));
    when(payments.selectForUpdate("pay")).thenReturn(List.of(record("PENDING")));
    when(executions.selectByPayNo("pay")).thenReturn(execution("ROLLBACK"));
    when(cancellations.selectByPayNo("pay"))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccCancelPO());
    when(cancellations.selectForUpdate("pay"))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccCancelPO());
    var store =
        new PayTccPublicStateStore(
            payments,
            executions,
            mock(PayTccDispatchMapper.class),
            mock(PayTccExecutionEvidence.class),
            cancellations,
            mock(com.cloudmall.pay.mapper.PayDebitIntentMapper.class));
    assertThrows(BizException.class, () -> store.cancelledProof(payment()));
    assertThrows(BizException.class, () -> store.projectFailed(payment(), "xid"));
    verify(payments, never()).markFailed(any(), any());
  }

  @org.junit.jupiter.api.Test
  void proofReadsStoredPaymentModeBeforeUsingCancellationEvidence() {
    PayRecordMapper payments = mock(PayRecordMapper.class);
    PayRecordPO payment = record("PENDING");
    payment.debitMode = "LEGACY";
    when(payments.selectByPayNo("pay")).thenReturn(List.of(payment));
    PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);
    assertThrows(BizException.class, () -> store(payments, executions).cancelledProof(payment()));
    verify(executions, never()).selectByPayNo(any());
  }

  private PayTccPublicStateStore store(PayRecordMapper payments, PayTccExecutionMapper executions) {
    return new PayTccPublicStateStore(
        payments,
        executions,
        mock(PayTccDispatchMapper.class),
        mock(PayTccExecutionEvidence.class),
        mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class),
        mock(com.cloudmall.pay.mapper.PayDebitIntentMapper.class));
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "pay";
    payment.orderNo = "order";
    payment.userId = 7L;
    payment.amount = BigDecimal.TEN;
    return payment;
  }

  private PayRecordPO record(String status) {
    PayRecordPO record = new PayRecordPO();
    record.payNo = "pay";
    record.orderNo = "order";
    record.userId = 7L;
    record.amount = BigDecimal.TEN;
    record.debitMode = "TCC";
    record.status = status;
    return record;
  }

  private PayTccExecutionPO execution(String decision) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay";
    execution.orderNo = "order";
    execution.userId = 7L;
    execution.amount = BigDecimal.TEN;
    execution.operationKey = "tcc-debit:pay";
    execution.ledgerKey = "pay";
    execution.xid = "xid";
    execution.state = "ROLLEDBACK";
    execution.decision = decision;
    return execution;
  }
}
