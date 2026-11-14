package com.cloudmall.product.config;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.product.util.ProductReadFailureClassifier;
import com.cloudmall.product.util.ProductReadFailureClassifier.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 为商品读的确定性数据库依赖故障提供可追踪的 503 响应。 */
@RestControllerAdvice(basePackages = "com.cloudmall.product.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductReadExceptionHandler {
  /** 请求关联信息和实际 cause 链，方便区分连接获取与执行阶段失败。 */
  private static final Logger log = LoggerFactory.getLogger(ProductReadExceptionHandler.class);

  /** 只记录公开 GET 的确定性数据库依赖故障。 */
  private final MeterRegistry meterRegistry;

  /** 创建带失败计数的商品读异常处理器。 */
  public ProductReadExceptionHandler(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** 只把 GET 依赖不可用映射 503，写接口保持原有 500 契约。 */
  @ExceptionHandler({DataAccessException.class, TransactionException.class, SQLException.class})
  public ResponseEntity<ApiResponse<Object>> databaseUnavailable(
      Exception failure, HttpServletRequest request) {
    // Preserve the exact registration GET failure before a nested SQL cause selects this advice.
    if ("GET".equals(request.getMethod())
        && failure instanceof com.cloudmall.common.error.BizException metricFailure
        && "PRODUCT_METRICS_UNAVAILABLE".equals(metricFailure.getCode())
        && metricFailure.getHttpStatus() == 503) {
      return ResponseEntity.status(503)
          .cacheControl(CacheControl.noStore())
          .body(
              ApiResponse.error(
                  "PRODUCT_METRICS_UNAVAILABLE", "Product metrics could not be registered"));
    }

    // 1. 分类完整 cause 链；事务包装、通信中断和未知实现错误保持各自含义。
    Reason reason = ProductReadFailureClassifier.classify(failure);
    boolean readRequest = "GET".equals(request.getMethod());
    boolean dependencyReadFailure = readRequest && reason != Reason.UNKNOWN;
    // 2. 写请求和未知错误不污染 read failures，也不改变既有 500 契约。
    if (dependencyReadFailure)
      meterRegistry
          .counter("cloudmall.product.read.failures", "reason", reason.metricTag())
          .increment();
    ApiResponse<Object> response =
        dependencyReadFailure
            ? ApiResponse.error("PRODUCT_QUERY_UNAVAILABLE", "商品查询暂时不可用")
            : ApiResponse.error("COMMON_INTERNAL_ERROR", "服务内部错误");
    log.error(
        "商品请求数据库异常，path={}，requestId={}，traceId={}，reason={}，exceptionType={}",
        request.getRequestURI(),
        response.requestId,
        MDC.get("traceId"),
        reason.metricTag(),
        failure.getClass().getName(),
        failure);
    // 3. 错误响应不携带 SQL、连接地址和凭据，暂时不可用结果禁止缓存。
    if (!dependencyReadFailure) return ResponseEntity.internalServerError().body(response);
    return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(response);
  }
}
