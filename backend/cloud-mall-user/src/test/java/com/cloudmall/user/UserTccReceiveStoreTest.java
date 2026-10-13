package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 接收重放和原分支归属保护；真实回滚由owned SQL单独验证。 */
class UserTccReceiveStoreTest {
  /** 原接收Mapper替身。 */
  private UserTccReceiveMapper mapper;

  /** 实际元数据事务服务。 */
  private UserTccReceiveStore store;

  /** 准备独立替身，不连接业务库或RM。 */
  @BeforeEach
  void setup() {
    mapper = mock(UserTccReceiveMapper.class);
    store = new UserTccReceiveStore(mapper);
  }

  /** 遗留NULL金额不能在重复claim时变成空指针500。 */
  @Test
  void replayWithNullStoredAmountIsFixedConflict() {
    UserTccReceivePO original = stored(null);
    when(mapper.lockByPayNo("pay1")).thenReturn(original);
    assertConflict(() -> store.claim(7L, request(), 1));
    verify(mapper).claim(any());
    verify(mapper).lockByPayNo("pay1");
  }

  /** 只有首次INSERT winner取得派发权，数值等值尾零不误拒重放。 */
  @Test
  void firstClaimAndEquivalentReplayKeepOneWinner() {
    when(mapper.claim(any())).thenReturn(1, 0);
    when(mapper.lockByPayNo("pay1")).thenReturn(stored(new BigDecimal("10.000")));
    assertTrue(store.claim(7L, request(), 1));
    assertFalse(store.claim(7L, request(), 1));
  }

  /** 原载荷或金额不一致沿已有幂等冲突，不改金额或用户。 */
  @Test
  void existingPayloadConflictsRemainRejected() {
    for (String variant : new String[] {"amount", "user", "pay", "operation"}) {
      UserTccReceivePO original = stored(new BigDecimal("10.00"));
      switch (variant) {
        case "amount" -> original.amount = new BigDecimal("11.00");
        case "user" -> original.userId = 8L;
        case "pay" -> original.payNo = "PAY1";
        case "operation" -> original.operationKey = "other";
        default -> throw new AssertionError(variant);
      }
      when(mapper.lockByPayNo("pay1")).thenReturn(original);
      assertConflict(() -> store.claim(7L, request(), 1));
    }
  }

  /** CI别名读取同一原XID和branch也不能确认另一支付号。 */
  @Test
  void bindingAliasPayNoMustBeConflict() {
    UserTccReceivePO original = stored(new BigDecimal("10.00"));
    original.branchId = 11L;
    when(mapper.lockByPayNo("PAY1")).thenReturn(original);
    assertConflict(() -> store.bindBranch("PAY1", "private-xid", 11L));
  }

  /** 分支必须为正，非法观察不应触达Mapper。 */
  @Test
  void nonpositiveBranchDoesNotReachMapper() {
    for (Long branchId : new Long[] {0L, -1L}) {
      UserTccReceivePO original = stored(new BigDecimal("10.00"));
      original.branchId = branchId;
      when(mapper.lockByPayNo("pay1")).thenReturn(original);
      assertConflict(() -> store.bindBranch("pay1", "private-xid", branchId));
      verifyNoInteractions(mapper);
    }
  }

  /** null分支noop保留，正分支readback匹配仍成功。 */
  @Test
  void nullBranchAndOriginalPositiveBindingRemainValid() {
    store.bindBranch("pay1", "private-xid", null);
    verifyNoInteractions(mapper);
    UserTccReceivePO original = stored(new BigDecimal("10.00"));
    original.branchId = 11L;
    when(mapper.lockByPayNo("pay1")).thenReturn(original);
    store.bindBranch("pay1", "private-xid", 11L);
    verify(mapper).bindBranch(eq("pay1"), eq("private-xid"), eq(11L), any());
  }

  /** 认领及绑定写后的精确多结果固定409，不能返回派发权或认定元数据成功。 */
  @Test
  void lockedReceiptMultipleResultsKeepBusinessConflict() {
    for (String action : new String[] {"fresh", "replay", "bind"}) {
      for (boolean wrapped : new boolean[] {false, true}) {
        setup();
        when(mapper.claim(any())).thenReturn("fresh".equals(action) ? 1 : 0);
        var duplicate =
            new org.apache.ibatis.exceptions.TooManyResultsException("receipt-lock-duplicate");
        RuntimeException failure =
            wrapped ? new org.mybatis.spring.MyBatisSystemException(duplicate) : duplicate;
        doThrow(failure).when(mapper).lockByPayNo("pay1");
        var rejected = assertThrows(BizException.class, () -> invokeReceiptStoreAction(action));
        assertEquals("IDEMPOTENCY_CONFLICT", rejected.getCode());
        assertEquals("TCC 接收载荷不一致", rejected.getMessage());
        assertEquals(409, rejected.getHttpStatus());
        var ordered = inOrder(mapper);
        if ("bind".equals(action))
          ordered.verify(mapper).bindBranch(eq("pay1"), eq("private-xid"), eq(11L), any());
        else ordered.verify(mapper).claim(any());
        ordered.verify(mapper).lockByPayNo("pay1");
        ordered.verifyNoMoreInteractions();
      }
    }
  }

  /** 其它异常原对象，前置branch guard与合法独立事务/派发权语义保持。 */
  @Test
  void receiptLockClassificationKeepsOtherFaultsAndBranchGuards() {
    for (String action : new String[] {"fresh", "replay", "bind"}) {
      for (String shape :
          new String[] {
            "resource",
            "sql-wrapper",
            "nested-too-many",
            "other-wrapper",
            "runtime-cause",
            "message-only"
          }) {
        setup();
        when(mapper.claim(any())).thenReturn("fresh".equals(action) ? 1 : 0);
        RuntimeException failure =
            switch (shape) {
              case "resource" ->
                  new org.springframework.dao.DataAccessResourceFailureException(
                      "receipt-lock-jdbc-fault");
              case "sql-wrapper" ->
                  new org.mybatis.spring.MyBatisSystemException(
                      new java.sql.SQLException("receipt-lock-sql-fault"));
              case "nested-too-many" ->
                  new org.mybatis.spring.MyBatisSystemException(
                      new org.apache.ibatis.exceptions.PersistenceException(
                          "outer",
                          new org.apache.ibatis.exceptions.TooManyResultsException("nested")));
              case "other-wrapper" ->
                  new org.apache.ibatis.exceptions.PersistenceException(
                      "outer", new org.apache.ibatis.exceptions.TooManyResultsException("nested"));
              case "runtime-cause" ->
                  new IllegalArgumentException(
                      "outer", new org.apache.ibatis.exceptions.TooManyResultsException("nested"));
              default -> new IllegalArgumentException("TooManyResultsException is only text");
            };
        doThrow(failure).when(mapper).lockByPayNo("pay1");
        assertSame(
            failure, assertThrows(RuntimeException.class, () -> invokeReceiptStoreAction(action)));
        verify(mapper).lockByPayNo("pay1");
      }
    }
    setup();
    doThrow(new org.apache.ibatis.exceptions.TooManyResultsException("lock-must-not-run"))
        .when(mapper)
        .lockByPayNo("pay1");
    store.bindBranch("pay1", "private-xid", null);
    assertConflict(() -> store.bindBranch("pay1", "private-xid", 0L));
    verifyNoInteractions(mapper);
  }

  /** 用原公开事务方法执行三种动作，回滚由owned真实SQL证明。 */
  private void invokeReceiptStoreAction(String action) {
    if ("bind".equals(action)) store.bindBranch("pay1", "private-xid", 11L);
    else store.claim(7L, request(), 1);
  }

  /** 仅首次winner在非启用状态回滚拒绝，disabled/null/坏状态已有重放仍无派发。 */
  @Test
  void observedDisabledStatusRejectsOnlyFreshWinner() {
    for (Integer status : java.util.Arrays.asList(0, -1, null, 2)) {
      setup();
      when(mapper.claim(any())).thenReturn(1);
      when(mapper.lockByPayNo("pay1")).thenReturn(stored(new BigDecimal("10.00")));
      var failure = assertThrows(BizException.class, () -> store.claim(7L, request(), status));
      assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
      assertEquals("用户不可用", failure.getMessage());
      assertEquals(409, failure.getHttpStatus());
      var ordered = inOrder(mapper);
      ordered.verify(mapper).claim(any());
      ordered.verify(mapper).lockByPayNo("pay1");
      ordered.verifyNoMoreInteractions();
      setup();
      when(mapper.claim(any())).thenReturn(0);
      when(mapper.lockByPayNo("pay1")).thenReturn(stored(new BigDecimal("10.00")));
      assertFalse(store.claim(7L, request(), status));
      verify(mapper).claim(any());
      verify(mapper).lockByPayNo("pay1");
    }
  }

  /** 原载荷错误先于status，启用winner/replay与null来源错误保持。 */
  @Test
  void observedStatusGateKeepsPayloadPriorityAndEnabledDispatch() {
    for (String variant : new String[] {"amount", "user", "missing"}) {
      setup();
      when(mapper.claim(any())).thenReturn(1);
      UserTccReceivePO original = stored(new BigDecimal("10.00"));
      if ("amount".equals(variant)) original.amount = null;
      if ("user".equals(variant)) original.userId = 8L;
      when(mapper.lockByPayNo("pay1")).thenReturn("missing".equals(variant) ? null : original);
      assertEquals(
          "TCC 接收载荷不一致",
          assertThrows(BizException.class, () -> store.claim(7L, request(), 0)).getMessage());
    }
    setup();
    when(mapper.claim(any())).thenReturn(1, 0);
    when(mapper.lockByPayNo("pay1")).thenReturn(stored(new BigDecimal("10.000")));
    assertTrue(store.claim(7L, request(), 1));
    assertFalse(store.claim(7L, request(), 1));
  }

  /** 固定原409不授权第二次Try或错误分支确认。 */
  private static void assertConflict(Runnable action) {
    BizException failure = assertThrows(BizException.class, action::run);
    assertEquals(409, failure.getHttpStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
  }

  /** 原输入在HTTP层已校验，此处保持真实canonical DTO。 */
  private static UserTccTryDTO request() {
    UserTccTryDTO request = new UserTccTryDTO();
    request.payNo = "pay1";
    request.orderNo = "order1";
    request.amount = new BigDecimal("10.00");
    request.xid = "private-xid";
    request.operationKey = "tcc-debit:pay1";
    request.ledgerKey = "pay1";
    return request;
  }

  /** 原接收载荷只由目标用例改变金额/身份/分支，不模拟余额或资金写。 */
  private static UserTccReceivePO stored(BigDecimal amount) {
    UserTccReceivePO original = new UserTccReceivePO();
    original.payNo = "pay1";
    original.orderNo = "order1";
    original.userId = 7L;
    original.amount = amount;
    original.xid = "private-xid";
    original.operationKey = "tcc-debit:pay1";
    original.ledgerKey = "pay1";
    original.state = "CLAIMED";
    return original;
  }
}
