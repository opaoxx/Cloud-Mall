package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.service.impl.PayTccDispatchPersistence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 派发独立本地事务的状态和 CAS 边界测试。 */
class PayTccDispatchPersistenceTest {
  /** 派发事实 Mapper。 */
  private PayTccDispatchMapper dispatchMapper;

  /** 原支付事实 Mapper。 */
  private PayRecordMapper payRecordMapper;

  /** 主动取消 gate Mapper。 */
  private PayTccCancelMapper cancelMapper;

  /** 被测独立事务边界。 */
  private PayTccDispatchPersistence persistence;

  /** 准备持久化边界依赖。 */
  @BeforeEach
  void setUp() {
    dispatchMapper = mock(PayTccDispatchMapper.class);
    payRecordMapper = mock(PayRecordMapper.class);
    cancelMapper = mock(PayTccCancelMapper.class);
    when(cancelMapper.selectForUpdate(any())).thenReturn(null);
    persistence = new PayTccDispatchPersistence(dispatchMapper, payRecordMapper, cancelMapper);
  }

  /** 已确认终态不能被 UNKNOWN 或 TRYING 倒退。 */
  @Test
  void confirmedTerminalCannotRegress() {
    PayTccDispatchPO dispatch = dispatch("CONFIRMED", 7L);
    when(dispatchMapper.selectForUpdate(dispatch.payNo)).thenReturn(dispatch);

    assertFalse(
        persistence.mirror(
            dispatch, "CONFIRMED", "TRY_UNKNOWN", null, "late", LocalDateTime.now()));
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
  }

  /** 相反终态或 User 分支变更必须明确返回冲突。 */
  @Test
  void oppositeTerminalAndBranchChangeAreConflicts() {
    PayTccDispatchPO confirmed = dispatch("CONFIRMED", 7L);
    when(dispatchMapper.selectForUpdate(confirmed.payNo)).thenReturn(confirmed);
    assertThrows(
        BizException.class,
        () ->
            persistence.mirror(confirmed, "CONFIRMED", "CANCELLED", 7L, null, LocalDateTime.now()));

    PayTccDispatchPO trying = dispatch("TRY_ACCEPTED", 7L);
    when(dispatchMapper.selectForUpdate(trying.payNo)).thenReturn(trying);
    assertThrows(
        BizException.class,
        () ->
            persistence.mirror(trying, "TRY_ACCEPTED", "CONFIRMED", 8L, null, LocalDateTime.now()));
  }

  /** TRY_ACCEPTED 的未知结果不能覆盖已经观察到的受理状态。 */
  @Test
  void unknownDoesNotOverwriteTryAccepted() {
    PayTccDispatchPO dispatch = dispatch("TRY_ACCEPTED", 7L);
    when(dispatchMapper.selectForUpdate(dispatch.payNo)).thenReturn(dispatch);

    assertFalse(
        persistence.mirror(
            dispatch, "TRY_ACCEPTED", "TRY_UNKNOWN", null, "late unknown", LocalDateTime.now()));
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
  }

  /** GET 镜像不能把尚未发出 POST 的 PREPARED 改成未知状态。 */
  @Test
  void preparedCannotBeConsumedByGetMirror() {
    PayTccDispatchPO dispatch = dispatch("PREPARED", null);
    when(dispatchMapper.selectForUpdate(dispatch.payNo)).thenReturn(dispatch);

    assertFalse(
        persistence.mirror(
            dispatch, "PREPARED", "TRY_UNKNOWN", null, "not posted", LocalDateTime.now()));
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
  }

  /** 原支付改为 LEGACY 或非待支付后不得执行 POST_ATTEMPTED CAS。 */
  @Test
  void changedPaymentModeOrStatusBlocksPostAttempt() {
    PayTccDispatchPO dispatch = dispatch("PREPARED", null);
    PayRecordPO payment = payment("LEGACY", "PENDING");
    when(payRecordMapper.selectForUpdate(dispatch.payNo)).thenReturn(List.of(payment));

    assertFalse(persistence.markPostAttempted(dispatch, LocalDateTime.now()));
    verify(dispatchMapper, never()).markPostAttempted(any(), any(), any());

    payment.debitMode = "TCC";
    payment.status = "SUCCESS";
    assertFalse(persistence.markPostAttempted(dispatch, LocalDateTime.now()));
    verify(dispatchMapper, never()).markPostAttempted(any(), any(), any());
  }

  /** 原支付载荷不一致时不得执行 POST_ATTEMPTED CAS。 */
  @Test
  void changedPaymentPayloadBlocksPostAttempt() {
    PayTccDispatchPO dispatch = dispatch("PREPARED", null);
    PayRecordPO payment = payment("TCC", "PENDING");
    payment.amount = new BigDecimal("11.00");
    when(payRecordMapper.selectForUpdate(dispatch.payNo)).thenReturn(List.of(payment));

    assertFalse(persistence.markPostAttempted(dispatch, LocalDateTime.now()));
    verify(dispatchMapper, never()).markPostAttempted(any(), any(), any());
  }

  /** CAS 影响行数为零时返回 false，调用方不得发送 POST。 */
  @Test
  void zeroRowPostAttemptCasReturnsFalse() {
    PayTccDispatchPO dispatch = dispatch("PREPARED", null);
    when(payRecordMapper.selectForUpdate(dispatch.payNo))
        .thenReturn(List.of(payment("TCC", "PENDING")));
    when(dispatchMapper.markPostAttempted(any(), any(), any())).thenReturn(0);

    assertFalse(persistence.markPostAttempted(dispatch, LocalDateTime.now()));
  }

  /** 捕获实际镜像参数，长Unicode诊断被限制但当前状态/分支/更新时间不变。 */
  @Test
  void mirrorBoundsErrorAndPreservesCurrentStateCas() {
    var current = dispatch("POST_ATTEMPTED", 7L);
    when(dispatchMapper.selectForUpdate(current.payNo)).thenReturn(current);
    when(dispatchMapper.mirrorState(any(), any(), any(), any(), any(), any())).thenReturn(1);
    var updatedAt = LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789000000);
    org.junit.jupiter.api.Assertions.assertTrue(
        persistence.mirror(current, "stale-state", "TRY_UNKNOWN", 7L, "😀".repeat(513), updatedAt));
    var error = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(dispatchMapper)
        .mirrorState(
            org.mockito.ArgumentMatchers.eq(current.payNo),
            org.mockito.ArgumentMatchers.eq("POST_ATTEMPTED"),
            org.mockito.ArgumentMatchers.eq("TRY_UNKNOWN"),
            org.mockito.ArgumentMatchers.eq(7L),
            error.capture(),
            org.mockito.ArgumentMatchers.same(updatedAt));
    org.junit.jupiter.api.Assertions.assertEquals("😀".repeat(512), error.getValue());
    org.mockito.Mockito.verifyNoInteractions(payRecordMapper, cancelMapper);
  }

  /** 锁定返回对象的错误/NULL原键必须在分支、终态或CAS前冲突，缺失保404。 */
  @Test
  void mirrorRejectsAlienRowBeforeBranchStateAndSql() {
    for (String state :
        List.of(
            "CONFIRMED", "CANCELLED", "PREPARED", "POST_ATTEMPTED", "TRY_UNKNOWN", "TRY_ACCEPTED"))
      for (String mutation : List.of("foreign", "case", "space", "null")) {
        var row = dispatch(state, 7L);
        row.payNo =
            switch (mutation) {
              case "foreign" -> "other-pay";
              case "case" -> "PAY-PERSISTENCE-1";
              case "space" -> "pay-persistence-1 ";
              default -> null;
            };
        when(dispatchMapper.selectForUpdate("pay-persistence-1")).thenReturn(row);
        var before =
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row));
        for (Long branch : List.of(0L, 7L)) {
          var failure =
              assertThrows(
                  BizException.class,
                  () ->
                      persistence.mirror(
                          mirrorAnchor("pay-persistence-1"),
                          "stale-state",
                          "TRY_UNKNOWN",
                          branch,
                          "alias",
                          LocalDateTime.of(2026, 10, 14, 12, 0)));
          org.junit.jupiter.api.Assertions.assertEquals(
              "PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
          org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
        }
        org.junit.jupiter.api.Assertions.assertEquals(
            before,
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row)));
      }
    var missing =
        assertThrows(
            BizException.class,
            () ->
                persistence.mirror(
                    mirrorAnchor("absent-pay"),
                    "POST_ATTEMPTED",
                    "TRY_UNKNOWN",
                    0L,
                    "absent",
                    LocalDateTime.of(2026, 10, 14, 12, 0)));
    org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", missing.getCode());
    org.junit.jupiter.api.Assertions.assertEquals(404, missing.getHttpStatus());
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(payRecordMapper, cancelMapper);
  }

  /** 八字段单边及多字段漂移必须先于分支、终态或PREPARED早返拒绝。 */
  @Test
  void changedOriginalAnchorCannotReachBranchStateOrMirrorSql() {
    var mutations =
        List.<java.util.function.Consumer<PayTccDispatchPO>>of(
            current -> current.id = 2L,
            current -> current.payNo = "PAY-PERSISTENCE-1",
            current -> current.xid = "other-xid",
            current -> current.userId = 8L,
            current -> current.orderNo = "other-order",
            current -> current.amount = new BigDecimal("11.00"),
            current -> current.operationKey = "other-operation",
            current -> current.ledgerKey = "other-ledger",
            current -> {
              current.xid = "other-xid";
              current.userId = 8L;
            });
    for (String state : List.of("POST_ATTEMPTED", "CONFIRMED", "PREPARED", "TRY_ACCEPTED")) {
      for (var mutation : mutations) {
        var expected = dispatch("POST_ATTEMPTED", null);
        var current = dispatch(state, 7L);
        mutation.accept(current);
        when(dispatchMapper.selectForUpdate(expected.payNo)).thenReturn(current);
        var failure =
            assertThrows(
                BizException.class,
                () ->
                    persistence.mirror(
                        expected, "old-state", "TRY_UNKNOWN", 0L, "unknown", LocalDateTime.now()));
        org.junit.jupiter.api.Assertions.assertEquals(
            "PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
        org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
      }
    }
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(payRecordMapper, cancelMapper);
  }

  /** 数值等价金额和状态/分支/时间进展不属于载荷漂移，CAS仍用锁内状态。 */
  @Test
  void equalMoneyAndMutableProgressKeepCurrentStateCas() {
    var expected = dispatch("POST_ATTEMPTED", null);
    expected.amount = new BigDecimal("10.000");
    expected.createdAt = LocalDateTime.of(2026, 10, 1, 0, 0, 0, 123456789);
    var current = dispatch("TRY_ACCEPTED", 7L);
    current.createdAt = LocalDateTime.of(2026, 10, 1, 0, 0, 0, 123000000);
    current.updatedAt = LocalDateTime.of(2026, 10, 2, 0, 0);
    current.lastError = "concurrent-progress";
    when(dispatchMapper.selectForUpdate(expected.payNo)).thenReturn(current);
    when(dispatchMapper.mirrorState(any(), any(), any(), any(), any(), any())).thenReturn(1);
    var updatedAt = LocalDateTime.of(2026, 10, 15, 1, 0);
    org.junit.jupiter.api.Assertions.assertTrue(
        persistence.mirror(expected, "old-state", "CANCELLED", 7L, null, updatedAt));
    verify(dispatchMapper)
        .mirrorState(
            org.mockito.ArgumentMatchers.eq(expected.payNo),
            org.mockito.ArgumentMatchers.eq("TRY_ACCEPTED"),
            org.mockito.ArgumentMatchers.eq("CANCELLED"),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.same(updatedAt));
    org.junit.jupiter.api.Assertions.assertEquals("POST_ATTEMPTED", expected.state);
    org.junit.jupiter.api.Assertions.assertNull(expected.userBranchId);
  }

  /** 缺原snapshot不能重建，缺当前行仍404，任一侧缺金额均409而非NPE。 */
  @Test
  void missingAnchorAndMoneyStayDistinctFromMissingRow() {
    assertThrows(
        BizException.class,
        () -> persistence.mirror(null, "old", "TRY_UNKNOWN", null, null, LocalDateTime.now()));
    assertThrows(
        BizException.class,
        () ->
            persistence.mirror(
                new PayTccDispatchPO(), "old", "TRY_UNKNOWN", null, null, LocalDateTime.now()));
    org.mockito.Mockito.verifyNoInteractions(dispatchMapper);
    var absent =
        assertThrows(
            BizException.class,
            () ->
                persistence.mirror(
                    mirrorAnchor("absent-pay"),
                    "old",
                    "TRY_UNKNOWN",
                    0L,
                    null,
                    LocalDateTime.now()));
    org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", absent.getCode());
    org.junit.jupiter.api.Assertions.assertEquals(404, absent.getHttpStatus());
    for (boolean expectedMissing : List.of(false, true)) {
      var expected = dispatch("POST_ATTEMPTED", null);
      var current = dispatch("POST_ATTEMPTED", null);
      if (expectedMissing) expected.amount = null;
      else current.amount = null;
      when(dispatchMapper.selectForUpdate(expected.payNo)).thenReturn(current);
      var failure =
          assertThrows(
              BizException.class,
              () ->
                  persistence.mirror(
                      expected, "old", "TRY_UNKNOWN", null, null, LocalDateTime.now()));
      org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
      org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
    }
    verify(dispatchMapper, never()).mirrorState(any(), any(), any(), any(), any(), any());
  }

  /** 用请求原键构造测试锚点，不能从已变异的当前行复制。 */
  private PayTccDispatchPO mirrorAnchor(String payNo) {
    var expected = dispatch("POST_ATTEMPTED", null);
    expected.payNo = payNo;
    return expected;
  }

  private PayTccDispatchPO dispatch(String state, Long branchId) {
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.id = 1L;
    dispatch.payNo = "pay-persistence-1";
    dispatch.xid = "xid-persistence-1";
    dispatch.userId = 7L;
    dispatch.orderNo = "order-persistence-1";
    dispatch.amount = new BigDecimal("10.00");
    dispatch.operationKey = "tcc-debit:pay-persistence-1";
    dispatch.ledgerKey = "pay-persistence-1";
    dispatch.state = state;
    dispatch.userBranchId = branchId;
    return dispatch;
  }

  private PayRecordPO payment(String mode, String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-persistence-1";
    payment.userId = 7L;
    payment.orderNo = "order-persistence-1";
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = mode;
    payment.status = status;
    return payment;
  }
}
