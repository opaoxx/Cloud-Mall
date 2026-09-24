package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订单事务提交后可靠投递的 RabbitMQ 事件。 */
@TableName("order_outbox")
public class OrderOutboxPO {
  /** Outbox 内部主键。 */
  @TableId public Long id;

  /** 同一事件重试时保持不变的 ID。 */
  @TableField("event_id")
  public String eventId;

  /** 事件类型，目前使用 ORDER_TIMEOUT。 */
  @TableField("event_type")
  public String eventType;

  /** 事件关联的订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** RabbitMQ JSON envelope 的原始内容。 */
  public String payload;

  /** PENDING 或 PUBLISHED。 */
  public String status;

  /** 累计投递尝试次数。 */
  public Integer attempts;

  /** 下次允许投递的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 最近一次投递失败原因。 */
  @TableField("last_error")
  public String lastError;

  /** Publisher confirm 确认时间。 */
  @TableField("published_at")
  public LocalDateTime publishedAt;

  /** 事件写入时间，按 Asia/Shanghai 存储。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 事件最后更新时间，按 Asia/Shanghai 存储。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** 创建空的 outbox 持久化对象。 */
  public OrderOutboxPO() {}
}
