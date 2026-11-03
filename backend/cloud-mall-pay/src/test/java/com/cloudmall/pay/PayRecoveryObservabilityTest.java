package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import com.cloudmall.pay.util.PayRecoveryLog;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 真Service加日志appender的离线观察测试，不启动业务或连接资源。 */
class PayRecoveryObservabilityTest {
  /** 合成敏感字段，用于证明消息、参数和Throwable都没有原文泄露。 */
  private static final String CANARY = "PRIVATE_RECOVERY_CANARY";

  /** 原业务仍使用的合成支付标识，日志只应出现它的版本化指纹。 */
  private static final String PAY_NO = "pay-" + CANARY;

  /** 原业务仍使用的合成订单标识，日志不得原文输出。 */
  private static final String ORDER_NO = "order-" + CANARY;

  /** 本测试实际捕获的原Service logger。 */
  private Logger logger;

  /** 测试前logger级别，结束后恢复。 */
  private Level originalLevel;

  /** 测试前向父logger传播开关，结束后恢复。 */
  private boolean originalAdditivity;

  /** 正常ListAppender，只存本测试事件，不向root输出canary。 */
  private ListAppender<ILoggingEvent> events;

  /** 可真正向Logger调用方抛RuntimeException的拒写appender。 */
  private RejectingAppender rejecting;

  /** 意图查询和已有写入调用的替身。 */
  private PayDebitIntentMapper intents;

  /** 支付载荷查询和已有写入调用的替身。 */
  private PayRecordMapper payments;

  /** 用户Feign替身，没有真实RPC。 */
  private UserClient users;

  /** 订单Feign替身，没有真实RPC。 */
  private OrderClient orders;

  /** 新日志不得调用的意图创建服务。 */
  private PayDebitIntentService intentService;

  /** 只支持旧TransactionTemplate测试边界，不冒数据库事务实证。 */
  private PlatformTransactionManager transactions;

  /** 受测真实生产服务。 */
  private PayConsistencyService recovery;

  /** 捕获实际logger并保留原配置；不替换业务方法来生成事件。 */
  @BeforeEach
  void prepareLogger() {
    logger = (Logger) LoggerFactory.getLogger(PayConsistencyService.class);
    originalLevel = logger.getLevel();
    originalAdditivity = logger.isAdditive();
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    events = new ListAppender<>();
    events.setContext(logger.getLoggerContext());
    events.start();
    logger.addAppender(events);
    prepareService();
  }

  /** 精确移除本测试的appender、恢复原level/additivity并清理测试制造的中断。 */
  @AfterEach
  void restoreLogger() {
    if (rejecting != null) {
      logger.detachAppender(rejecting);
      rejecting.stop();
    }
    logger.detachAppender(events);
    events.stop();
    logger.setLevel(originalLevel);
    logger.setAdditive(originalAdditivity);
    Thread.interrupted();
  }

  /** 非空INFO、空尾回环DEBUG只记录原CAS，不把候选终点当本轮已应用进度。 */
  @Test
  void scanSummaryReflectsWrapEmptyLevelAndUnappliedCandidateCursor() {
    PayConsistencyService scanned = spy(recovery);
    PayDebitIntentPO outer = intent("INIT");
    outer.id = 50L;
    PayDebitIntentPO inner = intent("INIT");
    inner.id = 100L;
    inner.payNo = "inner-pay";
    when(intents.findDueAfter(any(), isNull(), eq(50)))
        .thenReturn(List.of(outer), List.of(inner), List.of());
    when(intents.findDueAfter(any(), eq(100L), eq(50))).thenReturn(List.of());
    doAnswer(
            invocation -> {
              scanned.recoverDue();
              return "INIT";
            })
        .when(scanned)
        .process(PAY_NO);
    doReturn("INIT").when(scanned).process("inner-pay");

    scanned.recoverDue();
    scanned.recoverDue();

    List<ILoggingEvent> summaries = matching("SCAN_FINISHED");
    assertEquals(3, summaries.size());
    assertTrue(
        summaries
            .get(0)
            .getFormattedMessage()
            .contains("candidateEndCursor=100 casApplied=true appliedEndCursor=100"));
    assertTrue(
        summaries
            .get(1)
            .getFormattedMessage()
            .contains("candidateEndCursor=50 casApplied=false appliedEndCursor=null"));
    assertEquals(Level.INFO, summaries.get(1).getLevel());
    assertEquals(Level.DEBUG, summaries.get(2).getLevel());
    assertTrue(summaries.get(2).getFormattedMessage().contains("startCursor=100"));
    assertTrue(
        summaries
            .get(2)
            .getFormattedMessage()
            .contains("wrapped=true candidateCount=0 queryCount=2"));
    verify(intents, times(3)).findDueAfter(any(), isNull(), eq(50));
    verify(intents).findDueAfter(any(), eq(100L), eq(50));
    verifyNoInteractions(users, orders, payments, intentService, transactions);
    assertPrivate();
  }

  /** claim非1快照只作已见事实；未知字符串不回抄，也不猜租约或到期原因。 */
  @Test
  void claimSnapshotPreservesReturnAndCallsWithoutLeakingUnknownState() {
    PayDebitIntentPO pending = intent("INIT");
    PayDebitIntentPO current = intent(CANARY);
    current.attempts = 2;
    when(intents.findByPayNo(PAY_NO)).thenReturn(pending, current);
    when(payments.selectByPayNo(PAY_NO)).thenReturn(List.of(payment()));
    when(intents.claim(eq(PAY_NO), anyString(), any(), any())).thenReturn(0);

    assertEquals(CANARY, recovery.process(PAY_NO));

    String observed = matching("CLAIM_NOT_ACQUIRED").get(0).getFormattedMessage();
    assertTrue(observed.contains("claimRows=0 reason=UNDETERMINED"));
    assertTrue(
        observed.contains("snapshotScope=OBSERVED_SNAPSHOT pendingStatus=INIT pendingAttempts=1"));
    assertTrue(observed.contains("currentStatus=UNKNOWN currentAttempts=2"));
    assertTrue(observed.contains("NextRetryAt=2026-10-26T12:00:00.123"));
    assertTrue(observed.matches(".*payFingerprint=v1:[0-9a-f]{32} .*"));
    verify(intents, times(2)).findByPayNo(PAY_NO);
    verify(intents).claim(eq(PAY_NO), anyString(), any(), any());
    verify(payments, times(2)).selectByPayNo(PAY_NO);
    verifyNoMoreInteractions(intents, payments);
    verifyNoInteractions(users, orders, intentService, transactions);
    assertPrivate();
  }

  /** PAID仍保库存门；PENDING_PAYMENT/NONE保留合法无动作事实，日志不增加远程写。 */
  @Test
  void orderEvidenceAndRetryWarnKeepOriginalFinancialGates() {
    for (String stockStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED")) {
      prepareService();
      events.list.clear();
      PayDebitIntentPO intent = intent("INIT");
      PayRecordPO payment = payment();
      when(intents.findByPayNo(PAY_NO)).thenReturn(intent);
      when(payments.selectByPayNo(PAY_NO)).thenReturn(List.of(payment));
      when(intents.claim(eq(PAY_NO), anyString(), any(), any())).thenReturn(1);
      when(transactions.getTransaction(any())).thenAnswer(unused -> new SimpleTransactionStatus());
      when(users.operation(7L, PAY_NO, 7L))
          .thenReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(PAY_NO, "DEBIT", "APPLIED", "10.00", null)));
      when(orders.paymentState(ORDER_NO, 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(ORDER_NO, "PAID", BigDecimal.TEN, 7L, stockStatus)));
      when(payments.lockOwnedLegacyStageSource(any(), anyString(), any())).thenReturn(payment);
      when(payments.lockOwnedLegacyRetrySource(any(), anyString(), any())).thenReturn(payment);
      when(payments.lockOwnedLegacyTerminalSource(any(), anyString(), any())).thenReturn(payment);
      when(payments.markOwnedLegacyOrderUnknown(any(), anyString(), any())).thenReturn(1);
      when(payments.markOwnedLegacySuccess(any(), anyString(), any(), any())).thenReturn(1);
      when(intents.updateOwnedLegacyStatus(any(), anyString(), any(), any())).thenReturn(1);
      when(intents.recordRetryOwnedLegacy(any(), anyString(), any(), any(), any(), any()))
          .thenReturn(1);
      when(intents.finishOwnedLegacy(any(), anyString(), any(), any())).thenReturn(1);

      assertEquals(
          "SUCCEEDED".equals(stockStatus) ? "SUCCESS" : "ORDER_SYNC_UNKNOWN",
          recovery.process(PAY_NO));

      int expectedReads = "SUCCEEDED".equals(stockStatus) ? 2 : 1;
      assertEquals(expectedReads, matching("ORDER_EVIDENCE_OBSERVED").size());
      assertTrue(
          matching("ORDER_EVIDENCE_OBSERVED").stream()
              .allMatch(
                  event ->
                      event
                          .getFormattedMessage()
                          .contains(
                              "orderStatus=PAID stockActionStatus="
                                  + stockStatus
                                  + " validation=VALIDATED durationNanos=")));
      verify(orders, times(expectedReads)).paymentState(ORDER_NO, 7L);
      verify(users, times(expectedReads)).operation(7L, PAY_NO, 7L);
      if (!"SUCCEEDED".equals(stockStatus)) {
        assertEquals(Level.WARN, matching("RETRY_DEFERRED").get(0).getLevel());
        verify(intents)
            .recordRetryOwnedLegacy(
                eq(PAY_NO),
                anyString(),
                eq("ORDER_SYNC_UNKNOWN"),
                eq("java.lang.IllegalStateException: 订单库存动作尚未完成: " + stockStatus),
                any(),
                any());
        verify(intents, never()).finishOwnedLegacy(any(), anyString(), any(), any());
      }
      verify(users, never()).debit(any(), any(), any());
      verify(users, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      assertPrivate();
    }
    prepareService();
    events.list.clear();
    OrderClient.OrderView pendingWithoutAction =
        new OrderClient.OrderView(ORDER_NO, "PENDING_PAYMENT", BigDecimal.TEN, 7L, "NONE");
    when(orders.paymentState(ORDER_NO, 7L)).thenReturn(ApiResponse.ok(pendingWithoutAction));
    assertSame(
        pendingWithoutAction,
        ReflectionTestUtils.invokeMethod(recovery, "readOrderState", intent("INIT")));
    assertEquals(1, matching("ORDER_EVIDENCE_OBSERVED").size());
    assertTrue(
        matching("ORDER_EVIDENCE_OBSERVED")
            .get(0)
            .getFormattedMessage()
            .contains("orderStatus=PENDING_PAYMENT stockActionStatus=NONE validation=VALIDATED"));
    verify(orders).paymentState(ORDER_NO, 7L);
    verifyNoMoreInteractions(orders);
    verifyNoInteractions(users, payments, intents, transactions, intentService);
    assertPrivate();
  }

  /** 原读取异常不换对象，未校验响应/异常消息均不会被事件当作可信原文。 */
  @Test
  void orderEvidenceFailureKeepsOriginalExceptionAndClosedStage() {
    RuntimeException original = new IllegalStateException(CANARY);
    when(orders.paymentState(ORDER_NO, 7L)).thenThrow(original);
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class,
            () -> ReflectionTestUtils.invokeMethod(recovery, "readOrderState", intent("INIT"))));
    String failed = matching("ORDER_EVIDENCE_FAILED").get(0).getFormattedMessage();
    assertTrue(failed.contains("orderStatus=NOT_OBSERVED stockActionStatus=NOT_OBSERVED"));
    assertTrue(failed.contains("validation=NOT_VALIDATED stage=RPC durationNanos="));
    verify(orders).paymentState(ORDER_NO, 7L);
    verifyNoMoreInteractions(orders);
    verifyNoInteractions(users, payments, intents, transactions, intentService);
    assertPrivate();
  }

  /** 拒写logger不能覆盖真实query异常或中断，也不能推进原cursor。 */
  @Test
  void rejectingAppenderPreservesQueryExceptionInterruptAndProgress() {
    PayConsistencyService scanned = spy(recovery);
    PayDebitIntentPO first = intent("INIT");
    first.id = 100L;
    when(intents.findDueAfter(any(), isNull(), eq(50))).thenReturn(List.of(first), List.of());
    RuntimeException original = new DataAccessResourceFailureException(CANARY);
    when(intents.findDueAfter(any(), eq(100L), eq(50))).thenThrow(original).thenReturn(List.of());
    doReturn("INIT").when(scanned).process(PAY_NO);
    scanned.recoverDue();
    rejectWrites();
    Thread.currentThread().interrupt();
    assertSame(
        original, assertThrows(DataAccessResourceFailureException.class, scanned::recoverDue));
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
    logger.detachAppender(rejecting);
    scanned.recoverDue();
    verify(intents, times(2)).findDueAfter(any(), eq(100L), eq(50));
    verifyNoInteractions(users, orders, payments, intentService, transactions);
    assertTrue(
        matching("SCAN_QUERY_FAILED")
            .get(0)
            .getFormattedMessage()
            .contains(
                "startCursor=100 wrapped=false queryCount=1 failureKind=QUERY_RUNTIME_FAILURE"));
    assertPrivate();
  }

  /** 拒写INFO仍返回原claim状态；候选WARN也只保安全failureKind而不附异常。 */
  @Test
  void rejectingAppenderCannotChangeClaimReturnOrCandidateFailureHandling() {
    PayDebitIntentPO intent = intent("ORDER_SYNC_UNKNOWN");
    when(intents.findByPayNo(PAY_NO)).thenReturn(intent);
    when(payments.selectByPayNo(PAY_NO)).thenReturn(List.of(payment()));
    when(intents.claim(eq(PAY_NO), anyString(), any(), any())).thenReturn(0);
    rejectWrites();
    assertEquals("ORDER_SYNC_UNKNOWN", recovery.process(PAY_NO));
    logger.detachAppender(rejecting);
    PayConsistencyService scanned = spy(recovery);
    when(intents.findDueAfter(any(), isNull(), eq(50))).thenReturn(List.of(intent));
    doAnswer(
            unused -> {
              throw new IllegalStateException(CANARY);
            })
        .when(scanned)
        .process(PAY_NO);
    scanned.recoverDue();
    assertEquals(Level.WARN, matching("SCAN_CANDIDATE_FAILED").get(0).getLevel());
    verifyNoInteractions(users, orders, intentService, transactions);
    assertPrivate();
  }

  /** helper隔离级别/格式RuntimeException但不吞JVM Error。 */
  @Test
  void helperIsolatesWholeRuntimePipelineButDoesNotCatchError() {
    org.slf4j.Logger failedLevel =
        mock(
            org.slf4j.Logger.class,
            invocation -> {
              throw new IllegalStateException(CANARY);
            });
    assertDoesNotThrow(() -> PayRecoveryLog.claimNotAcquired(failedLevel, PAY_NO, 0, null, null));
    List<PayDebitIntentPO> badSize =
        new AbstractList<>() {
          @Override
          public PayDebitIntentPO get(int index) {
            throw new IllegalStateException(CANARY);
          }

          @Override
          public int size() {
            throw new IllegalStateException(CANARY);
          }
        };
    assertDoesNotThrow(
        () ->
            PayRecoveryLog.scanFinished(
                logger, null, null, true, false, 1, badSize, System.nanoTime()));
    org.slf4j.Logger fatal =
        mock(
            org.slf4j.Logger.class,
            invocation -> {
              throw new AssertionError("TEST_FATAL");
            });
    assertThrows(
        AssertionError.class, () -> PayRecoveryLog.claimNotAcquired(fatal, PAY_NO, 0, null, null));
    assertPrivate();
  }

  private void prepareService() {
    intents = mock(PayDebitIntentMapper.class);
    payments = mock(PayRecordMapper.class);
    users = mock(UserClient.class);
    orders = mock(OrderClient.class);
    transactions = mock(PlatformTransactionManager.class);
    intentService = mock(PayDebitIntentService.class);
    recovery =
        new PayConsistencyService(intentService, intents, payments, users, orders, transactions);
  }

  private List<ILoggingEvent> matching(String event) {
    return events.list.stream()
        .filter(entry -> entry.getFormattedMessage().startsWith("event=" + event + " "))
        .toList();
  }

  private void assertPrivate() {
    for (ILoggingEvent event : events.list) {
      assertFalse(event.getFormattedMessage().contains(CANARY));
      assertNull(event.getThrowableProxy());
      if (event.getArgumentArray() != null)
        for (Object argument : event.getArgumentArray())
          assertFalse(String.valueOf(argument).contains(CANARY));
    }
  }

  private void rejectWrites() {
    rejecting = new RejectingAppender();
    rejecting.setContext(logger.getLoggerContext());
    rejecting.start();
    logger.addAppender(rejecting);
  }

  private static PayDebitIntentPO intent(String status) {
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.id = 50L;
    intent.payNo = PAY_NO;
    intent.orderNo = ORDER_NO;
    intent.userId = 7L;
    intent.amount = BigDecimal.TEN;
    intent.debitMode = "LEGACY";
    intent.status = status;
    intent.attempts = 1;
    intent.nextRetryAt = LocalDateTime.of(2026, 10, 26, 12, 0, 0, 123000000);
    return intent;
  }

  private static PayRecordPO payment() {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = PAY_NO;
    payment.orderNo = ORDER_NO;
    payment.userId = 7L;
    payment.amount = BigDecimal.TEN;
    payment.debitMode = "LEGACY";
    payment.status = "PENDING";
    return payment;
  }

  /** override doAppend绕过AppenderBase自己的异常吞并，实际检验Service的helper隔离。 */
  private static final class RejectingAppender extends ListAppender<ILoggingEvent> {
    /** 清中断并拒写，helper必须恢复既有标志且不覆盖原业务异常。 */
    @Override
    public void doAppend(ILoggingEvent event) {
      Thread.interrupted();
      throw new IllegalStateException(CANARY);
    }
  }
}
