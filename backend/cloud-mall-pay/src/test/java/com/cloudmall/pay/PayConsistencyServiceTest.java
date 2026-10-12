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
      var sameTerminal = new com.cloudmall.pay.domain.po.PayRecordPO();
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

  /** 本地终态定义必须REQUIRES_NEW，成功提交与零行回滚仅在fake manager层验证。 */
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
      verify(transactionManager).getTransaction(definition.capture());
      assertEquals(
          org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
          definition.getValue().getPropagationBehavior());
      verify(transactionManager, org.mockito.Mockito.times(fail ? 0 : 1)).commit(any());
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
    when(intentMapper.findDue(any(), eq(50))).thenReturn(java.util.List.of(intent()));
    consistencyService.recoverDue();
    verify(intentMapper).findDue(any(), eq(50));
    verify(intentMapper).claim(eq("pay-1"), anyString(), any(), any());
    verify(intentMapper).finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
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
