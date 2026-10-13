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
import com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl;
import com.cloudmall.pay.service.impl.PayTccReceiptStore;
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
    return receipt;
  }
}
