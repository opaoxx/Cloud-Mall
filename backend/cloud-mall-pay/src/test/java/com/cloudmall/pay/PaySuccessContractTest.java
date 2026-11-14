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
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.Entry;
import com.cloudmall.pay.service.PayProtocolMetrics.Mode;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 在新旧实际成功入口复用相同断言，冻结原读取、模式、指标及结果映射顺序。 */
class PaySuccessContractTest {
  /** 原支付读取观察点。 */
  private final PayRecordMapper records = mock(PayRecordMapper.class);

  /** 持久意图读取观察点。 */
  private final PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);

  /** 原Legacy编排替身。 */
  private final PayConsistencyService legacy = mock(PayConsistencyService.class);

  /** 原TCC编排替身。 */
  private final PayTccPaymentService tcc = mock(PayTccPaymentService.class);

  /** TCC开关缺失的观察点。 */
  private final ObjectProvider<PayTccPaymentService> provider = mock(ObjectProvider.class);

  /** 原路由指标观察点。 */
  private final PayProtocolMetrics metrics = mock(PayProtocolMetrics.class);

  /** 被测实际入口。 */
  private PaySuccessController controller;

  @BeforeEach
  void prepare() {
    controller =
        new PaySuccessController(
            new PaySuccessServiceImpl(
                records, intents, legacy, provider, metrics, new PayWriteMonitor()));
    when(provider.getIfAvailable()).thenReturn(tcc);
    AuthContext.set(7L, "USER");
  }

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void identityMissingRecordAndOwnershipPrecedeProtocolAndMetrics() {
    AuthContext.clear();
    failure(401, ErrorCodes.UNAUTHORIZED);
    verifyNoInteractions(records, intents, metrics, legacy, provider, tcc);
    AuthContext.set(7L, "USER");
    when(records.selectByPayNo("pay-1")).thenReturn(List.of());
    failure(404, ErrorCodes.NOT_FOUND);
    var other = payment("PENDING", "TCC");
    other.userId = 8L;
    doReturn(List.of(other)).when(records).selectByPayNo("pay-1");
    failure(403, ErrorCodes.FORBIDDEN);
    verifyNoInteractions(intents, metrics, legacy, provider, tcc);
  }

  @Test
  void fullFirstMappingAndExactSecondReadCardinalityRemainRequired() {
    var row = payment("PENDING", "LEGACY");
    doReturn(Arrays.asList(row, null)).when(records).selectByPayNo("pay-1");
    assertThrows(NullPointerException.class, () -> controller.success("pay-1"));
    for (List<PayRecordPO> second : List.of(List.<PayRecordPO>of(), List.of(row, row))) {
      doReturn(List.of(row), second).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    verifyNoInteractions(intents, metrics, legacy, provider, tcc);
  }

  @Test
  void invalidPersistedModesOrIntentConflictsFailEvenForSuccessfulPayment() {
    for (String mode : Arrays.asList(null, "", "legacy", "LEGACY ", "UNKNOWN")) {
      doReturn(List.of(payment("SUCCESS", mode))).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    for (String mode : Arrays.asList(null, "", "legacy", "LEGACY ", "TCC")) {
      var intent = new PayDebitIntentPO();
      intent.debitMode = mode;
      doReturn(intent).when(intents).findByPayNo("pay-1");
      doReturn(List.of(payment("SUCCESS", "LEGACY"))).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    doReturn(List.of(payment("SUCCESS", "TCC"))).when(records).selectByPayNo("pay-1");
    failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    verifyNoInteractions(metrics, legacy, provider, tcc);
  }

  @Test
  void terminalStateUsesFirstViewOnlyAfterModeValidationAndMetric() {
    for (String mode : List.of("LEGACY", "TCC")) {
      for (String state : List.of("SUCCESS", "FAILED", "REFUNDED")) {
        reset(records, intents, metrics);
        var first = payment(state, mode);
        doReturn(List.of(first), List.of(payment("PENDING", mode)))
            .when(records)
            .selectByPayNo("pay-1");
        if (state.equals("SUCCESS")) {
          assertEquals("SUCCESS", ((PayVO) controller.success("pay-1").data).status);
        } else {
          assertEquals("支付已处理", failure(409, ErrorCodes.PAY_DONE).getMessage());
        }
        var order = inOrder(records, intents, metrics);
        order.verify(records, times(2)).selectByPayNo("pay-1");
        order.verify(intents).findByPayNo("pay-1");
        order.verify(metrics).route(PayProtocolMetrics.mode(mode), Entry.MOCK_SUCCESS);
        order.verifyNoMoreInteractions();
      }
    }
    verifyNoInteractions(legacy, provider, tcc);
  }

  @Test
  void eachProtocolCallsOnlyItsOriginalOrchestratorAndMapsAllOutcomes() {
    for (String mode : List.of("LEGACY", "TCC")) {
      for (String outcome :
          Arrays.asList("SUCCESS", "FAILED", "REFUNDED", "REFUND_PENDING", "UNKNOWN", null)) {
        reset(records, intents, metrics, legacy, provider, tcc);
        when(provider.getIfAvailable()).thenReturn(tcc);
        when(records.selectByPayNo("pay-1"))
            .thenReturn(
                List.of(payment("PENDING", mode)),
                List.of(payment("PENDING", mode)),
                List.of(payment("OBSERVED_STATE", mode)));
        when(legacy.initiate(any())).thenReturn(outcome);
        when(tcc.pay(any())).thenReturn(outcome);
        if ("SUCCESS".equals(outcome)) {
          assertEquals("OBSERVED_STATE", ((PayVO) controller.success("pay-1").data).status);
        } else if ("FAILED".equals(outcome) || "REFUNDED".equals(outcome)) {
          assertEquals("订单未完成支付，余额已按账本处理", failure(409, "PAYMENT_FAILED").getMessage());
        } else if ("REFUND_PENDING".equals(outcome)) {
          assertEquals("订单已取消，余额退款处理中", failure(202, "PAY_REFUND_PENDING").getMessage());
        } else {
          assertEquals("扣款或订单结果待确认", failure(202, "PAY_ORDER_SYNC_UNKNOWN").getMessage());
        }
        verify(metrics).route(PayProtocolMetrics.mode(mode), Entry.MOCK_SUCCESS);
        verify(records, times("SUCCESS".equals(outcome) ? 3 : 2)).selectByPayNo("pay-1");
        verifyNoMoreInteractions(records);
        if (mode.equals("LEGACY")) {
          verify(legacy).initiate(any());
          verifyNoMoreInteractions(legacy);
          verifyNoInteractions(provider, tcc);
        } else {
          verify(provider).getIfAvailable();
          verify(tcc).pay(any());
          verifyNoMoreInteractions(provider, tcc);
          verifyNoInteractions(legacy);
        }
      }
    }
  }

  @Test
  void missingTccEntryDoesNotFallBackAndOriginalFailureIsNotRetried() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", "TCC")));
    doReturn(null).when(provider).getIfAvailable();
    failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    verify(metrics).route(Mode.TCC, Entry.MOCK_SUCCESS);
    verifyNoInteractions(legacy, tcc);
    doReturn(List.of(payment("PENDING", "LEGACY"))).when(records).selectByPayNo("pay-1");
    var original = new IllegalStateException("original unknown outcome");
    when(legacy.initiate(any())).thenThrow(original);
    assertSame(
        original, assertThrows(IllegalStateException.class, () -> controller.success("pay-1")));
    verify(legacy).initiate(any());
  }

  @Test
  void successfulOrchestratorStillRequiresOriginalReadBackWithoutRetryOrCompensation() {
    when(records.selectByPayNo("pay-1"))
        .thenReturn(
            List.of(payment("PENDING", "LEGACY")),
            List.of(payment("PENDING", "LEGACY")),
            List.of());
    when(legacy.initiate(any())).thenReturn("SUCCESS");
    failure(404, ErrorCodes.NOT_FOUND);
    verify(legacy).initiate(any());
    verifyNoMoreInteractions(legacy);
    verify(records, times(3)).selectByPayNo("pay-1");
    verifyNoMoreInteractions(records);
    verifyNoInteractions(provider, tcc);
  }

  @Test
  void httpKeepsResponseFieldsMoneyTimeNullsAnd503NoStore() throws Exception {
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
    mvc.perform(post("/api/payments/pay-1/mock-success")).andExpect(status().isUnauthorized());
    verifyNoInteractions(records);
    var row = payment("SUCCESS", "LEGACY");
    row.paidAt = LocalDateTime.of(2026, 11, 13, 12, 30, 45, 123000000);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(row));
    var result =
        mvc.perform(post("/api/payments/pay-1/mock-success").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.amount").value("10.00"))
            .andExpect(jsonPath("$.data.paidAt").value("2026-11-13T12:30:45.123+08:00"))
            .andReturn();
    var response = json.readTree(result.getResponse().getContentAsByteArray());
    assertEquals(4, response.size());
    assertEquals(6, response.get("data").size());
    assertTrue(response.get("data").get("amount").isTextual());
    assertFalse(response.get("requestId").asText().isBlank());
    row.amount = null;
    row.paidAt = null;
    result =
        mvc.perform(post("/api/payments/pay-1/mock-success").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andReturn();
    var data = json.readTree(result.getResponse().getContentAsByteArray()).get("data");
    assertTrue(data.get("amount").isNull());
    assertTrue(data.get("paidAt").isNull());
    row.debitMode = null;
    mvc.perform(post("/api/payments/pay-1/mock-success").header("X-User-Id", "7"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("PAY_DEBIT_MODE_UNAVAILABLE"));
    assertNull(AuthContext.userId());
  }

  private BizException failure(int status, String code) {
    var failure = assertThrows(BizException.class, () -> controller.success("pay-1"));
    assertEquals(status, failure.getHttpStatus());
    assertEquals(code, failure.getCode());
    return failure;
  }

  private static PayRecordPO payment(String state, String mode) {
    var payment = new PayRecordPO();
    payment.payNo = "pay-1";
    payment.orderNo = "order-1";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = state;
    payment.debitMode = mode;
    return payment;
  }
}
