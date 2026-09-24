package com.cloudmall.stock.service.impl;

import com.cloudmall.common.error.BizException;

/** 整笔库存不足时保留持久 REJECTED 结果的业务异常。 */
public final class StockReservationRejectedException extends BizException {
  /** 创建库存预扣已持久化拒绝异常。 */
  public StockReservationRejectedException(String message) {
    super("ORDER_STOCK_RESERVATION_REJECTED", message, 409);
  }
}
