package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.config.OrderShardingRuleFactory;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.*;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 17EH有限创建纵向影子：真实本地事务与独立Intent，远端仅受控计数替身。 */
class OrderCreationShardingShadowMySqlIT {
  /** 真实模板/原Factory的三类逻辑表。 */
  private static final List<String> LOGICAL =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** 原四个月窗口，不按当天补猜路由。 */
  private static final List<String> MONTHS = List.of("202611", "202612", "202701", "202702");

  /** 精确四张普通台账，不克隆Seckill或undo_log。 */
  private static final List<String> LEDGERS =
      List.of(
          "order_idempotency", "order_reservation_intent", "order_outbox", "order_stock_action");

  /** 原创建Clock输入，服务应向下截至毫秒。 */
  private static final OffsetDateTime RAW_CREATED =
      OffsetDateTime.parse("2026-12-31T23:59:59.999500000+08:00");

  /** 固定golden创建组件。 */
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-12-31T23:59:59.999");

  /** 原加三十分钟的跨年过期组件。 */
  private static final LocalDateTime EXPIRE_AT = CREATED_AT.plusMinutes(30);

  /** 真实owned触发器唯一安全marker。 */
  private static final String FAULT = "CM17EH_OUTBOX_INSERT_FAILURE";

  /** 失败角色唯一允许的触发器名。 */
  private static final String TRIGGER = "cm17eh_outbox_fail";

  /** 触发器动作声明，创建与完整metadata核验共用同一固定SQL。 */
  private static final String TRIGGER_ACTION =
      "SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644, MESSAGE_TEXT='" + FAULT + "'";

  /** 原traceId输入，不在测试中合成生产payload。 */
  private static final String TRACE = "cm17eh-local-create";

  /** 安全审计只序列化普通字段，不依赖Java时间模块。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 原组件审计格式。 */
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

  /** 固定商品元数据与价格，不访问真实Product。 */
  private static final Map<Long, ProductClient.SkuView> PRODUCTS =
      Map.of(
          7L,
          new ProductClient.SkuView(
              7L, 70L, "First product", "SKU-7", new BigDecimal("10.00"), Map.of("size", "M")),
          8L,
          new ProductClient.SkuView(
              8L, 80L, "Second product", "SKU-8", new BigDecimal("3.25"), Map.of("size", "L")));

  /** 原地址ID及稳定合法快照。 */
  private static final Map<String, Object> ADDRESS = Map.of("id", 9L, "receiver", "Fixture");

  /** 原清理证明的完整rawJSON，不归一化或重新授权。 */
  private static final Map<Long, CartCleanupSnapshotDTO> CART =
      Map.of(
          7L,
          new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":1,\"checked\":true}"),
          8L,
          new CartCleanupSnapshotDTO(8L, "{\"skuId\":8,\"quantity\":2,\"checked\":true}"));

  /** 源只读元数据/指纹及精确新库生命周期入口。 */
  private static JdbcTemplate source;

  /** 源连接归属计数，不是连接池。 */
  private static GuardedDataSource sourceDataSource;

  /** 已验证且永不输出的连接URL。 */
  private static String sourceUrl;

  /** 源完整表定义及checksum起始指纹。 */
  private static Map<String, Object> sourceBefore;

  /** 七个真实克隆来源的定义hash。 */
  private static final Map<String, String> DEFINITIONS = new TreeMap<>();

  /** 当前类唯一原生审计目录。 */
  private static Path auditRoot;

  /** 当前类实际CREATE返回的随机库名单。 */
  private static final Set<String> CREATED = new TreeSet<>();

  /** 当前类精确删除并确认不存在的库名单。 */
  private static final Set<String> DELETED = new TreeSet<>();

  @BeforeAll
  static void establishSource() throws Exception {
    assertEquals(ZoneId.of("Asia/Shanghai"), ZoneId.systemDefault());
    assertClean();
    String runId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    auditRoot = Path.of("target", "cm17eh-create-shadow", runId).toAbsolutePath();
    Files.createDirectories(auditRoot.getParent());
    Files.createDirectory(auditRoot);
    write(
        auditRoot.resolve("class-start.json"),
        Map.of(
            "runId",
            runId,
            "startedAt",
            Instant.now().toString(),
            "review",
            "17EH",
            "fixtureOnly",
            true));
    System.out.println("CM17EH_CREATE_SHADOW runId=" + runId + " startedAt=" + Instant.now());
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/?#]+/cloudmall_order(\\?[^#]*)?"))
      throw new IllegalStateException("CM17EH_SOURCE_CATALOG_REQUIRED");
    sourceDataSource = new GuardedDataSource(boundedUrl(sourceUrl), "cloudmall_order");
    source = jdbc(sourceDataSource);
    sourceBefore = sourceFingerprint();
    write(auditRoot.resolve("source-before.json"), sourceBefore);
    for (String table : cloneSources()) {
      assertEquals(
          List.of("BASE TABLE"),
          source.queryForList(
              "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND"
                  + " TABLE_NAME=?",
              String.class,
              table));
      DEFINITIONS.put(table, definition(source, table));
    }
    write(auditRoot.resolve("source-definitions.json"), DEFINITIONS);
  }

  @AfterAll
  static void finishSource() throws Exception {
    List<Throwable> errors = new ArrayList<>();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("created", new ArrayList<>(CREATED));
    result.put("deleted", new ArrayList<>(DELETED));
    attempt(
        errors,
        () -> {
          assertNotNull(sourceBefore);
          Map<String, Object> after = sourceFingerprint();
          result.put("sourceAfter", after);
          assertEquals(sourceBefore, after);
          sourceDataSource.assertClosedConnections();
          assertClean();
          assertEquals(CREATED, DELETED, "未知或失败fixture必须保留");
        });
    result.put("preserveRequired", !CREATED.equals(DELETED));
    result.put("errors", safeErrors(errors));
    attempt(errors, () -> write(auditRoot.resolve("class-finish.json"), result));
    throwIfErrors(errors);
  }

  @Test
  void creationCommitsAllLocalRowsAfterIndependentIntent() throws Exception {
    try (Run run = new Run("creationCommitsAllLocalRowsAfterIndependentIntent")) {
      Fixture physical = run.fixture(false, false);
      Fixture logical = run.fixture(true, false);
      Map<String, Object> physicalResult = physical.exercise();
      Map<String, Object> logicalResult = logical.exercise();
      assertEquals(physicalResult, logicalResult);
      write(
          run.path.resolve("normalized-comparison.json"),
          Map.of(
              "physical",
              physicalResult,
              "logical",
              logicalResult,
              "normalization",
              "original UUID/id retained per-route; comparison omits generated identities and"
                  + " orders business items by SKU"));
      run.assertionsPassed = true;
    }
  }

  @Test
  void outboxPersistenceFailureRollsBackOrderGroupAndPreservesIntentCompensation()
      throws Exception {
    try (Run run =
        new Run("outboxPersistenceFailureRollsBackOrderGroupAndPreservesIntentCompensation")) {
      Fixture physical = run.fixture(false, true);
      Fixture logical = run.fixture(true, true);
      Map<String, Object> physicalResult = physical.exercise();
      Map<String, Object> logicalResult = logical.exercise();
      assertEquals(physicalResult, logicalResult);
      write(
          run.path.resolve("normalized-comparison.json"),
          Map.of(
              "physical",
              physicalResult,
              "logical",
              logicalResult,
              "normalization",
              "original failure/UUID/id retained per-route; only fixed business result and call"
                  + " counts compared"));
      run.assertionsPassed = true;
    }
  }

  /** 方法级所有fixture独立关闭与审计，任何未知均阻止当前方法所有删除。 */
  private static final class Run implements AutoCloseable {
    /** 当前方法唯一目录。 */
    private final Path path;

    /** 实际准备的fixture，包含setup失败者。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 仅所有末尾断言通过后设置。 */
    private boolean assertionsPassed;

    Run(String method) throws Exception {
      assertClean();
      path = auditRoot.resolve(method);
      Files.createDirectory(path);
      write(
          path.resolve("method-start.json"),
          Map.of("method", method, "startedAt", Instant.now().toString()));
    }

    Fixture fixture(boolean logical, boolean failure) throws Exception {
      Fixture fixture =
          new Fixture(path.resolve(logical ? "logical" : "physical"), logical, failure);
      fixtures.add(fixture);
      fixture.prepare();
      return fixture;
    }

    /** 每个close/后置门分别尽力；原异常由try-with-resources保留并附suppressed。 */
    @Override
    public void close() throws Exception {
      // 1. 所有shadow先关闭；审计错误不能跳过资源收尾。
      List<Throwable> errors = new ArrayList<>();
      for (Fixture fixture : fixtures) attempt(errors, fixture::closeShadow);
      for (Fixture fixture : fixtures) {
        attempt(
            errors,
            () ->
                write(
                    fixture.path.resolve("close-input-observation.json"),
                    Map.of(
                        "inputs",
                        fixture.inputs,
                        "logicalInputs",
                        fixture.logicalInputs,
                        "statusWrites",
                        fixture.statusWrites,
                        "phases",
                        fixture.phases,
                        "executedSql",
                        fixture.base == null ? List.of() : fixture.base.executions,
                        "terminalVerified",
                        fixture.terminalVerified)));
        attempt(
            errors,
            () -> {
              if (fixture.base != null) fixture.base.assertClosedConnections();
              assertClean();
            });
        attempt(
            errors,
            () -> {
              assertTrue(fixture.created);
              assertNotNull(fixture.sql);
              Facts fresh = fixture.facts();
              write(fixture.path.resolve("before-delete.json"), fresh);
              assertTrue(fixture.terminalVerified);
              fixture.verifyTerminal(fresh);
              assertEquals(fixture.finalFacts, fresh);
            });
      }
      attempt(
          errors,
          () -> {
            assertTrue(assertionsPassed);
            assertEquals(sourceBefore, sourceFingerprint());
            sourceDataSource.assertClosedConnections();
            assertClean();
          });
      // 2. 每库删除前重读实际catalog、全facts/对象与本次归属，不扫描任何前缀。
      if (errors.isEmpty())
        for (Fixture fixture : fixtures) {
          attempt(
              errors,
              () -> {
                assertTrue(CREATED.contains(fixture.database));
                assertTrue(fixture.created);
                assertEquals(
                    fixture.database,
                    fixture.sql.queryForObject("SELECT DATABASE()", String.class));
                Facts fresh = fixture.facts();
                fixture.verifyTerminal(fresh);
                assertEquals(fixture.finalFacts, fresh);
                write(fixture.path.resolve("delete-gate.json"), fresh);
                fixture.base.assertClosedConnections();
                assertTrue(!fixture.logical || fixture.shadowClosed);
                assertClean();
                source.execute("DROP DATABASE " + fixture.database);
                assertEquals(
                    0,
                    source.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                        Integer.class,
                        fixture.database));
                DELETED.add(fixture.database);
              });
          if (!errors.isEmpty()) break;
        }
      // 3. 全部结果独立记录；不把本片没有owned worker解释成内部线程join。
      for (Fixture fixture : fixtures)
        attempt(
            errors,
            () ->
                write(
                    fixture.path.resolve("cleanup.json"),
                    Map.of(
                        "database",
                        fixture.database,
                        "createdConfirmed",
                        fixture.created,
                        "deletedConfirmed",
                        DELETED.contains(fixture.database),
                        "preserveRequired",
                        !DELETED.contains(fixture.database),
                        "shadowPresent",
                        fixture.shadow != null,
                        "shadowCloseReturned",
                        fixture.shadowClosed,
                        "ownedWorkersCreated",
                        0,
                        "allInternalThreadsJoinedClaimed",
                        false,
                        "errors",
                        safeErrors(errors))));
      attempt(
          errors,
          () ->
              write(
                  path.resolve("method-finish.json"),
                  Map.of(
                      "assertionsPassed",
                      assertionsPassed,
                      "finishedAt",
                      Instant.now().toString(),
                      "errors",
                      safeErrors(errors))));
      throwIfErrors(errors);
    }
  }

  /** 单路同DataSource/TM的真实创建图与19表owned资源。 */
  private static final class Fixture {
    /** 精确本次拥有的随机schema。 */
    private final String database = "cm17eh_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前路安全证据目录。 */
    private final Path path;

    /** 仅逻辑路显式构建真实4.1.1。 */
    private final boolean logical;

    /** 仅失败路允许声明触发器。 */
    private final boolean failure;

    /** 每路原幂等键，不与其他fixture共用。 */
    private final String key = "cm17eh-" + UUID.randomUUID();

    /** owned物理透传与连接计数。 */
    private GuardedDataSource base;

    /** owned独立事实入口。 */
    private JdbcTemplate sql;

    /** 唯一可选shadow。 */
    private ShardingDataSource shadow;

    /** 所有生产Mapper与TM共同使用的路由DataSource。 */
    private DataSource route;

    /** 同一生产图唯一事务管理器。 */
    private DataSourceTransactionManager manager;

    /** 同一生产图JDBC入口，只透传观察实际输入。 */
    private ObservingJdbcTemplate business;

    /** 真正create注解代理。 */
    private OrderTestService service;

    /** 库创建与影子关闭的明确返回事实。 */
    private boolean created, shadowClosed;

    /** 所有终态与调用链断言通过后才授权收尾核验。 */
    private boolean terminalVerified;

    /** 最终完整表/对象事实，删前必须全等。 */
    private Facts finalFacts;

    /** 失败角色触发器创建后的完整准确对象事实。 */
    private Map<String, Object> triggerPlan;

    /** 原生产UPDATE/INSERT输入及状态时间，不由终态行反推身份。 */
    private final List<Map<String, Object>> inputs = new ArrayList<>();

    /** 五个逻辑入口实际输入SQL与完整字段绑定。 */
    private final List<Map<String, Object>> logicalInputs = new ArrayList<>();

    /** 同步提交/失败/清理观察，不持有连接。 */
    private final List<Map<String, Object>> phases = new ArrayList<>();

    /** 动态原意图tuple与真实最终状态时间。 */
    private Map<String, Object> intentInput;

    /** 原订单号仅来自Service生成的Intent参数。 */
    private String orderNo;

    /** 正常提交前观察已完成的事实。 */
    private boolean beforeCommitObserved;

    /** markCommitted及其原受影响数。 */
    private int committedCalls, committedRows;

    /** 原Outbox.insert抛出的同一个异常对象。 */
    private RuntimeException persistenceFailure;

    /** 本地计数Stock，默认方法保原operationKey派生。 */
    private final StockClient stock = mock(StockClient.class, CALLS_REAL_METHODS);

    /** 本地计数Product，不委派。 */
    private final ProductClient product = mock(ProductClient.class);

    /** 本地计数User，不委派。 */
    private final UserClient user = mock(UserClient.class);

    /** 本地计数Cart，不委派。 */
    private final CartClient cart = mock(CartClient.class);

    /** 提交后按原快照实际调用的SKU顺序。 */
    private final List<Long> cleaned = new ArrayList<>();

    /** 原create代理调用前的真实上海时间下界，不替换任何业务now。 */
    private LocalDateTime createCallStarted;

    /** 原状态更新参数及返回时间窗。 */
    private final List<Map<String, Object>> statusWrites = new ArrayList<>();

    /** 固定终态golden：动态ID来自原输入，状态时间来自原调用范围核验。 */
    private Map<String, Object> intentGolden;

    Fixture(Path path, boolean logical, boolean failure) {
      this.path = path;
      this.logical = logical;
      this.failure = failure;
    }

    void prepare() throws Exception {
      Files.createDirectory(path);
      assertClean();
      assertTrue(database.matches("cm17eh_[0-9a-f]{32}"));
      write(
          path.resolve("ownership-plan.json"),
          Map.of(
              "database",
              database,
              "logical",
              logical,
              "failure",
              failure,
              "expectedTables",
              expectedTables(),
              "expectedTableCount",
              19,
              "trigger",
              failure ? TRIGGER : "NONE",
              "clockInput",
              RAW_CREATED.toString(),
              "createdGolden",
              MILLIS.format(CREATED_AT),
              "fixtureOnly",
              true));
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      source.execute(
          "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      CREATED.add(database);
      created = true;
      base =
          new GuardedDataSource(
              boundedUrl(sourceUrl.replace("/cloudmall_order", "/" + database)), database);
      sql = jdbc(base);
      for (String table : cloneSources()) {
        sql.execute("CREATE TABLE " + table + " LIKE cloudmall_order." + table);
        assertEquals(DEFINITIONS.get(table), definition(sql, table));
      }
      OrderSqlMapper ddl = new OrderSqlMapper(sql);
      for (String month : MONTHS) ddl.createMonthlyOrderTables(month);
      Facts initial = facts();
      assertEquals(expectedTables(), initial.tables.keySet());
      for (TableFact fact : initial.tables.values()) assertEquals(0, fact.count);
      verifyObjects(initial.objects, false);
      write(path.resolve("initial-facts.json"), initial);
      if (failure) {
        sql.execute(
            "CREATE TRIGGER "
                + TRIGGER
                + " BEFORE INSERT ON order_outbox FOR EACH ROW "
                + TRIGGER_ACTION);
        triggerPlan = trigger(sql);
        verifyDeclaredTrigger(triggerPlan);
        write(path.resolve("trigger-created.json"), triggerPlan);
      }
      if (logical) {
        Map<String, Collection<String>> inventory = new LinkedHashMap<>();
        Set<String> observed = tableTypes(sql).keySet();
        for (String name : LOGICAL)
          inventory.put(
              name, observed.stream().filter(table -> table.matches(name + "_[0-9]{6}")).toList());
        shadow =
            (ShardingDataSource)
                ShardingDataSourceFactory.createDataSource(
                    Map.of("ds0", base),
                    OrderShardingRuleFactory.create(YearMonth.of(2026, 12), inventory),
                    new Properties());
        route = shadow;
        write(
            path.resolve("rules-created.json"),
            Map.of(
                "inventory",
                inventory,
                "factoryCurrentMonth",
                "2026-12",
                "defaultDataSourceAdded",
                false));
      } else route = base;
      business = new ObservingJdbcTemplate(this, route);
      manager = new DataSourceTransactionManager(route);
      assertSame(route, business.getDataSource());
      assertSame(route, manager.getDataSource());
      OrderSqlMapper mapper =
          logical ? new LogicalOrderSqlMapper(this, business) : new OrderSqlMapper(business);
      IntentObserver intents = new IntentObserver(this, business);
      OrderReservationIntentService intentService =
          proxy(new OrderReservationIntentService(intents), manager);
      OrderTestService target =
          new OrderTestService(stock, product, user, cart, mapper, new ObjectMapper());
      ReflectionTestUtils.setField(target, "reservationIntentService", intentService);
      ReflectionTestUtils.setField(target, "reservationIntentMapper", intents);
      ReflectionTestUtils.setField(target, "orderOutboxMapper", new OutboxObserver(this, business));
      ReflectionTestUtils.setField(
          target, "orderStockActionMapper", new OrderStockActionMapper(business));
      ReflectionTestUtils.setField(
          target, "creationClock", Clock.fixed(RAW_CREATED.toInstant(), ZoneOffset.ofHours(8)));
      service = proxy(target, manager);
      configureClients();
      AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
      assertEquals(
          TransactionDefinition.PROPAGATION_REQUIRED,
          attributes
              .getTransactionAttribute(
                  com.cloudmall.order.service.impl.OrderCreationServiceImpl.class.getMethod(
                      "create",
                      String.class,
                      CreateOrderDTO.class,
                      java.util.function.LongSupplier.class),
                  com.cloudmall.order.service.impl.OrderCreationServiceImpl.class)
              .getPropagationBehavior());
      for (String method : List.of("createIntent", "markReserved", "markReleased")) {
        Class<?> arg =
            method.equals("createIntent") ? OrderReservationIntentPO.class : String.class;
        assertEquals(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            attributes
                .getTransactionAttribute(
                    OrderReservationIntentService.class.getMethod(method, arg),
                    OrderReservationIntentService.class)
                .getPropagationBehavior());
      }
      write(
          path.resolve("graph.json"),
          Map.of(
              "sameRouteDataSourceAndTM",
              true,
              "realProductionAnnotations",
              true,
              "intentRequiresNew",
              true,
              "seataXid",
              "ABSENT",
              "bootContextStarted",
              false,
              "route",
              logical ? "SHARDING411" : "PHYSICAL"));
    }

    /** 两个SKU的本地计数契约，全部读取或回调均核原Auth和无XID。 */
    void configureClients() {
      when(user.addresses())
          .thenAnswer(
              call -> {
                assertClientContext();
                return ApiResponse.ok(List.of(ADDRESS));
              });
      for (long sku : List.of(7L, 8L)) {
        when(product.sku(sku))
            .thenAnswer(
                call -> {
                  assertClientContext();
                  return ApiResponse.ok(PRODUCTS.get(sku));
                });
        when(cart.cleanupSnapshot(sku, 7L))
            .thenAnswer(
                call -> {
                  assertClientContext();
                  return ApiResponse.ok(CART.get(sku));
                });
      }
      when(stock.reserve(any()))
          .thenAnswer(
              call -> {
                assertClientContext();
                StockClient.Reservation request = call.getArgument(0);
                assertEquals(orderNo, request.orderNo());
                assertEquals("NORMAL", request.scene());
                assertEquals(
                    List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)),
                    request.items());
                observe("reserve-entry", 0, 0, 0, 0, "INIT", false);
                return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
              });
      when(stock.rollback(anyString(), any()))
          .thenAnswer(
              call -> {
                assertClientContext();
                assertEquals(orderNo, call.getArgument(0));
                StockClient.Action action = call.getArgument(1);
                assertEquals(orderNo + ":ROLLBACK", action.operationKey());
                return ApiResponse.ok(null);
              });
      when(stock.reservation(anyString()))
          .thenAnswer(
              call -> {
                assertClientContext();
                assertEquals(orderNo, call.getArgument(0));
                StockReservationStatusVO proof = new StockReservationStatusVO();
                proof.orderNo = orderNo;
                proof.status = "ROLLED_BACK";
                return ApiResponse.ok(proof);
              });
      when(cart.cleanupItem(anyLong(), anyLong(), any()))
          .thenAnswer(
              call -> {
                assertClientContext();
                Long sku = call.getArgument(0);
                assertEquals(7L, (Long) call.getArgument(1));
                assertEquals(CART.get(sku), call.getArgument(2));
                assertTrue(beforeCommitObserved);
                assertFalse(failure);
                try (Connection independent = base.getConnection()) {
                  Map<String, Object> view = window(independent);
                  verifyWindow(view, 1, 2, 1, 1, "COMMITTED");
                  verifyIntent((List<Map<String, Object>>) view.get("intent"), "COMMITTED", true);
                  verifyGroupRows(view);
                  phases.add(
                      Map.of(
                          "phase",
                          "cart-afterCommit",
                          "skuId",
                          sku,
                          "originalSnapshot",
                          CART.get(sku),
                          "independentCommittedView",
                          view,
                          "springTransactionActiveDuringCallback",
                          TransactionSynchronizationManager.isActualTransactionActive()));
                }
                cleaned.add(sku);
                return ApiResponse.ok(true);
              });
    }

    Map<String, Object> exercise() throws Exception {
      assertClean();
      Map<String, String> previousMdc = MDC.getCopyOfContextMap();
      base.recording = true;
      ApiResponse<?> response = null;
      DataAccessException expectedFailure = null;
      try {
        AuthContext.set(7L, "USER");
        MDC.put("traceId", TRACE);
        createCallStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (failure) {
          expectedFailure =
              assertThrows(DataAccessException.class, () -> service.create(key, request()));
          assertSame(persistenceFailure, expectedFailure);
          assertTrue(triggerCause(expectedFailure));
          assertEquals(0, expectedFailure.getSuppressed().length);
        } else {
          response = service.create(key, request());
          assertEquals("0", response.code);
          verifyResponse((OrderVO) response.data);
        }
      } finally {
        base.recording = false;
        AuthContext.clear();
        if (previousMdc == null) MDC.clear();
        else MDC.setContextMap(previousMdc);
      }
      assertClean();
      base.assertClosedConnections();
      try (Connection independent = base.getConnection()) {
        Map<String, Object> view = window(independent);
        verifyWindow(
            view,
            failure ? 0 : 1,
            failure ? 0 : 2,
            failure ? 0 : 1,
            failure ? 0 : 1,
            failure ? "RELEASED" : "COMMITTED");
        verifyIntent(
            (List<Map<String, Object>>) view.get("intent"),
            failure ? "RELEASED" : "COMMITTED",
            true);
        if (!failure) verifyGroupRows(view);
      }
      assertEquals(failure ? 0 : 1, committedCalls);
      assertEquals(failure ? 0 : 1, committedRows);
      assertEquals(!failure, beforeCommitObserved);
      assertEquals(failure ? List.of() : List.of(7L, 8L), cleaned);
      verify(user).addresses();
      verify(product).sku(7L);
      verify(product).sku(8L);
      verify(cart).cleanupSnapshot(7L, 7L);
      verify(cart).cleanupSnapshot(8L, 7L);
      verify(stock)
          .reserve(
              new StockClient.Reservation(
                  orderNo,
                  List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)),
                  "NORMAL"));
      if (failure) {
        verify(stock).rollback(orderNo);
        verify(stock).rollback(orderNo, new StockClient.Action(orderNo + ":ROLLBACK"));
        verify(stock).reservation(orderNo);
      } else {
        verify(cart).cleanupItem(7L, 7L, CART.get(7L));
        verify(cart).cleanupItem(8L, 7L, CART.get(8L));
      }
      verifyNoMoreInteractions(user, product, cart, stock);
      verifyExecution();
      finalFacts = facts();
      verifyTerminal(finalFacts);
      Map<String, Object> expectedFinalRows = new TreeMap<>();
      for (String table : expectedTables()) expectedFinalRows.put(table, expectedRows(table));
      write(path.resolve("expected-final-rows.json"), expectedFinalRows);
      write(path.resolve("final-facts.json"), finalFacts);
      write(path.resolve("original-inputs.json"), inputs);
      write(path.resolve("status-writes.json"), statusWrites);
      write(path.resolve("logical-inputs.json"), logicalInputs);
      write(path.resolve("executed-sql.json"), base.executions);
      write(path.resolve("transaction-phases.json"), phases);
      Map<String, Object> result =
          Map.of(
              "status",
              failure ? "RELEASED" : "PENDING_PAYMENT",
              "totalAmount",
              "16.50",
              "createdAt",
              MILLIS.format(CREATED_AT),
              "expireAt",
              MILLIS.format(EXPIRE_AT),
              "counts",
              List.of(failure ? 0 : 1, failure ? 0 : 2, failure ? 0 : 1, failure ? 0 : 1),
              "reserveCalls",
              1,
              "rollbackCalls",
              failure ? 1 : 0,
              "reservationProofCalls",
              failure ? 1 : 0,
              "cartCleanupCalls",
              cleaned.size());
      write(
          path.resolve("result.json"),
          Map.of(
              "normalizedBusiness",
              result,
              "originalOrderNo",
              orderNo,
              "originalKey",
              key,
              "intentGolden",
              intentGolden,
              "expectedTriggerFailure",
              expectedFailure == null ? Map.of() : safeError(expectedFailure),
              "trigger45000Vendor1644MarkerProven",
              failure,
              "allRemoteInteractionsAreLocalCountedDoubles",
              true));
      terminalVerified = true;
      return result;
    }

    /** 只在真实markCommitted返回1之后注册，原TM仍负责最终commit和Cart afterCommit。 */
    void committed(String originalOrderNo, int rows) {
      committedCalls++;
      committedRows += rows;
      assertEquals(orderNo, originalOrderNo);
      assertEquals(1, rows);
      assertFalse(failure);
      assertActive();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            /** 同一同步相位依次读bound和新独立连接，无worker或并发平台。 */
            @Override
            public void beforeCommit(boolean readOnly) {
              assertFalse(readOnly);
              assertEquals(List.of(), cleaned);
              try {
                observe("beforeCommit-after-markCommitted", 1, 2, 1, 1, "RESERVED", true);
                beforeCommitObserved = true;
              } catch (Exception observationFailure) {
                throw new IllegalStateException(
                    "CM17EH_BEFORE_COMMIT_OBSERVATION_FAILED", observationFailure);
              }
            }
          });
    }

    /** 同一bound事务与显式独立物理连接顺序普通SELECT，不调用CHECKSUM/DDL/加锁。 */
    @SuppressWarnings("unchecked")
    void observe(
        String phase,
        int main,
        int items,
        int idem,
        int outbox,
        String independentIntent,
        boolean committed)
        throws Exception {
      assertActive();
      assertEquals(List.of(), cleaned);
      Connection bound = DataSourceUtils.getConnection(route);
      try {
        assertFalse(bound.getAutoCommit());
        assertFalse(bound.isReadOnly());
        Map<String, Object> own = window(bound);
        verifyCounts(own, main, items, idem, outbox);
        if (!committed && main == 1) verifyLocalRows(own, false);
        if (committed) {
          verifyIntent((List<Map<String, Object>>) own.get("intent"), "COMMITTED", true);
          verifyGroupRows(own);
        }
        try (Connection independent = base.getConnection()) {
          assertNotSame(bound, independent);
          assertTrue(independent.getAutoCommit());
          Map<String, Object> external = window(independent);
          verifyWindow(external, 0, 0, 0, 0, independentIntent);
          verifyIntent(
              (List<Map<String, Object>>) external.get("intent"), independentIntent, false);
          phases.add(
              Map.of(
                  "phase",
                  phase,
                  "boundConnectionIdentity",
                  System.identityHashCode(bound),
                  "independentConnectionIdentity",
                  System.identityHashCode(independent),
                  "boundView",
                  own,
                  "independentView",
                  external,
                  "boundAutoCommit",
                  false,
                  "boundReadOnly",
                  false,
                  "actualIsolation",
                  bound.getTransactionIsolation(),
                  "sameRouteTM",
                  true,
                  "xidAbsent",
                  RootContext.getXID() == null));
        }
      } finally {
        DataSourceUtils.releaseConnection(bound, route);
      }
    }

    private void assertActive() {
      assertClientContext();
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
      assertTrue(TransactionSynchronizationManager.hasResource(route));
      assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      assertSame(route, business.getDataSource());
      assertSame(route, manager.getDataSource());
    }

    /** 所有初始/最终全表事实，包含准确DDL及触发器/非白名单对象。 */
    Facts facts() {
      assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
      Map<String, TableFact> tables = new TreeMap<>();
      for (String table : tableTypes(sql).keySet()) {
        assertTrue(expectedTables().contains(table));
        List<Map<String, Object>> rows =
            sql.queryForList("SELECT * FROM " + table + " ORDER BY id").stream()
                .map(OrderCreationShardingShadowMySqlIT::canonicalRow)
                .toList();
        long count = sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        assertEquals(count, (long) rows.size());
        tables.put(table, new TableFact(count, checksum(sql, table), definition(sql, table), rows));
      }
      return new Facts(database, tables, objects(sql));
    }

    void verifyTerminal(Facts facts) throws Exception {
      assertEquals(database, facts.database);
      assertEquals(expectedTables(), facts.tables.keySet());
      verifyObjects(facts.objects, failure);
      for (var entry : facts.tables.entrySet()) {
        String table = entry.getKey();
        TableFact actual = entry.getValue();
        String sourceTable =
            LEDGERS.contains(table)
                ? table
                : table.startsWith("mall_order_item_")
                    ? "mall_order_item_template"
                    : table.startsWith("order_status_log_")
                        ? "order_status_log_template"
                        : "mall_order_template";
        assertEquals(DEFINITIONS.get(sourceTable), actual.definition);
        List<Map<String, Object>> expected = expectedRows(table);
        assertEquals(expected.size(), actual.count);
        assertEquals(expected.size(), actual.rows.size());
        for (int index = 0; index < expected.size(); index++)
          assertFullRow(expected.get(index), actual.rows.get(index));
      }
    }

    private List<Map<String, Object>> expectedRows(String table) throws Exception {
      if (table.equals("order_reservation_intent")) return List.of(intentGolden);
      if (failure) return List.of();
      if (table.equals("mall_order_202612")) return List.of(orderGolden());
      if (table.equals("mall_order_item_202612")) return itemGoldens();
      if (table.equals("order_idempotency")) return List.of(idemGolden());
      if (table.equals("order_outbox")) return List.of(outboxGolden());
      return List.of();
    }

    /** 原输入的动态ID与固定业务字段组成golden，不把物理/逻辑互相相等当唯一依据。 */
    private Map<String, Object> orderGolden() throws Exception {
      Map<String, Object> input = oneInput("order");
      Map<String, Object> expected = new LinkedHashMap<>();
      expected.put("id", input.get("id"));
      expected.put("order_no", orderNo);
      expected.put("user_id", 7L);
      expected.put("status", "PENDING_PAYMENT");
      expected.put("total_amount", "16.50");
      expected.put("pay_amount", "16.50");
      expected.put("address_snapshot", JSON.writeValueAsString(ADDRESS));
      expected.put("expire_at", MILLIS.format(EXPIRE_AT));
      expected.put("paid_at", null);
      expected.put("cancelled_at", null);
      expected.put("created_at", MILLIS.format(CREATED_AT));
      expected.put("updated_at", MILLIS.format(CREATED_AT));
      return expected;
    }

    private List<Map<String, Object>> itemGoldens() throws Exception {
      List<Map<String, Object>> expected = new ArrayList<>();
      for (Map<String, Object> event : inputs) {
        if (!event.get("kind").equals("item")) continue;
        Map<String, Object> input = (Map<String, Object>) event.get("tuple");
        long sku = ((Number) input.get("sku_id")).longValue();
        ProductClient.SkuView product = PRODUCTS.get(sku);
        assertNotNull(product);
        int quantity = sku == 7L ? 1 : 2;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", input.get("id"));
        row.put("order_id", oneInput("order").get("id"));
        row.put("order_no", orderNo);
        row.put("product_id", product.productId());
        row.put("sku_id", sku);
        row.put("product_name_snapshot", product.productName());
        row.put("sku_snapshot", JSON.writeValueAsString(product.skuSnapshot()));
        row.put("unit_price", product.unitPrice().toPlainString());
        row.put("quantity", (long) quantity);
        row.put("line_amount", sku == 7L ? "10.00" : "6.50");
        row.put("created_at", MILLIS.format(CREATED_AT));
        expected.add(row);
      }
      assertEquals(2, expected.size());
      assertEquals(
          Set.of(7L, 8L),
          expected.stream()
              .map(row -> row.get("sku_id"))
              .collect(java.util.stream.Collectors.toSet()));
      expected.sort(Comparator.comparingLong(row -> ((Number) row.get("id")).longValue()));
      return expected;
    }

    private Map<String, Object> idemGolden() {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", oneInput("idempotency").get("id"));
      row.put("user_id", 7L);
      row.put("idempotency_key", key);
      row.put("order_no", orderNo);
      row.put("created_at", MILLIS.format(CREATED_AT));
      return row;
    }

    private Map<String, Object> outboxGolden() throws Exception {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", oneInput("outbox").get("id"));
      row.put("event_id", "order-timeout:" + orderNo);
      row.put("event_type", "ORDER_TIMEOUT");
      row.put("order_no", orderNo);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("eventId", "order-timeout:" + orderNo);
      payload.put("eventType", "ORDER_TIMEOUT");
      payload.put("orderNo", orderNo);
      payload.put("expireAt", EXPIRE_AT.atOffset(ZoneOffset.ofHours(8)).toString());
      payload.put("occurredAt", CREATED_AT.atOffset(ZoneOffset.ofHours(8)).toString());
      payload.put("traceId", TRACE);
      row.put("payload", JSON.writeValueAsString(payload));
      row.put("status", "PENDING");
      row.put("attempts", 0L);
      row.put("next_retry_at", MILLIS.format(CREATED_AT));
      row.put("last_error", null);
      row.put("published_at", null);
      row.put("created_at", MILLIS.format(CREATED_AT));
      row.put("updated_at", MILLIS.format(CREATED_AT));
      return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> oneInput(String kind) {
      List<Map<String, Object>> matches =
          inputs.stream().filter(event -> event.get("kind").equals(kind)).toList();
      assertEquals(1, matches.size(), kind);
      return (Map<String, Object>) matches.get(0).get("tuple");
    }

    /** 时间字段仍核原真实状态写参数；DATETIME(3)可能舍入或截断，不重写生产now。 */
    private void verifyIntent(List<Map<String, Object>> rows, String status, boolean terminal)
        throws Exception {
      assertEquals(1, rows.size());
      Map<String, Object> row = rows.get(0);
      assertNotNull(intentInput);
      Map<String, Object> expected = new LinkedHashMap<>(intentInput);
      expected.put("status", status);
      expected.put("last_error", null);
      expected.put("next_retry_at", null);
      assertEquals(orderNo, expected.get("order_no"));
      assertEquals(7L, expected.get("user_id"));
      assertEquals(key, expected.get("idempotency_key"));
      assertEquals(
          JSON.readTree("[{\"skuId\":7,\"quantity\":1},{\"skuId\":8,\"quantity\":2}]"),
          JSON.readTree((String) expected.get("items_snapshot")));
      assertEquals(MILLIS.format(CREATED_AT), expected.get("created_at"));
      if (status.equals("INIT")) {
        expected.put("updated_at", MILLIS.format(CREATED_AT));
      } else {
        List<Map<String, Object>> writes =
            statusWrites.stream().filter(write -> status.equals(write.get("status"))).toList();
        assertEquals(1, writes.size(), status);
        Map<String, Object> write = writes.get(0);
        assertEquals(1, ((Number) write.get("changed")).intValue());
        LocalDateTime input = LocalDateTime.parse((String) write.get("inputTime"));
        LocalDateTime actual = LocalDateTime.parse((String) row.get("updated_at"));
        LocalDateTime floor = input.truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime next = floor.plusNanos(1_000_000L);
        assertTrue(actual.equals(floor) || actual.equals(next), "实际组件只能为原绑定的毫秒截断值或下一毫秒");
        assertFalse(input.isBefore(LocalDateTime.parse((String) write.get("startedAt"))));
        assertFalse(input.isAfter(LocalDateTime.parse((String) write.get("returnedAt"))));
        Object prior = write.putIfAbsent("actualStoredAt", row.get("updated_at"));
        if (prior != null) assertEquals(prior, row.get("updated_at"));
        write.put("inputMillisFloor", MILLIS.format(floor));
        write.put("inputNextMillis", MILLIS.format(next));
        expected.put("updated_at", row.get("updated_at"));
      }
      assertFullRow(expected, row);
      if (terminal) {
        if (intentGolden == null) intentGolden = expected;
        else assertFullRow(intentGolden, row);
      }
    }

    @SuppressWarnings("unchecked")
    private void verifyGroupRows(Map<String, Object> view) throws Exception {
      verifyLocalRows(view, true);
    }

    @SuppressWarnings("unchecked")
    private void verifyLocalRows(Map<String, Object> view, boolean includeOutbox) throws Exception {
      assertFullRow(orderGolden(), ((List<Map<String, Object>>) view.get("main")).get(0));
      List<Map<String, Object>> expectedItems = itemGoldens();
      List<Map<String, Object>> actualItems = (List<Map<String, Object>>) view.get("items");
      assertEquals(expectedItems.size(), actualItems.size());
      for (int index = 0; index < expectedItems.size(); index++)
        assertFullRow(expectedItems.get(index), actualItems.get(index));
      assertFullRow(idemGolden(), ((List<Map<String, Object>>) view.get("idempotency")).get(0));
      if (includeOutbox)
        assertFullRow(outboxGolden(), ((List<Map<String, Object>>) view.get("outbox")).get(0));
    }

    private void verifyResponse(OrderVO response) throws Exception {
      assertNotNull(response);
      assertEquals(orderNo, response.orderNo);
      assertEquals(7L, response.userId);
      assertEquals("PENDING_PAYMENT", response.status);
      assertEquals(new BigDecimal("16.50"), response.totalAmount);
      assertEquals(new BigDecimal("16.50"), response.payAmount);
      assertEquals(
          JSON.readTree(JSON.writeValueAsString(ADDRESS)), JSON.readTree(response.addressSnapshot));
      assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(response.createdAt).getOffset());
      assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(response.expireAt).getOffset());
      assertEquals(CREATED_AT, OffsetDateTime.parse(response.createdAt).toLocalDateTime());
      assertEquals(EXPIRE_AT, OffsetDateTime.parse(response.expireAt).toLocalDateTime());
      List<Map<String, Object>> expected = itemGoldens();
      assertEquals(2, response.items.size());
      for (int index = 0; index < expected.size(); index++) {
        Map<String, Object> row = expected.get(index);
        OrderItemVO item = response.items.get(index);
        assertEquals(row.get("product_id"), item.productId());
        assertEquals(row.get("sku_id"), item.skuId());
        assertEquals(row.get("product_name_snapshot"), item.productNameSnapshot());
        assertEquals(
            JSON.readTree((String) row.get("sku_snapshot")), JSON.valueToTree(item.skuSnapshot()));
        assertEquals(row.get("unit_price"), item.unitPrice().toPlainString());
        assertEquals(row.get("quantity"), (long) item.quantity());
        assertEquals(row.get("line_amount"), item.lineAmount().toPlainString());
      }
    }

    /** 用包装器本地连接序号区分外层与REQUIRES_NEW，非服务端连接ID；主/明细只能落原202612月。 */
    @SuppressWarnings("unchecked")
    private void verifyExecution() throws Exception {
      List<Map<String, Object>> events = base.executions;
      assertFalse(events.isEmpty());
      Map<String, Object> outer =
          events.stream()
              .filter(
                  event ->
                      normalized((String) event.get("sql"))
                          .contains("from order_idempotency where"))
              .findFirst()
              .orElseThrow();
      Object outerId = outer.get("connectionId");
      for (Map<String, Object> event : events) {
        String text = normalized((String) event.get("sql"));
        Map<Integer, Object> parameters = (Map<Integer, Object>) event.get("parameters");
        if (text.startsWith("insert into mall_order")
            || text.startsWith("insert into order_idempotency")
            || text.startsWith("insert into order_outbox"))
          assertEquals(outerId, event.get("connectionId"));
        if (text.startsWith("insert into mall_order")) {
          assertTrue(text.matches("insert into mall_order(?:_item)?_202612\\s*\\(.*"));
        }
        if (text.startsWith("insert into order_reservation_intent"))
          assertNotEquals(outerId, event.get("connectionId"));
        if (text.startsWith("update order_reservation_intent")) {
          if ("COMMITTED".equals(parameters.get(1)))
            assertEquals(outerId, event.get("connectionId"));
          else assertNotEquals(outerId, event.get("connectionId"));
        }
      }
      List<String> actualTargets =
          events.stream()
              .map(event -> normalized((String) event.get("sql")))
              .filter(text -> text.startsWith("insert into mall_order"))
              .toList();
      assertEquals(3, actualTargets.size());
      assertTrue(
          actualTargets.stream()
              .allMatch(
                  text ->
                      !text.contains("_202611")
                          && !text.contains("_202701")
                          && !text.contains("_202702")));
      if (logical) {
        assertEquals(
            failure ? 3 : 5,
            logicalInputs.stream().map(event -> event.get("method")).distinct().count());
        assertTrue(
            logicalInputs.stream()
                .anyMatch(
                    event -> ((String) event.get("sql")).startsWith("INSERT INTO mall_order(")));
        assertTrue(
            logicalInputs.stream()
                .anyMatch(
                    event ->
                        ((String) event.get("sql")).startsWith("INSERT INTO mall_order_item(")));
      } else assertEquals(List.of(), logicalInputs);
      assertFullSubset(orderGolden(), oneInput("order"));
      for (Map<String, Object> item : itemGoldens()) {
        Map<String, Object> captured =
            inputs.stream()
                .filter(event -> event.get("kind").equals("item"))
                .map(event -> (Map<String, Object>) event.get("tuple"))
                .filter(row -> row.get("id").equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertFullRow(item, captured);
      }
      assertFullRow(idemGolden(), oneInput("idempotency"));
      assertFullRow(outboxGolden(), oneInput("outbox"));
    }

    private void verifyObjects(Map<String, Object> inventory, boolean withTrigger) {
      assertEquals(expectedTables(), ((Map<?, ?>) inventory.get("tables")).keySet());
      assertTrue(
          ((Map<?, ?>) inventory.get("tables")).values().stream().allMatch("BASE TABLE"::equals));
      assertEquals(List.of(), inventory.get("views"));
      assertEquals(List.of(), inventory.get("events"));
      assertEquals(List.of(), inventory.get("routines"));
      if (withTrigger) {
        assertEquals(List.of(TRIGGER), inventory.get("triggers"));
        verifyDeclaredTrigger(triggerPlan);
        assertEquals(Map.of(TRIGGER, triggerPlan), inventory.get("triggerDefinitions"));
      } else {
        assertEquals(List.of(), inventory.get("triggers"));
        assertEquals(Map.of(), inventory.get("triggerDefinitions"));
      }
    }

    @SuppressWarnings("unchecked")
    private void verifyDeclaredTrigger(Map<String, Object> plan) {
      assertNotNull(plan);
      Map<String, Object> metadata = (Map<String, Object>) plan.get("metadata");
      assertEquals(database, metadata.get("trigger_schema"));
      assertEquals(TRIGGER, metadata.get("trigger_name"));
      assertEquals(database, metadata.get("event_object_schema"));
      assertEquals("order_outbox", metadata.get("event_object_table"));
      assertEquals("BEFORE", metadata.get("action_timing"));
      assertEquals("INSERT", metadata.get("event_manipulation"));
      assertEquals(
          TRIGGER_ACTION.replaceAll("\\s+", ""),
          ((String) metadata.get("action_statement")).replaceAll("\\s+", ""));
      Map<String, Object> show = (Map<String, Object>) plan.get("showCreate");
      String definition = (String) show.get("sql original statement");
      assertNotNull(definition);
      assertTrue(definition.contains(FAULT));
      assertEquals(sha(definition.getBytes(StandardCharsets.UTF_8)), plan.get("ddlSha256"));
    }

    void closeShadow() throws Exception {
      if (shadow != null) {
        shadow.close();
        shadowClosed = true;
      }
    }
  }

  /** 原Mapper SQL/参数透传观察；不伪造INSERT、返回行数或异常。 */
  private static final class ObservingJdbcTemplate extends JdbcTemplate {
    /** 所属原图与审计收集器。 */
    private final Fixture fixture;

    ObservingJdbcTemplate(Fixture fixture, DataSource dataSource) {
      super(dataSource);
      this.fixture = fixture;
      setQueryTimeout(5);
    }

    /** 保留原参数与调用，成功后记录真实影响行和状态时间窗。 */
    @Override
    public int update(String text, Object... arguments) {
      // 1. 观察原字段绑定，不从数据库终态反推生成ID。
      String normalized = normalized(text);
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("sql", text);
      event.put(
          "parameters",
          Arrays.stream(arguments).map(OrderCreationShardingShadowMySqlIT::binding).toList());
      event.put("startedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
      String kind = "other";
      List<String> columns = List.of();
      if (normalized.startsWith("insert into order_reservation_intent(")) {
        kind = "intent";
        columns =
            List.of(
                "id",
                "order_no",
                "user_id",
                "idempotency_key",
                "items_snapshot",
                "status",
                "last_error",
                "next_retry_at",
                "created_at",
                "updated_at");
      } else if (normalized.startsWith("insert into mall_order_item")) {
        kind = "item";
        columns =
            List.of(
                "id",
                "order_id",
                "order_no",
                "product_id",
                "sku_id",
                "product_name_snapshot",
                "sku_snapshot",
                "unit_price",
                "quantity",
                "line_amount",
                "created_at");
      } else if (normalized.startsWith("insert into mall_order")) {
        kind = "order";
        columns =
            List.of(
                "id",
                "order_no",
                "user_id",
                "status",
                "total_amount",
                "pay_amount",
                "address_snapshot",
                "expire_at",
                "created_at",
                "updated_at");
      } else if (normalized.startsWith("insert into order_idempotency(")) {
        kind = "idempotency";
        columns = List.of("id", "user_id", "idempotency_key", "order_no", "created_at");
      } else if (normalized.startsWith("insert into order_outbox(")) {
        kind = "outbox";
        columns =
            List.of(
                "id",
                "event_id",
                "event_type",
                "order_no",
                "payload",
                "status",
                "attempts",
                "next_retry_at",
                "last_error",
                "published_at",
                "created_at",
                "updated_at");
      }
      event.put("kind", kind);
      if (!columns.isEmpty()) {
        Map<String, Object> tuple = tuple(columns, arguments);
        event.put("columns", columns);
        event.put("tuple", tuple);
        if (kind.equals("intent")) {
          assertNull(fixture.intentInput);
          fixture.intentInput = tuple;
          fixture.orderNo = (String) tuple.get("order_no");
          assertTrue(fixture.orderNo.matches("202612[0-9a-f]{32}"));
        }
      } else event.put("tuple", Map.of());
      fixture.inputs.add(event);
      Map<String, Object> status = null;
      if (normalized.startsWith("update order_reservation_intent set status=?")) {
        status = new LinkedHashMap<>();
        status.put("status", arguments[0]);
        status.put("inputTime", binding(arguments[1]));
        assertEquals(fixture.orderNo, arguments[2]);
      } else if (normalized.startsWith("update order_reservation_intent set status='released'")) {
        status = new LinkedHashMap<>();
        status.put("status", "RELEASED");
        status.put("inputTime", binding(arguments[0]));
        assertEquals(fixture.orderNo, arguments[1]);
      }
      if (status != null) {
        status.put("startedAt", fixture.createCallStarted.toString());
        status.put("startedAtScope", "original Service.create proxy invocation");
        status.put("jdbcStartedAt", event.get("startedAt"));
        fixture.statusWrites.add(status);
      }
      // 2. 必须实际调用原JdbcTemplate；触发器SQLException沿原链传播。
      int changed = super.update(text, arguments);
      // 3. 只在真实返回后记录，不把一次尝试冒称成功。
      event.put("changed", changed);
      event.put("returnedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
      if (status != null) {
        status.put("changed", changed);
        status.put("returnedAt", event.get("returnedAt"));
      }
      return changed;
    }
  }

  /** 原markCommitted真实UPDATE之后的唯一提交前观察hook。 */
  private static final class IntentObserver extends OrderReservationIntentMapper {
    /** 同一原图的观察器。 */
    private final Fixture fixture;

    IntentObserver(Fixture fixture, JdbcTemplate jdbc) {
      super(jdbc);
      this.fixture = fixture;
    }

    /** 透传原UPDATE及返回，返回1后才注册同步观察。 */
    @Override
    public int markCommitted(String orderNo, LocalDateTime updatedAt) {
      // 1. 原Mapper执行，非mock返回1。
      int changed = super.markCommitted(orderNo, updatedAt);
      // 2. 真实UPDATE返回后观察同一事务并注册同步。
      fixture.committed(orderNo, changed);
      // 3. 原影响行数保持不变。
      return changed;
    }
  }

  /** 原Outbox.insert前观察，失败必须来自owned MySQL触发器。 */
  private static final class OutboxObserver extends OrderOutboxMapper {
    /** 当前唯一fixture。 */
    private final Fixture fixture;

    OutboxObserver(Fixture fixture, JdbcTemplate jdbc) {
      super(jdbc);
      this.fixture = fixture;
    }

    /** 观察后继续真实insert，原DataAccessException同对象交还Service。 */
    @Override
    public int insert(OrderOutboxPO outbox) {
      // 1. 仅失败角色在原insert前核本地部分行与独立RESERVED。
      if (fixture.failure)
        try {
          fixture.observe("before-real-outbox-insert", 1, 2, 1, 0, "RESERVED", false);
        } catch (Exception observationFailure) {
          throw new IllegalStateException("CM17EH_OUTBOX_OBSERVATION_FAILED", observationFailure);
        }
      // 2. 调用原SQL，真实触发器才是预期故障来源。
      try {
        return super.insert(outbox);
      }
      // 3. 保留同一个原异常，不能包装成预期成功。
      catch (RuntimeException original) {
        fixture.persistenceFailure = original;
        throw original;
      }
    }
  }

  /** 唯一5入口逻辑候选；普通台账全部继承原Mapper，不增加default DS。 */
  private static final class LogicalOrderSqlMapper extends OrderSqlMapper {
    /** 所属fixture与真实路由输入记录。 */
    private final Fixture fixture;

    /** 原图相同DataSource/TM的JDBC入口。 */
    private final JdbcTemplate jdbc;

    LogicalOrderSqlMapper(Fixture fixture, JdbcTemplate jdbc) {
      super(jdbc);
      this.fixture = fixture;
      this.jdbc = jdbc;
    }

    /** 只把原十字段INSERT的目标改逻辑主表，保原PO/绑定顺序。 */
    @Override
    public int insertOrder(String table, OrderPO order) {
      // 1. PO创建组件、原号和请求物理月必须一致。
      requireMonth(table, "mall_order", order.orderNo, order.createdAt);
      // 2. 原字段和参数顺序不变，真实4.1.1依据created_at路由。
      String sql =
          "INSERT INTO"
              + " mall_order(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?, ?,?,?)";
      Object[] values = {
        order.id,
        order.orderNo,
        order.userId,
        order.status,
        order.totalAmount,
        order.payAmount,
        order.addressSnapshot,
        order.expireAt,
        order.createdAt,
        order.updatedAt
      };
      record(
          "insertOrder",
          table,
          sql,
          List.of(
              "id",
              "order_no",
              "user_id",
              "status",
              "total_amount",
              "pay_amount",
              "address_snapshot",
              "expire_at",
              "created_at",
              "updated_at"),
          values);
      // 3. 真实执行并保原影响行数。
      return jdbc.update(sql, values);
    }

    /** 原按号主键查询加原号推导的半开原月范围。 */
    @Override
    public Long findOrderId(String table, String orderNo) {
      // 1. 原号只确定原月，不猜当前时钟。
      requireMonth(table, "mall_order", orderNo, null);
      Object[] values = monthArguments(orderNo);
      // 2. 逻辑SELECT仍按原号和原月份有界。
      String sql = "SELECT id FROM mall_order WHERE order_no=? AND created_at>=? AND created_at<?";
      record("findOrderId", table, sql, List.of("order_no", "monthStart", "nextMonth"), values);
      // 3. 原Long结果沿原接口返回，不手造主键。
      return jdbc.queryForObject(sql, Long.class, values);
    }

    /** 原明细十一字段顺序和生成ID保持。 */
    @Override
    public int insertOrderItem(String table, OrderItemPO item) {
      // 1. 明细原号、created_at和主表月共同约束路由。
      requireMonth(table, "mall_order_item", item.orderNo, item.createdAt);
      // 2. 保原PO全部十一列和绑定。
      String sql =
          "INSERT INTO"
              + " mall_order_item(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?)";
      Object[] values = {
        item.id,
        item.orderId,
        item.orderNo,
        item.productId,
        item.skuId,
        item.productNameSnapshot,
        item.skuSnapshot,
        item.unitPrice,
        item.quantity,
        item.lineAmount,
        item.createdAt
      };
      record(
          "insertOrderItem",
          table,
          sql,
          List.of(
              "id",
              "order_id",
              "order_no",
              "product_id",
              "sku_id",
              "product_name_snapshot",
              "sku_snapshot",
              "unit_price",
              "quantity",
              "line_amount",
              "created_at"),
          values);
      // 3. 真实原月分片执行，不直接指定物理表冒称逻辑写。
      return jdbc.update(sql, values);
    }

    /** 原八字段详情主行，以原号和原月有界读取。 */
    @Override
    public List<Map<String, Object>> findOrder(String table, String orderNo) {
      // 1. 原参数身份与物理候选月不变。
      requireMonth(table, "mall_order", orderNo, null);
      Object[] values = monthArguments(orderNo);
      // 2. 保原八字段，逻辑路附原月谓词。
      String sql =
          "SELECT"
              + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
              + " FROM mall_order WHERE order_no=? AND created_at>=? AND created_at<?";
      record("findOrder", table, sql, List.of("order_no", "monthStart", "nextMonth"), values);
      // 3. 原Service继续做原号/所有权判断。
      return jdbc.queryForList(sql, values);
    }

    /** 原明细七字段及ORDER BY id，按号与原月共同有界。 */
    @Override
    public List<Map<String, Object>> findOrderItems(String table, String orderNo) {
      // 1. 原明细表月与原号保持一致。
      requireMonth(table, "mall_order_item", orderNo, null);
      Object[] values = monthArguments(orderNo);
      // 2. 保原七字段与原主键顺序。
      String sql =
          "SELECT"
              + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
              + " FROM mall_order_item WHERE order_no=? AND created_at>=? AND created_at<? ORDER BY"
              + " id";
      record("findOrderItems", table, sql, List.of("order_no", "monthStart", "nextMonth"), values);
      // 3. 真正交给4.1.1读取，不对实际结果作Java重排。
      return jdbc.queryForList(sql, values);
    }

    private void record(
        String method, String table, String sql, List<String> columns, Object[] values) {
      fixture.logicalInputs.add(
          Map.of(
              "method",
              method,
              "requestedPhysicalTable",
              table,
              "sql",
              sql,
              "bindingFields",
              columns,
              "bindings",
              tuple(columns, values)));
    }

    private void requireMonth(String table, String prefix, String orderNo, LocalDateTime created) {
      Object[] range = monthArguments(orderNo);
      assertEquals(prefix + "_" + orderNo.substring(0, 6), table);
      if (created != null) {
        assertEquals(CREATED_AT, created);
        assertFalse(created.isBefore((LocalDateTime) range[1]));
        assertTrue(created.isBefore((LocalDateTime) range[2]));
      }
    }
  }

  /** 只透传JDBC并核catalog/连接归属；不通过会打印完整URL的Spring调试入口。 */
  private static final class GuardedDataSource extends AbstractDataSource {
    /** 唯一受界定且不输出的原连接URL。 */
    private final String url;

    /** 每条原生连接必须确认的精确catalog。 */
    private final String catalog;

    /** 成功取得但尚未成功关闭的本次连接。 */
    private final AtomicInteger openConnections = new AtomicInteger();

    /** 本DataSource每次取得原生连接后分配的本地序号，不是MySQL服务端连接ID。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 原生close异常或身份异常会永久阻止清理。 */
    private boolean uncertain;

    /** 仅原create调用窗口记录下发SQL。 */
    private boolean recording;

    /** 原生Statement真实execute时的SQL及参数，不是Mapper意图替代。 */
    private final List<Map<String, Object>> executions = new ArrayList<>();

    GuardedDataSource(String url, String catalog) {
      this.url = url;
      this.catalog = catalog;
    }

    /** 每次真实连接后核当前catalog，任何未知保持失败。 */
    @Override
    public Connection getConnection() throws SQLException {
      // 1. 使用原固定开发认证，URL和认证均不进入日志。
      Connection raw = DriverManager.getConnection(url, "root", "root");
      try (Statement check = raw.createStatement()) {
        check.setQueryTimeout(5);
        try (ResultSet row = check.executeQuery("SELECT DATABASE()")) {
          if (!row.next() || !catalog.equals(row.getString(1)))
            throw new SQLException("CM17EH_CATALOG_MISMATCH");
        }
      } catch (SQLException | RuntimeException | Error failure) {
        uncertain = true;
        try {
          raw.close();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
      // 2. 仅确认归属后计数，每次close必须真正成功才扣减。
      int connectionId = sequence.incrementAndGet();
      openConnections.incrementAndGet();
      boolean[] closed = {false};
      Connection[] wrapped = new Connection[1];
      wrapped[0] =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                      if (!closed[0]) {
                        try {
                          invoke(method, raw, args);
                          closed[0] = true;
                          openConnections.decrementAndGet();
                        } catch (Throwable failure) {
                          uncertain = true;
                          throw failure;
                        }
                      }
                      return null;
                    }
                    if (method.getName().equals("setCatalog") && !catalog.equals(args[0])) {
                      uncertain = true;
                      throw new SQLException("CM17EH_CATALOG_CHANGE_REJECTED");
                    }
                    Object result = invoke(method, raw, args);
                    if (result instanceof Statement statement) {
                      statement.setQueryTimeout(5);
                      String prepared =
                          args != null && args.length > 0 && args[0] instanceof String
                              ? (String) args[0]
                              : null;
                      return wrapStatement(statement, prepared, wrapped[0], connectionId);
                    }
                    return result;
                  });
      // 3. 后续事务与Statement均透传至同一个真实连接。
      return wrapped[0];
    }

    /** 认证不可在fixture内分叉。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      // 1. 只接受项目既定认证。
      if (!"root".equals(username) || !"root".equals(password))
        throw new SQLException("CM17EH_CREDENTIAL_MISMATCH");
      // 2. 同样走精确catalog/归属门。
      Connection connection = getConnection();
      // 3. 不新建第二条隐藏连接。
      return connection;
    }

    private Statement wrapStatement(
        Statement raw, String prepared, Connection connection, int connectionId) {
      Map<Integer, Object> parameters = new TreeMap<>();
      Class<?> statementType =
          raw instanceof CallableStatement
              ? CallableStatement.class
              : raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {statementType},
              (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getConnection")) return connection;
                Object result;
                if (name.startsWith("execute")) {
                  String text =
                      prepared != null
                          ? prepared
                          : args != null && args.length > 0 && args[0] instanceof String
                              ? (String) args[0]
                              : null;
                  if (recording && text != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("sql", text);
                    event.put("parameters", new TreeMap<>(parameters));
                    event.put("connectionId", connectionId);
                    event.put(
                        "transactionActive",
                        TransactionSynchronizationManager.isActualTransactionActive());
                    event.put("authUser", AuthContext.userId());
                    event.put("xidAbsent", RootContext.getXID() == null);
                    executions.add(event);
                  }
                }
                result = invoke(method, raw, args);
                if (name.equals("clearParameters")) parameters.clear();
                else if (name.startsWith("set")
                    && args != null
                    && args.length >= 2
                    && args[0] instanceof Integer index) {
                  parameters.put(index, name.equals("setNull") ? null : binding(args[1]));
                }
                return result;
              });
    }

    void assertClosedConnections() {
      assertFalse(uncertain, "原生连接身份/关闭结果未知必须保留");
      assertEquals(0, openConnections.get(), "仍有本次连接未成功关闭");
    }
  }

  /** 完整表事实，保留全字段行而非仅checksum。 */
  private record TableFact(
      long count, String checksum, String definition, List<Map<String, Object>> rows) {}

  /** 精确库事实，包含完整对象inventory。 */
  private record Facts(
      String database, Map<String, TableFact> tables, Map<String, Object> objects) {}

  /** 收尾每一步独立执行，不让审计IO跳过后续资源检查。 */
  @FunctionalInterface
  private interface CheckedStep {
    void run() throws Exception;
  }

  private static JdbcTemplate jdbc(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout(5);
    return template;
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(T target, DataSourceTransactionManager manager) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) factory.getProxy();
  }

  private static CreateOrderDTO request() {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    OrderItemDTO first = new OrderItemDTO();
    first.skuId = 7L;
    first.quantity = 1;
    OrderItemDTO second = new OrderItemDTO();
    second.skuId = 8L;
    second.quantity = 2;
    request.items = List.of(first, second);
    return request;
  }

  private static void assertClientContext() {
    assertEquals(7L, AuthContext.userId());
    assertEquals("USER", AuthContext.role());
    assertNull(RootContext.getXID());
  }

  private static void assertClean() {
    assertNull(AuthContext.userId());
    assertNull(AuthContext.role());
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  /** 只做普通行SELECT，提交前不会CHECKSUM、加锁或DDL。 */
  private static Map<String, Object> window(Connection connection) throws SQLException {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("main", readRows(connection, "mall_order_202612"));
    view.put("items", readRows(connection, "mall_order_item_202612"));
    view.put("idempotency", readRows(connection, "order_idempotency"));
    view.put("outbox", readRows(connection, "order_outbox"));
    view.put("intent", readRows(connection, "order_reservation_intent"));
    return view;
  }

  private static List<Map<String, Object>> readRows(Connection connection, String table)
      throws SQLException {
    assertTrue(
        Set.of(
                "mall_order_202612",
                "mall_order_item_202612",
                "order_idempotency",
                "order_outbox",
                "order_reservation_intent")
            .contains(table));
    List<Map<String, Object>> result = new ArrayList<>();
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
        ResultSetMetaData metadata = rows.getMetaData();
        while (rows.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int index = 1; index <= metadata.getColumnCount(); index++)
            row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), rows.getObject(index));
          result.add(canonicalRow(row));
        }
      }
    }
    return result;
  }

  private static void verifyCounts(
      Map<String, Object> view, int main, int items, int idem, int outbox) {
    assertEquals(main, ((List<?>) view.get("main")).size());
    assertEquals(items, ((List<?>) view.get("items")).size());
    assertEquals(idem, ((List<?>) view.get("idempotency")).size());
    assertEquals(outbox, ((List<?>) view.get("outbox")).size());
  }

  @SuppressWarnings("unchecked")
  private static void verifyWindow(
      Map<String, Object> view, int main, int items, int idem, int outbox, String intent) {
    verifyCounts(view, main, items, idem, outbox);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) view.get("intent");
    assertEquals(1, rows.size());
    assertEquals(intent, rows.get(0).get("status"));
  }

  /** 原参数保留纳秒；实际DATETIME行独立格式化至其已存储的毫秒组件。 */
  private static Object binding(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime local) return local.toString();
    if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().toString();
    if (value instanceof BigDecimal money) return money.toPlainString();
    if (value instanceof Number number) return number.longValue();
    if (value instanceof Boolean) return value;
    return value.toString();
  }

  private static Map<String, Object> tuple(List<String> columns, Object[] values) {
    assertEquals(columns.size(), values.length);
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index++)
      row.put(columns.get(index), binding(values[index]));
    return row;
  }

  private static Map<String, Object> canonicalRow(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : row.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Timestamp stamp) value = MILLIS.format(stamp.toLocalDateTime());
      else if (value instanceof LocalDateTime local) value = MILLIS.format(local);
      else if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
      else if (value instanceof BigDecimal money) value = money.toPlainString();
      else if (value instanceof Number number) value = number.longValue();
      else if (value instanceof byte[] bytes) value = HexFormat.of().formatHex(bytes);
      result.put(entry.getKey().toLowerCase(Locale.ROOT), value);
    }
    return result;
  }

  private static void assertFullRow(Map<String, Object> expected, Map<String, Object> actual)
      throws Exception {
    assertEquals(expected.keySet(), actual.keySet());
    for (String column : expected.keySet())
      assertColumn(column, expected.get(column), actual.get(column));
  }

  private static void assertFullSubset(Map<String, Object> expected, Map<String, Object> insert)
      throws Exception {
    Set<String> expectedColumns = new TreeSet<>(expected.keySet());
    expectedColumns.remove("paid_at");
    expectedColumns.remove("cancelled_at");
    assertEquals(expectedColumns, insert.keySet());
    for (String column : insert.keySet())
      assertColumn(column, expected.get(column), insert.get(column));
  }

  private static void assertColumn(String column, Object expected, Object actual) throws Exception {
    if (Set.of("address_snapshot", "sku_snapshot", "items_snapshot", "payload").contains(column)
        && expected != null
        && actual != null)
      assertEquals(JSON.readTree((String) expected), JSON.readTree((String) actual), column);
    else assertEquals(expected, actual, column);
  }

  private static String normalized(String sql) {
    return sql.replace("`", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static Object[] monthArguments(String orderNo) {
    assertNotNull(orderNo);
    assertTrue(orderNo.matches("[0-9]{6}[A-Za-z0-9-]+"));
    YearMonth month =
        YearMonth.parse(orderNo.substring(0, 6), DateTimeFormatter.ofPattern("uuuuMM"));
    return new Object[] {
      orderNo, month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()
    };
  }

  private static List<String> cloneSources() {
    List<String> result = new ArrayList<>();
    for (String logical : LOGICAL) result.add(logical + "_template");
    result.addAll(LEDGERS);
    return result;
  }

  private static Set<String> expectedTables() {
    Set<String> result = new TreeSet<>(cloneSources());
    for (String logical : LOGICAL) for (String month : MONTHS) result.add(logical + "_" + month);
    assertEquals(19, result.size());
    return result;
  }

  private static String identifier(String name) {
    assertTrue(name.matches("[a-z0-9_]+"));
    return name;
  }

  private static Map<String, String> tableTypes(JdbcTemplate jdbc) {
    Map<String, String> result = new TreeMap<>();
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE"
                + " TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME"))
      result.put((String) row.get("TABLE_NAME"), (String) row.get("TABLE_TYPE"));
    return result;
  }

  private static String definition(JdbcTemplate jdbc, String table) {
    String ddl =
        (String) jdbc.queryForMap("SHOW CREATE TABLE " + identifier(table)).get("Create Table");
    assertNotNull(ddl);
    return sha(
        ddl.replaceFirst("CREATE TABLE `[^`]+`", "CREATE TABLE `MODEL`")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String checksum(JdbcTemplate jdbc, String table) {
    Object checksum = jdbc.queryForMap("CHECKSUM TABLE " + identifier(table)).get("Checksum");
    assertNotNull(checksum);
    return checksum.toString();
  }

  private static Map<String, Object> objects(JdbcTemplate jdbc) {
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, String> tables = tableTypes(jdbc);
    result.put("tables", tables);
    result.put(
        "views",
        tables.entrySet().stream()
            .filter(entry -> entry.getValue().equals("VIEW"))
            .map(Map.Entry::getKey)
            .sorted()
            .toList());
    result.put(
        "events",
        jdbc.queryForList(
            "SELECT EVENT_NAME FROM information_schema.events WHERE EVENT_SCHEMA=DATABASE() ORDER"
                + " BY EVENT_NAME",
            String.class));
    result.put(
        "routines",
        jdbc.queryForList(
            "SELECT ROUTINE_NAME FROM information_schema.routines WHERE ROUTINE_SCHEMA=DATABASE()"
                + " ORDER BY ROUTINE_NAME,ROUTINE_TYPE",
            String.class));
    List<String> triggers =
        jdbc.queryForList(
            "SELECT TRIGGER_NAME FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE()"
                + " ORDER BY TRIGGER_NAME",
            String.class);
    result.put("triggers", triggers);
    Map<String, Object> definitions = new TreeMap<>();
    for (String name : triggers) definitions.put(name, trigger(jdbc, name));
    result.put("triggerDefinitions", definitions);
    return result;
  }

  private static Map<String, Object> trigger(JdbcTemplate jdbc) {
    return trigger(jdbc, TRIGGER);
  }

  private static Map<String, Object> trigger(JdbcTemplate jdbc, String name) {
    Map<String, Object> show =
        canonicalRow(jdbc.queryForMap("SHOW CREATE TRIGGER " + identifier(name)));
    Map<String, Object> metadata =
        canonicalRow(
            jdbc.queryForMap(
                "SELECT * FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE() AND"
                    + " TRIGGER_NAME=?",
                name));
    String ddl = (String) show.get("sql original statement");
    assertNotNull(ddl);
    return Map.of(
        "showCreate",
        show,
        "metadata",
        metadata,
        "ddlSha256",
        sha(ddl.getBytes(StandardCharsets.UTF_8)));
  }

  private static Map<String, Object> sourceFingerprint() {
    assertEquals("cloudmall_order", source.queryForObject("SELECT DATABASE()", String.class));
    Map<String, Object> tables = new TreeMap<>();
    for (var entry : tableTypes(source).entrySet()) {
      assertEquals("BASE TABLE", entry.getValue());
      String table = identifier(entry.getKey());
      tables.put(
          table,
          Map.of(
              "count",
              source.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
              "checksum",
              checksum(source, table),
              "definition",
              definition(source, table)));
    }
    return Map.of("database", "cloudmall_order", "tables", tables, "objects", objects(source));
  }

  private static String boundedUrl(String url) {
    String[] pieces = url.split("\\?", 2);
    List<String> parameters = new ArrayList<>();
    if (pieces.length == 2)
      for (String parameter : pieces[1].split("&")) {
        String key = parameter.split("=", 2)[0];
        if (!Set.of(
                "serverTimezone",
                "forceConnectionTimeZoneToSession",
                "connectTimeout",
                "socketTimeout")
            .contains(key)) parameters.add(parameter);
      }
    parameters.add("serverTimezone=Asia%2FShanghai");
    parameters.add("forceConnectionTimeZoneToSession=true");
    parameters.add("connectTimeout=3000");
    parameters.add("socketTimeout=5000");
    return pieces[0] + "?" + String.join("&", parameters);
  }

  private static boolean triggerCause(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure;
        current != null && seen.add(current);
        current = current.getCause())
      if (current instanceof SQLException sql) {
        Set<SQLException> nextSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException next = sql;
            next != null && nextSeen.add(next);
            next = next.getNextException())
          if ("45000".equals(next.getSQLState())
              && next.getErrorCode() == 1644
              && next.getMessage() != null
              && next.getMessage().contains(FAULT)) return true;
      }
    return false;
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("CM17EH_SHA_UNAVAILABLE", failure);
    }
  }

  private static Map<String, Object> safeError(Throwable failure) {
    String message = String.valueOf(failure.getMessage());
    return Map.of(
        "type",
        failure.getClass().getName(),
        "messageSha256",
        sha(message.getBytes(StandardCharsets.UTF_8)));
  }

  private static List<Map<String, Object>> safeErrors(List<Throwable> errors) {
    return errors.stream().map(OrderCreationShardingShadowMySqlIT::safeError).toList();
  }

  private static void write(Path path, Object value) throws Exception {
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
  }

  private static void attempt(List<Throwable> errors, CheckedStep step) {
    try {
      step.run();
    } catch (Exception | AssertionError failure) {
      errors.add(failure);
    }
  }

  private static void throwIfErrors(List<Throwable> errors) throws Exception {
    if (errors.isEmpty()) return;
    Exception failure = new Exception("CM17EH_RESOURCE_OR_FACT_GATE_FAILED");
    for (Throwable error : errors) failure.addSuppressed(error);
    throw failure;
  }
}
