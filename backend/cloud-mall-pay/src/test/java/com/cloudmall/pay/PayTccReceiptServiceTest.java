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
