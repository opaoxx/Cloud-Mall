package com.cloudmall.common.web;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  /** 记录未分类服务端异常，避免统一 500 响应遮蔽根因。 */
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 转换业务异常为统一响应。 */
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Object>> biz(BizException exception) {
    return ResponseEntity.status(exception.getHttpStatus())
        .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
  }

  /** 转换参数校验异常为统一响应。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Object>> validation(MethodArgumentNotValidException exception) {
    return ResponseEntity.badRequest().body(ApiResponse.error("COMMON_INVALID_ARGUMENT", "请求参数无效"));
  }

  /** 转换缺少请求头异常为统一响应。 */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiResponse<Object>> missingHeader(
      MissingRequestHeaderException exception) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.error("COMMON_INVALID_ARGUMENT", "请求头参数无效"));
  }

  /** 转换未分类异常为统一响应。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Object>> other(Exception exception) {
    ApiResponse<Object> response = ApiResponse.error("COMMON_INTERNAL_ERROR", "服务内部错误");
    log.error("未分类服务端异常，requestId={}，traceId={}",
        response.requestId, MDC.get("traceId"), exception);
    return ResponseEntity.internalServerError()
        .body(response);
  }
}
