package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付记录持久化对象。 */
@TableName("pay_record")
public class PayRecordPO {
  /** 支付记录主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 对外支付单号。 */
  @TableField("pay_no")
  public String payNo;

  /** 关联订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 支付用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 支付金额。 */
  public BigDecimal amount;

  /** 支付状态。 */
  public String status;

  /** 支付完成时间。 */
  @TableField("paid_at")
  public LocalDateTime paidAt;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
