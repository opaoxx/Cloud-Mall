package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 商品 ES 存量重建的持久游标和租约。 */
@TableName("product_index_rebuild_job")
public class ProductIndexRebuildJobPO {
  /** 固定任务标识。 */
  @TableId(value = "job_id", type = IdType.INPUT)
  public String jobId;

  /** 对应的 ES 索引。 */
  @TableField("target_index")
  public String targetIndex;

  /** ES 索引丢失后递增的重建代次。 */
  @TableField("index_generation")
  public Integer indexGeneration;

  /** 已成功 Bulk 的最大商品主键。 */
  @TableField("cursor_product_id")
  public Long cursorProductId;

  /** 单次读取和 Bulk 的最大商品数。 */
  @TableField("batch_size")
  public Integer batchSize;

  /** 每分钟允许重建的最多商品数。 */
  @TableField("max_events_per_minute")
  public Integer maxEventsPerMinute;

  /** 最近一次成功批次的商品数。 */
  @TableField("last_batch_count")
  public Integer lastBatchCount;

  /** 最近一次 Bulk 尝试时间。 */
  @TableField("last_bulk_at")
  public LocalDateTime lastBulkAt;

  /** PENDING、RUNNING、PAUSED、COMPLETED 或 FAILED。 */
  public String status;

  /** 当前处理实例租约标识。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 当前处理实例租约到期时间。 */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** 最近错误的有界摘要。 */
  @TableField("last_error")
  public String lastError;

  /** 数据库本地创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 数据库本地更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
