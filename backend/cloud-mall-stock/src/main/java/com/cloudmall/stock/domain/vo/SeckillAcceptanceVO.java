package com.cloudmall.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** 秒杀受理、确定拒绝或结果待恢复的响应，保留原订单和事件身份。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SeckillAcceptanceVO {
  /** 受理事实已确认时为true；确定拒绝和待恢复时均为false。 */
  public final boolean accepted;

  /** 原持久认领的订单号。 */
  public final String orderNo;

  /** 由原订单号确定的稳定事件标识。 */
  public final String eventId;

  /** 原受理状态或RECOVERY_REQUIRED，不是订单最终状态。 */
  public final String reservationStatus;

  /** 成功或待恢复时的原兼容订单状态；确定拒绝不输出。 */
  public final String status;

  /** 待恢复分支的固定诊断原因；成功与确定拒绝不输出。 */
  public final String reason;

  private SeckillAcceptanceVO(
      boolean accepted,
      String orderNo,
      String eventId,
      String reservationStatus,
      String status,
      String reason) {
    this.accepted = accepted;
    this.orderNo = Objects.requireNonNull(orderNo);
    this.eventId = Objects.requireNonNull(eventId);
    this.reservationStatus = Objects.requireNonNull(reservationStatus);
    this.status = status;
    this.reason = reason;
  }

  /** 构造持久受理成功响应，保留原PENDING_PAYMENT字段。 */
  public static SeckillAcceptanceVO accepted(
      String orderNo, String reservationStatus, String eventId) {
    return new SeckillAcceptanceVO(
        true, orderNo, eventId, reservationStatus, "PENDING_PAYMENT", null);
  }

  /** 构造已有拒绝事实的重放响应，不新增status或reason字段。 */
  public static SeckillAcceptanceVO rejected(
      String orderNo, String reservationStatus, String eventId) {
    return new SeckillAcceptanceVO(false, orderNo, eventId, reservationStatus, null, null);
  }

  /** 构造待恢复响应；原因仍按原Map.of规则禁止null。 */
  public static SeckillAcceptanceVO recoveryRequired(
      String orderNo, String eventId, String reason) {
    return new SeckillAcceptanceVO(
        false,
        orderNo,
        eventId,
        "RECOVERY_REQUIRED",
        "ORDER_RECOVERY_PENDING",
        Objects.requireNonNull(reason));
  }
}
