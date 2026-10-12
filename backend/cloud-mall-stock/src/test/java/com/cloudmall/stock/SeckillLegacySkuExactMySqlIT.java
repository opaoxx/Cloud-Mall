package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实JDBC legacy SKU类型及实际Order fallback证明；Order行用Mapper替身，不冒充HTTP/分布式验收。 */
class SeckillLegacySkuExactMySqlIT {
  /** 原Stock库只读连接。 */
  private static JdbcTemplate source;

  /** 原表清单与校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 仅当前IT拥有的库。 */
  private static String database;

  /** 只在自建库准备与核验完整行事实。 */
  private static JdbcTemplate fixture;

  /** 被测查询数据源，任何非SELECT立即失败。 */
  private static SelectOnlyDataSource tracked;

  /** 实际Stock SQL层。 */
  private static StockSqlMapper mapper;

  /** 无Redis/Rabbit/写资源的实际Stock GET实现。 */
  private static StockServiceImpl service;

  /** 实际MyBatis秒杀结算读取Mapper。 */
  private static SeckillQuotaSettlementMapper settlementMapper;

  /** 实际秒杀结算查询服务，GET不触发建表或迁移。 */
  private static SeckillQuotaSettlementService settlementService;

  /** 复用标准Maven输出加载实际Order fallback，仅用于测试。 */
  private static URLClassLoader orderClasses;

  /** Order已有依赖仅供childloader加载，finally删除自有文件/目录。 */
  private static Path dependencyDirectory;

  /** 本次从标准Order Boot jar提取的已有依赖文件。 */
  private static final List<Path> dependencyJars = new ArrayList<>();

  /** 本类所有病例对应的原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  /** 被测秒杀/普通Stock事实表及不应被GET改动的库存/投影表。 */
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
    database = "cm16t33_" + UUID.randomUUID().toString().replace("-", "");
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
    settlementService = new SeckillQuotaSettlementService(settlementMapper, mapper);
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
          Class.forName(dependency[0], false, SeckillLegacySkuExactMySqlIT.class.getClassLoader());
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
            dependencyDirectory = Files.createTempDirectory("cm16t33_order_deps_");
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
            urls.toArray(java.net.URL[]::new), SeckillLegacySkuExactMySqlIT.class.getClassLoader());
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    fixture.execute("ALTER TABLE seckill_reservation MODIFY sku_id BIGINT NOT NULL");
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
      if (database != null && database.matches("cm16t33_[0-9a-f]{32}")) {
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
        "CM16T33 original Stock checksums unchanged; owned database and classloader removed");
  }

  @Test
  void currentSignedBigintNonPositiveSkuNeverBecomesLegacyEvidence() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (long sku : List.of(0L, -1L))
      for (String state : List.of("ACCEPTED", "REJECTED", "PENDING"))
        assertions.add(
            () -> {
              reset();
              seedLegacy(state, sku);
              assertInstanceOf(Long.class, mapper.findSeckillByOrderNo(ORDER_NO).get("sku_id"));
              var before = facts();
              tracked.checking = true;
              if ("ACCEPTED".equals(state)) {
                BizException failure = assertThrows(BizException.class, () -> read());
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertEquals(500, failure.getHttpStatus());
              } else {
                var outer = read();
                assertEquals("UNKNOWN", outer.status);
                assertTrue(outer.items.isEmpty());
              }
              tracked.sql.clear();
              var bare = bareRead();
              assertUnknownSku(bare);
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void ownedLegacyDecimalAndFloatingColumnsCannotBeTruncatedIntoDifferentSku() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String kind : List.of("fraction", "huge", "floating", "null"))
      assertions.add(
          () -> {
            reset();
            fixture.execute(
                "ALTER TABLE seckill_reservation MODIFY sku_id "
                    + ("floating".equals(kind) ? "DOUBLE NULL" : "DECIMAL(65,3) NULL"));
            Object sku =
                switch (kind) {
                  case "fraction" -> new BigDecimal("7.9");
                  case "huge" -> new BigDecimal("18446744073709551623");
                  case "floating" -> 7.0d;
                  default -> null;
                };
            seedLegacy("ACCEPTED", sku);
            Object observedSku = mapper.findSeckillByOrderNo(ORDER_NO).get("sku_id");
            if ("floating".equals(kind)) assertInstanceOf(Double.class, observedSku);
            if (List.of("fraction", "huge").contains(kind))
              assertInstanceOf(BigDecimal.class, observedSku);
            var before = facts();
            tracked.sql.clear();
            tracked.checking = true;
            var failure = assertThrows(BizException.class, () -> read(), kind);
            assertEquals(500, failure.getHttpStatus());
            assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
            assertEquals(2, tracked.sql.size());
            tracked.sql.clear();
            assertUnknownSku(bareRead());
            assertEquals(1, tracked.sql.size());
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void signedAndLegacyExactIntegersPreserveSkuBoundaryAndStates() {
    for (String storage : List.of("BIGINT NOT NULL", "DECIMAL(65,3) NULL")) {
      reset();
      fixture.execute("ALTER TABLE seckill_reservation MODIFY sku_id " + storage);
      for (String skuText : List.of("7", "9223372036854775807"))
        for (String state : List.of("ACCEPTED", "REJECTED", "PENDING")) {
          reset();
          fixture.execute("ALTER TABLE seckill_reservation MODIFY sku_id " + storage);
          seedLegacy(state, new BigDecimal(skuText));
          var before = facts();
          tracked.checking = true;
          var outer = read();
          assertEquals(
              "ACCEPTED".equals(state)
                  ? "RESERVED"
                  : "REJECTED".equals(state) ? "REJECTED" : "UNKNOWN",
              outer.status);
          if ("PENDING".equals(state)) assertTrue(outer.items.isEmpty());
          else {
            assertEquals(Long.parseLong(skuText), outer.items.get(0).skuId);
            assertEquals(1, outer.items.get(0).quantity);
          }
          tracked.sql.clear();
          var bare = bareRead();
          assertEquals(outer.status, bare.status);
          if ("PENDING".equals(state)) assertTrue(bare.items.isEmpty());
          else assertEquals(Long.parseLong(skuText), bare.items.get(0).skuId);
          assertEquals(1, tracked.sql.size());
          assertOnlySelects();
          assertEquals(before, facts());
        }
    }
  }

  @Test
  void actualControllerAndOrderFallbackDoNotPromoteInvalidRejectedSku() throws Exception {
    reset();
    fixture.execute("ALTER TABLE seckill_reservation MODIFY sku_id BIGINT NOT NULL");
    seedLegacy("REJECTED", 0L);
    var before = facts();
    tracked.checking = true;
    assertEquals("UNKNOWN", orderFallback("PAID"));
    assertEquals("UNKNOWN", orderFallback("CANCELLED"));
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    tracked.sql.clear();
    String body =
        mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private"));
    assertEquals(3, tracked.sql.size());
    assertOnlySelects();
    assertEquals(before, facts());
  }

  /** 未注入主settlement服务的既有兼容fallback，仍必须保持原字段身份精确。 */
  private static StockReservationVO bareRead() {
    return (StockReservationVO)
        new StockServiceImpl(null, mapper, null, new ObjectMapper()).reservation(ORDER_NO).data;
  }

  @Test
  void currentBigintAndVarcharContextCannotBecomeLegacyEvidence() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant :
        List.of(
            "activity-zero",
            "activity-negative",
            "user-zero",
            "user-negative",
            "idem-empty",
            "idem-space",
            "idem-unicode",
            "idem-null"))
      for (String state : List.of("ACCEPTED", "REJECTED", "PENDING"))
        assertions.add(
            () -> {
              reset();
              seedLegacy(state, 7L);
              switch (variant) {
                case "activity-zero" ->
                    fixture.update("UPDATE seckill_reservation SET activity_id=0");
                case "activity-negative" ->
                    fixture.update("UPDATE seckill_reservation SET activity_id=-1");
                case "user-zero" -> fixture.update("UPDATE seckill_reservation SET user_id=0");
                case "user-negative" -> fixture.update("UPDATE seckill_reservation SET user_id=-1");
                case "idem-empty" ->
                    fixture.update("UPDATE seckill_reservation SET idempotency_key=''");
                case "idem-space" ->
                    fixture.update("UPDATE seckill_reservation SET idempotency_key=' '");
                case "idem-unicode" ->
                    fixture.update("UPDATE seckill_reservation SET idempotency_key=?", "\u2003");
                case "idem-null" ->
                    fixture.update("UPDATE seckill_reservation SET idempotency_key='null'");
                default -> throw new AssertionError(variant);
              }
              var before = facts();
              tracked.checking = true;
              if ("ACCEPTED".equals(state)) {
                var failure = assertThrows(BizException.class, () -> read());
                assertEquals(500, failure.getHttpStatus());
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertEquals("秒杀受理事实维度无效", failure.getMessage());
                assertEquals(2, tracked.sql.size());
              } else {
                assertUnknownSku(read());
                assertEquals(3, tracked.sql.size());
                tracked.sql.clear();
                assertEquals("UNKNOWN", orderFallback("PAID"));
                assertEquals("UNKNOWN", orderFallback("CANCELLED"));
              }
              assertOnlySelects();
              tracked.sql.clear();
              assertUnknownSku(bareRead());
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void actualControllerKeepsPrimaryCorruptionAndOuterUnknownWithoutWrites() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("ACCEPTED", "REJECTED"))
      assertions.add(
          () -> {
            reset();
            seedLegacy(state, 7L);
            fixture.update("UPDATE seckill_reservation SET user_id=0");
            var before = facts();
            tracked.checking = true;
            var response = mvc.perform(get("/api/stock/reservations/" + ORDER_NO));
            if ("ACCEPTED".equals(state)) {
              byte[] body =
                  response
                      .andExpect(status().isInternalServerError())
                      .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CORRUPT"))
                      .andReturn()
                      .getResponse()
                      .getContentAsByteArray();
              assertEquals("秒杀受理事实维度无效", new ObjectMapper().readTree(body).get("message").asText());
              assertEquals(2, tracked.sql.size());
            } else {
              response
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                  .andExpect(jsonPath("$.data.items").isEmpty());
              assertEquals(3, tracked.sql.size());
            }
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  @Test
  void exactLongAndUnicodeContextBoundariesKeepLegacyReadProtocol() {
    for (long identity : List.of(1L, Long.MAX_VALUE))
      for (String key :
          List.of(
              "private-idem", " private-idem ", "NULL", "null ", "x".repeat(128), "😺".repeat(128)))
        for (String state : List.of("ACCEPTED", "REJECTED", "PENDING")) {
          reset();
          seedLegacy(state, 7L);
          fixture.update(
              "UPDATE seckill_reservation SET activity_id=?,user_id=?,idempotency_key=?",
              identity,
              identity,
              key);
          var before = facts();
          tracked.checking = true;
          var result = read();
          assertEquals(
              "ACCEPTED".equals(state)
                  ? "RESERVED"
                  : "REJECTED".equals(state) ? "REJECTED" : "UNKNOWN",
              result.status);
          if ("PENDING".equals(state)) assertTrue(result.items.isEmpty());
          else {
            assertEquals(1, result.items.size());
            assertEquals(7L, result.items.get(0).skuId);
          }
          assertEquals("ACCEPTED".equals(state) ? 2 : 3, tracked.sql.size());
          assertOnlySelects();
          tracked.sql.clear();
          assertEquals(result.status, bareRead().status);
          assertEquals(1, tracked.sql.size());
          assertEquals(before, facts());
        }
  }

  @Test
  void unsupportedLegacyStatusesStayEmptyWithAndWithoutPrimaryService() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unsupportedLegacyStatuses())
      assertions.add(
          () -> {
            reset();
            seedLegacy(state, 7L);
            var before = facts();
            tracked.checking = true;
            var outer = read();
            assertEquals(3, tracked.sql.size());
            assertOnlySelects();
            tracked.sql.clear();
            var bare = bareRead();
            assertEquals(1, tracked.sql.size());
            assertOnlySelects();
            for (String orderState : List.of("PAID", "CANCELLED")) {
              tracked.sql.clear();
              assertEquals("UNKNOWN", orderFallback(orderState));
              assertEquals(3, tracked.sql.size());
              assertOnlySelects();
            }
            assertAll(
                () -> assertUnknownSku(outer),
                () -> assertUnknownSku(bare),
                () -> assertEquals(before, facts()));
          });
    assertAll(assertions);
  }

  @Test
  void actualControllerReturnsUnknownEmptyForUnsupportedLegacyStates() throws Exception {
    StockController controller = new StockController();
    ReflectionTestUtils.setField(controller, "stockSqlMapper", mapper);
    ReflectionTestUtils.setField(controller, "seckillSettlementService", settlementService);
    ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unsupportedLegacyStatuses())
      assertions.add(
          () -> {
            reset();
            seedLegacy(state, 7L);
            var before = facts();
            tracked.checking = true;
            mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(ORDER_NO))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items").isEmpty());
            assertEquals(3, tracked.sql.size());
            assertOnlySelects();
            assertEquals(before, facts());
          });
    assertAll(assertions);
  }

  /** 原VARCHAR支持的非两受理协议状态，NULL仅unit。 */
  private static List<String> unsupportedLegacyStatuses() {
    return List.of(
        "PENDING",
        "PUBLISHED",
        "ORDER_CREATED",
        "UNKNOWN",
        "CONFLICT",
        "",
        " ",
        "accepted",
        "Accepted",
        "ACCEPTED ",
        " ACCEPTED",
        "rejected",
        "REJECTED ");
  }

  @Test
  void startupBackfillCannotBorrowAcceptedStatusAliasesOrUnsupportedSources() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unprovenStartupStates())
      assertions.add(
          () -> {
            reset();
            seedLegacy(state, 7L);
            var before = facts();
            var occurredAt = java.time.LocalDateTime.of(2026, 10, 12, 11, 0, 0, 123000000);
            int inserted = settlementMapper.insertMissingAcceptedOpen(occurredAt);
            assertAll(
                () -> assertEquals(0, inserted, state),
                () -> assertNull(settlementMapper.findSettlement(ORDER_NO), state),
                () -> assertEquals(before, facts(), state));
          });
    assertAll(assertions);
  }

  @Test
  void primaryStartupHookCannotCreateOpenFromWrongAcceptanceStatus() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : unprovenStartupStates())
      assertions.add(
          () -> {
            reset();
            seedLegacy(state, 7L);
            var before = facts();
            settlementService.ensureTable();
            assertAll(
                () -> assertNull(settlementMapper.findSettlement(ORDER_NO), state),
                () -> assertEquals(before, facts(), state));
          });
    assertAll(assertions);
  }

  @Test
  void exactAcceptedStartupBackfillKeepsOriginalTupleDefaultsAndIdempotency() {
    reset();
    seedLegacy("ACCEPTED", 7L);
    var before = facts();
    var occurredAt = java.time.LocalDateTime.of(2026, 10, 12, 11, 0, 0, 123000000);
    assertEquals(1, settlementMapper.insertMissingAcceptedOpen(occurredAt));
    var original = settlementMapper.findSettlement(ORDER_NO);
    assertNotNull(original.id);
    assertTrue(original.id > 0);
    assertEquals(ORDER_NO, original.orderNo);
    assertEquals(10L, original.activityId);
    assertEquals(7L, original.skuId);
    assertEquals(8L, original.userId);
    assertEquals("private-idem", original.idempotencyKey);
    assertEquals(1, original.quantity);
    assertEquals(1L, original.revision);
    assertEquals("OPEN", original.status);
    assertNull(original.operationKey);
    assertNull(original.redisRevision);
    assertNull(original.leaseOwner);
    assertNull(original.leaseUntil);
    assertNull(original.lastError);
    assertNull(original.nextRetryAt);
    assertEquals(occurredAt, original.createdAt);
    assertEquals(occurredAt, original.updatedAt);
    var after = facts();
    for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
      if (!"seckill_quota_settlement".equals(TABLES.get(tableIndex)))
        assertEquals(before.get(tableIndex), after.get(tableIndex));
    assertEquals(0, settlementMapper.insertMissingAcceptedOpen(occurredAt.plusHours(1)));
    assertEquals(after, facts());
    // 已有合法原结算行无论状态/诊断如何变化，启动回填不得覆盖。
    fixture.update(
        "UPDATE seckill_quota_settlement SET"
            + " status='CONFIRMED',operation_key=?,revision=2,last_error='owned-existing',lease_owner='owned-existing',lease_until='2099-01-01'",
        ORDER_NO + ":CONFIRM");
    var existing = facts();
    settlementService.ensureTable();
    assertEquals(existing, facts());
    reset();
    seedLegacy("ACCEPTED", 7L);
    var sourceBefore = facts();
    settlementService.ensureTable();
    var created = settlementMapper.findSettlement(ORDER_NO);
    assertEquals("OPEN", created.status);
    assertEquals(ORDER_NO, created.orderNo);
    assertEquals(7L, created.skuId);
    assertEquals(1L, created.revision);
    var createdFacts = facts();
    for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
      if (!"seckill_quota_settlement".equals(TABLES.get(tableIndex)))
        assertEquals(sourceBefore.get(tableIndex), createdFacts.get(tableIndex));
    settlementService.ensureTable();
    assertEquals(createdFacts, facts());
  }

  @Test
  void nonPositiveStartupIdentityCannotCreateOpenViaSqlOrPrimaryHook() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String field : List.of("activity_id", "sku_id", "user_id"))
      for (long value : List.of(0L, -1L))
        for (String entry : List.of("sql", "primary"))
          assertions.add(
              () -> {
                reset();
                seedLegacy("ACCEPTED", 7L);
                fixture.update("UPDATE seckill_reservation SET " + field + "=?", value);
                var before = facts();
                if ("sql".equals(entry)) {
                  int inserted =
                      settlementMapper.insertMissingAcceptedOpen(
                          java.time.LocalDateTime.of(2026, 10, 12, 11, 0));
                  assertAll(
                      () -> assertEquals(0, inserted, field + ":" + value),
                      () -> assertNull(settlementMapper.findSettlement(ORDER_NO)),
                      () -> assertEquals(before, facts()));
                } else {
                  settlementService.ensureTable();
                  assertAll(
                      () ->
                          assertNull(
                              settlementMapper.findSettlement(ORDER_NO), field + ":" + value),
                      () -> assertEquals(before, facts()));
                }
              });
    assertAll(assertions);
  }

  @Test
  void positiveStartupIdentityBoundariesKeepOriginalTupleAndIdempotency() {
    for (String field : List.of("activity_id", "sku_id", "user_id"))
      for (long value : List.of(1L, Long.MAX_VALUE))
        for (String entry : List.of("sql", "primary")) {
          reset();
          seedLegacy("ACCEPTED", 7L);
          fixture.update("UPDATE seckill_reservation SET " + field + "=?", value);
          var before = facts();
          var occurredAt = java.time.LocalDateTime.of(2026, 10, 12, 11, 0, 0, 123000000);
          if ("sql".equals(entry))
            assertEquals(1, settlementMapper.insertMissingAcceptedOpen(occurredAt));
          else settlementService.ensureTable();
          var current = settlementMapper.findSettlement(ORDER_NO);
          assertNotNull(current.id);
          assertEquals(ORDER_NO, current.orderNo);
          assertEquals("activity_id".equals(field) ? value : 10L, current.activityId);
          assertEquals("sku_id".equals(field) ? value : 7L, current.skuId);
          assertEquals("user_id".equals(field) ? value : 8L, current.userId);
          assertEquals("private-idem", current.idempotencyKey);
          assertEquals("OPEN", current.status);
          assertEquals(1L, current.revision);
          assertEquals(1, current.quantity);
          assertNull(current.operationKey);
          assertNull(current.redisRevision);
          assertNull(current.leaseOwner);
          assertNull(current.leaseUntil);
          if ("sql".equals(entry)) {
            assertEquals(occurredAt, current.createdAt);
            assertEquals(occurredAt, current.updatedAt);
          }
          var after = facts();
          for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
            if (!"seckill_quota_settlement".equals(TABLES.get(tableIndex)))
              assertEquals(before.get(tableIndex), after.get(tableIndex));
          assertEquals(0, settlementMapper.insertMissingAcceptedOpen(occurredAt.plusHours(1)));
          settlementService.ensureTable();
          assertEquals(after, facts());
        }
  }

  /** 原VARCHAR内的别名和其它受理态；不在夹具中规范化原status。 */
  private static List<String> unprovenStartupStates() {
    return List.of(
        "accepted",
        "Accepted",
        "ÁCCEPTED",
        "ACCÉPTED",
        "ACCEPTED ",
        "ACCEPTED  ",
        " ACCEPTED",
        "PENDING",
        "PUBLISHED",
        "ORDER_CREATED",
        "UNKNOWN",
        "REJECTED",
        "",
        " ");
  }

  /** 不伪造正SKU，不让无效REJECTED变成Order冲突/成功依据。 */
  private static void assertUnknownSku(StockReservationVO result) {
    assertEquals(ORDER_NO, result.orderNo);
    assertEquals("UNKNOWN", result.status);
    assertTrue(result.items.isEmpty());
  }

  /** 仅owned插入原JDBC类型，不在夹具/Mapper预先转换SKU值。 */
  private static void seedLegacy(String state, Object sku) {
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
            + " VALUES(1,10,?,8,?,'private-idem',?,'2026-10-11','2026-10-11')",
        sku,
        ORDER_NO,
        state);
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

    /** 仅GET阶段启用断言。 */
    boolean checking;

    /** 实际准备的SQL。 */
    final List<String> sql = new ArrayList<>();

    SelectOnlyDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** 每条被测SQL必须是无锁SELECT，不声称现GET是RR。 */
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
                  assertTrue(statement.startsWith("SELECT"), statement);
                  assertFalse(statement.contains("FOR UPDATE"));
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
