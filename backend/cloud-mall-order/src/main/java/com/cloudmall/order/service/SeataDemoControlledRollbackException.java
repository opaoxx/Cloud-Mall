package com.cloudmall.order.service;

/** CM-18 演练中由测试明确注入、预期触发全局回滚的受控异常。 */
public class SeataDemoControlledRollbackException extends RuntimeException {
  /** 创建受控回滚异常。 */
  public SeataDemoControlledRollbackException(String message) {
    super(message);
  }
}
