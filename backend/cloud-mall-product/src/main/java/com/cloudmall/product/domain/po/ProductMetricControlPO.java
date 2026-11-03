package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("product_metric_control")
public class ProductMetricControlPO {
  /** Original singleton_id column; nullability follows the reviewed table contract. */
  @TableField("singleton_id")
  public Integer singletonId;

  /** Original series_id column; nullability follows the reviewed table contract. */
  @TableField("series_id")
  public String seriesId;

  /** Original revision column; nullability follows the reviewed table contract. */
  @TableField("revision")
  public Long revision;

  /** Original started_at column; nullability follows the reviewed table contract. */
  @TableField("started_at")
  public LocalDateTime startedAt;

  /** Original index_uuid column; nullability follows the reviewed table contract. */
  @TableField("index_uuid")
  public String indexUuid;

  /** Original index_ready column; nullability follows the reviewed table contract. */
  @TableField("index_ready")
  public Integer indexReady;

  /** Original updated_at column; nullability follows the reviewed table contract. */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
