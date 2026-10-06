package com.cloudmall.pay.config;

import com.cloudmall.common.error.BizException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** User TCC Feign 专属超时和重试配置，不参与全局组件扫描。 */
public class UserTccFeignConfiguration {
  /** 配置 User TCC 固定连接和读取超时。 */
  @Bean
  public Request.Options userTccRequestOptions() {
    return new Request.Options(3_000, 5_000);
  }

  /** POST 永不重试，GET 最多额外重试一次。 */
  @Bean
  public Retryer userTccRetryer() {
    return new GetOnlyRetryer();
  }

  /** 只有完整的身份冲突业务码才明确拒绝；余额不足和普通 4xx 仍待 TC 决议。 */
  @Bean
  public ErrorDecoder userTccErrorDecoder() {
    ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    ErrorDecoder fallback = new ErrorDecoder.Default();
    return (methodKey, response) -> {
      if (response.status() != 409 || response.body() == null)
        return fallback.decode(methodKey, response);
      try (java.io.InputStream body = response.body().asInputStream()) {
        byte[] bytes = body.readAllBytes();
        try {
          JsonNode envelope = json.readTree(bytes);
          String code = envelope == null ? "" : envelope.path("code").asText();
          if ("IDEMPOTENCY_CONFLICT".equals(code) || "USER_TCC_STATE_CONFLICT".equals(code))
            return new BizException("PAY_TCC_DISPATCH_CONFLICT", "User TCC 原身份或持久事实冲突", 409);
        } catch (java.io.IOException incompleteJson) {
          // 无完整响应证据时保留未知，不能推断为安全拒绝。
        }
        return fallback.decode(methodKey, response.toBuilder().body(bytes).build());
      } catch (java.io.IOException responseLost) {
        return new BizException("PAY_TCC_UNKNOWN", "User TCC 错误响应未完整到达", 503);
      }
    };
  }

  /** 仅允许该 TCC 客户端的只读 GET 进行一次恢复重试。 */
  static final class GetOnlyRetryer implements Retryer {
    /** 每次 Feign 调用已经使用的 GET 重试次数。 */
    private int getRetryCount;

    /** 写请求立即传播失败，只读请求至多额外尝试一次。 */
    @Override
    public void continueOrPropagate(RetryableException failure) {
      if (failure.method() != feign.Request.HttpMethod.GET || getRetryCount++ >= 1) {
        throw failure;
      }
    }

    /** 为下一次独立调用重置重试预算。 */
    @Override
    public Retryer clone() {
      return new GetOnlyRetryer();
    }
  }
}
