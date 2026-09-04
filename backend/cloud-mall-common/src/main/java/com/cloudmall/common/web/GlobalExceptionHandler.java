package com.cloudmall.common.web;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> biz(BizException e) { return ResponseEntity.status(e.getHttpStatus()).body(ApiResponse.error(e.getCode(), e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> validation(MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(ApiResponse.error("COMMON_INVALID_ARGUMENT", "请求参数无效")); }
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> missingHeader(MissingRequestHeaderException e) { return ResponseEntity.badRequest().body(ApiResponse.error("COMMON_INVALID_ARGUMENT", "请求头参数无效")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> other(Exception e) { return ResponseEntity.internalServerError().body(ApiResponse.error("COMMON_INTERNAL_ERROR", "服务内部错误")); }
}
