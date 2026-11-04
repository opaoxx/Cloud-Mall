package com.cloudmall.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 已观察到受理行的八字段响应，原值为null时仍必须输出该字段。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SeckillReservationObservedVO extends SeckillReservationFactVO {
  /** 受理行中的活动ID原值，不在只读接口做数值强转。 */
  public final Object activityId;

  /** 受理行中的SKU原值，保留JDBC数值与null语义。 */
  public final Object skuId;

  /** 受理行中的用户ID原值。 */
  public final Object userId;

  /** 受理行中的原幂等键，缺值仍显示为null。 */
  public final Object idempotencyKey;

  /** 受理行中的原指纹，不在查询时补造或重算。 */
  public final Object requestHash;

  /** 在outbox查询之前观察到的原受理状态。 */
  public final Object reservationStatus;

  /** 创建已观察字段视图；只有具体outbox子类型才带eventId。 */
  public SeckillReservationObservedVO(
      String orderNo,
      String status,
      Object activityId,
      Object skuId,
      Object userId,
      Object idempotencyKey,
      Object requestHash,
      Object reservationStatus) {
    super(orderNo, status);
    this.activityId = activityId;
    this.skuId = skuId;
    this.userId = userId;
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.reservationStatus = reservationStatus;
  }
}
