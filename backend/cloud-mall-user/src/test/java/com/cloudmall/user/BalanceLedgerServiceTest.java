package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.UserPO;
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
  private ValueOperations<String, String> valueOperations;

  @BeforeEach
  void setUp() {
    userService = new UserServiceImpl();
    userMapper = mock(UserMapper.class);
    balanceLedgerMapper = mock(BalanceLedgerMapper.class);
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    ReflectionTestUtils.setField(userService, "userMapper", userMapper);
    ReflectionTestUtils.setField(userService, "balanceLedgerMapper", balanceLedgerMapper);
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
    when(userMapper.selectActiveForUpdate(7L)).thenReturn(user);
    when(balanceLedgerMapper.selectByOperationKey("refund-1"))
        .thenReturn(null)
        .thenReturn(refund);
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
