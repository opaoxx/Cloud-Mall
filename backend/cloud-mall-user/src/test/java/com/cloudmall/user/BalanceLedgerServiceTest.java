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

  /** 原冻结行读取替身，用于确认退款本地事实，不写实际资金。 */
  private com.cloudmall.user.mapper.BalanceTccDebitMapper balanceTccDebitMapper;

  /** 当前用例可修改的已确认冻结行，复制实际原DEBIT快照。 */
  private com.cloudmall.user.domain.po.BalanceTccDebitPO originalRefundFreeze;

  private ValueOperations<String, String> valueOperations;

  /** 当前余额操作查询的主行，修改字段用于验证只读拒绝。 */
  private BalanceLedgerPO operationLedger;

  /** 当前查询的原DEBIT来源，REFUND查询不能忽略这一事实。 */
  private BalanceLedgerPO operationSource;

  /** Legacy当前用例的已存账本，用于改变历史字段并验证重放拒绝。 */
  private BalanceLedgerPO legacyDebitLedger;

  /** Legacy当前用例的不可变尝试归属，金额缺证不得跳入资金写。 */
  private BalanceDebitAttemptPO legacyDebitAttempt;

  /** 当前退款用例可改变的原扣款证明，正常字段完整且不连接真实库。 */
  private BalanceLedgerPO originalRefundDebit;

  /** 当前退款用例可修改的TCC归属，供active确认来源检查。 */
  private BalanceDebitAttemptPO originalRefundAttempt;

  @BeforeEach
  void setUp() {
    userService = new UserServiceImpl();
    userMapper = mock(UserMapper.class);
    balanceLedgerMapper = mock(BalanceLedgerMapper.class);
    balanceDebitAttemptMapper = mock(BalanceDebitAttemptMapper.class);
    balanceTccDebitMapper = mock(com.cloudmall.user.mapper.BalanceTccDebitMapper.class);
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
    ReflectionTestUtils.setField(userService, "balanceTccDebitMapper", balanceTccDebitMapper);
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
    when(balanceLedgerMapper.findPaymentLedgerCandidates(request.payNo))
        .thenReturn(java.util.List.of());
    ApiResponse<?> first = userService.debitInternal(7L, 7L, request);
    ApiResponse<?> replay = userService.debitInternal(7L, 7L, request);

    assertEquals("90.00", ((com.cloudmall.user.domain.vo.BalanceDebitVO) first.data).balance);
    assertEquals("90.00", ((com.cloudmall.user.domain.vo.BalanceDebitVO) replay.data).balance);
    verify(userMapper, times(1))
        .debitBalance(eq(7L), eq(new BigDecimal("10.00")), any(LocalDateTime.class));
    verify(balanceLedgerMapper, times(1)).findPaymentLedgerCandidates(request.payNo);
  }

  @Test
  void duplicateRefundReturnsOriginalLedgerResultAndCreditsOnce() {
    UserPO user = activeUser(new BigDecimal("90.00"));
    BalanceLedgerPO original = appliedDebit("pay-1", new BigDecimal("90.00"));
    BalanceLedgerPO refund = appliedRefund("refund-1", new BigDecimal("100.00"));
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenReturn(null).thenReturn(refund);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenReturn(original);
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo))
        .thenReturn(java.util.List.of(original));
    when(balanceLedgerMapper.findDebitCandidatesByPayNo(original.payNo))
        .thenReturn(java.util.List.of(original));
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(java.util.List.of());
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
    when(balanceTccDebitMapper.selectByPayNo(attempt.payNo))
        .thenReturn(confirmedRefundFreeze(attempt, original));
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund:pay-tcc"))
        .thenReturn(null)
        .thenReturn(refund);
    when(balanceLedgerMapper.selectDebitForUpdate("pay-tcc")).thenReturn(original);
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo))
        .thenReturn(java.util.List.of(original));
    when(balanceLedgerMapper.findDebitCandidatesByPayNo(original.payNo))
        .thenReturn(java.util.List.of(original));
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-tcc")).thenReturn(java.util.List.of());
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
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo))
        .thenReturn(java.util.List.of(original));
    when(balanceLedgerMapper.findDebitCandidatesByPayNo(original.payNo))
        .thenReturn(java.util.List.of(original));
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
      originalRefundFreeze.amount = originalRefundDebit.amount;
      originalRefundFreeze.balanceBefore = originalRefundDebit.balanceBefore;
      originalRefundFreeze.balanceAfterTry = originalRefundDebit.balanceAfter;
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
          new org.springframework.dao.DataRetrievalFailureException("owned-linked-read-failure");
      when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenThrow(unavailable);
      org.junit.jupiter.api.Assertions.assertSame(
          unavailable,
          assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** fresh/replay与兼容入口非法金额须在任何Mapper和缓存之前拒绝。 */
  @Test
  void invalidLegacyMoneyDomainStopsAllEntryPointsBeforeAnyMapper() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String operation : java.util.List.of("debit", "refund", "compat")) {
      for (boolean replay : java.util.List.of(false, true)) {
        for (BigDecimal amount :
            new BigDecimal[] {
              null,
              BigDecimal.ZERO,
              new BigDecimal("-1"),
              new BigDecimal("0.001"),
              new BigDecimal("10.001"),
              new BigDecimal("1.999"),
              new BigDecimal("10000000000000000.00"),
              new BigDecimal("9999999999999999.991")
            }) {
          assertions.add(
              () -> {
                setUp();
                when(userMapper.selectActiveForUpdate(7L))
                    .thenReturn(activeUser(new BigDecimal("100.00")));
                when(userMapper.selectByIdForUpdate(7L))
                    .thenReturn(activeUser(new BigDecimal("90.00")));
                if (replay)
                  when(balanceLedgerMapper.selectByOperationKey(any()))
                      .thenReturn(
                          "refund".equals(operation)
                              ? appliedRefund("refund-1", new BigDecimal("100.00"))
                              : appliedDebit("pay-1", new BigDecimal("90.00")));
                var failure =
                    assertThrows(
                        com.cloudmall.common.error.BizException.class,
                        () -> invokeLegacyMoney(operation, amount, 7L));
                assertEquals(400, failure.getHttpStatus());
                assertEquals("COMMON_INVALID_ARGUMENT", failure.getCode());
                org.mockito.Mockito.verifyNoInteractions(
                    userMapper, balanceLedgerMapper, balanceDebitAttemptMapper, valueOperations);
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 先校验调用者身份，不以非法金额改变403优先级。 */
  @Test
  void callerIdentityStillPrecedesInvalidLegacyMoneyDomain() {
    for (String operation : java.util.List.of("debit", "refund", "compat")) {
      setUp();
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> invokeLegacyMoney(operation, new BigDecimal("0.001"), 8L));
      assertEquals(403, failure.getHttpStatus());
      assertEquals("COMMON_FORBIDDEN", failure.getCode());
      org.mockito.Mockito.verifyNoInteractions(
          userMapper, balanceLedgerMapper, balanceDebitAttemptMapper, valueOperations);
    }
  }

  /** 合法尾零/cent/上限不误拒，既有重放不二次扣款；退款来源金额域保持。 */
  @Test
  void validLegacyMoneyDomainKeepsCentTailZerosAndMaximum() {
    for (String amountText : java.util.List.of("0.01", "0.010", "10.000", "9999999999999999.99")) {
      BigDecimal amount = new BigDecimal(amountText);
      setUp();
      var request = debitRequest();
      request.amount = amount;
      var existing = appliedDebit("pay-1", BigDecimal.ZERO);
      existing.amount = amount;
      existing.balanceBefore = amount;
      when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(amount));
      when(balanceLedgerMapper.selectByOperationKey("pay-1")).thenReturn(existing);
      userService.debitInternal(7L, 7L, request);
      verify(userMapper, org.mockito.Mockito.never()).debitBalance(any(), any(), any());
      setUp();
      var refund = refundRequest();
      refund.amount = amount;
      var original = appliedDebit("pay-1", BigDecimal.ZERO);
      original.amount = amount;
      original.balanceBefore = amount;
      when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(BigDecimal.ZERO));
      when(balanceLedgerMapper.selectDebitForUpdate("pay-1")).thenReturn(original);
      when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(original.payNo))
          .thenReturn(java.util.List.of(original));
      when(balanceLedgerMapper.findDebitCandidatesByPayNo(original.payNo))
          .thenReturn(java.util.List.of(original));
      when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(java.util.List.of());
      when(userMapper.creditBalance(any(), any(), any())).thenReturn(1);
      userService.refundInternal(7L, 7L, refund);
      verify(userMapper).creditBalance(eq(7L), eq(amount), any());
    }
  }

  /** 只替换金额/调用者，三个原公开入口真实执行，保持原JSON/DTO字段。 */
  private void invokeLegacyMoney(String operation, BigDecimal amount, Long callerId) {
    if ("debit".equals(operation)) {
      var request = debitRequest();
      request.amount = amount;
      userService.debitInternal(7L, callerId, request);
    } else if ("refund".equals(operation)) {
      var request = refundRequest();
      request.amount = amount;
      userService.refundInternal(7L, callerId, request);
    } else {
      var request = new com.cloudmall.user.domain.dto.DebitRequestDTO();
      request.paymentKey = "pay-1";
      request.amount = amount;
      userService.debit(7L, callerId, request);
    }
  }

  /** 当前wallet缺失/损坏/派生overflow不能写credit或refund snapshot。 */
  @Test
  void freshRefundRejectsMalformedWalletAndDerivedOverflowBeforeCredit() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      for (BigDecimal balance :
          new BigDecimal[] {
            null,
            new BigDecimal("-1.00"),
            new BigDecimal("90.001"),
            new BigDecimal("10000000000000000.00"),
            new BigDecimal("9999999999999999.99")
          }) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              var user = activeUser(balance);
              if (mode.startsWith("disabled")) user.status = 0;
              when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals(409, failure.getHttpStatus());
              assertEquals("BALANCE_REFUND_FAILED", failure.getCode());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已credit而ledger不唯一写入须抛可回滚Runtime，不返回APPLIED或补写。 */
  @Test
  void freshRefundLedgerWriteMustAffectExactlyOneRow() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      for (int insertedRows : java.util.List.of(0, 2)) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              when(balanceLedgerMapper.insert(any())).thenReturn(insertedRows);
              assertThrows(
                  IllegalStateException.class, () -> userService.refundInternal(7L, 7L, request));
              verify(userMapper).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    var request = installRefundProofMode("active-fresh");
    when(userMapper.creditBalance(any(), any(), any())).thenReturn(0);
    var failure =
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> userService.refundInternal(7L, 7L, request));
    assertEquals("BALANCE_REFUND_FAILED", failure.getCode());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
  }

  /** zero/尾零/精确MAX保正确snapshot，原source/超额仍先于wallet。 */
  @Test
  void refundWalletBoundsKeepHealthyValuesAndEarlierFailurePriority() {
    for (BigDecimal balance :
        java.util.List.of(
            BigDecimal.ZERO, new BigDecimal("90.000"), new BigDecimal("9999999999999989.99"))) {
      var request = installRefundProofMode("active-fresh");
      when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(balance));
      var response = userService.refundInternal(7L, 7L, request);
      var captor = org.mockito.ArgumentCaptor.forClass(BalanceLedgerPO.class);
      verify(balanceLedgerMapper).insert(captor.capture());
      assertEquals(balance, captor.getValue().balanceBefore);
      assertEquals(balance.add(request.amount), captor.getValue().balanceAfter);
      assertEquals(
          balance.add(request.amount).toPlainString(),
          ((com.cloudmall.user.domain.vo.BalanceRefundVO) response.data).balance);
    }
    var request = installRefundProofMode("active-fresh");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(null));
    var priorRefund = linkedRefund("prior-full", "10.00");
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
        .thenReturn(java.util.List.of(priorRefund));
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates("pay-1"))
        .thenReturn(java.util.List.of(originalRefundDebit, priorRefund));
    var excessRequest = request;
    assertEquals(
        "BALANCE_REFUND_EXCEEDED",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, excessRequest))
            .getCode());
    request = installRefundProofMode("active-fresh");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(null));
    originalRefundDebit.originalOperationKey = "bad";
    var sourceRequest = request;
    assertEquals(
        "BALANCE_REFUND_INVALID",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, sourceRequest))
            .getCode());
  }

  /** 既有refund是历史结果，当前钱包非法不扩大原重放范围或二次credit。 */
  @Test
  void existingRefundReplayDoesNotEnterFreshWalletOrWriteGate() {
    for (String mode : java.util.List.of("active-fresh", "disabled-replay")) {
      var request = installRefundProofMode(mode);
      var user = activeUser(null);
      if (mode.startsWith("disabled")) user.status = 0;
      when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
      when(balanceLedgerMapper.selectByOperationKey("refund-1"))
          .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
      userService.refundInternal(7L, 7L, request);
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** 原row的退款key/state/金额快照损坏，不能200重放或NPE，也不二次写资金。 */
  @Test
  void persistedRefundReplayRejectsIncompleteOrConflictingEvidence() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (boolean disabled : java.util.List.of(false, true)) {
      for (String variant :
          java.util.List.of(
              "key-null",
              "key-case",
              "key-other",
              "state-null",
              "state-case",
              "state-other",
              "amount-null",
              "amount-zero",
              "amount-negative",
              "amount-scale",
              "amount-cap",
              "before-null",
              "after-null",
              "before-negative",
              "after-negative",
              "before-scale",
              "after-scale",
              "before-cap",
              "after-cap",
              "delta",
              "user",
              "origin",
              "pay",
              "order",
              "type")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(disabled ? "disabled-replay" : "active-fresh");
              var existing = appliedRefund("refund-1", new BigDecimal("100.00"));
              switch (variant) {
                case "key-null" -> existing.operationKey = null;
                case "key-case" -> existing.operationKey = "REFUND-1";
                case "key-other" -> existing.operationKey = "other-refund";
                case "state-null" -> existing.state = null;
                case "state-case" -> existing.state = "applied";
                case "state-other" -> existing.state = "REJECTED";
                case "amount-null" -> existing.amount = null;
                case "amount-zero" -> existing.amount = BigDecimal.ZERO;
                case "amount-negative" -> existing.amount = new BigDecimal("-10.00");
                case "amount-scale" -> existing.amount = new BigDecimal("10.001");
                case "amount-cap" -> existing.amount = new BigDecimal("10000000000000000.00");
                case "before-null" -> existing.balanceBefore = null;
                case "after-null" -> existing.balanceAfter = null;
                case "before-negative" -> existing.balanceBefore = new BigDecimal("-1.00");
                case "after-negative" -> existing.balanceAfter = new BigDecimal("-1.00");
                case "before-scale" -> existing.balanceBefore = new BigDecimal("90.001");
                case "after-scale" -> existing.balanceAfter = new BigDecimal("100.001");
                case "before-cap" ->
                    existing.balanceBefore = new BigDecimal("10000000000000000.00");
                case "after-cap" -> existing.balanceAfter = new BigDecimal("10000000000000000.00");
                case "delta" -> existing.balanceAfter = new BigDecimal("99.00");
                case "user" -> existing.userId = 8L;
                case "origin" -> existing.originalOperationKey = "other-pay";
                case "pay" -> existing.payNo = "PAY-1";
                case "order" -> existing.orderNo = "other-order";
                case "type" -> existing.operationType = "refund";
                default -> throw new AssertionError(variant);
              }
              when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenReturn(existing);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals(409, failure.getHttpStatus());
              assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
              if (!disabled)
                verify(balanceLedgerMapper, org.mockito.Mockito.never())
                    .selectDebitForUpdate(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 历史快照0/尾零/MAX不和当前wallet比较，不新source query或重新credit。 */
  @Test
  void persistedRefundReplayKeepsHealthySnapshotBoundsAndCurrentWalletIndependence() {
    for (boolean disabled : java.util.List.of(false, true)) {
      for (BigDecimal before :
          java.util.List.of(
              BigDecimal.ZERO, new BigDecimal("90.000"), new BigDecimal("9999999999999989.99"))) {
        var request = installRefundProofMode(disabled ? "disabled-replay" : "active-fresh");
        var user = activeUser(null);
        if (disabled) user.status = 0;
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
        var existing = appliedRefund("refund-1", before.add(new BigDecimal("10.000")));
        existing.amount = new BigDecimal("10.000");
        existing.balanceBefore = before;
        when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenReturn(existing);
        var response = userService.refundInternal(7L, 7L, request);
        assertEquals(
            existing.balanceAfter.toPlainString(),
            ((com.cloudmall.user.domain.vo.BalanceRefundVO) response.data).balance);
        verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
        verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
        if (!disabled)
          verify(balanceLedgerMapper, org.mockito.Mockito.never()).selectDebitForUpdate(any());
      }
    }
  }

  /** disabled source原404仍先行，原DAO故障不改成missing或成功。 */
  @Test
  void refundReplayKeepsDisabledSourcePriorityAndQueryFailureChannel() {
    var request = installRefundProofMode("disabled-replay");
    originalRefundDebit.originalOperationKey = "bad";
    var corrupt = appliedRefund("refund-1", new BigDecimal("100.00"));
    corrupt.amount = null;
    when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenReturn(corrupt);
    assertEquals(
        "COMMON_NOT_FOUND",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, request))
            .getCode());
    var activeRequest = installRefundProofMode("active-fresh");
    var unavailable =
        new org.springframework.dao.DataRetrievalFailureException(
            "owned-refund-replay-read-failure");
    when(balanceLedgerMapper.selectByOperationKey("refund-1")).thenThrow(unavailable);
    org.junit.jupiter.api.Assertions.assertSame(
        unavailable,
        assertThrows(
            RuntimeException.class, () -> userService.refundInternal(7L, 7L, activeRequest)));
  }

  /** NULL归属金额必须404而不是NPE，原错误归属先于新增freeze查询。 */
  @Test
  void disabledRefundAttemptMoneyMustBeProvenBeforeFreezeRead() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("disabled-fresh", "disabled-replay")) {
      for (BigDecimal amount :
          new BigDecimal[] {
            null,
            BigDecimal.ZERO,
            new BigDecimal("-1"),
            new BigDecimal("10.001"),
            new BigDecimal("10000000000000000.00")
          }) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              var attempt = confirmedTccAttempt();
              attempt.payNo = request.payNo;
              attempt.orderNo = request.orderNo;
              attempt.ledgerKey = request.originalOperationKey;
              attempt.operationKey = "tcc-debit:" + request.payNo;
              attempt.amount = amount;
              when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(attempt);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals(404, failure.getHttpStatus());
              assertEquals("COMMON_NOT_FOUND", failure.getCode());
              org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper);
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 单独的CONFIRMED attempt标签不足以证明真实确认冻结、分支及金额快照。 */
  @Test
  void disabledRefundRequiresExactConfirmedFreezeAndOriginalDebitSnapshot() {
    java.util.List<org.junit.jupiter.api.function.Executable> assertions =
        new java.util.ArrayList<>();
    for (String mode : java.util.List.of("disabled-fresh", "disabled-replay")) {
      for (String variant :
          java.util.List.of(
              "missing",
              "state",
              "user",
              "pay",
              "order",
              "key",
              "xid",
              "branch",
              "amount-null",
              "amount",
              "before-null",
              "after-null",
              "before",
              "after",
              "negative")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              switch (variant) {
                case "missing" ->
                    when(balanceTccDebitMapper.selectByPayNo("pay-1")).thenReturn(null);
                case "state" -> originalRefundFreeze.state = "TRYING";
                case "user" -> originalRefundFreeze.userId = 8L;
                case "pay" -> originalRefundFreeze.payNo = "PAY-1";
                case "order" -> originalRefundFreeze.orderNo = "other";
                case "key" -> originalRefundFreeze.operationKey = "TCC-debit:pay-1";
                case "xid" -> originalRefundFreeze.xid = "other";
                case "branch" -> originalRefundFreeze.branchId = 99L;
                case "amount-null" -> originalRefundFreeze.amount = null;
                case "amount" -> originalRefundFreeze.amount = new BigDecimal("11.00");
                case "before-null" -> originalRefundFreeze.balanceBefore = null;
                case "after-null" -> originalRefundFreeze.balanceAfterTry = null;
                case "before" -> originalRefundFreeze.balanceBefore = new BigDecimal("101.00");
                case "after" -> originalRefundFreeze.balanceAfterTry = new BigDecimal("89.00");
                case "negative" -> originalRefundFreeze.balanceAfterTry = new BigDecimal("-1.00");
                default -> throw new AssertionError(variant);
              }
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals(404, failure.getHttpStatus());
              assertEquals("COMMON_NOT_FOUND", failure.getCode());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** active已观察TCC核freeze，disabled健康复制/尾零及DAO异常渠道保持。 */
  @Test
  void disabledRefundFreezeProofKeepsHealthyMoneyAndReadFailure() {
    var activeRequest = installRefundProofMode("active-fresh");
    userService.refundInternal(7L, 7L, activeRequest);
    verify(balanceDebitAttemptMapper).selectByPayNo(activeRequest.payNo);
    verify(balanceTccDebitMapper).selectByPayNo(activeRequest.payNo);
    var request = installRefundProofMode("disabled-fresh");
    originalRefundFreeze.amount = new BigDecimal("10.000");
    originalRefundFreeze.balanceBefore = new BigDecimal("100.000");
    originalRefundFreeze.balanceAfterTry = new BigDecimal("90.000");
    userService.refundInternal(7L, 7L, request);
    verify(userMapper).creditBalance(any(), any(), any());
    var failureRequest = installRefundProofMode("disabled-fresh");
    var unavailable =
        new org.springframework.dao.DataRetrievalFailureException("owned-freeze-read-failure");
    when(balanceTccDebitMapper.selectByPayNo("pay-1")).thenThrow(unavailable);
    org.junit.jupiter.api.Assertions.assertSame(
        unavailable,
        assertThrows(
            RuntimeException.class, () -> userService.refundInternal(7L, 7L, failureRequest)));
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
  }

  /** 查询主行字段与已知状态快照必须完整；NULL金额不能在VO映射时抛空指针。 */
  @Test
  void balanceOperationRejectsMalformedPrimaryRows() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("DEBIT", "REJECTED", "REFUND")) {
      for (String mutation :
          java.util.List.of(
              "key-null",
              "key-alias",
              "key-other",
              "pay-null",
              "pay-blank",
              "order-null",
              "order-blank",
              "amount-null",
              "amount-zero",
              "amount-negative",
              "amount-fraction",
              "amount-overflow",
              "before-null",
              "before-negative",
              "before-fraction",
              "before-overflow",
              "after-null",
              "after-negative",
              "after-fraction",
              "after-overflow",
              "type-null",
              "type-unknown",
              "state-null",
              "state-unknown",
              "state-alias",
              "wrong-delta",
              "bad-origin",
              "zero-delta")) {
        assertions.add(
            () -> {
              String key = installOperationQuery(mode);
              switch (mutation) {
                case "key-null" -> operationLedger.operationKey = null;
                case "key-alias" ->
                    operationLedger.operationKey = key.toUpperCase(java.util.Locale.ROOT);
                case "key-other" -> operationLedger.operationKey = "other";
                case "pay-null" -> operationLedger.payNo = null;
                case "pay-blank" -> operationLedger.payNo = " ";
                case "order-null" -> operationLedger.orderNo = null;
                case "order-blank" -> operationLedger.orderNo = " ";
                case "amount-null" -> operationLedger.amount = null;
                case "amount-zero" -> operationLedger.amount = BigDecimal.ZERO;
                case "amount-negative" -> operationLedger.amount = BigDecimal.ONE.negate();
                case "amount-fraction" -> operationLedger.amount = new BigDecimal("10.001");
                case "amount-overflow" ->
                    operationLedger.amount = new BigDecimal("10000000000000000");
                case "before-null" -> operationLedger.balanceBefore = null;
                case "before-negative" -> operationLedger.balanceBefore = BigDecimal.ONE.negate();
                case "before-fraction" -> operationLedger.balanceBefore = new BigDecimal("90.001");
                case "before-overflow" ->
                    operationLedger.balanceBefore = new BigDecimal("10000000000000000");
                case "after-null" -> operationLedger.balanceAfter = null;
                case "after-negative" -> operationLedger.balanceAfter = BigDecimal.ONE.negate();
                case "after-fraction" -> operationLedger.balanceAfter = new BigDecimal("90.001");
                case "after-overflow" ->
                    operationLedger.balanceAfter = new BigDecimal("10000000000000000");
                case "type-null" -> operationLedger.operationType = null;
                case "type-unknown" -> operationLedger.operationType = "OTHER";
                case "state-null" -> operationLedger.state = null;
                case "state-unknown" -> operationLedger.state = "UNKNOWN";
                case "state-alias" ->
                    operationLedger.state =
                        operationLedger.state.toLowerCase(java.util.Locale.ROOT);
                case "wrong-delta" -> operationLedger.balanceAfter = new BigDecimal("3.00");
                case "bad-origin" ->
                    operationLedger.originalOperationKey = "REFUND".equals(mode) ? " " : "other";
                case "zero-delta" -> {
                  operationLedger.balanceBefore = new BigDecimal("10.00");
                  operationLedger.balanceAfter = new BigDecimal("10.00");
                }
                default -> throw new AssertionError(mutation);
              }
              assertOperationConflict(key);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 退款查询须证明原实际扣款，源缺失也属于缺证409而不是主操作不存在404。 */
  @Test
  void refundOperationRejectsMissingOrMismatchedOriginalDebit() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        java.util.List.of(
            "missing",
            "alias-key",
            "wrong-type",
            "rejected",
            "other-user",
            "other-pay",
            "other-order",
            "origin",
            "null-amount",
            "null-before",
            "wrong-delta",
            "smaller-debit")) {
      assertions.add(
          () -> {
            String key = installOperationQuery("REFUND");
            switch (mutation) {
              case "missing" ->
                  when(balanceLedgerMapper.selectByOperationKey("pay-1")).thenReturn(null);
              case "alias-key" -> operationSource.operationKey = "PAY-1";
              case "wrong-type" -> operationSource.operationType = "REFUND";
              case "rejected" -> operationSource.state = "REJECTED";
              case "other-user" -> operationSource.userId = 8L;
              case "other-pay" -> operationSource.payNo = "other";
              case "other-order" -> operationSource.orderNo = "other";
              case "origin" -> operationSource.originalOperationKey = "other";
              case "null-amount" -> operationSource.amount = null;
              case "null-before" -> operationSource.balanceBefore = null;
              case "wrong-delta" -> operationSource.balanceAfter = new BigDecimal("89.00");
              case "smaller-debit" -> {
                operationSource.amount = new BigDecimal("5.00");
                operationSource.balanceBefore = new BigDecimal("95.00");
              }
              default -> throw new AssertionError(mutation);
            }
            assertOperationConflict(key);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** Raw关联集合不能为空证据、负数抵消或截断，REFUND自身也须在已证集合中。 */
  @Test
  void balanceOperationRejectsInvalidRefundCollectionsAndRowMembership() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("DEBIT", "REFUND")) {
      for (String scenario :
          java.util.List.of(
              "null-list",
              "null-row",
              "101",
              "negative",
              "wrong-user",
              "alias-origin",
              "wrong-state",
              "duplicate",
              "excess",
              "missing-member",
              "wrong-member-money")) {
        assertions.add(
            () -> {
              String key = installOperationQuery(mode);
              var candidate = linkedRefund("prior-refund", "2.00");
              java.util.List<BalanceLedgerPO> candidates = java.util.List.of(candidate);
              switch (scenario) {
                case "null-list" -> candidates = null;
                case "null-row" -> candidates = java.util.Arrays.asList((BalanceLedgerPO) null);
                case "101" -> {
                  candidates = new java.util.ArrayList<>();
                  for (int index = 0; index < 101; index++)
                    candidates.add(linkedRefund("prior-" + index, "0.01"));
                }
                case "negative" -> candidate.amount = new BigDecimal("-2.00");
                case "wrong-user" -> candidate.userId = 8L;
                case "alias-origin" -> candidate.originalOperationKey = "PAY-1";
                case "wrong-state" -> candidate.state = "REJECTED";
                case "duplicate" -> candidates = java.util.List.of(candidate, candidate);
                case "excess" -> candidates = java.util.List.of(linkedRefund("excess", "11.00"));
                case "missing-member" -> {
                  if ("DEBIT".equals(mode)) candidates = java.util.List.of();
                }
                case "wrong-member-money" -> candidate.operationKey = "refund-1";
                default -> throw new AssertionError(scenario);
              }
              when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(candidates);
              var paymentRows = new java.util.ArrayList<BalanceLedgerPO>();
              paymentRows.add(operationSource);
              if (candidates != null) paymentRows.addAll(candidates);
              when(balanceLedgerMapper.findRefundPaymentLedgerCandidates("pay-1"))
                  .thenReturn(paymentRows);
              when(balanceLedgerMapper.selectFirstRefundOperationKey("pay-1"))
                  .thenReturn("outside-refund");
              assertOperationConflict(key);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** marker需精确属于已证集合；REJECTED不允许任何退款关联。 */
  @Test
  void debitOperationMarkerCannotHideInconsistentOrRejectedRefundEvidence() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String scenario :
        java.util.List.of(
            "null-marker", "blank-marker", "alias-marker", "other-marker", "rejected-refund")) {
      assertions.add(
          () -> {
            String key =
                installOperationQuery("rejected-refund".equals(scenario) ? "REJECTED" : "DEBIT");
            var priorRefund = linkedRefund("prior-refund", "2.00");
            when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
                .thenReturn(java.util.List.of(priorRefund));
            when(balanceLedgerMapper.findRefundPaymentLedgerCandidates("pay-1"))
                .thenReturn(java.util.List.of(operationSource, priorRefund));
            String markerKey =
                switch (scenario) {
                  case "null-marker" -> null;
                  case "blank-marker" -> " ";
                  case "alias-marker" -> "PRIOR-REFUND";
                  case "other-marker" -> "other";
                  default -> "prior-refund";
                };
            when(balanceLedgerMapper.selectFirstRefundOperationKey("pay-1")).thenReturn(markerKey);
            assertOperationConflict(key);
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 调用者/主行不存在/foreign仍保原优先，不读关联、原来源或钱包。 */
  @Test
  void operationQueryKeepsCallerMissingAndForeignPriorities() {
    String key = installOperationQuery("REFUND");
    assertEquals(
        403,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.balanceOperation(7L, key, 8L))
            .getHttpStatus());
    org.mockito.Mockito.verifyNoInteractions(balanceLedgerMapper);
    when(balanceLedgerMapper.selectByOperationKey(key)).thenReturn(null);
    assertEquals(
        404,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.balanceOperation(7L, key, 7L))
            .getHttpStatus());
    String foreignKey = installOperationQuery("REFUND");
    operationLedger.userId = 8L;
    operationLedger.amount = null;
    assertEquals(
        404,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.balanceOperation(7L, foreignKey, 7L))
            .getHttpStatus());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
    assertOperationReadOnly();
  }

  /** SQL读取故障保持同对象异常，不能映射为无账本或补空集合。 */
  @Test
  void operationQueryDatabaseFaultsKeepTheOriginalExceptionChannel() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String stage : java.util.List.of("primary", "source", "candidates", "marker")) {
      assertions.add(
          () -> {
            String key = installOperationQuery("source".equals(stage) ? "REFUND" : "DEBIT");
            var unavailable =
                new org.springframework.dao.DataRetrievalFailureException(
                    "owned-operation-read-" + stage);
            switch (stage) {
              case "primary" ->
                  when(balanceLedgerMapper.selectByOperationKey(key)).thenThrow(unavailable);
              case "source" ->
                  when(balanceLedgerMapper.selectByOperationKey("pay-1")).thenThrow(unavailable);
              case "candidates" ->
                  when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
                      .thenThrow(unavailable);
              case "marker" ->
                  when(balanceLedgerMapper.selectFirstRefundOperationKey("pay-1"))
                      .thenThrow(unavailable);
              default -> throw new AssertionError(stage);
            }
            org.junit.jupiter.api.Assertions.assertSame(
                unavailable,
                assertThrows(
                    RuntimeException.class, () -> userService.balanceOperation(7L, key, 7L)));
            assertOperationReadOnly();
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 合法单行和部分/100集合保持原字段与selector，查询不扩当前wallet/归属。 */
  @Test
  void operationQueryHealthyHistoryKeepsFieldsAndBoundedRefunds() {
    for (String mode : java.util.List.of("DEBIT", "REJECTED", "REFUND")) {
      String key = installOperationQuery(mode);
      var response =
          (com.cloudmall.user.domain.vo.BalanceOperationVO)
              userService.balanceOperation(7L, key, 7L).data;
      assertEquals(key, response.operationKey);
      assertEquals("REJECTED".equals(mode) ? "DEBIT" : mode, response.type);
      assertEquals("REJECTED".equals(mode) ? "REJECTED" : "APPLIED", response.state);
      assertEquals("10.00", response.amount);
      assertEquals(null, response.refundOperationKey);
      assertOperationReadOnly();
    }
    for (int count : java.util.List.of(1, 100)) {
      String key = installOperationQuery("DEBIT");
      var candidates = new java.util.ArrayList<BalanceLedgerPO>();
      for (int index = 0; index < count; index++)
        candidates.add(linkedRefund("prior-" + index, "0.010"));
      when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(candidates);
      var paymentRows = new java.util.ArrayList<BalanceLedgerPO>();
      paymentRows.add(operationSource);
      if (candidates != null) paymentRows.addAll(candidates);
      when(balanceLedgerMapper.findRefundPaymentLedgerCandidates("pay-1")).thenReturn(paymentRows);
      when(balanceLedgerMapper.selectFirstRefundOperationKey("pay-1")).thenReturn("prior-0");
      var response =
          (com.cloudmall.user.domain.vo.BalanceOperationVO)
              userService.balanceOperation(7L, key, 7L).data;
      assertEquals("prior-0", response.refundOperationKey);
      assertOperationReadOnly();
    }
  }

  /** 按查询类型准备完整原行；REFUND默认可观察到同一原DEBIT与自身集合。 */
  private String installOperationQuery(String mode) {
    setUp();
    operationSource = appliedDebit("pay-1", new BigDecimal("90.00"));
    operationLedger =
        "REFUND".equals(mode)
            ? appliedRefund("refund-1", new BigDecimal("100.00"))
            : operationSource;
    if ("REJECTED".equals(mode)) {
      operationLedger.state = "REJECTED";
      operationLedger.balanceBefore = new BigDecimal("5.00");
      operationLedger.balanceAfter = new BigDecimal("5.00");
    }
    String key = operationLedger.operationKey;
    when(balanceLedgerMapper.selectByOperationKey("pay-1")).thenReturn(operationSource);
    when(balanceLedgerMapper.selectByOperationKey(key)).thenReturn(operationLedger);
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
        .thenReturn(
            "REFUND".equals(mode) ? java.util.List.of(operationLedger) : java.util.List.of());
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(operationSource.payNo))
        .thenReturn(
            "REFUND".equals(mode)
                ? java.util.List.of(operationSource, operationLedger)
                : java.util.List.of(operationSource));
    return key;
  }

  /** 所有坏证据固定409，不写资金或缓存；不把源缺失误作主操作404。 */
  private void assertOperationConflict(String key) {
    var failure =
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> userService.balanceOperation(7L, key, 7L));
    assertEquals(409, failure.getHttpStatus());
    assertEquals("COMMON_CONFLICT", failure.getCode());
    assertEquals("余额账本事实待核对", failure.getMessage());
    assertOperationReadOnly();
  }

  /** 查询不得写入账本、钱包、归属/冻结或Redis，亦不得读取当前钱包。 */
  private void assertOperationReadOnly() {
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    org.mockito.Mockito.verifyNoInteractions(
        userMapper, balanceDebitAttemptMapper, balanceTccDebitMapper, valueOperations);
  }

  /** 新扣款钱包损坏必须在claim前拒绝，兼容入口同样不能记录假不足。 */
  @Test
  void legacyFreshMalformedWalletStopsBeforeClaimOrMoneyWrite() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (BigDecimal balance :
          java.util.Arrays.asList(
              null,
              new BigDecimal("-1"),
              new BigDecimal("0.001"),
              new BigDecimal("100.001"),
              new BigDecimal("10000000000000000"))) {
        assertions.add(
            () -> {
              var request = installLegacyDebit(entry, null);
              when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(balance));
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request));
              assertEquals(409, failure.getHttpStatus());
              assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
              assertLegacyNoMoneyOrCacheWrite();
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 归属金额NULL和非法域沿原幂等错误，不在compareTo抛空指针。 */
  @Test
  void legacyAttemptMoneyRequiresSafePositiveDomain() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (BigDecimal amount :
          java.util.Arrays.asList(
              null,
              BigDecimal.ZERO,
              new BigDecimal("-1"),
              new BigDecimal("10.001"),
              new BigDecimal("10000000000000000"))) {
        assertions.add(
            () -> {
              var request = installLegacyDebit(entry, "APPLIED");
              legacyDebitAttempt.amount = amount;
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request));
              assertEquals(409, failure.getHttpStatus());
              assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).selectDebitByPayNo(any());
              assertLegacyNoMoneyOrCacheWrite();
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 账本实际key/origin及金额要精确证明，坏tuple不得缓存或200重放。 */
  @Test
  void legacyReplayRejectsMalformedLedgerMetadataBeforeSnapshot() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String mutation :
          java.util.List.of(
              "key-null",
              "key-alias",
              "key-other",
              "origin-empty",
              "origin-other",
              "origin-literal-null",
              "amount-null",
              "amount-zero",
              "amount-negative",
              "amount-fraction",
              "amount-overflow",
              "wrong-user")) {
        assertions.add(
            () -> {
              var request = installLegacyDebit(entry, "APPLIED");
              switch (mutation) {
                case "key-null" -> legacyDebitLedger.operationKey = null;
                case "key-alias" -> legacyDebitLedger.operationKey = "PAY-1";
                case "key-other" -> legacyDebitLedger.operationKey = "other";
                case "origin-empty" -> legacyDebitLedger.originalOperationKey = "";
                case "origin-other" -> legacyDebitLedger.originalOperationKey = "other";
                case "origin-literal-null" -> legacyDebitLedger.originalOperationKey = "null";
                case "amount-null" -> legacyDebitLedger.amount = null;
                case "amount-zero" -> legacyDebitLedger.amount = BigDecimal.ZERO;
                case "amount-negative" -> legacyDebitLedger.amount = BigDecimal.ONE.negate();
                case "amount-fraction" -> legacyDebitLedger.amount = new BigDecimal("10.001");
                case "amount-overflow" ->
                    legacyDebitLedger.amount = new BigDecimal("10000000000000000");
                case "wrong-user" -> legacyDebitLedger.userId = 8L;
                default -> throw new AssertionError(mutation);
              }
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request),
                      entry + ":" + mutation);
              assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
              assertLegacyNoMoneyOrCacheWrite();
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 两个已知终态的快照须证明扣款或原不足，坏snapshot不能借标签返回结果。 */
  @Test
  void legacyAppliedAndRejectedSnapshotsMustProveTheirMoneyDecision() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String state : java.util.List.of("APPLIED", "REJECTED")) {
        for (String mutation :
            java.util.List.of(
                "before-null",
                "after-null",
                "before-negative",
                "after-negative",
                "before-fraction",
                "after-fraction",
                "before-overflow",
                "after-overflow",
                "wrong-delta",
                "enough-rejected")) {
          assertions.add(
              () -> {
                var request = installLegacyDebit(entry, state);
                switch (mutation) {
                  case "before-null" -> legacyDebitLedger.balanceBefore = null;
                  case "after-null" -> legacyDebitLedger.balanceAfter = null;
                  case "before-negative" ->
                      legacyDebitLedger.balanceBefore = BigDecimal.ONE.negate();
                  case "after-negative" -> legacyDebitLedger.balanceAfter = BigDecimal.ONE.negate();
                  case "before-fraction" ->
                      legacyDebitLedger.balanceBefore = new BigDecimal("100.001");
                  case "after-fraction" ->
                      legacyDebitLedger.balanceAfter = new BigDecimal("90.001");
                  case "before-overflow" ->
                      legacyDebitLedger.balanceBefore = new BigDecimal("10000000000000000");
                  case "after-overflow" ->
                      legacyDebitLedger.balanceAfter = new BigDecimal("10000000000000000");
                  case "wrong-delta" -> legacyDebitLedger.balanceAfter = new BigDecimal("3.00");
                  case "enough-rejected" -> {
                    legacyDebitLedger.balanceBefore = new BigDecimal("10.00");
                    legacyDebitLedger.balanceAfter = new BigDecimal("10.00");
                  }
                  default -> throw new AssertionError(mutation);
                }
                var failure =
                    assertThrows(
                        com.cloudmall.common.error.BizException.class,
                        () -> invokeLegacyDebit(entry, request),
                        entry + ":" + state + ":" + mutation);
                assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
                assertLegacyNoMoneyOrCacheWrite();
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 健康钱包边界及历史快照重放保持，不以当前wallet或资金回补改变原拒款决策。 */
  @Test
  void legacyHealthyWalletAndHistoricalSnapshotDecisionsRemainStable() {
    for (String entry : java.util.List.of("debits", "compat")) {
      for (BigDecimal balance :
          java.util.List.of(
              BigDecimal.ZERO,
              new BigDecimal("0.01"),
              new BigDecimal("100.000"),
              new BigDecimal("9999999999999999.99"))) {
        var request = installLegacyDebit(entry, null);
        request.amount = new BigDecimal("0.010");
        when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(balance));
        if (balance.signum() == 0) {
          assertEquals(
              "PAYMENT_FAILED",
              assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request))
                  .getCode());
          verify(userMapper, org.mockito.Mockito.never()).debitBalance(any(), any(), any());
        } else {
          var response = invokeLegacyDebit(entry, request);
          assertEquals(
              balance.subtract(request.amount).toPlainString(),
              ((com.cloudmall.user.domain.vo.BalanceDebitVO) response.data).balance);
          verify(userMapper).debitBalance(eq(7L), eq(request.amount), any());
        }
        verify(balanceDebitAttemptMapper).insert(any());
        verify(balanceLedgerMapper).insert(any());
      }
      for (String state : java.util.List.of("APPLIED", "REJECTED")) {
        var request = installLegacyDebit(entry, state);
        when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(null));
        legacyDebitLedger.amount = new BigDecimal("10.000");
        legacyDebitAttempt.amount = new BigDecimal("10.000");
        if ("APPLIED".equals(state)) {
          legacyDebitLedger.balanceBefore = new BigDecimal("10.000");
          legacyDebitLedger.balanceAfter = new BigDecimal("0.000");
          when(balanceDebitAttemptMapper.selectByPayNo("pay-1")).thenReturn(null);
          var response = invokeLegacyDebit(entry, request);
          assertEquals(
              "0.000", ((com.cloudmall.user.domain.vo.BalanceDebitVO) response.data).balance);
          verify(userMapper, org.mockito.Mockito.never()).debitBalance(any(), any(), any());
          verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
        } else {
          assertEquals(
              "PAYMENT_FAILED",
              assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request))
                  .getCode());
          assertLegacyNoMoneyOrCacheWrite();
        }
      }
    }
  }

  /** 原状态优先于坏snapshot；DAO错误不转成钱包未知；参数仍先于资金读取。 */
  @Test
  void legacyExistingStateAndDatabaseFailureKeepOriginalPriority() {
    var request = installLegacyDebit("debits", "APPLIED");
    legacyDebitAttempt.state = "REJECTED";
    legacyDebitLedger.balanceBefore = null;
    var mismatchRequest = request;
    assertEquals(
        "BALANCE_DEBIT_UNKNOWN",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> invokeLegacyDebit("debits", mismatchRequest))
            .getCode());
    assertLegacyNoMoneyOrCacheWrite();
    request = installLegacyDebit("debits", "APPLIED");
    legacyDebitLedger.state = "UNKNOWN";
    legacyDebitAttempt.state = "UNKNOWN";
    legacyDebitLedger.balanceAfter = null;
    var unknownRequest = request;
    assertEquals(
        "BALANCE_DEBIT_UNKNOWN",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> invokeLegacyDebit("debits", unknownRequest))
            .getCode());
    assertLegacyNoMoneyOrCacheWrite();
    var failureRequest = installLegacyDebit("debits", null);
    when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(null));
    var unavailable =
        new org.springframework.dao.DataRetrievalFailureException(
            "owned-legacy-history-read-failure");
    when(balanceLedgerMapper.findDebitCandidatesByPayNo("pay-1")).thenThrow(unavailable);
    org.junit.jupiter.api.Assertions.assertSame(
        unavailable,
        assertThrows(RuntimeException.class, () -> invokeLegacyDebit("debits", failureRequest)));
    assertLegacyNoMoneyOrCacheWrite();
  }

  /** 缺列表或空行不能证无历史，多行固定冲突；任何情况不查fixed key或写资金/cache。 */
  @Test
  void legacyBoundedHistoryRejectsMissingAndMultipleCandidates() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String state : java.util.List.of("fresh", "APPLIED", "REJECTED", "history-no-attempt")) {
        for (String variant : java.util.List.of("null-list", "null-row", "two", "three")) {
          assertions.add(
              () -> {
                var request =
                    installLegacyDebit(
                        entry,
                        "fresh".equals(state)
                            ? null
                            : "history-no-attempt".equals(state) ? "APPLIED" : state);
                if ("history-no-attempt".equals(state))
                  when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(null);
                var candidate =
                    legacyDebitLedger == null
                        ? appliedDebit(request.operationKey, new BigDecimal("90.00"))
                        : legacyDebitLedger;
                java.util.List<BalanceLedgerPO> candidates =
                    switch (variant) {
                      case "null-list" -> null;
                      case "null-row" -> java.util.Collections.singletonList(null);
                      case "two" -> java.util.List.of(candidate, new BalanceLedgerPO());
                      case "three" ->
                          java.util.List.of(
                              candidate, new BalanceLedgerPO(), new BalanceLedgerPO());
                      default -> throw new AssertionError(variant);
                    };
                when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
                    .thenReturn(candidates);
                var failure =
                    assertThrows(
                        com.cloudmall.common.error.BizException.class,
                        () -> invokeLegacyDebit(entry, request));
                boolean missing = variant.startsWith("null");
                assertEquals(
                    missing ? "BALANCE_DEBIT_UNKNOWN" : "IDEMPOTENCY_CONFLICT", failure.getCode());
                assertEquals(missing ? "扣款归属缺少对应账本事实" : "支付号已绑定其他扣款操作", failure.getMessage());
                assertEquals(409, failure.getHttpStatus());
                verify(balanceLedgerMapper, org.mockito.Mockito.never())
                    .selectByOperationKey(any());
                verify(balanceLedgerMapper, org.mockito.Mockito.never()).selectDebitByPayNo(any());
                assertLegacyNoMoneyOrCacheWrite();
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 单候选仍按原精确操作键拒绝，与坏状态/缺快照/钱包无关，不伪造新归属。 */
  @Test
  void legacySingleHistoryKeepsExactOperationKeyConflict() {
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String variant : java.util.List.of("different", "case", "missing")) {
        var request = installLegacyDebit(entry, null);
        var candidate = new BalanceLedgerPO();
        candidate.operationKey =
            switch (variant) {
              case "different" -> "other-operation";
              case "case" -> request.operationKey.toUpperCase(java.util.Locale.ROOT);
              case "missing" -> null;
              default -> throw new AssertionError(variant);
            };
        when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
            .thenReturn(java.util.List.of(candidate));
        var failure =
            assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> invokeLegacyDebit(entry, request));
        assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
        assertEquals("支付号已绑定其他扣款操作", failure.getMessage());
        assertEquals(409, failure.getHttpStatus());
        verify(balanceLedgerMapper, org.mockito.Mockito.never()).selectByOperationKey(any());
        assertLegacyNoMoneyOrCacheWrite();
      }
    }
  }

  /** 数据库故障原对象传播，错误attempt仍先拒绝；所有失败均不产生资金或缓存写。 */
  @Test
  void legacyBoundedHistoryFaultKeepsAttemptPriority() {
    for (String entry : java.util.List.of("debits", "compat")) {
      var request = installLegacyDebit(entry, null);
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-legacy-bounded-history-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(balanceLedgerMapper)
          .findDebitCandidatesByPayNo(request.payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure, assertThrows(RuntimeException.class, () -> invokeLegacyDebit(entry, request)));
      assertLegacyNoMoneyOrCacheWrite();
      var invalidRequest = installLegacyDebit(entry, "APPLIED");
      legacyDebitAttempt.mode = "TCC";
      org.mockito.Mockito.doThrow(failure)
          .when(balanceLedgerMapper)
          .findDebitCandidatesByPayNo(invalidRequest.payNo);
      assertEquals(
          "IDEMPOTENCY_CONFLICT",
          assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> invokeLegacyDebit(entry, invalidRequest))
              .getCode());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findDebitCandidatesByPayNo(any());
      assertLegacyNoMoneyOrCacheWrite();
    }
  }

  /** fresh不能过滤掉原pay任意资金行，包含坏元数据、null行或101候选，均不claim或扣款。 */
  @Test
  void legacyFreshDebitRequiresEmptyAllTypePaymentLedgerScope() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String variant :
          java.util.List.of(
              "refund",
              "unknown-type",
              "unknown-state",
              "other-user",
              "pay-alias",
              "null-row",
              "limit101")) {
        assertions.add(
            () -> {
              var request = installLegacyDebit(entry, null);
              var candidate = new BalanceLedgerPO();
              candidate.operationType = "unknown-type".equals(variant) ? "UNKNOWN" : "REFUND";
              candidate.state = "unknown-state".equals(variant) ? "UNKNOWN" : "APPLIED";
              candidate.payNo =
                  "pay-alias".equals(variant)
                      ? request.payNo.toUpperCase(java.util.Locale.ROOT)
                      : request.payNo;
              candidate.userId = "other-user".equals(variant) ? 8L : 7L;
              java.util.List<BalanceLedgerPO> candidates =
                  "null-row".equals(variant)
                      ? java.util.Collections.singletonList(null)
                      : "limit101".equals(variant)
                          ? java.util.Collections.nCopies(101, candidate)
                          : java.util.List.of(candidate);
              when(balanceLedgerMapper.findPaymentLedgerCandidates(request.payNo))
                  .thenReturn(candidates);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request));
              assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
              assertEquals("支付号已有资金账本事实", failure.getMessage());
              assertEquals(409, failure.getHttpStatus());
              verify(balanceLedgerMapper).findPaymentLedgerCandidates(request.payNo);
              assertLegacyNoMoneyOrCacheWrite();
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 缺列表不是空，真实DAO异常原对象传播；新scope先于钱包校验、认领和资金写。 */
  @Test
  void legacyFreshPaymentScopeMissingAndFaultStopBeforeWalletWrites() {
    for (String entry : java.util.List.of("debits", "compat")) {
      var request = installLegacyDebit(entry, null);
      when(balanceLedgerMapper.findPaymentLedgerCandidates(request.payNo)).thenReturn(null);
      var missing =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> invokeLegacyDebit(entry, request));
      assertEquals("BALANCE_DEBIT_UNKNOWN", missing.getCode());
      assertEquals("扣款归属缺少对应账本事实", missing.getMessage());
      assertEquals(409, missing.getHttpStatus());
      assertLegacyNoMoneyOrCacheWrite();
      var faultRequest = installLegacyDebit(entry, null);
      when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(null));
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-legacy-fresh-payment-scope-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(balanceLedgerMapper)
          .findPaymentLedgerCandidates(faultRequest.payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(RuntimeException.class, () -> invokeLegacyDebit(entry, faultRequest)));
      assertLegacyNoMoneyOrCacheWrite();
    }
  }

  /** 已有APPLIED/REJECTED及无attempt历史继续独立重放，错误归属仍早于fresh scope。 */
  @Test
  void legacyReplayAndExistingAttemptKeepPriorityBeforeFreshPaymentScope() {
    for (String entry : java.util.List.of("debits", "compat")) {
      for (String state : java.util.List.of("APPLIED", "REJECTED", "history-no-attempt")) {
        var request =
            installLegacyDebit(entry, "history-no-attempt".equals(state) ? "APPLIED" : state);
        if ("history-no-attempt".equals(state))
          when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(null);
        when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(null));
        when(balanceLedgerMapper.findPaymentLedgerCandidates(request.payNo))
            .thenReturn(java.util.List.of(new BalanceLedgerPO()));
        if ("REJECTED".equals(state)) {
          assertEquals(
              "PAYMENT_FAILED",
              assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> invokeLegacyDebit(entry, request))
                  .getCode());
          assertLegacyNoMoneyOrCacheWrite();
        } else {
          assertEquals(
              "90.00",
              ((com.cloudmall.user.domain.vo.BalanceDebitVO) invokeLegacyDebit(entry, request).data)
                  .balance);
          verify(balanceDebitAttemptMapper, org.mockito.Mockito.never()).insert(any());
          verify(userMapper, org.mockito.Mockito.never()).debitBalance(any(), any(), any());
          verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
        }
        verify(balanceLedgerMapper, org.mockito.Mockito.never()).findPaymentLedgerCandidates(any());
      }
      var invalidRequest = installLegacyDebit(entry, "APPLIED");
      legacyDebitAttempt.mode = "TCC";
      org.mockito.Mockito.doThrow(
              new org.springframework.dao.DataAccessResourceFailureException(
                  "owned-should-not-read-fresh-scope"))
          .when(balanceLedgerMapper)
          .findPaymentLedgerCandidates(invalidRequest.payNo);
      assertEquals(
          "IDEMPOTENCY_CONFLICT",
          assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> invokeLegacyDebit(entry, invalidRequest))
              .getCode());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findPaymentLedgerCandidates(any());
      assertLegacyNoMoneyOrCacheWrite();
    }
  }

  /** 新退款候选须可靠唯一且复制原扣款，缺/多/错字段均在关联合计与资金写前拒绝。 */
  @Test
  void freshRefundRejectsMissingDuplicateOrMismatchedDebitScope() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      for (String variant :
          java.util.List.of(
              "null-list",
              "empty",
              "null-row",
              "two",
              "three",
              "id-null",
              "id-other",
              "key",
              "user",
              "pay",
              "order",
              "type",
              "state",
              "origin",
              "amount",
              "snapshot",
              "before-null",
              "after-null")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              var candidate = copyRefundDebit(originalRefundDebit);
              switch (variant) {
                case "id-null" -> candidate.id = null;
                case "id-other" -> candidate.id = 6L;
                case "key" -> candidate.operationKey = "PAY-1";
                case "user" -> candidate.userId = 8L;
                case "pay" -> candidate.payNo = "PAY-1";
                case "order" -> candidate.orderNo = "other-order";
                case "type" -> candidate.operationType = "debit";
                case "state" -> candidate.state = "UNKNOWN";
                case "origin" -> candidate.originalOperationKey = "other-key";
                case "amount" -> {
                  candidate.amount = new BigDecimal("11.00");
                  candidate.balanceAfter = new BigDecimal("89.00");
                }
                case "snapshot" -> {
                  candidate.balanceBefore = new BigDecimal("110.00");
                  candidate.balanceAfter = new BigDecimal("100.00");
                }
                case "before-null" -> candidate.balanceBefore = null;
                case "after-null" -> candidate.balanceAfter = null;
                default -> {}
              }
              java.util.List<BalanceLedgerPO> candidates =
                  switch (variant) {
                    case "null-list" -> null;
                    case "empty" -> java.util.List.of();
                    case "null-row" -> java.util.Collections.singletonList(null);
                    case "two" -> java.util.List.of(candidate, new BalanceLedgerPO());
                    case "three" ->
                        java.util.List.of(candidate, new BalanceLedgerPO(), new BalanceLedgerPO());
                    default -> java.util.List.of(candidate);
                  };
              when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
                  .thenReturn(candidates);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
              assertEquals("原扣款不存在或未应用", failure.getMessage());
              assertEquals(409, failure.getHttpStatus());
              verify(balanceLedgerMapper, org.mockito.Mockito.never())
                  .findLinkedRefundCandidates(any());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
              org.mockito.Mockito.verifyNoInteractions(valueOperations);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真实DAO故障原对象传播，原源坏字段/disabled404优先于有界查询，失败无credit/新退款。 */
  @Test
  void freshRefundDebitScopeFaultKeepsOriginalSourcePriority() {
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      var request = installRefundProofMode(mode);
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-refund-debit-scope-fault");
      org.mockito.Mockito.doThrow(failure)
          .when(balanceLedgerMapper)
          .findDebitCandidatesByPayNo(request.payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
      var invalidRequest = installRefundProofMode(mode);
      originalRefundDebit.originalOperationKey = "bad-source";
      org.mockito.Mockito.doThrow(failure)
          .when(balanceLedgerMapper)
          .findDebitCandidatesByPayNo(invalidRequest.payNo);
      assertOriginalRefundRejected(mode, invalidRequest);
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findDebitCandidatesByPayNo(any());
    }
  }

  /** 合法数值尾零复制通过；active/disabled已有退款replay不增加同pay来源查询。 */
  @Test
  void freshRefundScopeAcceptsValueCopiesAndKeepsExistingReplayIndependent() {
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      var request = installRefundProofMode(mode);
      var candidate = copyRefundDebit(originalRefundDebit);
      candidate.amount = new BigDecimal("10.000");
      candidate.balanceBefore = new BigDecimal("100.000");
      candidate.balanceAfter = new BigDecimal("90.000");
      when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
          .thenReturn(java.util.List.of(candidate));
      userService.refundInternal(7L, 7L, request);
      verify(balanceLedgerMapper).findDebitCandidatesByPayNo(request.payNo);
      verify(userMapper).creditBalance(any(), any(), any());
      var replayRequest = installRefundProofMode(mode);
      when(balanceLedgerMapper.selectByOperationKey(replayRequest.refundOperationKey))
          .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
      org.mockito.Mockito.doThrow(
              new org.springframework.dao.DataAccessResourceFailureException(
                  "owned-replay-scope-must-not-run"))
          .when(balanceLedgerMapper)
          .findDebitCandidatesByPayNo(replayRequest.payNo);
      userService.refundInternal(7L, 7L, replayRequest);
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findDebitCandidatesByPayNo(any());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** PO使用公开字段；测试显式复制原字段，确保每条坏值测试只改变目标证据。 */
  private BalanceLedgerPO copyRefundDebit(BalanceLedgerPO original) {
    BalanceLedgerPO candidate = new BalanceLedgerPO();
    candidate.id = original.id;
    candidate.userId = original.userId;
    candidate.operationKey = original.operationKey;
    candidate.operationType = original.operationType;
    candidate.originalOperationKey = original.originalOperationKey;
    candidate.payNo = original.payNo;
    candidate.orderNo = original.orderNo;
    candidate.amount = original.amount;
    candidate.state = original.state;
    candidate.balanceBefore = original.balanceBefore;
    candidate.balanceAfter = original.balanceAfter;
    candidate.createdAt = original.createdAt;
    return candidate;
  }

  /** active已观察TCC的归属/确认冻结必须完整，坏事实在累计/credit前拒绝。 */
  @Test
  void activeFreshRefundRejectsBadObservedTccOwnerAndFreeze() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        java.util.List.of(
            "mode-null",
            "mode-unknown",
            "mode-alias",
            "attempt-state",
            "attempt-user",
            "attempt-pay",
            "attempt-order",
            "attempt-key",
            "attempt-ledger",
            "attempt-amount-null",
            "attempt-amount",
            "attempt-xid",
            "attempt-branch",
            "freeze-missing",
            "freeze-state",
            "freeze-user",
            "freeze-pay",
            "freeze-order",
            "freeze-key",
            "freeze-xid",
            "freeze-branch",
            "freeze-amount",
            "freeze-snapshot")) {
      assertions.add(
          () -> {
            var request = installRefundProofMode("active-fresh");
            switch (mutation) {
              case "mode-null" -> originalRefundAttempt.mode = null;
              case "mode-unknown" -> originalRefundAttempt.mode = "UNKNOWN";
              case "mode-alias" -> originalRefundAttempt.mode = "tcc";
              case "attempt-state" -> originalRefundAttempt.state = "TRYING";
              case "attempt-user" -> originalRefundAttempt.userId = 8L;
              case "attempt-pay" -> originalRefundAttempt.payNo = "PAY-1";
              case "attempt-order" -> originalRefundAttempt.orderNo = "other-order";
              case "attempt-key" -> originalRefundAttempt.operationKey = "other-key";
              case "attempt-ledger" -> originalRefundAttempt.ledgerKey = "other-ledger";
              case "attempt-amount-null" -> originalRefundAttempt.amount = null;
              case "attempt-amount" -> originalRefundAttempt.amount = new BigDecimal("11.00");
              case "attempt-xid" -> originalRefundAttempt.xid = " ";
              case "attempt-branch" -> originalRefundAttempt.branchId = 0L;
              case "freeze-missing" ->
                  when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(null);
              case "freeze-state" -> originalRefundFreeze.state = "CANCELLED";
              case "freeze-user" -> originalRefundFreeze.userId = 8L;
              case "freeze-pay" -> originalRefundFreeze.payNo = "PAY-1";
              case "freeze-order" -> originalRefundFreeze.orderNo = "other-order";
              case "freeze-key" -> originalRefundFreeze.operationKey = "other-key";
              case "freeze-xid" -> originalRefundFreeze.xid = "other-xid";
              case "freeze-branch" -> originalRefundFreeze.branchId = 99L;
              case "freeze-amount" -> originalRefundFreeze.amount = new BigDecimal("11.00");
              case "freeze-snapshot" ->
                  originalRefundFreeze.balanceAfterTry = new BigDecimal("95.00");
              default -> throw new AssertionError(mutation);
            }
            var failure =
                assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> userService.refundInternal(7L, 7L, request));
            assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
            assertEquals(409, failure.getHttpStatus());
            assertEquals("原扣款不存在或未应用", failure.getMessage());
            if (mutation.startsWith("attempt") || mutation.startsWith("mode"))
              org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper);
            verify(balanceLedgerMapper, org.mockito.Mockito.never())
                .findLinkedRefundCandidates(any());
            verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
            verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 两来源DAO故障原对象传播，原DEBIT或T97坏证据先于新增归属观察。 */
  @Test
  void activeFreshRefundOwnerFaultKeepsOriginalSourcePriority() {
    for (String source : java.util.List.of("attempt", "freeze")) {
      var request = installRefundProofMode("active-fresh");
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException(
              "owned-active-refund-source-fault");
      if ("attempt".equals(source))
        org.mockito.Mockito.doThrow(failure)
            .when(balanceDebitAttemptMapper)
            .selectByPayNo(request.payNo);
      else
        org.mockito.Mockito.doThrow(failure)
            .when(balanceTccDebitMapper)
            .selectByPayNo(request.payNo);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
    var request = installRefundProofMode("active-fresh");
    when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
        .thenReturn(java.util.List.of());
    org.mockito.Mockito.doThrow(new AssertionError("原scope缺证不得观察owner"))
        .when(balanceDebitAttemptMapper)
        .selectByPayNo(request.payNo);
    assertEquals(
        "BALANCE_REFUND_INVALID",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, request))
            .getCode());
    verify(balanceDebitAttemptMapper, org.mockito.Mockito.never()).selectByPayNo(any());
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
  }

  /** null/LEGACY历史兼容；已知TCC一次归属/冻结读，existing active replay不读新来源。 */
  @Test
  void activeFreshRefundOwnerKeepsHistoricalCompatibilityAndReplayReads() {
    for (String mode : java.util.List.of("missing", "LEGACY", "TCC")) {
      var request = installRefundProofMode("active-fresh");
      if ("missing".equals(mode)) {
        when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(null);
        when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(null);
      } else {
        originalRefundAttempt.mode = mode;
        if ("LEGACY".equals(mode)) {
          originalRefundAttempt.state = "APPLIED";
          originalRefundAttempt.operationKey = originalRefundDebit.operationKey;
          originalRefundAttempt.ledgerKey = originalRefundDebit.operationKey;
          originalRefundAttempt.xid = null;
          originalRefundAttempt.branchId = null;
          when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(null);
        }
      }
      userService.refundInternal(7L, 7L, request);
      verify(balanceDebitAttemptMapper).selectByPayNo(request.payNo);
      verify(balanceTccDebitMapper).selectByPayNo(request.payNo);
      verify(userMapper).creditBalance(any(), any(), any());
    }
    var request = installRefundProofMode("active-fresh");
    when(balanceLedgerMapper.selectByOperationKey(request.refundOperationKey))
        .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
    org.mockito.Mockito.doThrow(new AssertionError("active replay不得观察owner"))
        .when(balanceDebitAttemptMapper)
        .selectByPayNo(request.payNo);
    userService.refundInternal(7L, 7L, request);
    org.mockito.Mockito.verifyNoInteractions(balanceDebitAttemptMapper, balanceTccDebitMapper);
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
  }

  /** Legacy已观察归属各坏字段不能因mode正确而被忽略，新退款在累计前停止。 */
  @Test
  void activeFreshRefundRejectsMalformedObservedLegacyOwner() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        java.util.List.of(
            "state-null",
            "state-rejected",
            "user",
            "pay",
            "order",
            "operation",
            "ledger",
            "xid-empty",
            "xid",
            "branch",
            "amount-null",
            "amount-zero",
            "amount-negative",
            "amount-scale",
            "amount-overflow",
            "amount-other")) {
      assertions.add(
          () -> {
            var request = installRefundProofMode("active-fresh");
            originalRefundAttempt.mode = "LEGACY";
            originalRefundAttempt.state = "APPLIED";
            originalRefundAttempt.operationKey = originalRefundDebit.operationKey;
            originalRefundAttempt.ledgerKey = originalRefundDebit.operationKey;
            originalRefundAttempt.xid = null;
            originalRefundAttempt.branchId = null;
            switch (mutation) {
              case "state-null" -> originalRefundAttempt.state = null;
              case "state-rejected" -> originalRefundAttempt.state = "REJECTED";
              case "user" -> originalRefundAttempt.userId = 8L;
              case "pay" -> originalRefundAttempt.payNo = "PAY-1";
              case "order" -> originalRefundAttempt.orderNo = "other-order";
              case "operation" -> originalRefundAttempt.operationKey = "PAY-1";
              case "ledger" -> originalRefundAttempt.ledgerKey = "other-ledger";
              case "xid-empty" -> originalRefundAttempt.xid = "";
              case "xid" -> originalRefundAttempt.xid = "unexpected-xid";
              case "branch" -> originalRefundAttempt.branchId = 0L;
              case "amount-null" -> originalRefundAttempt.amount = null;
              case "amount-zero" -> originalRefundAttempt.amount = BigDecimal.ZERO;
              case "amount-negative" -> originalRefundAttempt.amount = new BigDecimal("-1.00");
              case "amount-scale" -> originalRefundAttempt.amount = new BigDecimal("10.001");
              case "amount-overflow" ->
                  originalRefundAttempt.amount = new BigDecimal("10000000000000000.00");
              case "amount-other" -> originalRefundAttempt.amount = new BigDecimal("11.00");
              default -> throw new AssertionError(mutation);
            }
            var failure =
                assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> userService.refundInternal(7L, 7L, request));
            assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
            assertEquals(409, failure.getHttpStatus());
            assertEquals("原扣款不存在或未应用", failure.getMessage());
            verify(balanceDebitAttemptMapper).selectByPayNo(request.payNo);
            org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper, valueOperations);
            verify(balanceLedgerMapper, org.mockito.Mockito.never())
                .findLinkedRefundCandidates(any());
            verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
            verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 金额尾零复制的Legacy正常退款不读freeze，active existingreplay不重新核坏owner。 */
  @Test
  void activeFreshLegacyRefundAcceptsValueCopyAndKeepsReplayIndependent() {
    var request = installRefundProofMode("active-fresh");
    originalRefundAttempt.mode = "LEGACY";
    originalRefundAttempt.state = "APPLIED";
    originalRefundAttempt.operationKey = originalRefundDebit.operationKey;
    originalRefundAttempt.ledgerKey = originalRefundDebit.operationKey;
    originalRefundAttempt.xid = null;
    originalRefundAttempt.branchId = null;
    originalRefundAttempt.amount = new BigDecimal("10.000");
    when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(null);
    userService.refundInternal(7L, 7L, request);
    verify(balanceDebitAttemptMapper).selectByPayNo(request.payNo);
    verify(balanceTccDebitMapper).selectByPayNo(request.payNo);
    verify(userMapper).creditBalance(any(), any(), any());
    org.mockito.Mockito.clearInvocations(
        balanceDebitAttemptMapper, balanceTccDebitMapper, userMapper, balanceLedgerMapper);
    when(balanceLedgerMapper.selectByOperationKey(request.refundOperationKey))
        .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
    originalRefundAttempt.state = "REJECTED";
    userService.refundInternal(7L, 7L, request);
    org.mockito.Mockito.verifyNoInteractions(balanceDebitAttemptMapper, balanceTccDebitMapper);
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
  }

  /** 无owner仍有冻结时不能解释为历史Legacy，任意state/坏字段/别名/foreign均拒绝。 */
  @Test
  void activeFreshRefundWithoutOwnerRejectsAnyObservedFreeze() {
    for (String variant :
        java.util.List.of(
            "CONFIRMED",
            "TRYING",
            "CANCELLED",
            "UNKNOWN",
            "missing-fields",
            "pay-alias",
            "other-user")) {
      var request = installRefundProofMode("active-fresh");
      when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(null);
      if ("missing-fields".equals(variant))
        when(balanceTccDebitMapper.selectByPayNo(request.payNo))
            .thenReturn(new com.cloudmall.user.domain.po.BalanceTccDebitPO());
      else if ("pay-alias".equals(variant)) originalRefundFreeze.payNo = "PAY-1";
      else if ("other-user".equals(variant)) originalRefundFreeze.userId = 8L;
      else originalRefundFreeze.state = variant;
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> userService.refundInternal(7L, 7L, request));
      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
      assertEquals("原扣款不存在或未应用", failure.getMessage());
      assertEquals(409, failure.getHttpStatus());
      verify(balanceDebitAttemptMapper).selectByPayNo(request.payNo);
      verify(balanceTccDebitMapper).selectByPayNo(request.payNo);
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** 无owner查询故障不得充当无冻结，旧source门优先；真正无冻结历史保持可退款。 */
  @Test
  void missingRefundOwnerFreezeFaultKeepsPriorityAndEmptyHistoryCompatibility() {
    var request = installRefundProofMode("active-fresh");
    when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(null);
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException(
            "owned-missing-owner-freeze-fault");
    org.mockito.Mockito.doThrow(failure).when(balanceTccDebitMapper).selectByPayNo(request.payNo);
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    var invalidRequest = installRefundProofMode("active-fresh");
    originalRefundDebit.originalOperationKey = "bad-source";
    org.mockito.Mockito.doThrow(failure)
        .when(balanceTccDebitMapper)
        .selectByPayNo(invalidRequest.payNo);
    assertOriginalRefundRejected("active-fresh", invalidRequest);
    org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper);
    var historicalRequest = installRefundProofMode("active-fresh");
    when(balanceDebitAttemptMapper.selectByPayNo(historicalRequest.payNo)).thenReturn(null);
    when(balanceTccDebitMapper.selectByPayNo(historicalRequest.payNo)).thenReturn(null);
    userService.refundInternal(7L, 7L, historicalRequest);
    verify(balanceDebitAttemptMapper).selectByPayNo(historicalRequest.payNo);
    verify(balanceTccDebitMapper).selectByPayNo(historicalRequest.payNo);
    verify(userMapper).creditBalance(any(), any(), any());
  }

  /** 有效Legacy owner仍有任意冻结候选也不能入账，坏state或元数据不作忽略依据。 */
  @Test
  void activeFreshLegacyRefundRejectsAnyObservedTccFreeze() {
    for (String variant :
        java.util.List.of(
            "CONFIRMED", "TRYING", "CANCELLED", "UNKNOWN", "missing-fields", "alias", "foreign")) {
      var request = installLegacyRefundWithNoFreeze();
      var freeze = new com.cloudmall.user.domain.po.BalanceTccDebitPO();
      if (!"missing-fields".equals(variant)) {
        freeze.state = variant;
        freeze.payNo = "alias".equals(variant) ? "PAY-1" : request.payNo;
        freeze.userId = "foreign".equals(variant) ? 8L : 7L;
      }
      when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(freeze);
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> userService.refundInternal(7L, 7L, request));
      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
      assertEquals("原扣款不存在或未应用", failure.getMessage());
      assertEquals(409, failure.getHttpStatus());
      verify(balanceDebitAttemptMapper).selectByPayNo(request.payNo);
      verify(balanceTccDebitMapper).selectByPayNo(request.payNo);
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** 有效Legacy存在性DAO故障原样，坏Legacy owner先拒绝；真无冻结保持正常退款。 */
  @Test
  void legacyRefundFreezeFaultKeepsOwnerPriorityAndAbsentFreezeCompatibility() {
    var request = installLegacyRefundWithNoFreeze();
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException(
            "owned-legacy-freeze-source-fault");
    org.mockito.Mockito.doThrow(failure).when(balanceTccDebitMapper).selectByPayNo(request.payNo);
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    var invalidRequest = installLegacyRefundWithNoFreeze();
    originalRefundAttempt.state = "REJECTED";
    org.mockito.Mockito.doThrow(failure)
        .when(balanceTccDebitMapper)
        .selectByPayNo(invalidRequest.payNo);
    assertEquals(
        "BALANCE_REFUND_INVALID",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, invalidRequest))
            .getCode());
    org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper);
    var validRequest = installLegacyRefundWithNoFreeze();
    userService.refundInternal(7L, 7L, validRequest);
    verify(balanceDebitAttemptMapper).selectByPayNo(validRequest.payNo);
    verify(balanceTccDebitMapper).selectByPayNo(validRequest.payNo);
    verify(userMapper).creditBalance(any(), any(), any());
  }

  /** 仅新测试构造完整Legacy owner与无冻结来源，避免借TCC夹具误标兼容。 */
  private BalanceRefundRequestDTO installLegacyRefundWithNoFreeze() {
    var request = installRefundProofMode("active-fresh");
    originalRefundAttempt.mode = "LEGACY";
    originalRefundAttempt.state = "APPLIED";
    originalRefundAttempt.operationKey = originalRefundDebit.operationKey;
    originalRefundAttempt.ledgerKey = originalRefundDebit.operationKey;
    originalRefundAttempt.xid = null;
    originalRefundAttempt.branchId = null;
    when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(null);
    return request;
  }

  /** 同pay全范围缺证、错source或任意孤立退款/未知候选都不能继续新退款。 */
  @Test
  void freshRefundPaymentScopeRejectsMissingAndMalformedFacts() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      for (String variant :
          java.util.List.of(
              "null-list",
              "empty",
              "null-row",
              "source-missing",
              "source-duplicate",
              "source-id",
              "source-snapshot",
              "orphan",
              "wrong-origin",
              "unknown-type",
              "unknown-state",
              "pay-alias",
              "foreign",
              "limit102")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              var source = copyRefundDebit(originalRefundDebit);
              var refund = linkedRefund("pay-candidate", "2.00");
              switch (variant) {
                case "source-id" -> source.id = 99L;
                case "source-snapshot" -> {
                  source.balanceBefore = new BigDecimal("110.00");
                  source.balanceAfter = new BigDecimal("100.00");
                }
                case "orphan" -> refund.originalOperationKey = null;
                case "wrong-origin" -> refund.originalOperationKey = "other-original";
                case "unknown-type" -> refund.operationType = "UNKNOWN";
                case "unknown-state" -> refund.state = "PENDING";
                case "pay-alias" -> refund.payNo = "PAY-1";
                case "foreign" -> refund.userId = 8L;
                default -> {}
              }
              java.util.List<BalanceLedgerPO> rows =
                  switch (variant) {
                    case "null-list" -> null;
                    case "empty" -> java.util.List.of();
                    case "source-missing" -> java.util.List.of(refund);
                    case "source-duplicate" -> java.util.List.of(source, copyRefundDebit(source));
                    case "null-row" -> {
                      var mixed = new java.util.ArrayList<BalanceLedgerPO>();
                      mixed.add(source);
                      mixed.add(null);
                      yield mixed;
                    }
                    case "limit102" -> {
                      var mixed = new java.util.ArrayList<BalanceLedgerPO>();
                      mixed.add(source);
                      mixed.addAll(java.util.Collections.nCopies(101, refund));
                      yield mixed;
                    }
                    case "source-id", "source-snapshot" -> java.util.List.of(source);
                    default -> java.util.List.of(source, refund);
                  };
              when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(request.payNo))
                  .thenReturn(rows);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request));
              assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
              assertEquals("退款累计证据不完整或不一致", failure.getMessage());
              assertEquals(409, failure.getHttpStatus());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 两集合各自合法也须同membership/数值快照，缺行、等额别key、快照漂移均拒绝。 */
  @Test
  void freshRefundRejectsDivergentLinkedAndPaymentMembership() {
    for (String variant :
        java.util.List.of("missing-linked", "missing-payment", "key", "amount", "snapshot")) {
      var request = installRefundProofMode("active-fresh");
      var linked = linkedRefund("refund-member", "2.00");
      var payment = copyRefundDebit(linked);
      switch (variant) {
        case "key" -> payment.operationKey = "other-refund-member";
        case "amount" -> {
          payment.amount = new BigDecimal("3.00");
          payment.balanceAfter = new BigDecimal("93.00");
        }
        case "snapshot" -> {
          payment.balanceBefore = new BigDecimal("91.00");
          payment.balanceAfter = new BigDecimal("93.00");
        }
        default -> {}
      }
      when(balanceLedgerMapper.findLinkedRefundCandidates(originalRefundDebit.operationKey))
          .thenReturn(
              "missing-linked".equals(variant) ? java.util.List.of() : java.util.List.of(linked));
      when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(request.payNo))
          .thenReturn(
              "missing-payment".equals(variant)
                  ? java.util.List.of(originalRefundDebit)
                  : java.util.List.of(originalRefundDebit, payment));
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> userService.refundInternal(7L, 7L, request));
      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    }
  }

  /** 第二集合DAO异常原样，linked坏证先拒不读第二集合；existingreplay不扩大读取。 */
  @Test
  void refundPaymentScopeFaultKeepsLinkedPriorityAndReplayIsolation() {
    var request = installRefundProofMode("active-fresh");
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException(
            "owned-payment-membership-fault");
    org.mockito.Mockito.doThrow(failure)
        .when(balanceLedgerMapper)
        .findRefundPaymentLedgerCandidates(request.payNo);
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    var invalidRequest = installRefundProofMode("active-fresh");
    when(balanceLedgerMapper.findLinkedRefundCandidates(originalRefundDebit.operationKey))
        .thenReturn(java.util.List.of(new BalanceLedgerPO()));
    org.mockito.Mockito.doThrow(failure)
        .when(balanceLedgerMapper)
        .findRefundPaymentLedgerCandidates(invalidRequest.payNo);
    assertEquals(
        "BALANCE_REFUND_INVALID",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.refundInternal(7L, 7L, invalidRequest))
            .getCode());
    verify(balanceLedgerMapper, org.mockito.Mockito.never())
        .findRefundPaymentLedgerCandidates(any());
    var replayRequest = installRefundProofMode("active-fresh");
    when(balanceLedgerMapper.selectByOperationKey(replayRequest.refundOperationKey))
        .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
    org.mockito.Mockito.doThrow(failure)
        .when(balanceLedgerMapper)
        .findRefundPaymentLedgerCandidates(replayRequest.payNo);
    userService.refundInternal(7L, 7L, replayRequest);
    verify(balanceLedgerMapper, org.mockito.Mockito.never())
        .findRefundPaymentLedgerCandidates(any());
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
  }

  /** GET同pay额外/缺证/错source候选不能冒充完整操作证明，三种主行均固定只读409。 */
  @Test
  void operationQueryRejectsMissingAndOrphanPaymentMembership() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("DEBIT", "REFUND", "REJECTED")) {
      for (String variant :
          java.util.List.of(
              "null",
              "empty",
              "null-row",
              "orphan",
              "wrong-origin",
              "extra-debit",
              "source-id",
              "limit102")) {
        assertions.add(
            () -> {
              String key = installOperationQuery(mode);
              var source = copyRefundDebit(operationSource);
              if ("source-id".equals(variant)) source.id = 99L;
              var rows = new java.util.ArrayList<BalanceLedgerPO>();
              rows.add(source);
              if ("REFUND".equals(mode)) rows.add(operationLedger);
              var extra = linkedRefund("unlinked-operation", "1.00");
              extra.originalOperationKey = "wrong-origin".equals(variant) ? "other-key" : null;
              if ("extra-debit".equals(variant)) extra.operationType = "DEBIT";
              java.util.List<BalanceLedgerPO> paymentRows =
                  switch (variant) {
                    case "null" -> null;
                    case "empty" -> java.util.List.of();
                    case "null-row" -> {
                      rows.add(null);
                      yield rows;
                    }
                    case "source-id" -> rows;
                    case "limit102" -> {
                      rows.addAll(java.util.Collections.nCopies(102, extra));
                      yield rows;
                    }
                    default -> {
                      rows.add(extra);
                      yield rows;
                    }
                  };
              when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(operationSource.payNo))
                  .thenReturn(paymentRows);
              assertOperationConflict(key);
              verify(balanceLedgerMapper, org.mockito.Mockito.never())
                  .selectFirstRefundOperationKey(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 新payment DAO错误原对象传播，linked坏证/主行缺失仍先拒绝，不把错误转404或冲突。 */
  @Test
  void operationPaymentReadFaultKeepsLinkedAndMissingPriority() {
    String key = installOperationQuery("DEBIT");
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException(
            "owned-operation-payment-fault");
    org.mockito.Mockito.doThrow(failure)
        .when(balanceLedgerMapper)
        .findRefundPaymentLedgerCandidates("pay-1");
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        assertThrows(RuntimeException.class, () -> userService.balanceOperation(7L, key, 7L)));
    assertOperationReadOnly();
    String invalidKey = installOperationQuery("DEBIT");
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
        .thenReturn(java.util.List.of(new BalanceLedgerPO()));
    org.mockito.Mockito.doThrow(failure)
        .when(balanceLedgerMapper)
        .findRefundPaymentLedgerCandidates("pay-1");
    assertOperationConflict(invalidKey);
    verify(balanceLedgerMapper, org.mockito.Mockito.never())
        .findRefundPaymentLedgerCandidates(any());
    String missingKey = installOperationQuery("DEBIT");
    when(balanceLedgerMapper.selectByOperationKey(missingKey)).thenReturn(null);
    assertEquals(
        "COMMON_NOT_FOUND",
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> userService.balanceOperation(7L, missingKey, 7L))
            .getCode());
    verify(balanceLedgerMapper, org.mockito.Mockito.never())
        .findRefundPaymentLedgerCandidates(any());
  }

  /** 健康DEBIT/REFUND/REJECTED均一次linked+一次pay读，保RR只读及字段/marker语义。 */
  @Test
  void healthyOperationMembershipKeepsReadBudgetAndRejectedState() throws Exception {
    for (String mode : java.util.List.of("DEBIT", "REFUND", "REJECTED")) {
      String key = installOperationQuery(mode);
      var response =
          (com.cloudmall.user.domain.vo.BalanceOperationVO)
              userService.balanceOperation(7L, key, 7L).data;
      assertEquals("REJECTED".equals(mode) ? "REJECTED" : "APPLIED", response.state);
      verify(balanceLedgerMapper).findLinkedRefundCandidates("pay-1");
      verify(balanceLedgerMapper).findRefundPaymentLedgerCandidates("pay-1");
      assertOperationReadOnly();
    }
    var annotation =
        UserServiceImpl.class
            .getMethod("balanceOperation", Long.class, String.class, Long.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    org.junit.jupiter.api.Assertions.assertTrue(annotation.readOnly());
    assertEquals(
        org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
        annotation.isolation());
  }

  /** 重置Legacy替身；无state表示fresh，否则建立真实状态的完整历史快照。 */
  private BalanceDebitRequestDTO installLegacyDebit(String entry, String state) {
    setUp();
    var request = debitRequest();
    if ("compat".equals(entry)) request.orderNo = request.payNo;
    when(userMapper.selectActiveForUpdate(7L)).thenReturn(activeUser(new BigDecimal("100.00")));
    when(userMapper.debitBalance(any(), any(), any())).thenReturn(1);
    legacyDebitLedger = null;
    legacyDebitAttempt = null;
    if (state != null) {
      legacyDebitLedger = appliedDebit(request.operationKey, new BigDecimal("90.00"));
      legacyDebitLedger.orderNo = request.orderNo;
      legacyDebitLedger.state = state;
      if ("REJECTED".equals(state)) {
        legacyDebitLedger.balanceBefore = new BigDecimal("5.00");
        legacyDebitLedger.balanceAfter = new BigDecimal("5.00");
      }
      legacyDebitAttempt = new BalanceDebitAttemptPO();
      legacyDebitAttempt.mode = "LEGACY";
      legacyDebitAttempt.userId = 7L;
      legacyDebitAttempt.payNo = request.payNo;
      legacyDebitAttempt.orderNo = request.orderNo;
      legacyDebitAttempt.operationKey = request.operationKey;
      legacyDebitAttempt.ledgerKey = request.operationKey;
      legacyDebitAttempt.amount = request.amount;
      legacyDebitAttempt.state = state;
      when(balanceLedgerMapper.selectByOperationKey(request.operationKey))
          .thenReturn(legacyDebitLedger);
      when(balanceLedgerMapper.findDebitCandidatesByPayNo(request.payNo))
          .thenReturn(java.util.List.of(legacyDebitLedger));
      when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(legacyDebitAttempt);
    }
    return request;
  }

  /** 两个Legacy入口共用生产扣款路径，不连接真实Redis或数据库。 */
  private ApiResponse<?> invokeLegacyDebit(String entry, BalanceDebitRequestDTO request) {
    if ("compat".equals(entry)) {
      var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
      compatible.paymentKey = request.operationKey;
      compatible.amount = request.amount;
      return userService.debit(7L, 7L, compatible);
    }
    return userService.debitInternal(7L, 7L, request);
  }

  /** 缺证停止时不得认领归属、改变金额、增账本或更新缓存。 */
  private void assertLegacyNoMoneyOrCacheWrite() {
    verify(balanceDebitAttemptMapper, org.mockito.Mockito.never()).insert(any());
    verify(userMapper, org.mockito.Mockito.never()).debitBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
    org.mockito.Mockito.verifyNoInteractions(valueOperations);
  }

  /** 每个坏关联字段都必须拒绝，负数与过滤掉的状态不能掩盖已退款事实。 */
  @Test
  void freshRefundRejectsMalformedLinkedCandidatesBeforeCredit() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      for (String mutation :
          java.util.List.of(
              "null-row",
              "null-key",
              "blank-key",
              "debit-key",
              "duplicate-key",
              "wrong-user",
              "null-user",
              "wrong-pay",
              "wrong-order",
              "alias-origin",
              "wrong-origin",
              "wrong-type",
              "alias-type",
              "wrong-state",
              "alias-state",
              "null-amount",
              "zero-amount",
              "negative-amount",
              "fraction-amount",
              "overflow-amount",
              "null-before",
              "negative-before",
              "fraction-before",
              "overflow-before",
              "null-after",
              "negative-after",
              "fraction-after",
              "overflow-after",
              "wrong-delta")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              request.amount = BigDecimal.ONE;
              var candidate = linkedRefund("prior-refund", "2.00");
              java.util.List<BalanceLedgerPO> candidates = java.util.List.of(candidate);
              switch (mutation) {
                case "null-row" -> candidates = java.util.Arrays.asList((BalanceLedgerPO) null);
                case "null-key" -> candidate.operationKey = null;
                case "blank-key" -> candidate.operationKey = "  ";
                case "debit-key" -> candidate.operationKey = "pay-1";
                case "duplicate-key" -> candidates = java.util.List.of(candidate, candidate);
                case "wrong-user" -> candidate.userId = 8L;
                case "null-user" -> candidate.userId = null;
                case "wrong-pay" -> candidate.payNo = "other-pay";
                case "wrong-order" -> candidate.orderNo = "other-order";
                case "alias-origin" -> candidate.originalOperationKey = "PAY-1";
                case "wrong-origin" -> candidate.originalOperationKey = "other-origin";
                case "wrong-type" -> candidate.operationType = "DEBIT";
                case "alias-type" -> candidate.operationType = "refund";
                case "wrong-state" -> candidate.state = "REJECTED";
                case "alias-state" -> candidate.state = "applied";
                case "null-amount" -> candidate.amount = null;
                case "zero-amount" -> candidate.amount = BigDecimal.ZERO;
                case "negative-amount" -> candidate.amount = new BigDecimal("-2.00");
                case "fraction-amount" -> candidate.amount = new BigDecimal("2.001");
                case "overflow-amount" -> candidate.amount = new BigDecimal("10000000000000000");
                case "null-before" -> candidate.balanceBefore = null;
                case "negative-before" -> candidate.balanceBefore = BigDecimal.ONE.negate();
                case "fraction-before" -> candidate.balanceBefore = new BigDecimal("90.001");
                case "overflow-before" ->
                    candidate.balanceBefore = new BigDecimal("10000000000000000");
                case "null-after" -> candidate.balanceAfter = null;
                case "negative-after" -> candidate.balanceAfter = BigDecimal.ONE.negate();
                case "fraction-after" -> candidate.balanceAfter = new BigDecimal("92.001");
                case "overflow-after" ->
                    candidate.balanceAfter = new BigDecimal("10000000000000000");
                case "wrong-delta" -> candidate.balanceAfter = new BigDecimal("93.00");
                default -> throw new AssertionError(mutation);
              }
              when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(candidates);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request),
                      mode + ":" + mutation);
              assertEquals(409, failure.getHttpStatus());
              assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 无集合和101条哨兵不得被当作零累计；不会因超额提前忽略后续坏行。 */
  @Test
  void freshRefundRejectsMissingOrTruncatedEvidenceAndChecksAllRows() {
    for (String mode : java.util.List.of("active-fresh", "disabled-fresh")) {
      var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String scenario : java.util.List.of("null-list", "101-rows", "excess-then-bad")) {
        assertions.add(
            () -> {
              var request = installRefundProofMode(mode);
              java.util.List<BalanceLedgerPO> candidates = null;
              if ("101-rows".equals(scenario)) {
                candidates = new java.util.ArrayList<>();
                for (int index = 0; index < 101; index++)
                  candidates.add(linkedRefund("prior-" + index, "0.01"));
              } else if ("excess-then-bad".equals(scenario)) {
                var invalid = linkedRefund("last-bad", "2.00");
                invalid.state = "REJECTED";
                candidates = java.util.List.of(linkedRefund("excess", "10.00"), invalid);
              }
              when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(candidates);
              var failure =
                  assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> userService.refundInternal(7L, 7L, request),
                      mode + ":" + scenario);
              assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
              verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
              verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
            });
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    }
  }

  /** 合法部分累计、尾零、100边界和超额仍按原协议，existing重放不读新集合。 */
  @Test
  void provenRefundCandidatesKeepPartialExactLimitAndReplay() {
    for (String scenario : java.util.List.of("empty", "partial", "100-rows", "exceeded")) {
      var request = installRefundProofMode("active-fresh");
      java.util.List<BalanceLedgerPO> candidates = java.util.List.of();
      if ("partial".equals(scenario)) {
        candidates =
            java.util.List.of(
                linkedRefund("prior-one", "2.000"), linkedRefund("prior-two", "3.00"));
        request.amount = new BigDecimal("5.000");
      } else if ("100-rows".equals(scenario)) {
        candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++)
          candidates.add(linkedRefund("prior-" + index, "0.010"));
        request.amount = new BigDecimal("9.00");
      } else if ("exceeded".equals(scenario)) {
        candidates = java.util.List.of(linkedRefund("prior-one", "10.00"));
      }
      when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(candidates);
      var paymentRows = new java.util.ArrayList<BalanceLedgerPO>();
      paymentRows.add(originalRefundDebit);
      paymentRows.addAll(candidates);
      when(balanceLedgerMapper.findRefundPaymentLedgerCandidates("pay-1")).thenReturn(paymentRows);
      if ("exceeded".equals(scenario)) {
        assertEquals(
            "BALANCE_REFUND_EXCEEDED",
            assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> userService.refundInternal(7L, 7L, request))
                .getCode());
        verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
      } else {
        userService.refundInternal(7L, 7L, request);
        verify(userMapper).creditBalance(eq(7L), eq(request.amount), any());
      }
    }
    for (String mode : java.util.List.of("active-fresh", "disabled-replay")) {
      var request = installRefundProofMode(mode);
      when(balanceLedgerMapper.selectByOperationKey("refund-1"))
          .thenReturn(appliedRefund("refund-1", new BigDecimal("100.00")));
      when(balanceLedgerMapper.findLinkedRefundCandidates(any()))
          .thenThrow(new AssertionError("existing replay must not query aggregate"));
      userService.refundInternal(7L, 7L, request);
      verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
      verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    }
  }

  /** 候选读取故障同对象传播，不能补空集合继续退款。 */
  @Test
  void refundCandidateReadFaultDoesNotBecomeZeroAggregate() {
    var request = installRefundProofMode("active-fresh");
    var unavailable =
        new org.springframework.dao.DataRetrievalFailureException("owned-candidates-read-failure");
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenThrow(unavailable);
    org.junit.jupiter.api.Assertions.assertSame(
        unavailable,
        assertThrows(RuntimeException.class, () -> userService.refundInternal(7L, 7L, request)));
    verify(userMapper, org.mockito.Mockito.never()).creditBalance(any(), any(), any());
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).insert(any());
  }

  /** 构造合法历史部分退款，不要求其快照等于当前钱包。 */
  private BalanceLedgerPO linkedRefund(String operationKey, String amount) {
    var candidate = appliedRefund(operationKey, new BigDecimal("92.00"));
    candidate.amount = new BigDecimal(amount);
    candidate.balanceBefore = new BigDecimal("90.00");
    candidate.balanceAfter = candidate.balanceBefore.add(candidate.amount);
    return candidate;
  }

  /** 合法冻结PO复制原本地确认tuple和原DEBIT快照，不以状态标签代替金额来源。 */
  private com.cloudmall.user.domain.po.BalanceTccDebitPO confirmedRefundFreeze(
      BalanceDebitAttemptPO attempt, BalanceLedgerPO original) {
    var freeze = new com.cloudmall.user.domain.po.BalanceTccDebitPO();
    freeze.userId = attempt.userId;
    freeze.payNo = attempt.payNo;
    freeze.orderNo = attempt.orderNo;
    freeze.operationKey = attempt.operationKey;
    freeze.xid = attempt.xid;
    freeze.branchId = attempt.branchId;
    freeze.state = "CONFIRMED";
    freeze.amount = original.amount;
    freeze.balanceBefore = original.balanceBefore;
    freeze.balanceAfterTry = original.balanceAfter;
    return freeze;
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
    when(balanceLedgerMapper.findRefundPaymentLedgerCandidates(originalRefundDebit.payNo))
        .thenReturn(java.util.List.of(originalRefundDebit));
    when(balanceLedgerMapper.findDebitCandidatesByPayNo(originalRefundDebit.payNo))
        .thenReturn(java.util.List.of(originalRefundDebit));
    when(balanceLedgerMapper.selectByOperationKey("refund-1"))
        .thenReturn(
            "disabled-replay".equals(mode)
                ? appliedRefund("refund-1", new BigDecimal("100.00"))
                : null);
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1")).thenReturn(java.util.List.of());
    when(userMapper.creditBalance(any(), any(), any())).thenReturn(1);
    var attempt = confirmedTccAttempt();
    attempt.payNo = request.payNo;
    attempt.orderNo = request.orderNo;
    attempt.ledgerKey = request.originalOperationKey;
    attempt.operationKey = "tcc-debit:" + request.payNo;
    when(balanceDebitAttemptMapper.selectByPayNo(request.payNo)).thenReturn(attempt);
    originalRefundAttempt = attempt;
    originalRefundFreeze = confirmedRefundFreeze(attempt, originalRefundDebit);
    when(balanceTccDebitMapper.selectByPayNo(request.payNo)).thenReturn(originalRefundFreeze);
    return request;
  }

  /** 固定错误码保持，源缺证不得查关联集合/attempt、写钱包或创建新退款。 */
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
    verify(balanceLedgerMapper, org.mockito.Mockito.never()).findLinkedRefundCandidates(any());
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
    ledger.id = 5L;
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
    ledger.balanceBefore = balanceAfter.subtract(ledger.amount);
    return ledger;
  }
}
