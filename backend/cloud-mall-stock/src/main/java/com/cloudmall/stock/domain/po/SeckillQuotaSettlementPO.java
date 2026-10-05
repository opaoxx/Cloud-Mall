package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 秒杀受理后的最终配额结算事实。 */
@TableName("seckill_quota_settlement")
public class SeckillQuotaSettlementPO {
  /** 结算事实主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 秒杀订单号，结算事实唯一键。 */
  @TableField("order_no")
  public String orderNo;

  /** 秒杀活动主键。 */
  @TableField("activity_id")
  public Long activityId;

  /** 活动 SKU 主键。 */
  @TableField("sku_id")
  public Long skuId;

  /** 下单用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 原受理请求幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 本次结算的配额数量。 */
  public Integer quantity;

  /** 结算状态。 */
  public String status;

  /** 单订单结算 revision。 */
  public Long revision;

  /** 当前结算操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** 最近一次 Lua 回执中的活动 Redis revision。 */
  @TableField("redis_revision")
  public Long redisRevision;

  /** 最近一次可恢复错误。 */
  @TableField("last_error")
  public String lastError;

  /** 下次恢复时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 恢复租约持有者。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 恢复租约截止时间。 */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 最后更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
