package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.UserTccFeignConfiguration;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HTTP 409 不等同于身份冲突，必须保留资金结果未知的语义。 */
class UserTccFeignErrorDecoderTest {
  @Test
  void onlyCompleteIdentityCodesBecomeConflict() {
    for (String code : new String[] {"IDEMPOTENCY_CONFLICT", "USER_TCC_STATE_CONFLICT"}) {
      Exception result = decode("{\"code\":\"" + code + "\"}");
      assertTrue(result instanceof BizException);
      assertEquals("PAY_TCC_DISPATCH_CONFLICT", ((BizException) result).getCode());
    }
    for (String body :
        new String[] {
          "{\"code\":\"PAYMENT_FAILED\"}",
          "{\"code\":\"BALANCE_TCC_UNKNOWN\"}",
          "{\"code\":\"IDEMPOTENCY_CONFLICT\"",
          "{\"code\":\"IDEMPOTENCY_CONFLICT\"} garbage",
          ""
        }) {
      assertFalse(decode(body) instanceof BizException, body);
    }
  }

  private Exception decode(String body) {
    Request request =
        Request.create(
            Request.HttpMethod.POST,
            "http://cloud-mall-user/test",
            Map.of(),
            new byte[0],
            StandardCharsets.UTF_8,
            new RequestTemplate());
    Response response =
        Response.builder()
            .status(409)
            .reason("Conflict")
            .request(request)
            .headers(Map.of())
            .body(body, StandardCharsets.UTF_8)
            .build();
    return new UserTccFeignConfiguration().userTccErrorDecoder().decode("prepare", response);
  }
}
