package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** 公开支付回调预绑定和完成状态的幂等、载荷隔离测试。 */
class PayCallbackReceiptServiceTest {
  private static final String PAY_NO = "pay-callback-test";
  private static final String CALLBACK_ID = "callback-test";
  private static final Long USER_ID = 7L;
  private static final BigDecimal AMOUNT = new BigDecimal("10.00");

  @Mock private PayCallbackLogMapper callbackLogs;
  @Mock private PayRecordMapper payments;

  private PayCallbackReceiptService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new PayCallbackReceiptService(callbackLogs, payments, new ObjectMapper());
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment()));
  }

  /** 首次 claim 先写 received 状态和完整不可变 JSON。 */
  @Test
  void claimBindsCompleteReceivedFact() {
    PayCallbackDTO request = request(true);
    when(callbackLogs.lockedSelect(CALLBACK_ID))
        .thenAnswer(invocation -> received("RECEIVED_SUCCESS"));

    service.claim(view("PENDING"), request, CALLBACK_ID);

    verify(callbackLogs).insertReceived(any(PayCallbackLogPO.class));
    verify(callbackLogs).lockedSelect(CALLBACK_ID);
  }

  /** 同方向终态重放只复用绑定事实，不重复写完成状态。 */
  @Test
  void completeSameFinalDirectionIsIdempotent() {
    when(callbackLogs.lockedSelect(CALLBACK_ID)).thenReturn(received("SUCCESS"));

    PayRecordPO confirmed = payment();
    confirmed.status = "SUCCESS";
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(confirmed));
    service.complete(view("SUCCESS"), request(true), CALLBACK_ID);

    verify(callbackLogs, never()).markProcessed(any(), any(), any(), any());
  }

  /** 跨支付 callbackId 必须冲突，不能复用全局日志。 */
  @Test
  void callbackIdBoundToDifferentPaymentConflicts() {
    PayCallbackLogPO bound = received("RECEIVED_SUCCESS");
    bound.payNo = "another-pay";
    when(callbackLogs.lockedSelect(CALLBACK_ID)).thenReturn(bound);

    BizException exception =
        assertThrows(
            BizException.class, () -> service.claim(view("PENDING"), request(true), CALLBACK_ID));

    assertEquals("PAY_CALLBACK_CONFLICT", exception.getCode());
    verify(callbackLogs, never()).markProcessed(any(), any(), any(), any());
  }

  /** 同一 callbackId 改变 success 方向必须冲突。 */
  @Test
  void callbackDirectionChangeConflicts() {
    when(callbackLogs.lockedSelect(CALLBACK_ID)).thenReturn(received("RECEIVED_FAILURE"));

    BizException exception =
        assertThrows(
            BizException.class, () -> service.claim(view("PENDING"), request(true), CALLBACK_ID));

    assertEquals("PAY_CALLBACK_CONFLICT", exception.getCode());
  }

  /** 新 payload 的金额和用户字段必须与支付行完全匹配。 */
  @Test
  void completeRejectsMismatchedPayload() {
    PayCallbackLogPO bound = received("RECEIVED_SUCCESS");
    bound.payload =
        "{\"payNo\":\"pay-callback-test\",\"userId\":7,\"amount\":\"99.00\",\"success\":true}";
    when(callbackLogs.lockedSelect(CALLBACK_ID)).thenReturn(bound);

    BizException exception =
        assertThrows(
            BizException.class,
            () -> service.complete(view("PENDING"), request(true), CALLBACK_ID));

    assertEquals("PAY_CALLBACK_CONFLICT", exception.getCode());
    verify(callbackLogs, never()).markProcessed(any(), any(), any(), any());
  }

  /** 完成状态只允许 received 到同方向终态 CAS。 */
  @Test
  void completeMarksMatchingReceivedStatus() {
    when(callbackLogs.lockedSelect(CALLBACK_ID)).thenReturn(received("RECEIVED_FAILURE"));

    PayRecordPO failed = payment();
    failed.status = "FAILED";
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(failed));
    when(callbackLogs.markProcessed(any(), any(), any(), any())).thenReturn(1);
    service.complete(view("FAILED"), request(false), CALLBACK_ID);

    verify(callbackLogs)
        .markProcessed(
            eq(CALLBACK_ID),
            eq("RECEIVED_FAILURE"),
            eq("FAILED"),
            any(java.time.LocalDateTime.class));
  }

  private PayRecordPO payment() {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = PAY_NO;
    payment.userId = USER_ID;
    payment.amount = AMOUNT;
    return payment;
  }

  private PayVO view(String status) {
    PayVO payment = new PayVO();
    payment.payNo = PAY_NO;
    payment.userId = USER_ID;
    payment.amount = AMOUNT;
    payment.status = status;
    return payment;
  }

  private PayCallbackDTO request(boolean success) {
    PayCallbackDTO request = new PayCallbackDTO();
    request.payNo = PAY_NO;
    request.userId = USER_ID;
    request.amount = AMOUNT;
    request.success = success;
    request.callbackId = CALLBACK_ID;
    return request;
  }

  private PayCallbackLogPO received(String status) {
    PayCallbackLogPO log = new PayCallbackLogPO();
    log.payNo = PAY_NO;
    log.callbackId = CALLBACK_ID;
    log.callbackStatus = status;
    if ("SUCCESS".equals(status) || "FAILED".equals(status))
      log.processedAt = java.time.LocalDateTime.now();
    log.payload =
        "{\"payNo\":\"pay-callback-test\",\"userId\":7,\"amount\":\"10.00\",\"success\":"
            + (status.contains("SUCCESS") ? "true" : "false")
            + "}";
    return log;
  }
}
