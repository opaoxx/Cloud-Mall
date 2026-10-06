package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudmall.pay.config.UserTccFeignConfiguration;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** User TCC Feign 的 POST/GET 重试边界测试。 */
class UserTccFeignRetryerTest {
  /** GET 允许一次恢复重试，第二次失败直接交给 bridge。 */
  @Test
  void getRetriesOnce() {
    Retryer retryer = new UserTccFeignConfiguration().userTccRetryer();
    RetryableException failure = failure(Request.HttpMethod.GET);

    retryer.continueOrPropagate(failure);
    assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(failure));
  }

  /** POST 不允许自动重试，避免注册第二个 User TCC branch。 */
  @Test
  void postNeverRetries() {
    Retryer retryer = new UserTccFeignConfiguration().userTccRetryer();

    assertThrows(
        RetryableException.class,
        () -> retryer.continueOrPropagate(failure(Request.HttpMethod.POST)));
  }

  /** 创建指定 HTTP 方法的可重试异常。 */
  private RetryableException failure(Request.HttpMethod method) {
    Request request =
        Request.create(
            method,
            "http://cloud-mall-user/test",
            java.util.Map.of(),
            new byte[0],
            java.nio.charset.StandardCharsets.UTF_8,
            new feign.RequestTemplate());
    return new RetryableException(503, "unavailable", method, new Date(), request);
  }
}
