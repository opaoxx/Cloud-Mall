package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptServiceImpl;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay receipt Facade 的认领 winner 和 LocalTCC 注册边界测试。 */
class PayTccReceiptServiceTest {
  /** 原 dispatch 锚点服务。 */
  private PayTccBridgeService bridge;

  /** Receipt 独立认领存储。 */
  private PayTccReceiptStore receipts;

  /** Pay LocalTCC 资源代理。 */
  private PayTccReceiptLocalResource resource;

  /** 被测 Facade。 */
  private PayTccReceiptServiceImpl service;

  /** 准备 Facade 依赖。 */
  @BeforeEach
  void setUp() {
    bridge = mock(PayTccBridgeService.class);
    receipts = mock(PayTccReceiptStore.class);
    resource = mock(PayTccReceiptLocalResource.class);
    service = new PayTccReceiptServiceImpl(bridge, receipts, resource);
  }

  /** 唯一 winner 才调用 Pay LocalTCC，返回已持久化 receipt。 */
  @Test
  void winnerClaimsAndRegistersResource() {
    PayTccDispatchDTO request = request();
    PayTccDispatchVO dispatch = dispatch();
    when(bridge.prepare(request)).thenReturn(dispatch);
    when(receipts.claim(dispatch)).thenReturn(true);
    when(resource.prepare(null, request)).thenReturn(true);
    when(receipts.read("pay-receipt-test")).thenReturn(receipt("TRYING"));

    PayTccReceiptVO result = service.prepare(request);

    assertEquals("TRYING", result.state);
    verify(resource).prepare(null, request);
  }

  /** 重放 claim 只读既有事实，不注册第二个 Pay branch。 */
  @Test
  void loserReadsClaimedReceiptWithoutRegisteringResource() {
    PayTccDispatchDTO request = request();
    PayTccDispatchVO dispatch = dispatch();
    when(bridge.prepare(request)).thenReturn(dispatch);
    when(receipts.claim(dispatch)).thenReturn(false);
    when(receipts.read("pay-receipt-test")).thenReturn(receipt("CLAIMED"));

    PayTccReceiptVO result = service.prepare(request);

    assertEquals("CLAIMED", result.state);
    verify(resource, never()).prepare(any(), any());
  }

  /** LocalTCC 返回 false 时 Facade 返回未知错误且不伪造成功。 */
  @Test
  void resourceFalseReturnsUnknownAfterClaim() {
    PayTccDispatchDTO request = request();
    PayTccDispatchVO dispatch = dispatch();
    when(bridge.prepare(request)).thenReturn(dispatch);
    when(receipts.claim(dispatch)).thenReturn(true);
    when(resource.prepare(null, request)).thenReturn(false);

    BizException failure = assertThrows(BizException.class, () -> service.prepare(request));

    assertEquals("PAY_TCC_RECEIPT_UNKNOWN", failure.getCode());
    verify(receipts, never()).read(any());
  }

  /** LocalTCC 抛错时保持已认领事实，不重复 claim 或注册。 */
  @Test
  void resourceExceptionDoesNotReleaseClaim() {
    PayTccDispatchDTO request = request();
    PayTccDispatchVO dispatch = dispatch();
    when(bridge.prepare(request)).thenReturn(dispatch);
    when(receipts.claim(dispatch)).thenReturn(true);
    doThrow(new RuntimeException("resource failure")).when(resource).prepare(null, request);

    assertThrows(RuntimeException.class, () -> service.prepare(request));

    verify(receipts).claim(dispatch);
    verify(resource).prepare(null, request);
  }

  /** read 缺少 receipt 时返回 404，且不触发 bridge 或资源调用。 */
  @Test
  void readMissingReceiptReturnsNotFound() {
    when(receipts.read("missing-pay")).thenReturn(null);

    BizException failure = assertThrows(BizException.class, () -> service.read("missing-pay"));

    assertEquals(404, failure.getHttpStatus());
    verify(bridge, never()).prepare(any());
    verify(resource, never()).prepare(any(), any());
  }

  /** 两个来源的foreign/case/space/NULL在首次和重放均拒绝，不查询或插入receipt。 */
  @Test
  void claimRequiresBothOriginalSourcePaymentNumbers() {
    for (boolean replay : java.util.List.of(false, true))
      for (String sourceKind : java.util.List.of("payment", "anchor", "both"))
        for (String mutation : java.util.List.of("foreign", "case", "space", "null")) {
          var fixture = new ReceiptClaimFixture();
          if (replay) fixture.seedExisting();
          String wrong =
              switch (mutation) {
                case "foreign" -> "other-pay";
                case "case" -> "PAY-RECEIPT-TEST";
                case "space" -> "pay-receipt-test ";
                default -> null;
              };
          if (!sourceKind.equals("anchor")) fixture.payment.payNo = wrong;
          if (!sourceKind.equals("payment")) fixture.anchor.payNo = wrong;
          var before = fixture.snapshot();
          var failure =
              assertThrows(BizException.class, () -> fixture.store.claim(fixture.dispatch));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
          assertEquals(before, fixture.snapshot());
          verify(fixture.receiptRows, never()).selectByPayNoForUpdate(any());
          verify(fixture.receiptRows, never()).claim(any());
        }
  }

  /** 真实Facade和Store拒绝错来源，不允许注册LocalTCC或产生winner。 */
  @Test
  void facadeCannotRegisterResourceFromAlienClaimSources() {
    for (String sourceKind : java.util.List.of("payment", "anchor", "both")) {
      var fixture = new ReceiptClaimFixture();
      if (!sourceKind.equals("anchor")) fixture.payment.payNo = "PAY-RECEIPT-TEST";
      if (!sourceKind.equals("payment")) fixture.anchor.payNo = "PAY-RECEIPT-TEST";
      var anchorBridge = mock(PayTccBridgeService.class);
      var localResource = mock(PayTccReceiptLocalResource.class);
      var facade = new PayTccReceiptServiceImpl(anchorBridge, fixture.store, localResource);
      var request = request();
      when(anchorBridge.prepare(request)).thenReturn(fixture.dispatch);
      var before = fixture.snapshot();
      var failure = assertThrows(BizException.class, () -> facade.prepare(request));
      assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
      assertEquals(before, fixture.snapshot());
      org.mockito.Mockito.verifyNoInteractions(localResource);
      verify(fixture.receiptRows, never()).claim(any());
    }
  }

  /** 精确来源保持winner/幂等；共同NULL原键或取消gate不能认领。 */
  @Test
  void exactClaimSourcesKeepWinnerReplayAndNullOrCancelRejection() {
    var exact = new ReceiptClaimFixture();
    org.junit.jupiter.api.Assertions.assertTrue(exact.store.claim(exact.dispatch));
    assertEquals("CLAIMED", exact.stored.state);
    assertEquals(exact.dispatch.payNo, exact.stored.payNo);
    var after = exact.snapshot();
    org.junit.jupiter.api.Assertions.assertFalse(exact.store.claim(exact.dispatch));
    assertEquals(after, exact.snapshot());
    verify(exact.receiptRows).claim(any());
    var missingKeys = new ReceiptClaimFixture();
    missingKeys.dispatch.payNo = null;
    missingKeys.payment.payNo = null;
    missingKeys.anchor.payNo = null;
    missingKeys.dispatch.operationKey = "tcc-debit:null";
    missingKeys.anchor.operationKey = "tcc-debit:null";
    missingKeys.dispatch.ledgerKey = null;
    missingKeys.anchor.ledgerKey = null;
    var before = missingKeys.snapshot();
    assertEquals(
        "PAY_TCC_RECEIPT_CONFLICT",
        assertThrows(BizException.class, () -> missingKeys.store.claim(missingKeys.dispatch))
            .getCode());
    assertEquals(before, missingKeys.snapshot());
    verify(missingKeys.receiptRows, never()).claim(any());
    var cancelled = new ReceiptClaimFixture();
    when(cancelled.cancelRows.selectForUpdate(any()))
        .thenReturn(new com.cloudmall.pay.domain.po.PayTccCancelPO());
    assertEquals(
        "PAY_TCC_RECEIPT_CONFLICT",
        assertThrows(BizException.class, () -> cancelled.store.claim(cancelled.dispatch))
            .getCode());
    verify(cancelled.receiptRows, never()).selectByPayNoForUpdate(any());
    verify(cancelled.receiptRows, never()).claim(any());
  }

  /** 非空返回行原键不精确时，四状态read/lock均拒绝且对象不变。 */
  @Test
  void receiptLookupsRejectForeignReturnedIdentity() {
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (String mutation : java.util.List.of("foreign", "case", "space", "null")) {
        var fixture = new ReceiptClaimFixture();
        fixture.seedExisting();
        fixture.stored.state = state;
        fixture.stored.payNo =
            switch (mutation) {
              case "foreign" -> "other-pay";
              case "case" -> "PAY-RECEIPT-TEST";
              case "space" -> "pay-receipt-test ";
              default -> null;
            };
        var before = fixture.snapshot();
        assertEquals(
            "PAY_TCC_RECEIPT_CONFLICT",
            assertThrows(BizException.class, () -> fixture.store.read("pay-receipt-test"))
                .getCode());
        assertEquals(
            "PAY_TCC_RECEIPT_CONFLICT",
            assertThrows(BizException.class, () -> fixture.store.lock("pay-receipt-test"))
                .getCode());
        assertEquals(before, fixture.snapshot());
        verify(fixture.receiptRows, never()).claim(any());
        org.mockito.Mockito.verifyNoInteractions(
            fixture.paymentRows, fixture.anchorRows, fixture.cancelRows);
      }
    var missingKeys = new ReceiptClaimFixture();
    missingKeys.seedExisting();
    missingKeys.stored.payNo = null;
    assertEquals(
        "PAY_TCC_RECEIPT_CONFLICT",
        assertThrows(BizException.class, () -> missingKeys.store.read(null)).getCode());
    assertEquals(
        "PAY_TCC_RECEIPT_CONFLICT",
        assertThrows(BizException.class, () -> missingKeys.store.lock(null)).getCode());
  }

  /** 真实Facade/Store读到别名receipt必须冲突，不调用bridge或LocalTCC。 */
  @Test
  void facadeRejectsAliasedReceiptWithoutBridgeOrLocalTcc() {
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      var fixture = new ReceiptClaimFixture();
      fixture.seedExisting();
      fixture.stored.state = state;
      fixture.stored.payNo = "PAY-RECEIPT-TEST";
      var facade = new PayTccReceiptServiceImpl(bridge, fixture.store, resource);
      var before = fixture.snapshot();
      assertEquals(
          "PAY_TCC_RECEIPT_CONFLICT",
          assertThrows(BizException.class, () -> facade.read("pay-receipt-test")).getCode());
      assertEquals(before, fixture.snapshot());
      org.mockito.Mockito.verifyNoInteractions(bridge, resource);
      verify(fixture.receiptRows, never()).claim(any());
    }
  }

  /** 精确getter返回原对象及VO字段，缺失仍NULL/Facade404，不新增认领或资源。 */
  @Test
  void receiptLookupExactAndMissingKeepOriginalSemantics() {
    var fixture = new ReceiptClaimFixture();
    fixture.seedExisting();
    var facade = new PayTccReceiptServiceImpl(bridge, fixture.store, resource);
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      fixture.stored.state = state;
      fixture.stored.branchId = state.equals("CLAIMED") ? null : 11L;
      var before = fixture.snapshot();
      org.junit.jupiter.api.Assertions.assertSame(
          fixture.stored, fixture.store.read("pay-receipt-test"));
      org.junit.jupiter.api.Assertions.assertSame(
          fixture.stored, fixture.store.lock("pay-receipt-test"));
      var result = facade.read("pay-receipt-test");
      assertEquals(fixture.stored.payNo, result.payNo);
      assertEquals(fixture.stored.xid, result.xid);
      assertEquals(fixture.stored.branchId, result.branchId);
      assertEquals(state, result.state);
      assertEquals(fixture.stored.operationKey, result.operationKey);
      assertEquals(fixture.stored.ledgerKey, result.ledgerKey);
      assertEquals(before, fixture.snapshot());
    }
    fixture.stored = null;
    org.junit.jupiter.api.Assertions.assertNull(fixture.store.read("absent-pay"));
    org.junit.jupiter.api.Assertions.assertNull(fixture.store.lock("absent-pay"));
    var missing = assertThrows(BizException.class, () -> facade.read("absent-pay"));
    assertEquals("PAY_TCC_RECEIPT_NOT_FOUND", missing.getCode());
    assertEquals(404, missing.getHttpStatus());
    verify(fixture.receiptRows, never()).claim(any());
    org.mockito.Mockito.verifyNoInteractions(
        bridge, resource, fixture.paymentRows, fixture.anchorRows, fixture.cancelRows);
  }

  /** 首次认领逐项要求未派发的输入/锚点阶段，不把PREPARED等同于未用过POST。 */
  @Test
  void freshReceiptRequiresUnusedDispatchEvidence() {
    for (String state :
        java.util.Arrays.asList(
            null, "UNKNOWN", "POST_ATTEMPTED", "TRY_ACCEPTED", "CONFIRMED", "CANCELLED")) {
      var fixture = new ReceiptClaimFixture();
      fixture.dispatch.state = state;
      assertFreshClaimRejected(fixture);
    }
    for (Long branch : java.util.List.of(0L, -1L, 7L)) {
      var inputBranch = new ReceiptClaimFixture();
      inputBranch.dispatch.userBranchId = branch;
      assertFreshClaimRejected(inputBranch);
      var anchorBranch = new ReceiptClaimFixture();
      anchorBranch.anchor.userBranchId = branch;
      assertFreshClaimRejected(anchorBranch);
    }
    var posted = new ReceiptClaimFixture();
    posted.anchor.postAttemptedAt = java.time.LocalDateTime.of(2026, 10, 15, 0, 0);
    assertFreshClaimRejected(posted);
    var advanced = new ReceiptClaimFixture();
    advanced.anchor.state = "TRY_ACCEPTED";
    assertFreshClaimRejected(advanced);
    var completedPayment = new ReceiptClaimFixture();
    completedPayment.payment.status = "SUCCESS";
    assertFreshClaimRejected(completedPayment);
  }

  /** 真实Facade/Store拒绝已派发fresh，唯一合法winner只触发一次资源尝试。 */
  @Test
  void freshReceiptFacadeCannotRegisterFromConsumedDispatch() {
    for (boolean marked : java.util.List.of(false, true)) {
      var fixture = new ReceiptClaimFixture();
      if (marked) fixture.anchor.postAttemptedAt = java.time.LocalDateTime.of(2026, 10, 15, 0, 0);
      else fixture.anchor.userBranchId = 7L;
      var anchorBridge = mock(PayTccBridgeService.class);
      var localResource = mock(PayTccReceiptLocalResource.class);
      when(anchorBridge.prepare(any())).thenReturn(fixture.dispatch);
      var facade = new PayTccReceiptServiceImpl(anchorBridge, fixture.store, localResource);
      var before = fixture.snapshot();
      var failure = assertThrows(BizException.class, () -> facade.prepare(request()));
      assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, fixture.snapshot());
      org.mockito.Mockito.verifyNoInteractions(localResource);
      verify(fixture.receiptRows, never()).claim(any());
    }
    var fresh = new ReceiptClaimFixture();
    var anchorBridge = mock(PayTccBridgeService.class);
    var localResource = mock(PayTccReceiptLocalResource.class);
    when(anchorBridge.prepare(any())).thenReturn(fresh.dispatch);
    when(localResource.prepare(any(), any())).thenReturn(true);
    var facade = new PayTccReceiptServiceImpl(anchorBridge, fresh.store, localResource);
    assertEquals("CLAIMED", facade.prepare(request()).state);
    var afterFirst = fresh.snapshot();
    assertEquals("CLAIMED", facade.prepare(request()).state);
    assertEquals(afterFirst, fresh.snapshot());
    verify(localResource).prepare(any(), any());
    verify(fresh.receiptRows).claim(any());
  }

  /** 已有四状态重放不受fresh阶段门影响，原载荷冲突仍必须校验。 */
  @Test
  void existingReceiptReplayPrecedesFreshDispatchGate() {
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      var fixture = new ReceiptClaimFixture();
      fixture.seedExisting();
      fixture.stored.state = state;
      fixture.stored.branchId = state.equals("CLAIMED") ? null : 11L;
      fixture.payment.status = "SUCCESS";
      fixture.anchor.state = "CONFIRMED";
      fixture.anchor.postAttemptedAt = java.time.LocalDateTime.of(2026, 10, 15, 0, 0);
      fixture.anchor.userBranchId = 7L;
      fixture.dispatch.state = null;
      fixture.dispatch.userBranchId = 7L;
      var before = fixture.snapshot();
      org.junit.jupiter.api.Assertions.assertFalse(fixture.store.claim(fixture.dispatch));
      var anchorBridge = mock(PayTccBridgeService.class);
      var localResource = mock(PayTccReceiptLocalResource.class);
      when(anchorBridge.prepare(any())).thenReturn(fixture.dispatch);
      assertEquals(
          state,
          new PayTccReceiptServiceImpl(anchorBridge, fixture.store, localResource)
              .prepare(request())
              .state);
      assertEquals(before, fixture.snapshot());
      org.mockito.Mockito.verifyNoInteractions(localResource);
      verify(fixture.receiptRows, never()).claim(any());
    }
    var conflicting = new ReceiptClaimFixture();
    conflicting.seedExisting();
    conflicting.stored.xid = "other-xid";
    conflicting.anchor.postAttemptedAt = java.time.LocalDateTime.of(2026, 10, 15, 0, 0);
    assertFreshClaimRejected(conflicting);
    verify(conflicting.receiptRows).selectByPayNoForUpdate(conflicting.dispatch.payNo);
  }

  /** 对比输入/源/receipt完整事实并验证没有claim写入，不伪造成功或修复坏场景。 */
  private void assertFreshClaimRejected(ReceiptClaimFixture fixture) {
    var before = fixture.snapshot();
    var failure = assertThrows(BizException.class, () -> fixture.store.claim(fixture.dispatch));
    assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
    assertEquals(409, failure.getHttpStatus());
    assertEquals(before, fixture.snapshot());
    verify(fixture.receiptRows, never()).claim(any());
  }

  /** winner/replay的七字段变化或缺金额都不能作为原准备结果返回。 */
  @Test
  void prepareReadbackRequiresOriginalReceiptPayload() {
    for (boolean winner : java.util.List.of(false, true))
      for (var mutation : receiptReadbackMutations()) {
        setUp();
        var expected = dispatch();
        var changed = receipt("TRYING");
        mutation.accept(changed);
        when(bridge.prepare(any())).thenReturn(expected);
        when(receipts.claim(expected)).thenReturn(winner);
        when(resource.prepare(any(), any())).thenReturn(true);
        when(receipts.read(expected.payNo)).thenReturn(changed);
        var failure = assertThrows(BizException.class, () -> service.prepare(request()));
        assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        verify(receipts).claim(expected);
        verify(resource, org.mockito.Mockito.times(winner ? 1 : 0)).prepare(any(), any());
        verify(receipts).read(expected.payNo);
      }
  }

  /** 数值等价、状态/分支进展保持，缺行先于payload仍原503且不追加注册。 */
  @Test
  void prepareReadbackKeepsProgressEqualMoneyAndMissingPriority() {
    for (boolean winner : java.util.List.of(false, true))
      for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
        setUp();
        var expected = dispatch();
        expected.amount = "1E+1";
        var current = receipt(state);
        current.amount = new BigDecimal("10.000");
        current.branchId = state.equals("CLAIMED") ? null : 11L;
        when(bridge.prepare(any())).thenReturn(expected);
        when(receipts.claim(expected)).thenReturn(winner);
        when(resource.prepare(any(), any())).thenReturn(true);
        when(receipts.read(expected.payNo)).thenReturn(current);
        var result = service.prepare(request());
        assertEquals(state, result.state);
        assertEquals(current.branchId, result.branchId);
        assertEquals(current.xid, result.xid);
        verify(resource, org.mockito.Mockito.times(winner ? 1 : 0)).prepare(any(), any());
        assertEquals("1E+1", expected.amount);
      }
    for (boolean winner : java.util.List.of(false, true)) {
      setUp();
      var expected = dispatch();
      when(bridge.prepare(any())).thenReturn(expected);
      when(receipts.claim(expected)).thenReturn(winner);
      when(resource.prepare(any(), any())).thenReturn(true);
      var failure = assertThrows(BizException.class, () -> service.prepare(request()));
      assertEquals("PAY_TCC_RECEIPT_UNKNOWN", failure.getCode());
      assertEquals(503, failure.getHttpStatus());
      verify(resource, org.mockito.Mockito.times(winner ? 1 : 0)).prepare(any(), any());
    }
  }

  /** 实际Store复用payload规则，既有缺金额409；合法尾零/指数重放不改金额或状态。 */
  @Test
  void existingReceiptPayloadComparisonRejectsMissingMoneySafely() {
    var missing = new ReceiptClaimFixture();
    missing.seedExisting();
    missing.stored.amount = null;
    assertFreshClaimRejected(missing);
    var equal = new ReceiptClaimFixture();
    equal.seedExisting();
    equal.dispatch.amount = "1E+1";
    equal.stored.amount = new BigDecimal("10.000");
    var before = equal.snapshot();
    org.junit.jupiter.api.Assertions.assertFalse(equal.store.claim(equal.dispatch));
    assertEquals(before, equal.snapshot());
    verify(equal.receiptRows, never()).claim(any());
  }

  /** 每次只变异独立读回PO，保留原dispatch预期，不借变更修复坏场景。 */
  private java.util.List<java.util.function.Consumer<PayTccReceiptPO>> receiptReadbackMutations() {
    return java.util.List.of(
        current -> current.payNo = "PAY-RECEIPT-TEST",
        current -> current.xid = "changed-xid",
        current -> current.userId = 8L,
        current -> current.orderNo = "changed-order",
        current -> current.amount = new BigDecimal("11.00"),
        current -> current.operationKey = "changed-operation",
        current -> current.ledgerKey = "changed-ledger",
        current -> current.amount = null,
        current -> {
          current.xid = "changed-xid";
          current.userId = 8L;
        });
  }

  /** 实际Store既有坏证据统一409，不NPE、不新claim、不触发资源替身。 */
  @Test
  void existingReceiptRequiresValidStateAndBranch() {
    for (var mutation : receiptEvidenceMutations()) {
      var fixture = new ReceiptClaimFixture();
      fixture.seedExisting();
      mutation.accept(fixture.stored);
      assertFreshClaimRejected(fixture);
      var anchorBridge = mock(PayTccBridgeService.class);
      var localResource = mock(PayTccReceiptLocalResource.class);
      when(anchorBridge.prepare(any())).thenReturn(fixture.dispatch);
      var before = fixture.snapshot();
      var failure =
          assertThrows(
              BizException.class,
              () ->
                  new PayTccReceiptServiceImpl(anchorBridge, fixture.store, localResource)
                      .prepare(request()));
      assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(before, fixture.snapshot());
      org.mockito.Mockito.verifyNoInteractions(localResource);
      verify(fixture.receiptRows, never()).claim(any());
    }
    var bothBad = new ReceiptClaimFixture();
    bothBad.seedExisting();
    bothBad.stored.xid = "changed-xid";
    bothBad.stored.state = null;
    var failure = assertThrows(BizException.class, () -> bothBad.store.claim(bothBad.dispatch));
    assertEquals("Pay receipt 载荷不一致", failure.getMessage());
  }

  /** winner/replay末次读回拒绝坏状态/分支，已完成资源次数保持不追加。 */
  @Test
  void prepareReadbackRequiresValidStateAndBranch() {
    for (boolean winner : java.util.List.of(false, true))
      for (var mutation : receiptEvidenceMutations()) {
        setUp();
        var expected = dispatch();
        var current = receipt("CLAIMED");
        mutation.accept(current);
        when(bridge.prepare(any())).thenReturn(expected);
        when(receipts.claim(expected)).thenReturn(winner);
        when(resource.prepare(any(), any())).thenReturn(true);
        when(receipts.read(expected.payNo)).thenReturn(current);
        var before =
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(current));
        var failure = assertThrows(BizException.class, () -> service.prepare(request()));
        assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        assertEquals(
            before,
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(current)));
        verify(receipts).claim(expected);
        verify(receipts).read(expected.payNo);
        verify(resource, org.mockito.Mockito.times(winner ? 1 : 0)).prepare(any(), any());
      }
    setUp();
    var expected = dispatch();
    var bothBad = receipt("CLAIMED");
    bothBad.xid = "changed-xid";
    bothBad.state = null;
    when(bridge.prepare(any())).thenReturn(expected);
    when(receipts.read(expected.payNo)).thenReturn(bothBad);
    var failure = assertThrows(BizException.class, () -> service.prepare(request()));
    assertEquals("Pay receipt 读回载荷与原记录不一致", failure.getMessage());
  }

  /** CLAIMED不证明未登记；合法NULL/正branch及上界均保持原只读重放。 */
  @Test
  void validReceiptEvidenceKeepsClaimedUnknownAndBoundProgress() {
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (Long branch : java.util.Arrays.asList(null, 1L, Long.MAX_VALUE)) {
        if (branch == null && !state.equals("CLAIMED")) continue;
        var fixture = new ReceiptClaimFixture();
        fixture.seedExisting();
        fixture.stored.state = state;
        fixture.stored.branchId = branch;
        fixture.payment.status = "SUCCESS";
        fixture.anchor.state = "CONFIRMED";
        fixture.anchor.postAttemptedAt = java.time.LocalDateTime.of(2026, 10, 15, 0, 0);
        fixture.anchor.userBranchId = 7L;
        fixture.dispatch.state = null;
        fixture.dispatch.userBranchId = 7L;
        var before = fixture.snapshot();
        org.junit.jupiter.api.Assertions.assertFalse(fixture.store.claim(fixture.dispatch));
        assertEquals(before, fixture.snapshot());
        verify(fixture.receiptRows, never()).claim(any());
        for (boolean winner : java.util.List.of(false, true)) {
          setUp();
          var expected = dispatch();
          when(bridge.prepare(any())).thenReturn(expected);
          when(receipts.claim(expected)).thenReturn(winner);
          when(resource.prepare(any(), any())).thenReturn(true);
          when(receipts.read(expected.payNo)).thenReturn(fixture.stored);
          var result = service.prepare(request());
          assertEquals(state, result.state);
          assertEquals(branch, result.branchId);
          verify(resource, org.mockito.Mockito.times(winner ? 1 : 0)).prepare(any(), any());
          assertEquals(before, fixture.snapshot());
        }
      }
  }

  /** 独立read复用同一有效域，拒绝坏状态/分支且不触发认领或资源。 */
  @Test
  void publicReadRequiresValidStateAndBranch() {
    for (var mutation : receiptEvidenceMutations()) {
      setUp();
      var current = receipt("CLAIMED");
      mutation.accept(current);
      when(receipts.read("pay-receipt-test")).thenReturn(current);
      var before =
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
              () ->
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .findAndRegisterModules()
                      .writeValueAsString(current));
      var failure = assertThrows(BizException.class, () -> service.read("pay-receipt-test"));
      assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      assertEquals(
          before,
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
              () ->
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .findAndRegisterModules()
                      .writeValueAsString(current)));
      verify(receipts).read("pay-receipt-test");
      org.mockito.Mockito.verifyNoMoreInteractions(receipts);
      org.mockito.Mockito.verifyNoInteractions(bridge, resource);
    }
  }

  /** CLAIMED未知/正branch及其它三态正branch均保持原视图字段。 */
  @Test
  void publicReadKeepsValidClaimedAndBoundEvidence() {
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (Long branch : java.util.Arrays.asList(null, 1L, Long.MAX_VALUE)) {
        if (branch == null && !state.equals("CLAIMED")) continue;
        setUp();
        var current = receipt(state);
        current.branchId = branch;
        when(receipts.read("pay-receipt-test")).thenReturn(current);
        var result = service.read("pay-receipt-test");
        assertEquals(current.payNo, result.payNo);
        assertEquals(current.xid, result.xid);
        assertEquals(current.operationKey, result.operationKey);
        assertEquals(current.ledgerKey, result.ledgerKey);
        assertEquals(state, result.state);
        assertEquals(branch, result.branchId);
        verify(receipts).read("pay-receipt-test");
        org.mockito.Mockito.verifyNoMoreInteractions(receipts);
        org.mockito.Mockito.verifyNoInteractions(bridge, resource);
      }
  }

  /** 缺失404及Store identity/DAO原异常保持先级，只查询一次。 */
  @Test
  void publicReadKeepsMissingAndLookupFailurePriority() {
    var missing = assertThrows(BizException.class, () -> service.read("missing-pay"));
    assertEquals("PAY_TCC_RECEIPT_NOT_FOUND", missing.getCode());
    assertEquals(404, missing.getHttpStatus());
    verify(receipts).read("missing-pay");
    org.mockito.Mockito.verifyNoMoreInteractions(receipts);
    org.mockito.Mockito.verifyNoInteractions(bridge, resource);
    for (RuntimeException original :
        java.util.List.of(
            new BizException("PAY_TCC_RECEIPT_CONFLICT", "original lookup identity conflict", 409),
            new IllegalStateException("original lookup failure"))) {
      setUp();
      org.mockito.Mockito.doThrow(original).when(receipts).read("pay-receipt-test");
      var failure = assertThrows(RuntimeException.class, () -> service.read("pay-receipt-test"));
      org.junit.jupiter.api.Assertions.assertSame(original, failure);
      verify(receipts).read("pay-receipt-test");
      org.mockito.Mockito.verifyNoMoreInteractions(receipts);
      org.mockito.Mockito.verifyNoInteractions(bridge, resource);
    }
  }

  /** 独立变异stored事实；null状态仅模拟异常Mapper结果，不宣称DB允许NULL。 */
  private java.util.List<java.util.function.Consumer<PayTccReceiptPO>> receiptEvidenceMutations() {
    var mutations = new java.util.ArrayList<java.util.function.Consumer<PayTccReceiptPO>>();
    for (String state : java.util.Arrays.asList(null, "UNKNOWN", "", "trying", "TRYING "))
      mutations.add(
          current -> {
            current.state = state;
            current.branchId = 11L;
          });
    for (String state : java.util.List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      for (Long branch : java.util.List.of(0L, -1L))
        mutations.add(
            current -> {
              current.state = state;
              current.branchId = branch;
            });
      if (!state.equals("CLAIMED"))
        mutations.add(
            current -> {
              current.state = state;
              current.branchId = null;
            });
    }
    return mutations;
  }

  /** 真实Store及可独立变异来源；Mapper均为替身，不模拟数据库列可NULL。 */
  private static final class ReceiptClaimFixture {
    /** 已冻结的原派发输入。 */
    final PayTccDispatchVO dispatch = new PayTccDispatchVO();

    /** 原支付返回对象。 */
    final PayRecordPO payment = new PayRecordPO();

    /** 原派发锚点返回对象。 */
    final com.cloudmall.pay.domain.po.PayTccDispatchPO anchor =
        new com.cloudmall.pay.domain.po.PayTccDispatchPO();

    /** receipt读取和首写替身。 */
    final com.cloudmall.pay.mapper.PayTccReceiptMapper receiptRows =
        mock(com.cloudmall.pay.mapper.PayTccReceiptMapper.class);

    /** 原支付锁定替身。 */
    final com.cloudmall.pay.mapper.PayRecordMapper paymentRows =
        mock(com.cloudmall.pay.mapper.PayRecordMapper.class);

    /** 原锚点锁定替身。 */
    final com.cloudmall.pay.mapper.PayTccDispatchMapper anchorRows =
        mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class);

    /** 取消gate替身。 */
    final com.cloudmall.pay.mapper.PayTccCancelMapper cancelRows =
        mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class);

    /** 真实认领逻辑，未套Spring事务代理。 */
    final PayTccReceiptStore store =
        new PayTccReceiptStore(receiptRows, paymentRows, anchorRows, cancelRows);

    /** 模拟唯一receipt首次插入后的读取事实。 */
    PayTccReceiptPO stored;

    /** 准备完整且精确同源的默认输入，插入与重读只由替身维护。 */
    ReceiptClaimFixture() {
      dispatch.payNo = "pay-receipt-test";
      dispatch.xid = "xid-receipt-test";
      dispatch.userId = 7L;
      dispatch.orderNo = "order-receipt-test";
      dispatch.amount = "10.00";
      dispatch.operationKey = "tcc-debit:pay-receipt-test";
      dispatch.ledgerKey = dispatch.payNo;
      dispatch.state = "PREPARED";
      payment.payNo = dispatch.payNo;
      payment.userId = dispatch.userId;
      payment.orderNo = dispatch.orderNo;
      payment.amount = BigDecimal.TEN;
      payment.debitMode = "TCC";
      payment.status = "PENDING";
      anchor.payNo = dispatch.payNo;
      anchor.xid = dispatch.xid;
      anchor.userId = dispatch.userId;
      anchor.orderNo = dispatch.orderNo;
      anchor.amount = BigDecimal.TEN;
      anchor.operationKey = dispatch.operationKey;
      anchor.ledgerKey = dispatch.ledgerKey;
      anchor.state = "PREPARED";
      when(paymentRows.selectForUpdate(any())).thenReturn(java.util.List.of(payment));
      when(anchorRows.selectForUpdate(any())).thenReturn(anchor);
      when(receiptRows.selectByPayNo(any())).thenAnswer(invocation -> stored);
      when(receiptRows.selectByPayNoForUpdate(any())).thenAnswer(invocation -> stored);
      when(receiptRows.claim(any()))
          .thenAnswer(
              invocation -> {
                stored = invocation.getArgument(0);
                return 1;
              });
    }

    /** 构造合法已存在的CLAIMED，来源冲突必须先于读取它被拒。 */
    void seedExisting() {
      stored = new PayTccReceiptPO();
      stored.payNo = dispatch.payNo;
      stored.xid = dispatch.xid;
      stored.userId = dispatch.userId;
      stored.orderNo = dispatch.orderNo;
      stored.amount = BigDecimal.TEN;
      stored.operationKey = dispatch.operationKey;
      stored.ledgerKey = dispatch.ledgerKey;
      stored.state = "CLAIMED";
    }

    /** 保存输入/来源/receipt所有公开字段，包含合法插入后的Java时间。 */
    String snapshot() {
      return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () ->
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .findAndRegisterModules()
                  .writeValueAsString(java.util.Arrays.asList(dispatch, payment, anchor, stored)));
    }
  }

  private PayTccDispatchDTO request() {
    PayTccDispatchDTO request = new PayTccDispatchDTO();
    request.payNo = "pay-receipt-test";
    request.userId = 7L;
    request.orderNo = "order-receipt-test";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private PayTccDispatchVO dispatch() {
    PayTccDispatchVO dispatch = new PayTccDispatchVO();
    dispatch.payNo = "pay-receipt-test";
    dispatch.xid = "xid-receipt-test";
    dispatch.userId = 7L;
    dispatch.orderNo = "order-receipt-test";
    dispatch.amount = "10.00";
    dispatch.operationKey = "tcc-debit:pay-receipt-test";
    dispatch.ledgerKey = "pay-receipt-test";
    return dispatch;
  }

  /** 默认合法资源证据；坏分支/状态测试必须在构造后显式变异。 */
  private PayTccReceiptPO receipt(String state) {
    PayTccReceiptPO receipt = new PayTccReceiptPO();
    receipt.payNo = "pay-receipt-test";
    receipt.xid = "xid-receipt-test";
    receipt.userId = 7L;
    receipt.orderNo = "order-receipt-test";
    receipt.amount = new BigDecimal("10.00");
    receipt.operationKey = "tcc-debit:pay-receipt-test";
    receipt.ledgerKey = "pay-receipt-test";
    receipt.state = state;
    receipt.branchId = "CLAIMED".equals(state) ? null : 11L;
    return receipt;
  }
}
