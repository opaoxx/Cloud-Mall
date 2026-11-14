package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryServiceImpl;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真实Store/Service配查询替身，拒绝把CI查询返回对象误认为请求支付号。 */
class PayTccDeliveryIdentityTest {
  /** 已确认两来源自身一致，也必须与原请求精确匹配；共同NULL也不是身份。 */
  @Test
  void ensureRejectsOtherRequestedIdentity() {
    for (String original : Arrays.asList("other-pay", "PAY-IDENTITY", "pay-identity ", null)) {
      var fixture = new DeliveryFixture(original);
      var before = fixture.snapshot();
      var failure = assertThrows(BizException.class, () -> fixture.store.ensure("pay-identity"));
      assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, fixture.snapshot());
      verifyNoInteractions(fixture.deliveries, fixture.dispatches, fixture.evidence);
    }
    var nullKey = new DeliveryFixture(null);
    assertThrows(BizException.class, () -> nullKey.store.ensure(null));
    verifyNoInteractions(nullKey.deliveries);
  }

  /** 七种状态的已出现异源/NULL原键均冲突，不修改返回对象或其它来源。 */
  @Test
  void readRejectsForeignAndNullRowKeys() {
    for (String state :
        List.of(
            "READY",
            "CANCEL_REQUESTED",
            "CANCEL_RECONCILING",
            "ORDER_SYNC_UNKNOWN",
            "REFUND_PENDING",
            "SUCCESS",
            "REFUNDED"))
      for (String original : Arrays.asList("other-pay", "PAY-IDENTITY", "pay-identity ", null)) {
        var fixture = new DeliveryFixture("pay-identity");
        fixture.seedJob(state);
        fixture.current.payNo = original;
        var before = fixture.snapshot();
        assertEquals(
            "PAY_TCC_DELIVERY_CONFLICT",
            assertThrows(BizException.class, () -> fixture.store.read("pay-identity")).getCode());
        assertEquals(before, fixture.snapshot());
        verify(fixture.deliveries, never()).insert(any(PayTccDeliveryPO.class));
        verify(fixture.deliveries, never()).claim(any(), any(), any(), any());
        verifyNoInteractions(
            fixture.payments, fixture.executions, fixture.dispatches, fixture.evidence);
      }
    var nullKey = new DeliveryFixture("pay-identity");
    nullKey.seedJob("SUCCESS");
    nullKey.current.payNo = null;
    assertThrows(BizException.class, () -> nullKey.store.read(null));
  }

  /** 真实Service/Store错来源不能新建、返回另一终态或触发User/Order调用。 */
  @Test
  void deliverCannotUseAliasedCommittedSources() {
    for (String phase : List.of("new", "SUCCESS", "REFUNDED")) {
      var fixture = new DeliveryFixture("PAY-IDENTITY");
      if (!phase.equals("new")) fixture.seedJob(phase);
      var tcc = mock(UserTccClient.class);
      var users = mock(UserClient.class);
      var orders = mock(OrderClient.class);
      var service =
          new PayTccDeliveryServiceImpl(fixture.store, tcc, users, orders, "test-callback");
      var before = fixture.snapshot();
      assertEquals(
          "PAY_TCC_DELIVERY_CONFLICT",
          assertThrows(BizException.class, () -> service.deliver("pay-identity")).getCode());
      assertEquals(before, fixture.snapshot());
      verifyNoInteractions(
          tcc, users, orders, fixture.deliveries, fixture.evidence, fixture.dispatches);
    }
  }

  /** 匹配ensure只插一次READY，缺失与未确认/非COMMIT沿原优先级返回NULL。 */
  @Test
  void canonicalAndUncommittedReadSemanticsRemain() {
    var matching = new DeliveryFixture("pay-identity");
    var created = matching.store.ensure("pay-identity");
    assertEquals("pay-identity", created.payNo);
    assertEquals("READY", created.state);
    assertEquals("refund:pay-identity", created.refundOperationKey);
    var after = matching.snapshot();
    assertSame(created, matching.store.ensure("pay-identity"));
    assertSame(created, matching.store.read("pay-identity"));
    assertEquals(after, matching.snapshot());
    verify(matching.deliveries).insert(any(PayTccDeliveryPO.class));
    var missing = new DeliveryFixture("pay-identity");
    assertNull(missing.store.read("absent-pay"));
    for (String state : List.of("CLAIMED", "RUNNING", "ROLLEDBACK")) {
      var uncommitted = new DeliveryFixture("OTHER-PAY");
      uncommitted.execution.state = state;
      assertNull(uncommitted.store.ensure("pay-identity"));
      verifyNoInteractions(uncommitted.deliveries);
    }
    var nonCommit = new DeliveryFixture("OTHER-PAY");
    nonCommit.execution.decision = "ROLLBACK";
    assertNull(nonCommit.store.ensure("pay-identity"));
    verifyNoInteractions(nonCommit.deliveries);
    var missingExecution = new DeliveryFixture("pay-identity");
    when(missingExecution.executions.selectByPayNo(any())).thenReturn(null);
    assertNull(missingExecution.store.ensure("pay-identity"));
    verifyNoInteractions(missingExecution.deliveries);
  }

  /** 非法租约身份在任何认领读取/锁之前409，不能用NULL owner保存认领。 */
  @Test
  void invalidClaimTokensFailBeforeAnyLookup() {
    for (String token :
        Arrays.asList(null, "", " ", "\t\r\n", "\u3000", "a".repeat(129), "😀".repeat(65))) {
      var fixture = new DeliveryFixture("pay-identity");
      var before = fixture.snapshot();
      var failure =
          assertThrows(BizException.class, () -> fixture.store.claim("pay-identity", token));
      assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, fixture.snapshot());
      verifyNoInteractions(
          fixture.payments,
          fixture.deliveries,
          fixture.executions,
          fixture.dispatches,
          fixture.evidence);
    }
  }

  /** 非法token不能借历史坏owner相等获得推进或收尾权；已有锁查询顺序保持。 */
  @Test
  void invalidStageAndFinishTokensKeepFactsAndSkipWrites() {
    for (String token :
        Arrays.asList(null, "", " ", "\t\r\n", "\u3000", "a".repeat(129), "😀".repeat(65))) {
      var fixture = new DeliveryFixture("pay-identity");
      fixture.seedJob("READY");
      fixture.current.leaseOwner = token;
      fixture.current.leaseUntil =
          java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
      var before = fixture.snapshot();
      org.junit.jupiter.api.Assertions.assertFalse(
          fixture.store.stage("pay-identity", token, "ORDER_SYNC_UNKNOWN"));
      verify(fixture.payments).selectForUpdate("pay-identity");
      verify(fixture.deliveries).selectForUpdate("pay-identity");
      fixture.store.finish("pay-identity", token, 1, "should-not-save");
      assertEquals(before, fixture.snapshot());
      verify(fixture.payments, never()).markOrderUnknown(any(), any());
      verify(fixture.deliveries, never()).updateState(any(), any(), any(), any(), any(), any());
      verify(fixture.deliveries, never()).finishAttempt(any(), any(), any(), any(), any());
      verifyNoInteractions(fixture.executions, fixture.dispatches, fixture.evidence);
    }
  }

  /** 合法边界保原token/readback/状态参数，不trim首尾非blank空格。 */
  @Test
  void validLeaseTokenParametersAndReadbackRemainUnchanged() {
    for (String token : List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64))) {
      var fixture = new DeliveryFixture("pay-identity");
      fixture.seedJob("READY");
      fixture.current.leaseOwner = token;
      fixture.current.leaseUntil =
          java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
      when(fixture.deliveries.claim(any(), any(), any(), any())).thenReturn(1);
      assertSame(fixture.current, fixture.store.claim("pay-identity", token));
      verify(fixture.deliveries)
          .claim(
              org.mockito.ArgumentMatchers.eq("pay-identity"),
              org.mockito.ArgumentMatchers.eq(token),
              any(),
              any());
      when(fixture.payments.markOrderUnknown(any(), any())).thenReturn(1);
      when(fixture.deliveries.updateState(any(), any(), any(), any(), any(), any())).thenReturn(1);
      org.junit.jupiter.api.Assertions.assertTrue(
          fixture.store.stage("pay-identity", token, "ORDER_SYNC_UNKNOWN"));
      verify(fixture.deliveries)
          .updateState(
              org.mockito.ArgumentMatchers.eq("pay-identity"),
              org.mockito.ArgumentMatchers.eq(token),
              org.mockito.ArgumentMatchers.eq("READY"),
              org.mockito.ArgumentMatchers.eq("ORDER_SYNC_UNKNOWN"),
              org.mockito.ArgumentMatchers.isNull(),
              any());
      fixture.store.finish("pay-identity", token, 1, "lease-evidence");
      verify(fixture.deliveries)
          .finishAttempt(
              org.mockito.ArgumentMatchers.eq("pay-identity"),
              org.mockito.ArgumentMatchers.eq(token),
              any(),
              org.mockito.ArgumentMatchers.eq("lease-evidence"),
              any());
    }
  }

  /** 完整相同原拼写来源，持久读取/插入为替身，未套Spring事务代理。 */
  private static final class DeliveryFixture {
    /** 原支付对象。 */
    final PayRecordPO payment = new PayRecordPO();

    /** 原已确认执行对象。 */
    final PayTccExecutionPO execution = new PayTccExecutionPO();

    /** 原交付读取和首写替身。 */
    final PayTccDeliveryMapper deliveries = mock(PayTccDeliveryMapper.class);

    /** 原支付锁定替身。 */
    final PayRecordMapper payments = mock(PayRecordMapper.class);

    /** 原执行读取替身。 */
    final PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);

    /** 不应触及的派发来源替身。 */
    final PayTccDispatchMapper dispatches = mock(PayTccDispatchMapper.class);

    /** 不应触及的资金证据替身。 */
    final PayTccExecutionEvidence evidence = mock(PayTccExecutionEvidence.class);

    /** 真实交付本地规则。 */
    final PayTccDeliveryStore store =
        new PayTccDeliveryStore(deliveries, payments, executions, dispatches, evidence);

    /** 模拟当前交付事实。 */
    PayTccDeliveryPO current;

    /** 创建足够核对原身份和金额的完整源对象，插入后返回同一个保存对象。 */
    DeliveryFixture(String original) {
      payment.payNo = original;
      payment.userId = 7L;
      payment.orderNo = "order-identity";
      payment.amount = BigDecimal.TEN;
      payment.debitMode = "TCC";
      payment.status = "PENDING";
      execution.payNo = original;
      execution.userId = payment.userId;
      execution.orderNo = payment.orderNo;
      execution.amount = BigDecimal.TEN;
      execution.xid = "xid-identity";
      execution.operationKey = "tcc-debit:" + original;
      execution.ledgerKey = original;
      execution.state = "COMMITTED";
      execution.decision = "COMMIT";
      when(payments.selectForUpdate(any())).thenReturn(List.of(payment));
      when(executions.selectByPayNo(any())).thenReturn(execution);
      when(deliveries.selectForUpdate(any())).thenAnswer(invocation -> current);
      when(deliveries.selectByPayNo(any())).thenAnswer(invocation -> current);
      when(deliveries.insert(any(PayTccDeliveryPO.class)))
          .thenAnswer(
              invocation -> {
                current = invocation.getArgument(0);
                return 1;
              });
    }

    /** 按原来源构造持久状态，不生成新XID或补偿键。 */
    void seedJob(String state) {
      current = new PayTccDeliveryPO();
      current.payNo = execution.payNo;
      current.userId = execution.userId;
      current.orderNo = execution.orderNo;
      current.amount = execution.amount;
      current.xid = execution.xid;
      current.refundOperationKey = "refund:" + execution.payNo;
      current.state = state;
    }

    /** 保存所有公开字段，含新任务的Java时间与金额原scale。 */
    String snapshot() {
      return assertDoesNotThrow(
          () ->
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .findAndRegisterModules()
                  .writeValueAsString(Arrays.asList(payment, execution, current)));
    }
  }
}
