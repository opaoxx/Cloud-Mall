package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.feign.StockClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/** 通过真实 Feign 方法绑定验证兼容动作调用能够编码非空请求体。 */
class StockFeignDefaultActionContractTest {
  /** 默认确认/回滚必须沿用原订单号稳定动作键，并只发送一次 POST。 */
  @ParameterizedTest
  @ValueSource(strings = {"ROLLBACK", "CONFIRM"})
  void compatibilityMethodEncodesTheOriginalOperationKey(String action) throws Exception {
    AtomicReference<Request> sentRequest = new AtomicReference<>();
    ObjectMapper mapper = new ObjectMapper();
    StockClient client = Feign.builder().contract(new SpringMvcContract())
        .encoder((body, bodyType, template) -> {
          try {
            template.body(mapper.writeValueAsString(body));
            template.header("Content-Type", "application/json");
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        })
        .decoder((response, type) -> ApiResponse.ok(null))
        .client((request, options) -> {
          sentRequest.set(request);
          return Response.builder().request(request).status(200).reason("OK")
              .headers(Map.of()).body("{}", StandardCharsets.UTF_8).build();
        }).target(StockClient.class, "http://stock.test");

    if ("ROLLBACK".equals(action)) client.rollback("202610-contract");
    else client.confirm("202610-contract");

    assertEquals(Request.HttpMethod.POST, sentRequest.get().httpMethod());
    assertEquals("http://stock.test/api/stock/reservations/202610-contract/"
        + action.toLowerCase(java.util.Locale.ROOT), sentRequest.get().url());
    assertEquals("202610-contract:" + action,
        mapper.readTree(sentRequest.get().body()).path("operationKey").asText());
  }
}
