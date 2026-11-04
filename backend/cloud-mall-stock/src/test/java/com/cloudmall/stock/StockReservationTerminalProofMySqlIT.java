package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockReservationReader;
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
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实Stock SUM及实际Order fallback证明；Order行用Mapper替身，不冒充HTTP/分布式验收。 */
class StockReservationTerminalProofMySqlIT {
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
  private static StockReservationReader service;

  /** 复用标准Maven输出加载实际Order fallback，仅用于测试。 */
  private static URLClassLoader orderClasses;

  /** Order已有依赖仅供childloader加载，finally删除自有文件/目录。 */
  private static Path dependencyDirectory;

  /** 本次从标准Order Boot jar提取的已有依赖文件。 */
  private static final List<Path> dependencyJars = new ArrayList<>();

  /** 本类所有病例对应的原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  /** 被测三张Stock事实表及不应被GET改动的库存/投影表。 */
  private static final List<String> TABLES =
      List.of(
          "stock_flow",
          "stock_reservation_request",
          "seckill_reservation",
          "stock_sku",
          "stock_cache_repair");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_stock(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_stock URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = fingerprint();
    database = "cm16t4_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    var dataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_stock", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(dataSource);
    for (String table : TABLES)
      fixture.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
    tracked = new SelectOnlyDataSource(dataSource);
    mapper = new StockSqlMapper(new JdbcTemplate(tracked));
    service = StockTestServices.reader(mapper, new ObjectMapper());
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
              dependency[0], false, StockReservationTerminalProofMySqlIT.class.getClassLoader());
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
            dependencyDirectory = Files.createTempDirectory("cm16t4_order_deps_");
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
            StockReservationTerminalProofMySqlIT.class.getClassLoader());
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
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
      if (database != null && database.matches("cm16t4_[0-9a-f]{32}")) {
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
        "CM16T4 original Stock checksums unchanged; owned database and classloader removed");
  }

  @Test
  void flowIdentityAliasesAndMixedOriginalIdentifiersAreNotCurrentOrderProof() {
    for (String variant : List.of("upper", "space", "accent", "same-sku-mixed", "cross-sku-mixed"))
      for (boolean hasRequest : List.of(false, true)) {
        reset();
        reserve(7, 2);
        reserve(8, 3);
        flow(7, "CONFIRM", 2);
        flow(8, "CONFIRM", 3);
        if (hasRequest) request("CONFIRMED");
        if ("upper".equals(variant))
          fixture.update("UPDATE stock_flow SET order_no=UPPER(order_no)");
        if ("space".equals(variant))
          fixture.update("UPDATE stock_flow SET order_no=CONCAT(order_no,' ')");
        if ("accent".equals(variant))
          fixture.update("UPDATE stock_flow SET order_no='202610próof1'");
        if ("same-sku-mixed".equals(variant))
          fixture.update(
              "UPDATE stock_flow SET order_no=UPPER(order_no) WHERE sku_id=7 AND"
                  + " flow_type='CONFIRM'");
        if ("cross-sku-mixed".equals(variant))
          fixture.update("UPDATE stock_flow SET order_no=UPPER(order_no) WHERE sku_id=8");
        var before = facts();
        tracked.checking = true;
        var result = read();
        assertEquals("UNKNOWN", result.status, variant);
        assertEquals(ORDER_NO, result.orderNo);
        assertTrue(result.items.isEmpty());
        assertEquals(before, facts());
        assertOnlySelects();
      }
  }

  @Test
  void flowRawInvalidRowsCannotDisappearBehindApparentlyValidSums() {
    for (String variant :
        List.of(
            "compensated-negative",
            "zero",
            "unknown",
            "lowercase",
            "padded",
            "null-type",
            "null-quantity")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      flow(7, "CONFIRM", 2);
      flow(8, "CONFIRM", 3);
      request("CONFIRMED");
      if ("compensated-negative".equals(variant)) {
        flow(7, "CONFIRM", -1);
        flow(7, "CONFIRM", 1);
      }
      if ("zero".equals(variant)) flow(7, "ROLLBACK", 0);
      if ("unknown".equals(variant)) flow(7, "ADJUST", 1);
      if ("lowercase".equals(variant))
        fixture.update(
            "UPDATE stock_flow SET flow_type='confirm' WHERE sku_id=7 AND flow_type='CONFIRM'");
      if ("padded".equals(variant))
        fixture.update(
            "UPDATE stock_flow SET flow_type='CONFIRM ' WHERE sku_id=7 AND flow_type='CONFIRM'");
      if ("null-type".equals(variant) || "null-quantity".equals(variant)) {
        fixture.execute(
            "ALTER TABLE stock_flow MODIFY flow_type VARCHAR(32) NULL, MODIFY quantity INT NULL");
        flow(7, "ADJUST", 1);
        if ("null-type".equals(variant))
          fixture.update("UPDATE stock_flow SET flow_type=NULL WHERE flow_type='ADJUST'");
        else fixture.update("UPDATE stock_flow SET quantity=NULL WHERE flow_type='ADJUST'");
      }
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, variant);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void flowInvalidOriginCannotLetActualOrderFallbackClaimSettlement() throws Exception {
    reserve(7, 2);
    reserve(8, 3);
    flow(7, "CONFIRM", 2);
    flow(8, "CONFIRM", 3);
    fixture.update("UPDATE stock_flow SET order_no=UPPER(order_no)");
    var before = facts();
    tracked.checking = true;
    assertEquals("UNKNOWN", orderFallback("PAID"));
    assertEquals(before, facts());
    assertOnlySelects();
  }

  @Test
  void flowInvalidOriginCannotBeAcknowledgedAsReservedOnReplay() {
    reserve(7, 2);
    reserve(8, 3);
    request("RESERVED");
    fixture.update("UPDATE stock_flow SET order_no=UPPER(order_no)");
    var before = facts();
    tracked.checking = true;
    var failure =
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "alreadyReserved", ORDER_NO));
    assertEquals(503, failure.getHttpStatus());
    assertEquals("STOCK_RESERVATION_PROOF_UNAVAILABLE", failure.getCode());
    assertEquals(before, facts());
    assertOnlySelects();
  }

  @Test
  void flowSameIdentityPositiveRowsKeepHealthyThreeStates() {
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      if (!"RESERVED".equals(state)) {
        String type = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
        flow(7, type, 2);
        flow(8, type, 3);
      }
      request(state);
      var before = facts();
      tracked.checking = true;
      var projections = mapper.findReservationFlows(ORDER_NO);
      assertEquals(2, projections.size());
      for (var projection : projections) {
        assertEquals(ORDER_NO, projection.get("original_order_no"));
        assertEquals(1L, ((Number) projection.get("order_identity_count")).longValue());
        assertEquals(0L, ((Number) projection.get("invalid_flow_count")).longValue());
      }
      assertEquals(state, read().status);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void snapshotReplayCannotReplaceIncompleteProofWithAcknowledgement() {
    reserve(7, 2);
    request("RESERVED");
    var before = facts();
    tracked.checking = true;
    var failure =
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "alreadyReserved", ORDER_NO));
    assertEquals(503, failure.getHttpStatus());
    assertEquals("STOCK_RESERVATION_PROOF_UNAVAILABLE", failure.getCode());
    assertEquals(before, facts());
    assertOnlySelects();
  }

  @Test
  void snapshotMissingExtraAndWrongQuantityCannotClaimCompleteRequest() {
    for (String variant : List.of("missing", "extra", "quantity")) {
      reset();
      reserve(7, 2);
      flow(7, "CONFIRM", 2);
      if (!"missing".equals(variant)) {
        int quantity = "quantity".equals(variant) ? 4 : 3;
        reserve(8, quantity);
        flow(8, "CONFIRM", quantity);
      }
      if ("extra".equals(variant)) {
        reserve(9, 1);
        flow(9, "CONFIRM", 1);
      }
      request("CONFIRMED");
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, variant);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void snapshotWrongHashRequestIdentityAndSceneCannotAuthorizeTerminal() {
    for (String variant : List.of("hash", "identity", "scene")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      flow(7, "CONFIRM", 2);
      flow(8, "CONFIRM", 3);
      request("CONFIRMED");
      if ("hash".equals(variant))
        fixture.update("UPDATE stock_reservation_request SET items_hash=?", "0".repeat(64));
      if ("identity".equals(variant))
        fixture.update("UPDATE stock_reservation_request SET order_no='202610PROOF1'");
      if ("scene".equals(variant))
        fixture.update("UPDATE stock_reservation_request SET scene='SECKILL'");
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, variant);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void snapshotInvalidJsonShapesAndNumbersRemainUnknownWithoutRepair() {
    for (String snapshot :
        List.of(
            "null",
            "{}",
            "[]",
            "[null]",
            "[{\"skuId\":7,\"quantity\":\"2\"},{\"skuId\":8,\"quantity\":3}]",
            "[{\"skuId\":7,\"quantity\":2.5},{\"skuId\":8,\"quantity\":3}]",
            "[{\"skuId\":8,\"quantity\":3},{\"skuId\":7,\"quantity\":2}]",
            "[{\"skuId\":7,\"quantity\":2},{\"skuId\":7,\"quantity\":2}]",
            "[{\"skuId\":7,\"quantity\":2,\"extra\":1},{\"skuId\":8,\"quantity\":3}]")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      flow(7, "CONFIRM", 2);
      flow(8, "CONFIRM", 3);
      request("CONFIRMED");
      fixture.update("UPDATE stock_reservation_request SET items_snapshot=?", snapshot);
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, snapshot);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void snapshotMysqlJsonNormalizationKeepsOriginalWriterHashAndHealthyStates() {
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      if (!"RESERVED".equals(state)) {
        String type = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
        flow(7, type, 2);
        flow(8, type, 3);
      }
      request(state);
      String stored =
          fixture.queryForObject(
              "SELECT items_snapshot FROM stock_reservation_request", String.class);
      assertNotEquals("[{\"skuId\":7,\"quantity\":2},{\"skuId\":8,\"quantity\":3}]", stored);
      var before = facts();
      tracked.checking = true;
      assertEquals(state, read().status);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void snapshotMissingSkuCannotLetActualOrderFallbackReportSucceeded() throws Exception {
    reserve(7, 2);
    flow(7, "CONFIRM", 2);
    request("CONFIRMED");
    var before = facts();
    tracked.checking = true;
    assertEquals("UNKNOWN", orderFallback("PAID"));
    assertEquals(before, facts());
    assertOnlySelects();
  }

  @Test
  void partialAndMixedLegacyFlowsCannotClaimAWholeOrderTerminal() {
    for (String variant : List.of("confirm-pending", "rollback-pending", "mixed", "partial-one")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      if ("confirm-pending".equals(variant)) flow(7, "CONFIRM", 2);
      if ("rollback-pending".equals(variant)) flow(7, "ROLLBACK", 2);
      if ("mixed".equals(variant)) {
        flow(7, "CONFIRM", 2);
        flow(8, "ROLLBACK", 3);
      }
      if ("partial-one".equals(variant)) flow(7, "CONFIRM", 1);
      var before = facts();
      tracked.checking = true;
      StockReservationVO result = read();
      assertEquals("UNKNOWN", result.status, variant);
      assertTrue(result.items.isEmpty());
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void requestTerminalCannotOverrideIncompleteOrOppositeSqlEvidence() {
    for (String state : List.of("CONFIRMED", "ROLLED_BACK", "RESERVED")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      flow(7, "CONFIRM", 2);
      request(state);
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, state);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void realNegativeZeroExcessAndAggregateCapacityOverflowRemainUnknown() {
    for (String variant :
        List.of("negative", "zero-sku", "excess", "addition-overflow", "sum-overflow")) {
      reset();
      if ("zero-sku".equals(variant)) {
        reserve(0, 2);
        flow(0, "CONFIRM", 2);
      } else if ("sum-overflow".equals(variant)) {
        reserve(7, Integer.MAX_VALUE);
        reserve(7, Integer.MAX_VALUE);
      } else if ("addition-overflow".equals(variant)) {
        reserve(7, Integer.MAX_VALUE);
        flow(7, "CONFIRM", Integer.MAX_VALUE);
        flow(7, "ROLLBACK", 1);
      } else {
        reserve(7, 2);
        flow(7, "CONFIRM", "negative".equals(variant) ? -1 : 3);
      }
      request("CONFIRMED");
      var before = facts();
      tracked.checking = true;
      assertEquals("UNKNOWN", read().status, variant);
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void completeMultiSkuFactsPreserveStatesWithAndWithoutRequests() {
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK"))
      for (boolean hasRequest : List.of(false, true)) {
        reset();
        reserve(7, 2);
        reserve(8, 3);
        if (!"RESERVED".equals(state)) {
          String type = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
          flow(7, type, 2);
          flow(8, type, 3);
        }
        if (hasRequest) request(state);
        var before = facts();
        tracked.checking = true;
        var result = read();
        assertEquals(state, result.status);
        assertEquals(2, result.items.size());
        assertEquals(before, facts());
        assertOnlySelects();
      }
  }

  @Test
  void actualOrderFallbackDoesNotConvertPartialStockIntoCompletion() throws Exception {
    reserve(7, 2);
    reserve(8, 3);
    flow(7, "CONFIRM", 2);
    var before = facts();
    tracked.checking = true;
    assertEquals("UNKNOWN", orderFallback("PAID"));
    assertEquals("UNKNOWN", orderFallback("CANCELLED"));
    assertEquals(before, facts());
    assertOnlySelects();
  }

  @Test
  void actualOrderFallbackRetainsKnownSameDirectionTerminalSemantics() throws Exception {
    for (String orderState : List.of("PAID", "CANCELLED")) {
      reset();
      reserve(7, 2);
      reserve(8, 3);
      String type = "PAID".equals(orderState) ? "CONFIRM" : "ROLLBACK";
      flow(7, type, 2);
      flow(8, type, 3);
      var before = facts();
      tracked.checking = true;
      assertEquals("SUCCEEDED", orderFallback(orderState));
      assertEquals(before, facts());
      assertOnlySelects();
    }
  }

  @Test
  void existingHttpGetReturnsUnknownAndDoesNotTouchRedisOrCreateProjection() throws Exception {
    reserve(7, 2);
    reserve(8, 3);
    flow(7, "CONFIRM", 2);
    var before = facts();
    tracked.checking = true;
    StockController controller =
        new StockController(
            StockTestServices.query(null, mapper, new ObjectMapper(), null), null, null);
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.items").isEmpty());
    assertEquals(before, facts());
    assertOnlySelects();
  }

  /** 只准备owned原始流水；每条key唯一，不调用库存写接口。 */
  private static void flow(long skuId, String type, int quantity) {
    long nextId = fixture.queryForObject("SELECT COUNT(*)+1 FROM stock_flow", Long.class);
    fixture.update(
        "INSERT INTO stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
            + " VALUES(?,?,?,?,?,?,'2026-10-10 02:00:00.123')",
        nextId,
        skuId,
        ORDER_NO,
        type,
        quantity,
        "proof-flow-" + nextId);
  }

  /** 添加原RESERVE，不改stock_sku数量。 */
  private static void reserve(long skuId, int quantity) {
    flow(skuId, "RESERVE", quantity);
  }

  /** 准备原请求证据，用于证明其状态不能覆盖矛盾流水。 */
  private static void request(String state) {
    String snapshot = "[{\"skuId\":7,\"quantity\":2},{\"skuId\":8,\"quantity\":3}]";
    try {
      String hash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      fixture.update(
          "INSERT INTO"
              + " stock_reservation_request(id,order_no,scene,items_snapshot,items_hash,status,created_at,updated_at)"
              + " VALUES(1,?,'NORMAL',?,?,?,'2026-10-10 02:00:00.123','2026-10-10 02:00:00.123')",
          ORDER_NO,
          snapshot,
          hash,
          state);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
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
