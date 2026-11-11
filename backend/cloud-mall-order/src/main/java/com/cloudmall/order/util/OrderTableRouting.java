package com.cloudmall.order.util;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/** 订单读写共用的原订单号到物理月表规则。 */
public final class OrderTableRouting {
  /** 禁止实例化纯路由工具。 */
  private OrderTableRouting() {}

  /** 按原订单号格式与月份校验确定物理表名，非法身份仍返回404。 */
  public static String tableFromOrderNo(String orderNo) {
    if (orderNo == null || !orderNo.matches("\\d{6}[A-Za-z0-9-]+"))
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    try {
      YearMonth.parse(orderNo.substring(0, 6), DateTimeFormatter.ofPattern("yyyyMM"));
    } catch (Exception invalidOrderMonth) {
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    }
    return "mall_order_" + orderNo.substring(0, 6);
  }
}
