package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.config.FeignAuthConfiguration;
import com.cloudmall.order.controller.OrderSeckillAdmissionController;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.service.impl.OrderSeckillAdmissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 实际Order MVC到实际StockClient的HTTP契约；Stock服务器受控，不宣称持久受理链验收。 */
class OrderSeckillAdmissionFeignTest {
  @AfterEach
  void clearThreadAndRules() {
    AuthContext.clear();
    FlowRuleManager.loadRules(List.of());
    ParamFlowRuleManager.loadRules(List.of());
  }

  @Test
  void actualFeignKeepsHeadersPayloadEnvelopeAndOneAttemptForEveryResponse() throws Exception {
    FlowRuleManager.loadRules(List.of());
    ParamFlowRuleManager.loadRules(List.of());
    var mapper = new ObjectMapper();
    var nextReply = new AtomicReference<Reply>();
    var calls = new AtomicInteger();
    var transportFailure = new AtomicReference<Throwable>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/api/stock/seckill/reservations", exchange.getRequestURI().toString());
            assertEquals(List.of("33"), exchange.getRequestHeaders().get("X-User-Id"));
            assertEquals(List.of("USER"), exchange.getRequestHeaders().get("X-User-Role"));
            var payload = mapper.readTree(exchange.getRequestBody());
            Set<String> keys = new java.util.HashSet<>();
            payload.fieldNames().forEachRemaining(keys::add);
            assertEquals(
                Set.of("activityId", "skuId", "userId", "idempotencyKey", "addressSnapshot"), keys);
            assertEquals(11, payload.get("activityId").asLong());
            assertEquals(22, payload.get("skuId").asLong());
            assertEquals(33, payload.get("userId").asLong());
            assertEquals(" original-key ", payload.get("idempotencyKey").asText());
            assertEquals(
                mapper.valueToTree(Map.of("id", 3, "receiver", "小明")),
                mapper.readTree(payload.get("addressSnapshot").asText()));
            Reply reply = nextReply.get();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(reply.httpStatus(), body.length);
            exchange.getResponseBody().write(body);
          } catch (Throwable failure) {
            transportFailure.compareAndSet(null, failure);
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      StockClient stock =
          Feign.builder()
              .contract(new SpringMvcContract())
              .retryer(feign.Retryer.NEVER_RETRY)
              .options(new feign.Request.Options(3000, 5000))
              .requestInterceptor(new FeignAuthConfiguration().authHeaders())
              .encoder(
                  (body, type, template) -> {
                    template.header("Content-Type", "application/json");
                    try {
                      template.body(mapper.writeValueAsBytes(body), StandardCharsets.UTF_8);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                      throw new feign.codec.EncodeException("受理测试请求序列化失败", failure);
                    }
                  })
              .decoder(
                  (response, type) -> {
                    try (var input = response.body().asInputStream()) {
                      return mapper.readValue(input, mapper.constructType(type));
                    }
                  })
              .target(StockClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
      UserClient users = mock(UserClient.class);
      when(users.addresses())
          .thenReturn(ApiResponse.ok(List.of(Map.of("id", 3L, "receiver", "小明"))));
      var controller =
          new OrderSeckillAdmissionController(
              new OrderSeckillAdmissionServiceImpl(stock, users, mapper));
      var mvc =
          MockMvcBuilders.standaloneSetup(controller)
              .addInterceptors(new AuthInterceptor())
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      var replies = new ArrayList<Reply>();
      replies.add(
          new Reply(
              200,
              "{\"code\":\"0\",\"message\":\"原受理\",\"requestId\":\"stock-request\",\"data\":{\"accepted\":true,\"reservationStatus\":\"ACCEPTED\",\"orderNo\":\"original\",\"eventId\":null,\"amount\":\"12.30\"}}",
              200,
              "0"));
      replies.add(
          new Reply(
              200,
              "{\"code\":\"SECKILL_PENDING\",\"message\":\"等待\",\"requestId\":\"unchanged\",\"data\":null}",
              200,
              "SECKILL_PENDING"));
      replies.add(error(429, "GATEWAY_RATE_LIMITED", 429, "GATEWAY_RATE_LIMITED"));
      replies.add(error(429, "SECKILL_LIMIT_REACHED", 429, "SECKILL_LIMIT_REACHED"));
      replies.add(error(429, "UNKNOWN", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(new Reply(429, "broken", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(error(409, "SECKILL_IDEMPOTENCY_CONFLICT", 409, "SECKILL_IDEMPOTENCY_CONFLICT"));
      for (String code :
          List.of(
              "SECKILL_NOT_STARTED",
              "SECKILL_ENDED",
              "SECKILL_SOLD_OUT",
              "SECKILL_CLAIM_CONFLICT",
              "COMMON_CONFLICT")) {
        replies.add(error(409, code, 409, "SECKILL_NOT_AVAILABLE"));
      }
      replies.add(error(409, "UNKNOWN", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(new Reply(409, "not-json", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(error(503, "REDIS_SERVICE_UNAVAILABLE", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(error(500, "COMMON_INTERNAL_ERROR", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(error(401, "COMMON_UNAUTHORIZED", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      replies.add(new Reply(200, "null", 503, "GATEWAY_SERVICE_UNAVAILABLE"));
      int expectedCalls = 0;
      for (Reply reply : replies) {
        nextReply.set(reply);
        var result =
            mvc.perform(
                    post("/api/seckill/orders")
                        .header("X-User-Id", "33")
                        .header("X-User-Role", "USER")
                        .header("Idempotency-Key", " original-key ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":11,\"skuId\":22,\"addressId\":3}"))
                .andExpect(status().is(reply.expectedStatus()))
                .andExpect(jsonPath("$.code").value(reply.expectedCode()))
                .andReturn();
        assertNull(transportFailure.get(), () -> String.valueOf(transportFailure.get()));
        assertEquals(++expectedCalls, calls.get(), "each request makes exactly one Stock attempt");
        if (reply.expectedStatus() == 200) {
          assertEquals(
              mapper.readTree(reply.body()),
              mapper.readTree(result.getResponse().getContentAsByteArray()));
        } else if (reply.expectedStatus() == 429 || reply.expectedStatus() == 503) {
          assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        }
        assertNull(AuthContext.userId());
      }
      assertEquals(18, calls.get());
      verify(users, times(18)).addresses();
    } finally {
      server.stop(0);
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private static Reply error(int status, String code, int expectedStatus, String expectedCode) {
    return new Reply(status, "{\"code\":\"" + code + "\"}", expectedStatus, expectedCode);
  }

  /** 每次只提供一个响应，验证写调用不自动重试。 */
  private record Reply(int httpStatus, String body, int expectedStatus, String expectedCode) {}
}
