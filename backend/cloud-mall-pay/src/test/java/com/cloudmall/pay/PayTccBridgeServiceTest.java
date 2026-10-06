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
    response.ledgerApplied = ledgerApplied;
    return response;
  }
}
