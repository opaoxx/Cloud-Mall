package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.FeignAuthConfiguration;
import com.cloudmall.pay.feign.OrderClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 显式IT：先构建Order，再以实际Pay Feign调用实际Order MVC/Service，SQL为受控替身。 */
class PayOrderStateFeignIT {
  @Test
  void actualPayClientConsumesPaidAndCancelResponsesWithOriginalHeaderAndNullableActionField()
      throws Exception {
    AuthContext.clear();
    Path classes = Path.of("../cloud-mall-order/target/classes").toAbsolutePath().normalize();
    try (var loader =
        new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      var states =
          Map.of(
              "202611paid",
              new AtomicReference<>("PENDING_PAYMENT"),
              "202611cancel",
              new AtomicReference<>("PENDING_PAYMENT"));
      var actions = new CopyOnWriteArrayList<String>();
      var requests = new CopyOnWriteArrayList<String>();
      var transportFailure = new AtomicReference<Throwable>();
      Class<?> ordersType = loader.loadClass("com.cloudmall.order.mapper.OrderSqlMapper");
      Object orders =
          mock(
              ordersType,
              invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("findOrder")) {
                  String orderNo = invocation.getArgument(1);
                  var state = states.get(orderNo);
                  return state == null
                      ? List.of()
                      : List.of(
                          Map.of(
                              "order_no",
                              orderNo,
                              "user_id",
                              7L,
                              "status",
                              state.get(),
                              "total_amount",
                              new BigDecimal("12.30"),
                              "pay_amount",
                              new BigDecimal("12.30"),
                              "address_snapshot",
                              "{}",
                              "created_at",
                              LocalDateTime.of(2026, 11, 1, 12, 0)));
                }
                if (method.equals("findOrderItems")) return List.of();
                if (method.equals("updateStatus"))
                  return states
                          .get((String) invocation.getArgument(1))
                          .compareAndSet(invocation.getArgument(3), invocation.getArgument(2))
                      ? 1
                      : 0;
                return Answers.RETURNS_DEFAULTS.answer(invocation);
              });
      Class<?> actionType = loader.loadClass("com.cloudmall.order.mapper.OrderStockActionMapper");
      Object actionMapper =
          mock(
              actionType,
              invocation -> {
                if (invocation.getMethod().getName().equals("insert")) {
                  actions.add(invocation.getArgument(1) + ":" + invocation.getArgument(2));
                  return 1;
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
              });
      Class<?> monitorType = loader.loadClass("com.cloudmall.order.service.impl.OrderWriteMonitor");
      Object service =
          loader
              .loadClass("com.cloudmall.order.service.impl.OrderStateServiceImpl")
              .getConstructor(ordersType, ObjectMapper.class, actionType, monitorType)
              .newInstance(
                  orders,
                  new ObjectMapper(),
                  actionMapper,
                  monitorType.getConstructor().newInstance());
      Object controller =
          loader
              .loadClass("com.cloudmall.order.controller.OrderStateController")
              .getConstructor(loader.loadClass("com.cloudmall.order.service.OrderStateService"))
              .newInstance(service);
      var builder = new Jackson2ObjectMapperBuilder();
      new CommonWebConfiguration().moneyAsString().customize(builder);
      var mvc =
          MockMvcBuilders.standaloneSetup(controller)
              .addInterceptors(new AuthInterceptor())
              .setControllerAdvice(new GlobalExceptionHandler())
              .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
              .build();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var executor = Executors.newSingleThreadExecutor();
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            try {
              assertEquals("POST", exchange.getRequestMethod());
              assertEquals(List.of("7"), exchange.getRequestHeaders().get("X-User-Id"));
              String path = exchange.getRequestURI().getPath();
              assertTrue(
                  path.equals("/api/orders/202611paid/paid")
                      || path.equals("/api/orders/202611cancel/cancel"));
              requests.add(path);
              var request = post(URI.create("http://localhost" + exchange.getRequestURI()));
              exchange
                  .getRequestHeaders()
                  .forEach((name, values) -> request.header(name, values.toArray()));
              var response = mvc.perform(request).andReturn().getResponse();
              assertNull(AuthContext.userId());
              byte[] body = response.getContentAsByteArray();
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(response.getStatus(), body.length);
              exchange.getResponseBody().write(body);
            } catch (Throwable failure) {
              transportFailure.set(failure);
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      try {
        server.start();
        var json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OrderClient client =
            Feign.builder()
                .contract(new SpringMvcContract())
                .requestInterceptor(new FeignAuthConfiguration().authHeaders())
                .retryer(feign.Retryer.NEVER_RETRY)
                .options(new feign.Request.Options(3000, 5000))
                .decoder(
                    (response, type) -> {
                      try (var body = response.body().asInputStream()) {
                        return json.readValue(body, json.constructType(type));
                      }
                    })
                .target(OrderClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
        for (int replay = 0; replay < 2; replay++) {
          var paid = client.paid("202611paid", 7L);
          var cancelled = client.cancel("202611cancel", 7L);
          assertEquals("0", paid.code);
          assertEquals("PAID", paid.data.status());
          assertEquals("202611paid", paid.data.orderNo());
          assertEquals("0", cancelled.code);
          assertEquals("CANCELLED", cancelled.data.status());
          assertEquals("202611cancel", cancelled.data.orderNo());
          for (var result : List.of(paid, cancelled)) {
            assertEquals(7L, result.data.userId());
            assertEquals(new BigDecimal("12.30"), result.data.payAmount());
            assertNull(result.data.stockActionStatus(), "原公开OrderVO没有该字段，不能使用四参兼容构造器补成SUCCEEDED");
          }
        }
        assertEquals(List.of("202611paid:CONFIRM", "202611cancel:ROLLBACK"), actions);
        assertEquals(4, requests.size());
        assertNull(transportFailure.get());
      } finally {
        server.stop(0);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        AuthContext.clear();
      }
    }
  }
}
