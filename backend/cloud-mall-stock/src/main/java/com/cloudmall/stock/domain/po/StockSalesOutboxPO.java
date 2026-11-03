package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Persistent CM21 row; identity and counters are never inferred from legacy statistics. */
@TableName("stock_sales_outbox")
public class StockSalesOutboxPO {
  /** Original event_id column; nullability follows the reviewed table contract. */
  @TableField("event_id")
  public String eventId;

  /** Original product_id column; nullability follows the reviewed table contract. */
  @TableField("product_id")
  public Long productId;

  /** Original event_type column; nullability follows the reviewed table contract. */
  @TableField("event_type")
  public String eventType;

  /** Original fingerprint column; nullability follows the reviewed table contract. */
  @TableField("fingerprint")
  public String fingerprint;

  /** Original payload_json column; nullability follows the reviewed table contract. */
  @TableField("payload_json")
  public String payloadJson;

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

  /** Original published_at column; nullability follows the reviewed table contract. */
  @TableField("published_at")
  public LocalDateTime publishedAt;

  /** Original last_error column; nullability follows the reviewed table contract. */
  @TableField("last_error")
  public String lastError;

  /** Original created_at column; nullability follows the reviewed table contract. */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** Original updated_at column; nullability follows the reviewed table contract. */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** Original sku_id column; nullability follows the reviewed table contract. */
  @TableField("sku_id")
  public Long skuId;

  /** Original order_no column; nullability follows the reviewed table contract. */
  @TableField("order_no")
  public String orderNo;

  /** Original quantity column; nullability follows the reviewed table contract. */
  @TableField("quantity")
  public Integer quantity;

  /** Original source_ref column; nullability follows the reviewed table contract. */
  @TableField("source_ref")
  public String sourceRef;

  /** Original source_revision column; nullability follows the reviewed table contract. */
  @TableField("source_revision")
  public Long sourceRevision;
}
