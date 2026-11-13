package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCallbackService;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.Entry;
import com.cloudmall.pay.util.PayDebitModes;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 回调共享临界区和原处理顺序；claim/complete由既有独立事务代理负责。 */
@Service
public class PayCallbackServiceImpl implements PayCallbackService {
  /** 原支付归属、模式与响应读取入口。 */
  private final PayRecordMapper payRecordMapper;

  /** 原意图模式核验入口。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 原Legacy失败授权的独立事务代理。 */
  private final PayLegacyFailureService legacyFailureService;

  /** 原Legacy成功及恢复编排。 */
  private final PayConsistencyService payConsistencyService;

  /** 按原开关提供的TCC入口。 */
  private final ObjectProvider<PayTccPaymentService> tccPaymentService;

  /** 在业务副作用前绑定、业务确认后完成的独立事务代理。 */
  private final PayCallbackReceiptService callbackReceipts;

  /** 原回调路由指标及记录时点。 */
  private final PayProtocolMetrics metrics;

  /** 与创建、模拟成功共用的操作监视器。 */
  private final PayWriteMonitor writeMonitor;

  /** 原内部回调凭证配置，校验仍在临界区内。 */
  private final String callbackToken;

  /** 注入原回调依赖、共享监视器和未改变键名/默认值的内部凭证。 */
  public PayCallbackServiceImpl(
      PayRecordMapper payRecordMapper,
      PayDebitIntentMapper payDebitIntentMapper,
      PayLegacyFailureService legacyFailureService,
      PayConsistencyService payConsistencyService,
      ObjectProvider<PayTccPaymentService> tccPaymentService,
      PayCallbackReceiptService callbackReceipts,
      PayProtocolMetrics metrics,
      PayWriteMonitor writeMonitor,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.payRecordMapper = payRecordMapper;
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.legacyFailureService = legacyFailureService;
    this.payConsistencyService = payConsistencyService;
    this.tccPaymentService = tccPaymentService;
    this.callbackReceipts = callbackReceipts;
    this.metrics = metrics;
    this.writeMonitor = writeMonitor;
    this.callbackToken = callbackToken;
  }

  /** {@inheritDoc} */
  @Override
  public PayVO handle(String token, PayCallbackDTO callback) {
    synchronized (writeMonitor) {
      // 1. 先凭证后参数，保留回调标识原值或回退到支付号的规则。
      if (!Objects.equals(callbackToken, token))
        throw new BizException(ErrorCodes.FORBIDDEN, "回调凭证无效", 403);
      if (callback == null
          || callback.payNo == null
          || callback.payNo.isBlank()
          || callback.amount == null
          || callback.userId == null) {
        throw new BizException(ErrorCodes.INVALID, "回调参数不完整", 400);
      }
      String callbackId =
          callback.callbackId == null || callback.callbackId.isBlank()
              ? callback.payNo
              : callback.callbackId;
      // 2. 使用载荷身份读取归属，先核验持久协议再核对金额并记录指标。
      PayVO payment =
          PayProcessingSupport.ownedForUser(payRecordMapper, callback.payNo, callback.userId);
      String mode =
          PayProcessingSupport.paymentMode(payRecordMapper, payDebitIntentMapper, callback.payNo);
      if (payment.amount.compareTo(callback.amount) != 0)
        throw new BizException(ErrorCodes.PAY_MISMATCH, "回调金额不一致", 409);
      metrics.route(PayProtocolMetrics.mode(mode), Entry.CALLBACK);
      // 3. claim独立提交后才进入终态判断与原协议编排，异常/未知不得complete。
      callbackReceipts.claim(payment, callback, callbackId);
      if (callback.success) {
        if ("FAILED".equals(payment.status) || "REFUNDED".equals(payment.status))
          throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
        if (!"SUCCESS".equals(payment.status)) {
          String outcome =
              PayProcessingSupport.initiate(
                  payment, mode, payConsistencyService, tccPaymentService);
          if (!"SUCCESS".equals(outcome))
            return PayProcessingSupport.paymentForOutcome(payRecordMapper, payment.payNo, outcome);
        }
      } else if (PayDebitModes.TCC.equals(mode)) {
        PayProcessingSupport.tccEntry(tccPaymentService).failure(payment);
      } else {
        payment = legacyFailureService.fail(payment);
      }
      // 4. 原complete独立提交后才重读响应；后续读失败不回退已持久事实。
      callbackReceipts.complete(payment, callback, callbackId);
      return PayProcessingSupport.find(payRecordMapper, payment.payNo);
    }
  }
}
