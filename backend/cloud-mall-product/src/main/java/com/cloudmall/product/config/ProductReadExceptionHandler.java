package com.cloudmall.product.config;

import com.cloudmall.common.api.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 为商品读的确定性数据库依赖故障提供可追踪的 503 响应。 */
@RestControllerAdvice(basePackages = "com.cloudmall.product.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductReadExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ProductReadExceptionHandler.class);
  private final MeterRegistry meterRegistry;

  /** 创建带失败计数的商品读异常处理器。 */
  public ProductReadExceptionHandler(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** 只把 GET 依赖不可用映射 503，写接口保持原有 500 契约。 */
  @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class})
  public ResponseEntity<ApiResponse<Object>> databaseUnavailable(
      Exception failure, HttpServletRequest request) {
    String reason = failure instanceof QueryTimeoutException ? "sql_timeout"
        : failure instanceof CannotGetJdbcConnectionException ? "connection_acquire"
        : "db_connection";
    meterRegistry.counter("cloudmall.product.read.failures", "reason", reason).increment();
    boolean readRequest = "GET".equals(request.getMethod());
    ApiResponse<Object> response = readRequest
        ? ApiResponse.error("PRODUCT_QUERY_UNAVAILABLE", "商品查询暂时不可用")
        : ApiResponse.error("COMMON_INTERNAL_ERROR", "服务内部错误");
    log.error("商品数据库依赖失败，path={}，requestId={}，traceId={}，reason={}",
        request.getRequestURI(), response.requestId, MDC.get("traceId"), reason, failure);
    if (!readRequest)
      return ResponseEntity.internalServerError()
          .body(response);
    return ResponseEntity.status(503).body(response);
  }
}
