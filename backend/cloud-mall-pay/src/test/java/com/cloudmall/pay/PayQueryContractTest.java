package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayQueryController;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayQueryServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 支付查询的原HTTP行为；同一组断言先验证旧入口，再验证职责拆分后的入口。 */
class PayQueryContractTest {
  /** 本地支付查询观察点，不连接真实数据库或远端服务。 */
  private final PayRecordMapper payments = mock(PayRecordMapper.class);

  /** 使用公共金额序列化规则检查实际响应字节。 */
  private final ObjectMapper json = jsonMapper();

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void authenticationFailsBeforeAnyDatabaseAccess() throws Exception {
    MockMvc mvc = mvc();
    mvc.perform(get("/api/payments/orders/order-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(ErrorCodes.UNAUTHORIZED))
        .andExpect(jsonPath("$.message").value("请先登录"));
    mvc.perform(get("/api/payments/orders/order-1").header("X-User-Id", "invalid"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(payments);
    assertNull(AuthContext.userId());
  }

  @Test
  void returnsFirstRowInMapperOrderWithOriginalMoneyTimeAndEnvelope() throws Exception {
    PayRecordPO first = payment("pay-first", "SUCCESS", "LEGACY");
    first.amount = new BigDecimal("1234567890123456.70");
    first.paidAt = LocalDateTime.of(2026, 11, 13, 9, 8, 7, 123000000);
    when(payments.selectByOrderNoAndUserId("order-1", 7L))
        .thenAnswer(
            invocation -> {
              assertEquals(7L, AuthContext.userId());
              return List.of(first, payment("pay-second", "PENDING", "TCC"));
            });

    JsonNode response =
        response(
            mvc()
                .perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andReturn());
    assertEquals(Set.of("code", "message", "data", "requestId"), fields(response));
    assertEquals("0", response.get("code").asText());
    assertEquals("OK", response.get("message").asText());
    assertFalse(response.get("requestId").asText().isBlank());
    JsonNode data = response.get("data");
    assertEquals(Set.of("payNo", "orderNo", "userId", "amount", "status", "paidAt"), fields(data));
    assertEquals("pay-first", data.get("payNo").asText());
    assertEquals("order-1", data.get("orderNo").asText());
    assertEquals(7L, data.get("userId").longValue());
    assertTrue(data.get("amount").isTextual());
    assertEquals("1234567890123456.70", data.get("amount").asText());
    assertEquals("SUCCESS", data.get("status").asText());
    assertEquals("2026-11-13T09:08:07.123+08:00", data.get("paidAt").asText());
    verify(payments).selectByOrderNoAndUserId("order-1", 7L);
    verifyNoMoreInteractions(payments);
    assertNull(AuthContext.userId());
  }

  @Test
  void emptyOrOtherUsersRecordIsNotFoundRatherThanForbidden() throws Exception {
    when(payments.selectByOrderNoAndUserId("order-1", 7L))
        .thenReturn(List.of(payment("pay-owner", "PENDING", "LEGACY")));
    when(payments.selectByOrderNoAndUserId("order-1", 8L)).thenReturn(List.of());
    mvc()
        .perform(get("/api/payments/orders/order-1").header("X-User-Id", "8"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND))
        .andExpect(jsonPath("$.message").value("支付记录不存在"));
    verify(payments).selectByOrderNoAndUserId("order-1", 8L);
    verifyNoMoreInteractions(payments);
    assertNull(AuthContext.userId());
  }

  @Test
  void nullFieldsRemainPresentInResponse() throws Exception {
    when(payments.selectByOrderNoAndUserId("order-1", 7L)).thenReturn(List.of(new PayRecordPO()));
    JsonNode data =
        response(
                mvc()
                    .perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
                    .andExpect(status().isOk())
                    .andReturn())
            .get("data");
    assertEquals(Set.of("payNo", "orderNo", "userId", "amount", "status", "paidAt"), fields(data));
    data.forEach(value -> assertTrue(value.isNull()));
  }

  @Test
  void allStatesAndModesAreReturnedWithoutDrivingPaymentOrValidatingMode() throws Exception {
    MockMvc mvc = mvc();
    for (String state :
        List.of(
            "PENDING",
            "SUCCESS",
            "FAILED",
            "ORDER_SYNC_UNKNOWN",
            "REFUND_PENDING",
            "REFUNDED",
            "FUTURE_STATE")) {
      for (String mode : Arrays.asList("LEGACY", "TCC", null, "invalid")) {
        doReturn(List.of(payment("pay-1", state, mode)))
            .when(payments)
            .selectByOrderNoAndUserId("order-1", 7L);
        mvc.perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value(state))
            .andExpect(jsonPath("$.data.amount").value("10.00"))
            .andExpect(jsonPath("$.data.debitMode").doesNotExist());
      }
    }
    verify(payments, times(28)).selectByOrderNoAndUserId("order-1", 7L);
    verifyNoMoreInteractions(payments);
  }

  @Test
  void invalidLaterRowStillFailsInsteadOfReturningFirstRowEarly() throws Exception {
    when(payments.selectByOrderNoAndUserId("order-1", 7L))
        .thenReturn(Arrays.asList(payment("pay-first", "PENDING", "LEGACY"), null));
    MvcResult result =
        mvc()
            .perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(ErrorCodes.INTERNAL))
            .andReturn();
    assertInstanceOf(NullPointerException.class, result.getResolvedException());
    assertNull(AuthContext.userId());
  }

  @Test
  void mapperFailureIsNotReinterpretedAsMissingOrSuccessfulPayment() throws Exception {
    var failure = new IllegalStateException("controlled mapper failure");
    when(payments.selectByOrderNoAndUserId("order-1", 7L)).thenThrow(failure);
    MvcResult result =
        mvc()
            .perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(ErrorCodes.INTERNAL))
            .andReturn();
    assertSame(failure, result.getResolvedException());
    verify(payments).selectByOrderNoAndUserId("order-1", 7L);
    verifyNoMoreInteractions(payments);
    assertNull(AuthContext.userId());
  }

  private MockMvc mvc() {
    var controller = new PayQueryController(new PayQueryServiceImpl(payments));
    return MockMvcBuilders.standaloneSetup(controller)
        .addInterceptors(new AuthInterceptor())
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
        .build();
  }

  private static ObjectMapper jsonMapper() {
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    return builder.build();
  }

  private JsonNode response(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsByteArray());
  }

  private static Set<String> fields(JsonNode object) {
    Set<String> names = new HashSet<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static PayRecordPO payment(String payNo, String status, String mode) {
    var payment = new PayRecordPO();
    payment.payNo = payNo;
    payment.orderNo = "order-1";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = status;
    payment.debitMode = mode;
    return payment;
  }
}
