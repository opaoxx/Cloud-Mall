package com.cloudmall.order.service.impl;

/** Order 本地 Sentinel 阻断 Stock 调用，远端是否已有历史事实仍须由恢复器核对。 */
public class StockCircuitOpenException extends RuntimeException {
  /** 创建可供订单入口和恢复任务区分的本地熔断异常。 */
  public StockCircuitOpenException(String resource, Throwable cause) {
    super("Stock circuit open for " + resource, cause);
  }
}
