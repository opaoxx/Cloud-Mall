package com.cloudmall.order.domain.dto;

import java.util.ArrayList;
import java.util.List;

/** 创建普通订单请求。 */
public class CreateOrderDTO {
  /** 订单商品列表。 */
  public List<OrderItemDTO> items = new ArrayList<>();

  /** 收货地址主键。 */
  public Long addressId;
}
