package com.cloudmall.common.error;

public final class ErrorCodes {
  /** 禁止实例化错误码常量类。 */
  private ErrorCodes() {}

  /** 保存 INVALID 对应的内部状态或配置。 */
  public static final String INVALID = "COMMON_INVALID_ARGUMENT",
      INTERNAL = "COMMON_INTERNAL_ERROR",
      UNAUTHORIZED = "COMMON_UNAUTHORIZED",
      FORBIDDEN = "COMMON_FORBIDDEN",
      NOT_FOUND = "COMMON_NOT_FOUND",
      CONFLICT = "COMMON_CONFLICT",
      DUPLICATE = "ORDER_DUPLICATE_REQUEST",
      STOCK = "ORDER_STOCK_NOT_ENOUGH",
      STATUS = "ORDER_STATUS_INVALID",
      PAY_MISMATCH = "PAY_AMOUNT_MISMATCH",
      PAY_DONE = "PAY_ALREADY_PROCESSED";
}
