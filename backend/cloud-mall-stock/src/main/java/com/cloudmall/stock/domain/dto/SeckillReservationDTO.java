package com.cloudmall.stock.domain.dto;

/** 秒杀库存预扣请求。 */
public class SeckillReservationDTO {
  /** 秒杀活动主键。 */
  public Long activityId;

  /** SKU 主键。 */
  public Long skuId;

  /** 用户主键。 */
  public Long userId;

  /** 请求幂等键。 */
  public String idempotencyKey;

  /** 秒杀订单收货地址快照，随消息传递给订单服务。 */
  public String addressSnapshot;
}
