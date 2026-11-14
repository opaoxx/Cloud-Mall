package com.cloudmall.stock.domain.vo;

import java.util.List;

/** 订单库存预扣持久化状态响应。 */
public class StockReservationVO {
  /** 订单号。 */
  public String orderNo;

  /** 预扣状态。 */
  public String status;

  /** SKU 明细状态。 */
  public List<StockReservationItemVO> items;

  /** 创建库存预扣状态响应。 */
  public StockReservationVO(String orderNo, String status, List<StockReservationItemVO> items) {
    this.orderNo = orderNo;
    this.status = status;
    this.items = items;
  }
}
