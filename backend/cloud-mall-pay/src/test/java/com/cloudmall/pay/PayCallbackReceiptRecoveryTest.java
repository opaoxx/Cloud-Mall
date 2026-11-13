package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 回调先返回处理中、支付以后收敛时的独立收据恢复边界。 */
@ExtendWith(MockitoExtension.class)
class PayCallbackReceiptRecoveryTest {
  /** 本测试的固定支付身份。 */
  private static final String PAY_NO = "pay-recovery";

  /** 本测试的固定回调身份。 */
  private static final String CALLBACK_ID = "callback-recovery";

  /** 回调日志查询和CAS的替身。 */
  @Mock private PayCallbackLogMapper callbacks;

  /** 支付行锁与不可变字段读取的替身。 */
  @Mock private PayRecordMapper payments;

  /** 受测真实收据服务。 */
  private PayCallbackReceiptService service;

  /** 加锁前仅供定位的日志快照。 */
  private PayCallbackLogPO snapshot;

  /** 锁后用于完成校验的日志。 */
  private PayCallbackLogPO locked;

  /** 独立业务恢复之后可改变终态的支付事实。 */
  private PayRecordPO payment;

  @BeforeEach
  void setUp() {
    service = new PayCallbackReceiptService(callbacks, payments, new ObjectMapper());
    snapshot = receipt();
    locked = receipt();
    payment = new PayRecordPO();
    payment.payNo = PAY_NO;
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = "LEGACY";
    payment.status = "SUCCESS";
    when(callbacks.selectByCallbackId(CALLBACK_ID)).thenReturn(List.of(snapshot));
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));
    when(callbacks.lockedSelect(CALLBACK_ID)).thenReturn(locked);
  }

  /** 原回调处理中时不补状态；后续支付成功可自动完成，重复扫描不重写时间。 */
  @Test
  void receivedCallbackCompletesAfterAsynchronousSuccessWithoutAnotherPost() {
    payment.status = "ORDER_SYNC_UNKNOWN";
    service.recoverReceived(CALLBACK_ID);
    verify(callbacks, never()).markProcessed(any(), any(), any(), any());
    payment.status = "SUCCESS";
    doAnswer(
            invocation -> {
              locked.callbackStatus = "SUCCESS";
              locked.processedAt = invocation.getArgument(3);
              return 1;
            })
        .when(callbacks)
        .markProcessed(eq(CALLBACK_ID), eq("RECEIVED_SUCCESS"), eq("SUCCESS"), any());

    service.recoverReceived(CALLBACK_ID);
    LocalDateTime completedAt = locked.processedAt;
    service.recoverReceived(CALLBACK_ID);

    assertNotNull(completedAt);
    org.junit.jupiter.api.Assertions.assertEquals(completedAt, locked.processedAt);
    verify(callbacks, times(1)).markProcessed(any(), any(), any(), any());
    verify(payments, times(3)).selectForUpdate(PAY_NO);
    verifyNoMoreInteractions(payments);
  }

  /** 已失败支付仅完成相同失败方向，不写资金或支付记录。 */
  @Test
  void matchingFailureCompletesOnlyReceipt() {
    payment.status = "FAILED";
    locked.callbackStatus = "RECEIVED_FAILURE";
    locked.payload = payload(false);
    when(callbacks.markProcessed(any(), any(), any(), any())).thenReturn(1);

    service.recoverReceived(CALLBACK_ID);

    verify(callbacks).markProcessed(eq(CALLBACK_ID), eq("RECEIVED_FAILURE"), eq("FAILED"), any());
    verify(payments).selectForUpdate(PAY_NO);
    verifyNoMoreInteractions(payments);
  }

  /** TCC、未知协议、未完成和相反支付终态都不授予日志完成。 */
  @Test
  void incompatibleModeOrTerminalStateDoesNotComplete() {
    for (String mode : Arrays.asList("TCC", "UNKNOWN", null, "LEGACY ", "legacy")) {
      payment.debitMode = mode;
      service.recoverReceived(CALLBACK_ID);
    }
    payment.debitMode = "LEGACY";
    for (String status : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "FAILED", "REFUNDED")) {
      payment.status = status;
      service.recoverReceived(CALLBACK_ID);
    }
    verify(callbacks, never()).markProcessed(any(), any(), any(), any());
  }

  /** 自动恢复不能用支付行补造历史空载荷，且必须拒绝JSON歧义或类型截断。 */
  @Test
  void incompleteAmbiguousAndCoercedPayloadsRemainUnchanged() {
    List<String> invalid =
        Arrays.asList(
            null,
            "",
            "{}",
            "null",
            payload(true) + " {}",
            payload(true).replace("\"payNo\":", "\"payNo\":\"other\",\"payNo\":"),
            payload(true).replace("\"userId\":7", "\"userId\":9223372036854775808"),
            payload(true).replace("\"userId\":7", "\"userId\":\"7\""),
            payload(true).replace("\"userId\":7", "\"userId\":7.0"),
            payload(true).replace("\"userId\":7", "\"userId\":0"),
            payload(true).replace("\"amount\":\"10.00\"", "\"amount\":10"),
            payload(true).replace("\"success\":true", "\"success\":\"true\""),
            payload(true).replace("\"success\":true", "\"success\":true,\"extra\":1"),
            payload(true).replace("\"amount\":\"10.00\"", "\"amount\":\"0.00\""));
    for (String payload : invalid) {
      locked.payload = payload;
      assertThrows(BizException.class, () -> service.recoverReceived(CALLBACK_ID));
    }
    verify(callbacks, never()).markProcessed(any(), any(), any(), any());
  }

  /** 锁定后载荷的pay、user、amount、方向或主键分叉均不能写日志终态。 */
  @Test
  void lockedPayloadAndIdentityMustMatch() {
    for (String payload :
        List.of(
            payload(true).replace(PAY_NO, "other-pay"),
            payload(true).replace("\"userId\":7", "\"userId\":8"),
            payload(true).replace("10.00", "11.00"),
            payload(false))) {
      locked.payload = payload;
      assertThrows(BizException.class, () -> service.recoverReceived(CALLBACK_ID));
    }
    locked.payload = payload(true);
    locked.id = 2L;
    assertThrows(BizException.class, () -> service.recoverReceived(CALLBACK_ID));
    verify(callbacks, never()).markProcessed(any(), any(), any(), any());
  }

  /** 沿原支付行到日志行的锁顺序，CAS零行必须报告冲突。 */
  @Test
  void locksPaymentBeforeReceiptAndRejectsUnconfirmedCas() {
    when(callbacks.markProcessed(any(), any(), any(), any())).thenReturn(0);
    assertThrows(BizException.class, () -> service.recoverReceived(CALLBACK_ID));
    InOrder locks = inOrder(payments, callbacks);
    locks.verify(callbacks).selectByCallbackId(CALLBACK_ID);
    locks.verify(payments).selectForUpdate(PAY_NO);
    locks.verify(callbacks).lockedSelect(CALLBACK_ID);
    locks.verify(callbacks).markProcessed(any(), any(), any(), any());
  }

  /** 已终态但缺处理时间不是可自动补造的完成证据。 */
  @Test
  void terminalReceiptWithoutProcessedTimeIsRejected() {
    locked.callbackStatus = "SUCCESS";
    assertThrows(BizException.class, () -> service.recoverReceived(CALLBACK_ID));
    verify(callbacks, never()).markProcessed(any(), any(), any(), any());
  }

  private static PayCallbackLogPO receipt() {
    PayCallbackLogPO row = new PayCallbackLogPO();
    row.id = 1L;
    row.payNo = PAY_NO;
    row.callbackId = CALLBACK_ID;
    row.callbackStatus = "RECEIVED_SUCCESS";
    row.payload = payload(true);
    return row;
  }

  private static String payload(boolean success) {
    return "{\"payNo\":\""
        + PAY_NO
        + "\",\"userId\":7,\"amount\":\"10.00\",\"success\":"
        + success
        + "}";
  }
}
