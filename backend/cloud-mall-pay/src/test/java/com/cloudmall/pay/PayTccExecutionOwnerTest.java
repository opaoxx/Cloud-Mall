package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 实际Store与Mapper替身验证owner契约；事务和SQL由native另验。 */
class PayTccExecutionOwnerTest {
  /** fresh owner就是原insert对象；重放返回NULL，旧boolean接口保true/false。 */
  @Test
  void claimKeepsInsertedOwnerAndReplayCompatibility() {
    var fixture = new Fixture();
    var request = request();
    when(fixture.executions.insert(any(PayTccExecutionPO.class))).thenReturn(1);
    var owned = fixture.store.claimOwned(request);
    var inserted = org.mockito.ArgumentCaptor.forClass(PayTccExecutionPO.class);
    verify(fixture.executions).insert(inserted.capture());
    assertSame(inserted.getValue(), owned);
    assertNotNull(owned.id);
    assertNull(owned.xid);
    assertEquals("CLAIMED", owned.state);
    assertEquals("NONE", owned.decision);
    assertEquals(request.payNo, owned.payNo);
    assertEquals(request.userId, owned.userId);
    assertEquals(request.orderNo, owned.orderNo);
    assertEquals(request.amount, owned.amount);
    assertEquals("tcc-debit:" + request.payNo, owned.operationKey);
    assertEquals(request.payNo, owned.ledgerKey);
    fixture.source.status = "SUCCESS";
    when(fixture.executions.selectForUpdate(request.payNo)).thenReturn(owned);
    assertNull(fixture.store.claimOwned(request));
    assertFalse(fixture.store.claim(request));
    verify(fixture.payments, org.mockito.Mockito.times(3)).selectForUpdate(request.payNo);
    verify(fixture.executions, org.mockito.Mockito.times(3)).selectForUpdate(request.payNo);
    verify(fixture.cancels).selectForUpdate(request.payNo);
    verify(fixture.dispatches).selectByPayNo(request.payNo);
    verify(fixture.receipts).selectByPayNo(request.payNo);
    verifyNoMoreInteractions(
        fixture.payments,
        fixture.executions,
        fixture.cancels,
        fixture.dispatches,
        fixture.receipts);
    var legacy = new Fixture();
    when(legacy.executions.insert(any(PayTccExecutionPO.class))).thenReturn(1);
    assertTrue(legacy.store.claim(request));
    verify(legacy.executions).insert(any(PayTccExecutionPO.class));
  }

  /** mutable prebegin保持可绑定，已绑定同XID幂等；原owner对象不被改写。 */
  @Test
  void ownedBindKeepsPrebeginProgressAndReturnsBoundRow() {
    for (String state : List.of("CLAIMED", "BEGIN_UNKNOWN", "RUNNING")) {
      var fixture = new Fixture();
      var owned = execution("CLAIMED", null);
      var current = execution(state, state.equals("RUNNING") ? "owned-xid" : null);
      if (state.equals("RUNNING")) {
        owned.state = "RUNNING";
        owned.xid = "owned-xid";
      }
      var bound = execution("RUNNING", "owned-xid");
      when(fixture.executions.selectForUpdate(owned.payNo)).thenReturn(current);
      when(fixture.executions.bindXid(any(), any(), any())).thenReturn(1);
      when(fixture.executions.selectByPayNo(owned.payNo)).thenReturn(bound);
      var before = snapshot(owned);
      assertSame(bound, fixture.store.bindOwnedXid(owned, "owned-xid"));
      assertEquals(before, snapshot(owned));
      var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
      order.verify(fixture.payments).selectForUpdate(owned.payNo);
      order.verify(fixture.executions).selectForUpdate(owned.payNo);
      if (!state.equals("RUNNING"))
        order.verify(fixture.executions).bindXid(eq(owned.payNo), eq("owned-xid"), any());
      order.verify(fixture.executions).selectByPayNo(owned.payNo);
      fixture.verifyNoOtherInteractions();
    }
  }

  /** 失配owner/source及无效输入不绑定；SQL0与NULL读回保持冲突。 */
  @Test
  void ownedBindRejectsDriftAndInvalidInputsWithoutAdoptingRows() {
    for (String mutation :
        List.of(
            "id",
            "xid",
            "user",
            "order",
            "amount",
            "payment-mode",
            "payment-missing",
            "null-owner",
            "invalid-xid",
            "zero-update",
            "null-readback",
            "different-bound-xid",
            "bad-owner-amount")) {
      var fixture = new Fixture();
      var owned = execution("CLAIMED", null);
      var current = execution("CLAIMED", null);
      String incomingXid = "owned-xid";
      switch (mutation) {
        case "id" -> current.id = 2L;
        case "xid" -> current.xid = "peer-xid";
        case "user" -> current.userId = fixture.source.userId = 8L;
        case "order" -> current.orderNo = fixture.source.orderNo = "peer-order";
        case "amount" -> current.amount = fixture.source.amount = new BigDecimal("10.01");
        case "payment-mode" -> fixture.source.debitMode = "LEGACY";
        case "payment-missing" ->
            when(fixture.payments.selectForUpdate(owned.payNo)).thenReturn(List.of());
        case "invalid-xid" -> incomingXid = " ";
        case "null-readback" -> when(fixture.executions.bindXid(any(), any(), any())).thenReturn(1);
        case "different-bound-xid" -> {
          owned.state = current.state = "RUNNING";
          owned.xid = current.xid = "original-xid";
        }
        case "bad-owner-amount" -> owned.amount = null;
        default -> {}
      }
      when(fixture.executions.selectForUpdate(owned.payNo)).thenReturn(current);
      var input = mutation.equals("null-owner") ? null : owned;
      var xid = incomingXid;
      var before = snapshot(owned);
      var failure = assertThrows(BizException.class, () -> fixture.store.bindOwnedXid(input, xid));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, snapshot(owned));
      if (List.of("null-owner", "invalid-xid").contains(mutation))
        verifyNoInteractions(fixture.payments, fixture.executions);
      else {
        var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
        order.verify(fixture.payments).selectForUpdate(owned.payNo);
        order.verify(fixture.executions).selectForUpdate(owned.payNo);
        if (List.of("zero-update", "null-readback").contains(mutation))
          verify(fixture.executions).bindXid(eq(owned.payNo), eq(xid), any());
        if (mutation.equals("null-readback")) verify(fixture.executions).selectByPayNo(owned.payNo);
      }
      fixture.verifyNoOtherInteractions();
    }
  }

  /** 原owner校验先于决议参数/终态幂等，不允许漂移借同XID决议。 */
  @Test
  void ownedDecisionRejectsDriftBeforeDecisionAndTerminalShortcuts() {
    for (String mutation :
        List.of("id", "xid", "user", "order", "amount", "mode", "missing", "null")) {
      var fixture = new Fixture();
      var owned = execution("RUNNING", "owned-xid");
      var current = execution("COMMITTED", "owned-xid");
      current.decision = "COMMIT";
      switch (mutation) {
        case "id" -> current.id = 2L;
        case "xid" -> current.xid = "peer-xid";
        case "user" -> current.userId = fixture.source.userId = 8L;
        case "order" -> current.orderNo = fixture.source.orderNo = "peer-order";
        case "amount" -> current.amount = fixture.source.amount = new BigDecimal("10.01");
        case "mode" -> fixture.source.debitMode = "LEGACY";
        case "missing" -> when(fixture.payments.selectForUpdate(owned.payNo)).thenReturn(List.of());
        default -> {}
      }
      when(fixture.executions.selectForUpdate(owned.payNo)).thenReturn(current);
      var input = mutation.equals("null") ? null : owned;
      var before = snapshot(owned);
      var failure =
          assertThrows(
              BizException.class, () -> fixture.store.decideOwned(input, owned.xid, "COMMIT"));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, snapshot(owned));
      if (input == null) verifyNoInteractions(fixture.payments, fixture.executions);
      else {
        var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
        order.verify(fixture.payments).selectForUpdate(owned.payNo);
        order.verify(fixture.executions).selectForUpdate(owned.payNo);
      }
      fixture.verifyNoOtherInteractions();
    }
  }

  /** 新决议与旧内核共享双向CAS、同向/终态幂等，原快照不因当前进度而改变。 */
  @Test
  void ownedDecisionKeepsDirectionAndMutableProgress() {
    for (String decision : List.of("COMMIT", "ROLLBACK"))
      for (String state :
          List.of("RUNNING", decision + "_REQUESTED", "COMMITTED", "ROLLEDBACK", "CONFLICT")) {
        var fixture = new Fixture();
        var owned = execution("RUNNING", "owned-xid");
        var current = execution(state, "owned-xid");
        current.decision = state.equals("RUNNING") ? "NONE" : decision;
        fixture.source.status = "SUCCESS";
        var chosen = execution(decision + "_REQUESTED", "owned-xid");
        chosen.decision = decision;
        when(fixture.executions.selectForUpdate(owned.payNo)).thenReturn(current);
        when(fixture.executions.setDecision(any(), any(), any(), any(), any())).thenReturn(1);
        when(fixture.executions.selectByPayNo(owned.payNo)).thenReturn(chosen);
        var before = snapshot(owned);
        assertSame(
            state.equals("RUNNING") ? chosen : current,
            fixture.store.decideOwned(owned, owned.xid, decision));
        assertEquals(before, snapshot(owned));
        var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
        order.verify(fixture.payments).selectForUpdate(owned.payNo);
        order.verify(fixture.executions).selectForUpdate(owned.payNo);
        if (state.equals("RUNNING")) {
          order
              .verify(fixture.executions)
              .setDecision(
                  eq(owned.payNo), eq(owned.xid), eq(decision), eq(decision + "_REQUESTED"), any());
          order.verify(fixture.executions).selectByPayNo(owned.payNo);
        }
        fixture.verifyNoOtherInteractions();
      }
  }

  /** 坏参数/反向/SQL0/NULL读回仍冲突；unit只验调用契约，事务由native验。 */
  @Test
  void ownedDecisionKeepsInputAndPersistenceConflicts() {
    for (String mutation :
        List.of(
            "null-xid",
            "blank-xid",
            "long-xid",
            "different-xid",
            "null-decision",
            "bad-decision",
            "opposite",
            "zero-update",
            "null-readback")) {
      var fixture = new Fixture();
      var owned = execution("RUNNING", "owned-xid");
      var current = execution("RUNNING", "owned-xid");
      if (mutation.equals("opposite")) current.decision = "ROLLBACK";
      when(fixture.executions.selectForUpdate(owned.payNo)).thenReturn(current);
      if (mutation.equals("null-readback"))
        when(fixture.executions.setDecision(any(), any(), any(), any(), any())).thenReturn(1);
      String xid =
          switch (mutation) {
            case "null-xid" -> null;
            case "blank-xid" -> " ";
            case "long-xid" -> "x".repeat(129);
            case "different-xid" -> "other-xid";
            default -> owned.xid;
          };
      String decision =
          mutation.equals("null-decision")
              ? null
              : mutation.equals("bad-decision") ? "commit" : "COMMIT";
      var failure =
          assertThrows(BizException.class, () -> fixture.store.decideOwned(owned, xid, decision));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      var order = org.mockito.Mockito.inOrder(fixture.payments, fixture.executions);
      order.verify(fixture.payments).selectForUpdate(owned.payNo);
      order.verify(fixture.executions).selectForUpdate(owned.payNo);
      if (List.of("zero-update", "null-readback").contains(mutation))
        order
            .verify(fixture.executions)
            .setDecision(
                eq(owned.payNo), eq(owned.xid), eq("COMMIT"), eq("COMMIT_REQUESTED"), any());
      if (mutation.equals("null-readback"))
        order.verify(fixture.executions).selectByPayNo(owned.payNo);
      fixture.verifyNoOtherInteractions();
    }
  }

  /** 有效请求与固定键共享原创建域。 */
  private static PayTccDispatchDTO request() {
    var request = new PayTccDispatchDTO();
    request.payNo = "pay-owner-1";
    request.userId = 7L;
    request.orderNo = "order-owner-1";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  /** 原owner和持久行分别构造，避免共享对象掩盖身份变化。 */
  private static PayTccExecutionPO execution(String state, String xid) {
    var execution = new PayTccExecutionPO();
    var request = request();
    execution.id = 1L;
    execution.payNo = request.payNo;
    execution.userId = request.userId;
    execution.orderNo = request.orderNo;
    execution.amount = request.amount;
    execution.operationKey = "tcc-debit:" + request.payNo;
    execution.ledgerKey = request.payNo;
    execution.state = state;
    execution.decision = "NONE";
    execution.xid = xid;
    return execution;
  }

  /** 核对入参未被规范化或赋予其它XID。 */
  private String snapshot(PayTccExecutionPO owner) {
    return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(owner));
  }

  /** 实际Store、独立Mapper替身；本类不宣称代理事务和SQL执行证明。 */
  private static final class Fixture {
    /** 执行Mapper。 */
    private final PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);

    /** 原PayMapper。 */
    private final PayRecordMapper payments = mock(PayRecordMapper.class);

    /** 派发Mapper。 */
    private final PayTccDispatchMapper dispatches = mock(PayTccDispatchMapper.class);

    /** receipt Mapper。 */
    private final PayTccReceiptMapper receipts = mock(PayTccReceiptMapper.class);

    /** 取消Mapper。 */
    private final PayTccCancelMapper cancels = mock(PayTccCancelMapper.class);

    /** 原Pay来源。 */
    private final PayRecordPO source = new PayRecordPO();

    /** 实际Store。 */
    private final PayTccExecutionStore store =
        new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);

    /** 准备匹配原Pay，execution由各用例独立设定。 */
    private Fixture() {
      var request = request();
      source.payNo = request.payNo;
      source.userId = request.userId;
      source.orderNo = request.orderNo;
      source.amount = request.amount;
      source.debitMode = "TCC";
      source.status = "PENDING";
      when(payments.selectForUpdate(request.payNo)).thenReturn(List.of(source));
    }

    /** 已明确核验之外不得额外读写或访问其它资源。 */
    private void verifyNoOtherInteractions() {
      verifyNoMoreInteractions(payments, executions);
      verifyNoInteractions(cancels, dispatches, receipts);
    }
  }
}
