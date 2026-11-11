package com.cloudmall.order.domain.vo;

import java.math.BigDecimal;
import java.util.Objects;

/** 支付恢复使用的订单与库存动作状态投影。 */
public final class OrderPaymentStateVO {
  /** 原订单号。 */
  public final String orderNo;

  /** 原订单状态，不推断额外终态。 */
  public final String status;

  /** 原应付金额，沿公共序列化配置输出字符串。 */
  public final BigDecimal payAmount;

  /** 原订单所属用户。 */
  public final Long userId;

  /** 动作证明或既有存量兜底得到的结算状态。 */
  public final String stockActionStatus;

  /** 创建五字段投影，保持原Map.of对所有字段的非空要求。 */
  public OrderPaymentStateVO(
      String orderNo, String status, BigDecimal payAmount, Long userId, String stockActionStatus) {
    this.orderNo = Objects.requireNonNull(orderNo);
    this.status = Objects.requireNonNull(status);
    this.payAmount = Objects.requireNonNull(payAmount);
    this.userId = Objects.requireNonNull(userId);
    this.stockActionStatus = Objects.requireNonNull(stockActionStatus);
  }
}
