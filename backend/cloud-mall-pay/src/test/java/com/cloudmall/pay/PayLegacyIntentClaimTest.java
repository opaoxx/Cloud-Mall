package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Legacy 首次意图的 Pay 行锁、当前读和幂等状态测试。 */
class PayLegacyIntentClaimTest {
  private static final String PAY_NO = "pay-legacy-claim";

  @Mock private PayDebitIntentMapper intents;
  @Mock private PayRecordMapper payments;

  private PayDebitIntentService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new PayDebitIntentService(intents, payments);
  }

  /** PENDING 且无 intent 时仅插入一次 INIT，随后锁定读取完整事实。 */
  @Test
  void pendingPaymentClaimsOneInitIntent() {
    PayRecordPO payment = payment("PENDING");
    PayDebitIntentPO stored = intent("INIT");
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));
    when(intents.findByPayNoForUpdate(PAY_NO)).thenReturn(null, stored);
    when(intents.insertIfAbsent(any())).thenReturn(1);

    PayDebitIntentPO result = service.ensureIntent(view());

    assertEquals("INIT", result.status);
    verify(intents).insertIfAbsent(any());
    verify(intents, org.mockito.Mockito.times(2)).findByPayNoForUpdate(PAY_NO);
  }

  /** 已有 INIT/DEBITED 等事实原样返回，不覆盖租约和状态。 */
  @Test
  void existingIntentIsReturnedWithoutMutation() {
    PayRecordPO payment = payment("PENDING");
    PayDebitIntentPO existing = intent("DEBITED");
    existing.attempts = 4;
    existing.leaseOwner = "worker-a";
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));
    when(intents.findByPayNoForUpdate(PAY_NO)).thenReturn(existing);

    PayDebitIntentPO result = service.ensureIntent(view());

    assertEquals(existing, result);
    assertEquals(4, result.attempts);
    verify(intents, never()).insertIfAbsent(any());
  }

  /** 非 PENDING 支付没有意图时不得创建新的 INIT。 */
  @Test
  void nonPendingPaymentCannotCreateIntent() {
    PayRecordPO payment = payment("SUCCESS");
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));
    when(intents.findByPayNoForUpdate(PAY_NO)).thenReturn(null);

    assertThrows(BizException.class, () -> service.ensureIntent(view()));

    verify(intents, never()).insertIfAbsent(any());
  }

  /** Pay 与请求金额不一致时在意图写入前拒绝。 */
  @Test
  void paymentTupleMismatchIsRejectedBeforeInsert() {
    PayRecordPO payment = payment("PENDING");
    payment.amount = new BigDecimal("11.00");
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));

    BizException failure = assertThrows(BizException.class, () -> service.ensureIntent(view()));

    assertEquals(409, failure.getHttpStatus());
    verify(intents, never()).insertIfAbsent(any());
  }

  /** INSERT IGNORE 竞争后必须锁定读取竞争者事实，不能把 0 行当作无意图。 */
  @Test
  void insertRaceReadsDurableIntentAndPreservesIt() {
    PayRecordPO payment = payment("PENDING");
    PayDebitIntentPO winner = intent("INIT");
    winner.leaseOwner = "other-worker";
    when(payments.selectForUpdate(PAY_NO)).thenReturn(List.of(payment));
    when(intents.findByPayNoForUpdate(PAY_NO)).thenReturn(null, winner);
    when(intents.insertIfAbsent(any())).thenReturn(0);

    PayDebitIntentPO result = service.ensureIntent(view());

    assertEquals("other-worker", result.leaseOwner);
    verify(intents).insertIfAbsent(any());
  }

  private PayVO view() {
    PayVO view = new PayVO();
    view.payNo = PAY_NO;
    view.orderNo = "order-legacy-claim";
    view.userId = 7L;
    view.amount = new BigDecimal("10.00");
    return view;
  }

  private PayRecordPO payment(String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = PAY_NO;
    payment.orderNo = "order-legacy-claim";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = status;
    payment.debitMode = "LEGACY";
    return payment;
  }

  private PayDebitIntentPO intent(String status) {
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = PAY_NO;
    intent.orderNo = "order-legacy-claim";
    intent.userId = 7L;
    intent.amount = new BigDecimal("10.00");
    intent.status = status;
    intent.debitMode = "LEGACY";
    return intent;
  }
}
