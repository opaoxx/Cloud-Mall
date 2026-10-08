package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀幂等键冲突证据持久化对象；原幂等记录保持不可变。 */
@TableName("seckill_order_dedup_conflict")
public class SeckillOrderDedupConflictPO {
  /** 冲突记录主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 客户幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 冲突载荷指纹。 */
  @TableField("request_hash")
  public String requestHash;

  /** 冲突消息携带的订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 冲突消息事件号。 */
  @TableField("event_id")
  public String eventId;

  /** 原幂等记录中的订单号。 */
  @TableField("existing_order_no")
  public String existingOrderNo;

  /** 原幂等记录中的请求指纹。 */
  @TableField("existing_request_hash")
  public String existingRequestHash;

  /** 冲突类型。 */
  @TableField("conflict_type")
  public String conflictType;

  /** 结构化JSON查询视图，大数或小数可能被数据库规范化并丢精度。 */
  public String payload;

  /** 当前序列化后的完整JSON文本证据；历史null仅有旧JSON视图，不可逆向伪造原文。 */
  @TableField("raw_payload")
  public String rawPayload;

  /** 审计状态。 */
  public String status;

  /** 记录时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
