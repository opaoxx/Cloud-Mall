package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserTccController;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccHttpService;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import com.cloudmall.user.service.impl.UserTccStateQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 原资金状态查询金额门回归，坏金额不能变成500或合法终态。 */
class UserTccStateAmountValidationTest {
  /** 原接收事实替身。 */
  private UserTccReceiveMapper receives;

  /** 原资金归属替身。 */
  private BalanceDebitAttemptMapper attempts;

  /** 原冻结事实替身。 */
  private BalanceTccDebitMapper debits;

  /** 原扣款账本替身。 */
  private BalanceLedgerMapper ledgers;

  /** 原分支Fence替身。 */
  private TccFenceLogMapper fences;

  /** 实际只读原查询器。 */
  private UserTccStateQuery query;

  /** 准备只读查询依赖。 */
  @BeforeEach
  void setUp() {
    receives = mock(UserTccReceiveMapper.class);
    attempts = mock(BalanceDebitAttemptMapper.class);
    debits = mock(BalanceTccDebitMapper.class);
    ledgers = mock(BalanceLedgerMapper.class);
    fences = mock(TccFenceLogMapper.class);
    query = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 原来源分别坏金额时必须是固定409，而非空指针或终态。 */
  @Test
  void receiveAmountMustBePositiveLosslessMoney() {
    rejectSource("receive");
  }

  /** 归属金额NULL不能进入compareTo。 */
  @Test
  void attemptAmountMustBePositiveLosslessMoney() {
    rejectSource("attempt");
  }

  /** 冻结金额NULL不能进入compareTo。 */
  @Test
  void debitAmountMustBePositiveLosslessMoney() {
    rejectSource("debit");
  }

  /** 已确认账本金额NULL不能进入compareTo。 */
  @Test
  void ledgerAmountMustBePositiveLosslessMoney() {
    rejectSource("ledger");
  }

  /** 四来源相等不能证明零、负数、超精度或超容量金额合法。 */
  @Test
  void matchingInvalidAmountsNeverProveAnyState() {
    for (String state : List.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED")) {
      for (BigDecimal amount : invalidAmounts()) {
        if (amount == null) continue;
        install(state, amount, amount, amount, amount);
        assertConflict();
      }
    }
  }

  /** 两位小数最小值、无损零尾数和18.2上界均保留合法状态；终态无Fence语义不变。 */
  @Test
  void losslessValidAmountsAndExpiredTerminalFenceRemainValid() {
    for (String state : List.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED")) {
      for (String raw : List.of("0.01", "1.000", "9999999999999999.99")) {
        BigDecimal amount = new BigDecimal(raw);
        install(state, amount, amount, amount, amount);
        assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
        if (List.of("CONFIRMED", "CANCELLED").contains(state)) {
          when(fences.findByBranch("xid-query-1", 12L)).thenReturn(null);
          assertEquals(state, query.read(7L, "tcc-debit:pay-query-1", "xid-query-1").state);
        }
      }
    }
  }

  /** 原HTTP桥消费实际查询器，坏金额为固定409而不是globalHandler的500。 */
  @Test
  void originalStateHttpRejectsNullMoneyWithFixedConflict() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var store = mock(UserTccReceiveStore.class);
    var users = mock(UserMapper.class);
    var bridge = new UserTccHttpService(resource, store, query, users, "ops-secret", true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTccController(bridge))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    for (String source : List.of("receive", "attempt", "debit", "ledger")) {
      BigDecimal valid = new BigDecimal("10.00");
      install(
          "CONFIRMED",
          "receive".equals(source) ? null : valid,
          "attempt".equals(source) ? null : valid,
          "debit".equals(source) ? null : valid,
          "ledger".equals(source) ? null : valid);
      mvc.perform(
              get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay-query-1")
                  .header("X-User-Id", "7")
                  .header("X-Internal-Service", "cloud-mall-pay")
                  .header("X-Internal-Callback-Token", "ops-secret")
                  .param("xid", "xid-query-1"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
    }
    verifyNoInteractions(resource, store, users);
  }

  /** 金额替身覆盖NULL、非正、不可无损两位及容量溢出，不依赖数据库舍入。 */
  private static BigDecimal[] invalidAmounts() {
    return new BigDecimal[] {
      null,
      BigDecimal.ZERO,
      new BigDecimal("-10.00"),
      new BigDecimal("10.001"),
      new BigDecimal("10000000000000000.00")
    };
  }

  /** 单来源坏值，其余来源保留可信正金额。 */
  private void rejectSource(String source) {
    for (BigDecimal amount : invalidAmounts()) {
      BigDecimal valid = new BigDecimal("10.00");
      install(
          "CONFIRMED",
          "receive".equals(source) ? amount : valid,
          "attempt".equals(source) ? amount : valid,
          "debit".equals(source) ? amount : valid,
          "ledger".equals(source) ? amount : valid);
      assertConflict();
    }
  }

  /** 原固定冲突码拒绝返回资金状态，NULL绝不成为意外500。 */
  private void assertConflict() {
    BizException failure =
        assertThrows(
            BizException.class, () -> query.read(7L, "tcc-debit:pay-query-1", "xid-query-1"));
    assertEquals(409, failure.getHttpStatus());
    assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
  }

  /** 完整原元组及状态，只有金额由用例控制，不屏蔽旧身份/Fence检查。 */
  private void install(
      String state,
      BigDecimal receiveAmount,
      BigDecimal attemptAmount,
      BigDecimal debitAmount,
      BigDecimal ledgerAmount) {
    UserTccReceivePO receive = receive(12L);
    receive.amount = receiveAmount;
    BalanceDebitAttemptPO attempt = attempt(state, 12L);
    attempt.amount = attemptAmount;
    BalanceTccDebitPO debit = debit(state, 12L);
    debit.amount = debitAmount;
    debit.balanceBefore = debitAmount;
    debit.balanceAfterTry = BigDecimal.ZERO;
    BalanceLedgerPO ledger = ledger();
    ledger.amount = ledgerAmount;
    ledger.balanceBefore = ledgerAmount;
    ledger.balanceAfter = BigDecimal.ZERO;
    when(receives.findByOperationKey(receive.operationKey)).thenReturn(receive);
    when(attempts.selectByPayNo(receive.payNo))
        .thenReturn("UNKNOWN".equals(state) ? null : attempt);
    when(debits.selectByPayNo(receive.payNo)).thenReturn("UNKNOWN".equals(state) ? null : debit);
    when(ledgers.selectByOperationKey(receive.ledgerKey))
        .thenReturn("CONFIRMED".equals(state) ? ledger : null);
    when(ledgers.selectDebitByPayNo(receive.payNo))
        .thenReturn("CONFIRMED".equals(state) ? ledger : null);
    when(ledgers.findDebitCandidatesByPayNo(receive.payNo))
        .thenReturn("CONFIRMED".equals(state) ? java.util.List.of(ledger) : java.util.List.of());
    when(fences.findByBranch(receive.xid, 12L))
        .thenReturn(fence("TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3));
  }

  /** 原接收载荷合法基线。 */
  private UserTccReceivePO receive(Long branchId) {
    UserTccReceivePO receive = new UserTccReceivePO();
    receive.userId = 7L;
    receive.payNo = "pay-query-1";
    receive.orderNo = "order-query-1";
    receive.amount = new BigDecimal("10.00");
    receive.xid = "xid-query-1";
    receive.branchId = branchId;
    receive.operationKey = "tcc-debit:pay-query-1";
    receive.ledgerKey = "pay-query-1";
    receive.state = "CLAIMED";
    return receive;
  }

  /** 原TCC归属合法基线。 */
  private BalanceDebitAttemptPO attempt(String state, Long branchId) {
    BalanceDebitAttemptPO attempt = new BalanceDebitAttemptPO();
    attempt.mode = "TCC";
    attempt.userId = 7L;
    attempt.payNo = "pay-query-1";
    attempt.orderNo = "order-query-1";
    attempt.amount = new BigDecimal("10.00");
    attempt.xid = "xid-query-1";
    attempt.branchId = branchId;
    attempt.operationKey = "tcc-debit:pay-query-1";
    attempt.ledgerKey = "pay-query-1";
    attempt.state = state;
    return attempt;
  }

  /** 原冻结载荷合法基线。 */
  private BalanceTccDebitPO debit(String state, Long branchId) {
    BalanceTccDebitPO debit = new BalanceTccDebitPO();
    debit.userId = 7L;
    debit.payNo = "pay-query-1";
    debit.orderNo = "order-query-1";
    debit.amount = new BigDecimal("10.00");
    debit.xid = "xid-query-1";
    debit.branchId = branchId;
    debit.operationKey = "tcc-debit:pay-query-1";
    debit.state = state;
    return debit;
  }

  /** 原扣款账本合法基线。 */
  private BalanceLedgerPO ledger() {
    BalanceLedgerPO ledger = new BalanceLedgerPO();
    ledger.userId = 7L;
    ledger.payNo = "pay-query-1";
    ledger.orderNo = "order-query-1";
    ledger.amount = new BigDecimal("10.00");
    ledger.operationKey = "pay-query-1";
    ledger.operationType = "DEBIT";
    ledger.state = "APPLIED";
    return ledger;
  }

  /** 原官方Fence状态基线。 */
  private TccFenceLogPO fence(int status) {
    TccFenceLogPO fence = new TccFenceLogPO();
    fence.xid = "xid-query-1";
    fence.branchId = 12L;
    fence.actionName = BalanceTccDebitLocalResource.ACTION_NAME;
    fence.status = status;
    return fence;
  }
}
