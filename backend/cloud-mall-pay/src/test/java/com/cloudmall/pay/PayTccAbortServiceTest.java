package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayTccAbortProofVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortService;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pay TCC 主动回滚服务的 User TRYING/no-ledger 证明门测试。 */
class PayTccAbortServiceTest {
  /** 初段proof与最终回滚决议边界替身。 */
  private PayTccAbortStore store;

  /** User当前状态只读客户端替身。 */
  private UserTccClient users;

  /** 被测未决回滚请求服务。 */
  private PayTccAbortService service;

  /** 准备主动回滚服务依赖。 */
  @BeforeEach
  void setUp() {
    store = mock(PayTccAbortStore.class);
    users = mock(UserTccClient.class);
    service = new PayTccAbortService(store, users, "token");
  }

  /** 完整 User TRYING/no-ledger proof 才调用 Store 的回滚 CAS。 */
  @Test
  void completeTryingProofRequestsRollback() {
    PayVO payment = payment();
    PayTccAbortProofVO proof = proof();
    when(store.preparedProof(payment)).thenReturn(proof);
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userState("TRYING", false, 31L)));

    service.requestRollback(payment);

    verify(store).requestRollback(payment, proof);
  }

  /** UNKNOWN/CANCELLED/CONFIRMED、ledgerApplied、branch/amount 不匹配均保持 202。 */
  @Test
  void incompleteUserProofDoesNotRequestRollback() {
    PayVO payment = payment();
    PayTccAbortProofVO proof = proof();
    when(store.preparedProof(payment)).thenReturn(proof);
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(userState("UNKNOWN", false, 31L)))
        .thenReturn(ApiResponse.ok(userState("CANCELLED", false, 31L)))
        .thenReturn(ApiResponse.ok(userState("CONFIRMED", false, 31L)))
        .thenReturn(ApiResponse.ok(userState("TRYING", true, 31L)))
        .thenReturn(ApiResponse.ok(userState("TRYING", false, 32L)))
        .thenReturn(ApiResponse.ok(userState("TRYING", false, 31L, "11.00")));

    for (int attempt = 0; attempt < 6; attempt++) {
      assertEquals(
          202,
          assertThrows(BizException.class, () -> service.requestRollback(payment)).getHttpStatus());
    }
    verify(store, never()).requestRollback(any(), any());
  }

  /** User Feign 异常只能返回结果未知，不能写回滚决议。 */
  @Test
  void userFeignFailureDoesNotRequestRollback() {
    PayVO payment = payment();
    when(store.preparedProof(payment)).thenReturn(proof());
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenThrow(
            feign.FeignException.errorStatus(
                "User",
                feign.Response.builder()
                    .status(503)
                    .request(
                        feign.Request.create(
                            feign.Request.HttpMethod.GET,
                            "/state",
                            java.util.Collections.emptyMap(),
                            null,
                            java.nio.charset.StandardCharsets.UTF_8,
                            null))
                    .build()));

    assertEquals(
        202,
        assertThrows(BizException.class, () -> service.requestRollback(payment)).getHttpStatus());
    verify(store, never()).requestRollback(any(), any());
  }

  @Test
  void incompleteLocalProofNeverQueriesUser() {
    when(store.preparedProof(any()))
        .thenThrow(new BizException("PAY_ORDER_SYNC_UNKNOWN", "pending", 202));
    assertThrows(BizException.class, () -> service.requestRollback(payment()));
    org.mockito.Mockito.verifyNoInteractions(users);
    verify(store, never()).requestRollback(any(), any());
  }

  /** 实际Jackson缺字段/null均默认false但无presence，不能进入最终决议Store。 */
  @Test
  void missingOrNullLedgerEvidenceNeverRequestsRollback() throws Exception {
    for (String variant : java.util.List.of("missing", "null")) {
      setUp();
      var payment = payment();
      var proof = proof();
      var decoded = decodedAbortLedgerState(variant);
      org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerApplied);
      org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerAppliedPresent);
      when(store.preparedProof(payment)).thenReturn(proof);
      when(users.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(decoded));
      var failure = assertThrows(BizException.class, () -> service.requestRollback(payment));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
      assertEquals(202, failure.getHttpStatus());
      assertEquals("User分支尚不能证明可回滚，请等待原资金结果", failure.getMessage());
      var order = org.mockito.Mockito.inOrder(store, users);
      order.verify(store).preparedProof(payment);
      order
          .verify(users)
          .state(
              proof.userId, proof.operationKey, proof.userId, "cloud-mall-pay", "token", proof.xid);
      order.verifyNoMoreInteractions();
      verify(store, never()).requestRollback(any(), any());
    }
  }

  /** 明确false保proof→GET→原Store次序，true仍pending且presence不序列化。 */
  @Test
  void explicitLedgerBooleanPreservesAbortContract() throws Exception {
    for (String variant : java.util.List.of("false", "true")) {
      setUp();
      var payment = payment();
      var proof = proof();
      var decoded = decodedAbortLedgerState(variant);
      org.junit.jupiter.api.Assertions.assertTrue(decoded.ledgerAppliedPresent);
      assertEquals(variant.equals("true"), decoded.ledgerApplied);
      org.junit.jupiter.api.Assertions.assertFalse(
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(decoded)
              .contains("ledgerAppliedPresent"));
      when(store.preparedProof(payment)).thenReturn(proof);
      when(users.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(decoded));
      if (variant.equals("false")) {
        service.requestRollback(payment);
        var order = org.mockito.Mockito.inOrder(store, users);
        order.verify(store).preparedProof(payment);
        order
            .verify(users)
            .state(
                proof.userId,
                proof.operationKey,
                proof.userId,
                "cloud-mall-pay",
                "token",
                proof.xid);
        order.verify(store).requestRollback(payment, proof);
        order.verifyNoMoreInteractions();
      } else {
        assertEquals(
            202,
            assertThrows(BizException.class, () -> service.requestRollback(payment))
                .getHttpStatus());
        verify(store, never()).requestRollback(any(), any());
      }
    }
  }

  /** 外来RootContext和初段proof失败仍先拒绝，未触及User或最终决议Store。 */
  @Test
  void inheritedContextAndLocalProofKeepEarlierPriority() {
    String originalXid = io.seata.core.context.RootContext.unbind();
    try {
      io.seata.core.context.RootContext.bind("foreign-abort-proof-xid");
      try {
        var failure = assertThrows(BizException.class, () -> service.requestRollback(payment()));
        assertEquals("PAY_TCC_CONTEXT_INVALID", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        org.mockito.Mockito.verifyNoInteractions(store, users);
      } finally {
        io.seata.core.context.RootContext.unbind();
      }
      setUp();
      var original = new BizException("PAY_ORDER_SYNC_UNKNOWN", "local-proof-pending", 202);
      when(store.preparedProof(any())).thenThrow(original);
      org.junit.jupiter.api.Assertions.assertSame(
          original, assertThrows(BizException.class, () -> service.requestRollback(payment())));
      org.mockito.Mockito.verifyNoInteractions(users);
      verify(store, never()).requestRollback(any(), any());
    } finally {
      if (originalXid != null) io.seata.core.context.RootContext.bind(originalXid);
    }
  }

  /** 真实JSON解布尔presence，不直接修改内部标志。 */
  private com.cloudmall.pay.domain.vo.UserTccStateVO decodedAbortLedgerState(String variant)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json =
        mapper.valueToTree(userState("TRYING", false, 31L));
    if (variant.equals("missing")) json.remove("ledgerApplied");
    else if (variant.equals("null")) json.putNull("ledgerApplied");
    else json.put("ledgerApplied", variant.equals("true"));
    return mapper.treeToValue(json, com.cloudmall.pay.domain.vo.UserTccStateVO.class);
  }

  private PayVO payment() {
    PayVO payment = new PayVO();
    payment.payNo = "pay-abort-test";
    payment.userId = 7L;
    payment.orderNo = "order-abort-test";
    payment.amount = new BigDecimal("10.00");
    payment.status = "PENDING";
    return payment;
  }

  private PayTccAbortProofVO proof() {
    PayTccAbortProofVO proof = new PayTccAbortProofVO();
    proof.payNo = "pay-abort-test";
    proof.userId = 7L;
    proof.orderNo = "order-abort-test";
    proof.amount = "10.00";
    proof.xid = "xid-abort-test";
    proof.operationKey = "tcc-debit:pay-abort-test";
    proof.ledgerKey = "pay-abort-test";
    proof.payBranchId = 41L;
    proof.userBranchId = 31L;
    return proof;
  }

  private com.cloudmall.pay.domain.vo.UserTccStateVO userState(
      String state, boolean ledger, Long branch) {
    return userState(state, ledger, branch, "10.00");
  }

  private com.cloudmall.pay.domain.vo.UserTccStateVO userState(
      String state, boolean ledger, Long branch, String amount) {
    com.cloudmall.pay.domain.vo.UserTccStateVO view =
        new com.cloudmall.pay.domain.vo.UserTccStateVO();
    view.userId = 7L;
    view.payNo = "pay-abort-test";
    view.orderNo = "order-abort-test";
    view.amount = amount;
    view.operationKey = "tcc-debit:pay-abort-test";
    view.ledgerKey = "pay-abort-test";
    view.xid = "xid-abort-test";
    view.branchId = branch;
    view.state = state;
    view.setLedgerApplied(ledger);
    return view;
  }
}
