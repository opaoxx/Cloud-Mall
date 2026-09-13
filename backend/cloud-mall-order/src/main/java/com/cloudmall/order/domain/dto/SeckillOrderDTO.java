package com.cloudmall.order.domain.dto;

/** 创建秒杀订单请求。 */
public class SeckillOrderDTO {
  /** 秒杀活动主键。 */
  public Long activityId;

  /** SKU 主键。 */
  public Long skuId;

  /** 秒杀订单使用的收货地址主键。 */
  public Long addressId;
}
