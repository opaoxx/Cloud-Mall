package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀活动持久化对象。 */
@TableName("seckill_activity")
public class SeckillActivityPO {
  /** 活动主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 参与活动的 SKU。 */
  @TableField("sku_id")
  public Long skuId;

  /** 活动开始时间。 */
  @TableField("start_at")
  public LocalDateTime startAt;

  /** 活动结束时间。 */
  @TableField("end_at")
  public LocalDateTime endAt;

  /** 活动库存上限。 */
  @TableField("stock_limit")
  public Integer stockLimit;

  /** 用户限购数量。 */
  @TableField("per_user_limit")
  public Integer perUserLimit;

  /** 活动状态。 */
  public String status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
