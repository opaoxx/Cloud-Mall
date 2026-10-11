package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.BalanceTccDebitServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** TCC 入口参数和开关的无数据库单元测试。 */
class BalanceTccDebitServiceTest {
  /** 原用户行锁和余额写替身。 */
  private UserMapper userMapper;

  /** 原资金归属读取和状态写替身。 */
  private BalanceDebitAttemptMapper attemptMapper;

  /** 原扣款账本读写替身。 */
  private BalanceLedgerMapper ledgerMapper;

  /** 原冻结载荷和状态写替身。 */
  private BalanceTccDebitMapper tccMapper;

  /** 实际本地资金服务，不连接业务库。 */
  private BalanceTccDebitServiceImpl service;

  @BeforeEach
  void setUp() {
    userMapper = mock(UserMapper.class);
    attemptMapper = mock(BalanceDebitAttemptMapper.class);
    ledgerMapper = mock(BalanceLedgerMapper.class);
    tccMapper = mock(BalanceTccDebitMapper.class);
    service = new BalanceTccDebitServiceImpl(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void disabledTryRejectsBeforeAnyMapperCall() {
    BizException failure = assertThrows(BizException.class, () -> service.tryDebit(request()));
    assertEquals("PAY_DEBIT_MODE_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void malformedAmountAndKeysAreRejectedBeforeFundsAccess() {
    BalanceTccDebitTryDTO invalidAmount = request();
    invalidAmount.amount = new BigDecimal("1.001");
    assertEquals(
        400,
        assertThrows(BizException.class, () -> service.tryDebit(invalidAmount)).getHttpStatus());
    BalanceTccDebitTryDTO invalidKey = request();
    invalidKey.ledgerKey = "different-ledger-key";
    assertEquals(
        400, assertThrows(BizException.class, () -> service.tryDebit(invalidKey)).getHttpStatus());
    verifyNoInteractions(userMapper, attemptMapper, ledgerMapper, tccMapper);
  }

  @Test
  void failedConditionalFreezeCannotBeReportedAsSuccessfulTry() {
    ReflectionTestUtils.setField(service, "enabled", true);
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = new BigDecimal("100.00");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    when(attemptMapper.insert(any())).thenReturn(1);
    assertThrows(IllegalStateException.class, () -> service.tryDebit(request()));
    verify(tccMapper, never()).insert(any());
  }

  /** 缺归属金额不能在三个资金入口变为NPE或写余额。 */
  @Test
  void nullAttemptAmountIsConflictBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      installFunds("TRYING", "attempt");
      assertPersistedAmountConflict(operation, "IDEMPOTENCY_CONFLICT");
    }
  }

  /** 缺冻结金额沿原UNKNOWN，而非意外500。 */
  @Test
  void nullFrozenAmountIsUnknownBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm", "cancel")) {
      installFunds("TRYING", "debit");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** 已确认缺账本金额不能重放为成功，也不能变NPE。 */
  @Test
  void nullConfirmedLedgerAmountIsUnknownBeforeAnyFundsWrite() {
    for (String operation : List.of("try", "confirm")) {
      installFunds("CONFIRMED", "ledger");
      assertPersistedAmountConflict(operation, "BALANCE_TCC_UNKNOWN");
    }
  }

  /** 完整原元组仅将目标金额置NULL，不使用资金写替身掩盖错误。 */
  private void installFunds(String state, String missing) {
    ReflectionTestUtils.setField(service, "enabled", true);
    BalanceTccDebitTryDTO original = request();
    UserPO user = new UserPO();
    user.id = 7L;
    user.status = 1;
    user.balance = new BigDecimal("100.00");
    when(userMapper.selectByIdForUpdate(7L)).thenReturn(user);
    BalanceDebitAttemptPO owner = new BalanceDebitAttemptPO();
    owner.mode = "TCC";
    owner.userId = original.userId;
    owner.payNo = original.payNo;
    owner.orderNo = original.orderNo;
    owner.xid = original.xid;
    owner.branchId = original.branchId;
    owner.operationKey = original.operationKey;
    owner.ledgerKey = original.ledgerKey;
    owner.amount = "attempt".equals(missing) ? null : original.amount;
    owner.state = state;
    when(attemptMapper.selectByPayNo(original.payNo)).thenReturn(owner);
    when(attemptMapper.selectByPayNoForUpdate(original.payNo)).thenReturn(owner);
    BalanceTccDebitPO frozen = new BalanceTccDebitPO();
    frozen.userId = original.userId;
    frozen.payNo = original.payNo;
    frozen.orderNo = original.orderNo;
    frozen.xid = original.xid;
    frozen.branchId = original.branchId;
    frozen.operationKey = original.operationKey;
    frozen.amount = "debit".equals(missing) ? null : original.amount;
    frozen.state = state;
    frozen.balanceBefore = new BigDecimal("100.00");
    frozen.balanceAfterTry = new BigDecimal("90.00");
    when(tccMapper.selectByPayNo(original.payNo)).thenReturn(frozen);
    BalanceLedgerPO applied = new BalanceLedgerPO();
    applied.userId = original.userId;
    applied.payNo = original.payNo;
    applied.orderNo = original.orderNo;
    applied.operationKey = original.ledgerKey;
    applied.operationType = "DEBIT";
    applied.state = "APPLIED";
    applied.amount = "ledger".equals(missing) ? null : original.amount;
    when(ledgerMapper.selectByOperationKey(original.ledgerKey)).thenReturn(applied);
  }

  /** 固定409及原code，所有金额/状态写Mapper都不得调用。 */
  private void assertPersistedAmountConflict(String operation, String code) {
    BalanceTccDebitTryDTO original = request();
    BalanceTccDebitFinalizeDTO completion = new BalanceTccDebitFinalizeDTO();
    completion.userId = original.userId;
    completion.payNo = original.payNo;
    completion.orderNo = original.orderNo;
    completion.amount = original.amount;
    completion.xid = original.xid;
    completion.branchId = original.branchId;
    completion.operationKey = original.operationKey;
    completion.ledgerKey = original.ledgerKey;
    BizException failure =
        assertThrows(
            BizException.class,
            () -> {
              switch (operation) {
                case "try" -> service.tryDebit(original);
                case "confirm" -> service.confirmDebit(completion);
                case "cancel" -> service.cancelDebit(completion);
                default -> throw new AssertionError(operation);
              }
            });
    assertEquals(409, failure.getHttpStatus());
    assertEquals(code, failure.getCode());
    verify(userMapper, never()).freezeBalance(any(), any(), any());
    verify(userMapper, never()).clearFrozenBalance(any(), any(), any());
    verify(userMapper, never()).releaseFrozenBalance(any(), any(), any());
    verify(ledgerMapper, never()).insert(any());
    verify(attemptMapper, never()).transitionState(any(), any(), any(), any());
    verify(tccMapper, never()).insert(any());
    verify(tccMapper, never()).confirmTrying(any(), any());
    verify(tccMapper, never()).cancelTrying(any(), any());
  }

  private BalanceTccDebitTryDTO request() {
    BalanceTccDebitTryDTO request = new BalanceTccDebitTryDTO();
    request.userId = 7L;
    request.payNo = "pay-unit-1";
    request.orderNo = "order-unit-1";
    request.operationKey = "tcc-debit:pay-unit-1";
    request.ledgerKey = "pay-unit-1";
    request.xid = "xid-unit-1";
    request.branchId = 1L;
    request.amount = new BigDecimal("10.00");
    return request;
  }
}
