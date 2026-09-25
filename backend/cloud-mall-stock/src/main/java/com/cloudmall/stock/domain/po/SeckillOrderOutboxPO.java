package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀订单事件 outbox 持久化对象。 */
@TableName("seckill_order_outbox")
public class SeckillOrderOutboxPO {
  /** 数据库主键。 */
  @TableId public Long id;
  /** 稳定事件号。 */
  @TableField("event_id") public String eventId;
  /** 原订单号。 */
  @TableField("order_no") public String orderNo;
  /** 原请求幂等键。 */
  @TableField("idempotency_key") public String idempotencyKey;
  /** 原始请求载荷。 */
  public String payload;
  /** 不可变请求指纹。 */
  @TableField("request_hash") public String requestHash;
  /** 投递状态。 */
  public String status;
  /** 投递次数。 */
  public Integer attempts;
  /** 下次重试时间。 */
  @TableField("next_retry_at") public LocalDateTime nextRetryAt;
  /** 租约持有者。 */
  @TableField("lease_owner") public String leaseOwner;
  /** 租约截止时间。 */
  @TableField("lease_until") public LocalDateTime leaseUntil;
  /** 最近错误。 */
  @TableField("last_error") public String lastError;
  /** 发布完成时间。 */
  @TableField("published_at") public LocalDateTime publishedAt;
  /** 创建时间。 */
  @TableField("created_at") public LocalDateTime createdAt;
  /** 更新时间。 */
  @TableField("updated_at") public LocalDateTime updatedAt;
}
