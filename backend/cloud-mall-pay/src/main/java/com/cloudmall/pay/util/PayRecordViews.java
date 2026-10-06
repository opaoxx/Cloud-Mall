package com.cloudmall.pay.util;

import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import java.time.ZoneOffset;

/** 保持支付响应字段、金额、时间偏移和 null 语义一致的映射。 */
public final class PayRecordViews {
  private PayRecordViews() {}

  /** 将当前持久支付事实转换为既有响应格式。 */
  public static PayVO toView(PayRecordPO record) {
    PayVO payment = new PayVO();
    payment.payNo = record.payNo;
    payment.orderNo = record.orderNo;
    payment.userId = record.userId;
    payment.amount = record.amount;
    payment.status = record.status;
    if (record.paidAt != null)
      payment.paidAt = record.paidAt.atOffset(ZoneOffset.ofHours(8)).toString();
    return payment;
  }
}
