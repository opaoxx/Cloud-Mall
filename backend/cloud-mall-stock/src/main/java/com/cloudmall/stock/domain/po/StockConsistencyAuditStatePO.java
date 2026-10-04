package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 普通库存周期审计的唯一持久游标，不保存库存数量副本。 */
@TableName("stock_consistency_audit_state")
public class StockConsistencyAuditStatePO {
  /** 固定状态键 NORMAL_STOCK。 */
  @TableId(value = "state_key", type = IdType.INPUT)
  public String stateKey;

  /** 已完整观察的最大 SKU 主键。 */
  @TableField("cursor_sku_id")
  public Long cursorSkuId;

  /** 当前扫描周期起始时间。 */
  @TableField("cycle_started_at")
  public LocalDateTime cycleStartedAt;

  /** 最近一个完整扫描周期结束时间。 */
  @TableField("last_completed_at")
  public LocalDateTime lastCompletedAt;

  /** 最近一次成功批次的行数。 */
  @TableField("last_batch_count")
  public Integer lastBatchCount;

  /** 状态最近更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** 最近一次失败摘要，成功推进时清除。 */
  @TableField("last_error")
  public String lastError;
}
