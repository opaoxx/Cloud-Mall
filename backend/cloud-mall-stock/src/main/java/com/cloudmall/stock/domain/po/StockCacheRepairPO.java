package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 普通库存 MySQL 事实到 Redis 投影的持久修复记录。 */
@TableName("stock_cache_repair")
public class StockCacheRepairPO {
  /** 修复记录主键。 */
  @TableId public Long id;

  /** SKU 主键。 */
  @TableField("sku_id") public Long skuId;

  /** 对应来源版本的可用量。 */
  @TableField("target_available") public Integer targetAvailable;

  /** 库存事实表的递增版本。 */
  @TableField("source_version") public Integer sourceVersion;

  /** PENDING、APPLIED 或 STALE。 */
  public String status;

  /** 已尝试投影的次数。 */
  public Integer attempts;

  /** 下次允许重试的时间。 */
  @TableField("next_retry_at") public LocalDateTime nextRetryAt;

  /** 当前认领修复的实例标识。 */
  @TableField("lease_owner") public String leaseOwner;

  /** 修复租约的过期时间。 */
  @TableField("lease_until") public LocalDateTime leaseUntil;

  /** 最近的 Redis 或数据库错误。 */
  @TableField("last_error") public String lastError;

  /** 记录创建时间。 */
  @TableField("created_at") public LocalDateTime createdAt;

  /** 记录更新时间。 */
  @TableField("updated_at") public LocalDateTime updatedAt;

  /** 创建空的库存缓存修复对象。 */
  public StockCacheRepairPO() {}
}
