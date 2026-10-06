package com.cloudmall.pay.domain.vo;

import com.cloudmall.pay.domain.po.PayTccDeliveryPO;

/** Pay TCC 交付任务内部视图。 */
public class PayTccDeliveryVO {
  /** 支付号。 */
  public String payNo;

  /** 原始全局事务标识。 */
  public String xid;

  /** 支付用户主键。 */
  public Long userId;

  /** 关联订单号。 */
  public String orderNo;

  /** 金额字符串。 */
  public String amount;

  /** 交付任务状态。 */
  public String state;

  /** 唯一退款操作键。 */
  public String refundOperationKey;

  /** 已尝试次数。 */
  public Integer attempts;

  /** 下一次重试时间。 */
  public java.time.LocalDateTime nextRetryAt;

  /** 当前租约持有者。 */
  public String leaseOwner;

  /** 当前租约到期时间。 */
  public java.time.LocalDateTime leaseUntil;

  /** 最近一次错误摘要。 */
  public String lastError;

  /** 从交付台账持久化事实构造完整 tuple 视图。 */
  public static PayTccDeliveryVO from(PayTccDeliveryPO delivery) {
    PayTccDeliveryVO view = new PayTccDeliveryVO();
    view.payNo = delivery.payNo;
    view.xid = delivery.xid;
    view.userId = delivery.userId;
    view.orderNo = delivery.orderNo;
    view.amount = delivery.amount.toPlainString();
    view.state = delivery.state;
    view.refundOperationKey = delivery.refundOperationKey;
    view.attempts = delivery.attempts;
    view.nextRetryAt = delivery.nextRetryAt;
    view.leaseOwner = delivery.leaseOwner;
    view.leaseUntil = delivery.leaseUntil;
    view.lastError = delivery.lastError;
    return view;
  }
}
