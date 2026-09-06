package com.cloudmall.common.error;

public class BizException extends RuntimeException {
  /** 统一响应的业务状态码。 */
  private final String code;

  /** 保存 httpStatus 对应的内部状态或配置。 */
  private final int httpStatus;

  /** 创建携带业务码和 HTTP 状态的异常。 */
  public BizException(String code, String message, int httpStatus) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }

  /** 执行 getCode 的内部辅助逻辑。 */
  public String getCode() {
    return code;
  }

  /** 执行 getHttpStatus 的内部辅助逻辑。 */
  public int getHttpStatus() {
    return httpStatus;
  }
}
