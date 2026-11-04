package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
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
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实秒杀settlement/legacy SQL及实际Order fallback证明；Order行用Mapper替身，不冒充HTTP/分布式验收。 */
class SeckillReservationOrderIdentityMySqlIT {
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
    database = "cm16t31_" + UUID.randomUUID().toString().replace("-", "");
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
    service = StockTestServices.reader(mapper, new ObjectMapper());
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
              dependency[0], false, SeckillReservationOrderIdentityMySqlIT.class.getClassLoader());
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
            dependencyDirectory = Files.createTempDirectory("cm16t31_order_deps_");
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
            SeckillReservationOrderIdentityMySqlIT.class.getClassLoader());
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
      if (database != null && database.matches("cm16t31_[0-9a-f]{32}")) {
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
        "CM16T31 original Stock checksums unchanged; owned database and classloader removed");
  }

  @Test
  void settlementAliasesCannotAuthorizeOrderFallbackSuccess() throws Exception {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "CONFIRMED", "RELEASED"))
      for (String original : List.of("202610PROOF1", "202610proof1 ", "202610próof1"))
        assertions.add(
            () -> {
              reset();
              seedSettlement(original, state);
              // 实际CI候选必须被读取；不能通过SQL过滤或不存在行掩盖错误原身份。
              assertEquals(original, settlementMapper.findSettlement(ORDER_NO).orderNo);
              var before = facts();
              tracked.sql.clear();
              tracked.checking = true;
              var result = read();
              assertEquals(ORDER_NO, result.orderNo);
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              List<org.junit.jupiter.api.function.Executable> proofChecks = new ArrayList<>();
              proofChecks.add(
                  () -> {
                    assertEquals("UNKNOWN", result.status, state + original);
                    assertTrue(result.items.isEmpty());
                  });
              for (String orderState : List.of("PAID", "CANCELLED")) {
                proofChecks.add(
                    () -> {
                      tracked.sql.clear();
                      assertEquals(
                          "UNKNOWN", orderFallback(orderState), state + original + orderState);
                      assertEquals(1, tracked.sql.size());
                      assertOnlySelects();
                    });
              }
              assertAll(proofChecks);
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void legacyAliasesAreUnknownInBothPrimaryAndBareFallback() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("ACCEPTED", "PENDING", "REJECTED"))
      for (String original : List.of("202610PROOF1", "202610proof1 ", "202610próof1"))
        assertions.add(
            () -> {
              reset();
              seedLegacy(state, original);
              assertEquals(original, mapper.findSeckillByOrderNo(ORDER_NO).get("order_no"));
              var before = facts();
              tracked.sql.clear();
              tracked.checking = true;
              var result = read();
              assertEquals("UNKNOWN", result.status, state + original);
              assertEquals(ORDER_NO, result.orderNo);
              assertTrue(result.items.isEmpty());
              assertEquals("ACCEPTED".equals(state) ? 2 : 3, tracked.sql.size());
              assertOnlySelects();
              tracked.sql.clear();
              var bare = StockTestServices.reader(mapper, new ObjectMapper());
              var fallback = (StockReservationVO) bare.reservation(ORDER_NO).data;
              assertEquals("UNKNOWN", fallback.status);
              assertTrue(fallback.items.isEmpty());
              assertEquals(1, tracked.sql.size());
              assertOnlySelects();
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void exactStatesMissingAndOrderFallbackKeepTheirOriginalMeaning() throws Exception {
    for (String state : List.of("OPEN", "CONFIRMED", "RELEASED", "PROJECTION_BLOCKED")) {
      reset();
      seedSettlement(ORDER_NO, state);
      var before = facts();
      tracked.checking = true;
      var result = read();
      assertEquals(
          switch (state) {
            case "OPEN" -> "RESERVED";
            case "CONFIRMED" -> "CONFIRMED";
            case "RELEASED" -> "ROLLED_BACK";
            default -> "UNKNOWN";
          },
          result.status);
      assertEquals(1, result.items.size());
      assertEquals(20L, result.items.get(0).skuId);
      assertEquals(1, result.items.get(0).quantity);
      assertEquals(2, tracked.sql.size());
      if (List.of("CONFIRMED", "RELEASED").contains(state))
        assertEquals("SUCCEEDED", orderFallback("RELEASED".equals(state) ? "CANCELLED" : "PAID"));
      assertOnlySelects();
      assertEquals(before, facts());
    }
    for (String state : List.of("ACCEPTED", "PENDING", "REJECTED")) {
      reset();
      seedLegacy(state, ORDER_NO);
      var before = facts();
      tracked.checking = true;
      var result = read();
      assertEquals(
          "ACCEPTED".equals(state) ? "RESERVED" : "REJECTED".equals(state) ? "REJECTED" : "UNKNOWN",
          result.status);
      assertEquals("PENDING".equals(state) ? 0 : 1, result.items.size());
      assertEquals("ACCEPTED".equals(state) ? 2 : 3, tracked.sql.size());
      assertOnlySelects();
      assertEquals(before, facts());
    }
    reset();
    var before = facts();
    tracked.checking = true;
    assertNull(settlementService.reservation(ORDER_NO));
    tracked.sql.clear();
    var missing = read();
    assertEquals("NOT_FOUND", missing.status);
    assertTrue(missing.items.isEmpty());
    assertEquals(5, tracked.sql.size());
    assertOnlySelects();
    assertEquals(before, facts());
  }

  @Test
  void actualHttpGetUsesOriginalIdentityAndNeverEchoesAliasOrWrites() throws Exception {
    seedSettlement("202610próof1", "CONFIRMED");
    var before = facts();
    StockController controller =
        new StockController(
            StockTestServices.query(null, mapper, new ObjectMapper(), settlementService),
            null,
            null);
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    tracked.checking = true;
    String body =
        mvc.perform(get("/api/stock/reservations/" + ORDER_NO))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderNo").value(ORDER_NO))
            .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.data.items").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("202610próof1"));
    assertFalse(body.contains("idempotency"));
    assertEquals(1, tracked.sql.size());
    assertOnlySelects();
    assertEquals(before, facts());
  }

  /** 准备秒杀结算行；只写owned库，不经被测GET。 */
  private static void seedSettlement(String orderNo, String status) {
    fixture.update(
        "INSERT INTO"
            + " seckill_quota_settlement(id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,operation_key,redis_revision,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,1,?,1,?,?,'2026-10-10 02:00:00.123','2026-10-10 02:00:00.123')",
        1L,
        orderNo,
        10L,
        20L,
        8L,
        orderNo + ":idempotency",
        status,
        "CONFIRMED".equals(status)
            ? orderNo + ":CONFIRM"
            : "RELEASED".equals(status)
                    || "RELEASE_PENDING".equals(status)
                    || "PROJECTION_BLOCKED".equals(status)
                ? orderNo + ":ROLLBACK"
                : null,
        "RELEASED".equals(status) ? 4L : null);
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,order_no,activity_id,sku_id,user_id,idempotency_key,status,created_at,updated_at,request_hash,request_payload)"
            + " VALUES(1,?,10,20,8,?,'ACCEPTED','2026-10-09','2026-10-09',REPEAT('0',64),'{}')",
        orderNo,
        orderNo + ":idempotency");
  }

  /** 准备旧秒杀受理行；状态仅由实际Stock fallback读取。 */
  private static void seedLegacy(String status, String orderNo) {
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at,request_hash,request_payload)"
            + " VALUES(?,?,?,?,?,?,?,'2026-10-10 02:00:00.123','2026-10-10 02:00:00.123',REPEAT('0',64),'{}')",
        1L,
        10L,
        20L,
        8L,
        orderNo,
        orderNo + ":idempotency",
        status);
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
