package com.cloudmall.order.domain.vo;

import java.util.List;

/** 库存服务按订单号返回的预扣事实视图。 */
public class StockReservationStatusVO {
  /** 原预扣订单号。 */
  public String orderNo;

  /** RESERVED、CONFIRMED、ROLLED_BACK 或 NOT_FOUND。 */
  public String status;

  /** 原预扣 SKU 明细。 */
  public List<StockReservationItemVO> items;

  /** Jackson 反序列化构造器。 */
  public StockReservationStatusVO() {}

  /** 库存预扣明细视图。 */
  public static class StockReservationItemVO {
    /** SKU 主键。 */
    public Long skuId;

    /** 原预扣数量。 */
    public int quantity;

    /** SKU 对应的结算状态。 */
    public String status;

    /** Jackson 反序列化构造器。 */
    public StockReservationItemVO() {}
  }
}
