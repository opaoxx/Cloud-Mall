package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀订单消费幂等表持久化对象。 */
@TableName("seckill_order_dedup")
public class SeckillOrderDedupPO {
  /** 记录主键。 */
  @TableId(type = IdType.INPUT) public Long id;
  /** 用户主键。 */
  @TableField("user_id") public Long userId;
  /** 客户幂等键。 */
  @TableField("idempotency_key") public String idempotencyKey;
  /** 不可变请求指纹。 */
  @TableField("request_hash") public String requestHash;
  /** 原始秒杀订单号。 */
  @TableField("order_no") public String orderNo;
  /** 消费状态。 */
  public String status;
  /** 原始事件号。 */
  @TableField("event_id") public String eventId;
  /** 创建时间。 */
  @TableField("created_at") public LocalDateTime createdAt;
  /** 更新时间。 */
  @TableField("updated_at") public LocalDateTime updatedAt;
}
