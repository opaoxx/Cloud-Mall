package com.cloudmall.order.domain.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 订单摘要和详情响应。 */
public class OrderVO {
  /** 对外订单号。 */
  public String orderNo;

  /** 所属用户主键。 */
  public Long userId;

  /** 订单状态。 */
  public String status;

  /** 商品总额。 */
  public BigDecimal totalAmount;

  /** 应付金额。 */
  public BigDecimal payAmount;

  /** 收货地址快照。 */
  public String addressSnapshot;

  /** 创建时间。 */
  public String createdAt;

  /** 过期时间。 */
  public String expireAt;

  /** 订单明细。 */
  public List<OrderItemVO> items = new ArrayList<>();
}
