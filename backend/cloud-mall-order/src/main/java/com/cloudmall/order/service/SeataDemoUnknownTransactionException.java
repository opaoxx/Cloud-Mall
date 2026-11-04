package com.cloudmall.order.service;

/** CM-18 无法确认全局事务最终结果时使用的内部异常。 */
public class SeataDemoUnknownTransactionException extends RuntimeException {
  /** 创建未知事务状态异常。 */
  public SeataDemoUnknownTransactionException(String message, Throwable cause) {
    super(message, cause);
  }
}
