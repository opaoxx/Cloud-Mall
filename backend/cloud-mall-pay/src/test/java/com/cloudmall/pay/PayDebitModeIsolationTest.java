package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.service.impl.legacy.PayDebitIntentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 未支持的持久扣款协议不能被现有恢复器解释成普通扣款。 */
class PayDebitModeIsolationTest {
  @Test
  void tccIntentIsRejectedBeforeLeaseOrAnyRemoteCall() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    PayRecordMapper records = mock(PayRecordMapper.class);
    UserClient users = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = "pay-tcc";
    intent.status = "INIT";
    intent.debitMode = "TCC";
    when(intents.findByPayNo("pay-tcc")).thenReturn(intent);
    when(intents.claim(anyString(), anyString(), any(), any())).thenReturn(1);
    PayConsistencyService service =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intents,
            records,
            users,
            orders,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    BizException failure = assertThrows(BizException.class, () -> service.process("pay-tcc"));

    assertEquals("PAY_DEBIT_MODE_UNAVAILABLE", failure.getCode());
    assertEquals(503, failure.getHttpStatus());
    verify(intents, never()).claim(anyString(), anyString(), any(), any());
    verify(intents, never())
        .recordRetry(anyString(), anyString(), anyString(), anyString(), any(), any());
    verifyNoInteractions(users, orders, records);
  }

  @Test
  void mismatchedPaymentModeCannotClaimALegacyIntent() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    PayRecordMapper records = mock(PayRecordMapper.class);
    UserClient users = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = "pay-mixed";
    intent.debitMode = "LEGACY";
    PayRecordPO payment = new PayRecordPO();
    payment.debitMode = "TCC";
    when(intents.findByPayNo("pay-mixed")).thenReturn(intent);
    when(records.selectByPayNo("pay-mixed")).thenReturn(List.of(payment));
    PayConsistencyService service =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intents,
            records,
            users,
            orders,
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    assertThrows(BizException.class, () -> service.process("pay-mixed"));

    verify(intents, never()).claim(anyString(), anyString(), any(), any());
    verifyNoInteractions(users, orders);
  }

  @Test
  void unknownStoredModeDoesNotFallBackToLegacy() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    PayRecordMapper records = mock(PayRecordMapper.class);
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = "pay-future";
    intent.debitMode = "future-mode";
    when(intents.findByPayNo("pay-future")).thenReturn(intent);
    PayConsistencyService service =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intents,
            records,
            mock(UserClient.class),
            mock(OrderClient.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    assertThrows(BizException.class, () -> service.process("pay-future"));

    verify(intents, never()).claim(anyString(), anyString(), any(), any());
    verifyNoInteractions(records);
  }

  @Test
  void newIntentCopiesModeFromTheOriginalPayment() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    PayRecordMapper records = mock(PayRecordMapper.class);
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-original";
    payment.orderNo = "order-original";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = "LEGACY";
    payment.status = "PENDING";
    when(records.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    java.util.concurrent.atomic.AtomicReference<PayDebitIntentPO> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(intents.findByPayNoForUpdate(payment.payNo)).thenAnswer(invocation -> saved.get());
    when(intents.insertIfAbsent(any()))
        .thenAnswer(
            invocation -> {
              saved.set(invocation.getArgument(0));
              return 1;
            });
    PayVO view = new PayVO();
    view.payNo = payment.payNo;
    view.orderNo = payment.orderNo;
    view.userId = payment.userId;
    view.amount = payment.amount;

    PayDebitIntentPO result = new PayDebitIntentService(intents, records).ensureIntent(view);

    assertEquals("LEGACY", result.debitMode);
    assertEquals("pay-original", result.payNo);
  }

  @Test
  void repeatedIntentCreationCannotOverwriteAStoredTccMode() {
    PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);
    PayRecordMapper records = mock(PayRecordMapper.class);
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-existing";
    payment.orderNo = "order-existing";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = "LEGACY";
    payment.status = "PENDING";
    PayDebitIntentPO stored = new PayDebitIntentPO();
    stored.payNo = payment.payNo;
    stored.debitMode = "TCC";
    when(records.selectForUpdate(payment.payNo)).thenReturn(List.of(payment));
    when(intents.findByPayNoForUpdate(payment.payNo)).thenReturn(stored);
    PayVO view = new PayVO();
    view.payNo = payment.payNo;
    view.orderNo = payment.orderNo;
    view.userId = payment.userId;
    view.amount = payment.amount;

    PayDebitIntentService service = new PayDebitIntentService(intents, records);
    BizException failure = assertThrows(BizException.class, () -> service.ensureIntent(view));

    assertEquals("PAY_DEBIT_MODE_UNAVAILABLE", failure.getCode());
    assertEquals("TCC", stored.debitMode);
    verify(intents, never()).insertIfAbsent(any());
  }

  @Test
  void unsupportedCreationModeFailsStartupValidation() {
    PayDebitModeConfiguration configuration = new PayDebitModeConfiguration();
    assertEquals("LEGACY", configuration.newPaymentMode());
    for (String mode : List.of("TCC", "unknown", "", "legacy")) {
      ReflectionTestUtils.setField(configuration, "configuredMode", mode);
      assertThrows(IllegalStateException.class, configuration::validate);
    }
  }
}
