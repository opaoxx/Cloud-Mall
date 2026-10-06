package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccCancelService;
import com.cloudmall.pay.service.impl.PayTccPaymentService;
import com.cloudmall.pay.service.impl.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.PayTccRollbackProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 已确认资金的失败请求只报告当前结果，不发起第二次取消或退款。 */
class PayTccCommittedFailureTest {
  /** 原 execution 的只读来源。 */
  private final PayTccPublicStateStore states = mock(PayTccPublicStateStore.class);

  /** 全局决议入口，本测试禁止调用。 */
  private final PayTccCoordinatorService coordinator = mock(PayTccCoordinatorService.class);

  /** 订单与退款交付，本测试禁止调用。 */
  private final PayTccDeliveryService delivery = mock(PayTccDeliveryService.class);

  /** 原订单 RPC，本测试禁止调用。 */
  private final OrderClient orders = mock(OrderClient.class);

  /** 回滚证明，不能接管已经提交的资金。 */
  private final PayTccRollbackProjectionService projection =
      mock(PayTccRollbackProjectionService.class);

  /** 无 execution 取消，不接管 COMMITTED。 */
  private final PayTccCancelService cancellations = mock(PayTccCancelService.class);

  @Test
  void refundPendingHasSpecificOutcomeWithoutSideEffects() {
    assertOutcome("REFUND_PENDING", "COMMITTED", "PAY_REFUND_PENDING");
  }

  @Test
  void unsettledCommittedPaymentsRemainUnknown() {
    assertOutcome("PENDING", "COMMITTED", "PAY_ORDER_SYNC_UNKNOWN");
    assertOutcome("ORDER_SYNC_UNKNOWN", "COMMITTED", "PAY_ORDER_SYNC_UNKNOWN");
  }

  @Test
  void refundPendingDoesNotProveAnUnknownExecutionCommitted() {
    assertOutcome("REFUND_PENDING", "UNKNOWN", "PAY_ORDER_SYNC_UNKNOWN");
    assertOutcome("REFUND_PENDING", "COMMIT_REQUESTED", "PAY_ORDER_SYNC_UNKNOWN");
  }

  private void assertOutcome(String paymentStatus, String executionState, String expectedCode) {
    PayTccPaymentService service =
        new PayTccPaymentService(coordinator, delivery, states, orders, projection);
    ReflectionTestUtils.setField(service, "cancelService", cancellations);
    PayVO payment = new PayVO();
    payment.payNo = "committed-payment";
    payment.status = paymentStatus;
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.state = executionState;
    when(states.execution(payment.payNo)).thenReturn(execution);
    BizException result = assertThrows(BizException.class, () -> service.failure(payment));
    assertEquals(202, result.getHttpStatus());
    assertEquals(expectedCode, result.getCode());
    assertEquals(paymentStatus, payment.status);
    verifyNoInteractions(coordinator, delivery, orders, projection, cancellations);
  }
}
