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
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.BalanceTccDebitServiceImpl;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** TCC 入口参数和开关的无数据库单元测试。 */
class BalanceTccDebitServiceTest {
  private UserMapper userMapper;
  private BalanceDebitAttemptMapper attemptMapper;
  private BalanceLedgerMapper ledgerMapper;
  private BalanceTccDebitMapper tccMapper;
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
