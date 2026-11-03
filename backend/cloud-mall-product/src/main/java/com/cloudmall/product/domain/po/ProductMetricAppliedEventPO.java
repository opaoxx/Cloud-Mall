package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("product_metric_applied_event")
public class ProductMetricAppliedEventPO {
  /** Original event_id column; nullability follows the reviewed table contract. */
  @TableField("event_id")
  public String eventId;

  /** Original fingerprint column; nullability follows the reviewed table contract. */
  @TableField("fingerprint")
  public String fingerprint;

  /** Original event_type column; nullability follows the reviewed table contract. */
  @TableField("event_type")
  public String eventType;

  /** Original product_id column; nullability follows the reviewed table contract. */
  @TableField("product_id")
  public Long productId;

  /** Original sku_id column; nullability follows the reviewed table contract. */
  @TableField("sku_id")
  public Long skuId;

  /** Original quantity column; nullability follows the reviewed table contract. */
  @TableField("quantity")
  public Integer quantity;

  /** Original order_no column; nullability follows the reviewed table contract. */
  @TableField("order_no")
  public String orderNo;

  /** Original source_ref column; nullability follows the reviewed table contract. */
  @TableField("source_ref")
  public String sourceRef;

  /** Original source_revision column; nullability follows the reviewed table contract. */
  @TableField("source_revision")
  public Long sourceRevision;

  /** Original occurred_at column; nullability follows the reviewed table contract. */
  @TableField("occurred_at")
  public LocalDateTime occurredAt;

  /** Original applied_at column; nullability follows the reviewed table contract. */
  @TableField("applied_at")
  public LocalDateTime appliedAt;

  /** Original metric_version column; nullability follows the reviewed table contract. */
  @TableField("metric_version")
  public Long metricVersion;
}
