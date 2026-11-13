package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.util.PayDebitModes;
import com.cloudmall.pay.util.PayRecordViews;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/** 成功、失败和回调共用的原持久支付读取、协议选择及结果映射；不持有锁或开启事务。 */
final class PayProcessingSupport {
  private PayProcessingSupport() {}

  /** 完整映射原查询结果再取首条；无记录沿原404，不增加唯一性或模式校验。 */
  static PayVO find(PayRecordMapper payRecordMapper, String payNo) {
    List<PayVO> records =
        payRecordMapper.selectByPayNo(payNo).stream().map(PayRecordViews::toView).toList();
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return records.get(0);
  }

  /** 先按原支付号读取，再按已经获得的身份核对归属，保持404先于403。 */
  static PayVO ownedForUser(PayRecordMapper payRecordMapper, String payNo, long userId) {
    PayVO payment = find(payRecordMapper, payNo);
    if (!Objects.equals(payment.userId, userId))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此支付记录", 403);
    return payment;
  }

  /** 再次读取恰一条支付及原意图，精确核验持久协议；异常或冲突不允许降级。 */
  static String paymentMode(
      PayRecordMapper payRecordMapper, PayDebitIntentMapper payDebitIntentMapper, String payNo) {
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(payNo);
    if (records.size() != 1) throw PayDebitModes.unavailable();
    String mode = records.get(0).debitMode;
    PayDebitIntentPO intent = payDebitIntentMapper.findByPayNo(payNo);
    if (PayDebitModes.TCC.equals(mode)) {
      if (intent != null) throw PayDebitModes.unavailable();
      return mode;
    }
    PayDebitModes.requireSupported(mode);
    if (intent != null) PayDebitModes.requireSupported(intent.debitMode);
    return PayDebitModes.LEGACY;
  }

  /** 只调用原协议对应编排一次，保留其结果及异常，不重试、不补偿或切换协议。 */
  static String initiate(
      PayVO payment,
      String mode,
      PayConsistencyService payConsistencyService,
      ObjectProvider<PayTccPaymentService> tccPaymentService) {
    return PayDebitModes.TCC.equals(mode)
        ? tccEntry(tccPaymentService).pay(payment)
        : payConsistencyService.initiate(payment);
  }

  /** 原TCC入口未启用时返回原503，不回落到Legacy。 */
  static PayTccPaymentService tccEntry(ObjectProvider<PayTccPaymentService> tccPaymentService) {
    PayTccPaymentService entry = tccPaymentService.getIfAvailable();
    if (entry == null) throw PayDebitModes.unavailable();
    return entry;
  }

  /** SUCCESS才重读原支付；失败/退款中/未知结果保持原错误码和HTTP状态，包括null结果。 */
  static PayVO paymentForOutcome(PayRecordMapper payRecordMapper, String payNo, String outcome) {
    if ("SUCCESS".equals(outcome)) return find(payRecordMapper, payNo);
    if ("REFUNDED".equals(outcome) || "FAILED".equals(outcome))
      throw new BizException("PAYMENT_FAILED", "订单未完成支付，余额已按账本处理", 409);
    if ("REFUND_PENDING".equals(outcome))
      throw new BizException("PAY_REFUND_PENDING", "订单已取消，余额退款处理中", 202);
    throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "扣款或订单结果待确认", 202);
  }
}
