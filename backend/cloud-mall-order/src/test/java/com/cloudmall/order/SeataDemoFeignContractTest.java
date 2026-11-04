package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.domain.dto.SeataStockDemoBranchDTO;
import com.cloudmall.order.feign.StockClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

/** 实际Feign HTTP验证Java方法/DTO改名不改变既有Stock分支协议。 */
class SeataDemoFeignContractTest {
  @Test
  void renamedMethodRetainsPostBodyHeadersAndTypedResponse() throws Exception {
    var json = new ObjectMapper();
    var failure = new AtomicReference<Throwable>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/api/internal/cm18/seata/demo/branch",
        exchange -> {
          try {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(
                "/api/internal/cm18/seata/demo/branch", exchange.getRequestURI().toString());
            assertEquals("test-xid", exchange.getRequestHeaders().getFirst("TX_XID"));
            assertEquals(
                "STOCK_AFTER_WRITE", exchange.getRequestHeaders().getFirst("X-CM18-Failure-Point"));
            assertEquals(
                "cloud-mall-cm18-test",
                exchange.getRequestHeaders().getFirst("X-Internal-Service"));
            assertEquals(
                json.readTree("{\"txId\":\"cm18-feign\"}"),
                json.readTree(exchange.getRequestBody()));
            byte[] body =
                ("{\"code\":\"0\",\"message\":\"成功\",\"data\":{"
                        + "\"txId\":\"cm18-feign\",\"xid\":\"masked\",\"status\":\"WRITTEN\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Throwable problem) {
            failure.set(problem);
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
              .encoder(
                  (body, type, template) -> {
                    try {
                      template.body(json.writeValueAsBytes(body), StandardCharsets.UTF_8);
                    } catch (java.io.IOException failureToEncode) {
                      throw new feign.codec.EncodeException("无法序列化测试请求", failureToEncode);
                    }
                  })
              .decoder(
                  (response, type) -> {
                    try (var input = response.body().asInputStream()) {
                      return json.readValue(input, json.constructType(type));
                    }
                  })
              .target(StockClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
      var response =
          client.executeSeataDemoBranch(
              new SeataStockDemoBranchDTO("cm18-feign"),
              "test-xid",
              "STOCK_AFTER_WRITE",
              "cloud-mall-cm18-test");
      assertNull(failure.get());
      assertEquals("0", response.code);
      assertEquals("cm18-feign", response.data.txId);
      assertEquals("masked", response.data.xid);
      assertEquals("WRITTEN", response.data.status);
    } finally {
      server.stop(0);
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
