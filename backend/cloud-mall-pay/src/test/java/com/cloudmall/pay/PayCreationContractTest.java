package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.controller.PayCreationController;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayCreationServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 同一行为矩阵先验证旧创建入口，再验证新组合入口；不触发实际资金操作。 */
class PayCreationContractTest {
  /** 保留尾空格的原幂等键。 */
  private static final String KEY = "key-original ";

  /** 该用户与原键对应的固定支付单号，防止trim或hash格式变化。 */
  private static final String PAY_NO = "37d99593bf8bbe493d1c22be46b28a070ccb49c4500e47f2";

  /** 支付表访问观察点。 */
  private final PayRecordMapper records = mock(PayRecordMapper.class);

  /** 原订单详情调用观察点。 */
  private final OrderClient orders = mock(OrderClient.class);

  /** 仅新支付选择协议的观察点。 */
  private final PayDebitModeConfiguration mode = mock(PayDebitModeConfiguration.class);

  /** 被测实际HTTP入口。 */
  private PayCreationController controller;

  @BeforeEach
  void prepare() {
    controller =
        new PayCreationController(
            new PayCreationServiceImpl(orders, records, mode, new PayWriteMonitor()));
    AuthContext.set(7L, "USER");
  }

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void identityPrecedesParameterChecksAndInvalidInputsDoNotReadData() {
    AuthContext.clear();
    assertEquals(
        401, assertThrows(BizException.class, () -> controller.create(null, null)).getHttpStatus());
    AuthContext.set(7L, "USER");
    for (String key : Arrays.asList(null, "", "  ")) {
      assertCode(ErrorCodes.INVALID, () -> controller.create(key, request("order-1", "10.00")));
    }
    assertCode(ErrorCodes.INVALID, () -> controller.create(KEY, null));
    assertCode(ErrorCodes.INVALID, () -> controller.create(KEY, request(null, "10.00")));
    for (String amount : Arrays.asList(null, "0", "-0.01")) {
      assertCode(ErrorCodes.INVALID, () -> controller.create(KEY, request("order-1", amount)));
    }
    verifyNoInteractions(records, orders, mode);
  }

  @Test
  void matchingKeyReturnsOriginalRecordBeforeOrderAndModeEvenWhenAmountScaleDiffers() {
    PayRecordPO original = payment(PAY_NO, "order-1", 7L, "10.00");
    original.status = "SUCCESS";
    original.debitMode = "TCC";
    when(records.selectByPayNo(PAY_NO)).thenReturn(List.of(original));
    PayVO result = (PayVO) controller.create(KEY, request("order-1", "10.000")).data;
    assertEquals(PAY_NO, result.payNo);
    assertEquals("SUCCESS", result.status);
    assertEquals(new BigDecimal("10.00"), result.amount);
    verify(records).selectByPayNo(PAY_NO);
    verifyNoMoreInteractions(records);
    verifyNoInteractions(orders, mode);
  }

  @Test
  void keyCannotBeReusedForDifferentOrderUserOrAmount() {
    for (PayRecordPO original :
        List.of(
            payment(PAY_NO, "other", 7L, "10.00"),
            payment(PAY_NO, "order-1", 8L, "10.00"),
            payment(PAY_NO, "order-1", 7L, "10.01"))) {
      doReturn(List.of(original)).when(records).selectByPayNo(PAY_NO);
      BizException conflict =
          assertCode(
              ErrorCodes.DUPLICATE, () -> controller.create(KEY, request("order-1", "10.00")));
      assertEquals(409, conflict.getHttpStatus());
      assertEquals("Idempotency-Key已用于其他支付请求", conflict.getMessage());
    }
    verify(records, times(3)).selectByPayNo(PAY_NO);
    verifyNoMoreInteractions(records);
    verifyNoInteractions(orders, mode);
  }

  @Test
  void allRowsAreMappedBeforeKeyedOrOrderReplay() {
    doReturn(Arrays.asList(payment(PAY_NO, "order-1", 7L, "10.00"), null))
        .when(records)
        .selectByPayNo(PAY_NO);
    assertThrows(
        NullPointerException.class, () -> controller.create(KEY, request("order-1", "10.00")));
    verifyNoInteractions(orders, mode);
    doReturn(List.of()).when(records).selectByPayNo(PAY_NO);
    validOrder("order-1");
    doReturn(Arrays.asList(payment("previous", "order-1", 7L, "10.00"), null))
        .when(records)
        .selectByOrderNo("order-1");
    assertThrows(
        NullPointerException.class, () -> controller.create(KEY, request("order-1", "10.00")));
    verify(records, never()).insert(any(PayRecordPO.class));
    verifyNoInteractions(mode);
  }

  @Test
  void remoteNullOwnershipStateAndAmountChecksKeepTheirOriginalPrecedence() {
    when(orders.get("order-1", 7L)).thenReturn(null);
    assertCode(ErrorCodes.NOT_FOUND, () -> controller.create(KEY, request("order-1", "10.00")));
    doReturn(ApiResponse.ok(null)).when(orders).get("order-1", 7L);
    assertCode(ErrorCodes.NOT_FOUND, () -> controller.create(KEY, request("order-1", "10.00")));
    doReturn(ApiResponse.ok(new OrderClient.OrderView("order-1", "PAID", new BigDecimal("99"), 8L)))
        .when(orders)
        .get("order-1", 7L);
    assertCode(ErrorCodes.FORBIDDEN, () -> controller.create(KEY, request("order-1", "10.00")));
    doReturn(ApiResponse.ok(new OrderClient.OrderView("order-1", "PAID", new BigDecimal("99"), 7L)))
        .when(orders)
        .get("order-1", 7L);
    assertCode(ErrorCodes.PAY_DONE, () -> controller.create(KEY, request("order-1", "10.00")));
    doReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "PENDING_PAYMENT", new BigDecimal("99"), 7L)))
        .when(orders)
        .get("order-1", 7L);
    assertCode(ErrorCodes.PAY_MISMATCH, () -> controller.create(KEY, request("order-1", "10.00")));
    verify(records, never()).selectByOrderNo(anyString());
    verify(records, never()).insert(any(PayRecordPO.class));
    verifyNoInteractions(mode);
  }

  @Test
  void orderReplayStillChecksRemoteFirstAndDoesNotAddValidationOfStoredReplay() {
    when(orders.get("order-1", 7L))
        .thenReturn(
            new ApiResponse<>(
                "nonzero",
                "original",
                new OrderClient.OrderView("order-1", "PENDING_PAYMENT", new BigDecimal("10"), 7L)));
    PayRecordPO original = payment("other-key", "order-1", 9L, "11.00");
    original.status = "REFUNDED";
    when(records.selectByOrderNo("order-1")).thenReturn(List.of(original));
    PayVO result = (PayVO) controller.create(KEY, request("order-1", "10.00")).data;
    assertEquals("other-key", result.payNo);
    assertEquals(9L, result.userId);
    assertEquals("REFUNDED", result.status);
    var sequence = inOrder(records, orders);
    sequence.verify(records).selectByPayNo(PAY_NO);
    sequence.verify(orders).get("order-1", 7L);
    sequence.verify(records).selectByOrderNo("order-1");
    sequence.verifyNoMoreInteractions();
    verifyNoInteractions(mode);
  }

  @Test
  void newPaymentKeepsRawKeyBlankOrderPrecisionModeAndReadAfterInsert() {
    for (String protocol : List.of("LEGACY", "TCC")) {
      reset(records, orders, mode);
      validOrder(" ");
      when(mode.newPaymentMode()).thenReturn(protocol);
      LocalDateTime before = LocalDateTime.now(ZoneOffset.ofHours(8));
      when(records.insert(any(PayRecordPO.class)))
          .thenAnswer(
              invocation -> {
                PayRecordPO row = invocation.getArgument(0);
                assertEquals(PAY_NO, row.payNo);
                assertEquals(" ", row.orderNo);
                assertEquals(7L, row.userId);
                assertEquals(new BigDecimal("10.000"), row.amount);
                assertEquals("PENDING", row.status);
                assertEquals(protocol, row.debitMode);
                assertNotNull(row.id);
                assertNull(row.paidAt);
                assertEquals(row.createdAt, row.updatedAt);
                assertFalse(row.createdAt.isBefore(before));
                assertFalse(row.createdAt.isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))));
                doReturn(List.of(row)).when(records).selectByPayNo(PAY_NO);
                return 1;
              });
      PayVO result = (PayVO) controller.create(KEY, request(" ", "10.000")).data;
      assertEquals(PAY_NO, result.payNo);
      assertEquals(new BigDecimal("10.000"), result.amount);
      assertNull(result.paidAt);
      var sequence = inOrder(records, orders, mode);
      sequence.verify(records).selectByPayNo(PAY_NO);
      sequence.verify(orders).get(" ", 7L);
      sequence.verify(records).selectByOrderNo(" ");
      sequence.verify(mode).newPaymentMode();
      sequence.verify(records).insert(any(PayRecordPO.class));
      sequence.verify(records).selectByPayNo(PAY_NO);
      sequence.verifyNoMoreInteractions();
    }
  }

  @Test
  void failuresKeepOriginalExceptionAndNeverTriggerAdditionalWork() {
    validOrder("order-1");
    var rejected = new IllegalStateException("mode rejected");
    when(mode.newPaymentMode()).thenThrow(rejected);
    assertSame(
        rejected,
        assertThrows(
            IllegalStateException.class,
            () -> controller.create(KEY, request("order-1", "10.00"))));
    verify(records, never()).insert(any(PayRecordPO.class));
    doReturn("LEGACY").when(mode).newPaymentMode();
    var insertFailure = new IllegalStateException("insert failed");
    doThrow(insertFailure).when(records).insert(any(PayRecordPO.class));
    assertSame(
        insertFailure,
        assertThrows(
            IllegalStateException.class,
            () -> controller.create(KEY, request("order-1", "10.00"))));
    doReturn(1).when(records).insert(any(PayRecordPO.class));
    assertCode(ErrorCodes.NOT_FOUND, () -> controller.create(KEY, request("order-1", "10.00")));
    verify(orders, never()).paid(anyString(), anyLong());
    verify(orders, never()).cancel(anyString(), anyLong());
  }

  @Test
  void httpHeaderBindingAuthenticationAndResponseShapeStayCompatible() throws Exception {
    AuthContext.clear();
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    var json = builder.build();
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new AuthInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
    String body = "{\"orderNo\":\"order-1\",\"payAmount\":\"10.00\"}";
    mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCodes.INVALID));
    mvc.perform(
            post("/api/payments")
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(records, orders, mode);
    when(records.selectByPayNo(PAY_NO))
        .thenReturn(List.of(payment(PAY_NO, "order-1", 7L, "10.00")));
    var response =
        mvc.perform(
                post("/api/payments")
                    .header("X-User-Id", "7")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.amount").value("10.00"))
            .andReturn();
    var data = json.readTree(response.getResponse().getContentAsByteArray()).get("data");
    assertEquals(6, data.size());
    assertTrue(data.get("amount").isTextual());
    assertTrue(data.get("paidAt").isNull());
    assertNull(AuthContext.userId());
  }

  private void validOrder(String orderNo) {
    when(orders.get(orderNo, 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    orderNo, "PENDING_PAYMENT", new BigDecimal("10.00"), 7L)));
  }

  private static BizException assertCode(
      String code, org.junit.jupiter.api.function.Executable work) {
    BizException failure = assertThrows(BizException.class, work);
    assertEquals(code, failure.getCode());
    return failure;
  }

  private static PayCreateDTO request(String orderNo, String amount) {
    var request = new PayCreateDTO();
    request.orderNo = orderNo;
    request.payAmount = amount == null ? null : new BigDecimal(amount);
    return request;
  }

  private static PayRecordPO payment(String payNo, String orderNo, long userId, String amount) {
    var payment = new PayRecordPO();
    payment.payNo = payNo;
    payment.orderNo = orderNo;
    payment.userId = userId;
    payment.amount = new BigDecimal(amount);
    payment.status = "PENDING";
    return payment;
  }
}
