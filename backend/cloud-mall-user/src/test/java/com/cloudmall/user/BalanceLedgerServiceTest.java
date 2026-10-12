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

  /** 当前退款用例可改变的原扣款证明，正常字段完整且不连接真实库。 */
  private BalanceLedgerPO originalRefundDebit;

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
    when(balanceLedgerMapper.findLinkedRefundCandidates("pay-1"))
        .thenReturn(java.util.List.of(linkedRefund("prior-full", "10.00")));
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

  /** active无freeze查询，disabled健康复制/尾零及DAO异常渠道保持。 */
  @Test
  void disabledRefundFreezeProofKeepsHealthyMoneyActiveIsolationAndReadFailure() {
    var activeRequest = installRefundProofMode("active-fresh");
    userService.refundInternal(7L, 7L, activeRequest);
    org.mockito.Mockito.verifyNoInteractions(balanceTccDebitMapper);
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
