package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("product_metric_projection_outbox")
public class ProductMetricProjectionOutboxPO {
  /** Original product_id column; nullability follows the reviewed table contract. */
  @TableField("product_id")
  public Long productId;

  /** Original metric_version column; nullability follows the reviewed table contract. */
  @TableField("metric_version")
  public Long metricVersion;

  /** Original created_at column; nullability follows the reviewed table contract. */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** Original updated_at column; nullability follows the reviewed table contract. */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** Original status column; nullability follows the reviewed table contract. */
  @TableField("status")
  public String status;

  /** Original attempts column; nullability follows the reviewed table contract. */
  @TableField("attempts")
  public Integer attempts;

  /** Original next_retry_at column; nullability follows the reviewed table contract. */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** Original lease_owner column; nullability follows the reviewed table contract. */
  @TableField("lease_owner")
  public String leaseOwner;

  /** Original lease_until column; nullability follows the reviewed table contract. */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** Original last_error column; nullability follows the reviewed table contract. */
  @TableField("last_error")
  public String lastError;
}
