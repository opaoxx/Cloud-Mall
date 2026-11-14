package com.cloudmall.common.error;

public final class ErrorCodes {
  /** 禁止实例化错误码常量类。 */
  private ErrorCodes() {}

  /** 请求参数未通过格式或业务取值校验。 */
  public static final String INVALID = "COMMON_INVALID_ARGUMENT";

  /** 未分类的服务端内部错误。 */
  public static final String INTERNAL = "COMMON_INTERNAL_ERROR";

  /** 当前请求缺少有效登录身份。 */
  public static final String UNAUTHORIZED = "COMMON_UNAUTHORIZED";

  /** 当前用户缺少执行请求所需的权限。 */
  public static final String FORBIDDEN = "COMMON_FORBIDDEN";

  /** 请求对应的业务资源未找到。 */
  public static final String NOT_FOUND = "COMMON_NOT_FOUND";

  /** 请求与当前资源状态或已有业务事实冲突。 */
  public static final String CONFLICT = "COMMON_CONFLICT";

  /** 订单请求命中已有幂等记录。 */
  public static final String DUPLICATE = "ORDER_DUPLICATE_REQUEST";

  /** 订单所需的可用库存不足。 */
  public static final String STOCK = "ORDER_STOCK_NOT_ENOUGH";

  /** 当前订单状态不允许执行所请求的操作。 */
  public static final String STATUS = "ORDER_STATUS_INVALID";

  /** 请求支付金额与订单应付金额不一致。 */
  public static final String PAY_MISMATCH = "PAY_AMOUNT_MISMATCH";

  /** 支付已被处理，无法按当前请求再次操作。 */
  public static final String PAY_DONE = "PAY_ALREADY_PROCESSED";

  /** Redis 依赖不可用，当前业务无法完成。 */
  public static final String REDIS_UNAVAILABLE = "REDIS_SERVICE_UNAVAILABLE";
}
