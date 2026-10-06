package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccCancelService;
import com.cloudmall.pay.service.impl.PayTccPaymentService;
import com.cloudmall.pay.service.impl.PayTccPublicStateStore;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** TCC ROLLEDBACK 到公开 FAILED 的完整 User 证据门测试。 */
class PayTccRollbackFailureTest {
  private PayTccCoordinatorService coordinator;
  private PayTccDeliveryService delivery;
  private PayTccPublicStateStore states;
  private OrderClient orders;
  private UserTccClient users;
  private PayTccPaymentService service;
  private PayTccCancelService cancelService;

  /** 准备 Pay TCC payment service 及可选取消服务。 */
  @BeforeEach
  void setUp() {
    coordinator = mock(PayTccCoordinatorService.class);
    delivery = mock(PayTccDeliveryService.class);
    states = mock(PayTccPublicStateStore.class);
    orders = mock(OrderClient.class);
    users = mock(UserTccClient.class);
    cancelService = mock(PayTccCancelService.class);
    service = new PayTccPaymentService(coordinator, delivery, states, orders, users, "token");
    ReflectionTestUtils.setField(service, "cancelService", cancelService);
    ReflectionTestUtils.setField(service, "cancellations", mock(PayTccCancelMapper.class));
  }

  /** 完整 ROLLEDBACK/CANCELLED/no-ledger proof 才允许投影 FAILED。 */
  @Test
  void completeRollbackProofProjectsFailed() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(execution("ROLLEDBACK"));
    when(states.cancelledProof(payment)).thenReturn(proof());
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userState(false, 31L)));
    when(states.projectFailed(payment, "xid-rollback-test")).thenReturn("FAILED");

    service.failure(payment);

    assertEquals("FAILED", payment.status);
    verify(states).projectFailed(payment, "xid-rollback-test");
    verify(cancelService, never()).request(any());
    verify(coordinator, never()).execute(any());
    verify(coordinator, never()).recover(any());
    verify(orders, never()).cancel(any(), any());
    verify(orders, never()).paid(any(), any());
    verify(orders, never()).paymentState(any(), any());
    verify(delivery, never()).deliver(any());
  }

  /** 无 execution 仍交给 16E cancel service，不直接声明 FAILED。 */
  @Test
  void missingExecutionUsesCancelServiceAndKeepsPending() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(null);
    doThrow(new BizException("PAY_ORDER_SYNC_UNKNOWN", "pending", 202))
        .when(cancelService)
        .request(payment);

    BizException pending = assertThrows(BizException.class, () -> service.failure(payment));

    assertEquals(202, pending.getHttpStatus());
    verify(cancelService).request(payment);
    verify(states, never()).projectFailed(any(), any());
  }

  /** UNKNOWN 或 COMMITTED execution 不得调用 cancel service 或支付失败投影。 */
  @Test
  void nonRollbackExecutionDoesNotCancelOrFail() {
    for (String state :
        new String[] {
          "CLAIMED",
          "BEGIN_UNKNOWN",
          "RUNNING",
          "UNKNOWN",
          "COMMIT_REQUESTED",
          "ROLLBACK_REQUESTED",
          "COMMITTED",
          "CONFLICT"
        }) {
      PayVO payment = payment();
      when(states.execution(payment.payNo)).thenReturn(execution(state));
      BizException pending = assertThrows(BizException.class, () -> service.failure(payment));
      assertEquals(202, pending.getHttpStatus());
    }

    verify(cancelService, never()).request(any());
    verify(states, never()).projectFailed(any(), any());
    verify(coordinator, never()).recover(any());
    verify(orders, never()).cancel(any(), any());
    verify(delivery, never()).deliver(any());
  }

  /** User live GET 缺失、Feign 异常、ledgerApplied 和 branch 不符均保持未知。 */
  @Test
  void incompleteUserProofNeverProjectsFailed() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(execution("ROLLEDBACK"));
    when(states.cancelledProof(payment)).thenReturn(proof());
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(null)
        .thenThrow(feignFailure())
        .thenReturn(ApiResponse.ok(userState(true, 31L)))
        .thenReturn(ApiResponse.ok(userState(false, 32L)));

    for (int attempt = 0; attempt < 4; attempt++) {
      assertEquals(
          202, assertThrows(BizException.class, () -> service.failure(payment)).getHttpStatus());
    }

    verify(states, never()).projectFailed(any(), any());
    verify(cancelService, never()).request(any());
  }

  /** User live GET 金额为空、格式错误或状态未知时不得投影 FAILED。 */
  @Test
  void malformedOrUnknownUserProofRemainsPending() {
    PayVO payment = payment();
    when(states.execution(payment.payNo)).thenReturn(execution("ROLLEDBACK"));
    when(states.cancelledProof(payment)).thenReturn(proof());
    UserTccStateVO malformed = userState(false, 31L);
    malformed.amount = "not-a-number";
    UserTccStateVO unknown = userState(false, 31L);
    unknown.state = "UNKNOWN";
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(malformed))
        .thenReturn(ApiResponse.ok(unknown))
        .thenReturn(new ApiResponse<>());

    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals(
          202, assertThrows(BizException.class, () -> service.failure(payment)).getHttpStatus());
    }
    verify(states, never()).projectFailed(any(), any());
  }

  /** SUCCESS/REFUNDED 返回既有 409，FAILED 按幂等语义不调用任何远程服务。 */
  @Test
  void publicTerminalStatusesDoNotStartRollback() {
    for (String status : new String[] {"SUCCESS", "REFUNDED"}) {
      BizException done = assertThrows(BizException.class, () -> service.failure(payment(status)));
      assertEquals(409, done.getHttpStatus());
    }
    service.failure(payment("FAILED"));

    verify(cancelService, never()).request(any());
    verify(coordinator, never()).execute(any());
    verify(coordinator, never()).recover(any());
    verify(orders, never()).cancel(any(), any());
    verify(orders, never()).paid(any(), any());
    verify(orders, never()).paymentState(any(), any());
    verify(delivery, never()).deliver(any());
  }

  private PayVO payment() {
    return payment("PENDING");
  }

  private PayVO payment(String status) {
    PayVO payment = new PayVO();
    payment.payNo = "pay-rollback-test";
    payment.userId = 7L;
    payment.orderNo = "order-rollback-test";
    payment.amount = new BigDecimal("10.00");
    payment.status = status;
    return payment;
  }

  private PayTccExecutionPO execution(String state) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = "pay-rollback-test";
    execution.xid = "xid-rollback-test";
    execution.userId = 7L;
    execution.orderNo = "order-rollback-test";
    execution.amount = new BigDecimal("10.00");
    execution.operationKey = "tcc-debit:pay-rollback-test";
    execution.ledgerKey = "pay-rollback-test";
    execution.state = state;
    execution.decision = "ROLLBACK";
    return execution;
  }

  private PayTccDispatchVO proof() {
    PayTccDispatchVO proof = new PayTccDispatchVO();
    proof.payNo = "pay-rollback-test";
    proof.xid = "xid-rollback-test";
    proof.userId = 7L;
    proof.orderNo = "order-rollback-test";
    proof.amount = "10.00";
    proof.operationKey = "tcc-debit:pay-rollback-test";
    proof.ledgerKey = "pay-rollback-test";
    proof.userBranchId = 31L;
    proof.state = "CANCELLED";
    return proof;
  }

  private UserTccStateVO userState(boolean ledgerApplied, Long branchId) {
    UserTccStateVO state = new UserTccStateVO();
    state.userId = 7L;
    state.payNo = "pay-rollback-test";
    state.orderNo = "order-rollback-test";
    state.amount = "10.00";
    state.operationKey = "tcc-debit:pay-rollback-test";
    state.ledgerKey = "pay-rollback-test";
    state.xid = "xid-rollback-test";
    state.branchId = branchId;
    state.state = "CANCELLED";
    state.ledgerApplied = ledgerApplied;
    return state;
  }

  private FeignException feignFailure() {
    Request request =
        Request.create(
            Request.HttpMethod.GET,
            "/state",
            Collections.emptyMap(),
            null,
            StandardCharsets.UTF_8,
            null);
    return FeignException.errorStatus(
        "User state", Response.builder().status(503).request(request).build());
  }
}
