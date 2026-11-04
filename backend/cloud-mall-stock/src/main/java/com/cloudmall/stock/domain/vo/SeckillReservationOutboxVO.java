package com.cloudmall.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 已观察到outbox行的九字段响应，eventId缺值与outbox不存在明确区分。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class SeckillReservationOutboxVO extends SeckillReservationObservedVO {
  /** 原outbox事件标识；行存在但字段缺失时明确输出null。 */
  public final Object eventId;

  /** 创建受理与outbox观察结果，不规范化或隐藏原始空值。 */
  public SeckillReservationOutboxVO(
      String orderNo,
      String status,
      Object activityId,
      Object skuId,
      Object userId,
      Object idempotencyKey,
      Object requestHash,
      Object reservationStatus,
      Object eventId) {
    super(
        orderNo, status, activityId, skuId, userId, idempotencyKey, requestHash, reservationStatus);
    this.eventId = eventId;
  }
}
