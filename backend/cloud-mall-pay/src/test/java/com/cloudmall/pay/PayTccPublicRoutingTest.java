package com.cloudmall.pay;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PayCallbackController;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayCallbackServiceImpl;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** 公开支付入口的 LEGACY/TCC 持久模式分流和失败边界测试。 */
class PayTccPublicRoutingTest {
  private PayRecordMapper payments;
  private PayConsistencyService legacy;
  private PayTccPaymentService tcc;
  private ObjectProvider<PayTccPaymentService> tccProvider;
  private PayCallbackReceiptService callbackReceipts;
  private PayCallbackController service;
  private PayCallbackServiceImpl callbackService;
  private PaySuccessServiceImpl successService;
  private PaySuccessController successController;
  private PayFailureController failureController;

  /** 准备支付入口依赖。 */
  @BeforeEach
  void setUp() {
    payments = mock(PayRecordMapper.class);
    legacy = mock(PayConsistencyService.class);
    tcc = mock(PayTccPaymentService.class);
    tccProvider = mock(ObjectProvider.class);
    callbackReceipts = mock(PayCallbackReceiptService.class);
    when(tccProvider.getIfAvailable()).thenReturn(tcc);
    var intents = mock(PayDebitIntentMapper.class);
    var monitor = new PayWriteMonitor();
    var legacyFailure = mock(com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService.class);
    callbackService =
        new PayCallbackServiceImpl(
            payments,
            intents,
            legacyFailure,
            legacy,
            tccProvider,
            callbackReceipts,
            com.cloudmall.pay.service.PayProtocolMetrics.noop(),
            monitor,
            "token");
    service = new PayCallbackController(callbackService);
    successService =
        new PaySuccessServiceImpl(
            payments,
            intents,
            legacy,
            tccProvider,
            com.cloudmall.pay.service.PayProtocolMetrics.noop(),
            monitor);
    successController = new PaySuccessController(successService);
    failureController =
        new PayFailureController(
            new PayFailureServiceImpl(
                payments,
                intents,
                legacyFailure,
                tccProvider,
                com.cloudmall.pay.service.PayProtocolMetrics.noop()));
    AuthContext.set(7L, "USER");
  }

  /** 清理公开入口认证上下文。 */
  @AfterEach
  void tearDown() {
    AuthContext.clear();
  }

  /** 持久 LEGACY 支付继续进入旧 PayConsistencyService。 */
  @Test
  void legacySuccessUsesLegacyCoordinator() {
    when(payments.selectByPayNo("pay-routing")).thenReturn(List.of(payment("LEGACY", "PENDING")));
    when(legacy.initiate(any(PayVO.class))).thenReturn("ORDER_SYNC_UNKNOWN");

    BizException pending =
        assertThrows(BizException.class, () -> successController.success("pay-routing"));

    assertEquals(202, pending.getHttpStatus());
    verify(legacy).initiate(any(PayVO.class));
    verify(tcc, never()).pay(any());
  }

  /** 持久 TCC 支付只进入 TCC payment service，不进入 Legacy。 */
  @Test
  void tccSuccessUsesTccCoordinator() {
    when(payments.selectByPayNo("pay-routing"))
        .thenReturn(
            List.of(payment("TCC", "PENDING")),
            List.of(payment("TCC", "PENDING")),
            List.of(payment("TCC", "SUCCESS")));
    when(tcc.pay(any(PayVO.class))).thenReturn("SUCCESS");

    ApiResponse<?> response = successController.success("pay-routing");

    assertEquals("SUCCESS", ((PayVO) response.data).status);
    verify(tcc).pay(any(PayVO.class));
    verify(legacy, never()).initiate(any());
  }

  /** TCC 已存在 Legacy intent 时按持久冲突返回 503。 */
  @Test
  void tccWithLegacyIntentIsRejected() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    when(payments.selectByPayNo("pay-routing")).thenReturn(List.of(payment("TCC", "PENDING")));
    when(intents.findByPayNo("pay-routing")).thenReturn(intent("LEGACY"));
    ReflectionTestUtils.setField(callbackService, "payDebitIntentMapper", intents);
    ReflectionTestUtils.setField(successService, "payDebitIntentMapper", intents);

    BizException failure =
        assertThrows(BizException.class, () -> successController.success("pay-routing"));

    assertEquals(503, failure.getHttpStatus());
    verify(tcc, never()).pay(any());
    verify(legacy, never()).initiate(any());
  }

  /** TCC 入口缺失时不降级为 Legacy，返回 503。 */
  @Test
  void tccWithoutEntryIsRejected() {
    when(tccProvider.getIfAvailable()).thenReturn(null);
    when(payments.selectByPayNo("pay-routing")).thenReturn(List.of(payment("TCC", "PENDING")));

    BizException failure =
        assertThrows(BizException.class, () -> successController.success("pay-routing"));

    assertEquals(503, failure.getHttpStatus());
    verify(legacy, never()).initiate(any());
  }

  /** TCC 主动失败保持 202，不调用 Legacy/Order 取消。 */
  @Test
  void tccFailureReturnsUnknownWithoutLegacySideEffect() {
    when(payments.selectByPayNo("pay-routing")).thenReturn(List.of(payment("TCC", "PENDING")));
    when(tccProvider.getIfAvailable()).thenReturn(tcc);
    doThrow(new BizException("PAY_ORDER_SYNC_UNKNOWN", "pending", 202))
        .when(tcc)
        .failure(any(PayVO.class));

    BizException pending =
        assertThrows(BizException.class, () -> failureController.fail("pay-routing"));

    assertEquals(202, pending.getHttpStatus());
    verify(legacy, never()).initiate(any());
    verify(tcc).failure(any(PayVO.class));
  }

  /** TCC 成功回调先预绑定 callbackId，再执行 TCC 编排；未知结果不能 200。 */
  @Test
  void tccCallbackPrebindsAndUnknownDoesNotComplete() {
    when(payments.selectByPayNo("pay-routing"))
        .thenReturn(List.of(payment("TCC", "PENDING")), List.of(payment("TCC", "PENDING")));
    when(tcc.pay(any(PayVO.class))).thenReturn("UNKNOWN");
    PayCallbackDTO callback = callback(true);

    BizException pending =
        assertThrows(BizException.class, () -> service.callback("token", callback));

    assertEquals(202, pending.getHttpStatus());
    var order = inOrder(callbackReceipts, tcc);
    order
        .verify(callbackReceipts)
        .claim(any(), any(), org.mockito.ArgumentMatchers.eq("callback-1"));
    order.verify(tcc).pay(any(PayVO.class));
    verify(callbackReceipts, never()).complete(any(), any(), any());
  }

  /** TCC 成功回调必须按 claim→pay→complete 顺序闭合回调事实。 */
  @Test
  void tccSuccessfulCallbackCompletesAfterCoordinator() {
    when(payments.selectByPayNo("pay-routing"))
        .thenReturn(
            List.of(payment("TCC", "PENDING")),
            List.of(payment("TCC", "PENDING")),
            List.of(payment("TCC", "SUCCESS")));
    when(tcc.pay(any(PayVO.class))).thenReturn("SUCCESS");
    PayCallbackDTO callback = callback(true);

    ApiResponse<?> response = service.callback("token", callback);

    assertEquals("SUCCESS", ((PayVO) response.data).status);
    var order = inOrder(callbackReceipts, tcc);
    order
        .verify(callbackReceipts)
        .claim(any(), any(), org.mockito.ArgumentMatchers.eq("callback-1"));
    order.verify(tcc).pay(any(PayVO.class));
    order
        .verify(callbackReceipts)
        .complete(any(), any(), org.mockito.ArgumentMatchers.eq("callback-1"));
  }

  /** Legacy 成功回调保持旧编排，并在未知结果时不能提前完成 callback。 */
  @Test
  void legacyCallbackUnknownKeepsExistingPendingSemantics() {
    when(payments.selectByPayNo("pay-routing"))
        .thenReturn(List.of(payment("LEGACY", "PENDING")), List.of(payment("LEGACY", "PENDING")));
    when(legacy.initiate(any(PayVO.class))).thenReturn("ORDER_SYNC_UNKNOWN");

    BizException pending =
        assertThrows(BizException.class, () -> service.callback("token", callback(true)));

    assertEquals(202, pending.getHttpStatus());
    verify(legacy).initiate(any(PayVO.class));
    verify(callbackReceipts, never()).complete(any(), any(), any());
    verify(tcc, never()).pay(any());
  }

  private PayRecordPO payment(String mode, String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-routing";
    payment.orderNo = "order-routing";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = mode;
    payment.status = status;
    return payment;
  }

  private com.cloudmall.pay.domain.po.PayDebitIntentPO intent(String mode) {
    com.cloudmall.pay.domain.po.PayDebitIntentPO intent =
        new com.cloudmall.pay.domain.po.PayDebitIntentPO();
    intent.payNo = "pay-routing";
    intent.debitMode = mode;
    return intent;
  }

  private PayCallbackDTO callback(boolean success) {
    PayCallbackDTO callback = new PayCallbackDTO();
    callback.payNo = "pay-routing";
    callback.callbackId = "callback-1";
    callback.userId = 7L;
    callback.amount = new BigDecimal("10.00");
    callback.success = success;
    return callback;
  }
}
