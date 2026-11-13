package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 支付入口对已确认和待恢复结果的 HTTP 契约回归。 */
class BalancePaymentContractTest {
  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void unknownOrderResultReturnsAcceptedWithoutCallingDebitAgain() {
    PayRecordMapper payRecordMapper = mock(PayRecordMapper.class);
    PayConsistencyService consistencyService = mock(PayConsistencyService.class);
    when(payRecordMapper.selectByPayNo("pay-1")).thenReturn(List.of(payment("PENDING")));
    when(consistencyService.initiate(any(PayVO.class))).thenReturn("ORDER_SYNC_UNKNOWN");
    PaySuccessController payService = service(payRecordMapper, consistencyService);

    AuthContext.set(7L, "USER");
    BizException outcome = assertThrows(BizException.class, () -> payService.success("pay-1"));

    assertEquals(202, outcome.getHttpStatus());
    assertEquals("PAY_ORDER_SYNC_UNKNOWN", outcome.getCode());
    verify(consistencyService).initiate(any(PayVO.class));
  }

  @Test
  void confirmedOrderAndLedgerReturnSuccessfulPayment() {
    PayRecordMapper payRecordMapper = mock(PayRecordMapper.class);
    PayConsistencyService consistencyService = mock(PayConsistencyService.class);
    when(payRecordMapper.selectByPayNo("pay-1"))
        .thenReturn(List.of(payment("PENDING")), List.of(payment("SUCCESS")));
    when(consistencyService.initiate(any(PayVO.class))).thenReturn("SUCCESS");
    PaySuccessController payService = service(payRecordMapper, consistencyService);

    AuthContext.set(7L, "USER");
    ApiResponse<?> result = payService.success("pay-1");

    assertEquals("SUCCESS", ((PayVO) result.data).status);
  }

  private static PaySuccessController service(
      PayRecordMapper payRecordMapper, PayConsistencyService consistencyService) {
    PaySuccessController payService =
        new PaySuccessController(
            new PaySuccessServiceImpl(
                payRecordMapper,
                mock(com.cloudmall.pay.mapper.PayDebitIntentMapper.class),
                consistencyService,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                com.cloudmall.pay.service.PayProtocolMetrics.noop(),
                new PayWriteMonitor()));
    return payService;
  }

  private static PayRecordPO payment(String status) {
    PayRecordPO payment = new PayRecordPO();
    payment.payNo = "pay-1";
    payment.orderNo = "order-1";
    payment.userId = 7L;
    payment.amount = new BigDecimal("10.00");
    payment.status = status;
    payment.debitMode = "LEGACY";
    return payment;
  }
}
