package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.SeckillReservationQueryController;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillReservationQueryServiceImpl;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 独占实库验证内部GET的查询次数、无事务/无写入边界及原JSON形状。 */
class SeckillReservationFactMySqlIT {
  @Test
  void missingAndForbiddenRequestsDoNotReadUnobservedOutbox() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.mvc.perform(get(fixture.path)).andExpect(status().isForbidden());
      assertTrue(fixture.observed.sql.isEmpty());
      var result = fixture.read();
      assertEquals(2, result.size());
      assertEquals("NOT_FOUND", result.get("status").asText());
      assertEquals(1, fixture.observed.sql.size());
      assertTrue(fixture.observed.sql.get(0).contains("seckill_reservation"));
      assertEquals(List.of(List.of(), List.of()), fixture.facts());
    }
  }

  @Test
  void observedAndOutboxShapesReadOnlyOriginalRows() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.jdbc.update(
          "INSERT INTO seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,request_hash,request_payload,status,created_at,updated_at) VALUES(1,9,7,8,'order-1','original-key',REPEAT('0',64),'{}','ACCEPTED',NOW(3),NOW(3))");
      var before = fixture.facts();
      var result = fixture.read();
      assertEquals(8, result.size());
      assertFalse(result.has("eventId"));
      assertEquals("UNKNOWN", result.get("status").asText());
      assertEquals(9L, result.get("activityId").longValue());
      assertEquals(before, fixture.facts());
      assertEquals(2, fixture.observed.sql.size());
      fixture.jdbc.update(
          "INSERT INTO seckill_order_outbox(id,event_id,order_no,idempotency_key,request_hash,payload,status,attempts,next_retry_at,created_at,updated_at) VALUES(1,'event-1','order-1','original-key',REPEAT('0',64),'{}','PUBLISHED',0,NOW(3),NOW(3),NOW(3))");
      before = fixture.facts();
      result = fixture.read();
      assertEquals(9, result.size());
      assertEquals("event-1", result.get("eventId").asText());
      assertEquals("PUBLISHED", result.get("status").asText());
      assertEquals(before, fixture.facts());
      assertEquals(2, fixture.observed.sql.size());
      fixture.jdbc.update("UPDATE seckill_reservation SET status='REJECTED'");
      before = fixture.facts();
      result = fixture.read();
      assertEquals("REJECTED", result.get("status").asText());
      assertTrue(result.has("eventId"));
      assertEquals(before, fixture.facts());
      assertEquals(2, fixture.observed.sql.size());
    }
  }

  /** 拒绝任何非SELECT或锁定读，同时验证没有新增事务且读保护仍生效。 */
  static class SelectOnlyDataSource extends AbstractDataSource {
    /** 本例真实JDBC资源。 */
    private final DataSource delegate;

    /** 每次HTTP读取执行的SQL。 */
    private final List<String> sql = new ArrayList<>();

    SelectOnlyDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** 为实际Mapper提供只能准备SELECT的连接。 */
    @Override
    public Connection getConnection() throws SQLException {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertTrue(StockDependencyGuard.mysqlActive());
      Connection actual = delegate.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement")) {
                  String statement = (String) arguments[0];
                  assertTrue(statement.startsWith("SELECT"), statement);
                  assertFalse(statement.contains("FOR UPDATE"));
                  sql.add(statement);
                }
                try {
                  return method.invoke(actual, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 不允许夹具外的凭据覆盖。 */
    @Override
    public Connection getConnection(String user, String password) {
      throw new UnsupportedOperationException();
    }
  }

  /** 仅创建和删除本例UUID库，源表只读克隆并核对checksum。 */
  static class Fixture implements AutoCloseable {
    /** 本例唯一库名。 */
    private final String schema = "stock_fact_" + UUID.randomUUID().toString().replace("-", "");

    /** 用于克隆和最终清理的源连接。 */
    private final JdbcTemplate administrator;

    /** 源表原始checksum。 */
    private final List<Map<String, Object>> sourceBefore;

    /** 本例种子准备和断言SQL。 */
    private final JdbcTemplate jdbc;

    /** 被测Mapper只读数据源。 */
    private final SelectOnlyDataSource observed;

    /** 真实代理和Controller组成的MVC链。 */
    private final MockMvc mvc;

    /** 原内部HTTP路径。 */
    private final String path = "/api/internal/stock/seckill/reservations/order-1";

    Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_stock"))
        throw new IllegalStateException("Explicit isolated Stock URL required");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      sourceBefore = sourceChecksum();
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      try {
        DataSource dataSource =
            new DriverManagerDataSource(
                configured(url.replace("/cloudmall_stock", "/" + schema)), "root", "root");
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("seckill_reservation", "seckill_order_outbox"))
          jdbc.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
        observed = new SelectOnlyDataSource(dataSource);
        var proxy =
            new AspectJProxyFactory(
                new SeckillReservationQueryServiceImpl(
                    new StockSqlMapper(new JdbcTemplate(observed))));
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new StockDependencyAspect());
        mvc =
            MockMvcBuilders.standaloneSetup(new SeckillReservationQueryController(proxy.getProxy()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
      } catch (Exception | AssertionError failure) {
        administrator.execute("DROP DATABASE " + schema);
        throw failure;
      }
    }

    private JsonNode read() throws Exception {
      observed.sql.clear();
      String body =
          mvc.perform(get(path).header("X-Internal-Service", "cloud-mall-order"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(StockDependencyGuard.mysqlActive());
      return new ObjectMapper().readTree(body).get("data");
    }

    private List<List<Map<String, Object>>> facts() {
      return List.of(
          jdbc.queryForList("SELECT * FROM seckill_reservation"),
          jdbc.queryForList("SELECT * FROM seckill_order_outbox"));
    }

    private List<Map<String, Object>> sourceChecksum() {
      return administrator.queryForList(
          "CHECKSUM TABLE cloudmall_stock.seckill_reservation,cloudmall_stock.seckill_order_outbox");
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 核对源数据未变后，仅删除本例数据库。 */
    @Override
    public void close() {
      try {
        assertEquals(sourceBefore, sourceChecksum());
      } finally {
        if (!schema.matches("stock_fact_[0-9a-f]{32}"))
          throw new IllegalStateException("Invalid owned schema");
        administrator.execute("DROP DATABASE " + schema);
      }
    }
  }
}
