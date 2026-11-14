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
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelService;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.projection.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.projection.PayTccRollbackProjectionService;
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
  /** 原资金执行/恢复入口替身。 */
  private PayTccCoordinatorService coordinator;

  /** 本批不得调用的交付替身。 */
  private PayTccDeliveryService delivery;

  /** 本地回滚证明与投影边界替身。 */
  private PayTccPublicStateStore states;

  /** 本批不得调用的Order客户端替身。 */
  private OrderClient orders;

  /** User当前状态只读客户端替身。 */
  private UserTccClient users;

  /** 被测公开支付/失败入口。 */
  private PayTccPaymentService service;

  /** 无execution主动取消入口替身。 */
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
    service =
        new PayTccPaymentService(
            coordinator,
            delivery,
            states,
            orders,
            new PayTccRollbackProjectionService(states, users, "token"));
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

  @Test
  void disabledAbortDoesNotChooseUndecidedExecution() {
    PayVO payment = payment();
    PayTccExecutionPO execution = execution("RUNNING");
    execution.decision = "NONE";
    when(states.execution(payment.payNo)).thenReturn(execution);
    assertEquals(
        202, assertThrows(BizException.class, () -> service.failure(payment)).getHttpStatus());
    org.mockito.Mockito.verifyNoInteractions(coordinator, delivery, orders, users, cancelService);
  }

  /** 真实JSON缺字段/null两入口均pending；原null用户/坏状态门也保持。 */
  @Test
  void missingLedgerEvidenceNeverReachesEitherProjection() throws Exception {
    for (String variant :
        java.util.List.of("missing", "null", "user-null", "state-null", "unknown"))
      for (boolean claimed : java.util.List.of(false, true)) {
        setUp();
        var payment = payment();
        var decoded = decodedRollbackLedgerState(variant);
        if (variant.equals("missing") || variant.equals("null")) {
          org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerApplied);
          org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerAppliedPresent);
        }
        when(states.cancelledProof(payment)).thenReturn(proof());
        when(users.state(any(), any(), any(), any(), any(), any()))
            .thenReturn(ApiResponse.ok(decoded));
        var projection = new PayTccRollbackProjectionService(states, users, "token");
        var failure =
            assertThrows(
                BizException.class,
                () -> {
                  if (claimed) projection.projectClaimed(payment, "worker");
                  else projection.project(payment);
                });
        assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
        assertEquals(202, failure.getHttpStatus());
        assertEquals("订单或资金结果待确认，请保留原支付单", failure.getMessage());
        verify(states).cancelledProof(payment);
        verify(users)
            .state(
                payment.userId,
                proof().operationKey,
                payment.userId,
                "cloud-mall-pay",
                "token",
                proof().xid);
        verify(states, never()).projectFailed(any(), any());
        verify(states, never()).projectFailedClaimed(any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(coordinator, delivery, orders, cancelService);
      }
  }

  /** JSON显式false保持两入口返回，显式true保持拒绝，presence不进入JSON。 */
  @Test
  void explicitLedgerBooleanKeepsBothProjectionContracts() throws Exception {
    for (String variant : java.util.List.of("false", "true"))
      for (boolean claimed : java.util.List.of(false, true)) {
        setUp();
        var payment = payment();
        var decoded = decodedRollbackLedgerState(variant);
        org.junit.jupiter.api.Assertions.assertTrue(decoded.ledgerAppliedPresent);
        assertEquals(variant.equals("true"), decoded.ledgerApplied);
        org.junit.jupiter.api.Assertions.assertFalse(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(decoded)
                .contains("ledgerAppliedPresent"));
        when(states.cancelledProof(payment)).thenReturn(proof());
        when(users.state(any(), any(), any(), any(), any(), any()))
            .thenReturn(ApiResponse.ok(decoded));
        when(states.projectFailed(payment, proof().xid)).thenReturn("FAILED");
        when(states.projectFailedClaimed(payment, proof().xid, "worker")).thenReturn("FAILED");
        var projection = new PayTccRollbackProjectionService(states, users, "token");
        if (variant.equals("false")) {
          assertEquals(
              "FAILED",
              claimed ? projection.projectClaimed(payment, "worker") : projection.project(payment));
          if (claimed) verify(states).projectFailedClaimed(payment, proof().xid, "worker");
          else verify(states).projectFailed(payment, proof().xid);
        } else {
          assertThrows(
              BizException.class,
              () -> {
                if (claimed) projection.projectClaimed(payment, "worker");
                else projection.project(payment);
              });
          verify(states, never()).projectFailed(any(), any());
          verify(states, never()).projectFailedClaimed(any(), any(), any());
        }
      }
  }

  /** 公开支付/失败入口收到缺失或null无扣款证据时保留PENDING，不再报FAILED。 */
  @Test
  void incompleteLedgerProofKeepsPublicPaymentEntryPending() throws Exception {
    for (String variant : java.util.List.of("missing", "null"))
      for (String entry : java.util.List.of("failure", "pay")) {
        setUp();
        var payment = payment();
        when(states.execution(payment.payNo)).thenReturn(execution("ROLLEDBACK"));
        var recovered = new com.cloudmall.pay.domain.vo.PayTccExecutionVO();
        recovered.state = "ROLLEDBACK";
        recovered.xid = "xid-rollback-test";
        recovered.payNo = payment.payNo;
        when(coordinator.recover(payment.payNo)).thenReturn(recovered);
        when(states.cancelledProof(payment)).thenReturn(proof());
        when(users.state(any(), any(), any(), any(), any(), any()))
            .thenReturn(ApiResponse.ok(decodedRollbackLedgerState(variant)));
        assertEquals(
            202,
            assertThrows(
                    BizException.class,
                    () -> {
                      if (entry.equals("failure")) service.failure(payment);
                      else service.pay(payment);
                    })
                .getHttpStatus());
        assertEquals("PENDING", payment.status);
        verify(states, never()).projectFailed(any(), any());
        verify(states, never()).projectFailedClaimed(any(), any(), any());
        verify(coordinator, never()).execute(any());
        if (entry.equals("failure")) verify(coordinator, never()).recover(any());
        else verify(coordinator).recover(payment.payNo);
        org.mockito.Mockito.verifyNoInteractions(delivery, orders, cancelService);
      }
  }

  /** 以实际Jackson解码显式布尔/缺失/null，不手工伪造presence标志。 */
  private UserTccStateVO decodedRollbackLedgerState(String variant)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (variant.equals("user-null")) return null;
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(userState(false, 31L));
    if (variant.equals("missing")) json.remove("ledgerApplied");
    else if (variant.equals("null")) json.putNull("ledgerApplied");
    else json.put("ledgerApplied", variant.equals("true"));
    if (variant.equals("state-null")) json.putNull("state");
    if (variant.equals("unknown")) json.put("state", "UNKNOWN");
    return mapper.treeToValue(json, UserTccStateVO.class);
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
    state.setLedgerApplied(ledgerApplied);
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
