package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实结算状态操作键读写及实际Order fallback证明；Order行用Mapper替身，不冒充HTTP/分布式验收。 */
class SeckillTerminalOperationKeyMySqlIT {
  /** 原Stock库只读连接。 */
  private static JdbcTemplate source;

  /** 原表清单与校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 仅当前IT拥有的库。 */
  private static String database;

  /** 只在自建库准备与核验完整行事实。 */
  private static JdbcTemplate fixture;

  /** 被测数据源：GET只准无锁SELECT；写只准事务内原INSERT及FOR UPDATE。 */
  private static SelectOnlyDataSource tracked;

  /** 实际Stock SQL层。 */
  private static StockSqlMapper mapper;

  /** 无Redis/Rabbit资源的实际Stock实现，只经本fixture验证GET路径。 */
  private static StockServiceImpl service;

  /** 实际MyBatis秒杀结算读取Mapper。 */
  private static SeckillQuotaSettlementMapper settlementMapper;

  /** 真实事务代理下的秒杀结算服务；GET不触发建表、迁移或事务。 */
  private static SeckillQuotaSettlementService settlementService;

  /** 仅写路径启用真实同数据源事务，GET仍不冒称RR。 */
  private static DataSourceTransactionManager transactions;

  /** 复用标准Maven输出加载实际Order fallback，仅用于测试。 */
  private static URLClassLoader orderClasses;

  /** Order已有依赖仅供childloader加载，finally删除自有文件/目录。 */
  private static Path dependencyDirectory;

  /** 本次从标准Order Boot jar提取的已有依赖文件。 */
  private static final List<Path> dependencyJars = new ArrayList<>();

  /** 本类所有病例对应的原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  /** 被测秒杀/普通Stock事实表；GET、终态noop及拒绝写前后均核完整行不变。 */
  private static final List<String> TABLES =
      List.of(
          "stock_flow",
          "stock_reservation_request",
          "seckill_reservation",
          "seckill_quota_settlement",
          "stock_sku",
          "stock_cache_repair");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_stock(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_stock URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = fingerprint();
    database = "cm16t34_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    var dataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_stock", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(dataSource);
    for (String table : TABLES) {
      if ("seckill_quota_settlement".equals(table)) continue;
      fixture.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
    }
    tracked = new SelectOnlyDataSource(dataSource);
    mapper = new StockSqlMapper(new JdbcTemplate(tracked));
    MybatisConfiguration mybatisConfiguration = new MybatisConfiguration();
    mybatisConfiguration.setMapUnderscoreToCamelCase(true);
    mybatisConfiguration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    mybatisConfiguration.addMapper(SeckillQuotaSettlementMapper.class);
    MybatisSqlSessionFactoryBean sessionFactory = new MybatisSqlSessionFactoryBean();
    sessionFactory.setDataSource(tracked);
    sessionFactory.setConfiguration(mybatisConfiguration);
    SqlSessionTemplate sqlSession = new SqlSessionTemplate(sessionFactory.getObject());
    settlementMapper = sqlSession.getMapper(SeckillQuotaSettlementMapper.class);
    settlementMapper.ensureTable();
    transactions = new DataSourceTransactionManager(tracked);
    var settlementProxy =
        new ProxyFactory(new SeckillQuotaSettlementService(settlementMapper, mapper));
    settlementProxy.setProxyTargetClass(true);
    settlementProxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    settlementService = (SeckillQuotaSettlementService) settlementProxy.getProxy();
    service = new StockServiceImpl(null, mapper, null, new ObjectMapper());
    ReflectionTestUtils.setField(service, "seckillSettlementService", settlementService);
    Path classes = Path.of("../cloud-mall-order/target/classes").toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(classes))
      throw new IllegalStateException("先通过Maven构建Order标准classes");
    List<java.net.URL> urls = new ArrayList<>();
    urls.add(classes.toUri().toURL());
    try (JarFile jar =
        new JarFile(
            Path.of("../cloud-mall-order/target/cloud-mall-order-0.1.0-SNAPSHOT.jar").toFile())) {
      for (String[] dependency :
          List.of(
              new String[] {"feign.FeignException", "feign-core-"},
              new String[] {
                "org.springframework.cloud.openfeign.FeignClient", "spring-cloud-openfeign-core-"
              })) {
        try {
          Class.forName(
              dependency[0], false, SeckillTerminalOperationKeyMySqlIT.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
          var entries =
              jar.stream()
                  .filter(
                      entry ->
                          entry.getName().startsWith("BOOT-INF/lib/" + dependency[1])
                              && entry.getName().endsWith(".jar"))
                  .toList();
          if (entries.size() != 1)
            throw new IllegalStateException("Order artifact须有唯一声明依赖:" + dependency[1]);
          if (dependencyDirectory == null)
            dependencyDirectory = Files.createTempDirectory("cm16t34_order_deps_");
          Path output = dependencyDirectory.resolve(dependency[1] + "existing.jar");
          try (var input = jar.getInputStream(entries.get(0))) {
            Files.copy(input, output);
          }
          dependencyJars.add(output);
          urls.add(output.toUri().toURL());
        }
      }
    }
    orderClasses =
        new URLClassLoader(
            urls.toArray(java.net.URL[]::new),
            SeckillTerminalOperationKeyMySqlIT.class.getClassLoader());
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    tracked.writePhase = false;
    tracked.allowTransition = false;
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    tracked.sql.clear();
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (orderClasses != null) orderClasses.close();
    for (Path dependency : dependencyJars) Files.deleteIfExists(dependency);
    if (dependencyDirectory != null) Files.deleteIfExists(dependencyDirectory);
    if (tracked != null) tracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (database != null && database.matches("cm16t34_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
      }
    }
    System.out.println(
        "CM16T34 original Stock checksums unchanged; owned database and classloader removed");
  }

  @Test
  void corruptedTerminalGetAndActualOrderFallbackStayUnknown() throws Exception {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String key : badKeys(state))
        assertions.add(
            () -> {
              reset();
              seedSettlement(state, key);
              var before = facts();
              tracked.checking = true;
              var observed = read();
              List<org.junit.jupiter.api.function.Executable> evidence = new ArrayList<>();
              evidence.add(
                  () -> {
                    assertEquals("UNKNOWN", observed.status, state + ":" + key);
                    assertTrue(observed.items.isEmpty());
                  });
              for (String orderState : List.of("PAID", "CANCELLED"))
                evidence.add(
                    () -> {
                      tracked.sql.clear();
                      assertEquals("UNKNOWN", orderFallback(orderState), state + key + orderState);
                      assertEquals(1, tracked.sql.size());
                      assertOnlySelects();
                    });
              assertAll(evidence);
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void corruptedTerminalWriteCannotAcknowledgeOrChooseDirection() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String key : badKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                reset();
                seedSettlement(state, key);
                var before = facts();
                tracked.checking = true;
                tracked.writePhase = true;
                BizException failure =
                    assertThrows(
                        BizException.class, () -> settlementService.settle(ORDER_NO, action));
                assertEquals(500, failure.getHttpStatus());
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertEquals(2, tracked.sql.size());
                assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
                assertEquals(before, facts());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              });
    assertAll(assertions);
  }

  @Test
  void exactTerminalAndOtherStatesKeepPreviousScopeAndProtocol() throws Exception {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      reset();
      String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
      seedSettlement(state, ORDER_NO + ":" + action);
      var before = facts();
      tracked.checking = true;
      var result = read();
      assertEquals("CONFIRMED".equals(state) ? "CONFIRMED" : "ROLLED_BACK", result.status);
      assertEquals(1, result.items.size());
      assertEquals("SUCCEEDED", orderFallback("CONFIRMED".equals(state) ? "PAID" : "CANCELLED"));
      tracked.sql.clear();
      tracked.writePhase = true;
      assertEquals(
          true, ((Map<?, ?>) settlementService.settle(ORDER_NO, action).data).get("idempotent"));
      assertEquals(3, tracked.sql.size());
      assertEquals(before, facts());
      tracked.sql.clear();
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () ->
                      settlementService.settle(
                          ORDER_NO, "CONFIRM".equals(action) ? "ROLLBACK" : "CONFIRM"))
              .getHttpStatus());
      assertEquals(2, tracked.sql.size());
      assertEquals(before, facts());
    }
    for (String state :
        List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED", "CONFLICT", "other-state")) {
      reset();
      String key =
          "RELEASE_PENDING".equals(state) || "PROJECTION_BLOCKED".equals(state)
              ? ORDER_NO + ":ROLLBACK"
              : null;
      seedSettlement(state, key);
      var before = facts();
      tracked.checking = true;
      var result = read();
      assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", result.status);
      assertEquals(
          "CONFLICT".equals(state) || "other-state".equals(state) ? 0 : 1, result.items.size());
      assertOnlySelects();
      assertEquals(before, facts());
    }
  }

  @Test
  void actualControllerGetAndTransactionalWriteDoNotLeakOrGrantSuccess() throws Exception {
    for (String state : List.of("CONFIRMED", "OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      reset();
      seedSettlement(state, "private-other:CONFIRM");
      var before = facts();
      StockController controller = new StockController();
      ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
      ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
      ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
      var proxy = new ProxyFactory(controller);
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(
          new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
      var mvc =
          MockMvcBuilders.standaloneSetup(proxy.getProxy())
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      tracked.checking = true;
      String getBody =
          mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
              .andExpect(jsonPath("$.data.items").isEmpty())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(getBody.contains("private-other"));
      assertOnlySelects();
      tracked.sql.clear();
      tracked.writePhase = true;
      String postBody =
          mvc.perform(post("/api/stock/reservations/" + ORDER_NO + "/confirm"))
              .andExpect(status().isInternalServerError())
              .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CORRUPT"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(postBody.contains("private-other"));
      assertEquals(2, tracked.sql.size());
      assertEquals(before, facts());
    }
  }

  @Test
  void corruptedNonTerminalGetAndActualOrderFallbackCannotUseCandidateItems() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : badNonTerminalKeys(state))
        assertions.add(
            () -> {
              reset();
              seedSettlement(state, key);
              var before = facts();
              tracked.checking = true;
              var observed = read();
              assertEquals("UNKNOWN", observed.status, state + ":" + key);
              assertTrue(observed.items.isEmpty());
              assertEquals(ORDER_NO, observed.orderNo);
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              for (String orderState : List.of("PAID", "CANCELLED")) {
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback(orderState));
                assertEquals(1, tracked.sql.size());
                assertOnlySelects();
              }
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void corruptedNonTerminalWriteCannotLetCiAliasPassRealCas() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : badNonTerminalKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                reset();
                seedSettlement(state, key);
                var before = facts();
                tracked.checking = true;
                tracked.writePhase = true;
                // 红阶段让原真实CAS暴露CI覆盖；绿阶段必须两SQL拒绝，绝无UPDATE。
                tracked.allowTransition = true;
                var failure =
                    assertThrows(
                        BizException.class, () -> settlementService.settle(ORDER_NO, action));
                assertEquals(500, failure.getHttpStatus(), state + ":" + key);
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertFalse(failure.getMessage().contains("202610"));
                assertEquals(2, tracked.sql.size());
                assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
                assertEquals(before, facts());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              });
    assertAll(assertions);
  }

  @Test
  void exactNonTerminalKeysKeepRealCasRevisionAndCrossDirectionProtocol() {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : legalNonTerminalKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK")) {
          reset();
          seedSettlement(state, key);
          var before = facts();
          tracked.checking = true;
          var observed = read();
          assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", observed.status);
          assertEquals(1, observed.items.size());
          assertOnlySelects();
          assertEquals(before, facts());
          tracked.sql.clear();
          tracked.writePhase = true;
          tracked.allowTransition = true;
          boolean sameDirection = key == null || key.equals(ORDER_NO + ":" + action);
          if (!sameDirection || "RELEASE_PENDING".equals(state) && "CONFIRM".equals(action)) {
            var failure =
                assertThrows(BizException.class, () -> settlementService.settle(ORDER_NO, action));
            assertEquals("OPEN".equals(state) ? 503 : 409, failure.getHttpStatus());
            assertEquals(
                "OPEN".equals(state) ? "SECKILL_SETTLEMENT_UNKNOWN" : "SECKILL_SETTLEMENT_CONFLICT",
                failure.getCode());
            assertEquals("OPEN".equals(state) ? 3 : 2, tracked.sql.size());
            assertEquals(before, facts());
          } else {
            Map<?, ?> response = (Map<?, ?>) settlementService.settle(ORDER_NO, action).data;
            if ("CONFIRM".equals(action)) assertEquals(true, response.get("confirmed"));
            else {
              assertEquals(false, response.get("rolledBack"));
              assertEquals(true, response.get("pending"));
              assertEquals("UNKNOWN", response.get("status"));
            }
            var after = facts();
            for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
              if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
            var actual = after.get(3).get(0);
            String expectedState =
                "CONFIRM".equals(action)
                    ? "CONFIRMED"
                    : "OPEN".equals(state) ? "RELEASE_PENDING" : state;
            assertEquals(expectedState, actual.get("status"));
            assertEquals(ORDER_NO + ":" + action, actual.get("operation_key"));
            assertEquals(
                "OPEN".equals(state) && "CONFIRM".equals(action) ? 3L : 2L,
                ((Number) actual.get("revision")).longValue());
            var originalStable = new java.util.HashMap<>(before.get(3).get(0));
            var actualStable = new java.util.HashMap<>(actual);
            for (String changed :
                List.of("status", "operation_key", "revision", "updated_at", "next_retry_at")) {
              originalStable.remove(changed);
              actualStable.remove(changed);
            }
            assertEquals(originalStable, actualStable);
            assertEquals(3, tracked.sql.size());
            if (!"OPEN".equals(state) && "ROLLBACK".equals(action)) assertEquals(before, after);
          }
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
  }

  /** 不规范化真实坏键；OPEN允许NULL，pending不能保confirm方向。 */
  private static List<String> badNonTerminalKeys(String state) {
    List<String> keys =
        new ArrayList<>(
            List.of(
                "",
                "private-other:ROLLBACK",
                "202610PROOF1:ROLLBACK",
                "202610próof1:ROLLBACK",
                ORDER_NO + ":ROLLBACK ",
                ORDER_NO + ":rollback",
                ORDER_NO + ":OTHER"));
    if (!"OPEN".equals(state)) keys.add(null);
    if ("RELEASE_PENDING".equals(state)) keys.add(ORDER_NO + ":CONFIRM");
    return keys;
  }

  /** 保既有OPEN空键/两方向与blocked兼容，不把跨向CAS503改成409。 */
  private static List<String> legalNonTerminalKeys(String state) {
    if ("OPEN".equals(state))
      return java.util.Arrays.asList(null, ORDER_NO + ":CONFIRM", ORDER_NO + ":ROLLBACK");
    if ("RELEASE_PENDING".equals(state)) return List.of(ORDER_NO + ":ROLLBACK");
    return List.of(ORDER_NO + ":CONFIRM", ORDER_NO + ":ROLLBACK");
  }

  @Test
  void invalidOriginalIdempotencyDimensionRejectsGetAndActualController() throws Exception {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state :
        List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : List.of("", " ", "\t\n", "\u2003", "null"))
        assertions.add(
            () -> {
              reset();
              seedIdempotencySettlement(state, key);
              var before = facts();
              tracked.checking = true;
              var failure = assertThrows(BizException.class, () -> read());
              assertEquals(500, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
              assertEquals("秒杀结算事实维度无效", failure.getMessage());
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              for (String orderState : List.of("PAID", "CANCELLED")) {
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback(orderState));
                assertEquals(1, tracked.sql.size());
                assertOnlySelects();
              }
              assertEquals(before, facts());
            });
    assertAll(assertions);
    reset();
    seedIdempotencySettlement("CONFIRMED", "null");
    var before = facts();
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var proxy = new ProxyFactory(controller);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    var mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    tracked.checking = true;
    mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CORRUPT"))
        .andExpect(
            result ->
                assertEquals(
                    "秒杀结算事实维度无效",
                    new ObjectMapper()
                        .readTree(result.getResponse().getContentAsByteArray())
                        .path("message")
                        .asText()));
    assertEquals(1, tracked.sql.size());
    tracked.sql.clear();
    tracked.writePhase = true;
    mvc.perform(
            post("/api/stock/reservations/" + ORDER_NO + "/confirm")
                .contentType("application/json")
                .content("{\"operationKey\":\"" + ORDER_NO + ":CONFIRM\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CORRUPT"))
        .andExpect(
            result ->
                assertEquals(
                    "秒杀结算事实维度无效",
                    new ObjectMapper()
                        .readTree(result.getResponse().getContentAsByteArray())
                        .path("message")
                        .asText()));
    assertEquals(2, tracked.sql.size());
    assertEquals(before, facts());
  }

  @Test
  void invalidOriginalIdempotencyDimensionStopsRealWriteBeforeAnyUpdate() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state :
        List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : List.of("", " ", "\t\n", "\u2003", "null"))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                reset();
                seedIdempotencySettlement(state, key);
                var before = facts();
                tracked.checking = true;
                tracked.writePhase = true;
                // 红阶段允许原表CAS显示旧错误；绿阶段必须两SQL且所有事实不变。
                tracked.allowTransition = true;
                var failure =
                    assertThrows(
                        BizException.class, () -> settlementService.settle(ORDER_NO, action));
                assertEquals(500, failure.getHttpStatus());
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertEquals("秒杀结算事实维度无效", failure.getMessage());
                assertEquals(2, tracked.sql.size());
                assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
                assertEquals(before, facts());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              });
    assertAll(assertions);
  }

  @Test
  void exactOriginalIdempotencyBoundaryKeepsDatabaseUnicodeAndReplay() {
    for (String state :
        List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key :
          List.of(
              "private-idem",
              " private-idem ",
              "NULL",
              "null ",
              "x".repeat(128),
              "😺".repeat(128))) {
        reset();
        seedIdempotencySettlement(state, key);
        assertEquals(
            key,
            fixture.queryForObject(
                "SELECT idempotency_key FROM seckill_quota_settlement", String.class));
        var before = facts();
        tracked.checking = true;
        var observed = read();
        assertEquals(
            "OPEN".equals(state)
                ? "RESERVED"
                : "CONFIRMED".equals(state)
                    ? "CONFIRMED"
                    : "RELEASED".equals(state) ? "ROLLED_BACK" : "UNKNOWN",
            observed.status);
        assertEquals(1, observed.items.size());
        assertOnlySelects();
        assertEquals(before, facts());
        if (!"OPEN".equals(state)) {
          tracked.sql.clear();
          tracked.writePhase = true;
          String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
          var response = (Map<?, ?>) settlementService.settle(ORDER_NO, action).data;
          if ("CONFIRMED".equals(state) || "RELEASED".equals(state))
            assertEquals(true, response.get("idempotent"));
          else {
            assertEquals(true, response.get("pending"));
            assertEquals(false, response.get("rolledBack"));
          }
          assertEquals(3, tracked.sql.size());
          assertEquals(before, facts());
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
      }
  }

  /** 仅owned当前NOTNULL/VARCHAR128准备可存原键，不ALTER或补造受理来源。 */
  private static void seedIdempotencySettlement(String state, String key) {
    String operationKey =
        "OPEN".equals(state)
            ? null
            : ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK");
    seedSettlement(state, operationKey);
    fixture.update(
        "UPDATE seckill_quota_settlement SET idempotency_key=? WHERE order_no=?", key, ORDER_NO);
    fixture.update(
        "UPDATE seckill_reservation SET idempotency_key=? WHERE order_no=?", key, ORDER_NO);
  }

  @Test
  void badReleasedReceiptsStayUnknownForStockAndActualOrderFallback() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (Long receipt :
        java.util.Arrays.asList(null, 0L, -1L, Long.MIN_VALUE, 1000000000001L, Long.MAX_VALUE))
      assertions.add(
          () -> {
            reset();
            seedReleasedReceipt(receipt);
            var before = facts();
            tracked.checking = true;
            var result = read();
            assertAll(
                () -> assertEquals("UNKNOWN", result.status, String.valueOf(receipt)),
                () -> assertTrue(result.items.isEmpty()),
                () -> assertEquals(ORDER_NO, result.orderNo));
            assertOnlySelects();
            for (String orderState : List.of("PAID", "CANCELLED")) {
              tracked.sql.clear();
              assertEquals("UNKNOWN", orderFallback(orderState));
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
            }
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void badReleasedReceiptsBlockRealTransactionalWritesAndActualController() throws Exception {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (Long receipt :
        java.util.Arrays.asList(null, 0L, -1L, Long.MIN_VALUE, 1000000000001L, Long.MAX_VALUE))
      for (String action : List.of("CONFIRM", "ROLLBACK"))
        assertions.add(
            () -> {
              reset();
              seedReleasedReceipt(receipt);
              var before = facts();
              tracked.checking = true;
              tracked.writePhase = true;
              var failure =
                  assertThrows(
                      BizException.class, () -> settlementService.settle(ORDER_NO, action));
              assertEquals(500, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
              assertEquals("秒杀释放回执缺证", failure.getMessage());
              assertEquals(2, tracked.sql.size());
              assertEquals(before, facts());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            });
    assertAll(assertions);
    reset();
    seedReleasedReceipt(null);
    var before = facts();
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var proxy = new ProxyFactory(controller);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    var mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    tracked.checking = true;
    mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.items").isEmpty());
    assertOnlySelects();
    for (String action : List.of("confirm", "rollback")) {
      tracked.sql.clear();
      tracked.writePhase = true;
      byte[] response =
          mvc.perform(post("/api/stock/reservations/" + ORDER_NO + "/" + action))
              .andExpect(status().isInternalServerError())
              .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CORRUPT"))
              .andReturn()
              .getResponse()
              .getContentAsByteArray();
      assertEquals("秒杀释放回执缺证", new ObjectMapper().readTree(response).get("message").asText());
      assertEquals(2, tracked.sql.size());
    }
    assertEquals(before, facts());
  }

  @Test
  void exactReleasedReceiptBoundariesKeepRealGetNoopAndConflict() throws Exception {
    for (long receipt : new long[] {1L, 4L, 1000000000000L}) {
      reset();
      seedReleasedReceipt(receipt);
      var before = facts();
      tracked.checking = true;
      var result = read();
      assertEquals("ROLLED_BACK", result.status);
      assertEquals(1, result.items.size());
      assertOnlySelects();
      tracked.sql.clear();
      assertEquals("SUCCEEDED", orderFallback("CANCELLED"));
      assertOnlySelects();
      tracked.sql.clear();
      tracked.writePhase = true;
      var replay = (Map<?, ?>) settlementService.settle(ORDER_NO, "ROLLBACK").data;
      assertEquals(true, replay.get("rolledBack"));
      assertEquals(true, replay.get("idempotent"));
      assertEquals(3, tracked.sql.size());
      tracked.sql.clear();
      assertEquals(
          409,
          assertThrows(BizException.class, () -> settlementService.settle(ORDER_NO, "CONFIRM"))
              .getHttpStatus());
      assertEquals(2, tracked.sql.size());
      assertEquals(before, facts());
    }
  }

  /** 仅 owned 原 BIGINT 列准备回执变异，不改表结构。 */
  private static void seedReleasedReceipt(Long receipt) {
    seedSettlement("RELEASED", ORDER_NO + ":ROLLBACK");
    fixture.update(
        "UPDATE seckill_quota_settlement SET redis_revision=? WHERE order_no=?", receipt, ORDER_NO);
  }

  @Test
  void realTransitionsCannotWriteWithoutMatchingAcceptanceSource() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String transition : List.of("open-confirm", "open-rollback", "blocked-confirm"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              seedTransition(transition);
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              tracked.writePhase = true;
              tracked.allowTransition = true;
              Integer changed =
                  new org.springframework.transaction.support.TransactionTemplate(transactions)
                      .execute(transactionStatus -> applyTransition(transition));
              assertAll(
                  () -> assertEquals(Integer.valueOf(0), changed, transition + ":" + variant),
                  () -> assertEquals(before, facts(), transition + ":" + variant),
                  () -> assertEquals(1, tracked.sql.size()));
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            });
    assertAll(assertions);
  }

  @Test
  void transactionalServiceKeepsUnknown503ForUnprovenTransitionSource() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String transition : List.of("open-confirm", "open-rollback", "blocked-confirm"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              seedTransition(transition);
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              tracked.writePhase = true;
              tracked.allowTransition = true;
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> settlementService.settle(ORDER_NO, transitionAction(transition)));
              assertEquals(503, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_UNKNOWN", failure.getCode());
              assertEquals(3, tracked.sql.size());
              assertTrue(tracked.sql.get(0).startsWith("INSERT IGNORE"));
              assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
              assertTrue(tracked.sql.get(2).startsWith("UPDATE"));
              assertEquals(before, facts());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerCannotAcknowledgeOrClearMissingSourceTransitions() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var proxy = new ProxyFactory(controller);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    var mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String transition : List.of("open-confirm", "open-rollback", "blocked-confirm"))
      assertions.add(
          () -> {
            reset();
            seedTransition(transition);
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            tracked.writePhase = true;
            tracked.allowTransition = true;
            mvc.perform(
                    post(
                        "/api/stock/reservations/"
                            + ORDER_NO
                            + "/"
                            + transitionAction(transition).toLowerCase(Locale.ROOT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_UNKNOWN"));
            assertEquals(3, tracked.sql.size());
            assertEquals(before, facts());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          });
    assertAll(assertions);
  }

  @Test
  void allOriginalAcceptedStatesKeepRealTransitionSetAndRevision() {
    for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED"))
      for (String transition : List.of("open-confirm", "open-rollback", "blocked-confirm")) {
        reset();
        seedTransition(transition);
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var before = facts();
        tracked.checking = true;
        tracked.writePhase = true;
        tracked.allowTransition = true;
        var response = settlementService.settle(ORDER_NO, transitionAction(transition));
        assertEquals("0", response.code);
        if ("open-rollback".equals(transition)) {
          assertEquals(false, ((Map<?, ?>) response.data).get("rolledBack"));
          assertEquals(true, ((Map<?, ?>) response.data).get("pending"));
        } else assertEquals(true, ((Map<?, ?>) response.data).get("confirmed"));
        assertEquals(3, tracked.sql.size());
        var after = facts();
        for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
          if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
        var stored = after.get(3).get(0);
        assertEquals(
            "open-rollback".equals(transition) ? "RELEASE_PENDING" : "CONFIRMED",
            stored.get("status"));
        assertEquals(
            "open-confirm".equals(transition) ? 3L : 2L,
            ((Number) stored.get("revision")).longValue());
        assertEquals(ORDER_NO + ":" + transitionAction(transition), stored.get("operation_key"));
        assertNull(stored.get("lease_owner"));
        assertNull(stored.get("lease_until"));
        assertNull(stored.get("last_error"));
        assertNull(stored.get("redis_revision"));
        if ("open-rollback".equals(transition)) assertNotNull(stored.get("next_retry_at"));
        else assertNull(stored.get("next_retry_at"));
        var originalStable = new java.util.HashMap<>(before.get(3).get(0));
        var actualStable = new java.util.HashMap<>(stored);
        for (String changed :
            List.of(
                "status",
                "operation_key",
                "revision",
                "updated_at",
                "next_retry_at",
                "lease_owner",
                "lease_until",
                "last_error")) {
          originalStable.remove(changed);
          actualStable.remove(changed);
        }
        assertEquals(originalStable, actualStable);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      }
  }

  /** 仅自有事实准备三个真实CAS路径；blocked诊断/租约可验证拒绝不清理。 */
  private static void seedTransition(String transition) {
    seedSettlement(
        "blocked-confirm".equals(transition) ? "PROJECTION_BLOCKED" : "OPEN",
        "blocked-confirm".equals(transition) ? ORDER_NO + ":CONFIRM" : null);
    if ("blocked-confirm".equals(transition))
      fixture.update(
          "UPDATE seckill_quota_settlement SET"
              + " lease_owner='owned-existing-lease',lease_until='2099-01-01',last_error='owned-diagnostic',next_retry_at='2000-01-01'");
  }

  /** 原方向，只用于测试请求，不从被测SQL派生结论。 */
  private static String transitionAction(String transition) {
    return "open-rollback".equals(transition) ? "ROLLBACK" : "CONFIRM";
  }

  /** 真实MyBatis SQL返回影响行数，事务由调用方管理。 */
  private static int applyTransition(String transition) {
    return switch (transition) {
      case "open-confirm" ->
          settlementMapper.transitionOpenToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L);
      case "open-rollback" ->
          settlementMapper.transitionToReleasePending(ORDER_NO, ORDER_NO + ":ROLLBACK", 2L);
      case "blocked-confirm" ->
          settlementMapper.transitionBlockedToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L);
      default -> throw new AssertionError(transition);
    };
  }

  /** 现存原列能够实际保存的错来源，拒绝通过排序规则借证。 */
  private static List<String> badTransitionSources() {
    return List.of(
        "missing",
        "pending",
        "unknown",
        "rejected",
        "status-case",
        "status-space",
        "order-foreign",
        "order-case",
        "order-accent",
        "order-space",
        "activity",
        "sku",
        "user",
        "idem-foreign",
        "idem-case",
        "idem-accent",
        "idem-space");
  }

  /** 只改变owned原受理记录；settlement维度/操作键保持合法。 */
  private static void corruptTransitionSource(String variant) {
    switch (variant) {
      case "missing" -> fixture.update("DELETE FROM seckill_reservation");
      case "pending", "unknown", "rejected" ->
          fixture.update(
              "UPDATE seckill_reservation SET status=?", variant.toUpperCase(Locale.ROOT));
      case "status-case" -> fixture.update("UPDATE seckill_reservation SET status='accepted'");
      case "status-space" -> fixture.update("UPDATE seckill_reservation SET status='ACCEPTED '");
      case "order-foreign" ->
          fixture.update("UPDATE seckill_reservation SET order_no='foreign-owned-source'");
      case "order-case" ->
          fixture.update(
              "UPDATE seckill_reservation SET order_no=?", ORDER_NO.toUpperCase(Locale.ROOT));
      case "order-accent" ->
          fixture.update("UPDATE seckill_reservation SET order_no='202610próof1'");
      case "order-space" ->
          fixture.update("UPDATE seckill_reservation SET order_no=?", ORDER_NO + " ");
      case "activity" -> fixture.update("UPDATE seckill_reservation SET activity_id=11");
      case "sku" -> fixture.update("UPDATE seckill_reservation SET sku_id=21");
      case "user" -> fixture.update("UPDATE seckill_reservation SET user_id=9");
      case "idem-foreign" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='foreign-owned-idem'");
      case "idem-case" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='PRIVATE-IDEM'");
      case "idem-accent" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='priváte-idem'");
      case "idem-space" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='private-idem '");
      default -> throw new AssertionError(variant);
    }
  }

  @Test
  void terminalGetAndActualOrderFallbackCannotBorrowUnprovenAcceptance() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              seedSettlement(
                  state, ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK"));
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              var result = read();
              assertAll(
                  () -> assertEquals("UNKNOWN", result.status, state + ":" + variant),
                  () -> assertTrue(result.items.isEmpty()),
                  () -> assertEquals(ORDER_NO, result.orderNo),
                  () -> assertEquals(2, tracked.sql.size()));
              assertOnlySelects();
              for (String orderState : List.of("PAID", "CANCELLED")) {
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback(orderState));
                assertEquals(2, tracked.sql.size());
                assertOnlySelects();
              }
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerReturnsUnknownEmptyForMissingTerminalSource() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      assertions.add(
          () -> {
            reset();
            seedSettlement(
                state, ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK"));
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items").isEmpty());
            assertEquals(2, tracked.sql.size());
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void originalAcceptedStatusesKeepBothTerminalGetProtocols() throws Exception {
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED")) {
        reset();
        seedSettlement(state, ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK"));
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var before = facts();
        tracked.checking = true;
        var result = read();
        assertEquals("CONFIRMED".equals(state) ? "CONFIRMED" : "ROLLED_BACK", result.status);
        assertEquals(1, result.items.size());
        assertEquals(2, tracked.sql.size());
        assertOnlySelects();
        tracked.sql.clear();
        assertEquals("SUCCEEDED", orderFallback("CONFIRMED".equals(state) ? "PAID" : "CANCELLED"));
        assertEquals(2, tracked.sql.size());
        assertOnlySelects();
        assertEquals(before, facts());
      }
  }

  @Test
  void sameDirectionTerminalReplayRejectsUnprovenSourceWithoutWrites() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
              seedSettlement(state, ORDER_NO + ":" + action);
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              tracked.writePhase = true;
              var failure =
                  assertThrows(
                      BizException.class, () -> settlementService.settle(ORDER_NO, action));
              assertEquals(503, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_UNKNOWN", failure.getCode());
              assertEquals(3, tracked.sql.size());
              assertTrue(tracked.sql.get(0).startsWith("INSERT IGNORE"));
              assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
              assertTrue(
                  tracked.sql.get(2).startsWith("SELECT ACCEPTED.ID FROM SECKILL_RESERVATION"));
              assertFalse(tracked.sql.get(2).contains("FOR UPDATE"));
              assertEquals(before, facts());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              tracked.sql.clear();
              assertEquals(
                  409,
                  assertThrows(
                          BizException.class,
                          () ->
                              settlementService.settle(
                                  ORDER_NO, "CONFIRMED".equals(state) ? "ROLLBACK" : "CONFIRM"))
                      .getHttpStatus());
              assertEquals(2, tracked.sql.size());
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerCannotReturnSuccessForMissingTerminalReplaySource() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var proxy = new ProxyFactory(controller);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    var mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      assertions.add(
          () -> {
            reset();
            String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
            seedSettlement(state, ORDER_NO + ":" + action);
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            tracked.writePhase = true;
            mvc.perform(
                    post(
                        "/api/stock/reservations/"
                            + ORDER_NO
                            + "/"
                            + action.toLowerCase(Locale.ROOT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_UNKNOWN"));
            assertEquals(3, tracked.sql.size());
            assertEquals(before, facts());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          });
    assertAll(assertions);
  }

  @Test
  void allOriginalAcceptedStatesKeepSameNoopAndOppositeConflict() {
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED")) {
        reset();
        String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
        seedSettlement(state, ORDER_NO + ":" + action);
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var before = facts();
        tracked.checking = true;
        tracked.writePhase = true;
        var response = (Map<?, ?>) settlementService.settle(ORDER_NO, action).data;
        assertEquals(true, response.get("idempotent"));
        assertEquals(true, response.get("CONFIRMED".equals(state) ? "confirmed" : "rolledBack"));
        assertEquals(3, tracked.sql.size());
        assertEquals(before, facts());
        tracked.sql.clear();
        assertEquals(
            409,
            assertThrows(
                    BizException.class,
                    () ->
                        settlementService.settle(
                            ORDER_NO, "CONFIRMED".equals(state) ? "ROLLBACK" : "CONFIRM"))
                .getHttpStatus());
        assertEquals(2, tracked.sql.size());
        assertEquals(before, facts());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      }
  }

  @Test
  void nonterminalGetCannotExposeUnprovenSourceItemsOrCompleteOrderFallback() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              seedSettlement(state, "OPEN".equals(state) ? null : ORDER_NO + ":ROLLBACK");
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              var result = read();
              assertAll(
                  () -> assertEquals("UNKNOWN", result.status, state + ":" + variant),
                  () -> assertTrue(result.items.isEmpty()),
                  () -> assertEquals(ORDER_NO, result.orderNo),
                  () -> assertEquals(2, tracked.sql.size()));
              assertOnlySelects();
              for (String orderState : List.of("PAID", "CANCELLED")) {
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback(orderState));
                assertEquals(2, tracked.sql.size());
                assertOnlySelects();
              }
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerReturnsUnknownEmptyForUnprovenNonterminalSource() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      assertions.add(
          () -> {
            reset();
            seedSettlement(state, "OPEN".equals(state) ? null : ORDER_NO + ":ROLLBACK");
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items").isEmpty());
            assertEquals(2, tracked.sql.size());
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void originalAcceptedStatesKeepAllNonterminalItemsAndStatuses() throws Exception {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED")) {
        reset();
        seedSettlement(state, "OPEN".equals(state) ? null : ORDER_NO + ":ROLLBACK");
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var before = facts();
        tracked.checking = true;
        var result = read();
        assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", result.status);
        assertEquals(1, result.items.size());
        assertEquals(20L, result.items.get(0).skuId);
        assertEquals(1, result.items.get(0).quantity);
        assertEquals(2, tracked.sql.size());
        assertOnlySelects();
        for (String orderState : List.of("PAID", "CANCELLED")) {
          tracked.sql.clear();
          assertEquals("UNKNOWN", orderFallback(orderState));
          assertEquals(2, tracked.sql.size());
          assertOnlySelects();
        }
        assertEquals(before, facts());
      }
  }

  @Test
  void unsupportedStatusesStayEmptyForActualStockReadAndOrderFallback() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unsupportedStoredStatuses())
      for (String sourceCase : List.of("present", "missing", "sku"))
        assertions.add(
            () -> {
              reset();
              seedSettlement(state, ORDER_NO + ":ROLLBACK");
              if (!"present".equals(sourceCase)) corruptTransitionSource(sourceCase);
              var before = facts();
              tracked.checking = true;
              var result = read();
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              assertTrue(tracked.sql.get(0).contains("SECKILL_QUOTA_SETTLEMENT"));
              for (String orderState : List.of("PAID", "CANCELLED")) {
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback(orderState));
                assertEquals(1, tracked.sql.size());
                assertOnlySelects();
              }
              assertAll(
                  () -> assertNotNull(result),
                  () -> assertEquals(ORDER_NO, result.orderNo),
                  () -> assertEquals("UNKNOWN", result.status),
                  () -> assertTrue(result.items.isEmpty(), state + ":" + sourceCase),
                  () -> assertEquals(before, facts()));
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerKeepsUnknownEmptyForUnsupportedStatusesWithoutSourceQueries()
      throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unsupportedStoredStatuses())
      assertions.add(
          () -> {
            reset();
            seedSettlement(state, ORDER_NO + ":ROLLBACK");
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items").isEmpty());
            assertEquals(1, tracked.sql.size());
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  /** 原VARCHAR可存的越协议状态；NULL由unit覆盖，不ALTER生产约束。 */
  private static List<String> unsupportedStoredStatuses() {
    return List.of(
        "CONFLICT",
        "UNKNOWN",
        "unrecognized",
        "",
        " ",
        "open",
        "Open",
        "OPEN ",
        " OPEN",
        "confirmed",
        "RELEASED ",
        "release_pending",
        "projection_blocked");
  }

  @Test
  void pendingRollbackReplayCannotAcceptUnprovenSourceWithoutChangingFacts() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String variant : badTransitionSources())
        assertions.add(
            () -> {
              reset();
              seedPendingReplay(state);
              corruptTransitionSource(variant);
              var before = facts();
              tracked.checking = true;
              tracked.writePhase = true;
              var failure =
                  assertThrows(
                      BizException.class, () -> settlementService.settle(ORDER_NO, "ROLLBACK"));
              assertEquals(503, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_UNKNOWN", failure.getCode());
              assertEquals(3, tracked.sql.size());
              assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
              assertTrue(
                  tracked.sql.get(2).startsWith("SELECT ACCEPTED.ID FROM SECKILL_RESERVATION"));
              assertFalse(tracked.sql.get(2).contains("FOR UPDATE"));
              assertEquals(before, facts());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              if ("RELEASE_PENDING".equals(state)) {
                tracked.sql.clear();
                assertEquals(
                    409,
                    assertThrows(
                            BizException.class, () -> settlementService.settle(ORDER_NO, "CONFIRM"))
                        .getHttpStatus());
                assertEquals(2, tracked.sql.size());
                assertEquals(before, facts());
              }
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerRejectsMissingSourcePendingReplays() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var proxy = new ProxyFactory(controller);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    var mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      assertions.add(
          () -> {
            reset();
            seedPendingReplay(state);
            corruptTransitionSource("missing");
            var before = facts();
            tracked.checking = true;
            tracked.writePhase = true;
            mvc.perform(post("/api/stock/reservations/" + ORDER_NO + "/rollback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_UNKNOWN"));
            assertEquals(3, tracked.sql.size());
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void originalAcceptedStatesKeepPendingResponseAndAllLeaseDiagnostics() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED")) {
        reset();
        seedPendingReplay(state);
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var before = facts();
        tracked.checking = true;
        tracked.writePhase = true;
        var response = (Map<?, ?>) settlementService.settle(ORDER_NO, "ROLLBACK").data;
        assertEquals(false, response.get("rolledBack"));
        assertEquals(true, response.get("pending"));
        assertEquals("UNKNOWN", response.get("status"));
        assertEquals(3, tracked.sql.size());
        assertEquals(before, facts());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      }
    reset();
    seedSettlement("PROJECTION_BLOCKED", ORDER_NO + ":CONFIRM");
    corruptTransitionSource("missing");
    var before = facts();
    tracked.checking = true;
    tracked.writePhase = true;
    assertEquals(
        409,
        assertThrows(BizException.class, () -> settlementService.settle(ORDER_NO, "ROLLBACK"))
            .getHttpStatus());
    assertEquals(2, tracked.sql.size());
    assertEquals(before, facts());
  }

  /** 租约/诊断字段保原有值，证明查询重放不清理任何恢复元数据。 */
  private static void seedPendingReplay(String state) {
    seedSettlement(state, ORDER_NO + ":ROLLBACK");
    fixture.update(
        "UPDATE seckill_quota_settlement SET"
            + " lease_owner='owned-pending-owner',lease_until='2099-01-01',last_error='owned-pending-error',next_retry_at='2000-01-01'");
  }

  /** 两个终态的原键缺失、别单、方向及排序规则别名，不预归一化。 */
  private static List<String> badKeys(String state) {
    String action = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
    return java.util.Arrays.asList(
        null,
        "private-other:" + action,
        ORDER_NO + ":" + ("CONFIRM".equals(action) ? "ROLLBACK" : "CONFIRM"),
        "202610PROOF1:" + action,
        ORDER_NO + ":" + action + " ");
  }

  /** 仅owned准备原VARCHAR状态操作键，无ALTER或生产迁移。 */
  private static void seedSettlement(String state, String key) {
    fixture.update(
        "INSERT INTO"
            + " seckill_quota_settlement(id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,operation_key,redis_revision,created_at,updated_at)"
            + " VALUES(1,?,10,20,8,'private-idem',1,?,2,?,?,'2026-10-11','2026-10-11')",
        ORDER_NO,
        state,
        key,
        "RELEASED".equals(state) ? 4L : null);
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,order_no,activity_id,sku_id,user_id,idempotency_key,status,created_at,updated_at,request_hash,request_payload)"
            + " VALUES(1,?,10,20,8,'private-idem','ACCEPTED','2026-10-10','2026-10-10',REPEAT('0',64),'{}')",
        ORDER_NO);
  }

  /** 原生产GET，任何数据库异常都不得当作终态证据。 */
  private static StockReservationVO read() {
    return (StockReservationVO) service.reservation(ORDER_NO).data;
  }

  /** 所有被测SQL只允许无锁SELECT。 */
  private static void assertOnlySelects() {
    assertFalse(tracked.sql.isEmpty());
    assertTrue(
        tracked.sql.stream()
            .allMatch(sql -> sql.startsWith("SELECT") && !sql.contains("FOR UPDATE")));
  }

  /** 完整事实包括未触碰的库存/投影表，不仅比较状态字段。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 原库清单和checksum用于证明源库从未写入。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_stock"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_stock", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_stock." + table));
    }
    return values;
  }

  /** 保留项目上海DATETIME语义，不设置全局时区。 */
  private static String shanghai(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 真实Order fallback消费实际Stock GET；仅Order主行/动作查询用Mapper替身。 */
  @SuppressWarnings("unchecked")
  private static String orderFallback(String orderState) throws Exception {
    Class<?> sqlType = orderClasses.loadClass("com.cloudmall.order.mapper.OrderSqlMapper");
    Object orderMapper = mock(sqlType);
    when((List<Map<String, Object>>)
            sqlType
                .getMethod("findOrder", String.class, String.class)
                .invoke(orderMapper, "mall_order_202610", ORDER_NO))
        .thenReturn(
            List.of(
                Map.of(
                    "order_no",
                    ORDER_NO,
                    "user_id",
                    8L,
                    "status",
                    orderState,
                    "total_amount",
                    BigDecimal.TEN,
                    "pay_amount",
                    BigDecimal.TEN,
                    "address_snapshot",
                    "{}")));
    when((List<Map<String, Object>>)
            sqlType
                .getMethod("findOrderItems", String.class, String.class)
                .invoke(orderMapper, "mall_order_item_202610", ORDER_NO))
        .thenReturn(List.of());
    Class<?> clientType = orderClasses.loadClass("com.cloudmall.order.feign.StockClient");
    ObjectMapper json = new ObjectMapper();
    Object client =
        Proxy.newProxyInstance(
            orderClasses,
            new Class<?>[] {clientType},
            (proxy, method, arguments) -> {
              if (method.getDeclaringClass() == Object.class)
                return switch (method.getName()) {
                  case "toString" -> "Actual Stock read-only result bridge";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == arguments[0];
                  default -> throw new AssertionError(method.getName());
                };
              assertEquals("reservation", method.getName(), "Order must not issue a Stock write");
              assertEquals(ORDER_NO, arguments[0]);
              var actual = read();
              Object response =
                  json.convertValue(
                      actual,
                      orderClasses.loadClass(
                          "com.cloudmall.order.domain.vo.StockReservationStatusVO"));
              return ApiResponse.ok(response);
            });
    Class<?> orderType =
        orderClasses.loadClass("com.cloudmall.order.service.impl.OrderServiceImpl");
    Object order = orderType.getConstructor().newInstance();
    ReflectionTestUtils.setField(order, "stock", client);
    ReflectionTestUtils.setField(order, "orderSqlMapper", orderMapper);
    ReflectionTestUtils.setField(
        order,
        "orderStockActionMapper",
        mock(orderClasses.loadClass("com.cloudmall.order.mapper.OrderStockActionMapper")));
    var result =
        (ApiResponse<?>)
            orderType
                .getMethod("paymentState", String.class, Long.class)
                .invoke(order, ORDER_NO, 8L);
    return (String) ((Map<?, ?>) result.data).get("stockActionStatus");
  }

  /** 当前owned库的读监测，不添加或伪造事务属性。 */
  private static final class SelectOnlyDataSource extends AbstractDataSource {
    /** owned底层数据源。 */
    final DataSource delegate;

    /** 仅被测GET/settle/POST阶段启用断言。 */
    boolean checking;

    /** 只在settle/POST期间允许原INSERT+锁读；专用矩阵另允许原表CAS，始终禁止普通写。 */
    boolean writePhase;

    /** 状态矩阵允许原表CAS；负例以两SQL及完整事实证明零UPDATE。 */
    boolean allowTransition;

    /** 实际准备的SQL。 */
    final List<String> sql = new ArrayList<>();

    SelectOnlyDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** GET只准无锁SELECT；写核真实事务及允许的原SQL，不声称GET是RR。 */
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      Connection connection = delegate.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (checking && "prepareStatement".equals(method.getName())) {
                  String statement =
                      ((String) arguments[0]).stripLeading().toUpperCase(Locale.ROOT);
                  if (writePhase) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(connection.getAutoCommit());
                    assertTrue(
                        statement.startsWith("INSERT IGNORE INTO SECKILL_QUOTA_SETTLEMENT")
                            || statement.startsWith("SELECT * FROM SECKILL_QUOTA_SETTLEMENT")
                                && statement.contains("FOR UPDATE")
                            || statement.startsWith("SELECT ACCEPTED.ID FROM SECKILL_RESERVATION")
                                && !statement.contains("FOR UPDATE")
                            || allowTransition
                                && statement.startsWith("UPDATE SECKILL_QUOTA_SETTLEMENT SET"),
                        statement);
                  } else {
                    assertTrue(statement.startsWith("SELECT"), statement);
                    assertFalse(statement.contains("FOR UPDATE"));
                  }
                  sql.add(statement);
                }
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 本fixture凭证固定，走同一受监测入口。 */
    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return getConnection();
    }
  }
}
