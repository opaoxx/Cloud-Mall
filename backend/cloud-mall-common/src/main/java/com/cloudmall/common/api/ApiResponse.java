package com.cloudmall.common.api;

public class ApiResponse<T> {
  /** 统一响应的业务状态码。 */
  public String code;

  /** 统一响应的可读消息。 */
  public String message;

  /** 统一响应承载的业务数据。 */
  public T data;

  /** 用于排查请求的响应标识。 */
  public String requestId;

  /** 初始化统一接口响应对象。 */
  public ApiResponse() {}

  /** 初始化统一接口响应对象。 */
  public ApiResponse(String code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    requestId = java.util.UUID.randomUUID().toString();
  }

  /** 构造成功的统一 API 响应。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>("0", "OK", data);
  }

  /** 构造失败的统一 API 响应。 */
  public static <T> ApiResponse<T> error(String code, String message) {
    return new ApiResponse<>(code, message, null);
  }
}
