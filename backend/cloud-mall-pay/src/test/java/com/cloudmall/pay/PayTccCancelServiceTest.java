package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.service.impl.PayTccCancelService;
import com.cloudmall.pay.service.impl.PayTccCancelStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** TCC 主动取消的订单事实门控、租约和原子完成单元测试。 */
class PayTccCancelServiceTest {
  private static final String PAY_NO = "pay-cancel-service";
  private static final String ORDER_NO = "order-cancel-service";
  private static final Long USER_ID = 7L;
  private static final BigDecimal AMOUNT = new BigDecimal("10.00");

  @Mock private PayTccCancelStore store;
  @Mock private OrderClient orders;

  private PayTccCancelService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new PayTccCancelService(store, orders);
  }

  /** POST cancel 响应丢失后必须重新 GET，才能依据 CANCELLED+SUCCEEDED 完成。 */
  @Test
  void cancelResponseLossRequiresSecondGet() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT", "SUCCEEDED")))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "SUCCEEDED")));
    when(orders.cancel(ORDER_NO, USER_ID)).thenThrow(new IllegalStateException("response lost"));
    when(store.read(PAY_NO)).thenReturn(job);

    String state = service.reconcile(PAY_NO);

    assertEquals("REQUESTED", state);
    verify(orders).cancel(ORDER_NO, USER_ID);
    verify(orders, org.mockito.Mockito.times(2)).paymentState(ORDER_NO, USER_ID);
    verify(store).stage(eq(PAY_NO), anyString(), eq("COMPLETED"), anyString());
  }

  /** 订单已取消但库存仍处理中时只能保持 ORDER_CANCELLED。 */
  @Test
  void cancelledPendingStockDoesNotComplete() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "PENDING")));
    when(store.read(PAY_NO)).thenReturn(job);

    service.reconcile(PAY_NO);

    verify(orders, never()).cancel(anyString(), any());
    verify(store).stage(eq(PAY_NO), anyString(), eq("ORDER_CANCELLED"), eq(null));
  }

  /** 订单已支付时必须进入 CONFLICT，不得调用取消。 */
  @Test
  void paidOrderEntersConflict() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PAID", "SUCCEEDED")));
    when(store.read(PAY_NO)).thenReturn(job);

    service.reconcile(PAY_NO);

    verify(orders, never()).cancel(anyString(), any());
    verify(store).stage(eq(PAY_NO), anyString(), eq("CONFLICT"), eq(null));
  }

  /** 订单金额载荷不一致时进入 CONFLICT，不发送取消请求。 */
  @Test
  void mismatchedOrderAmountEntersConflict() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    ORDER_NO, "PENDING_PAYMENT", new BigDecimal("11.00"), USER_ID, "SUCCEEDED")));
    when(store.read(PAY_NO)).thenReturn(job);

    service.reconcile(PAY_NO);

    verify(orders, never()).cancel(anyString(), any());
    verify(store).stage(eq(PAY_NO), anyString(), eq("CONFLICT"), eq("PAY_TCC_CANCEL_CONFLICT"));
  }

  /** 初始 payment-state 不可用时保持未知，不发送 POST cancel。 */
  @Test
  void unavailableInitialGetDoesNotPostCancel() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID)).thenReturn(null);
    when(store.read(PAY_NO)).thenReturn(job);

    service.reconcile(PAY_NO);

    verify(orders, never()).cancel(anyString(), any());
    verify(store)
        .stage(eq(PAY_NO), anyString(), eq("CANCEL_UNKNOWN"), eq("PAY_ORDER_SYNC_UNKNOWN"));
  }

  /** 取消恢复未取得 lease 时不能调用订单 RPC。 */
  @Test
  void failedClaimDoesNotCallRpc() {
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(null);
    when(store.read(PAY_NO)).thenReturn(null);

    assertEquals("CANCEL_UNKNOWN", service.reconcile(PAY_NO));

    verify(orders, never()).paymentState(anyString(), any());
    verify(orders, never()).cancel(anyString(), any());
  }

  /** 已存在但本实例未领取 lease 时同样不能发送 RPC。 */
  @Test
  void unclaimedLeaseDoesNotCallRpc() {
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(null);
    PayTccCancelPO existing = job("CANCEL_UNKNOWN");
    when(store.read(PAY_NO)).thenReturn(existing);

    assertEquals("CANCEL_UNKNOWN", service.reconcile(PAY_NO));

    verify(orders, never()).paymentState(anyString(), any());
    verify(orders, never()).cancel(anyString(), any());
  }

  /** 已 CANCELLED 且库存已成功时只需 GET 证明即可请求 COMPLETED。 */
  @Test
  void completedRequiresOnlyFinalGetEvidence() {
    PayTccCancelPO job = job("ORDER_CANCELLED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("CANCELLED", "SUCCEEDED")));
    when(store.read(PAY_NO)).thenReturn(job);

    service.reconcile(PAY_NO);

    verify(orders).paymentState(ORDER_NO, USER_ID);
    verify(orders, never()).cancel(anyString(), any());
    verify(store).stage(eq(PAY_NO), anyString(), eq("COMPLETED"), eq(null));
  }

  @Test
  void pendingOrderWithConflictingStockMustNotBeCancelled() {
    PayTccCancelPO job = job("REQUESTED");
    when(store.claimRecovery(eq(PAY_NO), anyString())).thenReturn(job);
    when(orders.paymentState(ORDER_NO, USER_ID))
        .thenReturn(ApiResponse.ok(order("PENDING_PAYMENT", "CONFLICT")));
    when(store.read(PAY_NO)).thenReturn(job);
    service.reconcile(PAY_NO);
    verify(orders, never()).cancel(anyString(), any());
    verify(store).stage(eq(PAY_NO), anyString(), eq("CONFLICT"), eq(null));
  }

  private PayTccCancelPO job(String state) {
    PayTccCancelPO job = new PayTccCancelPO();
    job.payNo = PAY_NO;
    job.userId = USER_ID;
    job.orderNo = ORDER_NO;
    job.amount = AMOUNT;
    job.state = state;
    job.attempts = 1;
    job.leaseOwner = "test-token";
    job.leaseUntil = LocalDateTime.now().plusMinutes(1);
    return job;
  }

  private OrderClient.OrderView order(String status, String stockActionStatus) {
    return new OrderClient.OrderView(ORDER_NO, status, AMOUNT, USER_ID, stockActionStatus);
  }
}
