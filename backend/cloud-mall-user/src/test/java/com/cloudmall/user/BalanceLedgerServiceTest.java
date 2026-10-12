package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.UserServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** 余额账本跨缓存丢失场景的单元测试。 */
class BalanceLedgerServiceTest {
  private UserServiceImpl userService;
  private UserMapper userMapper;
  private BalanceLedgerMapper balanceLedgerMapper;
  private BalanceDebitAttemptMapper balanceDebitAttemptMapper;
  private ValueOperations<String, String> valueOperations;

  /** 当前退款用例可改变的原扣款证明，正常字段完整且不连接真实库。 */
  private BalanceLedgerPO originalRefundDebit;

  @BeforeEach
  void setUp() {
    userService = new UserServiceImpl();
    userMapper = mock(UserMapper.class);
    balanceLedgerMapper = mock(BalanceLedgerMapper.class);
    balanceDebitAttemptMapper = mock(BalanceDebitAttemptMapper.class);
    when(balanceDebitAttemptMapper.insert(any())).thenReturn(1);
    when(balanceLedgerMapper.insert(any())).thenReturn(1);
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    ReflectionTestUtils.setField(userService, "userMapper", userMapper);
    ReflectionTestUtils.setField(userService, "balanceLedgerMapper", balanceLedgerMapper);
    ReflectionTestUtils.setField(
        userService, "balanceDebitAttemptMapper", balanceDebitAttemptMapper);
    ReflectionTestUtils.setField(userService, "redis", redisTemplate);
  }

  @Test
  void duplicateDebitAfterRedisKeyLossUsesLedgerAndDoesNotDebitAgain() {
    UserPO user = activeUser(new BigDecimal("100.00"));
    when(userMapper.selectActiveForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("pay-1"))
        .thenReturn(null)
        .thenReturn(appliedDebit("pay-1", new BigDecimal("90.00")));
    when(userMapper.debitBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class)))
        .thenReturn(1);

    BalanceDebitRequestDTO request = debitRequest();
    ApiResponse<?> first = userService.debitInternal(7L, 7L, request);
    ApiResponse<?> replay = userService.debitInternal(7L, 7L, request);

    assertEquals("90.00", ((com.cloudmall.user.domain.vo.BalanceDebitVO) first.data).balance);
    assertEquals("90.00", ((com.cloudmall.user.domain.vo.BalanceDebitVO) replay.data).balance);
    verify(userMapper, times(1))
        .debitBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class));
  }

  @Test
  void duplicateRefundReturnsOriginalLedgerResultAndCreditsOnce() {
    UserPO user = activeUser(new BigDecimal("90.00"));
    BalanceLedgerPO original = appliedDebit("pay-1", new BigDecimal("90.00"));
    BalanceLedgerPO refund = appliedRefund("refund-1", new BigDecimal("100.00"));
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenReturn(null).thenReturn(refund);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenReturn(original);
    when(balanceLedgerMapper.sumAppliedRefunds("pay-1")).thenReturn(new BigDecimal("0.00"));
    when(userMapper.creditBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class)))
        .thenReturn(1);

    BalanceRefundRequestDTO request = refundRequest();
    ApiResponse<?> first = userService.refundInternal(7L, 7L, request);
    ApiResponse<?> replay = userService.refundInternal(7L, 7L, request);

    assertEquals("100.00", ((com.cloudmall.user.domain.vo.BalanceRefundVO) first.data).balance);
    assertEquals("100.00", ((com.cloudmall.user.domain.vo.BalanceRefundVO) replay.data).balance);
    verify(userMapper, times(1))
        .creditBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class));
  }

  /** 禁用用户的已确认 TCC 扣款仍可按完整归属退款一次。 */
  @Test
  void disabledConfirmedTccDebitCanRefundOnce() {
    UserPO user = activeUser(new BigDecimal("90.00"));
    user.status = 0;
    BalanceLedgerPO original = appliedDebit("pay-tcc", new BigDecimal("90.00"));
    original.payNo = "pay-tcc";
    original.orderNo = "order-tcc";
    BalanceLedgerPO refund = appliedRefund("refund:pay-tcc", new BigDecimal("100.00"));
    refund.originalOperationKey = "pay-tcc";
    refund.payNo = "pay-tcc";
    refund.orderNo = "order-tcc";
    BalanceDebitAttemptPO attempt = confirmedTccAttempt();
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund:pay-tcc"))
        .thenReturn(null)
        .thenReturn(refund);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-tcc")).thenReturn(original);
    when(balanceLedgerMapper.sumAppliedRefunds("pay-tcc")).thenReturn(new BigDecimal("0.00"));
    when(balanceDebitAttemptMapper.selectByPayNo("pay-tcc")).thenReturn(attempt);
    when(userMapper.creditBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class)))
        .thenReturn(1);

    BalanceRefundRequestDTO request = new BalanceRefundRequestDTO();
    request.refundOperationKey = "refund:pay-tcc";
    request.originalOperationKey = "pay-tcc";
    request.payNo = "pay-tcc";
    request.orderNo = "order-tcc";
    request.amount = new BigDecimal("10.00");

    userService.refundInternal(7L, 7L, request);
    userService.refundInternal(7L, 7L, request);

    verify(userMapper, times(1))
        .creditBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class));
  }

  /** 禁用 Legacy 退款和未确认 TCC 退款都保持拒绝。 */
  @Test
  void disabledLegacyOrTryingTccCannotRefund() {
    UserPO user = activeUser(new BigDecimal("90.00"));
    user.status = 0;
    BalanceLedgerPO original = appliedDebit("pay-disabled", new BigDecimal("90.00"));
    original.payNo = "pay-disabled";
    original.orderNo = "order-disabled";
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund-disabled")).thenReturn(null);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-disabled")).thenReturn(original);
    when(balanceDebitAttemptMapper.selectByPayNo("pay-disabled")).thenReturn(null);
    BalanceRefundRequestDTO request = new BalanceRefundRequestDTO();
    request.refundOperationKey = "refund-disabled";
    request.originalOperationKey = "pay-disabled";
    request.payNo = "pay-disabled";
    request.orderNo = "order-disabled";
    request.amount = new BigDecimal("10.00");
    assertThrows(
        com.cloudmall.common.error.BizException.class,
        () -> userService.refundInternal(7L, 7L, request));

    BalanceDebitAttemptPO trying = confirmedTccAttempt();
    trying.payNo = "pay-disabled";
    trying.orderNo = "order-disabled";
    trying.state = "TRYING";
    trying.ledgerKey = "pay-disabled";
    when(balanceDebitAttemptMapper.selectByPayNo("pay-disabled")).thenReturn(trying);
    assertThrows(
        com.cloudmall.common.error.BizException.class,
        () -> userService.refundInternal(7L, 7L, request));
  }

  /** CI候选及非法原来源不能成为新增退款或disabled重放证明。 */
  @Test
  void refundRejectsUnprovenOriginalIdentityTypeAndOrigin() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh", "disabled-replay")) {
      for (String variant :
          java.util.List.of(
              "missing",
              "key-case",
              "key-other",
              "type-case",
              "type-refund",
              "origin-empty",
              "origin-self",
              "user",
              "pay",
              "order",
              "state")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              switch (variant) {
                case "missing" ->
                    when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenReturn(null);
                case "key-case" -> originalRefundDebit.operationKey = "PAY-1";
                case "key-other" -> originalRefundDebit.operationKey = "other-key";
                case "type-case" -> originalRefundDebit.operationType = "debit";
                case "type-refund" -> originalRefundDebit.operationType = "REFUND";
                case "origin-empty" -> originalRefundDebit.originalOperationKey = "";
                case "origin-self" -> originalRefundDebit.originalOperationKey = "pay-1";
                case "user" -> originalRefundDebit.userId = 8L;
                case "pay" -> originalRefundDebit.payNo = "PAY-1";
                case "order" -> originalRefundDebit.orderNo = "other-order";
                case "state" -> originalRefundDebit.state = "REJECTED";
                default -> throw new AssertionError(variant);
              }
              assertOriginalRefundRejected(mode, request);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 坏原金额/余额及差额须在汇总、确认归属比较和credit之前拒绝，不能NPE。 */
  @Test
  void refundRejectsUnprovenOriginalMoneyAndDeltaBeforeCredit() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh", "disabled-replay")) {
      for (String variant :
          java.util.List.of(
              "amount-null",
              "amount-zero",
              "amount-negative",
              "amount-scale",
              "amount-cap",
              "before-null",
              "after-null",
              "before-negative",
              "after-negative",
              "balance-cap",
              "delta")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              switch (variant) {
                case "amount-null" -> originalRefundDebit.amount = null;
                case "amount-zero" -> originalRefundDebit.amount = BigDecimal.ZERO;
                case "amount-negative" -> originalRefundDebit.amount = new BigDecimal("-1.00");
                case "amount-scale" -> originalRefundDebit.amount = new BigDecimal("10.001");
                case "amount-cap" ->
                    originalRefundDebit.amount = new BigDecimal("10000000000000000.00");
                case "before-null" -> originalRefundDebit.balanceBefore = null;
                case "after-null" -> originalRefundDebit.balanceAfter = null;
                case "before-negative" ->
                    originalRefundDebit.balanceBefore = new BigDecimal("-1.00");
                case "after-negative" -> originalRefundDebit.balanceAfter = new BigDecimal("-1.00");
                case "balance-cap" ->
                    originalRefundDebit.balanceBefore = new BigDecimal("10000000000000000.00");
                case "delta" -> originalRefundDebit.balanceAfter = new BigDecimal("95.00");
                default -> throw new AssertionError(variant);
              }
              assertOriginalRefundRejected(mode, request);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 纯字段门保零余额/无损尾零，不改变active已有退款重放不读source的原边界。 */
  @Test
  void validOriginalRefundProofKeepsZeroBalanceTailZerosAndReplayBoundaries() {
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh", "disabled-replay")) {
      var request = installRefundProofMode(mode);
      originalRefundDebit.amount = new BigDecimal("10.000");
      originalRefundDebit.balanceBefore = new BigDecimal("10.000");
      originalRefundDebit.balanceAfter = new BigDecimal("0.000");
      userService.refundInternal(7L, 7L, request);
      verify(userMapper, times("disabled-replay".equals(mode) ? 0 : 1))
          .creditBalance(any(), any(), any());
    }
    var request = installRefundProofMode("active-fresh");
    when(balanceLedgerMapper.selectByOperationKey("refund-1"))
        .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
    when(balanceLedgerMapper.selectDebitForUpdate("pay-1"))
        .thenThrow(new AssertionError("active replay不得扩大原source读取范围"));
    userService.refundInternal(7L, 7L, request);
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
  }

  /** 原查询或汇总故障原样传播，不把数据库错误转换成无扣款或写退款。 */
  @Test
  void refundOriginalLookupAndAggregateFailuresKeepExistingExceptionChannel() {
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh", "disabled-replay")) {
      var request = installRefundProofMode(mode);
      var unavailable =
          new org.springframework.dao.DataRetrievalFailureException("owned-original-read-failure");
      when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenThrow(unavailable);
      org.junit.jupiter.api.Assertions.assertSame(
          unavailable,
          assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      var request = installRefundProofMode(mode);
      var unavailable =
          new org.springframework.dao.DataRetrievalFailureException("owned-sum-read-failure");
      when(balanceLedgerMapper.sumAppliedRefunds("pay-1")).thenThrow(unavailable);
      org.junit.jupiter.api.Assertions.assertSame(
          unavailable,
          assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** 每组合重置替身，disabled仅模拟已确认TCC，避免把Legacy禁用拒绝当新源门复现。 */
  private BalanceRefundRequestDTO installRefundProofMode(String mode) {
    setUp();
    var request = refundRequest();
    var user = activeUser(new BigDecimal("90.00"));
    boolean disabled = mode.startsWith("disabled");
    if (disabled) user.status = 0;
    originalRefundDebit = appliedDebit("pay-1", new BigDecimal("90.00"));
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenReturn(originalRefundDebit);
    when(balanceLedgerMapper.selectByOperationKey("refund-1"))
        .thenReturn(
            "disabled-replay".equals(mode)
                ? appliedRefund("refund-1", new BigDecimal("100.00"))
                : null);
    when(balanceLedgerMapper.sumAppliedRefunds("pay-1")).thenReturn(BigDecimal.ZERO);
    when(userMapper.creditBalance(any(), any(), any())).thenReturn(1);
    var attempt = confirmedTccAttempt();
    attempt.payNo = request.payNo;
    attempt.orderNo = request.orderNo;
    attempt.ledgerKey = request.originalOperationKey;
    attempt.operationKey = "tcc-debit:" + request.payNo;
    when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(attempt);
    return request;
  }

  /** 固定错误码保持，源缺证不得查sum/attempt、写钱包或创建新退款。 */
  private void assertOriginalRefundRejected(String mode, BalanceRefundRequestDTO request) {
    var failure =
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> userService.refundInternal(7L, 7L, request));
    assertEquals(mode.startsWith("disabled") ? 404 : 409, failure.getHttpStatus());
    assertEquals(
        mode.startsWith("disabled") ? "COMMON_NOT_FOUND" : "BALANCE_REFUND_INVALID",
        failure.getCode());
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).sumAppliedRefunds(any());
    verify(balanceDebitAttemptMapper, org.mockito.Mockito.never()).selectByPayNo(any());
  }

  private BalanceDebitAttemptPO confirmedTccAttempt() {
    BalanceDebitAttemptPO attempt = new BalanceDebitAttemptPO();
    attempt.mode = "TCC";
    attempt.state = "CONFIRMED";
    attempt.userId = 7L;
    attempt.payNo = "pay-tcc";
    attempt.orderNo = "order-tcc";
    attempt.amount = new BigDecimal("10.00");
    attempt.xid = "xid-tcc";
    attempt.branchId = 12L;
    attempt.operationKey = "tcc-debit:pay-tcc";
    attempt.ledgerKey = "pay-tcc";
    return attempt;
  }

  private BalanceDebitRequestDTO debitRequest() {
    BalanceDebitRequestDTO request = new BalanceDebitRequestDTO();
    request.operationKey = "pay-1";
    request.payNo = "pay-1";
    request.orderNo = "order-1";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private BalanceRefundRequestDTO refundRequest() {
    BalanceRefundRequestDTO request = new BalanceRefundRequestDTO();
    request.refundOperationKey = "refund-1";
    request.originalOperationKey = "pay-1";
    request.payNo = "pay-1";
    request.orderNo = "order-1";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private UserPO activeUser(BigDecimal balance) {
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = balance;
    return user;
  }

  private BalanceLedgerPO appliedDebit(String operationKey, BigDecimal balanceAfter) {
    BalanceLedgerPO ledger = new BalanceLedgerPO();
    ledger.userId = 7L;
    ledger.operationKey = operationKey;
    ledger.operationType = "DEBIT";
    ledger.payNo = "pay-1";
    ledger.orderNo = "order-1";
    ledger.amount = new BigDecimal("10.00");
    ledger.state = "APPLIED";
    ledger.balanceAfter = balanceAfter;
    ledger.balanceBefore = balanceAfter.add(ledger.amount);
    return ledger;
  }

  private BalanceLedgerPO appliedRefund(String operationKey, BigDecimal balanceAfter) {
    BalanceLedgerPO ledger = new BalanceLedgerPO();
    ledger.userId = 7L;
    ledger.operationKey = operationKey;
    ledger.operationType = "REFUND";
    ledger.originalOperationKey = "pay-1";
    ledger.payNo = "pay-1";
    ledger.orderNo = "order-1";
    ledger.amount = new BigDecimal("10.00");
    ledger.state = "APPLIED";
    ledger.balanceAfter = balanceAfter;
    return ledger;
  }
}
