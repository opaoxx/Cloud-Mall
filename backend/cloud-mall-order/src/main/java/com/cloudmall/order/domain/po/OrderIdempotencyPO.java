package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订单创建幂等记录持久化对象。 */
@TableName("order_idempotency")
public class OrderIdempotencyPO {
  /** 记录主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 已创建订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
