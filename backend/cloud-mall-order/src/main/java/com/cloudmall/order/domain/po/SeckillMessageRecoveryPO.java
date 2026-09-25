package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀死信原始载荷恢复表持久化对象。 */
@TableName("seckill_message_recovery")
public class SeckillMessageRecoveryPO {
  /** 记录主键。 */
  @TableId(type = IdType.INPUT) public Long id;
  /** 稳定事件号。 */
  @TableField("event_id") public String eventId;
  /** 请求指纹。 */
  @TableField("request_hash") public String requestHash;
  /** 原始 JSON 载荷。 */
  public String payload;
  /** 恢复状态。 */
  public String status;
  /** 已尝试次数。 */
  public Integer attempts;
  /** 下次恢复时间。 */
  @TableField("next_retry_at") public LocalDateTime nextRetryAt;
  /** 租约持有者。 */
  @TableField("lease_owner") public String leaseOwner;
  /** 租约截止时间。 */
  @TableField("lease_until") public LocalDateTime leaseUntil;
  /** 最近错误。 */
  @TableField("last_error") public String lastError;
  /** 创建时间。 */
  @TableField("created_at") public LocalDateTime createdAt;
  /** 更新时间。 */
  @TableField("updated_at") public LocalDateTime updatedAt;
}
