package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.PayTccRollbackProjectionService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 回滚投影 lease、原 XID 和 Legacy 隔离测试。 */
class PayTccRollbackProjectionLeaseTest {
  private PayRecordMapper payments;
  private PayTccExecutionMapper executions;
  private PayTccDispatchMapper dispatches;
  private PayTccExecutionEvidence evidence;
  private PayTccCancelMapper cancellations;
  private PayDebitIntentMapper intents;
  private PayTccPublicStateStore states;
  private PayVO payment;
  private PayTccExecutionPO execution;

  /** 准备真实投影 Store 所需的六个 Mapper 依赖。 */
  @BeforeEach
  void setUp() {
    payments = mock(PayRecordMapper.class);
    executions = mock(PayTccExecutionMapper.class);
    dispatches = mock(PayTccDispatchMapper.class);
    evidence = mock(PayTccExecutionEvidence.class);
    cancellations = mock(PayTccCancelMapper.class);
    intents = mock(PayDebitIntentMapper.class);
    states =
        new PayTccPublicStateStore(
            payments, executions, dispatches, evidence, cancellations, intents);
    payment = payment();
    execution = execution("NONE");
    when(payments.selectForUpdate(payment.payNo)).thenReturn(java.util.List.of(paymentRecord()));
    when(executions.selectForUpdate(payment.payNo)).thenReturn(execution);
    when(cancellations.selectForUpdate(payment.payNo)).thenReturn(null);
    when(intents.findByPayNo(payment.payNo)).thenReturn(null);
    when(payments.markFailed(any(), any())).thenReturn(1);
  }

  /** 空 token 不得触发状态 Store 的投影或支付更新。 */
  @Test
  void emptyTokenDoesNotProject() {
    PayTccRollbackProjectionService projection =
        new PayTccRollbackProjectionService(states, mock(UserTccClient.class), "token");

    assertThrows(BizException.class, () -> projection.projectClaimed(payment, ""));
    verify(payments, never()).markFailed(any(), any());
    verify(executions, never()).selectForUpdate(anyString());
  }

  /** token 不符或 lease 过期时不得写 FAILED。 */
  @Test
  void wrongOrExpiredLeaseCannotProject() {
    execution.recoveryOwner = "owner-1";
    execution.recoveryLeaseUntil = LocalDateTime.now().plusMinutes(1);
    assertThrows(
        BizException.class, () -> states.projectFailedClaimed(payment, execution.xid, "owner-2"));
    verify(payments, never()).markFailed(any(), any());

    execution.recoveryOwner = "owner-1";
    execution.recoveryLeaseUntil = LocalDateTime.now().minusSeconds(1);
    assertThrows(
        BizException.class, () -> states.projectFailedClaimed(payment, execution.xid, "owner-1"));
    verify(payments, never()).markFailed(any(), any());
  }

  /** NONE/COMMIT/ROLLBACK 三种合法 decision 均可在 owner lease 内投影。 */
  @Test
  void validOwnerCanProjectAllRollbackDecisions() {
    execution.recoveryOwner = "owner-1";
    execution.recoveryLeaseUntil = LocalDateTime.now().plusMinutes(1);
    for (String decision : new String[] {"NONE", "COMMIT", "ROLLBACK"}) {
      execution.decision = decision;
      assertEquals("FAILED", states.projectFailedClaimed(payment, execution.xid, "owner-1"));
    }
    verify(payments, org.mockito.Mockito.times(3)).markFailed(any(), any());
  }

  /** Legacy intent 存在时阻止 TCC rollback 投影。 */
  @Test
  void legacyIntentBlocksRollbackProjection() {
    execution.recoveryOwner = "owner-1";
    execution.recoveryLeaseUntil = LocalDateTime.now().plusMinutes(1);
    when(intents.findByPayNo(payment.payNo)).thenReturn(new PayDebitIntentPO());

    assertThrows(
        BizException.class, () -> states.projectFailedClaimed(payment, execution.xid, "owner-1"));
    verify(payments, never()).markFailed(any(), any());
  }

  @Test
  void backgroundProjectionCannotDowngradeToUnleasedPublicProjection() {
    PayTccPublicStateStore storage = mock(PayTccPublicStateStore.class);
    UserTccClient users = mock(UserTccClient.class);
    PayTccRollbackProjectionService service =
        new PayTccRollbackProjectionService(storage, users, "token");
    assertThrows(BizException.class, () -> service.projectClaimed(payment, null));
    assertThrows(BizException.class, () -> service.projectClaimed(payment, " "));
    org.mockito.Mockito.verifyNoInteractions(storage, users);
  }

  private PayVO payment() {
    PayVO view = new PayVO();
    view.payNo = "pay-lease-test";
    view.userId = 7L;
    view.orderNo = "order-lease-test";
    view.amount = new BigDecimal("10.00");
    view.status = "PENDING";
    return view;
  }

  private PayRecordPO paymentRecord() {
    PayRecordPO record = new PayRecordPO();
    record.payNo = payment.payNo;
    record.userId = payment.userId;
    record.orderNo = payment.orderNo;
    record.amount = payment.amount;
    record.debitMode = "TCC";
    record.status = "PENDING";
    return record;
  }

  private PayTccExecutionPO execution(String decision) {
    PayTccExecutionPO row = new PayTccExecutionPO();
    row.payNo = payment.payNo;
    row.xid = "xid-lease-test";
    row.userId = payment.userId;
    row.orderNo = payment.orderNo;
    row.amount = payment.amount;
    row.operationKey = "tcc-debit:" + payment.payNo;
    row.ledgerKey = payment.payNo;
    row.state = "ROLLEDBACK";
    row.decision = decision;
    return row;
  }
}
