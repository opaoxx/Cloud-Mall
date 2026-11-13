package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PayCallbackController;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.PayCallbackServiceImpl;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Legacy 失败入口的只读终态分类、退款中语义及意图完整性。 */
class PayLegacyFailureOutcomeTest {
  /** 原支付记录。 */
  private final PayRecordMapper payments = mock(PayRecordMapper.class);

  /** 原 Legacy 扣款意图。 */
  private final PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);

  /** 被禁止重复调用的订单端。 */
  private final OrderClient orders = mock(OrderClient.class);

  /** 被禁止重复扣款/退款的用户端。 */
  private final UserClient users = mock(UserClient.class);

  /** 回调预绑定及完成标记。 */
  private final PayCallbackReceiptService receipts = mock(PayCallbackReceiptService.class);

  /** 公开失败入口。 */
  private PayCallbackController service;

  /** 新失败入口，复用原底层授权实例。 */
  private PayFailureController failureController;

  /** 每个案例独立的真实结构支付视图。 */
  private PayRecordPO payment;

  @BeforeEach
  void prepare() {
    var legacyFailure =
        new com.cloudmall.pay.service.impl.PayLegacyFailureService(payments, intents, orders);
    org.springframework.beans.factory.ObjectProvider<
            com.cloudmall.pay.service.impl.PayTccPaymentService>
        tcc = mock(org.springframework.beans.factory.ObjectProvider.class);
    service =
        new PayCallbackController(
            new PayCallbackServiceImpl(
                payments,
                intents,
                legacyFailure,
                mock(com.cloudmall.pay.service.impl.PayConsistencyService.class),
                tcc,
                receipts,
                com.cloudmall.pay.service.PayProtocolMetrics.noop(),
                new com.cloudmall.pay.service.impl.PayWriteMonitor(),
                "token"));
    failureController =
        new PayFailureController(
            new PayFailureServiceImpl(
                payments,
                intents,
                legacyFailure,
                tcc,
                com.cloudmall.pay.service.PayProtocolMetrics.noop()));
    payment = new PayRecordPO();
    payment.payNo = "legacy-pay";
    payment.orderNo = "legacy-order";
    payment.userId = 7L;
    payment.amount = BigDecimal.TEN;
    payment.debitMode = "LEGACY";
    when(payments.selectByPayNo(payment.payNo)).thenReturn(List.of(payment));
    when(payments.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(orders.cancel(any(), any())).thenReturn(ApiResponse.ok(null));
    AuthContext.set(7L, "USER");
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @ParameterizedTest
  @CsvSource({
    "REFUND_PENDING,REFUND_PENDING,202,PAY_REFUND_PENDING",
    "REFUNDED,REFUNDED,409,PAY_ALREADY_PROCESSED",
    "SUCCESS,SUCCESS,409,PAY_ALREADY_PROCESSED",
    "FAILED,missing,200,0",
    "FAILED,FAILED,200,0",
    "FAILED,INIT,202,PAY_ORDER_SYNC_UNKNOWN",
    "ORDER_SYNC_UNKNOWN,missing,202,PAY_ORDER_SYNC_UNKNOWN"
  })
  void mockFailureClassifiesPersistedResultWithoutSideEffects(
      String status, String intentStatus, int http, String code) {
    arrange(status, intentStatus);
    assertResult(() -> failureController.fail(payment.payNo), http, code);
    verifyNoInteractions(users);
    verify(orders, never()).cancel(any(), any());
    verify(payments, never()).markFailed(any(), any());
  }

  @ParameterizedTest
  @CsvSource({
    "REFUND_PENDING,REFUND_PENDING,202,PAY_REFUND_PENDING",
    "REFUNDED,REFUNDED,409,PAY_ALREADY_PROCESSED",
    "FAILED,missing,200,0",
    "FAILED,FAILED,200,0",
    "FAILED,INIT,202,PAY_ORDER_SYNC_UNKNOWN",
    "ORDER_SYNC_UNKNOWN,missing,202,PAY_ORDER_SYNC_UNKNOWN"
  })
  void callbackOnlyCompletesActualFailedResult(
      String status, String intentStatus, int http, String code) {
    arrange(status, intentStatus);
    assertResult(() -> service.callback("token", callback()), http, code);
    verify(receipts).claim(any(), any(), eq("failure"));
    if (http == 200) verify(receipts).complete(any(), any(), eq("failure"));
    else verify(receipts, never()).complete(any(), any(), any());
    verify(orders, never()).cancel(any(), any());
    verifyNoInteractions(users);
  }

  @Test
  void mismatchedFailedIntentCannotAuthorizeTerminalReplay() {
    PayDebitIntentPO intent = arrange("FAILED", "FAILED");
    intent.amount = BigDecimal.ONE;
    assertResult(() -> failureController.fail(payment.payNo), 503, "PAY_DEBIT_MODE_UNAVAILABLE");
    assertResult(() -> service.callback("token", callback()), 503, "PAY_DEBIT_MODE_UNAVAILABLE");
    verify(orders, never()).cancel(any(), any());
    verify(receipts, never()).complete(any(), any(), any());
  }

  @Test
  void unattemptedPendingPaymentStillUsesExistingCancellation() {
    arrange("PENDING", "missing");
    when(payments.markFailed(any(), any()))
        .thenAnswer(
            invocation -> {
              payment.status = "FAILED";
              return 1;
            });
    assertResult(() -> failureController.fail(payment.payNo), 200, "0");
    verify(orders).cancel(payment.orderNo, payment.userId);
    verify(payments).markFailed(eq(payment.payNo), any());
  }

  @Test
  void matchingFailedIntentAllowsUnknownCallbackToFinish() {
    arrange("ORDER_SYNC_UNKNOWN", "FAILED");
    when(payments.markFailed(any(), any()))
        .thenAnswer(
            invocation -> {
              payment.status = "FAILED";
              return 1;
            });
    assertResult(() -> service.callback("token", callback()), 200, "0");
    verify(orders).cancel(payment.orderNo, payment.userId);
    verify(receipts).complete(any(), any(), eq("failure"));
  }

  @Test
  void cancellationErrorNeverCompletesCallbackOrWritesFailed() {
    arrange("PENDING", "missing");
    when(orders.cancel(any(), any())).thenThrow(new IllegalStateException("unavailable"));
    BizException failure =
        assertThrows(BizException.class, () -> service.callback("token", callback()));
    assertEquals(500, failure.getHttpStatus());
    verify(payments, never()).markFailed(any(), any());
    verify(receipts, never()).complete(any(), any(), any());
  }

  private PayDebitIntentPO arrange(String status, String intentStatus) {
    payment.status = status;
    PayDebitIntentPO intent = null;
    if (!"missing".equals(intentStatus)) {
      intent = new PayDebitIntentPO();
      intent.payNo = payment.payNo;
      intent.orderNo = payment.orderNo;
      intent.userId = payment.userId;
      intent.amount = payment.amount;
      intent.debitMode = "LEGACY";
      intent.status = intentStatus;
    }
    when(intents.findByPayNo(payment.payNo)).thenReturn(intent);
    when(intents.findByPayNoForUpdate(payment.payNo)).thenReturn(intent);
    return intent;
  }

  private PayCallbackDTO callback() {
    PayCallbackDTO callback = new PayCallbackDTO();
    callback.payNo = payment.payNo;
    callback.userId = payment.userId;
    callback.amount = payment.amount;
    callback.callbackId = "failure";
    callback.success = false;
    return callback;
  }

  private void assertResult(
      java.util.function.Supplier<ApiResponse<?>> request, int http, String code) {
    if (http == 200) {
      assertEquals("FAILED", ((PayVO) request.get().data).status);
    } else {
      BizException outcome = assertThrows(BizException.class, request::get);
      assertEquals(http, outcome.getHttpStatus());
      assertEquals(code, outcome.getCode());
    }
  }
}
