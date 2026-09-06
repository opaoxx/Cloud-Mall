package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单主表持久化对象。逻辑表由 Sharding-JDBC 路由到月度物理表。 */
@TableName("mall_order")
public class OrderPO {
  /** 订单内部主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 对外订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 下单用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 订单状态。 */
  public String status;

  /** 商品总额。 */
  @TableField("total_amount")
  public BigDecimal totalAmount;

  /** 应付金额。 */
  @TableField("pay_amount")
  public BigDecimal payAmount;

  /** 收货地址快照 JSON。 */
  @TableField("address_snapshot")
  public String addressSnapshot;

  /** 待支付过期时间。 */
  @TableField("expire_at")
  public LocalDateTime expireAt;

  /** 支付完成时间。 */
  @TableField("paid_at")
  public LocalDateTime paidAt;

  /** 取消时间。 */
  @TableField("cancelled_at")
  public LocalDateTime cancelledAt;

  /** 创建时间及分片键。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
