package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockReservationService;
import com.cloudmall.stock.service.impl.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.SeckillReservationServiceImpl;
import com.cloudmall.stock.service.impl.StockQueryServiceImpl;
import com.cloudmall.stock.service.impl.StockReservationReader;
import com.cloudmall.stock.service.impl.StockReservationRequestService;
import com.cloudmall.stock.service.impl.StockReservationServiceImpl;
import com.cloudmall.stock.service.impl.StockReservationSnapshotCodec;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独占MySQL/Redis验证组合入口的真实事务、共享证明连接和原Lua/outbox受理。 */
class StockCoreRefactoringMySqlIT {
  @Test
  void concurrentOriginalOrdersCannotOversellTheLastUnit() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.seedStock(7, 1);
      var service = fixture.context.getBean(StockReservationService.class);
      var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
      var ready = new java.util.concurrent.CountDownLatch(6);
      var start = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Future<Boolean>> outcomes = new ArrayList<>();
      try {
        for (int index = 0; index < 6; index++) {
          String orderNo = "core-concurrent-" + index;
          outcomes.add(
              workers.submit(
                  () -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                      throw new AssertionError("workers did not start");
                    try {
                      service.reserve(request(orderNo, 7, 1));
                      return true;
                    } catch (
                        com.cloudmall.stock.service.impl.StockReservationRejectedException
                            rejected) {
                      return false;
                    }
                  }));
        }
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        int accepted = 0;
        for (var outcome : outcomes)
          if (outcome.get(20, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
        assertEquals(1, accepted);
        assertEquals(0, fixture.quantity(7));
        assertEquals(1, fixture.count("stock_flow"));
        assertEquals(1, fixture.count("stock_cache_repair"));
        assertEquals(
            5,
            fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation_request WHERE status='REJECTED'",
                Integer.class));
      } finally {
        start.countDown();
        workers.shutdown();
        assertTrue(workers.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void reserveReplayRejectionAndFailureKeepOriginalDurableBoundaries() throws Exception {
    try (var fixture = new Fixture()) {
      var service = fixture.context.getBean(StockReservationService.class);
      assertTrue(AopUtils.isAopProxy(service));
      assertFalse(AopUtils.isAopProxy(fixture.context.getBean(StockController.class)));
      fixture.seedStock(7, 10);
      var claimConnection = new AtomicReference<Long>();
      var writeConnection = new AtomicReference<Long>();
      var proofConnection = new AtomicReference<Long>();
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                claimConnection.set(fixture.connectionId());
                return invocation.callRealMethod();
              })
          .when(fixture.stocks)
          .insertReservationRequest(anyString(), anyString(), anyString(), anyString(), any());
      doAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                writeConnection.set(fixture.connectionId());
                return invocation.callRealMethod();
              })
          .when(fixture.stocks)
          .updateAvailableWithVersion(anyLong(), anyInt(), anyInt(), any());
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                proofConnection.set(fixture.connectionId());
                return invocation.callRealMethod();
              })
          .when(fixture.stocks)
          .findReservationFlows(anyString());

      assertEquals(
          "RESERVED",
          ((com.cloudmall.stock.domain.vo.StockReservationVO)
                  service.reserve(request("core-reserve", 7, 2)))
              .status);
      assertNotEquals(claimConnection.get(), writeConnection.get());
      assertEquals(writeConnection.get(), proofConnection.get());
      assertEquals(
          "ALREADY_RESERVED",
          ((com.cloudmall.stock.domain.vo.StockReservationVO)
                  service.reserve(request("core-reserve", 7, 2)))
              .status);
      assertEquals(8, fixture.quantity(7));
      assertEquals(1, fixture.count("stock_flow"));
      assertEquals(1, fixture.count("stock_cache_repair"));

      fixture
          .mvc
          .perform(
              post("/api/stock/reservations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"orderNo\":\"core-rejected\",\"items\":[{\"skuId\":7,\"quantity\":99}]}"))
          .andExpect(status().isConflict());
      assertEquals("REJECTED", fixture.state("core-rejected"));
      assertEquals(8, fixture.quantity(7));
      fixture.jdbc.execute(
          "CREATE TRIGGER core_reject_flow BEFORE INSERT ON stock_flow FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CORE_FLOW_FAILURE'");
      var sqlFailure =
          assertThrows(
              org.springframework.jdbc.UncategorizedSQLException.class,
              () -> service.reserve(request("core-failure", 7, 1)));
      assertEquals("45000", sqlFailure.getSQLException().getSQLState());
      assertEquals("INIT", fixture.state("core-failure"));
      assertEquals(8, fixture.quantity(7));
      assertEquals(1, fixture.count("stock_flow"));
      assertEquals(1, fixture.count("stock_cache_repair"));
      fixture.jdbc.execute("DROP TRIGGER core_reject_flow");
      service.reserve(request("core-failure", 7, 1));
      assertEquals(7, fixture.quantity(7));
      assertEquals("RESERVED", fixture.state("core-failure"));
    }
  }

  @Test
  void legacyMarkersClearOnlyAfterCommitAndRemainWhenOuterTransactionRollsBack() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.seedStock(7, 10);
      var service = fixture.context.getBean(StockReservationService.class);
      service.reserve(request("core-confirm", 7, 2));
      String marker = fixture.own("stock:reservation:core-confirm");
      String lines = fixture.own(marker + ":lines");
      fixture.redis.opsForValue().set(marker, "original");
      fixture.redis.opsForValue().set(lines, "original-lines");
      var transaction =
          new TransactionTemplate(fixture.context.getBean(PlatformTransactionManager.class));
      transaction.executeWithoutResult(
          status -> {
            service.confirm("core-confirm");
            assertEquals("original", fixture.redis.opsForValue().get(marker));
            assertEquals("original-lines", fixture.redis.opsForValue().get(lines));
            status.setRollbackOnly();
          });
      assertEquals("RESERVED", fixture.state("core-confirm"));
      assertTrue(fixture.redis.hasKey(marker));
      transaction.executeWithoutResult(
          status -> {
            service.confirm("core-confirm");
            assertTrue(fixture.redis.hasKey(marker));
          });
      assertEquals("CONFIRMED", fixture.state("core-confirm"));
      assertFalse(fixture.redis.hasKey(marker));
      assertFalse(fixture.redis.hasKey(lines));
      assertEquals(
          2,
          fixture.jdbc.queryForObject(
              "SELECT sold_quantity FROM stock_sku WHERE sku_id=7", Integer.class));
    }
  }

  @Test
  void realLuaClaimAcceptanceAndOutboxKeepOneOrderAcrossHttpReplay() throws Exception {
    try (var fixture = new Fixture()) {
      String meta = fixture.own("seckill:meta:9:7");
      String stock = fixture.own("seckill:stock:9:7");
      fixture.own("seckill:user:9:8");
      fixture.own("seckill:quota:revision:9:7");
      fixture.own("seckill:accepted:9:7:8:core-key");
      Instant now = Instant.now();
      fixture
          .redis
          .opsForHash()
          .putAll(
              meta,
              Map.of(
                  "stockLimit",
                  "5",
                  "perUserLimit",
                  "1",
                  "startAt",
                  now.minusSeconds(60).toString(),
                  "endAt",
                  now.plusSeconds(600).toString()));
      fixture.redis.opsForValue().set(stock, "5");
      AuthContext.set(8L, "USER");
      String body =
          "{\"activityId\":9,\"skuId\":7,\"userId\":8,\"idempotencyKey\":\"core-key\",\"addressSnapshot\":\"{}\"}";
      try {
        var first =
            fixture
                .json
                .readTree(
                    fixture
                        .mvc
                        .perform(
                            post("/api/stock/seckill/reservations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.accepted").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("data");
        String orderNo = first.path("orderNo").asText();
        fixture.own("seckill:quota:op:" + orderNo);
        fixture
            .mvc
            .perform(
                post("/api/stock/seckill/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderNo").value(orderNo))
            .andExpect(jsonPath("$.data.eventId").value(first.path("eventId").asText()));
        assertEquals("4", fixture.redis.opsForValue().get(stock));
        assertEquals(1, fixture.count("seckill_reservation"));
        assertEquals(1, fixture.count("seckill_order_outbox"));
        assertEquals(1, fixture.count("seckill_quota_settlement"));
        assertEquals(0, fixture.count("stock_flow"));
        assertEquals(
            "ACCEPTED",
            fixture.jdbc.queryForObject("SELECT status FROM seckill_reservation", String.class));
      } finally {
        AuthContext.clear();
      }
    }
  }

  private static StockReservationDTO request(String orderNo, long skuId, int quantity) {
    var request = new StockReservationDTO();
    request.orderNo = orderNo;
    request.scene = "NORMAL";
    request.items = List.of(new StockLineDTO(skuId, quantity));
    return request;
  }

  /** 仅由此IT显式启用的基础设施，避免进入真实应用扫描。 */
  @Configuration
  @Profile("stock-core-refactor-it")
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  @MapperScan("com.cloudmall.stock.mapper")
  static class Infrastructure {}

  /** 独占UUID数据库及本实验Redis DB15的精确Key清单。 */
  static class Fixture implements AutoCloseable {
    /** 本例独占的UUID数据库名。 */
    private final String schema = "stock_core_" + UUID.randomUUID().toString().replace("-", "");

    /** 本例精确拥有的Redis Key。 */
    private final List<String> keys = new ArrayList<>();

    /** 创建与删除本例数据库的管理连接。 */
    private final JdbcTemplate administrator;

    /** 本例准备与断言用的真实SQL连接。 */
    private final JdbcTemplate jdbc;

    /** HTTP与快照共享的JSON配置。 */
    private final ObjectMapper json = new ObjectMapper();

    /** 真实Mapper的连接观察探针，不替代SQL执行。 */
    private final StockSqlMapper stocks;

    /** 独立实验Redis DB15的连接资源。 */
    private final LettuceConnectionFactory connections;

    /** 实际执行原Lua和标记清理的模板。 */
    private final StringRedisTemplate redis;

    /** 持有实际业务代理和事务基础设施的上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 使用实际组合Controller验证HTTP链。 */
    private final MockMvc mvc;

    Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_stock"))
        throw new IllegalStateException("Explicit isolated Stock MySQL URL required");
      int redisPort = Integer.parseInt(System.getProperty("cloudmall.stock.refactor.redis.port"));
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      DataSource dataSource =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_stock", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(dataSource);
      for (String table :
          List.of(
              "stock_sku",
              "stock_flow",
              "stock_reservation_request",
              "stock_cache_repair",
              "seckill_reservation",
              "seckill_order_outbox",
              "seckill_quota_settlement"))
        jdbc.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
      var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", redisPort);
      redisConfig.setPassword(RedisPassword.of("root"));
      redisConfig.setDatabase(15);
      connections = new LettuceConnectionFactory(redisConfig);
      connections.afterPropertiesSet();
      redis = new StringRedisTemplate(connections);
      stocks = spy(new StockSqlMapper(jdbc));
      var factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      SqlSessionFactory sessions = factory.getObject();
      context.getEnvironment().setActiveProfiles("stock-core-refactor-it");
      context.register(
          Infrastructure.class,
          StockDependencyAspect.class,
          StockController.class,
          StockQueryServiceImpl.class,
          StockReservationServiceImpl.class,
          SeckillReservationServiceImpl.class,
          StockReservationReader.class,
          StockReservationSnapshotCodec.class,
          StockReservationRequestService.class,
          SeckillQuotaSettlementService.class,
          SeckillAcceptanceService.class,
          SeckillRequestClaimService.class);
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(SqlSessionFactory.class, () -> sessions);
      context.registerBean(
          "transactionManager",
          DataSourceTransactionManager.class,
          () -> new DataSourceTransactionManager(dataSource));
      context.registerBean(StockSqlMapper.class, () -> stocks);
      context.registerBean(ObjectMapper.class, () -> json);
      context.registerBean(StringRedisTemplate.class, () -> redis);
      try {
        context.refresh();
      } catch (RuntimeException failure) {
        context.close();
        connections.destroy();
        administrator.execute("DROP DATABASE " + schema);
        throw failure;
      }
      mvc =
          MockMvcBuilders.standaloneSetup(context.getBean(StockController.class))
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
    }

    private String own(String key) {
      keys.add(key);
      return key;
    }

    private void seedStock(long skuId, int quantity) {
      jdbc.update("INSERT INTO stock_sku VALUES(?,41,?,?,0,0,0,NOW(3))", skuId, quantity, quantity);
    }

    private int quantity(long skuId) {
      return jdbc.queryForObject(
          "SELECT available_quantity FROM stock_sku WHERE sku_id=?", Integer.class, skuId);
    }

    private String state(String orderNo) {
      return jdbc.queryForObject(
          "SELECT status FROM stock_reservation_request WHERE order_no=?", String.class, orderNo);
    }

    private int count(String table) {
      return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long connectionId() {
      return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 关闭真实代理与Redis连接，只删除本例精确资源。 */
    @Override
    public void close() {
      try {
        context.close();
        if (!keys.isEmpty()) redis.delete(keys);
      } finally {
        connections.destroy();
        if (!schema.matches("stock_core_[0-9a-f]{32}"))
          throw new IllegalStateException("Invalid owned schema");
        administrator.execute("DROP DATABASE " + schema);
      }
    }
  }
}
