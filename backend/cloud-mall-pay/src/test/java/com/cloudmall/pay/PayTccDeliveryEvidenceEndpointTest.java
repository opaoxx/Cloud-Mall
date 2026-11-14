package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 受控本机 HTTP 验证证据端口接线与只读请求；不是 RabbitMQ 集成验收。 */
class PayTccDeliveryEvidenceEndpointTest {
  @Test
  void metadataUsesExplicitEndpointAndOnlyReadsOwnedVhostAndQueueList() throws Exception {
    verifyMetadata(false);
  }

  @Test
  void foreignVhostResponseCannotBecomeOwnedEvidence() throws Exception {
    verifyMetadata(true);
  }

  @Test
  void databaseEvidenceMustMatchExactConfiguredPortAndOwnedCatalogWithoutConnecting() {
    String schema = "cm16c_" + "d".repeat(32) + "_pay";
    JdbcTemplate database =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:20000/"
                    + schema
                    + "?allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000",
                "root",
                "root"));
    Object binding =
        ReflectionTestUtils.invokeMethod(
            PayTccStageEvidence.class, "sourceBinding", database, schema, 20000);
    assertNotNull(binding);
    assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(binding, "verifyUnchanged"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReflectionTestUtils.invokeMethod(
                PayTccStageEvidence.class, "sourceBinding", database, schema, 20001));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReflectionTestUtils.invokeMethod(
                PayTccStageEvidence.class, "sourceBinding", database, schema + "_other", 20000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReflectionTestUtils.invokeMethod(
                PayTccStageEvidence.class, "sourceBinding", database, schema));
    DriverManagerDataSource source = (DriverManagerDataSource) database.getDataSource();
    source.setUrl(source.getUrl().replace(":20000/", ":20001/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReflectionTestUtils.invokeMethod(binding, "verifyUnchanged"));
  }

  /** 指定端口上的两类响应只模拟管理协议，不启动消费者或真实 broker。 */
  private void verifyMetadata(boolean foreignVhost) throws Exception {
    String batch = "cm16c_" + "c".repeat(32);
    List<String> observed = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/",
        exchange -> {
          observed.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          String body =
              exchange.getRequestURI().getPath().startsWith("/api/vhosts/")
                  ? "{\"name\":\"" + (foreignVhost ? "unowned" : batch) + "\"}"
                  : "{\"items\":[{\"name\":\"cloudmall.order.timeout.dlx\",\"vhost\":\""
                      + batch
                      + "\",\"messages\":0,\"consumers\":1,\"password\":\"not-for-evidence\"}],\"total_count\":1}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
          exchange.close();
        });
    server.start();
    try {
      List<String> missing = new ArrayList<>();
      Map<String, Object> snapshot =
          ReflectionTestUtils.invokeMethod(
              PayTccStageEvidence.class,
              "mqSnapshot",
              batch,
              missing,
              server.getAddress().getPort());
      assertNotNull(snapshot);
      assertEquals(false, snapshot.get("eventCausalityProven"));
      if (foreignVhost) {
        assertEquals("UNAVAILABLE", snapshot.get("status"));
        assertEquals(List.of("SOURCE_UNAVAILABLE:MQ"), missing);
        assertEquals(List.of("GET /api/vhosts/" + batch), observed);
        assertFalse(snapshot.containsKey("queues"));
      } else {
        assertEquals("OBSERVED", snapshot.get("status"));
        assertTrue(missing.isEmpty());
        assertEquals(
            List.of(
                "GET /api/vhosts/" + batch,
                "GET /api/queues/" + batch + "?page=1&page_size=100&pagination=true"),
            observed);
        assertFalse(snapshot.toString().contains("not-for-evidence"));
        assertEquals(batch, snapshot.get("vhost"));
      }
    } finally {
      server.stop(0);
    }
  }
}
