package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.feign.StockClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/** 显式IT：先构建Stock标准产物，再用实际Stock VO与Order Feign验证HTTP契约，无新增模块依赖。 */
class StockSeckillFactFeignIT {
  @Test
  void actualFeignConsumesStockViewsWithoutLosingAbsentOrNullFields() throws Exception {
    var mapper = new ObjectMapper();
    Path stockClasses = Path.of("../cloud-mall-stock/target/classes").toAbsolutePath().normalize();
    try (var loader =
        new URLClassLoader(new URL[] {stockClasses.toUri().toURL()}, getClass().getClassLoader())) {
      Object missing =
          loader
              .loadClass("com.cloudmall.stock.domain.vo.SeckillReservationFactVO")
              .getMethod("notFound", String.class)
              .invoke(null, "order-1");
      var observed =
          loader
              .loadClass("com.cloudmall.stock.domain.vo.SeckillReservationObservedVO")
              .getConstructor(
                  String.class,
                  String.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class);
      var outbox =
          loader
              .loadClass("com.cloudmall.stock.domain.vo.SeckillReservationOutboxVO")
              .getConstructor(
                  String.class,
                  String.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class,
                  Object.class);
      var payloads =
          List.of(
              missing,
              observed.newInstance("order-1", "UNKNOWN", 9L, 7L, 8L, "key", null, "ACCEPTED"),
              outbox.newInstance("order-1", "null", 9L, 7L, 8L, "key", null, "ACCEPTED", null),
              outbox.newInstance(
                  "order-1", "PUBLISHED", 9L, 7L, 8L, "key", "hash", "ACCEPTED", "event-1"));
      var currentBody = new AtomicReference<byte[]>();
      var requests = new AtomicInteger();
      var transportFailure = new AtomicReference<Throwable>();
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var executor = Executors.newSingleThreadExecutor();
      server.setExecutor(executor);
      server.createContext(
          "/api/internal/stock/seckill/reservations/order-1",
          exchange -> {
            try {
              assertEquals("GET", exchange.getRequestMethod());
              assertEquals(
                  "cloud-mall-order", exchange.getRequestHeaders().getFirst("X-Internal-Service"));
              requests.incrementAndGet();
              byte[] body = currentBody.get();
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
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
        StockClient client =
            Feign.builder()
                .contract(new SpringMvcContract())
                .retryer(feign.Retryer.NEVER_RETRY)
                .options(new feign.Request.Options(3000, 5000))
                .decoder(
                    (response, type) -> {
                      try (var input = response.body().asInputStream()) {
                        return mapper.readValue(input, mapper.constructType(type));
                      }
                    })
                .target(StockClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
        int[] sizes = {2, 8, 9, 9};
        for (int index = 0; index < payloads.size(); index++) {
          Object payload = payloads.get(index);
          currentBody.set(mapper.writeValueAsBytes(ApiResponse.ok(payload)));
          var response = client.seckillReservationFact("order-1", "cloud-mall-order");
          assertEquals("0", response.code);
          JsonNode decoded = mapper.valueToTree(response.data);
          assertEquals(mapper.readTree(currentBody.get()).get("data"), decoded);
          assertEquals(sizes[index], decoded.size());
          assertEquals(index >= 2, response.data.containsKey("eventId"));
          if (index == 2) assertNull(response.data.get("eventId"));
          if (index > 0) assertTrue(response.data.containsKey("requestHash"));
        }
        assertEquals(4, requests.get());
        assertNull(transportFailure.get());
      } finally {
        server.stop(0);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }
}
