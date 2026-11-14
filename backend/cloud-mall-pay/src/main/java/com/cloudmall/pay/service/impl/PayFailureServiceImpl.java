package com.cloudmall.pay.service.impl;

import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayFailureService;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.Entry;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import com.cloudmall.pay.util.PayDebitModes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 原失败入口的无外层事务编排；事务和状态规则由既有Legacy/TCC服务负责。 */
@Service
public class PayFailureServiceImpl implements PayFailureService {
  /** 原支付首读及协议次读入口。 */
  private final PayRecordMapper payRecordMapper;

  /** 原持久意图协议核验入口。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 在独立事务内锁定当前事实并授权失败的既有代理。 */
  private final PayLegacyFailureService legacyFailureService;

  /** 仅在TCC模式提供的既有失败入口。 */
  private final ObjectProvider<PayTccPaymentService> tccPaymentService;

  /** 保留原MOCK_FAIL路由指标及其记录时点。 */
  private final PayProtocolMetrics metrics;

  /** 注入原协议读取、失败授权和观察依赖，不引入共享监视器。 */
  public PayFailureServiceImpl(
      PayRecordMapper payRecordMapper,
      PayDebitIntentMapper payDebitIntentMapper,
      PayLegacyFailureService legacyFailureService,
      ObjectProvider<PayTccPaymentService> tccPaymentService,
      PayProtocolMetrics metrics) {
    this.payRecordMapper = payRecordMapper;
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.legacyFailureService = legacyFailureService;
    this.tccPaymentService = tccPaymentService;
    this.metrics = metrics;
  }

  /** {@inheritDoc} */
  @Override
  public PayVO fail(String payNo, long userId) {
    // 1. 读取支付归属，再按原次读及意图核验持久协议，保留指标顺序。
    PayVO payment = PayProcessingSupport.ownedForUser(payRecordMapper, payNo, userId);
    String mode = PayProcessingSupport.paymentMode(payRecordMapper, payDebitIntentMapper, payNo);
    metrics.route(PayProtocolMetrics.mode(mode), Entry.MOCK_FAIL);
    if (PayDebitModes.TCC.equals(mode)) {
      // 2. TCC在原入口暂停外层事务；直接返回其更新后的原视图，不重读旧快照。
      PayProcessingSupport.tccEntry(tccPaymentService).failure(payment);
      return payment;
    }
    // 3. Legacy由原独立当前读事务授权，返回其实际结果，不用外层视图推断无意图。
    return legacyFailureService.fail(payment);
  }
}
