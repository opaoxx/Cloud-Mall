package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccBridgeConfiguration;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl;
import com.cloudmall.pay.service.impl.PayTccDispatchPersistence;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Pay TCC bridge 的一次性 POST、未知响应和入口隔离测试。 */
class PayTccBridgeServiceTest {
  /** 支付记录 Mapper。 */
  private PayRecordMapper payRecordMapper;

  /** 派发记录 Mapper。 */
  private PayTccDispatchMapper dispatchMapper;

  /** 独立事务持久化边界。 */
  private PayTccDispatchPersistence persistence;

  /** User TCC Feign 客户端。 */
  private UserTccClient userTccClient;

  /** 测试用 bridge 开关配置。 */
  private PayTccBridgeConfiguration configuration;

  /** 被测 Pay bridge。 */
  private PayTccBridgeServiceImpl service;

  /** 准备启用状态下的桥及其持久化依赖。 */
  @BeforeEach
  void setUp() {
    payRecordMapper = mock(PayRecordMapper.class);
    dispatchMapper = mock(PayTccDispatchMapper.class);
    persistence = mock(PayTccDispatchPersistence.class);
    userTccClient = mock(UserTccClient.class);
    configuration = new PayTccBridgeConfiguration();
    ReflectionTestUtils.setField(configuration, "enabled", true);
    service =
        new PayTccBridgeServiceImpl(
            payRecordMapper,
            dispatchMapper,
            persistence,
            userTccClient,
            configuration,
            "callback-token");
    RootContext.bind("xid-pay-test");
  }

  /** 清理当前线程的 Seata XID。 */
  @AfterEach
  void tearDown() {
    RootContext.unbind();
  }

  /** 在 Pay 分支注册前保存锚点不会提前派发 User。 */
  @Test
  void prepareOnlyPersistsAnchorWithoutUserCallOrPostPermission() {
    when(payRecordMapper.selectByPayNo("pay-test")).thenReturn(List.of(payment("TCC", "PENDING")));
    org.junit.jupiter.api.Assertions.assertEquals("PREPARED", service.prepare(request()).state);
    verify(persistence).prepare(any());
    verify(persistence, never()).markPostAttempted(any(), any());
    org.mockito.Mockito.verifyNoInteractions(userTccClient);
  }

  /** POST 响应丢失后恢复只能 GET，不能再次注册 User 分支。 */
  @Test
  void lostPostResponseThenRecoverDoesNotPostAgain() {
    PayRecordPO payment = payment("TCC", "PENDING");
    PayTccDispatchPO dispatch = dispatch("POST_ATTEMPTED");
    when(payRecordMapper.selectByPayNo("pay-test")).thenReturn(List.of(payment));
    when(dispatchMapper.selectByPayNo("pay-test"))
        .thenReturn(null)
        .thenReturn(dispatch)
        .thenReturn(dispatch)
        .thenReturn(dispatch)
        .thenReturn(dispatch);
    when(persistence.markPostAttempted(any(), any())).thenReturn(true);
    when(persistence.mirror(any(), any(), any(), any(), any(), any())).thenReturn(true);
    doThrow(new RuntimeException("response lost"))
        .when(userTccClient)
        .prepare(any(), any(), any(), any(), any(), any());
    service.dispatch(request());

    UserTccStateVO state = state("TRYING", 7L, false);
    when(userTccClient.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(state));
    service.recover("pay-test");

    verify(userTccClient).prepare(any(), any(), any(), any(), any(), any());
    verify(userTccClient).state(any(), any(), any(), any(), any(), any());
  }

  /** 未知状态、缺分支和确认缺账本都不能镜像为 TRY_ACCEPTED。 */
  @Test
  void invalidUserStatesAreKeptUnknown() {
    PayTccDispatchPO dispatch = dispatch("POST_ATTEMPTED");
    when(dispatchMapper.selectByPayNo("pay-test")).thenReturn(dispatch);
    when(persistence.markPostAttempted(any(), any())).thenReturn(true);
    when(persistence.mirror(any(), any(), any(), any(), any(), any())).thenReturn(true);
    when(userTccClient.prepare(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(state("UNKNOWN", 7L, false)))
        .thenReturn(ApiResponse.ok(state("TRYING", null, false)))
        .thenReturn(ApiResponse.ok(state("CONFIRMED", 7L, false)));

    ReflectionTestUtils.invokeMethod(service, "postOnce", dispatch);
    ReflectionTestUtils.invokeMethod(service, "postOnce", dispatch);
    ReflectionTestUtils.invokeMethod(service, "postOnce", dispatch);

    verify(persistence, never())
        .mirror(any(), any(), org.mockito.ArgumentMatchers.eq("TRY_ACCEPTED"), any(), any(), any());
  }

  /** LEGACY 支付或载荷不匹配时不创建派发事实。 */
  @Test
  void legacyPaymentCannotCreateTccDispatch() {
    PayRecordPO payment = payment("LEGACY", "PENDING");
    when(payRecordMapper.selectByPayNo("pay-test")).thenReturn(List.of(payment));
    when(dispatchMapper.selectByPayNo("pay-test")).thenReturn(null);

    assertThrows(BizException.class, () -> service.dispatch(request()));
    verify(persistence, never()).prepare(any());
    verify(userTccClient, never()).prepare(any(), any(), any(), any(), any(), any());
  }

  /** bridge 关闭时 dispatch 不访问支付派发和 User HTTP。 */
  @Test
  void disabledBridgeDoesNotUseHttp() {
    ReflectionTestUtils.setField(configuration, "enabled", false);

    assertThrows(BizException.class, () -> service.dispatch(request()));
    verifyNoInteractionsForDisabledPath();
  }

  /** 返回原键错误的记录须先于配置/终态/PREPARED/User访问被拒绝。 */
  @Test
  void recoverRejectsAlienRowBeforeConfigurationOrUserCalls() {
    for (boolean enabled : List.of(false, true)) {
      ReflectionTestUtils.setField(configuration, "enabled", enabled);
      for (String state :
          List.of(
              "CONFIRMED",
              "CANCELLED",
              "PREPARED",
              "POST_ATTEMPTED",
              "TRY_UNKNOWN",
              "TRY_ACCEPTED"))
        for (String mutation : List.of("foreign", "case", "space", "null")) {
          var row = dispatch(state);
          row.payNo =
              switch (mutation) {
                case "foreign" -> "other-pay";
                case "case" -> "PAY-TEST";
                case "space" -> "pay-test ";
                default -> null;
              };
          when(dispatchMapper.selectByPayNo("pay-test")).thenReturn(row);
          var before =
              org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                  () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row));
          var failure = assertThrows(BizException.class, () -> service.recover("pay-test"));
          org.junit.jupiter.api.Assertions.assertEquals(
              "PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
          org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
          org.junit.jupiter.api.Assertions.assertEquals(
              before,
              org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                  () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(row)));
          org.mockito.Mockito.verifyNoInteractions(userTccClient, persistence, payRecordMapper);
        }
    }
  }

  /** 匹配行原终态、配置和PREPARED优先级保持，缺失仍404且无副作用。 */
  @Test
  void exactAndMissingRecoverLookupsKeepEarlyReturns() {
    for (boolean enabled : List.of(false, true)) {
      ReflectionTestUtils.setField(configuration, "enabled", enabled);
      for (String state : List.of("CONFIRMED", "CANCELLED", "PREPARED")) {
        var row = dispatch(state);
        when(dispatchMapper.selectByPayNo(row.payNo)).thenReturn(row);
        if (!enabled && state.equals("PREPARED")) {
          var failure = assertThrows(BizException.class, () -> service.recover(row.payNo));
          org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_UNKNOWN", failure.getCode());
        } else {
          var result = service.recover(row.payNo);
          org.junit.jupiter.api.Assertions.assertEquals(row.payNo, result.payNo);
          org.junit.jupiter.api.Assertions.assertEquals(state, result.state);
        }
      }
    }
    when(dispatchMapper.selectByPayNo("absent-pay")).thenReturn(null);
    var missing = assertThrows(BizException.class, () -> service.recover("absent-pay"));
    org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", missing.getCode());
    org.junit.jupiter.api.Assertions.assertEquals(404, missing.getHttpStatus());
    org.mockito.Mockito.verifyNoInteractions(userTccClient, persistence, payRecordMapper);
  }

  /** 两入口的missing/null不能请求可信镜像或学习branch，POST消费后只GET。 */
  @Test
  void missingBridgeLedgerFieldsOnlyRequestUnknownMirrors() throws Exception {
    for (String userStateName : List.of("TRYING", "CANCELLED"))
      for (String variant : List.of("missing", "null"))
        for (boolean post : List.of(false, true)) {
          setUp();
          var decoded = decodedBridgeLedgerState(userStateName, variant, 7L);
          org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerApplied);
          org.junit.jupiter.api.Assertions.assertFalse(decoded.ledgerAppliedPresent);
          var row = arrangeBridgeLedgerReply(post, decoded, null);
          org.junit.jupiter.api.Assertions.assertEquals(
              "TRY_UNKNOWN", callBridgeLedgerReply(post).state);
          org.junit.jupiter.api.Assertions.assertNull(row.userBranchId);
          verify(persistence)
              .mirror(
                  org.mockito.ArgumentMatchers.same(row),
                  any(),
                  org.mockito.ArgumentMatchers.eq("TRY_UNKNOWN"),
                  org.mockito.ArgumentMatchers.isNull(),
                  org.mockito.ArgumentMatchers.eq("BizException"),
                  any());
          org.junit.jupiter.api.Assertions.assertEquals(
              "TRY_UNKNOWN", service.recover("pay-test").state);
          verify(userTccClient, org.mockito.Mockito.times(post ? 1 : 0))
              .prepare(any(), any(), any(), any(), any(), any());
          verify(userTccClient, org.mockito.Mockito.times(post ? 1 : 2))
              .state(any(), any(), any(), any(), any(), any());
        }
  }

  /** 明确false/true的原镜像与UNKNOWN早返保持，内部presence不进入JSON。 */
  @Test
  void explicitLedgerProofAndUnknownEarlyReturnKeepMappings() throws Exception {
    for (String userStateName : List.of("TRYING", "CANCELLED", "CONFIRMED", "UNKNOWN"))
      for (boolean post : List.of(false, true)) {
        setUp();
        String variant =
            userStateName.equals("UNKNOWN")
                ? "missing"
                : userStateName.equals("CONFIRMED") ? "true" : "false";
        var decoded = decodedBridgeLedgerState(userStateName, variant, 7L);
        org.junit.jupiter.api.Assertions.assertEquals(
            !userStateName.equals("UNKNOWN"), decoded.ledgerAppliedPresent);
        org.junit.jupiter.api.Assertions.assertFalse(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(decoded)
                .contains("ledgerAppliedPresent"));
        var row = arrangeBridgeLedgerReply(post, decoded, null);
        String target =
            userStateName.equals("TRYING")
                ? "TRY_ACCEPTED"
                : userStateName.equals("UNKNOWN") ? "TRY_UNKNOWN" : userStateName;
        var result = callBridgeLedgerReply(post);
        org.junit.jupiter.api.Assertions.assertEquals(target, result.state);
        if (userStateName.equals("UNKNOWN")) {
          org.junit.jupiter.api.Assertions.assertNull(result.userBranchId);
          org.junit.jupiter.api.Assertions.assertEquals(
              post ? "User POST 状态未知" : "User 状态仍未知", result.lastError);
        } else {
          org.junit.jupiter.api.Assertions.assertEquals(7L, result.userBranchId);
          org.junit.jupiter.api.Assertions.assertNull(result.lastError);
        }
        verify(persistence)
            .mirror(
                org.mockito.ArgumentMatchers.same(row),
                any(),
                org.mockito.ArgumentMatchers.eq(target),
                any(),
                any(),
                any());
      }
  }

  /** 原永久branch冲突先于缺失presence，不能吞成普通未知镜像。 */
  @Test
  void branchConflictPrecedesMissingLedgerPresence() throws Exception {
    for (boolean post : List.of(false, true)) {
      setUp();
      var row =
          arrangeBridgeLedgerReply(post, decodedBridgeLedgerState("TRYING", "missing", 9L), 7L);
      var failure = assertThrows(BizException.class, () -> callBridgeLedgerReply(post));
      org.junit.jupiter.api.Assertions.assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
      org.junit.jupiter.api.Assertions.assertEquals(409, failure.getHttpStatus());
      org.junit.jupiter.api.Assertions.assertEquals(7L, row.userBranchId);
      verify(persistence, never()).mirror(any(), any(), any(), any(), any(), any());
    }
  }

  /** 保Bridge逻辑和参数，持久化为替身；真实CAS由owned native另验。 */
  private PayTccDispatchPO arrangeBridgeLedgerReply(
      boolean post, UserTccStateVO reply, Long existingBranch) {
    var row = dispatch(post ? "PREPARED" : "POST_ATTEMPTED");
    row.userBranchId = existingBranch;
    when(dispatchMapper.selectByPayNo("pay-test")).thenReturn(row);
    when(payRecordMapper.selectByPayNo("pay-test")).thenReturn(List.of(payment("TCC", "PENDING")));
    when(persistence.markPostAttempted(any(), any())).thenReturn(true);
    when(persistence.mirror(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              row.state = invocation.getArgument(2);
              Long branch = invocation.getArgument(3);
              if (row.userBranchId == null && branch != null) row.userBranchId = branch;
              row.lastError = invocation.getArgument(4);
              return true;
            });
    when(userTccClient.prepare(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(reply));
    when(userTccClient.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(reply));
    return row;
  }

  /** 两条真实public入口，POST使用@BeforeEach提供的原XID。 */
  private com.cloudmall.pay.domain.vo.PayTccDispatchVO callBridgeLedgerReply(boolean post) {
    return post ? service.dispatch(request()) : service.recover("pay-test");
  }

  /** 实际Jackson解DTO，以原注解区分缺字段/null/明确boolean。 */
  private UserTccStateVO decodedBridgeLedgerState(String userStateName, String variant, Long branch)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json =
        mapper.valueToTree(state(userStateName, branch, false));
    if (variant.equals("missing")) json.remove("ledgerApplied");
    else if (variant.equals("null")) json.putNull("ledgerApplied");
    else json.put("ledgerApplied", variant.equals("true"));
    return mapper.treeToValue(json, UserTccStateVO.class);
  }

  private void verifyNoInteractionsForDisabledPath() {
    verify(payRecordMapper, never()).selectByPayNo(any());
    verify(dispatchMapper, never()).selectByPayNo(any());
    verify(userTccClient, never()).prepare(any(), any(), any(), any(), any(), any());
  }

  private PayTccDispatchDTO request() {
    PayTccDispatchDTO request = new PayTccDispatchDTO();
    request.payNo = "pay-test";
    request.userId = 7L;
    request.orderNo = "order-test";
    request.amount = new BigDecimal("10.00");
    return request;
  }

  private PayRecordPO payment(String mode, String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-test";
    payment.userId = 7L;
    payment.orderNo = "order-test";
    payment.amount = new BigDecimal("10.00");
    payment.debitMode = mode;
    payment.status = status;
    return payment;
  }

  private PayTccDispatchPO dispatch(String state) {
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.payNo = "pay-test";
    dispatch.xid = "xid-pay-test";
    dispatch.userId = 7L;
    dispatch.orderNo = "order-test";
    dispatch.amount = new BigDecimal("10.00");
    dispatch.operationKey = "tcc-debit:pay-test";
    dispatch.ledgerKey = "pay-test";
    dispatch.state = state;
    return dispatch;
  }

  private UserTccStateVO state(String state, Long branchId, boolean ledgerApplied) {
    UserTccStateVO response = new UserTccStateVO();
    response.userId = 7L;
    response.payNo = "pay-test";
    response.orderNo = "order-test";
    response.amount = "10.00";
    response.operationKey = "tcc-debit:pay-test";
    response.ledgerKey = "pay-test";
    response.xid = "xid-pay-test";
    response.branchId = branchId;
    response.state = state;
    response.setLedgerApplied(ledgerApplied);
    return response;
  }
}
