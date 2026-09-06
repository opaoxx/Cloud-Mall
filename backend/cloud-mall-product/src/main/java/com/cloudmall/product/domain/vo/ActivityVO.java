package com.cloudmall.product.domain.vo;

import java.time.OffsetDateTime;

/** 秒杀活动响应视图。 */
public class ActivityVO {
  /** 活动主键。 */
  public final long activityId;

  /** SKU 主键。 */
  public final long skuId;

  /** 活动开始时间。 */
  public final OffsetDateTime startAt;

  /** 活动结束时间。 */
  public final OffsetDateTime endAt;

  /** 剩余库存。 */
  public final int remainingStock;

  /** 每用户限购数量。 */
  public final int perUserLimit;

  /** 活动状态。 */
  public final String status;

  /** 创建秒杀活动响应。 */
  public ActivityVO(
      long activityId,
      long skuId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      int remainingStock,
      int perUserLimit,
      String status) {
    this.activityId = activityId;
    this.skuId = skuId;
    this.startAt = startAt;
    this.endAt = endAt;
    this.remainingStock = remainingStock;
    this.perUserLimit = perUserLimit;
    this.status = status;
  }
}
