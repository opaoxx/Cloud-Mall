package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("product_online_stat")
public class ProductOnlineStatPO {
  /** Original product_id column; nullability follows the reviewed table contract. */
  @TableField("product_id")
  public Long productId;

  /** Original view_count column; nullability follows the reviewed table contract. */
  @TableField("view_count")
  public Long viewCount;

  /** Original search_count column; nullability follows the reviewed table contract. */
  @TableField("search_count")
  public Long searchCount;

  /** Original sold_count column; nullability follows the reviewed table contract. */
  @TableField("sold_count")
  public Long soldCount;

  /** Original hot_score column; nullability follows the reviewed table contract. */
  @TableField("hot_score")
  public Long hotScore;

  /** Original metric_version column; nullability follows the reviewed table contract. */
  @TableField("metric_version")
  public Long metricVersion;

  /** Original last_event_id column; nullability follows the reviewed table contract. */
  @TableField("last_event_id")
  public String lastEventId;

  /** Original created_at column; nullability follows the reviewed table contract. */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** Original updated_at column; nullability follows the reviewed table contract. */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
