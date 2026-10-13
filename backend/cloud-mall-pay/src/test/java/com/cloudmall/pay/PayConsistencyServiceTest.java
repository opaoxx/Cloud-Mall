package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import feign.Request;
import feign.RetryableException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 跨服务响应丢失、订单取消和支付崩溃后的本地余额一致性测试。 */
class PayConsistencyServiceTest {
  private PayDebitIntentMapper intentMapper;
  private PayRecordMapper payRecordMapper;
  private UserClient userClient;
  private OrderClient orderClient;
  private PayConsistencyService consistencyService;

  /** 意图保存替身，用于核对入口拒绝无任何保存行为。 */
  private PayDebitIntentService intentService;

  /** 单元事务管理器，仅核调用定义，不冒称实际SQL回滚。 */
  private org.springframework.transaction.PlatformTransactionManager transactionManager;

  @BeforeEach
  void setUp() {
    intentMapper = mock(PayDebitIntentMapper.class);
    payRecordMapper = mock(PayRecordMapper.class);
    userClient = mock(UserClient.class);
    orderClient = mock(OrderClient.class);
    transactionManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenAnswer(
            invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
    intentService = mock(PayDebitIntentService.class);
    consistencyService =
        new PayConsistencyService(
            intentService,
            intentMapper,
            payRecordMapper,
            userClient,
            orderClient,
            transactionManager);
    when(intentMapper.claim(eq("pay-1"), anyString(), any(), any())).thenReturn(1);
    when(intentMapper.findByPayNo("pay-1")).thenReturn(intent());
    when(intentMapper.hasOwnedLegacyLease(anyString(), anyString(), any())).thenReturn(true);
    when(intentMapper.updateOwnedLegacyStatus(anyString(), anyString(), anyString(), any()))
        .thenReturn(1);
    when(intentMapper.recordRetryOwnedLegacy(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyOrderUnknown(anyString(), anyString(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefundPending(anyString(), anyString(), any()))
        .thenReturn(1);
    when(intentMapper.finishOwnedLegacy(anyString(), anyString(), anyString(), any()))
        .thenReturn(1);
    com.cloudmall.pay.domain.po.PayRecordPO payment = new com.cloudmall.pay.domain.po.PayRecordPO();
    payment.payNo = "pay-1";
    payment.orderNo = "order-1";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    payment.debitMode = "LEGACY";
    when(payRecordMapper.selectByPayNo("pay-1")).thenReturn(java.util.List.of(payment));
    when(payRecordMapper.lockOwnedLegacyTerminalSource(any(), anyString(), any()))
        .thenReturn(payment);
    when(payRecordMapper.lockOwnedLegacyStageSource(any(), anyString(), any())).thenReturn(payment);
    when(userClient.operation(7L, "pay-1", 7L)).thenReturn(ApiResponse.ok(appliedDebit()));
    when(userClient.operation(7L, "refund:pay-1", 7L))
        .thenReturn(ApiResponse.ok(refundFact("refund:pay-1", "10.00")));
  }

  @Test
  void orderWriteTimeoutKeepsDebitAndNeverRefundsWithoutAConfirmedCancellation() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
    when(orderClient.paid("order-1", 7L)).thenThrow(timeout());

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(intentMapper)
        .recordRetryOwnedLegacy(
            eq("pay-1"),
            anyString(),
            eq("ORDER_SYNC_UNKNOWN"),
            anyString(),
            any(LocalDateTime.class),
            any(LocalDateTime.class));
    verify(userClient, never()).refund(any(), any(), any());
    verify(userClient, never()).debit(any(), any(), any());
  }

  @Test
  void confirmedCancellationRefundsTheSameDebitOnlyByItsStableKey() {
    when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order("CANCELLED")));
    when(payRecordMapper.markOwnedLegacyRefundPending(eq("pay-1"), anyString(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefunded(eq("pay-1"), anyString(), any())).thenReturn(1);
    when(userClient.refund(eq(7L), eq(7L), any()))
        .thenReturn(
            ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));

    String result = consistencyService.process("pay-1");

    assertEquals("REFUNDED", result);
    org.mockito.ArgumentCaptor<UserClient.RefundRequest> refundRequest =
        org.mockito.ArgumentCaptor.forClass(UserClient.RefundRequest.class);
    verify(userClient).refund(eq(7L), eq(7L), refundRequest.capture());
    assertEquals("refund:pay-1", refundRequest.getValue().refundOperationKey());
    assertEquals("pay-1", refundRequest.getValue().originalOperationKey());
    verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
  }

  @Test
  void crashAfterDebitResumesFromLedgerWithoutDebitingAgain() {
    when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order("PAID")));
    when(payRecordMapper.markOwnedLegacySuccess(eq("pay-1"), anyString(), any(), any()))
        .thenReturn(1);

    String result = consistencyService.process("pay-1");

    assertEquals("SUCCESS", result);
    verify(userClient, never()).debit(any(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
    verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
  }

  @Test
  void paidOrderDoesNotFinishPaymentUntilStockConfirmationIsDurable() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    "order-1", "PAID", new BigDecimal("10.00"), 7L, "UNKNOWN")));

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(payRecordMapper, never()).markOwnedLegacySuccess(eq("pay-1"), anyString(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
  }

  @Test
  void cancelledOrderDoesNotRefundUntilStockRollbackIsDurable() {
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    "order-1", "CANCELLED", new BigDecimal("10.00"), 7L, "PENDING")));

    String result = consistencyService.process("pay-1");

    assertEquals("ORDER_SYNC_UNKNOWN", result);
    verify(userClient, never()).refund(any(), any(), any());
  }

  /** 部分金额和错退款字段不能凭marker结清，全额前的验证失败只重试。 */
  @Test
  void refundMarkerRequiresTheExactAppliedFullRefundLedger() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String variant :
        java.util.List.of(
            "key-null",
            "key-alias",
            "key-other",
            "type-wrong",
            "state-wrong",
            "amount-null",
            "amount-partial",
            "amount-zero",
            "amount-negative",
            "amount-fraction",
            "amount-overflow",
            "amount-text",
            "null-response",
            "null-body",
            "failed-envelope")) {
      assertions.add(
          () -> {
            prepareRefundPath(true, "custom-refund");
            when(userClient.operation(7L, "custom-refund", 7L))
                .thenReturn(badRefundResponse(variant, "custom-refund"));
            assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"), variant);
            verifyRefundNotFinished("ORDER_SYNC_UNKNOWN");
            verify(userClient, never()).refund(any(), any(), any());
          });
    }
    for (String key : java.util.List.of("", " ")) {
      assertions.add(
          () -> {
            prepareRefundPath(true, key);
            assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
            verifyRefundNotFinished("ORDER_SYNC_UNKNOWN");
            verify(userClient, never()).operation(7L, key, 7L);
            verify(userClient, never()).refund(any(), any(), any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** POST成功标签不足以结清，原pending方向必须保留到全额退款GET被证明。 */
  @Test
  void refundPostSuccessRequiresActualFullLedgerBeforeFinish() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String variant :
        java.util.List.of(
            "key-null",
            "key-alias",
            "key-other",
            "type-wrong",
            "state-wrong",
            "amount-null",
            "amount-partial",
            "amount-zero",
            "amount-negative",
            "amount-fraction",
            "amount-overflow",
            "amount-text",
            "null-response",
            "null-body",
            "failed-envelope")) {
      assertions.add(
          () -> {
            prepareRefundPath(false, "refund:pay-1");
            when(userClient.operation(7L, "refund:pay-1", 7L))
                .thenReturn(badRefundResponse(variant, "refund:pay-1"));
            assertEquals("REFUND_PENDING", consistencyService.process("pay-1"), variant);
            verifyRefundNotFinished("REFUND_PENDING");
            verify(userClient).refund(eq(7L), eq(7L), any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 写回执必须精确固定键；错ACK不得进入实际退款读取或终态。 */
  @Test
  void refundWriteAcknowledgementCannotAliasItsStableOperationKey() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String operationKey : java.util.Arrays.asList(null, "other", "REFUND:PAY-1")) {
      assertions.add(
          () -> {
            prepareRefundPath(false, "refund:pay-1");
            when(userClient.refund(eq(7L), eq(7L), any()))
                .thenReturn(
                    ApiResponse.ok(new UserClient.BalanceView(operationKey, "APPLIED", "100.00")));
            assertEquals("REFUND_PENDING", consistencyService.process("pay-1"));
            verifyRefundNotFinished("REFUND_PENDING");
            verify(userClient, never()).operation(7L, "refund:pay-1", 7L);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 退款GET的404/500不得套用初始扣款missing语义，保持原pending并不猜测失败。 */
  @Test
  void refundLedgerReadFaultsNeverBecomeNoDebitOrRefunded() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (boolean marker : java.util.List.of(true, false)) {
      for (int status : java.util.List.of(404, 500)) {
        assertions.add(
            () -> {
              String key = marker ? "custom-refund" : "refund:pay-1";
              prepareRefundPath(marker, key);
              when(userClient.operation(7L, key, 7L)).thenThrow(refundReadFault(status));
              String expected = marker ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
              assertEquals(expected, consistencyService.process("pay-1"));
              verifyRefundNotFinished(expected);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真正全额退款和无损尾零/指数表示保持数值语义，自定义marker键合法且不二次退款。 */
  @Test
  void provenRefundLedgerKeepsCustomKeysAndEqualMoneyRepresentations() {
    for (boolean marker : java.util.List.of(true, false)) {
      for (String amount : java.util.List.of("10.00", "10.000", "1E+1")) {
        String key = marker ? "custom-refund" : "refund:pay-1";
        prepareRefundPath(marker, key);
        when(userClient.operation(7L, key, 7L)).thenReturn(ApiResponse.ok(refundFact(key, amount)));
        assertEquals("REFUNDED", consistencyService.process("pay-1"));
        verify(userClient).operation(7L, key, 7L);
        verify(payRecordMapper).markOwnedLegacyRefunded(eq("pay-1"), anyString(), any());
        verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
        verify(userClient, never()).debit(any(), any(), any());
        verify(userClient, org.mockito.Mockito.times(marker ? 0 : 1)).refund(any(), any(), any());
      }
    }
  }

  /** 未取消或库存动作未完成时保持旧顺序，不提前扩大退款账本读取。 */
  @Test
  void refundConfirmationDoesNotRunBeforeCancellationAndStockProof() {
    for (OrderClient.OrderView order :
        java.util.List.of(
            order("PAID"),
            new OrderClient.OrderView(
                "order-1", "CANCELLED", new BigDecimal("10.00"), 7L, "UNKNOWN"))) {
      prepareRefundPath(true, "custom-refund");
      when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order));
      assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
      verify(userClient, never()).operation(7L, "custom-refund", 7L);
      verify(userClient, never()).refund(any(), any(), any());
      verifyRefundNotFinished("ORDER_SYNC_UNKNOWN");
    }
  }

  /** 同一个service每次处理领取都需新token，旧执行不能命中新一轮的owner条件。 */
  @Test
  void repeatedClaimsUseDistinctAttemptTokensOnTheSameService() {
    when(userClient.operation(7L, "pay-1", 7L))
        .thenThrow(new IllegalStateException("owned read failure"));
    assertEquals("INIT", consistencyService.process("pay-1"));
    assertEquals("INIT", consistencyService.process("pay-1"));
    var tokens = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(intentMapper, org.mockito.Mockito.times(2))
        .claim(eq("pay-1"), tokens.capture(), any(), any());
    org.junit.jupiter.api.Assertions.assertNotEquals(
        tokens.getAllValues().get(0), tokens.getAllValues().get(1));
    for (String token : tokens.getAllValues()) {
      assertEquals(token, java.util.UUID.fromString(token).toString());
      verify(intentMapper)
          .recordRetryOwnedLegacy(eq("pay-1"), eq(token), eq("INIT"), anyString(), any(), any());
    }
    verify(userClient, never()).debit(any(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
  }

  /** 每条成功/退款/重试私有链只传自己的claim token，正常状态与旧协议保持。 */
  @Test
  void eachOutcomePropagatesItsClaimTokenThroughOwnedWrites() {
    for (String outcome :
        java.util.List.of("SUCCESS", "REFUNDED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      prepareRefundPath(false, "refund:pay-1");
      if ("SUCCESS".equals(outcome)) {
        when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order("PAID")));
        when(payRecordMapper.markOwnedLegacySuccess(eq("pay-1"), anyString(), any(), any()))
            .thenReturn(1);
      } else if ("ORDER_SYNC_UNKNOWN".equals(outcome)) {
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
        when(orderClient.paid("order-1", 7L)).thenThrow(timeout());
        when(intentMapper.findByPayNo("pay-1")).thenReturn(intent());
      } else if ("REFUND_PENDING".equals(outcome)) {
        when(userClient.operation(7L, "refund:pay-1", 7L)).thenReturn(ApiResponse.ok(null));
      }
      assertEquals(outcome, consistencyService.process("pay-1"));
      var token = org.mockito.ArgumentCaptor.forClass(String.class);
      verify(intentMapper).claim(eq("pay-1"), token.capture(), any(), any());
      for (var invocation : org.mockito.Mockito.mockingDetails(intentMapper).getInvocations()) {
        if (java.util.Set.of(
                "updateOwnedLegacyStatus", "finishOwnedLegacy", "recordRetryOwnedLegacy")
            .contains(invocation.getMethod().getName())) {
          assertEquals(token.getValue(), invocation.getArgument(1));
        }
      }
    }
  }

  /** owned支付记录更新失败不能用已同终态readback或旧finish绕过资格。 */
  @Test
  void terminalRecordZeroRowsCannotUseOrdinaryFinishOrSameStatusFallback() {
    for (String terminal : java.util.List.of("SUCCESS", "REFUNDED", "FAILED")) {
      prepareTerminalTest(terminal);
      if ("SUCCESS".equals(terminal))
        when(payRecordMapper.markOwnedLegacySuccess(anyString(), anyString(), any(), any()))
            .thenReturn(0);
      else if ("REFUNDED".equals(terminal))
        when(payRecordMapper.markOwnedLegacyRefunded(anyString(), anyString(), any()))
            .thenReturn(0);
      else
        when(payRecordMapper.markOwnedLegacyFailed(anyString(), anyString(), any())).thenReturn(0);
      var sameTerminal = payRecordMapper.selectByPayNo("pay-1").get(0);
      org.mockito.Mockito.clearInvocations(payRecordMapper);
      sameTerminal.debitMode = "LEGACY";
      sameTerminal.status = terminal;
      when(payRecordMapper.selectByPayNo("pay-1")).thenReturn(java.util.List.of(sameTerminal));
      org.junit.jupiter.api.Assertions.assertNotEquals(
          terminal, consistencyService.process("pay-1"));
      verify(intentMapper, never()).finishOwnedLegacy(anyString(), anyString(), anyString(), any());
      verify(intentMapper, never()).finish(anyString(), anyString(), anyString(), any());
    }
  }

  /** 支付记录已推进但intent owned finish失败，不能报告完成或普通finish重试。 */
  @Test
  void terminalIntentFinishMustAffectExactlyOneOwnedRow() {
    for (String terminal : java.util.List.of("SUCCESS", "REFUNDED", "FAILED")) {
      prepareTerminalTest(terminal);
      when(intentMapper.finishOwnedLegacy(anyString(), anyString(), anyString(), any()))
          .thenReturn(0);
      org.junit.jupiter.api.Assertions.assertNotEquals(
          terminal, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(terminal), any());
      verify(intentMapper, never()).finish(anyString(), anyString(), anyString(), any());
    }
  }

  /** 三方向均由原资金与订单事实确定，owned写成功只由当前替身控制。 */
  private void prepareTerminalTest(String terminal) {
    prepareRefundPath("REFUNDED".equals(terminal), "custom-refund");
    if (!"REFUNDED".equals(terminal)) when(intentMapper.findByPayNo("pay-1")).thenReturn(intent());
    if ("SUCCESS".equals(terminal))
      when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order("PAID")));
    if ("FAILED".equals(terminal))
      when(userClient.operation(7L, "pay-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", "REJECTED", "10.00", null)));
    when(payRecordMapper.markOwnedLegacySuccess(anyString(), anyString(), any(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefunded(anyString(), anyString(), any())).thenReturn(1);
    when(payRecordMapper.markOwnedLegacyFailed(anyString(), anyString(), any())).thenReturn(1);
  }

  /** 任何正常中间stage零行必须停止其后的远端写，不走普通方法fallback。 */
  @Test
  void intermediateStageZeroRowsStopWritingRpc() {
    for (String stage :
        java.util.List.of("order-record", "debit-stage", "refund-record", "refund-stage")) {
      prepareRefundPath(false, "refund:pay-1");
      if ("order-record".equals(stage) || "debit-stage".equals(stage)) {
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
      }
      switch (stage) {
        case "order-record" ->
            when(payRecordMapper.markOwnedLegacyOrderUnknown(anyString(), anyString(), any()))
                .thenReturn(0);
        case "debit-stage" ->
            when(intentMapper.updateOwnedLegacyStatus(
                    anyString(), anyString(), eq("DEBITED"), any()))
                .thenReturn(0);
        case "refund-record" ->
            when(payRecordMapper.markOwnedLegacyRefundPending(anyString(), anyString(), any()))
                .thenReturn(0);
        case "refund-stage" ->
            when(intentMapper.updateOwnedLegacyStatus(
                    anyString(), anyString(), eq("REFUND_PENDING"), any()))
                .thenReturn(0);
        default -> throw new AssertionError(stage);
      }
      consistencyService.process("pay-1");
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
      verify(intentMapper, never()).updateOwnedStatus(anyString(), anyString(), anyString(), any());
      verify(payRecordMapper, never()).markOrderUnknown(anyString(), any());
      verify(payRecordMapper, never()).markRefundPending(anyString(), any());
    }
  }

  /** 三个RPC资格门为false时不发出该写请求，read-only证据顺序保持。 */
  @Test
  void missingCurrentLeaseStopsEachWritingRpc() {
    for (String path : java.util.List.of("debit", "paid", "refund")) {
      prepareRefundPath(false, "refund:pay-1");
      if (!"refund".equals(path))
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
      if ("debit".equals(path))
        when(userClient.operation(7L, "pay-1", 7L)).thenThrow(refundReadFault(404));
      when(intentMapper.hasOwnedLegacyLease(anyString(), anyString(), any())).thenReturn(false);
      consistencyService.process("pay-1");
      verify(intentMapper).hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 异常清理零行是失去资格，不得再调用无owner旧方法改写新执行。 */
  @Test
  void cleanupZeroRowsCannotFallBackToOrdinaryWrites() {
    setUp();
    when(orderClient.paymentState("order-1", 7L))
        .thenThrow(new IllegalStateException("owned order unavailable"));
    when(payRecordMapper.markOwnedLegacyOrderUnknown(anyString(), anyString(), any()))
        .thenReturn(0);
    when(intentMapper.recordRetryOwnedLegacy(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(0);
    assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
    verify(payRecordMapper).markOwnedLegacyOrderUnknown(eq("pay-1"), anyString(), any());
    verify(intentMapper)
        .recordRetryOwnedLegacy(
            eq("pay-1"), anyString(), eq("ORDER_SYNC_UNKNOWN"), anyString(), any(), any());
    verify(payRecordMapper, never()).markOrderUnknown(anyString(), any());
    verify(intentMapper, never())
        .recordRetry(anyString(), anyString(), anyString(), anyString(), any(), any());
    verify(userClient, never()).debit(any(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
  }

  /** stage和terminal分别REQUIRES_NEW；终态失败不撤回已提交阶段，仅核fake manager。 */
  @Test
  void terminalTransactionDefinitionCommitsOrRollsBackAsOneUnit() {
    for (boolean fail : java.util.List.of(false, true)) {
      prepareTerminalTest("SUCCESS");
      if (fail)
        when(intentMapper.finishOwnedLegacy(anyString(), anyString(), anyString(), any()))
            .thenReturn(0);
      assertEquals(fail ? "ORDER_SYNC_UNKNOWN" : "SUCCESS", consistencyService.process("pay-1"));
      var definition =
          org.mockito.ArgumentCaptor.forClass(
              org.springframework.transaction.TransactionDefinition.class);
      verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(definition.capture());
      for (var actualDefinition : definition.getAllValues()) {
        assertEquals(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            actualDefinition.getPropagationBehavior());
      }
      // 前一stage独立提交，后一terminal失败只回滚terminal，不撤回stage。
      verify(transactionManager, org.mockito.Mockito.times(fail ? 1 : 2)).commit(any());
      verify(transactionManager, org.mockito.Mockito.times(fail ? 1 : 0)).rollback(any());
    }
  }

  /** 缺事务管理器时构造器必须拒绝，不能回退到旧无事务处理。 */
  @Test
  void missingTerminalTransactionManagerFailsFast() {
    org.junit.jupiter.api.Assertions.assertThrows(
        NullPointerException.class,
        () ->
            new PayConsistencyService(
                mock(PayDebitIntentService.class),
                intentMapper,
                payRecordMapper,
                userClient,
                orderClient,
                null));
  }

  /** 调用者已有Seata XID时不进入本地终态块，也不解绑调用者的上下文。 */
  @Test
  void terminalTransactionRejectsInheritedGlobalContext() {
    org.junit.jupiter.api.Assertions.assertNull(io.seata.core.context.RootContext.getXID());
    prepareTerminalTest("SUCCESS");
    io.seata.core.context.RootContext.bind("owned-global-context-probe");
    try {
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class, () -> consistencyService.process("pay-1"));
      assertEquals("Legacy支付恢复不能继承事务上下文", failure.getMessage());
      org.mockito.Mockito.verifyNoInteractions(
          intentService,
          intentMapper,
          payRecordMapper,
          userClient,
          orderClient,
          transactionManager);
      verify(transactionManager, never()).getTransaction(any());
      verify(payRecordMapper, never())
          .markOwnedLegacySuccess(anyString(), anyString(), any(), any());
      verify(intentMapper, never()).finishOwnedLegacy(anyString(), anyString(), anyString(), any());
      assertEquals("owned-global-context-probe", io.seata.core.context.RootContext.getXID());
    } finally {
      io.seata.core.context.RootContext.unbind();
    }
  }

  /** 三入口在local/global/两者同时存在时先拒绝，且保留调用者上下文。 */
  @Test
  void recoveryEntrancesRejectInheritedContextsBeforeAnyEffects() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entrance : java.util.List.of("process", "initiate", "recoverDue")) {
      for (String context : java.util.List.of("local", "global", "both")) {
        assertions.add(
            () -> {
              setUp();
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              org.junit.jupiter.api.Assertions.assertNull(
                  io.seata.core.context.RootContext.getXID());
              boolean local = !"global".equals(context);
              boolean global = !"local".equals(context);
              var payment = new com.cloudmall.pay.domain.vo.PayVO();
              payment.payNo = "pay-1";
              when(intentService.ensureIntent(payment)).thenReturn(intent());
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .setActualTransactionActive(local);
              if (global) io.seata.core.context.RootContext.bind("owned-entry-context-probe");
              try {
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> {
                          switch (entrance) {
                            case "process" -> consistencyService.process("pay-1");
                            case "initiate" -> consistencyService.initiate(payment);
                            case "recoverDue" -> consistencyService.recoverDue();
                            default -> throw new AssertionError(entrance);
                          }
                        },
                        entrance + ":" + context);
                assertEquals("Legacy支付恢复不能继承事务上下文", failure.getMessage());
                org.mockito.Mockito.verifyNoInteractions(
                    intentService,
                    intentMapper,
                    payRecordMapper,
                    userClient,
                    orderClient,
                    transactionManager);
                assertEquals(
                    local,
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertEquals(
                    global ? "owned-entry-context-probe" : null,
                    io.seata.core.context.RootContext.getXID());
              } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
                if (global) io.seata.core.context.RootContext.unbind();
              }
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 无上下文时initiate照常保存后处理，扫描照常逐项处理。 */
  @Test
  void standaloneInitiationAndScanKeepNormalRecoveryFlow() {
    prepareTerminalTest("SUCCESS");
    var payment = new com.cloudmall.pay.domain.vo.PayVO();
    payment.payNo = "pay-1";
    when(intentService.ensureIntent(payment)).thenReturn(intent());
    assertEquals("SUCCESS", consistencyService.initiate(payment));
    verify(intentService).ensureIntent(payment);
    verify(intentMapper).claim(eq("pay-1"), anyString(), any(), any());
    prepareTerminalTest("SUCCESS");
    when(intentMapper.findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50)))
        .thenReturn(java.util.List.of(intent()));
    consistencyService.recoverDue();
    verify(intentMapper).findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50));
    verify(intentMapper).claim(eq("pay-1"), anyString(), any(), any());
    verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
  }

  /** 单字段损坏及两表一致的非法域不能借模式检查进入恢复。 */
  @Test
  void originalPaymentTupleIsRequiredBeforeClaim() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String target : java.util.List.of("intent", "payment", "both")) {
      for (String variant :
          java.util.List.of(
              "pay-alias",
              "pay-missing",
              "order-other",
              "order-blank",
              "order-missing",
              "user-other",
              "user-zero",
              "user-missing",
              "money-other",
              "money-zero",
              "money-negative",
              "money-fraction",
              "money-overflow",
              "money-missing")) {
        if ("both".equals(target)
            && java.util.List.of("order-other", "user-other", "money-other").contains(variant))
          continue;
        assertions.add(
            () -> {
              setUp();
              PayDebitIntentPO originalIntent = intent();
              var payment = payRecordMapper.selectByPayNo("pay-1").get(0);
              org.mockito.Mockito.clearInvocations(payRecordMapper);
              if (!"payment".equals(target)) corruptRecoveryTuple(originalIntent, variant);
              if (!"intent".equals(target)) {
                PayDebitIntentPO changedPayment = intent();
                corruptRecoveryTuple(changedPayment, variant);
                payment.payNo = changedPayment.payNo;
                payment.orderNo = changedPayment.orderNo;
                payment.userId = changedPayment.userId;
                payment.amount = changedPayment.amount;
              }
              when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
              // 旧实现若按坏intent键读取，仍给同一原payment，才能测出载荷缺证。
              when(payRecordMapper.selectByPayNo(any())).thenReturn(java.util.List.of(payment));
              var failure =
                  org.junit.jupiter.api.Assertions.assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> consistencyService.process("pay-1"),
                      target + ":" + variant);
              assertEquals("COMMON_CONFLICT", failure.getCode());
              assertEquals(409, failure.getHttpStatus());
              assertEquals("支付意图与原支付记录不一致", failure.getMessage());
              verify(intentMapper, never()).claim(any(), any(), any(), any());
              assertOnlyRecoveryReadsOrClaim();
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 请求key必须精确；后两次重读也不能绕过原payment校验。 */
  @Test
  void requestedKeyAndBothRereadBranchesRequireOriginalTuple() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String checkpoint : java.util.List.of("requested-alias", "claim-zero", "claimed")) {
      assertions.add(
          () -> {
            setUp();
            PayDebitIntentPO changed = intent();
            changed.orderNo = "order-other";
            String requestedKey = "requested-alias".equals(checkpoint) ? "PAY-1" : "pay-1";
            if ("requested-alias".equals(checkpoint)) {
              when(intentMapper.findByPayNo(requestedKey)).thenReturn(intent());
              var originalPayments = payRecordMapper.selectByPayNo("pay-1");
              when(payRecordMapper.selectByPayNo(requestedKey)).thenReturn(originalPayments);
              org.mockito.Mockito.clearInvocations(payRecordMapper);
            } else {
              when(intentMapper.findByPayNo("pay-1")).thenReturn(intent(), changed);
              when(intentMapper.claim(eq("pay-1"), anyString(), any(), any()))
                  .thenReturn("claim-zero".equals(checkpoint) ? 0 : 1);
            }
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> consistencyService.process(requestedKey));
            assertEquals("COMMON_CONFLICT", failure.getCode());
            assertEquals(409, failure.getHttpStatus());
            if ("requested-alias".equals(checkpoint))
              verify(intentMapper, never()).claim(any(), any(), any(), any());
            else verify(intentMapper).claim(eq("pay-1"), anyString(), any(), any());
            assertOnlyRecoveryReadsOrClaim();
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 合法尾零、最小/最大金额及旧Java null-mode保持兼容，不按BigDecimal scale比较相等。 */
  @Test
  void healthyRecoveryTupleKeepsMoneyAndLegacyObjectCompatibility() {
    for (String amount : java.util.List.of("10.000", "0.01", "9999999999999999.99")) {
      setUp();
      PayDebitIntentPO original = intent();
      original.amount = new BigDecimal(amount);
      original.debitMode = null;
      var payment = payRecordMapper.selectByPayNo("pay-1").get(0);
      payment.amount = original.amount.setScale(2);
      payment.debitMode = null;
      org.mockito.Mockito.clearInvocations(payRecordMapper);
      when(intentMapper.findByPayNo("pay-1")).thenReturn(original);
      when(intentMapper.claim(eq("pay-1"), anyString(), any(), any())).thenReturn(0);
      assertEquals("INIT", consistencyService.process("pay-1"));
      assertOnlyRecoveryReadsOrClaim();
    }
  }

  /** 核对拒绝后仅发生查询/已发出的claim，没有stage/retry/终态或远端副作用。 */
  private void assertOnlyRecoveryReadsOrClaim() {
    org.mockito.Mockito.verifyNoInteractions(
        userClient, orderClient, intentService, transactionManager);
    for (var invocation : org.mockito.Mockito.mockingDetails(intentMapper).getInvocations()) {
      org.junit.jupiter.api.Assertions.assertTrue(
          java.util.List.of("findByPayNo", "claim").contains(invocation.getMethod().getName()),
          invocation.toString());
    }
    for (var invocation : org.mockito.Mockito.mockingDetails(payRecordMapper).getInvocations()) {
      assertEquals("selectByPayNo", invocation.getMethod().getName());
    }
  }

  /** 只改变选定字段，不改变模式或状态，金额均为真实BigDecimal输入。 */
  private static void corruptRecoveryTuple(PayDebitIntentPO tuple, String variant) {
    switch (variant) {
      case "pay-alias" -> tuple.payNo = "PAY-1";
      case "pay-missing" -> tuple.payNo = null;
      case "order-other" -> tuple.orderNo = "order-other";
      case "order-blank" -> tuple.orderNo = " ";
      case "order-missing" -> tuple.orderNo = null;
      case "user-other" -> tuple.userId = 8L;
      case "user-zero" -> tuple.userId = 0L;
      case "user-missing" -> tuple.userId = null;
      case "money-other" -> tuple.amount = new BigDecimal("11.00");
      case "money-zero" -> tuple.amount = BigDecimal.ZERO;
      case "money-negative" -> tuple.amount = new BigDecimal("-1.00");
      case "money-fraction" -> tuple.amount = new BigDecimal("10.001");
      case "money-overflow" -> tuple.amount = new BigDecimal("10000000000000000.00");
      case "money-missing" -> tuple.amount = null;
      default -> throw new AssertionError(variant);
    }
  }

  /** 健康缺失和拒绝来源经两次User读取后仍可FAILED，读取在实际事务外。 */
  @Test
  void latestStableDebitKeepsHealthyFailurePaths() {
    for (String path : java.util.List.of("absence", "rejected")) {
      for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
        if ("absence".equals(path) && !"CANCELLED".equals(orderStatus)) continue;
        setUp();
        configureOrderIdentityPath(
            "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1");
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", orderStatus, new BigDecimal("10.000"), 7L, "SUCCEEDED")));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  reads.incrementAndGet();
                  if ("absence".equals(path))
                    throw feign.FeignException.errorStatus(
                        "operation",
                        feign.Response.builder()
                            .status(404)
                            .reason("controlled-missing")
                            .request(
                                feign.Request.create(
                                    feign.Request.HttpMethod.GET,
                                    "http://cloud-mall-user/controlled-proof",
                                    java.util.Map.of(),
                                    null,
                                    java.nio.charset.StandardCharsets.UTF_8))
                            .headers(java.util.Map.of())
                            .body(new byte[0])
                            .build());
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "REJECTED", "10.000", null));
                })
            .when(userClient)
            .operation(7L, "pay-1", 7L);
        assertEquals("FAILED", consistencyService.process("pay-1"));
        assertEquals(2, reads.get());
        verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("FAILED"), any());
        verify(userClient, never()).debit(any(), any(), any());
        verify(userClient, never()).refund(any(), any(), any());
        verify(orderClient, never()).paid(any(), any());
      }
    }
  }

  /** 末次失败证据变化后，同支付修正原账本可退款或明确拒绝，无新扣款。 */
  @Test
  void latestFailureProofCanResumeAfterEvidenceRepair() {
    for (String scenario : java.util.List.of("absence", "rejected", "marker-repair")) {
      String path = "absence".equals(scenario) ? "absence" : "rejected";
      setUp();
      configureOrderIdentityPath(
          "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1");
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                if (reads.incrementAndGet() == 1) {
                  if ("absence".equals(path))
                    throw feign.FeignException.errorStatus(
                        "operation",
                        feign.Response.builder()
                            .status(404)
                            .reason("controlled-missing")
                            .request(
                                feign.Request.create(
                                    feign.Request.HttpMethod.GET,
                                    "http://cloud-mall-user/controlled-proof",
                                    java.util.Map.of(),
                                    null,
                                    java.nio.charset.StandardCharsets.UTF_8))
                            .headers(java.util.Map.of())
                            .body(new byte[0])
                            .build());
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "REJECTED", "10.00", null));
                }
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1",
                        "DEBIT",
                        "marker-repair".equals(scenario) ? "REJECTED" : "APPLIED",
                        "10.000",
                        "marker-repair".equals(scenario) ? "refund:pay-1" : null));
              })
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      assertEquals("INIT", consistencyService.process("pay-1"));
      assertEquals(2, reads.get());

      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1",
                      "DEBIT",
                      "marker-repair".equals(scenario) ? "REJECTED" : "APPLIED",
                      "10.000",
                      null)))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")))
          .when(orderClient)
          .paymentState("order-1", 7L);
      String expectedTerminal = "marker-repair".equals(scenario) ? "FAILED" : "REFUNDED";
      assertEquals(expectedTerminal, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(expectedTerminal), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times("marker-repair".equals(scenario) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 失败终态前原扣款来源变化不能被早期缺失或拒绝覆盖。 */
  @Test
  void latestDebitProofIsRequiredBeforeFailure() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("absence", "rejected")) {
      for (String variant :
          java.util.List.of(
              "applied",
              "marker",
              "marker-blank",
              "key-null",
              "key-other",
              "key-alias",
              "type",
              "amount-null",
              "amount-other",
              "state-null",
              "state-other",
              "null-envelope",
              "null-body",
              "bad-envelope",
              "fault",
              "missing-or-rejected")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(
                  "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1");
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (reads.incrementAndGet() == 1) {
                          if ("absence".equals(path))
                            throw feign.FeignException.errorStatus(
                                "operation",
                                feign.Response.builder()
                                    .status(404)
                                    .reason("controlled-missing")
                                    .request(
                                        feign.Request.create(
                                            feign.Request.HttpMethod.GET,
                                            "http://cloud-mall-user/controlled-proof",
                                            java.util.Map.of(),
                                            null,
                                            java.nio.charset.StandardCharsets.UTF_8))
                                    .headers(java.util.Map.of())
                                    .body(new byte[0])
                                    .build());
                          return ApiResponse.ok(
                              new UserClient.BalanceOperationView(
                                  "pay-1", "DEBIT", "REJECTED", "10.00", null));
                        }
                        if ("missing-or-rejected".equals(variant) && "rejected".equals(path))
                          throw feign.FeignException.errorStatus(
                              "operation",
                              feign.Response.builder()
                                  .status(404)
                                  .reason("controlled-missing")
                                  .request(
                                      feign.Request.create(
                                          feign.Request.HttpMethod.GET,
                                          "http://cloud-mall-user/controlled-proof",
                                          java.util.Map.of(),
                                          null,
                                          java.nio.charset.StandardCharsets.UTF_8))
                                  .headers(java.util.Map.of())
                                  .body(new byte[0])
                                  .build());
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-failure-proof-unavailable");
                        if ("null-envelope".equals(variant)) return null;
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "key-null".equals(variant)
                                    ? null
                                    : "key-other".equals(variant)
                                        ? "pay-other"
                                        : "key-alias".equals(variant) ? "PAY-1" : "pay-1",
                                "type".equals(variant) ? "REFUND" : "DEBIT",
                                "applied".equals(variant)
                                    ? "APPLIED"
                                    : "state-null".equals(variant)
                                        ? null
                                        : "state-other".equals(variant) ? "UNKNOWN" : "REJECTED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant) ? "11.00" : "10.000",
                                "marker".equals(variant)
                                    ? "refund:pay-1"
                                    : "marker-blank".equals(variant) ? "" : null));
                      })
                  .when(userClient)
                  .operation(7L, "pay-1", 7L);
              String result = consistencyService.process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals("INIT", result),
                  () -> assertEquals(2, reads.get()),
                  () -> verify(userClient, never()).debit(any(), any(), any()),
                  () -> verify(userClient, never()).refund(any(), any(), any()),
                  () -> verify(orderClient, never()).paid(any(), any()));
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"), anyString(), eq("INIT"), anyString(), any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 三类退款入口当前订单正确取消时保持原REFUNDED，Order GET在终态事务外。 */
  @Test
  void latestValidOrderKeepsHealthyRefundedPaths() {
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      setUp();
      configureOrderIdentityPath(
          "marker".equals(path)
              ? "refund-marker"
              : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
          "order-1");
      var originalIntent = intent();
      var durableIntent = intent();
      when(intentMapper.findByPayNo("pay-1"))
          .thenReturn(originalIntent, originalIntent, durableIntent);
      when(intentMapper.updateOwnedLegacyStatus(anyString(), anyString(), anyString(), any()))
          .thenAnswer(
              invocation -> {
                durableIntent.status = invocation.getArgument(2);
                return 1;
              });
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                int readNumber = reads.incrementAndGet();
                return ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1",
                        "after-paid".equals(path) && readNumber == 1
                            ? "PENDING_PAYMENT"
                            : "CANCELLED",
                        new BigDecimal("10.000"),
                        7L,
                        "SUCCEEDED"));
              })
          .when(orderClient)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", consistencyService.process("pay-1"));
      assertEquals("after-paid".equals(path) ? 3 : 2, reads.get());
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orderClient, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
          .paid(any(), any());
      verify(userClient, org.mockito.Mockito.times(2)).operation(eq(7L), anyString(), eq(7L));
    }
  }

  /** 退款已发或marker已证明后补最新取消证据，同pay恢复不再发退款。 */
  @Test
  void latestOrderProofCanResumeAfterEvidenceRepair() {
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      setUp();
      configureOrderIdentityPath(
          "marker".equals(path)
              ? "refund-marker"
              : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
          "order-1");
      var originalIntent = intent();
      var durableIntent = intent();
      when(intentMapper.findByPayNo("pay-1"))
          .thenReturn(originalIntent, originalIntent, durableIntent);
      when(intentMapper.updateOwnedLegacyStatus(anyString(), anyString(), anyString(), any()))
          .thenAnswer(
              invocation -> {
                durableIntent.status = invocation.getArgument(2);
                return 1;
              });
      int expectedReads = "after-paid".equals(path) ? 3 : 2;
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                int readNumber = reads.incrementAndGet();
                String status =
                    readNumber < expectedReads
                        ? "after-paid".equals(path) && readNumber == 1
                            ? "PENDING_PAYMENT"
                            : "CANCELLED"
                        : "PAID";
                return ApiResponse.ok(
                    new OrderClient.OrderView("order-1", status, BigDecimal.TEN, 7L, "SUCCEEDED"));
              })
          .when(orderClient)
          .paymentState("order-1", 7L);
      String expectedRetry = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
      assertEquals(expectedRetry, consistencyService.process("pay-1"));
      assertEquals(expectedReads, reads.get());
      var retriedIntent = intent();
      retriedIntent.status = expectedRetry;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(retriedIntent);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", "APPLIED", "10.000", "refund:pay-1")))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")))
          .when(orderClient)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orderClient, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
          .paid(any(), any());
    }
  }

  /** 退款终态前需当前取消/库存证据，不能沿用User退款操作前的旧Order。 */
  @Test
  void latestOrderProofIsRequiredBeforeRefunded() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      for (String variant :
          java.util.List.of(
              "pending",
              "paid",
              "completed",
              "stock-pending",
              "stock-unknown",
              "stock-null",
              "order-null",
              "order-other",
              "order-alias",
              "user-other",
              "amount-null",
              "amount-other",
              "null-body",
              "fault",
              "bad-envelope")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(
                  "marker".equals(path)
                      ? "refund-marker"
                      : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
                  "order-1");
              var originalIntent = intent();
              var durableIntent = intent();
              when(intentMapper.findByPayNo("pay-1"))
                  .thenReturn(originalIntent, originalIntent, durableIntent);
              when(intentMapper.updateOwnedLegacyStatus(
                      anyString(), anyString(), anyString(), any()))
                  .thenAnswer(
                      invocation -> {
                        durableIntent.status = invocation.getArgument(2);
                        return 1;
                      });
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              int expectedReads = "after-paid".equals(path) ? 3 : 2;
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        int readNumber = reads.incrementAndGet();
                        if (readNumber < expectedReads)
                          return ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1",
                                  "after-paid".equals(path) && readNumber == 1
                                      ? "PENDING_PAYMENT"
                                      : "CANCELLED",
                                  BigDecimal.TEN,
                                  7L,
                                  "SUCCEEDED"));
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-order-recheck-unavailable");
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-null".equals(variant)
                                    ? null
                                    : "order-other".equals(variant)
                                        ? "order-other"
                                        : "order-alias".equals(variant) ? "ORDER-1" : "order-1",
                                "pending".equals(variant)
                                    ? "PENDING_PAYMENT"
                                    : "paid".equals(variant)
                                        ? "PAID"
                                        : "completed".equals(variant) ? "COMPLETED" : "CANCELLED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant)
                                        ? new BigDecimal("11.00")
                                        : new BigDecimal("10.000"),
                                "user-other".equals(variant) ? 8L : 7L,
                                "stock-pending".equals(variant)
                                    ? "PENDING"
                                    : "stock-unknown".equals(variant)
                                        ? "UNKNOWN"
                                        : "stock-null".equals(variant) ? null : "SUCCEEDED"));
                      })
                  .when(orderClient)
                  .paymentState("order-1", 7L);
              String expected = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
              String result = consistencyService.process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(expected, result),
                  () -> assertEquals(expectedReads, reads.get()),
                  () ->
                      verify(userClient, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
                          .refund(any(), any(), any()),
                  () ->
                      verify(
                              orderClient,
                              org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
                          .paid(any(), any()),
                  () -> verify(userClient, never()).debit(any(), any(), any()));
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"), anyString(), eq(expected), anyString(), any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 三类成功路径须观察两次原账本，最新正确APPLIED且无退款标记才成功。 */
  @Test
  void latestValidDebitKeepsHealthySuccessPaths() {
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      setUp();
      configureOrderIdentityPath(path, "order-1");
      if (!"paid".equals(path))
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                ApiResponse.ok(
                    new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                if (reads.incrementAndGet() == 1 && "fresh-debit".equals(path))
                  throw feign.FeignException.errorStatus(
                      "operation",
                      feign.Response.builder()
                          .status(404)
                          .reason("controlled-missing")
                          .request(
                              feign.Request.create(
                                  feign.Request.HttpMethod.GET,
                                  "http://cloud-mall-user/controlled-proof",
                                  java.util.Map.of(),
                                  null,
                                  java.nio.charset.StandardCharsets.UTF_8))
                          .headers(java.util.Map.of())
                          .body(new byte[0])
                          .build());
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "APPLIED", "10.000", null));
              })
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      assertEquals("SUCCESS", consistencyService.process("pay-1"));
      assertEquals(2, reads.get());
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
      verify(userClient, org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
          .debit(any(), any(), any());
      verify(orderClient, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1))
          .paid(any(), any());
      verify(userClient, never()).refund(any(), any(), any());
    }
  }

  /** 末次账本缺证后，同支付补正确账本再恢复，不重发此前扣款。 */
  @Test
  void latestDebitProofCanResumeAfterEvidenceRepair() {
    String path = "fresh-debit";
    setUp();
    configureOrderIdentityPath(path, "order-1");
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              reads.incrementAndGet();
              throw feign.FeignException.errorStatus(
                  "operation",
                  feign.Response.builder()
                      .status(404)
                      .reason("controlled-missing")
                      .request(
                          feign.Request.create(
                              feign.Request.HttpMethod.GET,
                              "http://cloud-mall-user/controlled-proof",
                              java.util.Map.of(),
                              null,
                              java.nio.charset.StandardCharsets.UTF_8))
                      .headers(java.util.Map.of())
                      .body(new byte[0])
                      .build());
            })
        .when(userClient)
        .operation(7L, "pay-1", 7L);
    assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
    assertEquals(2, reads.get());
    var retriedIntent = intent();
    retriedIntent.status = "ORDER_SYNC_UNKNOWN";
    when(intentMapper.findByPayNo("pay-1")).thenReturn(retriedIntent);
    org.mockito.Mockito.doReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
        .when(userClient)
        .operation(7L, "pay-1", 7L);
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
    assertEquals("SUCCESS", consistencyService.process("pay-1"));
    verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
    verify(userClient).debit(any(), any(), any());
    verify(orderClient).paid(any(), any());
    verify(userClient, org.mockito.Mockito.times(4)).operation(7L, "pay-1", 7L);
    verify(userClient, never()).refund(any(), any(), any());
  }

  /** 成功前的最新原扣款缺证/矛盾不能被较早读或POST确认覆盖。 */
  @Test
  void latestDebitProofIsRequiredBeforeSuccess() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      for (String variant :
          java.util.List.of(
              "missing",
              "null-envelope",
              "null-body",
              "bad-envelope",
              "fault",
              "key-null",
              "key-other",
              "key-alias",
              "type",
              "amount-null",
              "amount-other",
              "rejected",
              "state-null",
              "marker",
              "marker-blank")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(path, "order-1");
              if (!"paid".equals(path))
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (reads.incrementAndGet() == 1) {
                          if ("fresh-debit".equals(path))
                            throw feign.FeignException.errorStatus(
                                "operation",
                                feign.Response.builder()
                                    .status(404)
                                    .reason("controlled-missing")
                                    .request(
                                        feign.Request.create(
                                            feign.Request.HttpMethod.GET,
                                            "http://cloud-mall-user/controlled-proof",
                                            java.util.Map.of(),
                                            null,
                                            java.nio.charset.StandardCharsets.UTF_8))
                                    .headers(java.util.Map.of())
                                    .body(new byte[0])
                                    .build());
                          return ApiResponse.ok(
                              new UserClient.BalanceOperationView(
                                  "pay-1", "DEBIT", "APPLIED", "10.00", null));
                        }
                        if ("missing".equals(variant))
                          throw feign.FeignException.errorStatus(
                              "operation",
                              feign.Response.builder()
                                  .status(404)
                                  .reason("controlled-missing")
                                  .request(
                                      feign.Request.create(
                                          feign.Request.HttpMethod.GET,
                                          "http://cloud-mall-user/controlled-proof",
                                          java.util.Map.of(),
                                          null,
                                          java.nio.charset.StandardCharsets.UTF_8))
                                  .headers(java.util.Map.of())
                                  .body(new byte[0])
                                  .build());
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-user-recheck-unavailable");
                        if ("null-envelope".equals(variant)) return null;
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "key-null".equals(variant)
                                    ? null
                                    : "key-other".equals(variant)
                                        ? "pay-other"
                                        : "key-alias".equals(variant) ? "PAY-1" : "pay-1",
                                "type".equals(variant) ? "REFUND" : "DEBIT",
                                "rejected".equals(variant)
                                    ? "REJECTED"
                                    : "state-null".equals(variant) ? null : "APPLIED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant) ? "11.00" : "10.000",
                                "marker".equals(variant)
                                    ? "refund:pay-1"
                                    : "marker-blank".equals(variant) ? "" : null));
                      })
                  .when(userClient)
                  .operation(7L, "pay-1", 7L);
              String result = consistencyService.process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals("ORDER_SYNC_UNKNOWN", result),
                  () -> assertEquals(2, reads.get()),
                  () ->
                      verify(
                              userClient,
                              org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
                          .debit(any(), any(), any()),
                  () ->
                      verify(orderClient, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1))
                          .paid(any(), any()),
                  () -> verify(userClient, never()).refund(any(), any(), any()));
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq("ORDER_SYNC_UNKNOWN"),
                      anyString(),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 六组退款方向在取消和库存完成时保原退款或已退款marker恢复。 */
  @Test
  void completedCancellationKeepsRefundAndMarkerRecovery() {
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if (!"REFUND_PENDING".equals(originalStatus) && !"REFUND_PENDING".equals(paymentStatus))
          continue;
        for (boolean markerPresent : java.util.List.of(false, true)) {
          setUp();
          configureOrderIdentityPath("applied-cancel", "order-1");
          var originalIntent = intent();
          originalIntent.status = originalStatus;
          when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
          payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
          org.mockito.Mockito.doReturn(
                  ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1",
                          "DEBIT",
                          "APPLIED",
                          "10.000",
                          markerPresent ? "refund:pay-1" : null)))
              .when(userClient)
              .operation(7L, "pay-1", 7L);
          assertEquals("REFUNDED", consistencyService.process("pay-1"));
          verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
          verify(userClient, never()).debit(any(), any(), any());
          verify(userClient, org.mockito.Mockito.times(markerPresent ? 0 : 1))
              .refund(any(), any(), any());
          verify(orderClient, never()).paid(any(), any());
        }
      }
    }
  }

  /** 取消/回滚证据补齐后，同支付下一次恢复只执行原退款一次。 */
  @Test
  void refundRecoveryCanResumeAfterCancellationEvidence() {
    for (String paymentStatus : java.util.List.of("PENDING", "REFUND_PENDING")) {
      String originalStatus = "PENDING".equals(paymentStatus) ? "REFUND_PENDING" : "INIT";
      setUp();
      configureOrderIdentityPath("applied-cancel", "order-1");
      var originalIntent = intent();
      originalIntent.status = originalStatus;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
      payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;

      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("REFUND_PENDING", consistencyService.process("pay-1"));
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
      var retriedIntent = intent();
      retriedIntent.status = "REFUND_PENDING";
      when(intentMapper.findByPayNo("pay-1")).thenReturn(retriedIntent);
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")));
      assertEquals("REFUNDED", consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 已记录退款方向但取消/库存证据不完整时，不得回到正向支付或写阶段。 */
  @Test
  void refundRecoveryRequiresCancelledOrderAndCompletedStock() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if (!"REFUND_PENDING".equals(originalStatus) && !"REFUND_PENDING".equals(paymentStatus))
          continue;
        for (String orderStatus :
            java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
          for (String stockAction :
              java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
            if ("CANCELLED".equals(orderStatus) && "SUCCEEDED".equals(stockAction)) continue;
            assertions.add(
                () -> {
                  setUp();
                  configureOrderIdentityPath("applied-cancel", "order-1");
                  var originalIntent = intent();
                  originalIntent.status = originalStatus;
                  when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
                  payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
                  org.mockito.Mockito.clearInvocations(payRecordMapper);
                  when(orderClient.paymentState("order-1", 7L))
                      .thenReturn(
                          ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1",
                                  orderStatus,
                                  new BigDecimal("10.000"),
                                  7L,
                                  stockAction)));
                  String result = consistencyService.process("pay-1");
                  org.junit.jupiter.api.Assertions.assertAll(
                      () -> assertEquals("REFUND_PENDING", result),
                      () -> verify(orderClient, never()).paid(any(), any()),
                      () -> verify(userClient, never()).debit(any(), any(), any()),
                      () -> verify(userClient, never()).refund(any(), any(), any()));
                  verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
                  verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
                  verify(payRecordMapper, never()).markOwnedLegacyOrderUnknown(any(), any(), any());
                  verify(payRecordMapper, never())
                      .markOwnedLegacyRefundPending(any(), any(), any());
                  verify(payRecordMapper, never())
                      .markOwnedLegacySuccess(any(), any(), any(), any());
                  verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
                  verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
                  verify(payRecordMapper, org.mockito.Mockito.times(2)).selectByPayNo("pay-1");
                  verify(intentMapper)
                      .recordRetryOwnedLegacy(
                          eq("pay-1"),
                          anyString(),
                          eq("REFUND_PENDING"),
                          org.mockito.ArgumentMatchers.contains("退款恢复阶段缺少订单取消或库存回滚证据"),
                          any(),
                          any());
                });
          }
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 正常拒绝仍FAILED，冲突来源修正后同支付可恢复成功/退款/初始拒绝。 */
  @Test
  void rejectedDebitKeepsHealthyFailureAndCanResumeAfterRepair() {
    for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
      setUp();
      configureOrderIdentityPath("rejected", "order-1");
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", orderStatus, BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("FAILED", consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("FAILED"), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
    for (String scenario : java.util.List.of("advanced-success", "advanced-refund", "marker")) {
      boolean refundPending = "advanced-refund".equals(scenario);
      boolean markerOnly = "marker".equals(scenario);
      String originalStatus = refundPending ? "REFUND_PENDING" : markerOnly ? "INIT" : "DEBITED";
      String paymentStatus = refundPending ? "REFUND_PENDING" : "PENDING";
      setUp();
      configureOrderIdentityPath("rejected", "order-1");
      var originalIntent = intent();
      originalIntent.status = originalStatus;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
      payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
      if (markerOnly)
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "REJECTED", "10.000", "refund:pay-1")))
            .when(userClient)
            .operation(7L, "pay-1", 7L);
      String expectedRetry =
          refundPending ? "REFUND_PENDING" : markerOnly ? "INIT" : "ORDER_SYNC_UNKNOWN";
      assertEquals(expectedRetry, consistencyService.process("pay-1"));
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
      var retriedIntent = intent();
      retriedIntent.status = expectedRetry;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(retriedIntent);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", markerOnly ? "REJECTED" : "APPLIED", "10.000", null)))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      refundPending || markerOnly ? "CANCELLED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      String expectedTerminal = refundPending ? "REFUNDED" : markerOnly ? "FAILED" : "SUCCESS";
      assertEquals(expectedTerminal, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(expectedTerminal), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times(refundPending ? 1 : 0))
          .refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 扣款拒绝与已推进阶段矛盾时，不能直接将支付恢复抹为FAILED。 */
  @Test
  void rejectedDebitCannotEraseAdvancedRecovery() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if ("INIT".equals(originalStatus) && "PENDING".equals(paymentStatus)) continue;
        for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
          assertions.add(
              () -> {
                setUp();
                configureOrderIdentityPath("rejected", "order-1");
                var originalIntent = intent();
                originalIntent.status = originalStatus;
                when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
                payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
                org.mockito.Mockito.clearInvocations(payRecordMapper);
                org.mockito.Mockito.doReturn(
                        ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1", "DEBIT", "REJECTED", "10.000", null)))
                    .when(userClient)
                    .operation(7L, "pay-1", 7L);
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                "SUCCEEDED")));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                            || "REFUND_PENDING".equals(paymentStatus)
                        ? "REFUND_PENDING"
                        : "DEBITED".equals(originalStatus)
                                || "ORDER_SYNC_UNKNOWN".equals(originalStatus)
                                || "ORDER_SYNC_UNKNOWN".equals(paymentStatus)
                            ? "ORDER_SYNC_UNKNOWN"
                            : "INIT";
                assertEquals(expected, consistencyService.process("pay-1"));
                verify(userClient, never()).debit(any(), any(), any());
                verify(userClient, never()).refund(any(), any(), any());
                verify(orderClient, never()).paid(any(), any());
                verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
                verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
                verify(payRecordMapper, org.mockito.Mockito.times(2)).selectByPayNo("pay-1");
                verify(intentMapper)
                    .recordRetryOwnedLegacy(
                        eq("pay-1"),
                        anyString(),
                        eq(expected),
                        org.mockito.ArgumentMatchers.contains("扣款拒绝事实与资金恢复状态不一致"),
                        any(),
                        any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** REJECTED携任意非NULL退款标记属于冲突来源，不能自动FAILED。 */
  @Test
  void rejectedDebitCannotContainRefundMarker() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    String originalStatus = "INIT";
    String paymentStatus = "PENDING";
    for (String refundMarker : java.util.List.of("refund:pay-1", "refund-other", "", " ")) {
      for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath("rejected", "order-1");
              var originalIntent = intent();
              originalIntent.status = originalStatus;
              when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
              payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
              org.mockito.Mockito.clearInvocations(payRecordMapper);
              org.mockito.Mockito.doReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "pay-1", "DEBIT", "REJECTED", "10.000", refundMarker)))
                  .when(userClient)
                  .operation(7L, "pay-1", 7L);
              when(orderClient.paymentState("order-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new OrderClient.OrderView(
                              "order-1", orderStatus, new BigDecimal("10.000"), 7L, "SUCCEEDED")));
              String expected =
                  "REFUND_PENDING".equals(originalStatus) || "REFUND_PENDING".equals(paymentStatus)
                      ? "REFUND_PENDING"
                      : "DEBITED".equals(originalStatus)
                              || "ORDER_SYNC_UNKNOWN".equals(originalStatus)
                              || "ORDER_SYNC_UNKNOWN".equals(paymentStatus)
                          ? "ORDER_SYNC_UNKNOWN"
                          : "INIT";
              assertEquals(expected, consistencyService.process("pay-1"));
              verify(userClient, never()).debit(any(), any(), any());
              verify(userClient, never()).refund(any(), any(), any());
              verify(orderClient, never()).paid(any(), any());
              verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(payRecordMapper, org.mockito.Mockito.times(2)).selectByPayNo("pay-1");
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq(expected),
                      org.mockito.ArgumentMatchers.contains("扣款拒绝事实与资金恢复状态不一致"),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** INIT与支付记录阶段错位缺证后，补原账本仍可沿同支付恢复。 */
  @Test
  void paymentProjectionCanResumeAfterOriginalDebit() {
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      setUp();
      configureOrderIdentityPath("fresh-debit", "order-1");
      payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
      boolean refundPending = "REFUND_PENDING".equals(paymentStatus);
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      refundPending ? "CANCELLED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      assertEquals(paymentStatus, consistencyService.process("pay-1"));
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
      var retriedIntent = intent();
      retriedIntent.status = paymentStatus;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(retriedIntent);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      String expectedTerminal = refundPending ? "REFUNDED" : "SUCCESS";
      assertEquals(expectedTerminal, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(expectedTerminal), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times(refundPending ? 1 : 0))
          .refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** INIT意图不能覆盖已推进的支付记录来源；缺账本只保原重试方向。 */
  @Test
  void paymentProjectionPreventsFreshFundsWhenDebitMissing() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String orderStatus :
          java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
        for (String stockAction :
            java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
          assertions.add(
              () -> {
                setUp();
                configureOrderIdentityPath("fresh-debit", "order-1");
                payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
                org.mockito.Mockito.clearInvocations(payRecordMapper);
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String result = consistencyService.process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(paymentStatus, result),
                    () -> verify(userClient, never()).debit(any(), any(), any()),
                    () -> verify(orderClient, never()).paid(any(), any()),
                    () -> verify(userClient, never()).refund(any(), any(), any()));
                verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
                verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
                verify(payRecordMapper, org.mockito.Mockito.times(2)).selectByPayNo("pay-1");
                verify(intentMapper)
                    .recordRetryOwnedLegacy(
                        eq("pay-1"), anyString(), eq(paymentStatus), anyString(), any(), any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 原支付记录的等待方向在账本/订单故障及APPLIED之后失败时仍须保留。 */
  @Test
  void paymentProjectionRetryDirectionSurvivesReadFailures() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String failurePoint : java.util.List.of("user", "order", "bad-debit", "stock-pending")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath("fresh-debit", "order-1");
              payRecordMapper.selectByPayNo("pay-1").get(0).status = paymentStatus;
              org.mockito.Mockito.clearInvocations(payRecordMapper);
              if ("user".equals(failurePoint))
                org.mockito.Mockito.doThrow(
                        new IllegalStateException("controlled-user-read-failure"))
                    .when(userClient)
                    .operation(7L, "pay-1", 7L);
              else {
                org.mockito.Mockito.doReturn(
                        ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1",
                                "DEBIT",
                                "APPLIED",
                                "bad-debit".equals(failurePoint) ? "11.00" : "10.000",
                                null)))
                    .when(userClient)
                    .operation(7L, "pay-1", 7L);
                if ("order".equals(failurePoint))
                  when(orderClient.paymentState("order-1", 7L))
                      .thenThrow(new IllegalStateException("controlled-order-read-failure"));
                else
                  when(orderClient.paymentState("order-1", 7L))
                      .thenReturn(
                          ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1", "PAID", BigDecimal.TEN, 7L, "UNKNOWN")));
              }
              String result = consistencyService.process("pay-1");
              assertEquals(paymentStatus, result);
              verify(userClient, never()).debit(any(), any(), any());
              verify(userClient, never()).refund(any(), any(), any());
              verify(orderClient, never()).paid(any(), any());

              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(payRecordMapper, org.mockito.Mockito.times(2)).selectByPayNo("pay-1");
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"), anyString(), eq(paymentStatus), anyString(), any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 推进阶段缺证后补原账本，下一次同支付恢复成功或完成原退款。 */
  @Test
  void advancedRecoveryResumesAfterOriginalDebitProof() {
    for (String originalStatus :
        java.util.List.of("DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      setUp();
      configureOrderIdentityPath("fresh-debit", "order-1");
      var originalIntent = intent();
      originalIntent.status = originalStatus;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
      boolean refundPending = "REFUND_PENDING".equals(originalStatus);
      String orderStatus =
          refundPending ? "CANCELLED" : "DEBITED".equals(originalStatus) ? "PAID" : "COMPLETED";
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", orderStatus, new BigDecimal("10.000"), 7L, "SUCCEEDED")));
      String expectedRetry = refundPending ? "REFUND_PENDING" : "ORDER_SYNC_UNKNOWN";
      assertEquals(expectedRetry, consistencyService.process("pay-1"));
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());

      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      String expectedTerminal = refundPending ? "REFUNDED" : "SUCCESS";
      assertEquals(expectedTerminal, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(expectedTerminal), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(userClient, org.mockito.Mockito.times(refundPending ? 1 : 0))
          .refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 已推进资金阶段不能将缺失账本当作未扣款；只保留原方向重试。 */
  @Test
  void missingDebitProofPreservesAdvancedRecovery() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String orderStatus :
          java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
        for (String stockAction :
            java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
          assertions.add(
              () -> {
                setUp();
                configureOrderIdentityPath("fresh-debit", "order-1");
                var originalIntent = intent();
                originalIntent.status = originalStatus;
                when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                        ? "REFUND_PENDING"
                        : "ORDER_SYNC_UNKNOWN";
                String result = consistencyService.process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(expected, result),
                    () -> verify(userClient, never()).debit(any(), any(), any()),
                    () -> verify(orderClient, never()).paid(any(), any()),
                    () -> verify(userClient, never()).refund(any(), any(), any()));
                verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
                verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
                verify(intentMapper)
                    .recordRetryOwnedLegacy(
                        eq("pay-1"),
                        anyString(),
                        eq(expected),
                        org.mockito.ArgumentMatchers.contains("已有资金恢复状态但原扣款账本缺失"),
                        any(),
                        any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 原支付缺证后读到正确APPLIED账本可恢复成功，不再次发扣款。 */
  @Test
  void originalDebitProofCanResumeWithoutRepeatingFunds() {
    for (String scenario : java.util.List.of("PAID", "COMPLETED", "bad-ack")) {
      setUp();
      configureOrderIdentityPath("fresh-debit", "order-1");
      if ("bad-ack".equals(scenario))
        when(userClient.debit(eq(7L), eq(7L), any()))
            .thenReturn(
                ApiResponse.ok(new UserClient.BalanceView("pay-other", "APPLIED", "90.00")));
      else
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", scenario, BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("INIT", consistencyService.process("pay-1"));

      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      "COMPLETED".equals(scenario) ? "COMPLETED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      assertEquals("SUCCESS", consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
      verify(userClient, org.mockito.Mockito.times("bad-ack".equals(scenario) ? 1 : 0))
          .debit(any(), any(), any());
      verify(userClient, never()).refund(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
    }
  }

  /** 未观察当前支付扣款时，已支付订单不得借终态再触发新扣款。 */
  @Test
  void paidOrderWithoutDebitCannotStartNewFunds() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String orderStatus : java.util.List.of("PAID", "COMPLETED")) {
      for (String stockAction : java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
        for (String originalStatus :
            java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
          assertions.add(
              () -> {
                setUp();
                configureOrderIdentityPath("fresh-debit", "order-1");
                var originalIntent = intent();
                originalIntent.status = originalStatus;
                when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                        ? "REFUND_PENDING"
                        : "INIT".equals(originalStatus) ? "INIT" : "ORDER_SYNC_UNKNOWN";
                String errorMessage =
                    "INIT".equals(originalStatus) ? "订单已支付但未观察到原扣款事实" : "已有资金恢复状态但原扣款账本缺失";
                String result = consistencyService.process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(expected, result),
                    () ->
                        verify(userClient, org.mockito.Mockito.times(0)).debit(any(), any(), any()),
                    () -> verify(orderClient, never()).paid(any(), any()),
                    () -> verify(userClient, never()).refund(any(), any(), any()));
                verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
                verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
                verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
                verify(intentMapper)
                    .recordRetryOwnedLegacy(
                        eq("pay-1"),
                        anyString(),
                        eq(expected),
                        org.mockito.ArgumentMatchers.contains(errorMessage),
                        any(),
                        any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 扣款回执必须属于原支付，坏回执不能推进订单或本地终态。 */
  @Test
  void debitAcknowledgementMustNameOriginalPayment() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String acknowledgementKey :
        java.util.Arrays.asList(
            null, "", " ", "pay-other", "PAY-1", "páy-1", "pay-1 ", "refund:pay-1")) {
      for (String originalStatus : java.util.List.of("INIT")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath("fresh-debit", "order-1");
              var originalIntent = intent();
              originalIntent.status = originalStatus;
              when(intentMapper.findByPayNo("pay-1")).thenReturn(originalIntent);
              when(userClient.debit(eq(7L), eq(7L), any()))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceView(acknowledgementKey, "APPLIED", "90.00")));
              String expected =
                  "REFUND_PENDING".equals(originalStatus)
                      ? "REFUND_PENDING"
                      : "INIT".equals(originalStatus) ? "INIT" : "ORDER_SYNC_UNKNOWN";
              String errorMessage = "用户余额扣款未确认";
              String result = consistencyService.process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(expected, result),
                  () -> verify(userClient, org.mockito.Mockito.times(1)).debit(any(), any(), any()),
                  () -> verify(orderClient, never()).paid(any(), any()),
                  () -> verify(userClient, never()).refund(any(), any(), any()));
              verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq(expected),
                      org.mockito.ArgumentMatchers.contains(errorMessage),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 未知订单状态在七条恢复决策中都不能触发新资金写或支付终态。 */
  @Test
  void unknownOrderStatusCannotStartFundsOrFinishPayment() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path :
        java.util.List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      for (String variant :
          java.util.Arrays.asList(
              null,
              "",
              "UNKNOWN",
              "CLOSED",
              "paid",
              "PAID ",
              "COMPLETED ",
              "PENDING_PAYMENT ",
              "CANCELLED\n",
              "páid")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(path, "order-1");
              var response =
                  ApiResponse.ok(
                      new OrderClient.OrderView(
                          "order-1", variant, new BigDecimal("10.000"), 7L, "SUCCEEDED"));
              if ("after-paid".equals(path))
                when(orderClient.paymentState("order-1", 7L))
                    .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")), response);
              else when(orderClient.paymentState("order-1", 7L)).thenReturn(response);
              String expected =
                  java.util.List.of("no-debit-cancel", "fresh-debit", "rejected").contains(path)
                      ? "INIT"
                      : "ORDER_SYNC_UNKNOWN";
              assertEquals(expected, consistencyService.process("pay-1"), path + ":" + variant);
              verify(userClient, never()).debit(any(), any(), any());
              verify(userClient, never()).refund(any(), any(), any());
              verify(orderClient, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
                  .paid(any(), any());
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq(expected),
                      org.mockito.ArgumentMatchers.contains("订单支付状态暂不可判定"),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 未知订单状态保留已持久化的退款等待方向，不重扣或补退。 */
  @Test
  void unknownOrderStatusKeepsRefundPendingDirection() {
    configureOrderIdentityPath("applied-cancel", "order-1");
    var pending = intent();
    pending.status = "REFUND_PENDING";
    when(intentMapper.findByPayNo("pay-1")).thenReturn(pending);
    when(orderClient.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "UNKNOWN", BigDecimal.TEN, 7L, "SUCCEEDED")));
    assertEquals("REFUND_PENDING", consistencyService.process("pay-1"));
    verify(userClient, never()).debit(any(), any(), any());
    verify(userClient, never()).refund(any(), any(), any());
    verify(orderClient, never()).paid(any(), any());
    verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
    verify(intentMapper)
        .recordRetryOwnedLegacy(
            eq("pay-1"),
            anyString(),
            eq("REFUND_PENDING"),
            org.mockito.ArgumentMatchers.contains("订单支付状态暂不可判定"),
            any(),
            any());
  }

  /** PAID/COMPLETED仍须库存动作成功，原等待状态不能借状态白名单直接结清。 */
  @Test
  void recognizedPaidStatesKeepOriginalStockCompletionGate() {
    for (String status : java.util.List.of("PAID", "COMPLETED")) {
      for (String stockAction : java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
        setUp();
        configureOrderIdentityPath("paid", "order-1");
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", status, new BigDecimal("10.000"), 7L, stockAction)));
        String expected = "SUCCEEDED".equals(stockAction) ? "SUCCESS" : "ORDER_SYNC_UNKNOWN";
        assertEquals(expected, consistencyService.process("pay-1"));
        verify(intentMapper, org.mockito.Mockito.times("SUCCESS".equals(expected) ? 1 : 0))
            .finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
        verify(userClient, never()).debit(any(), any(), any());
        verify(userClient, never()).refund(any(), any(), any());
        verify(orderClient, never()).paid(any(), any());
      }
    }
  }

  /** 同用户/金额的错订单不得决定任一支付终态或触发新的资金写。 */
  @Test
  void everyOrderReadRequiresTheExactIntentOrderNumber() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path :
        java.util.List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      for (String variant :
          java.util.List.of(
              "other", "null", "blank", "case-alias", "accent-alias", "space-alias")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(path, wrongOrderNumber(variant));
              String expected =
                  java.util.List.of("no-debit-cancel", "fresh-debit", "rejected").contains(path)
                      ? "INIT"
                      : "ORDER_SYNC_UNKNOWN";
              assertEquals(expected, consistencyService.process("pay-1"), path + ":" + variant);
              verify(userClient, never()).debit(any(), any(), any());
              verify(userClient, never()).refund(any(), any(), any());
              verify(orderClient, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
                  .paid(any(), any());
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyFailed(any(), any(), any());
              verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq(expected),
                      org.mockito.ArgumentMatchers.contains("订单身份、归属或金额与支付意图不一致"),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已在等待退款的意图遇到错订单仍保持原退款恢复方向，不误完成或重新退款。 */
  @Test
  void wrongOrderKeepsTheDurableRefundPendingRetryDirection() {
    configureOrderIdentityPath("applied-cancel", "order-other");
    PayDebitIntentPO pendingRefund = intent();
    pendingRefund.status = "REFUND_PENDING";
    when(intentMapper.findByPayNo("pay-1")).thenReturn(pendingRefund);
    assertEquals("REFUND_PENDING", consistencyService.process("pay-1"));
    verify(userClient, never()).refund(any(), any(), any());
    verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
    verify(intentMapper)
        .recordRetryOwnedLegacy(
            eq("pay-1"), anyString(), eq("REFUND_PENDING"), anyString(), any(), any());
  }

  /** 原订单号与数值相等的金额尾零在全部决策路径保持原结果。 */
  @Test
  void exactOrderIdentityKeepsNormalDecisionsAndFollowupRead() {
    for (String path :
        java.util.List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      setUp();
      configureOrderIdentityPath(path, "order-1");
      String expected =
          switch (path) {
            case "no-debit-cancel", "rejected" -> "FAILED";
            case "fresh-debit", "paid" -> "SUCCESS";
            default -> "REFUNDED";
          };
      assertEquals(expected, consistencyService.process("pay-1"));
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq(expected), any());
      if ("fresh-debit".equals(path)) verify(userClient).debit(eq(7L), eq(7L), any());
      else verify(userClient, never()).debit(any(), any(), any());
      if (java.util.List.of("applied-cancel", "after-paid").contains(path))
        verify(userClient).refund(eq(7L), eq(7L), any());
      else verify(userClient, never()).refund(any(), any(), any());
      verify(
              orderClient,
              org.mockito.Mockito.times(
                  java.util.List.of("fresh-debit", "after-paid").contains(path) ? 1 : 0))
          .paid(any(), any());
    }
  }

  /** 只变返回orderNo，其它字段满足原事实，二次GET保留已发paid这一事实。 */
  private void configureOrderIdentityPath(String path, String returnedOrderNo) {
    if ("no-debit-cancel".equals(path))
      when(userClient.operation(7L, "pay-1", 7L)).thenThrow(refundReadFault(404));
    else if ("fresh-debit".equals(path))
      when(userClient.operation(7L, "pay-1", 7L))
          .thenThrow(refundReadFault(404))
          .thenReturn(ApiResponse.ok(appliedDebit()));
    else
      when(userClient.operation(7L, "pay-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1",
                      "DEBIT",
                      "rejected".equals(path) ? "REJECTED" : "APPLIED",
                      "10.00",
                      "refund-marker".equals(path) ? "refund:pay-1" : null)));
    when(userClient.debit(eq(7L), eq(7L), any()))
        .thenReturn(ApiResponse.ok(new UserClient.BalanceView("pay-1", "APPLIED", "90.00")));
    when(userClient.refund(eq(7L), eq(7L), any()))
        .thenReturn(
            ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));
    when(payRecordMapper.markOwnedLegacySuccess(any(), any(), any(), any())).thenReturn(1);
    when(payRecordMapper.markOwnedLegacyFailed(any(), any(), any())).thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefunded(any(), any(), any())).thenReturn(1);
    when(orderClient.paid("order-1", 7L)).thenReturn(ApiResponse.ok(order("PAID")));
    String status =
        "paid".equals(path) ? "PAID" : "fresh-debit".equals(path) ? "PENDING_PAYMENT" : "CANCELLED";
    var returned =
        new OrderClient.OrderView(
            returnedOrderNo, status, new BigDecimal("10.000"), 7L, "SUCCEEDED");
    if ("after-paid".equals(path))
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")), ApiResponse.ok(returned));
    else if ("fresh-debit".equals(path))
      when(orderClient.paymentState("order-1", 7L))
          .thenReturn(ApiResponse.ok(returned), ApiResponse.ok(order("PAID")));
    else when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(returned));
  }

  /** 错键覆盖缺失和MySQL CI可等价的别名，Java判断必须精确。 */
  private static String wrongOrderNumber(String variant) {
    return switch (variant) {
      case "other" -> "order-other";
      case "null" -> null;
      case "blank" -> " ";
      case "case-alias" -> "ORDER-1";
      case "accent-alias" -> "órder-1";
      case "space-alias" -> "order-1 ";
      default -> throw new AssertionError(variant);
    };
  }

  /** 已等待退款的支付单无需经过不允许该状态的订单同步stage。 */
  @Test
  void cancelledDebitResumesRefundWithoutRegressingThroughGenericOrderStage() {
    for (String status :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      prepareRefundPath(false, "refund:pay-1");
      PayDebitIntentPO pending = intent();
      pending.status = status;
      when(intentMapper.findByPayNo("pay-1")).thenReturn(pending);
      var payment = payRecordMapper.selectByPayNo("pay-1").get(0);
      payment.status = "REFUND_PENDING";
      org.mockito.Mockito.clearInvocations(payRecordMapper);
      when(payRecordMapper.markOwnedLegacyOrderUnknown(any(), any(), any())).thenReturn(0);
      assertEquals("REFUNDED", consistencyService.process("pay-1"), status);
      verify(payRecordMapper, never()).markOwnedLegacyOrderUnknown(any(), any(), any());
      verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), eq("DEBITED"), any());
      verify(intentMapper)
          .updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq("REFUND_PENDING"), any());
      verify(intentMapper).hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
      verify(userClient).refund(eq(7L), eq(7L), any());
      verify(userClient, never()).debit(any(), any(), any());
      verify(orderClient, never()).paid(any(), any());
      verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
    }
  }

  /** 全部坏头也推进、空尾回环，每轮不补短批且最多处理50条。 */
  @Test
  void boundedScanMovesPastFailuresAndWrapsAtTheEnd() {
    PayConsistencyService recovery = org.mockito.Mockito.spy(consistencyService);
    var blocked = new java.util.ArrayList<PayDebitIntentPO>();
    for (long identifier = 1; identifier <= 50; identifier++) {
      PayDebitIntentPO candidate = intent();
      candidate.id = identifier;
      candidate.payNo = "blocked-" + identifier;
      blocked.add(candidate);
    }
    PayDebitIntentPO healthy = intent();
    healthy.id = 51L;
    healthy.payNo = "healthy";
    when(intentMapper.findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50)))
        .thenReturn(blocked);
    when(intentMapper.findDueAfter(any(), eq(50L), eq(50))).thenReturn(java.util.List.of(healthy));
    when(intentMapper.findDueAfter(any(), eq(51L), eq(50))).thenReturn(java.util.List.of());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              String payNo = invocation.getArgument(0);
              if (payNo.startsWith("blocked-")) throw new IllegalStateException("owned-poison");
              return "INIT";
            })
        .when(recovery)
        .process(anyString());
    recovery.recoverDue();
    recovery.recoverDue();
    recovery.recoverDue();
    verify(recovery).process("healthy");
    verify(recovery, org.mockito.Mockito.times(101)).process(anyString());
    verify(intentMapper, org.mockito.Mockito.times(2))
        .findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50));
    verify(intentMapper).findDueAfter(any(), eq(50L), eq(50));
    verify(intentMapper).findDueAfter(any(), eq(51L), eq(50));
    verify(intentMapper, never()).findDue(any(), org.mockito.ArgumentMatchers.anyInt());
  }

  /** DAO错误不能提交扫描进度，下一轮从同一游标重试；空回环仍有界。 */
  @Test
  void scanQueryFailureDoesNotAdvanceOrResetTheCursor() {
    PayConsistencyService recovery = org.mockito.Mockito.spy(consistencyService);
    PayDebitIntentPO candidate = intent();
    candidate.id = 9L;
    when(intentMapper.findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50)))
        .thenReturn(java.util.List.of(candidate), java.util.List.of());
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException("owned-scan-fault");
    when(intentMapper.findDueAfter(any(), eq(9L), eq(50)))
        .thenThrow(failure)
        .thenReturn(java.util.List.of());
    org.mockito.Mockito.doReturn("INIT").when(recovery).process("pay-1");
    recovery.recoverDue();
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessResourceFailureException.class,
            recovery::recoverDue));
    recovery.recoverDue();
    verify(intentMapper, org.mockito.Mockito.times(2)).findDueAfter(any(), eq(9L), eq(50));
    verify(intentMapper, org.mockito.Mockito.times(2))
        .findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50));
    verify(recovery).process("pay-1");
  }

  /** 并发旧轮结束时不能覆盖另一个已完成轮次的游标；不启动监听或业务App。 */
  @Test
  void lateScanCompletionCannotOverwriteChangedCursor() throws Exception {
    PayConsistencyService recovery = org.mockito.Mockito.spy(consistencyService);
    PayDebitIntentPO slow = intent();
    slow.id = 50L;
    slow.payNo = "slow";
    PayDebitIntentPO newer = intent();
    newer.id = 100L;
    newer.payNo = "newer";
    when(intentMapper.findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50)))
        .thenReturn(java.util.List.of(slow), java.util.List.of(newer), java.util.List.of());
    when(intentMapper.findDueAfter(any(), eq(100L), eq(50))).thenReturn(java.util.List.of());
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              entered.countDown();
              org.junit.jupiter.api.Assertions.assertTrue(
                  release.await(10, java.util.concurrent.TimeUnit.SECONDS));
              throw new IllegalStateException("owned-slow-scan-fault");
            })
        .when(recovery)
        .process("slow");
    org.mockito.Mockito.doReturn("INIT").when(recovery).process("newer");
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var task = executor.submit(recovery::recoverDue);
      org.junit.jupiter.api.Assertions.assertTrue(
          entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
      recovery.recoverDue();
      release.countDown();
      task.get(10, java.util.concurrent.TimeUnit.SECONDS);
      recovery.recoverDue();
      verify(intentMapper).findDueAfter(any(), eq(100L), eq(50));
      verify(intentMapper, never()).findDueAfter(any(), eq(50L), eq(50));
    } finally {
      release.countDown();
      executor.shutdownNow();
      org.junit.jupiter.api.Assertions.assertTrue(
          executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** 缺失候选id不制造游标，下一轮保留原起点；实际数据库主键非NULL。 */
  @Test
  void missingCandidateIdDoesNotInventScanProgress() {
    PayConsistencyService recovery = org.mockito.Mockito.spy(consistencyService);
    PayDebitIntentPO candidate = intent();
    candidate.id = null;
    when(intentMapper.findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50)))
        .thenReturn(java.util.List.of(candidate));
    org.mockito.Mockito.doReturn("INIT").when(recovery).process("pay-1");
    recovery.recoverDue();
    recovery.recoverDue();
    verify(intentMapper, org.mockito.Mockito.times(2))
        .findDueAfter(any(), org.mockito.ArgumentMatchers.isNull(), eq(50));
  }

  /** 重置每个分支的独立替身，POST失败时持久意图已处于REFUND_PENDING。 */
  private void prepareRefundPath(boolean marker, String refundKey) {
    setUp();
    when(orderClient.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(order("CANCELLED")));
    when(payRecordMapper.markOwnedLegacyRefundPending(eq("pay-1"), anyString(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefunded(eq("pay-1"), anyString(), any())).thenReturn(1);
    when(userClient.operation(7L, "pay-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    "pay-1", "DEBIT", "APPLIED", "10.00", marker ? refundKey : null)));
    when(userClient.operation(7L, refundKey, 7L))
        .thenReturn(ApiResponse.ok(refundFact(refundKey, "10.00")));
    when(userClient.refund(eq(7L), eq(7L), any()))
        .thenReturn(
            ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));
    if (!marker) {
      PayDebitIntentPO pendingRefund = intent();
      pendingRefund.status = "REFUND_PENDING";
      when(intentMapper.findByPayNo("pay-1")).thenReturn(intent(), intent(), pendingRefund);
    }
  }

  /** 未证实全额退款不得写终态或再次扣款；错误仍落原重试状态。 */
  private void verifyRefundNotFinished(String retryStatus) {
    verify(payRecordMapper, never()).markOwnedLegacyRefunded(eq("pay-1"), anyString(), any());
    verify(payRecordMapper, never()).markOwnedLegacyFailed(eq("pay-1"), anyString(), any());
    verify(intentMapper, never()).finishOwnedLegacy(eq("pay-1"), anyString(), anyString(), any());
    verify(intentMapper)
        .recordRetryOwnedLegacy(
            eq("pay-1"), anyString(), eq(retryStatus), anyString(), any(), any());
    verify(userClient, never()).debit(any(), any(), any());
  }

  /** 仅构造远端可见字段，不将这些字段冒称User原snapshot或跨服务来源证明。 */
  private static UserClient.BalanceOperationView refundFact(String operationKey, String amount) {
    return new UserClient.BalanceOperationView(operationKey, "REFUND", "APPLIED", amount, null);
  }

  /** 每个变体只破坏一个退款响应字段或envelope。 */
  private static ApiResponse<UserClient.BalanceOperationView> badRefundResponse(
      String variant, String key) {
    if ("null-response".equals(variant)) return null;
    if ("null-body".equals(variant)) return ApiResponse.ok(null);
    if ("failed-envelope".equals(variant))
      return ApiResponse.error("OWNED_UNAVAILABLE", "owned failure");
    String operationKey =
        switch (variant) {
          case "key-null" -> null;
          case "key-alias" -> key.toUpperCase(java.util.Locale.ROOT);
          case "key-other" -> "other-refund";
          default -> key;
        };
    String amount =
        switch (variant) {
          case "amount-null" -> null;
          case "amount-partial" -> "2.00";
          case "amount-zero" -> "0";
          case "amount-negative" -> "-10";
          case "amount-fraction" -> "10.001";
          case "amount-overflow" -> "10000000000000000";
          case "amount-text" -> "not-money";
          default -> "10.00";
        };
    return ApiResponse.ok(
        new UserClient.BalanceOperationView(
            operationKey,
            "type-wrong".equals(variant) ? "DEBIT" : "REFUND",
            "state-wrong".equals(variant) ? "UNKNOWN" : "APPLIED",
            amount,
            null));
  }

  /** 使用真实Feign异常类型验证404语义边界，不启动网络请求。 */
  private static feign.FeignException refundReadFault(int status) {
    Request request =
        Request.create(
            Request.HttpMethod.GET,
            "http://cloud-mall-user/owned-refund-proof",
            Map.of(),
            null,
            StandardCharsets.UTF_8);
    return feign.FeignException.errorStatus(
        "operation",
        feign.Response.builder()
            .status(status)
            .reason("owned fault")
            .request(request)
            .headers(Map.of())
            .body(new byte[0])
            .build());
  }

  /** 缺失原载荷时先拒绝，再由事务管理器回滚，不能尝试任何终态写。 */
  @Test
  void missingTerminalSourceRollsBackWithoutWrites() {
    when(payRecordMapper.lockOwnedLegacyTerminalSource(any(), anyString(), any())).thenReturn(null);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    consistencyService,
                    "commitOwnedLegacyTerminal",
                    intent(),
                    "owned-token",
                    "SUCCESS"));
    assertEquals("支付终态原载荷或领取资格已变化", failure.getMessage());
    verify(payRecordMapper, never()).markOwnedLegacySuccess(anyString(), anyString(), any(), any());
    verify(payRecordMapper, never()).markOwnedLegacyFailed(anyString(), anyString(), any());
    verify(payRecordMapper, never()).markOwnedLegacyRefunded(anyString(), anyString(), any());
    verify(intentMapper, never()).finishOwnedLegacy(anyString(), anyString(), anyString(), any());
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());
    org.mockito.Mockito.verifyNoInteractions(userClient, orderClient);
  }

  /** 三终态都先核原快照，再依次执行两写并提交，不在短事务内调用远端。 */
  @Test
  void terminalSourceLockPrecedesBothWritesAndCommit() {
    when(payRecordMapper.markOwnedLegacySuccess(anyString(), anyString(), any(), any()))
        .thenReturn(1);
    when(payRecordMapper.markOwnedLegacyFailed(anyString(), anyString(), any())).thenReturn(1);
    when(payRecordMapper.markOwnedLegacyRefunded(anyString(), anyString(), any())).thenReturn(1);
    for (String terminal : java.util.List.of("SUCCESS", "REFUNDED", "FAILED")) {
      var expected = intent();
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          consistencyService, "commitOwnedLegacyTerminal", expected, "owned-token", terminal);
      var ordered = org.mockito.Mockito.inOrder(payRecordMapper, intentMapper, transactionManager);
      ordered
          .verify(payRecordMapper)
          .lockOwnedLegacyTerminalSource(eq(expected), eq("owned-token"), any());
      switch (terminal) {
        case "SUCCESS" ->
            ordered
                .verify(payRecordMapper)
                .markOwnedLegacySuccess(eq("pay-1"), eq("owned-token"), any(), any());
        case "REFUNDED" ->
            ordered
                .verify(payRecordMapper)
                .markOwnedLegacyRefunded(eq("pay-1"), eq("owned-token"), any());
        case "FAILED" ->
            ordered
                .verify(payRecordMapper)
                .markOwnedLegacyFailed(eq("pay-1"), eq("owned-token"), any());
        default -> throw new AssertionError(terminal);
      }
      ordered
          .verify(intentMapper)
          .finishOwnedLegacy(eq("pay-1"), eq("owned-token"), eq(terminal), any());
      ordered.verify(transactionManager).commit(any());
      org.mockito.Mockito.clearInvocations(payRecordMapper, intentMapper, transactionManager);
    }
    org.mockito.Mockito.verifyNoInteractions(userClient, orderClient);
  }

  /** 受控末次Order事实；原接口异常语义仍由被测服务处理。 */
  private static ApiResponse<OrderClient.OrderView> successOrderProofResponse(String variant) {
    if ("fault".equals(variant))
      throw new IllegalStateException("controlled-success-order-unavailable");
    if ("null-response".equals(variant)) return null;
    if ("null-body".equals(variant)) return ApiResponse.ok(null);
    if ("bad-envelope".equals(variant))
      return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
    return ApiResponse.ok(
        new OrderClient.OrderView(
            "order-null".equals(variant)
                ? null
                : "order-other".equals(variant)
                    ? "other-order"
                    : "order-alias".equals(variant) ? "ORDER-1" : "order-1",
            "pending".equals(variant)
                ? "PENDING_PAYMENT"
                : "cancelled".equals(variant)
                    ? "CANCELLED"
                    : "status-null".equals(variant)
                        ? null
                        : "status-unknown".equals(variant)
                            ? "UNKNOWN"
                            : "completed".equals(variant) ? "COMPLETED" : "PAID",
            "amount-null".equals(variant)
                ? null
                : "amount-other".equals(variant)
                    ? new BigDecimal("11.00")
                    : new BigDecimal("10.000"),
            "user-null".equals(variant) ? null : "user-other".equals(variant) ? 8L : 7L,
            "stock-pending".equals(variant)
                ? "PENDING"
                : "stock-unknown".equals(variant)
                    ? "UNKNOWN"
                    : "stock-null".equals(variant)
                        ? null
                        : "stock-alias".equals(variant) ? "succeeded" : "SUCCEEDED"));
  }

  /** 三成功来源的末次订单缺证均等待，不进入本地终态事务或猜测退款。 */
  @Test
  void latestOrderProofIsRequiredBeforeSuccess() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      for (String variant :
          java.util.List.of(
              "pending",
              "cancelled",
              "status-null",
              "status-unknown",
              "stock-pending",
              "stock-unknown",
              "stock-null",
              "stock-alias",
              "order-null",
              "order-other",
              "order-alias",
              "user-null",
              "user-other",
              "amount-null",
              "amount-other",
              "null-response",
              "null-body",
              "bad-envelope",
              "fault")) {
        assertions.add(
            () -> {
              setUp();
              configureOrderIdentityPath(path, "order-1");
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              int expectedReads = "paid".equals(path) ? 2 : 3;
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        int readNumber = reads.incrementAndGet();
                        if (readNumber < expectedReads)
                          return successOrderProofResponse(
                              !"paid".equals(path) && readNumber == 1 ? "pending" : "paid");
                        verify(userClient, org.mockito.Mockito.times(2)).operation(7L, "pay-1", 7L);
                        return successOrderProofResponse(variant);
                      })
                  .when(orderClient)
                  .paymentState("order-1", 7L);
              assertEquals(
                  "ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"), path + ":" + variant);
              assertEquals(expectedReads, reads.get());
              verify(userClient, org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
                  .debit(any(), any(), any());
              verify(orderClient, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1))
                  .paid(any(), any());
              verify(userClient, never()).refund(any(), any(), any());
              verify(payRecordMapper, never())
                  .lockOwnedLegacyTerminalSource(any(), anyString(), any());
              verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
              verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
              verify(transactionManager).getTransaction(any());
              verify(transactionManager).commit(any());
              verify(transactionManager, never()).rollback(any());
              verify(intentMapper)
                  .recordRetryOwnedLegacy(
                      eq("pay-1"),
                      anyString(),
                      eq("ORDER_SYNC_UNKNOWN"),
                      anyString(),
                      any(),
                      any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 正确PAID或COMPLETED末次证明可成功；只增加一次Order GET。 */
  @Test
  void latestValidOrderKeepsHealthySuccessPaths() {
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      for (String terminalOrder : java.util.List.of("paid", "completed")) {
        setUp();
        configureOrderIdentityPath(path, "order-1");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        int expectedReads = "paid".equals(path) ? 2 : 3;
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  int readNumber = reads.incrementAndGet();
                  return successOrderProofResponse(
                      readNumber == expectedReads
                          ? terminalOrder
                          : !"paid".equals(path) && readNumber == 1 ? "pending" : "paid");
                })
            .when(orderClient)
            .paymentState("order-1", 7L);
        assertEquals("SUCCESS", consistencyService.process("pay-1"));
        assertEquals(expectedReads, reads.get());
        verify(userClient, org.mockito.Mockito.times(2)).operation(7L, "pay-1", 7L);
        verify(userClient, org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
            .debit(any(), any(), any());
        verify(orderClient, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1))
            .paid(any(), any());
        verify(userClient, never()).refund(any(), any(), any());
        verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
      }
    }
  }

  /** 最新原扣款错误优先；该错误不触发新增Order查询或终态锁。 */
  @Test
  void latestDebitFailurePrecedesSuccessOrderRecheck() {
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      setUp();
      configureOrderIdentityPath(path, "order-1");
      var userReads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                int readNumber = userReads.incrementAndGet();
                if (readNumber == 1 && "fresh-debit".equals(path)) throw refundReadFault(404);
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", readNumber == 1 ? "APPLIED" : "REJECTED", "10.00", null));
              })
          .when(userClient)
          .operation(7L, "pay-1", 7L);
      if (!"paid".equals(path))
        when(orderClient.paymentState("order-1", 7L))
            .thenReturn(successOrderProofResponse("pending"), successOrderProofResponse("paid"));
      assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
      assertEquals(2, userReads.get());
      verify(orderClient, org.mockito.Mockito.times("paid".equals(path) ? 1 : 2))
          .paymentState("order-1", 7L);
      verify(payRecordMapper, never()).lockOwnedLegacyTerminalSource(any(), anyString(), any());
      verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
      verify(intentMapper)
          .recordRetryOwnedLegacy(
              eq("pay-1"),
              anyString(),
              eq("ORDER_SYNC_UNKNOWN"),
              org.mockito.ArgumentMatchers.contains("原扣款账本未证明支付成功"),
              any(),
              any());
      verify(userClient, never()).refund(any(), any(), any());
    }
  }

  /** 第二阶段写失败只回滚该短事务，停止其后的远端写与终态。 */
  @Test
  void intermediateIntentFailureRollsBackStageAndStopsWritingRpc() {
    for (String stage : java.util.List.of("DEBITED", "REFUND_PENDING")) {
      for (String failureMode : java.util.List.of("zero", "exception")) {
        setUp();
        configureOrderIdentityPath("DEBITED".equals(stage) ? "paid" : "applied-cancel", "order-1");
        if ("DEBITED".equals(stage))
          when(orderClient.paymentState("order-1", 7L))
              .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT")));
        if ("exception".equals(failureMode))
          when(intentMapper.updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any()))
              .thenThrow(new IllegalStateException("controlled-stage-intent-write-failure"));
        else
          when(intentMapper.updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any()))
              .thenReturn(0);
        assertEquals("ORDER_SYNC_UNKNOWN", consistencyService.process("pay-1"));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(userClient, never()).debit(any(), any(), any());
        verify(userClient, never()).refund(any(), any(), any());
        verify(orderClient, never()).paid(any(), any());
        verify(intentMapper, never()).finishOwnedLegacy(any(), any(), any(), any());
        verify(payRecordMapper, never()).markOwnedLegacySuccess(any(), any(), any(), any());
        verify(payRecordMapper, never()).markOwnedLegacyRefunded(any(), any(), any());
      }
    }
  }

  /** 私有阶段提交也拒绝继承Seata上下文，不解绑caller，不启动事务或SQL。 */
  @Test
  void stageTransactionRejectsInheritedGlobalContext() {
    for (String stage : java.util.List.of("DEBITED", "REFUND_PENDING")) {
      setUp();
      org.junit.jupiter.api.Assertions.assertNull(io.seata.core.context.RootContext.getXID());
      io.seata.core.context.RootContext.bind("owned-stage-context");
      try {
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        consistencyService,
                        "commitOwnedLegacyStage",
                        intent(),
                        "owned-token",
                        stage));
        assertEquals("Legacy阶段不得继承全局事务", failure.getMessage());
        assertEquals("owned-stage-context", io.seata.core.context.RootContext.getXID());
        org.mockito.Mockito.verifyNoInteractions(
            transactionManager, intentMapper, payRecordMapper, userClient, orderClient);
      } finally {
        io.seata.core.context.RootContext.unbind();
      }
    }
  }

  /** 原阶段载荷或资格缺失时两写均不尝试，短事务回滚且无RPC。 */
  @Test
  void missingStageSourceRollsBackWithoutStageWrites() {
    for (String stage : java.util.List.of("DEBITED", "REFUND_PENDING")) {
      setUp();
      when(payRecordMapper.lockOwnedLegacyStageSource(any(), anyString(), any())).thenReturn(null);
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class,
              () ->
                  org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                      consistencyService,
                      "commitOwnedLegacyStage",
                      intent(),
                      "owned-token",
                      stage));
      assertEquals("支付阶段原载荷或领取资格已变化", failure.getMessage());
      verify(payRecordMapper, never()).markOwnedLegacyOrderUnknown(any(), any(), any());
      verify(payRecordMapper, never()).markOwnedLegacyRefundPending(any(), any(), any());
      verify(intentMapper, never()).updateOwnedLegacyStatus(any(), any(), any(), any());
      verify(transactionManager).rollback(any());
      verify(transactionManager, never()).commit(any());
      org.mockito.Mockito.verifyNoInteractions(userClient, orderClient);
    }
  }

  /** 两个阶段都先按原快照和token核source，再按原顺序两写并独立提交。 */
  @Test
  void stageSourceLockPrecedesBothWritesAndCommit() {
    for (String stage : java.util.List.of("DEBITED", "REFUND_PENDING")) {
      setUp();
      var expected = intent();
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          consistencyService, "commitOwnedLegacyStage", expected, "owned-token", stage);
      var ordered = org.mockito.Mockito.inOrder(payRecordMapper, intentMapper, transactionManager);
      ordered
          .verify(payRecordMapper)
          .lockOwnedLegacyStageSource(eq(expected), eq("owned-token"), any());
      if ("DEBITED".equals(stage))
        ordered
            .verify(payRecordMapper)
            .markOwnedLegacyOrderUnknown(eq("pay-1"), eq("owned-token"), any());
      else
        ordered
            .verify(payRecordMapper)
            .markOwnedLegacyRefundPending(eq("pay-1"), eq("owned-token"), any());
      ordered
          .verify(intentMapper)
          .updateOwnedLegacyStatus(eq("pay-1"), eq("owned-token"), eq(stage), any());
      ordered.verify(transactionManager).commit(any());
      org.mockito.Mockito.verifyNoInteractions(userClient, orderClient);
    }
  }

  private static PayDebitIntentPO intent() {
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.payNo = "pay-1";
    intent.orderNo = "order-1";
    intent.userId = 7L;
    intent.amount = new BigDecimal("10.00");
    intent.status = "INIT";
    intent.attempts = 1;
    return intent;
  }

  private static UserClient.BalanceOperationView appliedDebit() {
    return new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", null);
  }

  private static OrderClient.OrderView order(String status) {
    return new OrderClient.OrderView("order-1", status, new BigDecimal("10.00"), 7L);
  }

  private static RetryableException timeout() {
    Request request =
        Request.create(
            Request.HttpMethod.POST,
            "http://cloud-mall-order/api/orders/order-1/paid",
            Map.of(),
            null,
            StandardCharsets.UTF_8);
    return new RetryableException(
        -1, "order response lost", Request.HttpMethod.POST, (java.util.Date) null, request);
  }
}
