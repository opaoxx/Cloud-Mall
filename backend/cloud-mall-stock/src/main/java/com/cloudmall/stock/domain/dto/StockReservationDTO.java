package com.cloudmall.stock.domain.dto;

import java.util.ArrayList;
import java.util.List;

/** 普通库存预扣请求。 */
public class StockReservationDTO {
  /** 关联订单号。 */
  public String orderNo;

  /** 待处理的 SKU 数量。 */
  public List<StockLineDTO> items = new ArrayList<>();

  /** 库存处理场景。 */
  public String scene;
}
