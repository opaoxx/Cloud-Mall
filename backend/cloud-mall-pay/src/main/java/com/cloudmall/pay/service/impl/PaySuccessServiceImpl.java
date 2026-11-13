package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.Entry;
import com.cloudmall.pay.service.PaySuccessService;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 模拟成功业务，保留原共享锁、无新增事务及持久协议优先于终态的顺序。 */
@Service
public class PaySuccessServiceImpl implements PaySuccessService {
  /** 原支付记录读取入口。 */
  private final PayRecordMapper payRecordMapper;

  /** 原Legacy意图读取入口。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 既有Legacy资金及订单一致性编排。 */
  private final PayConsistencyService payConsistencyService;

  /** 按原开关提供的TCC业务入口。 */
  private final ObjectProvider<PayTccPaymentService> tccPaymentService;

  /** 原协议路由指标，在终态判断之前记录。 */
  private final PayProtocolMetrics metrics;

  /** 与创建及回调共用的本地操作监视器。 */
  private final PayWriteMonitor writeMonitor;

  /** 注入原持久读取、协议编排、观察组件与共享监视器。 */
  public PaySuccessServiceImpl(
      PayRecordMapper payRecordMapper,
      PayDebitIntentMapper payDebitIntentMapper,
      PayConsistencyService payConsistencyService,
      ObjectProvider<PayTccPaymentService> tccPaymentService,
      PayProtocolMetrics metrics,
      PayWriteMonitor writeMonitor) {
    this.payRecordMapper = payRecordMapper;
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.payConsistencyService = payConsistencyService;
    this.tccPaymentService = tccPaymentService;
    this.metrics = metrics;
    this.writeMonitor = writeMonitor;
  }

  /** {@inheritDoc} */
  @Override
  public PayVO succeed(String payNo, LongSupplier userIdSupplier) {
    synchronized (writeMonitor) {
      // 1. 锁内读取身份和原支付归属，随后再次读取持久协议与意图。
      PayVO payment =
          PayProcessingSupport.ownedForUser(payRecordMapper, payNo, userIdSupplier.getAsLong());
      String mode = PayProcessingSupport.paymentMode(payRecordMapper, payDebitIntentMapper, payNo);
      // 2. 按原位置记录指标，终态仍先通过协议核验再返回或拒绝。
      metrics.route(PayProtocolMetrics.mode(mode), Entry.MOCK_SUCCESS);
      if ("SUCCESS".equals(payment.status)) return payment;
      if ("FAILED".equals(payment.status) || "REFUNDED".equals(payment.status))
        throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
      // 3. 只进入原协议编排，并沿用原成功重读及未决/失败响应。
      String outcome =
          PayProcessingSupport.initiate(payment, mode, payConsistencyService, tccPaymentService);
      return PayProcessingSupport.paymentForOutcome(payRecordMapper, payNo, outcome);
    }
  }
}
