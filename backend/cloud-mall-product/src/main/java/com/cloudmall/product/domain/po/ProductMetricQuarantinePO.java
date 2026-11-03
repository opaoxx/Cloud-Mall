package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("product_metric_quarantine")
public class ProductMetricQuarantinePO {
  /** Original quarantine_id column; nullability follows the reviewed table contract. */
  @TableField("quarantine_id")
  public String quarantineId;

  /** Original queue_name column; nullability follows the reviewed table contract. */
  @TableField("queue_name")
  public String queueName;

  /** Original payload_sha256 column; nullability follows the reviewed table contract. */
  @TableField("payload_sha256")
  public String payloadSha256;

  /** Original payload_size column; nullability follows the reviewed table contract. */
  @TableField("payload_size")
  public Long payloadSize;

  /** Original raw_prefix column; nullability follows the reviewed table contract. */
  @TableField("raw_prefix")
  public byte[] rawPrefix;

  /** Original raw_complete column; nullability follows the reviewed table contract. */
  @TableField("raw_complete")
  public Integer rawComplete;

  /** Original event_id column; nullability follows the reviewed table contract. */
  @TableField("event_id")
  public String eventId;

  /** Original reason column; nullability follows the reviewed table contract. */
  @TableField("reason")
  public String reason;

  /** Original status column; nullability follows the reviewed table contract. */
  @TableField("status")
  public String status;

  /** Original created_at column; nullability follows the reviewed table contract. */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
