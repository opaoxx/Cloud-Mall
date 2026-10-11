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
    assertConflict(() -> store.claim(7L, request()));
    verify(mapper).claim(any());
    verify(mapper).lockByPayNo("pay1");
  }

  /** 只有首次INSERT winner取得派发权，数值等值尾零不误拒重放。 */
  @Test
  void firstClaimAndEquivalentReplayKeepOneWinner() {
    when(mapper.claim(any())).thenReturn(1, 0);
    when(mapper.lockByPayNo("pay1")).thenReturn(stored(new BigDecimal("10.000")));
    assertTrue(store.claim(7L, request()));
    assertFalse(store.claim(7L, request()));
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
      assertConflict(() -> store.claim(7L, request()));
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
