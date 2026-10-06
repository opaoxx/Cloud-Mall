package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import feign.Request;
import feign.RetryableException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 跨服务响应丢失、订单取消和支付崩溃后的本地余额一致性测试。 */
class PayConsistencyServiceTest {
  private PayDebitIntentMapper intentMapper;
  private PayRecordMapper payRecordMapper;
  private UserClient userClient;
  private OrderClient orderClient;
  private PayConsistencyService consistencyService;

  @BeforeEach
  void setUp() {
    intentMapper = mock(PayDebitIntentMapper.class);
    payRecordMapper = mock(PayRecordMapper.class);
    userClient = mock(UserClient.class);
    orderClient = mock(OrderClient.class);
    consistencyService =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intentMapper,
            payRecordMapper,
            userClient,
            orderClient);
    when(intentMapper.claim(eq("pay-1"), anyString(), any(), any())).thenReturn(1);
    when(intentMapper.findByPayNo("pay-1")).thenReturn(intent());
    com.cloudmall.pay.domain.po.PayRecordPO payment = new com.cloudmall.pay.domain.po.PayRecordPO();
    payment.payNo = "pay-1";
    payment.orderNo = "order-1";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    payment.debitMode = "LEGACY";
    when(payRecordMapper.selectByPayNo("pay-1")).thenReturn(java.util.List.of(payment));
    when(userClient.operation(7L, "pay-1", 7L)).thenReturn(ApiResponse.ok(appliedDebit()));
  }

  @Test
  void orderWriteTimeoutKeepsDebitAndNeverRefundsWithoutAConfirmedCancellation() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
    when(orderClient.paid("order-1", 7L)).thenThrow(timeout());

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(intentMapper)
        .recordRetry(
            eq("pay-1"),
            anyString(),
            eq("ORDER_SYNC_UNKNOWN"),
            anyString(),
            any(LocalDateTime.class),
            any(LocalDateTime.class));
    verify(userClient, never()).refund(any(), any(), any());
    verify(userClient, never()).debit(any(), any(), any());
  }

  @Test
  void confirmedCancellationRefundsTheSameDebitOnlyByItsStableKey() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(order("CANCELLED")));
    when(payRecordMapper.markRefundPending(eq("pay-1"), any())).thenReturn(1);
    when(payRecordMapper.markRefunded(eq("pay-1"), any())).thenReturn(1);
    when(userClient.refund(eq(7L), eq(7L), any()))
        .thenReturn(ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));

    String result = consistencyService.process("pay-1");

    assertEquals("REFUNDED", result);
    org.mockito.ArgumentCaptor<UserClient.RefundRequest> refundRequest =
        org.mockito.ArgumentCaptor.forClass(UserClient.RefundRequest.class);
    verify(userClient).refund(eq(7L), eq(7L), refundRequest.capture());
    assertEquals("refund:pay-1", refundRequest.getValue().refundOperationKey());
    assertEquals("pay-1", refundRequest.getValue().originalOperationKey());
    verify(intentMapper).finish(eq("pay-1"), anyString(), eq("REFUNDED"), any());
  }

  @Test
  void crashAfterDebitResumesFromLedgerWithoutDebitingAgain() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(order("PAID")));
    when(payRecordMapper.markSuccess(eq("pay-1"), any(), any())).thenReturn(1);

    String result = consistencyService.process("pay-1");

    assertEquals("SUCCESS", result);
    verify(userClient, never()).debit(any(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
    verify(intentMapper).finish(eq("pay-1"), anyString(), eq("SUCCESS"), any());
  }

  @Test
  void paidOrderDoesNotFinishPaymentUntilStockConfirmationIsDurable() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(
            new OrderClient.OrderView(
                "order-1", "PAID", new BigDecimal("10.00"), 7L, "UNKNOWN")));

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(payRecordMapper, never()).markSuccess(eq("pay-1"), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
  }

  @Test
  void cancelledOrderDoesNotRefundUntilStockRollbackIsDurable() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(
            new OrderClient.OrderView(
                "order-1", "CANCELLED", new BigDecimal("10.00"), 7L, "PENDING")));

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(userClient, never()).refund(any(), any(), any());
  }

  private static PayDebitIntentPO intent() {
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = "pay-1";
    intent.orderNo = "order-1";
    intent.userId = 7L;
    intent.amount = new BigDecimal("10.00");
    intent.status = "INIT";
    intent.attempts = 1;
    return intent;
  }

  private static UserClient.BalanceOperationView appliedDebit() {
    return new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", null);
  }

  private static OrderClient.OrderView order(String status) {
    return new OrderClient.OrderView("order-1", status, new BigDecimal("10.00"), 7L);
  }

  private static RetryableException timeout() {
    Request request =
        Request.create(
            Request.HttpMethod.POST,
            "http://cloud-mall-order/api/orders/order-1/paid",
            Map.of(),
            null,
            StandardCharsets.UTF_8);
    return new RetryableException(
        -1, "order response lost", Request.HttpMethod.POST, (java.util.Date) null, request);
  }
}
