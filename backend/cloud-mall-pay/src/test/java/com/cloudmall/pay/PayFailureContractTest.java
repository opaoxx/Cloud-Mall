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
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 原失败入口与新组合入口共用同一行为矩阵，底层编排为受控替身。 */
class PayFailureContractTest {
  /** 原支付读取观察点。 */
  private final PayRecordMapper records = mock(PayRecordMapper.class);

  /** 原意图协议读取观察点。 */
  private final PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);

  /** 原Legacy失败授权替身。 */
  private final PayLegacyFailureService legacy = mock(PayLegacyFailureService.class);

  /** 原TCC失败入口替身。 */
  private final PayTccPaymentService tcc = mock(PayTccPaymentService.class);

  /** TCC依赖是否提供。 */
  private final ObjectProvider<PayTccPaymentService> provider = mock(ObjectProvider.class);

  /** 协议指标顺序观察点。 */
  private final PayProtocolMetrics metrics = mock(PayProtocolMetrics.class);

  /** 实际HTTP入口。 */
  private PayFailureController controller;

  @BeforeEach
  void prepare() {
    controller =
        new PayFailureController(
            new PayFailureServiceImpl(records, intents, legacy, provider, metrics));
    when(provider.getIfAvailable()).thenReturn(tcc);
    AuthContext.set(7L, "USER");
  }

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void identityOwnershipFullMappingAndSecondReadCardinalityKeepOriginalOrder() {
    AuthContext.clear();
    failure(401, ErrorCodes.UNAUTHORIZED);
    verifyNoInteractions(records, intents, metrics, legacy, provider, tcc);
    AuthContext.set(7L, "USER");
    failure(404, ErrorCodes.NOT_FOUND);
    var row = payment("PENDING", "LEGACY");
    row.userId = 8L;
    doReturn(List.of(row)).when(records).selectByPayNo("pay-1");
    failure(403, ErrorCodes.FORBIDDEN);
    row.userId = 7L;
    doReturn(Arrays.asList(row, null)).when(records).selectByPayNo("pay-1");
    assertThrows(NullPointerException.class, () -> controller.fail("pay-1"));
    for (List<PayRecordPO> next : List.of(List.<PayRecordPO>of(), List.of(row, row))) {
      doReturn(List.of(row), next).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    verifyNoInteractions(intents, metrics, legacy, provider, tcc);
  }

  @Test
  void invalidModeAndIntentConflictStopBeforeMetricsOrFailureOrchestrator() {
    for (String mode : Arrays.asList(null, "", "legacy", "LEGACY ", "other")) {
      doReturn(List.of(payment("FAILED", mode))).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    var intent = new PayDebitIntentPO();
    intent.debitMode = "TCC";
    doReturn(intent).when(intents).findByPayNo("pay-1");
    for (String mode : List.of("LEGACY", "TCC")) {
      doReturn(List.of(payment("FAILED", mode))).when(records).selectByPayNo("pay-1");
      failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    }
    verifyNoInteractions(metrics, legacy, provider, tcc);
  }

  @Test
  void tccReturnsExactlyItsMutatedInputWithoutAThirdRead() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", "TCC")));
    var captured = new AtomicReference<PayVO>();
    doAnswer(
            invocation -> {
              PayVO payment = invocation.getArgument(0);
              captured.set(payment);
              payment.status = "FAILED";
              return null;
            })
        .when(tcc)
        .failure(any());
    var response = controller.fail("pay-1");
    assertSame(captured.get(), response.data);
    assertEquals("FAILED", ((PayVO) response.data).status);
    var sequence = inOrder(records, intents, metrics, provider, tcc);
    sequence.verify(records, times(2)).selectByPayNo("pay-1");
    sequence.verify(intents).findByPayNo("pay-1");
    sequence.verify(metrics).route(PayProtocolMetrics.Mode.TCC, PayProtocolMetrics.Entry.MOCK_FAIL);
    sequence.verify(provider).getIfAvailable();
    sequence.verify(tcc).failure(same(captured.get()));
    sequence.verifyNoMoreInteractions();
    verifyNoInteractions(legacy);
  }

  @Test
  void legacyReturnsItsCurrentViewOrNullWithoutReplacingItWithTheInitialSnapshot() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", "LEGACY")));
    var current = new PayVO();
    current.payNo = "pay-1";
    current.status = "FAILED";
    when(legacy.fail(any())).thenReturn(current);
    assertSame(current, controller.fail("pay-1").data);
    var sequence = inOrder(records, intents, metrics, legacy);
    sequence.verify(records, times(2)).selectByPayNo("pay-1");
    sequence.verify(intents).findByPayNo("pay-1");
    sequence
        .verify(metrics)
        .route(PayProtocolMetrics.Mode.LEGACY, PayProtocolMetrics.Entry.MOCK_FAIL);
    sequence.verify(legacy).fail(any());
    sequence.verifyNoMoreInteractions();
    doReturn(null).when(legacy).fail(any());
    assertNull(controller.fail("pay-1").data);
    verify(records, times(4)).selectByPayNo("pay-1");
    verifyNoMoreInteractions(records);
    verifyNoInteractions(provider, tcc);
  }

  @Test
  void missingTccOrOriginalExceptionsNeverFallBackOrRetry() {
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", "TCC")));
    doReturn(null).when(provider).getIfAvailable();
    failure(503, "PAY_DEBIT_MODE_UNAVAILABLE");
    verifyNoInteractions(tcc, legacy);
    for (String mode : List.of("TCC", "LEGACY")) {
      reset(records, intents, metrics, provider, tcc, legacy);
      when(provider.getIfAvailable()).thenReturn(tcc);
      when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING", mode)));
      var original = new BizException("PAY_ORDER_SYNC_UNKNOWN", "original", 202);
      doThrow(original).when(tcc).failure(any());
      doThrow(original).when(legacy).fail(any());
      assertSame(original, assertThrows(BizException.class, () -> controller.fail("pay-1")));
      if (mode.equals("TCC")) {
        verify(tcc).failure(any());
        verifyNoMoreInteractions(tcc);
        verifyNoInteractions(legacy);
      } else {
        verify(legacy).fail(any());
        verifyNoMoreInteractions(legacy);
        verifyNoInteractions(tcc, provider);
      }
      verify(records, times(2)).selectByPayNo("pay-1");
      verifyNoMoreInteractions(records);
    }
  }

  @Test
  void everyStateStillReachesTheOriginalProtocolSpecificFailureRules() {
    for (String mode : List.of("LEGACY", "TCC")) {
      for (String state :
          Arrays.asList(
              "PENDING",
              "SUCCESS",
              "FAILED",
              "REFUNDED",
              "REFUND_PENDING",
              "ORDER_SYNC_UNKNOWN",
              null)) {
        reset(records, intents, metrics, provider, tcc, legacy);
        when(provider.getIfAvailable()).thenReturn(tcc);
        when(records.selectByPayNo("pay-1")).thenReturn(List.of(payment(state, mode)));
        when(legacy.fail(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(state, ((PayVO) controller.fail("pay-1").data).status);
        verify(metrics).route(PayProtocolMetrics.mode(mode), PayProtocolMetrics.Entry.MOCK_FAIL);
        if (mode.equals("LEGACY")) {
          verify(legacy).fail(any());
          verifyNoInteractions(tcc, provider);
        } else {
          verify(tcc).failure(any());
          verifyNoInteractions(legacy);
        }
      }
    }
  }

  @Test
  void httpIgnoresOriginalUnboundBodyAndKeepsMoneyTimeNullAndNoStore() throws Exception {
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
    mvc.perform(
            post("/api/payments/pay-1/mock-fail")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(records);
    var row = payment("FAILED", "TCC");
    row.paidAt = LocalDateTime.of(2026, 11, 13, 12, 30, 45, 123000000);
    when(records.selectByPayNo("pay-1")).thenReturn(List.of(row));
    var result =
        mvc.perform(
                post("/api/payments/pay-1/mock-fail")
                    .header("X-User-Id", "7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{broken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.amount").value("10.00"))
            .andExpect(jsonPath("$.data.paidAt").value("2026-11-13T12:30:45.123+08:00"))
            .andReturn();
    var response = json.readTree(result.getResponse().getContentAsByteArray());
    assertEquals(4, response.size());
    assertEquals(6, response.get("data").size());
    assertTrue(response.get("data").get("amount").isTextual());
    row.paidAt = null;
    row.amount = null;
    result =
        mvc.perform(post("/api/payments/pay-1/mock-fail").header("X-User-Id", "7"))
            .andExpect(status().isOk())
            .andReturn();
    var data = json.readTree(result.getResponse().getContentAsByteArray()).get("data");
    assertTrue(data.get("amount").isNull());
    assertTrue(data.get("paidAt").isNull());
    row.debitMode = null;
    mvc.perform(post("/api/payments/pay-1/mock-fail").header("X-User-Id", "7"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertNull(AuthContext.userId());
  }

  private BizException failure(int status, String code) {
    var failure = assertThrows(BizException.class, () -> controller.fail("pay-1"));
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
