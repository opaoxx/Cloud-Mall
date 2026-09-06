package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀库存预扣记录持久化对象。 */
@TableName("seckill_reservation")
public class SeckillReservationPO {
  /** 记录主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 活动主键。 */
  @TableField("activity_id")
  public Long activityId;

  /** SKU 主键。 */
  @TableField("sku_id")
  public Long skuId;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 生成的订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 请求幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 预扣状态。 */
  public String status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
