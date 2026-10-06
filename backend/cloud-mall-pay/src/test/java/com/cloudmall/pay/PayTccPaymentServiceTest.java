package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccPaymentService;
import com.cloudmall.pay.service.impl.PayTccPublicStateStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TCC payment service 的首次预检、既有执行恢复和交付门控测试。 */
class PayTccPaymentServiceTest {
  private PayTccCoordinatorService coordinator;
  private PayTccDeliveryService delivery;
  private PayTccPublicStateStore states;
  private OrderClient orders;
  private UserTccClient users;
  private PayTccPaymentService service;

  /** 准备 TCC payment service 依赖。 */
  @BeforeEach
  void setUp() {
    coordinator = mock(PayTccCoordinatorService.class);
    delivery = mock(PayTccDeliveryService.class);
    states = mock(PayTccPublicStateStore.class);
    orders = mock(OrderClient.class);
    users = mock(UserTccClient.class);
    service = new PayTccPaymentService(coordinator, delivery, states, orders, users, "token");
  }

  /** 已有 execution 时直接 recover，不重新预检订单或 begin。 */
  @Test
  void existingExecutionRecoversWithoutOrderPrecheck() {
    PayVO payment = payment();
    PayTccExecutionVO execution = execution("COMMITTED");
    when(states.execution(payment.payNo)).thenReturn(executionPO("COMMITTED"));
    when(coordinator.recover(payment.payNo)).thenReturn(execution);
    when(delivery.deliver(payment.payNo)).thenReturn(delivery("SUCCESS"));

    assertEquals("SUCCESS", service.pay(payment));

    verify(orders, never()).paymentState(any(), any());
    verify(coordinator).recover(payment.payNo);
    verify(delivery).deliver(payment.payNo);
  }

  /** 首次执行订单金额/状态预检不匹配时拒绝，不创建 execution。 */
  @Test
  void firstExecutionOrderPrecheckMismatchReturnsPending() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(null);
    OrderClient.OrderView order =
        new OrderClient.OrderView(
            payment.orderNo, "PENDING_PAYMENT", new BigDecimal("11.00"), payment.userId);
    when(orders.paymentState(payment.orderNo, payment.userId))
        .thenReturn(com.cloudmall.common.api.ApiResponse.ok(order));

    BizException pending = assertThrows(BizException.class, () -> service.pay(payment));

    assertEquals(202, pending.getHttpStatus());
    verify(coordinator, never()).execute(any());
  }

  /** COMMITTED 只调用 delivery，不直接投影公开 SUCCESS。 */
  @Test
  void committedExecutionOnlyUsesDelivery() {
    PayVO payment = payment();
    PayTccExecutionVO execution = execution("COMMITTED");
    when(states.execution(payment.payNo)).thenReturn(executionPO("COMMITTED"));
    when(coordinator.recover(payment.payNo)).thenReturn(execution);
    when(delivery.deliver(payment.payNo)).thenReturn(delivery("ORDER_SYNC_UNKNOWN"));

    assertEquals("ORDER_SYNC_UNKNOWN", service.pay(payment));

    verify(delivery).deliver(payment.payNo);
    verify(users, never()).state(any(), any(), any(), any(), any(), any());
  }

  /** ROLLEDBACK 缺少 User 取消证据时保持 202，不投影 FAILED。 */
  @Test
  void rollbackWithoutUserEvidenceRemainsPending() {
    PayVO payment = payment();
    PayTccExecutionVO execution = execution("ROLLEDBACK");
    when(states.execution(payment.payNo)).thenReturn(executionPO("ROLLEDBACK"));
    when(coordinator.recover(payment.payNo)).thenReturn(execution);
    when(states.cancelledProof(payment))
        .thenThrow(new BizException("PAY_ORDER_SYNC_UNKNOWN", "pending", 202));

    BizException pending = assertThrows(BizException.class, () -> service.pay(payment));

    assertEquals(202, pending.getHttpStatus());
    verify(delivery, never()).deliver(any());
    verify(users, never()).state(any(), any(), any(), any(), any(), any());
  }

  /** Pay 取消证据完整但 User live GET 缺失时不能投影 FAILED。 */
  @Test
  void rollbackMissingUserLiveStateRemainsPending() {
    arrangeRollbackProof();
    when(users.state(any(), any(), any(), any(), any(), any())).thenReturn(null);

    BizException pending = assertThrows(BizException.class, () -> service.pay(payment()));

    assertEquals(202, pending.getHttpStatus());
    verify(states, never()).projectFailed(any(), any());
  }

  /** User live GET 携带 APPLIED ledger 时不能伪装为 CANCELLED。 */
  @Test
  void rollbackAppliedLedgerRemainsPending() {
    arrangeRollbackProof();
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userCancelledState(true, 21L)));

    BizException pending = assertThrows(BizException.class, () -> service.pay(payment()));

    assertEquals(202, pending.getHttpStatus());
    verify(states, never()).projectFailed(any(), any());
  }

  /** User live GET branch 不匹配时不能投影 FAILED。 */
  @Test
  void rollbackMismatchedBranchRemainsPending() {
    arrangeRollbackProof();
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userCancelledState(false, 22L)));

    BizException pending = assertThrows(BizException.class, () -> service.pay(payment()));

    assertEquals(202, pending.getHttpStatus());
    verify(states, never()).projectFailed(any(), any());
  }

  /** Pay/User 完整 CANCELLED 且无 APPLIED ledger 时才允许投影 FAILED。 */
  @Test
  void rollbackCompleteCancelledProofProjectsFailed() {
    arrangeRollbackProof();
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userCancelledState(false, 21L)));
    when(states.projectFailed(any(), any())).thenReturn("FAILED");

    assertEquals("FAILED", service.pay(payment()));
    verify(states).projectFailed(any(), org.mockito.ArgumentMatchers.eq("xid-payment-test"));
  }

  /** 真实 failure 方法只返回保守 202，不调用任何远程依赖。 */
  @Test
  void failureDoesNotCallRemoteDependencies() {
    BizException pending = assertThrows(BizException.class, () -> service.failure(payment()));

    assertEquals(202, pending.getHttpStatus());
    verify(coordinator, never()).execute(any());
    verify(coordinator, never()).recover(any());
    verify(delivery, never()).deliver(any());
    verify(orders, never()).paymentState(any(), any());
    verify(users, never()).state(any(), any(), any(), any(), any(), any());
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "pay-payment-test";
    payment.orderNo = "order-payment-test";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    return payment;
  }

  private PayTccExecutionVO execution(String state) {
    PayTccExecutionVO execution = new PayTccExecutionVO();
    execution.payNo = "pay-payment-test";
    execution.state = state;
    execution.decision = "COMMIT";
    execution.xid = "xid-payment-test";
    return execution;
  }

  private PayTccExecutionPO executionPO(String state) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay-payment-test";
    execution.state = state;
    execution.decision = "COMMIT";
    execution.xid = "xid-payment-test";
    return execution;
  }

  private PayTccDeliveryVO delivery(String state) {
    PayTccDeliveryVO delivery = new PayTccDeliveryVO();
    delivery.state = state;
    return delivery;
  }

  private void arrangeRollbackProof() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(executionPO("ROLLEDBACK"));
    when(coordinator.recover(payment.payNo)).thenReturn(execution("ROLLEDBACK"));
    when(states.cancelledProof(any(PayVO.class))).thenReturn(cancelledProof());
  }

  private PayTccDispatchVO cancelledProof() {
    PayTccDispatchVO proof = new PayTccDispatchVO();
    proof.payNo = "pay-payment-test";
    proof.userId = 7L;
    proof.orderNo = "order-payment-test";
    proof.amount = "10.00";
    proof.operationKey = "tcc-debit:pay-payment-test";
    proof.ledgerKey = "pay-payment-test";
    proof.xid = "xid-payment-test";
    proof.userBranchId = 21L;
    proof.state = "CANCELLED";
    return proof;
  }

  private UserTccStateVO userCancelledState(boolean ledgerApplied, Long branchId) {
    UserTccStateVO state = new UserTccStateVO();
    state.userId = 7L;
    state.payNo = "pay-payment-test";
    state.orderNo = "order-payment-test";
    state.amount = "10.00";
    state.operationKey = "tcc-debit:pay-payment-test";
    state.ledgerKey = "pay-payment-test";
    state.xid = "xid-payment-test";
    state.branchId = branchId;
    state.state = "CANCELLED";
    state.ledgerApplied = ledgerApplied;
    return state;
  }
}
