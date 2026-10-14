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
  /** Pay状态读取与FAILED写入的替身。 */
  private PayRecordMapper payments;

  /** 原execution当前读及锁定替身。 */
  private PayTccExecutionMapper executions;

  /** 本用例不应触及的派发来源替身。 */
  private PayTccDispatchMapper dispatches;

  /** 本用例不应触及的Pay receipt/Fence快照证据替身。 */
  private PayTccExecutionEvidence evidence;

  /** 取消资金互斥来源替身。 */
  private PayTccCancelMapper cancellations;

  /** Legacy意图资金互斥来源替身。 */
  private PayDebitIntentMapper intents;

  /** 执行本地投影规则的真实Store。 */
  private PayTccPublicStateStore states;

  /** 冻结的输入支付载荷。 */
  private PayVO payment;

  /** 原执行载荷及可控lease字段。 */
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

  /** 七类非法token在Pay/Execution锁和任何Mapper之前按原pending拒绝。 */
  @Test
  void invalidClaimedProjectionTokensSkipAllMappers() {
    for (String recoveryToken :
        java.util.Arrays.asList(
            null, "", " ", "\t\r\n", "\u3000", "a".repeat(129), "😀".repeat(65))) {
      setUp();
      execution.recoveryOwner = recoveryToken;
      execution.recoveryLeaseUntil =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
      var failure =
          assertThrows(
              BizException.class,
              () -> states.projectFailedClaimed(payment, execution.xid, recoveryToken));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
      assertEquals("资金回滚证据尚未齐全", failure.getMessage());
      assertEquals(202, failure.getHttpStatus());
      org.mockito.Mockito.verifyNoInteractions(
          payments, executions, dispatches, evidence, cancellations, intents);
    }
  }

  /** Service在本地proof和User GET之前拒绝七类非法租约，保持原202消息。 */
  @Test
  void invalidServiceProjectionTokensSkipProofAndUserGet() {
    for (String recoveryToken :
        java.util.Arrays.asList(
            null, "", " ", "\t\r\n", "\u3000", "a".repeat(129), "😀".repeat(65))) {
      var storage = mock(PayTccPublicStateStore.class);
      var users = mock(UserTccClient.class);
      var service = new PayTccRollbackProjectionService(storage, users, "internal-token");
      var failure =
          assertThrows(BizException.class, () -> service.projectClaimed(payment, recoveryToken));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
      assertEquals("回滚投影租约无效", failure.getMessage());
      assertEquals(202, failure.getHttpStatus());
      org.mockito.Mockito.verifyNoInteractions(storage, users);
    }
  }

  /** 四合法token保三decision与Service精确透传/证明顺序，公开投影保持无租约。 */
  @Test
  void validProjectionTokensAndPublicPathKeepTheirSemantics() {
    for (String recoveryToken :
        java.util.List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64))) {
      setUp();
      execution.recoveryOwner = recoveryToken;
      execution.recoveryLeaseUntil =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
      for (String decision : java.util.List.of("NONE", "COMMIT", "ROLLBACK")) {
        execution.decision = decision;
        assertEquals("FAILED", states.projectFailedClaimed(payment, execution.xid, recoveryToken));
      }
      verify(payments, org.mockito.Mockito.times(3)).markFailed(any(), any());
      var storage = mock(PayTccPublicStateStore.class);
      var users = mock(UserTccClient.class);
      var proof = new com.cloudmall.pay.domain.vo.PayTccDispatchVO();
      proof.payNo = payment.payNo;
      proof.userId = payment.userId;
      proof.orderNo = payment.orderNo;
      proof.amount = "10.00";
      proof.xid = execution.xid;
      proof.userBranchId = 11L;
      proof.operationKey = execution.operationKey;
      proof.ledgerKey = execution.ledgerKey;
      proof.state = "CANCELLED";
      var user = new com.cloudmall.pay.domain.vo.UserTccStateVO();
      user.payNo = payment.payNo;
      user.userId = payment.userId;
      user.orderNo = payment.orderNo;
      user.amount = "10.00";
      user.xid = proof.xid;
      user.branchId = proof.userBranchId;
      user.operationKey = proof.operationKey;
      user.ledgerKey = proof.ledgerKey;
      user.state = "CANCELLED";
      user.setLedgerApplied(false);
      when(storage.cancelledProof(payment)).thenReturn(proof);
      when(users.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(com.cloudmall.common.api.ApiResponse.ok(user));
      when(storage.projectFailedClaimed(payment, proof.xid, recoveryToken)).thenReturn("FAILED");
      var service = new PayTccRollbackProjectionService(storage, users, "internal-token");
      assertEquals("FAILED", service.projectClaimed(payment, recoveryToken));
      var order = org.mockito.Mockito.inOrder(storage, users);
      order.verify(storage).cancelledProof(payment);
      order
          .verify(users)
          .state(
              payment.userId,
              proof.operationKey,
              payment.userId,
              "cloud-mall-pay",
              "internal-token",
              proof.xid);
      order.verify(storage).projectFailedClaimed(payment, proof.xid, recoveryToken);
      order.verifyNoMoreInteractions();
      org.mockito.Mockito.clearInvocations(storage, users);
      when(storage.projectFailed(payment, proof.xid)).thenReturn("FAILED");
      assertEquals("FAILED", service.project(payment));
      verify(storage).projectFailed(payment, proof.xid);
      verify(storage, never()).projectFailedClaimed(any(), any(), any());
      verify(users)
          .state(
              payment.userId,
              proof.operationKey,
              payment.userId,
              "cloud-mall-pay",
              "internal-token",
              proof.xid);
    }
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
