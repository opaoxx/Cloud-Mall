package com.cloudmall.order.service.impl;

/** CM-18 无法确认全局事务最终结果时使用的内部异常。 */
public class Cm18UnknownTransactionException extends RuntimeException {
  /** 创建未知事务状态异常。 */
  public Cm18UnknownTransactionException(String message, Throwable cause) {
    super(message, cause);
  }
}
