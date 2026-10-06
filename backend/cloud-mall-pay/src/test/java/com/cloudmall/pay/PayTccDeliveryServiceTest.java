package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.impl.PayTccDeliveryServiceImpl;
import com.cloudmall.pay.service.impl.PayTccDeliveryStore;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Pay TCC 交付服务的证据门控、订单同步和唯一退款单元测试。 */
class PayTccDeliveryServiceTest {
  private static final Long USER_ID = 7L;
  private static final String PAY_NO = "pay-delivery-test";
  private static final String ORDER_NO = "order-delivery-test";
  private static final String XID = "xid-delivery-test";
  private static final BigDecimal AMOUNT = new BigDecimal("12.34");
  private static final String REFUND_KEY = "refund:" + PAY_NO;

  @Mock private PayTccDeliveryStore store;
  @Mock private UserTccClient tcc;
  @Mock private UserClient users;
  @Mock private OrderClient orders;

  private AutoCloseable mocks;
  private PayTccDeliveryServiceImpl service;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    RootContext.unbind();
    service = new PayTccDeliveryServiceImpl(store, tcc, users, orders, "callback-token");
  }

  @AfterEach
  void tearDown() throws Exception {
    RootContext.unbind();
    mocks.close();
  }

  /** 未确认 execution 不建立交付台账，也不能触发任一远程调用。 */
  @Test
  void uncommittedExecutionDoesNotCallRpc() {
    when(store.ensure(PAY_NO)).thenReturn(null);

    assertNotNull(service);
    service.deliver(PAY_NO);

    verifyNoInteractions(tcc, users, orders);
    verify(store, never()).claim(any(), any());
  }

  /** User 当前状态 UNKNOWN 时必须停止在资金证据门控之前。 */
  @Test
  void userUnknownBlocksOrderAndRefund() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    UserTccStateVO unknown = userState("UNKNOWN");
    when(tcc.state(any(), any(), any(), any(), any(), any())).thenReturn(ApiResponse.ok(unknown));

    service.deliver(PAY_NO);

    verifyNoInteractions(orders);
    verify(users, never()).refund(any(), any(), any());
  }

  /** 订单已支付但库存动作未知时不能成功交付，也不能退款。 */
  @Test
  void paidWithUnknownStockDoesNotSucceedOrRefund() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PAID", "UNKNOWN")));

    service.deliver(PAY_NO);

    verify(orders, never()).paid(any(), any());
    verify(users, never()).refund(any(), any(), any());
  }

  /** 已存在退款事实时，仍为待支付订单也不能再次 paid。 */
  @Test
  void existingRefundOperationBlocksPendingOrderPaid() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    prepareConfirmedMoney();
    when(users.operation(USER_ID, PAY_NO, USER_ID))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    PAY_NO, "DEBIT", "APPLIED", "12.34", REFUND_KEY)));
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT", "SUCCEEDED")));

    service.deliver(PAY_NO);

    verify(orders, never()).paid(any(), any());
  }

  /** 订单取消但库存动作未知时不允许退款。 */
  @Test
  void cancelledWithUnknownStockDoesNotRefund() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "UNKNOWN")));

    service.deliver(PAY_NO);

    verify(users, never()).refund(any(), any(), any());
  }

  /** REFUND_PENDING 只能用固定完整退款载荷 POST，再以同键读取账本事实。 */
  @Test
  void refundPendingPostsFixedPayloadThenReadsSameKey() {
    PayTccDeliveryPO job = job("REFUND_PENDING");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "SUCCEEDED")));
    when(users.refund(
            USER_ID,
            USER_ID,
            new UserClient.RefundRequest(REFUND_KEY, PAY_NO, PAY_NO, ORDER_NO, AMOUNT)))
        .thenReturn(ApiResponse.ok(new UserClient.BalanceView(REFUND_KEY, "APPLIED", "100.00")));
    when(users.operation(USER_ID, REFUND_KEY, USER_ID))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    REFUND_KEY, "REFUND", "APPLIED", "12.34", REFUND_KEY)));

    service.deliver(PAY_NO);

    verify(users)
        .refund(
            USER_ID,
            USER_ID,
            new UserClient.RefundRequest(REFUND_KEY, PAY_NO, PAY_NO, ORDER_NO, AMOUNT));
    verify(users).operation(USER_ID, REFUND_KEY, USER_ID);
  }

  /** 退款 POST 响应未知时不能用已有部分退款事实直接判定已退款。 */
  @Test
  void refundPostUnknownDoesNotReadPartialRefundAsTerminal() {
    PayTccDeliveryPO job = job("REFUND_PENDING");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "SUCCEEDED")));
    doThrow(new IllegalStateException("response lost"))
        .when(users)
        .refund(
            USER_ID,
            USER_ID,
            new UserClient.RefundRequest(REFUND_KEY, PAY_NO, PAY_NO, ORDER_NO, AMOUNT));

    service.deliver(PAY_NO);

    verify(users, never()).operation(USER_ID, REFUND_KEY, USER_ID);
  }

  /** 订单载荷不一致时禁止 paid。 */
  @Test
  void mismatchedOrderPayloadDoesNotCallPaid() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    ORDER_NO, "PENDING_PAYMENT", new BigDecimal("99.99"), USER_ID, "SUCCEEDED")));

    service.deliver(PAY_NO);

    verify(orders, never()).paid(any(), any());
  }

  /** 旧 token 无法 stage 时不能发出订单 paid。 */
  @Test
  void staleStageTokenDoesNotCallPaid() {
    PayTccDeliveryPO job = job("READY");
    prepareJob(job);
    prepareConfirmedMoney();
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT", "SUCCEEDED")));
    when(store.stage(eq(PAY_NO), any(), eq("ORDER_SYNC_UNKNOWN"))).thenReturn(false);

    service.deliver(PAY_NO);

    verify(orders, never()).paid(any(), any());
  }

  private PayTccDeliveryPO job(String state) {
    PayTccDeliveryPO job = new PayTccDeliveryPO();
    job.payNo = PAY_NO;
    job.xid = XID;
    job.userId = USER_ID;
    job.orderNo = ORDER_NO;
    job.amount = AMOUNT;
    job.state = state;
    job.refundOperationKey = REFUND_KEY;
    job.attempts = 1;
    return job;
  }

  private void prepareJob(PayTccDeliveryPO job) {
    when(store.ensure(PAY_NO)).thenReturn(job);
    when(store.claim(eq(PAY_NO), any())).thenReturn(job);
    when(store.stage(eq(PAY_NO), any(), any())).thenReturn(true);
    when(store.read(PAY_NO)).thenReturn(job);
    when(store.proof(job)).thenReturn(dispatch());
  }

  private PayTccDispatchVO dispatch() {
    PayTccDispatchVO dispatch = new PayTccDispatchVO();
    dispatch.payNo = PAY_NO;
    dispatch.userId = USER_ID;
    dispatch.orderNo = ORDER_NO;
    dispatch.amount = AMOUNT.toPlainString();
    dispatch.operationKey = "tcc-debit:" + PAY_NO;
    dispatch.ledgerKey = PAY_NO;
    dispatch.xid = XID;
    dispatch.userBranchId = 9L;
    dispatch.state = "CONFIRMED";
    return dispatch;
  }

  private void prepareConfirmedMoney() {
    when(tcc.state(
            USER_ID, "tcc-debit:" + PAY_NO, USER_ID, "cloud-mall-pay", "callback-token", XID))
        .thenReturn(ApiResponse.ok(userState("CONFIRMED")));
    when(users.operation(USER_ID, PAY_NO, USER_ID))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(PAY_NO, "DEBIT", "APPLIED", "12.34", null)));
  }

  private UserTccStateVO userState(String state) {
    UserTccStateVO view = new UserTccStateVO();
    view.userId = USER_ID;
    view.payNo = PAY_NO;
    view.orderNo = ORDER_NO;
    view.amount = AMOUNT.toPlainString();
    view.operationKey = "tcc-debit:" + PAY_NO;
    view.ledgerKey = PAY_NO;
    view.xid = XID;
    view.branchId = 9L;
    view.state = state;
    view.ledgerApplied = "CONFIRMED".equals(state);
    return view;
  }

  private OrderClient.OrderView order(String state, String stockActionStatus) {
    return new OrderClient.OrderView(ORDER_NO, state, AMOUNT, USER_ID, stockActionStatus);
  }
}
