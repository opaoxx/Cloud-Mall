package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayCallbackController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.impl.PayCallbackServiceImpl;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 原回调与组合入口复用相同断言；只冻结入口顺序，不复制资金或Receipt算法。 */
class PayCallbackContractTest {
  /** 原支付读取。 */
  private final PayRecordMapper records = mock(PayRecordMapper.class);

  /** 原意图读取。 */
  private final PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);

  /** 原Legacy成功编排。 */
  private final PayConsistencyService legacy = mock(PayConsistencyService.class);

  /** 原Legacy失败授权。 */
  private final PayLegacyFailureService failure = mock(PayLegacyFailureService.class);

  /** 原TCC编排。 */
  private final PayTccPaymentService tcc = mock(PayTccPaymentService.class);

  /** TCC依赖提供器。 */
  private final ObjectProvider<PayTccPaymentService> provider = mock(ObjectProvider.class);

  /** 独立绑定/完成观察点。 */
  private final PayCallbackReceiptService receipts = mock(PayCallbackReceiptService.class);

  /** 原路由指标。 */
  private final PayProtocolMetrics metrics = mock(PayProtocolMetrics.class);

  /** 实际HTTP入口。 */
  private PayCallbackController controller;

  @BeforeEach
  void prepare() {
    controller =
        new PayCallbackController(
            new PayCallbackServiceImpl(
                records,
                intents,
                failure,
                legacy,
                provider,
                receipts,
                metrics,
                new com.cloudmall.pay.service.impl.PayWriteMonitor(),
                "token"));
    when(provider.getIfAvailable()).thenReturn(tcc);
  }

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void credentialPrecedesParametersAndNoUserSessionIsRequired() {
    for (String token : Arrays.asList(null, "", "wrong")) {
      assertEquals(
          403,
          assertThrows(BizException.class, () -> controller.callback(token, null)).getHttpStatus());
    }
    assertEquals(
        400,
        assertThrows(BizException.class, () -> controller.callback("token", null)).getHttpStatus());
    for (int index = 0; index < 4; index++) {
      var request = request(true);
      if (index == 0) request.payNo = null;
      if (index == 1) request.payNo = " ";
      if (index == 2) request.amount = null;
      if (index == 3) request.userId = null;
      assertEquals(
          400,
          assertThrows(BizException.class, () -> controller.callback("token", request))
              .getHttpStatus());
    }
    verifyNoInteractions(records, intents, receipts, metrics, legacy, failure, provider, tcc);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("SUCCESS", "LEGACY")));
    AuthContext.set(99L, "USER");
    assertEquals(7L, ((PayVO) controller.callback("token", request(true)).data).userId);
  }

  @Test
  void ownershipThenModeThenAmountChecksAllPrecedeClaimAndMetric() {
    var request = request(true);
    assertCode(ErrorCodes.NOT_FOUND, request);
    var row = payment("PENDING", "LEGACY");
    row.userId = 8L;
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(row));
    assertCode(ErrorCodes.FORBIDDEN, request);
    row.userId = 7L;
    row.debitMode = "invalid";
    request.amount = new BigDecimal("20.00");
    assertCode("PAY_DEBIT_MODE_UNAVAILABLE", request);
    row.debitMode = "LEGACY";
    assertCode(ErrorCodes.PAY_MISMATCH, request);
    verifyNoInteractions(receipts, metrics, legacy, failure, provider, tcc);
  }

  @Test
  void callbackIdFallbackAndTerminalSuccessStillClaimAndCompleteWithoutOrchestration() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("SUCCESS", "LEGACY")));
    for (String callbackId : Arrays.asList(null, "", " \t", " exact-id ")) {
      clearInvocations(records, intents, receipts, metrics);
      var request = request(true);
      request.callbackId = callbackId;
      String expected = callbackId == null || callbackId.isBlank() ? "pay-1" : callbackId;
      assertEquals("SUCCESS", ((PayVO) controller.callback("token", request).data).status);
      var sequence = inOrder(records, intents, metrics, receipts);
      sequence.verify(records, times(2)).selectByPayNo("pay-1");
      sequence.verify(intents).findByPayNo("pay-1");
      sequence
          .verify(metrics)
          .route(PayProtocolMetrics.Mode.LEGACY, PayProtocolMetrics.Entry.CALLBACK);
      sequence.verify(receipts).claim(any(), same(request), eq(expected));
      sequence.verify(receipts).complete(any(), same(request), eq(expected));
      sequence.verify(records).selectByPayNo("pay-1");
      sequence.verifyNoMoreInteractions();
    }
    verifyNoInteractions(legacy, failure, provider, tcc);
  }

  @Test
  void rejectedTerminalStatesClaimBeforeRefusingAndNeverComplete() {
    for (String state : List.of("FAILED", "REFUNDED")) {
      reset(receipts);
      doReturn(List.of(payment(state, "LEGACY"))).when(records).selectByPayNo("pay-1");
      assertCode(ErrorCodes.PAY_DONE, request(true));
      verify(receipts).claim(any(), any(), eq("callback-1"));
      verify(receipts, never()).complete(any(), any(), any());
    }
    verifyNoInteractions(legacy, failure, provider, tcc);
  }

  @Test
  void successBranchesPreserveClaimOrchestrationCompleteAndFinalReadOrder() {
    for (String mode : List.of("LEGACY", "TCC")) {
      reset(records, intents, receipts, legacy, provider, tcc);
      when(provider.getIfAvailable()).thenReturn(tcc);
      when(records.selectByPayNo("pay-1"))
          .thenReturn(
              List.of(payment("PENDING", mode)),
              List.of(payment("PENDING", mode)),
              List.of(payment("SUCCESS", mode)));
      when(legacy.initiate(any())).thenReturn("SUCCESS");
      when(tcc.pay(any())).thenReturn("SUCCESS");
      var request = request(true);
      assertEquals("SUCCESS", ((PayVO) controller.callback("token", request).data).status);
      var sequence = inOrder(records, receipts, legacy, provider, tcc);
      sequence.verify(records, times(2)).selectByPayNo("pay-1");
      sequence.verify(receipts).claim(any(), same(request), eq("callback-1"));
      if (mode.equals("LEGACY")) {
        sequence.verify(legacy).initiate(any());
        verifyNoInteractions(tcc, provider);
      } else {
        sequence.verify(provider).getIfAvailable();
        sequence.verify(tcc).pay(any());
        verifyNoInteractions(legacy);
      }
      sequence.verify(receipts).complete(any(), same(request), eq("callback-1"));
      sequence.verify(records).selectByPayNo("pay-1");
      sequence.verifyNoMoreInteractions();
    }
  }

  @Test
  void unknownOutcomesAndReceiptFailuresDoNotAdvanceLaterStages() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", "LEGACY")));
    for (String outcome : Arrays.asList(null, "UNKNOWN", "REFUND_PENDING", "REFUNDED", "FAILED")) {
      reset(receipts, legacy);
      when(legacy.initiate(any())).thenReturn(outcome);
      String expected =
          "REFUND_PENDING".equals(outcome)
              ? "PAY_REFUND_PENDING"
              : ("FAILED".equals(outcome) || "REFUNDED".equals(outcome)
                  ? "PAYMENT_FAILED"
                  : "PAY_ORDER_SYNC_UNKNOWN");
      assertCode(expected, request(true));
      verify(receipts).claim(any(), any(), any());
      verify(receipts, never()).complete(any(), any(), any());
      verify(legacy).initiate(any());
    }
    reset(receipts, legacy, records);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("SUCCESS", "LEGACY")));
    var original = new IllegalStateException("claim unknown");
    doThrow(original).when(receipts).claim(any(), any(), any());
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class, () -> controller.callback("token", request(true))));
    verifyNoInteractions(legacy);
    verify(receipts, never()).complete(any(), any(), any());
    reset(receipts, records);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("SUCCESS", "LEGACY")));
    doThrow(original).when(receipts).complete(any(), any(), any());
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class, () -> controller.callback("token", request(true))));
    verify(records, times(2)).selectByPayNo("pay-1");
  }

  @Test
  void failureBranchesCompleteUsingTheirOwnReturnedOrMutatedView() {
    for (String mode : List.of("LEGACY", "TCC")) {
      reset(records, intents, receipts, failure, provider, tcc);
      when(provider.getIfAvailable()).thenReturn(tcc);
      when(records.selectByPayNo("pay-1"))
          .thenReturn(
              List.of(payment("PENDING", mode)),
              List.of(payment("PENDING", mode)),
              List.of(payment("FAILED", mode)));
      var view = new java.util.concurrent.atomic.AtomicReference<PayVO>();
      when(failure.fail(any()))
          .thenAnswer(
              invocation -> {
                var current = new PayVO();
                current.payNo = "pay-1";
                current.status = "FAILED";
                view.set(current);
                return current;
              });
      doAnswer(
              invocation -> {
                PayVO current = invocation.getArgument(0);
                current.status = "FAILED";
                view.set(current);
                return null;
              })
          .when(tcc)
          .failure(any());
      var request = request(false);
      assertEquals("FAILED", ((PayVO) controller.callback("token", request).data).status);
      verify(receipts).complete(same(view.get()), same(request), eq("callback-1"));
      verify(records, times(3)).selectByPayNo("pay-1");
      if (mode.equals("LEGACY")) verifyNoInteractions(tcc, provider);
      else verifyNoInteractions(failure);
    }
    verifyNoInteractions(legacy);
  }

  @Test
  void httpBindingPrecedesCredentialCheckAndKeepsPayloadIdentityMoneyTimeAndNull()
      throws Exception {
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    var json = builder.build();
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new AuthInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
    mvc.perform(
            post("/api/payments/callback")
                .header("X-Internal-Callback-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
        .andExpect(status().isInternalServerError());
    mvc.perform(
            post("/api/payments/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request(true))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(records);
    var row = payment("SUCCESS", "LEGACY");
    row.paidAt = LocalDateTime.of(2026, 11, 13, 12, 30, 45, 123000000);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(row));
    var result =
        mvc.perform(
                post("/api/payments/callback")
                    .header("X-User-Id", "99")
                    .header("X-Internal-Callback-Token", "token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request(true))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value(7))
            .andExpect(jsonPath("$.data.amount").value("10.00"))
            .andExpect(jsonPath("$.data.paidAt").value("2026-11-13T12:30:45.123+08:00"))
            .andReturn();
    var response = json.readTree(result.getResponse().getContentAsByteArray());
    assertEquals(4, response.size());
    assertEquals(6, response.get("data").size());
    assertTrue(response.get("data").get("amount").isTextual());
    row.paidAt = null;
    assertNull(((PayVO) controller.callback("token", request(true)).data).paidAt);
    assertNull(AuthContext.userId());
  }

  private void assertCode(String code, PayCallbackDTO request) {
    assertEquals(
        code,
        assertThrows(BizException.class, () -> controller.callback("token", request)).getCode());
  }

  private static PayCallbackDTO request(boolean success) {
    var request = new PayCallbackDTO();
    request.payNo = "pay-1";
    request.callbackId = "callback-1";
    request.userId = 7L;
    request.amount = new BigDecimal("10.00");
    request.success = success;
    return request;
  }

  private static PayRecordPO payment(String status, String mode) {
    var row = new PayRecordPO();
    row.payNo = "pay-1";
    row.orderNo = "order-1";
    row.userId = 7L;
    row.amount = new BigDecimal("10.00");
    row.status = status;
    row.debitMode = mode;
    return row;
  }
}
