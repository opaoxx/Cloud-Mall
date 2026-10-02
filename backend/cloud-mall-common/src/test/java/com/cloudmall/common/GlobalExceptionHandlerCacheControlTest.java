package com.cloudmall.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;

/** 防止秒杀限流与依赖故障响应被中间缓存复用。 */
class GlobalExceptionHandlerCacheControlTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void rateLimitAndDependencyFailureCannotBeCached() {
    assertEquals("no-store", handler.biz(
        new BizException("GATEWAY_RATE_LIMITED", "请稍后重试", 429))
        .getHeaders().getCacheControl());
    assertEquals("no-store", handler.biz(
        new BizException("GATEWAY_SERVICE_UNAVAILABLE", "请稍后重试", 503))
        .getHeaders().getCacheControl());
  }
}
