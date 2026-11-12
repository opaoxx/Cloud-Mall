package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.OrderShardingRuleFactory;
import com.cloudmall.order.config.OrderTableInitializer;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.*;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.impl.OrderOutboxDispatcher;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.cloudmall.order.service.impl.OrderStockActionRecovery;
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
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 17EI固定整链；真实JDBC/4.1.1，Stock/Rabbit仅本地计数替身。 */
class OrderReplayRecoveryShardingShadowMySqlIT {
  /** 三类逻辑表；状态日志仍兼容空表。 */
  private static final List<String> LOGICAL =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** 精确四普通台账。 */
  private static final List<String> LEDGERS =
      List.of(
          "order_idempotency", "order_reservation_intent", "order_outbox", "order_stock_action");

  /** 单次真实上海时窗；恢复器不注入替代Clock。 */
  private static LocalDateTime anchor, stale;

  /** 由原rollingMonths实方法获得。 */
  private static List<String> MONTHS;

  /** 本轮真实月份。 */
  private static YearMonth currentMonth;

  /** 组件审计格式。 */
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

  /** 普通安全审计字段映射器。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 原商品与分位价格。 */
  private static final Map<Long, ProductClient.SkuView> PRODUCTS =
      Map.of(
          7L,
          new ProductClient.SkuView(
              7L, 70L, "First product", "SKU-7", new BigDecimal("10.00"), Map.of("size", "M")),
          8L,
          new ProductClient.SkuView(
              8L, 80L, "Second product", "SKU-8", new BigDecimal("3.25"), Map.of("size", "L")));

  /** 原地址快照。 */
  private static final Map<String, Object> ADDRESS = Map.of("id", 9L, "receiver", "Fixture");

  /** 完整原Cart证明，不重建授权。 */
  private static final Map<Long, CartCleanupSnapshotDTO> CART =
      Map.of(
          7L,
          new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":1,\"checked\":true}"),
          8L,
          new CartCleanupSnapshotDTO(8L, "{\"skuId\":8,\"quantity\":2,\"checked\":true}"));

  /** 原trace字段输入。 */
  private static final String TRACE = "cm17ei-original-trace";

  /** 各恢复库唯一声明trigger。 */
  private static final String TRIGGER = "cm17ei_completion_fail";

  /** 标准target内唯一原生目录。 */
  private static Path auditRoot;

  /** 源只读连接计数。 */
  private static GuardedDataSource sourceDataSource;

  /** 源事实与精确本轮CREATE/DROP入口。 */
  private static JdbcTemplate source;

  /** 不写入任何日志或审计的URL。 */
  private static String sourceUrl;

  /** 源起始完整结构/count/checksum指纹。 */
  private static Map<String, Object> sourceBefore;

  /** 七原结构包含索引的规范DDL hash。 */
  private static final Map<String, String> DEFINITIONS = new TreeMap<>();

  /** 仅实际CREATE成功的本类精确资源。 */
  private static final Set<String> CREATED = new TreeSet<>();

  /** DROP后复核不存在的资源。 */
  private static final Set<String> DELETED = new TreeSet<>();

  @BeforeAll
  static void establishSource() throws Exception {
    assertEquals(ZoneId.of("Asia/Shanghai"), ZoneId.systemDefault());
    assertClean();
    anchor = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    stale = anchor.minusMinutes(10);
    currentMonth = YearMonth.from(anchor);
    MONTHS =
        ReflectionTestUtils.invokeMethod(
            OrderTableInitializer.class, "rollingMonths", currentMonth);
    assertNotNull(MONTHS);
    assertEquals(4, MONTHS.size());
    assertTrue(MONTHS.contains(stale.format(DateTimeFormatter.ofPattern("uuuuMM"))));
    String run = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    auditRoot = Path.of("target", "cm17ei-replay-recovery", run).toAbsolutePath();
    Files.createDirectories(auditRoot.getParent());
    Files.createDirectory(auditRoot);
    write(
        auditRoot.resolve("class-start.json"),
        Map.of(
            "review",
            "17EI",
            "runId",
            run,
            "startedAt",
            Instant.now().toString(),
            "anchor",
            MILLIS.format(anchor),
            "stale",
            MILLIS.format(stale),
            "rollingMonths",
            MONTHS,
            "rollingMonthsFromOriginalMethod",
            true));
    System.out.println("CM17EI_REPLAY_RECOVERY runId=" + run + " startedAt=" + Instant.now());
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/?#]+/cloudmall_order(\\?[^#]*)?"))
      throw new IllegalStateException("CM17EI_SOURCE_REQUIRED");
    sourceDataSource = new GuardedDataSource(boundedUrl(sourceUrl), "cloudmall_order");
    source = jdbc(sourceDataSource);
    sourceBefore = sourceFingerprint();
    write(auditRoot.resolve("source-before.json"), sourceBefore);
    for (String table : cloneSources()) {
      assertEquals("BASE TABLE", tableTypes(source).get(table));
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
          Map<String, Object> after = sourceFingerprint();
          result.put("sourceAfter", after);
          assertEquals(sourceBefore, after);
          sourceDataSource.assertClosedConnections();
          assertClean();
          assertEquals(CREATED, DELETED);
        });
    result.put("preserveRequired", !CREATED.equals(DELETED));
    result.put("errors", safeErrors(errors));
    attempt(errors, () -> write(auditRoot.resolve("class-finish.json"), result));
    throwIfErrors(errors);
  }

  @Test
  void replaysOriginalOrderWithoutNewReservation() throws Exception {
    try (Run run = new Run("replaysOriginalOrderWithoutNewReservation", Role.REPLAY)) {
      run.compare();
    }
  }

  @Test
  void recoversReservationIntentsThroughOriginalWritePaths() throws Exception {
    try (Run run = new Run("recoversReservationIntentsThroughOriginalWritePaths", Role.INTENT)) {
      run.compare();
    }
  }

  @Test
  void dispatchesOutboxThroughOriginalCompletionAndRetryWrites() throws Exception {
    try (Run run =
        new Run("dispatchesOutboxThroughOriginalCompletionAndRetryWrites", Role.OUTBOX)) {
      run.compare();
    }
  }

  @Test
  void recoversStockActionsThroughOriginalLeaseAndOutcomeWrites() throws Exception {
    try (Run run =
        new Run("recoversStockActionsThroughOriginalLeaseAndOutcomeWrites", Role.ACTION)) {
      run.compare();
    }
  }

  /** 角色固定，不交叉放松事实。 */
  private enum Role {
    REPLAY,
    INTENT,
    OUTBOX,
    ACTION
  }

  /** 一方法两路独立资源；任一路失败都保留本方法资源。 */
  private static final class Run implements AutoCloseable {
    /** 唯一方法目录。 */
    private final Path path;

    /** 本方法固定角色。 */
    private final Role role;

    /** 包括setup失败者的真实列表。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 只有两路golden/比较都过后设置。 */
    private boolean passed;

    Run(String method, Role role) throws Exception {
      this.role = role;
      assertClean();
      path = auditRoot.resolve(method);
      Files.createDirectory(path);
      write(
          path.resolve("method-start.json"),
          Map.of("method", method, "role", role.name(), "startedAt", Instant.now().toString()));
    }

    void compare() throws Exception {
      Fixture physical = new Fixture(path.resolve("physical"), role, false);
      fixtures.add(physical);
      physical.prepare();
      Fixture logical = new Fixture(path.resolve("logical"), role, true);
      fixtures.add(logical);
      logical.prepare();
      Map<String, Object> left = physical.exercise();
      Map<String, Object> right = logical.exercise();
      assertEquals(left, right);
      write(
          path.resolve("normalized-comparison.json"),
          Map.of(
              "physical",
              left,
              "logical",
              right,
              "normalization",
              "each route verifies full independent golden first; original generated"
                  + " IDs/claimOwner/errors/times retained separately; fixed business outcomes"
                  + " only"));
      passed = true;
    }

    /** 逐资源独立收尾，保留原异常和suppressed。 */
    @Override
    public void close() throws Exception {
      // 1. 审计失败不能跳过任一路的close/实际facts观察。
      List<Throwable> errors = new ArrayList<>();
      for (Fixture fixture : fixtures) attempt(errors, fixture::closeShadow);
      for (Fixture fixture : fixtures) {
        attempt(
            errors,
            () ->
                write(
                    fixture.path.resolve("close-input-observation.json"),
                    Map.of(
                        "business",
                        fixture.calls,
                        "wire",
                        fixture.base == null ? List.of() : fixture.base.executions,
                        "clients",
                        fixture.clientCalls,
                        "phases",
                        fixture.phases,
                        "terminalVerified",
                        fixture.verified)));
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
              Facts actual = fixture.facts();
              write(fixture.path.resolve("before-delete.json"), actual);
              assertTrue(fixture.verified);
              fixture.verifyFinal(actual);
              assertEquals(fixture.finalFacts, actual);
            });
      }
      attempt(
          errors,
          () -> {
            assertTrue(passed);
            assertEquals(sourceBefore, sourceFingerprint());
            sourceDataSource.assertClosedConnections();
            assertClean();
          });
      // 2. 每次准确DROP前重新读catalog、完整facts、对象和本类created身份。
      if (errors.isEmpty())
        for (Fixture fixture : fixtures) {
          attempt(
              errors,
              () -> {
                assertTrue(CREATED.contains(fixture.database));
                assertTrue(fixture.created);
                assertTrue(!fixture.logical || fixture.shadowClosed);
                assertEquals(
                    fixture.database,
                    fixture.sql.queryForObject("SELECT DATABASE()", String.class));
                Facts actual = fixture.facts();
                fixture.verifyFinal(actual);
                assertEquals(fixture.finalFacts, actual);
                write(fixture.path.resolve("delete-gate.json"), actual);
                fixture.base.assertClosedConnections();
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
      // 3. 结果独立CREATE_NEW；不声称全部库内部线程join。
      for (Fixture fixture : fixtures)
        attempt(
            errors,
            () ->
                write(
                    fixture.path.resolve("cleanup.json"),
                    Map.of(
                        "database",
                        fixture.database,
                        "role",
                        role.name(),
                        "created",
                        fixture.created,
                        "deleted",
                        DELETED.contains(fixture.database),
                        "preserved",
                        fixture.created && !DELETED.contains(fixture.database),
                        "shadowCloseReturned",
                        fixture.shadowClosed,
                        "ownedWorkersCreated",
                        0,
                        "internalThreadJoinClaimed",
                        false,
                        "errors",
                        safeErrors(errors))));
      attempt(
          errors,
          () ->
              write(
                  path.resolve("method-finish.json"),
                  Map.of(
                      "passed",
                      passed,
                      "finishedAt",
                      Instant.now().toString(),
                      "errors",
                      safeErrors(errors))));
      throwIfErrors(errors);
    }
  }

  /** 每角色一路精确19表；所有台账仍走原Mapper。 */
  private static final class Fixture {
    /** 仅本类拥有的随机schema。 */
    private final String database = "cm17ei_" + UUID.randomUUID().toString().replace("-", "");

    /** 本路证据目录。 */
    private final Path path;

    /** 固定seed/状态角色。 */
    private final Role role;

    /** 真实4.1.1或物理基线。 */
    private final boolean logical;

    /** 实际物理连接/SQL观察。 */
    private GuardedDataSource base;

    /** 事实和setup-only JDBC。 */
    private JdbcTemplate sql;

    /** 唯一可选shadow资源。 */
    private ShardingDataSource shadow;

    /** 原Mapper共同数据源。 */
    private DataSource route;

    /** 原Mapper调用透传审计。 */
    private ObservingJdbc business;

    /** 一路唯一TM，恢复器不包新事务。 */
    private DataSourceTransactionManager manager;

    /** 主明细候选只六入口。 */
    private OrderSqlMapper orders;

    /** 原可靠性台账Mapper。 */
    private OrderReservationIntentMapper intents;

    /** 原可靠性台账Mapper。 */
    private OrderOutboxMapper outbox;

    /** 原可靠性台账Mapper。 */
    private OrderStockActionMapper actions;

    /** 原target保留原Clock注入点。 */
    private OrderTestService target;

    /** 原@Transactional真实代理。 */
    private OrderTestService service;

    /** 创建/关闭/所有断言的实际返回事实。 */
    private boolean created, shadowClosed, verified;

    /** 完整预期行；动态值来自原输入且独立验证业务语义。 */
    private final Map<String, List<Map<String, Object>>> expected = new TreeMap<>();

    /** 初始完整声明，不覆盖。 */
    private Map<String, List<Map<String, Object>>> seed;

    /** 唯一声明trigger动作。 */
    private String triggerAction;

    /** 创建后完整metadata/SHOW CREATE。 */
    private Map<String, Object> triggerPlan;

    /** 原调用参数/返回/安全错误链。 */
    private final List<Map<String, Object>> calls = new ArrayList<>();

    /** 六入口逻辑SQL与原字段绑定。 */
    private final List<Map<String, Object>> logicalInputs = new ArrayList<>();

    /** 原客户端位置计数与参数。 */
    private final List<Map<String, Object>> clientCalls = new ArrayList<>();

    /** 同步阶段窗口/事实。 */
    private final List<Map<String, Object>> phases = new ArrayList<>();

    /** 真实调用前时间下界，不替代任何生产now。 */
    private LocalDateTime operationStarted;

    /** 当前入口名字，仅审计。 */
    private String operation;

    /** 断言通过后的完整行/对象，删前再读必须一致。 */
    private Facts finalFacts;

    /** 本地计数Stock保原default key派生。 */
    private final StockClient stock = mock(StockClient.class, CALLS_REAL_METHODS);

    /** 本地商品替身。 */
    private final ProductClient product = mock(ProductClient.class);

    /** 本地用户替身。 */
    private final UserClient user = mock(UserClient.class);

    /** 本地Cart原快照替身。 */
    private final CartClient cart = mock(CartClient.class);

    /** 本地Rabbit，不创建连接工厂。 */
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);

    /** 同原号受控库存状态；不作为真实库存资金声明。 */
    private final Map<String, String> stockStates = new HashMap<>();

    /** 真实create的原订单号，只从原Intent INSERT捕获。 */
    private String createdOrderNo;

    /** 原afterCommit清理记录。 */
    private final List<Long> cleaned = new ArrayList<>();

    Fixture(Path path, Role role, boolean logical) {
      this.path = path;
      this.role = role;
      this.logical = logical;
    }

    void prepare() throws Exception {
      Files.createDirectory(path);
      assertClean();
      assertTrue(database.matches("cm17ei_[0-9a-f]{32}"));
      for (String table : expectedTables()) expected.put(table, new ArrayList<>());
      write(
          path.resolve("ownership-plan.json"),
          Map.of(
              "database",
              database,
              "role",
              role.name(),
              "logical",
              logical,
              "tables",
              expectedTables(),
              "rollingMonths",
              MONTHS,
              "seedOnly",
              true,
              "stale",
              MILLIS.format(stale),
              "trigger",
              role == Role.REPLAY ? "NONE" : TRIGGER));
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      source.execute(
          "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      created = true;
      CREATED.add(database);
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
      Facts empty = facts();
      verifyObjects(empty.objects, false);
      for (TableFact fact : empty.tables.values()) assertEquals(0, fact.count);
      write(path.resolve("empty-facts.json"), empty);
      seed();
      seed = deepRows(expected);
      write(path.resolve("declared-seed.json"), seed);
      verifyRows(facts());
      if (role != Role.REPLAY) {
        String table =
            role == Role.INTENT
                ? "order_reservation_intent"
                : role == Role.OUTBOX ? "order_outbox" : "order_stock_action";
        long id = role == Role.INTENT ? 104L : role == Role.OUTBOX ? 203L : 304L;
        String status =
            role == Role.INTENT ? "COMMITTED" : role == Role.OUTBOX ? "PUBLISHED" : "SUCCEEDED";
        triggerAction =
            "BEGIN IF NEW.id="
                + id
                + " AND BINARY NEW.status=BINARY '"
                + status
                + "' THEN SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644, MESSAGE_TEXT='"
                + marker()
                + "'; END IF; END";
        sql.execute(
            "CREATE TRIGGER "
                + TRIGGER
                + " BEFORE UPDATE ON "
                + table
                + " FOR EACH ROW "
                + triggerAction);
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
                    OrderShardingRuleFactory.create(currentMonth, inventory),
                    new Properties());
        route = shadow;
        write(
            path.resolve("rules-created.json"),
            Map.of(
                "observedInventory",
                inventory,
                "currentMonth",
                currentMonth.toString(),
                "defaultDataSourceAdded",
                false));
      } else route = base;
      business = new ObservingJdbc(this, route);
      manager = new DataSourceTransactionManager(route);
      orders = logical ? new LogicalOrderSqlMapper(this, business) : new OrderSqlMapper(business);
      intents = new OrderReservationIntentMapper(business);
      outbox = new OrderOutboxMapper(business);
      actions = new OrderStockActionMapper(business);
      OrderReservationIntentService intentService =
          proxy(new OrderReservationIntentService(intents), manager);
      target = new OrderTestService(stock, product, user, cart, orders, new ObjectMapper());
      ReflectionTestUtils.setField(target, "reservationIntentService", intentService);
      ReflectionTestUtils.setField(target, "reservationIntentMapper", intents);
      ReflectionTestUtils.setField(target, "orderOutboxMapper", outbox);
      ReflectionTestUtils.setField(target, "orderStockActionMapper", actions);
      ReflectionTestUtils.setField(
          target,
          "creationClock",
          Clock.fixed(anchor.atOffset(ZoneOffset.ofHours(8)).toInstant(), ZoneOffset.ofHours(8)));
      service = proxy(target, manager);
      assertSame(route, business.getDataSource());
      assertSame(route, manager.getDataSource());
      AnnotationTransactionAttributeSource annotations = new AnnotationTransactionAttributeSource();
      assertEquals(
          TransactionDefinition.PROPAGATION_REQUIRED,
          annotations
              .getTransactionAttribute(
                  com.cloudmall.order.service.impl.OrderCreationServiceImpl.class.getMethod(
                      "create",
                      String.class,
                      CreateOrderDTO.class,
                      java.util.function.LongSupplier.class),
                  com.cloudmall.order.service.impl.OrderCreationServiceImpl.class)
              .getPropagationBehavior());
      assertEquals(
          TransactionDefinition.PROPAGATION_REQUIRES_NEW,
          annotations
              .getTransactionAttribute(
                  OrderReservationIntentService.class.getMethod(
                      "createIntent", OrderReservationIntentPO.class),
                  OrderReservationIntentService.class)
              .getPropagationBehavior());
      write(
          path.resolve("graph.json"),
          Map.of(
              "sameDataSourceAndTM",
              true,
              "createUsesOriginalRequiredAnnotation",
              true,
              "intentUsesOriginalRequiresNew",
              true,
              "recoveriesHaveNoAddedOuterTransaction",
              true,
              "logical",
              logical));
      configureClients();
      write(path.resolve("initial-facts.json"), facts());
    }

    private String marker() {
      return "CM17EI_" + role.name() + "_COMPLETION_FAILURE";
    }

    private String no(String suffix) {
      return stale.format(DateTimeFormatter.ofPattern("uuuuMM")) + "EI" + suffix;
    }

    private String orderTable(String no) {
      return "mall_order_" + no.substring(0, 6);
    }

    private String itemTable(String no) {
      return "mall_order_item_" + no.substring(0, 6);
    }

    /** setup-only固定原tuple，在原恢复入口之前一次写入；不回拨已有时间。 */
    private void seed() throws Exception {
      if (role == Role.REPLAY) {
        seedOrder(no("ReplayB"), 10001L, 10002L, 10003L, stale);
        seedIntent(10004L, no("ReplayB"), "Existing-B", "COMMITTED", stale);
        seedOutbox(10005L, no("ReplayB"), stale);
      } else if (role == Role.INTENT) {
        for (int index = 1; index <= 5; index++) {
          String no = no("Intent" + index);
          seedIntent(
              100L + index,
              no,
              "Intent-Key-" + index,
              index == 2 ? "RESERVE_UNKNOWN" : index == 3 ? "INIT" : "RESERVED",
              stale);
          if (index == 1 || index == 4 || index == 5)
            seedOrder(no, 1000L + index * 10, 1001L + index * 10, 1002L + index * 10, stale);
        }
        stockStates.put(no("Intent2"), "NOT_FOUND");
        stockStates.put(no("Intent3"), "WRONG_IDENTITY");
      } else if (role == Role.OUTBOX) {
        for (int index = 1; index <= 4; index++)
          seedOutbox(200L + index, no("Outbox" + index), stale);
      } else {
        OrderStockActionMapper mapper = new OrderStockActionMapper(sql);
        for (int index = 1; index <= 5; index++) {
          String no = no("Action" + index);
          String direction = index == 5 ? "ROLLBACK" : "CONFIRM";
          assertEquals(1, mapper.insert(300L + index, no, direction, stale));
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", 300L + index);
          row.put("order_no", no);
          row.put("action_type", direction);
          row.put("operation_key", no + ":" + direction);
          row.put("status", "PENDING");
          row.put("attempts", 0L);
          row.put("next_retry_at", MILLIS.format(stale));
          row.put("lease_owner", null);
          row.put("lease_until", null);
          row.put("last_error", null);
          row.put("created_at", MILLIS.format(stale));
          row.put("updated_at", MILLIS.format(stale));
          expected.get("order_stock_action").add(row);
          stockStates.put(
              no,
              index == 2
                  ? "ROLLED_BACK"
                  : index == 3 ? "WRONG_IDENTITY" : index == 4 ? "CONFIRMED" : "RESERVED");
        }
      }
    }

    private void seedOrder(String no, long id, long first, long second, LocalDateTime created)
        throws Exception {
      OrderPO row = orderPO(no, id, created);
      assertEquals(1, new OrderSqlMapper(sql).insertOrder(orderTable(no), row));
      expected.get(orderTable(no)).add(orderGolden(no, id, created));
      for (long sku : List.of(7L, 8L)) {
        long itemId = sku == 7L ? first : second;
        OrderItemPO item = itemPO(no, id, itemId, sku, created);
        assertEquals(1, new OrderSqlMapper(sql).insertOrderItem(itemTable(no), item));
        expected.get(itemTable(no)).add(itemGolden(no, id, itemId, sku, created));
      }
    }

    private void seedIntent(long id, String no, String key, String status, LocalDateTime created)
        throws Exception {
      OrderReservationIntentPO row = new OrderReservationIntentPO();
      row.id = id;
      row.orderNo = no;
      row.userId = 7L;
      row.idempotencyKey = key;
      row.itemsSnapshot =
          JSON.writeValueAsString(
              List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)));
      row.status = status;
      row.createdAt = created;
      row.updatedAt = created;
      row.nextRetryAt = role == Role.REPLAY ? null : created;
      assertEquals(1, new OrderReservationIntentMapper(sql).insertIfAbsent(row));
      expected
          .get("order_reservation_intent")
          .add(intentGolden(id, no, key, status, created, row.nextRetryAt));
    }

    private void seedOutbox(long id, String no, LocalDateTime created) throws Exception {
      OrderOutboxPO row = outboxPO(no, id, created);
      assertEquals(1, new OrderOutboxMapper(sql).insert(row));
      expected.get("order_outbox").add(outboxGolden(no, id, created));
    }

    void configureClients() {
      when(user.addresses())
          .thenAnswer(
              call -> {
                assertClientContext();
                client("addresses", null, Map.of());
                return ApiResponse.ok(List.of(ADDRESS));
              });
      for (long sku : List.of(7L, 8L)) {
        when(product.sku(sku))
            .thenAnswer(
                call -> {
                  assertClientContext();
                  client("sku", null, Map.of("skuId", sku));
                  return ApiResponse.ok(PRODUCTS.get(sku));
                });
        when(cart.cleanupSnapshot(sku, 7L))
            .thenAnswer(
                call -> {
                  assertClientContext();
                  client("cleanupSnapshot", null, Map.of("skuId", sku, "userId", 7L));
                  return ApiResponse.ok(CART.get(sku));
                });
      }
      when(cart.cleanupItem(anyLong(), anyLong(), any()))
          .thenAnswer(
              call -> {
                assertClientContext();
                long sku = call.getArgument(0);
                assertEquals(7L, (Long) call.getArgument(1));
                assertEquals(CART.get(sku), call.getArgument(2));
                assertEquals(Role.REPLAY, role);
                client(
                    "cleanupItem",
                    createdOrderNo,
                    Map.of("skuId", sku, "userId", 7L, "snapshot", CART.get(sku)));
                try (Connection independent = base.getConnection()) {
                  assertTrue(independent.getAutoCommit());
                  JdbcTemplate independentJdbc =
                      jdbc(
                          new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                              independent, true));
                  assertEquals(
                      1,
                      independentJdbc.queryForObject(
                          "SELECT COUNT(*) FROM "
                              + orderTable(createdOrderNo)
                              + " WHERE order_no=?",
                          Integer.class,
                          createdOrderNo));
                  assertEquals(
                      "COMMITTED",
                      independentJdbc.queryForObject(
                          "SELECT status FROM order_reservation_intent WHERE order_no=?",
                          String.class,
                          createdOrderNo));
                }
                cleaned.add(sku);
                return ApiResponse.ok(true);
              });
      when(stock.reserve(any()))
          .thenAnswer(
              call -> {
                stockContext();
                StockClient.Reservation request = call.getArgument(0);
                assertEquals("NORMAL", request.scene());
                assertEquals(
                    List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)),
                    request.items());
                assertEquals(
                    role == Role.REPLAY ? createdOrderNo : no("Intent2"), request.orderNo());
                client(
                    "reserve",
                    request.orderNo(),
                    Map.of("scene", request.scene(), "items", request.items()));
                stockStates.put(request.orderNo(), "RESERVED");
                return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
              });
      when(stock.reservation(anyString()))
          .thenAnswer(
              call -> {
                stockContext();
                String no = call.getArgument(0);
                String state = stockStates.get(no);
                assertNotNull(state);
                client("reservation", no, Map.of("status", state));
                StockReservationStatusVO proof = new StockReservationStatusVO();
                proof.orderNo = state.equals("WRONG_IDENTITY") ? no + "Other" : no;
                proof.status = state.equals("WRONG_IDENTITY") ? "ROLLED_BACK" : state;
                return ApiResponse.ok(proof);
              });
      when(stock.confirm(anyString(), any()))
          .thenAnswer(
              call -> {
                stockContext();
                String no = call.getArgument(0);
                StockClient.Action action = call.getArgument(1);
                assertEquals(no + ":CONFIRM", action.operationKey());
                client("confirm", no, Map.of("operationKey", action.operationKey()));
                stockStates.put(no, "CONFIRMED");
                return ApiResponse.ok(null);
              });
      when(stock.rollback(anyString(), any()))
          .thenAnswer(
              call -> {
                stockContext();
                String no = call.getArgument(0);
                StockClient.Action action = call.getArgument(1);
                assertEquals(no + ":ROLLBACK", action.operationKey());
                client("rollback", no, Map.of("operationKey", action.operationKey()));
                stockStates.put(no, "ROLLED_BACK");
                return ApiResponse.ok(null);
              });
    }

    private void stockContext() {
      if (role == Role.REPLAY) assertClientContext();
      else assertClean();
    }

    private void client(String name, String no, Map<String, Object> values) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("method", name);
      event.put("orderNo", no);
      event.put("arguments", values);
      event.put("transactionActive", TransactionSynchronizationManager.isActualTransactionActive());
      event.put("authUser", AuthContext.userId());
      event.put("xidAbsent", RootContext.getXID() == null);
      clientCalls.add(event);
    }

    Map<String, Object> exercise() throws Exception {
      assertClean();
      operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
      assertTrue(stale.isBefore(operationStarted.minusMinutes(2)));
      base.recording = true;
      Map<String, Object> outcome;
      try {
        switch (role) {
          case REPLAY:
            outcome = replay();
            break;
          case INTENT:
            operation = "recoverStaleReservations";
            new OrderReservationRecovery(stock, intents, orders, JSON).recoverStaleReservations();
            outcome = verifyIntentChain();
            break;
          case OUTBOX:
            operation = "dispatchPending";
            dispatch();
            outcome = verifyOutboxChain();
            break;
          case ACTION:
            operation = "recoverDue";
            new OrderStockActionRecovery(actions, stock).recoverDue();
            outcome = verifyActionChain();
            break;
          default:
            throw new IllegalStateException("CM17EI_UNKNOWN_ROLE");
        }
      } finally {
        base.recording = false;
        AuthContext.clear();
      }
      assertClean();
      base.assertClosedConnections();
      applyWrites();
      finalFacts = facts();
      verifyFinal(finalFacts);
      verifyWire();
      write(path.resolve("original-calls.json"), calls);
      write(path.resolve("logical-inputs.json"), logicalInputs);
      write(path.resolve("actual-executed-sql.json"), base.executions);
      write(path.resolve("client-calls.json"), clientCalls);
      write(path.resolve("phases.json"), phases);
      write(path.resolve("expected-final-rows.json"), expected);
      write(path.resolve("final-facts.json"), finalFacts);
      write(path.resolve("result.json"), outcome);
      verified = true;
      return outcome;
    }

    /** A真实生成身份后重放；B只用初始声明seed，无DELETE投影造场景。 */
    private Map<String, Object> replay() throws Exception {
      Map<String, String> saved = MDC.getCopyOfContextMap();
      try {
        AuthContext.set(7L, "USER");
        MDC.put("traceId", TRACE);
        operation = "create-A";
        ApiResponse<?> first = service.create("Key-Case", request());
        assertEquals("0", first.code);
        OrderVO original = (OrderVO) first.data;
        assertEquals(createdOrderNo, original.orderNo);
        captureCreatedGolden();
        applyWrites();
        verifyRows(facts());
        assertVO(original, createdOrderNo, anchor);
        Facts before = facts();
        phases.add(Map.of("phase", "after-real-create-A", "facts", before));
        int clientBefore = clientCalls.size();
        operation = "direct-replay-A-next-month-clock";
        ReflectionTestUtils.setField(
            target,
            "creationClock",
            Clock.fixed(
                anchor.plusMonths(1).atOffset(ZoneOffset.ofHours(8)).toInstant(),
                ZoneOffset.ofHours(8)));
        ApiResponse<?> repeated = service.create("Key-Case", request());
        assertEquals("0", repeated.code);
        assertEquals(JSON.valueToTree(original), JSON.valueToTree(repeated.data));
        assertVO((OrderVO) repeated.data, createdOrderNo, anchor);
        assertEquals(clientBefore, clientCalls.size());
        assertEquals(before, facts());
        for (String alias : List.of("KEY-CASE", "Kéy-Case", "Key-Case ")) {
          operation = "alias-replay-A";
          BizException rejected =
              assertThrows(BizException.class, () -> service.create(alias, request()));
          assertEquals(409, rejected.getHttpStatus());
          assertEquals(ErrorCodes.DUPLICATE, rejected.getCode());
          assertEquals(clientBefore, clientCalls.size());
          assertEquals(before, facts());
        }
        operation = "committed-intent-fallback-B";
        ApiResponse<?> fallback = service.create("Existing-B", request());
        assertEquals("0", fallback.code);
        assertVO((OrderVO) fallback.data, no("ReplayB"), stale);
        assertEquals(before, facts());
        assertEquals(List.of(7L, 8L), cleaned);
        verify(user, times(2)).addresses();
        for (long sku : List.of(7L, 8L)) {
          verify(product, times(2)).sku(sku);
          verify(cart, times(2)).cleanupSnapshot(sku, 7L);
          verify(cart).cleanupItem(sku, 7L, CART.get(sku));
        }
        verify(stock)
            .reserve(
                new StockClient.Reservation(
                    createdOrderNo,
                    List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)),
                    "NORMAL"));
        verifyNoMoreInteractions(user, product, cart, stock);
        List<Map<String, Object>> duplicate =
            calls.stream()
                .filter(
                    event ->
                        "committed-intent-fallback-B".equals(event.get("operation"))
                            && sqlOf(event).startsWith("insert into order_reservation_intent"))
                .toList();
        assertEquals(1, duplicate.size());
        assertTrue(duplicate.get(0).containsKey("error"));
        assertTrue(
            ((Map<?, ?>) duplicate.get(0).get("error"))
                .get("type")
                .toString()
                .contains("DuplicateKeyException"));
        return Map.of(
            "role",
            "REPLAY",
            "orders",
            2,
            "items",
            4,
            "intent",
            2,
            "idem",
            1,
            "outbox",
            2,
            "reserve",
            1,
            "cleanup",
            2,
            "directAndFallbackSameOriginal",
            true,
            "aliasesRejected",
            3);
      } finally {
        AuthContext.clear();
        if (saved == null) MDC.clear();
        else MDC.setContextMap(saved);
      }
    }

    /** 原发送位置使用真实CorrelationData future与MessagePostProcessor；无broker。 */
    private void dispatch() {
      doAnswer(
              call -> {
                assertClean();
                String exchange = call.getArgument(0);
                String routingKey = call.getArgument(1);
                Map<String, Object> envelope = call.getArgument(2);
                MessagePostProcessor processor = call.getArgument(3);
                CorrelationData correlation = call.getArgument(4);
                String event = (String) envelope.get("eventId");
                assertEquals(event, correlation.getId());
                assertEquals("", routingKey);
                assertEquals(OrderMessagingConfiguration.TIMEOUT_EXCHANGE, exchange);
                Map<String, Object> seedRow =
                    seed.get("order_outbox").stream()
                        .filter(row -> row.get("event_id").equals(event))
                        .findFirst()
                        .orElseThrow();
                Map<String, Object> original =
                    JSON.readValue((String) seedRow.get("payload"), Map.class);
                assertEquals(seedRow.get("order_no"), envelope.get("orderNo"));
                assertEquals("ORDER_TIMEOUT", envelope.get("eventType"));
                for (String key :
                    List.of("eventId", "eventType", "orderNo", "expireAt", "occurredAt", "traceId"))
                  assertEquals(original.get(key), envelope.get(key));
                assertEquals(original, envelope.get("payload"));
                byte[] originalBody = JSON.writeValueAsBytes(envelope);
                Message processed =
                    processor.postProcessMessage(
                        new Message(originalBody, new MessageProperties()));
                assertArrayEquals(originalBody, processed.getBody());
                assertNotNull(processed.getMessageProperties().getExpiration());
                long ttl = Long.parseLong(processed.getMessageProperties().getExpiration());
                assertTrue(ttl > 0 && ttl <= 30 * 60 * 1000L);
                client(
                    "publish",
                    (String) seedRow.get("order_no"),
                    Map.of(
                        "eventId",
                        event,
                        "exchange",
                        exchange,
                        "routingKey",
                        routingKey,
                        "envelope",
                        envelope,
                        "ttlMillis",
                        ttl,
                        "messagePostProcessorExecuted",
                        true));
                if (seedRow.get("id").equals(202L))
                  throw new IllegalStateException("CM17EI_CONTROLLED_SEND_FAILURE");
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(
              anyString(),
              anyString(),
              any(),
              any(MessagePostProcessor.class),
              any(CorrelationData.class));
      new OrderOutboxDispatcher(
              outbox,
              rabbit,
              JSON,
              new MockEnvironment()
                  .withProperty("cloudmall.order.outbox.confirm-timeout-ms", "100"))
          .dispatchPending();
      verify(rabbit).setMandatory(true);
      verify(rabbit, times(4))
          .convertAndSend(
              anyString(),
              eq(""),
              any(),
              any(MessagePostProcessor.class),
              any(CorrelationData.class));
      verifyNoMoreInteractions(rabbit);
      verifyNoInteractions(stock, user, product, cart);
    }

    private Map<String, Object> verifyIntentChain() {
      assertClean();
      assertEquals(
          5, updates("update order_reservation_intent set next_retry_at=?,updated_at=?").size());
      assertEquals(3, updates("update order_reservation_intent set status='committed'").size());
      assertEquals(1, updates("update order_reservation_intent set status='released'").size());
      assertEquals(1, updates("update order_reservation_intent set last_error=?").size());
      assertCompletionFault("order_reservation_intent", 104L);
      verify(stock, times(3)).reservation(no("Intent2"));
      verify(stock)
          .reserve(
              new StockClient.Reservation(
                  no("Intent2"),
                  List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2)),
                  "NORMAL"));
      verify(stock).rollback(no("Intent2"));
      verify(stock).rollback(no("Intent2"), new StockClient.Action(no("Intent2") + ":ROLLBACK"));
      verify(stock).reservation(no("Intent3"));
      verifyNoMoreInteractions(stock);
      verifyNoInteractions(user, product, cart, rabbit);
      return Map.of(
          "role",
          "INTENT",
          "claimed",
          5,
          "committed",
          2,
          "released",
          1,
          "retry",
          1,
          "completionDbFault",
          1,
          "healthyFollower",
          true);
    }

    private Map<String, Object> verifyOutboxChain() {
      assertEquals(3, updates("update order_outbox set status='published'").size());
      assertEquals(1, updates("update order_outbox set attempts=case").size());
      assertCompletionFault("order_outbox", 203L);
      assertEquals(4, clientCalls.size());
      return Map.of(
          "role",
          "OUTBOX",
          "published",
          2,
          "retry",
          1,
          "completionDbFault",
          1,
          "healthyFollower",
          true,
          "realBrokerContacted",
          false);
    }

    private Map<String, Object> verifyActionChain() {
      assertClean();
      List<Map<String, Object>> claims = updates("update order_stock_action set lease_owner=?");
      assertEquals(5, claims.size());
      Set<String> owners = new HashSet<>();
      for (Map<String, Object> claim : claims) {
        assertEquals(1L, claim.get("changed"));
        String owner = (String) args(claim).get(0);
        assertTrue(owner.matches("[0-9a-f-]{36}"));
        assertTrue(owners.add(owner));
        assertEquals(
            LocalDateTime.parse((String) args(claim).get(2)).plusSeconds(30),
            LocalDateTime.parse((String) args(claim).get(1)));
      }
      assertEquals(4, updates("update order_stock_action set status=?,last_error=?").size());
      assertEquals(1, updates("update order_stock_action set status='unknown'").size());
      assertCompletionFault("order_stock_action", 304L);
      for (Map<String, Object> call : calls) {
        String text = sqlOf(call);
        if (text.startsWith("update order_stock_action set status")) {
          List<Object> values = args(call);
          long id = ((Number) values.get(3)).longValue();
          Map<String, Object> claimed =
              claims.stream().filter(row -> args(row).get(3).equals(id)).findFirst().orElseThrow();
          assertEquals(args(claimed).get(0), values.get(4));
          assertTrue(
              LocalDateTime.parse((String) values.get(5))
                  .isBefore(LocalDateTime.parse((String) args(claimed).get(1))));
        }
      }
      verify(stock, times(2)).reservation(no("Action1"));
      verify(stock).confirm(no("Action1"), new StockClient.Action(no("Action1") + ":CONFIRM"));
      verify(stock).reservation(no("Action2"));
      verify(stock).reservation(no("Action3"));
      verify(stock).reservation(no("Action4"));
      verify(stock, times(2)).reservation(no("Action5"));
      verify(stock).rollback(no("Action5"), new StockClient.Action(no("Action5") + ":ROLLBACK"));
      verifyNoMoreInteractions(stock);
      verifyNoInteractions(user, product, cart, rabbit);
      return Map.of(
          "role",
          "ACTION",
          "claimed",
          5,
          "succeeded",
          2,
          "conflict",
          1,
          "retry",
          1,
          "completionDbFault",
          1,
          "healthyFollower",
          true);
    }

    private List<Map<String, Object>> updates(String prefix) {
      return calls.stream()
          .filter(event -> event.get("kind").equals("update") && sqlOf(event).startsWith(prefix))
          .toList();
    }

    private void assertCompletionFault(String table, long id) {
      List<Map<String, Object>> failures =
          calls.stream()
              .filter(
                  event -> sqlOf(event).startsWith("update " + table) && event.containsKey("error"))
              .toList();
      assertEquals(1, failures.size());
      Map<String, Object> error = (Map<String, Object>) failures.get(0).get("error");
      assertEquals(true, error.get("declaredTrigger45000Vendor1644Marker"));
      assertTrue(
          args(failures.get(0)).contains(id)
              || table.equals("order_reservation_intent")
                  && args(failures.get(0)).contains(no("Intent4"))
              || table.equals("order_outbox")
                  && args(failures.get(0)).contains("order-timeout:" + no("Outbox3")));
    }

    /** 原create生成行的独立完整golden，seed始终不变。 */
    private final Map<String, List<Map<String, Object>>> createdRows = new TreeMap<>();

    private void captureCreatedGolden() throws Exception {
      for (String table : expectedTables()) createdRows.put(table, new ArrayList<>());
      Map<String, Object> main = singleInsert("mall_order");
      List<Object> mv = args(main);
      long orderId = ((Number) mv.get(0)).longValue();
      createdRows.get(orderTable(createdOrderNo)).add(orderGolden(createdOrderNo, orderId, anchor));
      assertInsert(
          main,
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
          createdRows.get(orderTable(createdOrderNo)).get(0));
      List<Map<String, Object>> items =
          calls.stream()
              .filter(
                  event ->
                      "create-A".equals(event.get("operation"))
                          && sqlOf(event).startsWith("insert into mall_order_item"))
              .toList();
      assertEquals(2, items.size());
      Set<Long> skus = new HashSet<>();
      for (Map<String, Object> event : items) {
        List<Object> values = args(event);
        long sku = ((Number) values.get(4)).longValue();
        assertTrue(skus.add(sku));
        Map<String, Object> item =
            itemGolden(createdOrderNo, orderId, ((Number) values.get(0)).longValue(), sku, anchor);
        createdRows.get(itemTable(createdOrderNo)).add(item);
        assertInsert(
            event,
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
            item);
      }
      assertEquals(Set.of(7L, 8L), skus);
      Map<String, Object> intent = singleInsert("order_reservation_intent");
      Map<String, Object> ig =
          intentGolden(
              ((Number) args(intent).get(0)).longValue(),
              createdOrderNo,
              "Key-Case",
              "INIT",
              anchor,
              null);
      createdRows.get("order_reservation_intent").add(ig);
      assertInsert(
          intent,
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
              "updated_at"),
          ig);
      Map<String, Object> idem = singleInsert("order_idempotency");
      Map<String, Object> dg = new LinkedHashMap<>();
      dg.put("id", args(idem).get(0));
      dg.put("user_id", 7L);
      dg.put("idempotency_key", "Key-Case");
      dg.put("order_no", createdOrderNo);
      dg.put("created_at", MILLIS.format(anchor));
      createdRows.get("order_idempotency").add(dg);
      assertInsert(idem, List.of("id", "user_id", "idempotency_key", "order_no", "created_at"), dg);
      Map<String, Object> ob = singleInsert("order_outbox");
      Map<String, Object> og =
          outboxGolden(createdOrderNo, ((Number) args(ob).get(0)).longValue(), anchor);
      createdRows.get("order_outbox").add(og);
      assertInsert(
          ob,
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
              "updated_at"),
          og);
    }

    private Map<String, Object> singleInsert(String target) {
      List<Map<String, Object>> found =
          calls.stream()
              .filter(
                  event ->
                      "create-A".equals(event.get("operation"))
                          && sqlOf(event)
                              .matches(
                                  "insert into "
                                      + target
                                      + (target.equals("mall_order")
                                          ? "(?:_"
                                              + anchor.format(DateTimeFormatter.ofPattern("uuuuMM"))
                                              + ")?"
                                          : "")
                                      + "\\s*\\(.*"))
              .toList();
      assertEquals(1, found.size());
      assertEquals(1L, found.get(0).get("changed"));
      return found.get(0);
    }

    private void assertInsert(
        Map<String, Object> event, List<String> columns, Map<String, Object> golden)
        throws Exception {
      List<Object> values = args(event);
      assertEquals(columns.size(), values.size());
      for (int index = 0; index < columns.size(); index++) {
        String column = columns.get(index);
        Object value = values.get(index);
        if (value != null && column.endsWith("_at"))
          value = MILLIS.format(LocalDateTime.parse((String) value));
        assertColumn(column, golden.get(column), value);
      }
    }

    /** 全部原成功UPDATE按绑定构成golden；异常statement不产生期望写入。 */
    private void applyWrites() throws Exception {
      expected.clear();
      expected.putAll(deepRows(seed));
      for (var entry : createdRows.entrySet())
        expected
            .get(entry.getKey())
            .addAll(deepRows(Map.of(entry.getKey(), entry.getValue())).get(entry.getKey()));
      Map<String, TimeBinding> times = new LinkedHashMap<>();
      for (Map<String, Object> call : calls) {
        if (!call.get("kind").equals("update") || !sqlOf(call).startsWith("update ")) continue;
        if (call.containsKey("error")) continue;
        assertEquals(1L, call.get("changed"));
        List<Object> values = args(call);
        String text = sqlOf(call);
        String table = text.split(" ")[1];
        Map<String, Object> row;
        if (table.equals("order_reservation_intent")) {
          String no =
              (String)
                  values.get(
                      text.startsWith("update order_reservation_intent set last_error=?")
                          ? 3
                          : text.startsWith("update order_reservation_intent set status=?")
                              ? 2
                              : text.startsWith(
                                      "update order_reservation_intent set next_retry_at=?")
                                  ? 2
                                  : 1);
          row =
              expected.get(table).stream()
                  .filter(item -> item.get("order_no").equals(no))
                  .findFirst()
                  .orElseThrow();
          if (text.contains("set next_retry_at=?,updated_at=?")) {
            assertEquals(
                LocalDateTime.parse((String) values.get(1)).plusMinutes(1),
                LocalDateTime.parse((String) values.get(0)));
            time(table, row, "next_retry_at", values.get(0), call, times);
            time(table, row, "updated_at", values.get(1), call, times);
          } else if (text.contains("set last_error=?")) {
            row.put("last_error", values.get(0));
            time(table, row, "next_retry_at", values.get(1), call, times);
            time(table, row, "updated_at", values.get(2), call, times);
            assertWindow(LocalDateTime.parse((String) values.get(1)).minusMinutes(1), call);
          } else {
            row.put(
                "status",
                text.contains("set status=?")
                    ? values.get(0)
                    : text.contains("set status='committed'") ? "COMMITTED" : "RELEASED");
            row.put("last_error", null);
            clearTime(table, row, "next_retry_at", times);
            time(
                table,
                row,
                "updated_at",
                values.get(text.contains("set status=?") ? 1 : 0),
                call,
                times);
          }
        } else if (table.equals("order_outbox")) {
          String event = (String) values.get(text.contains("set status='published'") ? 2 : 3);
          row =
              expected.get(table).stream()
                  .filter(item -> item.get("event_id").equals(event))
                  .findFirst()
                  .orElseThrow();
          if (text.contains("set status='published'")) {
            row.put("status", "PUBLISHED");
            row.put("last_error", null);
            time(table, row, "published_at", values.get(0), call, times);
            time(table, row, "updated_at", values.get(1), call, times);
          } else {
            assertTrue(text.contains("set attempts=case"));
            row.put("attempts", ((Number) row.get("attempts")).longValue() + 1);
            row.put("last_error", values.get(0));
            time(table, row, "next_retry_at", values.get(1), call, times);
            time(table, row, "updated_at", values.get(2), call, times);
            assertWindow(LocalDateTime.parse((String) values.get(1)).minusSeconds(10), call);
          }
        } else if (table.equals("order_stock_action")) {
          row =
              expected.get(table).stream()
                  .filter(item -> item.get("id").equals(values.get(3)))
                  .findFirst()
                  .orElseThrow();
          if (text.contains("set lease_owner=?")) {
            row.put("lease_owner", values.get(0));
            time(table, row, "lease_until", values.get(1), call, times);
            row.put("attempts", 1L);
            time(table, row, "updated_at", values.get(2), call, times);
          } else {
            row.put("status", text.contains("set status='unknown'") ? "UNKNOWN" : values.get(0));
            row.put("last_error", values.get(text.contains("set status='unknown'") ? 0 : 1));
            row.put("lease_owner", null);
            clearTime(table, row, "lease_until", times);
            time(table, row, "updated_at", values.get(2), call, times);
            if (text.contains("set status='unknown'")) {
              time(table, row, "next_retry_at", values.get(1), call, times);
              assertWindow(LocalDateTime.parse((String) values.get(1)).minusSeconds(5), call);
            }
          }
        } else throw new AssertionError("CM17EI_UNDECLARED_UPDATE");
      }
      Facts actual = facts();
      for (TimeBinding item : times.values()) {
        Map<String, Object> actualRow =
            actual.tables.get(item.table).rows.stream()
                .filter(row -> row.get("id").equals(item.row.get("id")))
                .findFirst()
                .orElseThrow();
        LocalDateTime input = LocalDateTime.parse((String) item.value);
        LocalDateTime floor = input.truncatedTo(ChronoUnit.MILLIS);
        String value = (String) actualRow.get(item.column);
        LocalDateTime stored = LocalDateTime.parse(value);
        assertTrue(stored.equals(floor) || stored.equals(floor.plusNanos(1_000_000)));
        item.row.put(item.column, value);
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("column", item.column);
        proof.put("input", input.toString());
        proof.put("millisFloor", MILLIS.format(floor));
        proof.put("nextMillis", MILLIS.format(floor.plusNanos(1_000_000)));
        proof.put("stored", value);
        ((List<Map<String, Object>>)
                item.call.computeIfAbsent("storedComponentProof", ignored -> new ArrayList<>()))
            .add(proof);
        if (item.column.equals("updated_at") || item.column.equals("published_at"))
          assertWindow(input, item.call);
      }
      verifyOutcomes();
    }

    private void time(
        String table,
        Map<String, Object> row,
        String column,
        Object input,
        Map<String, Object> call,
        Map<String, TimeBinding> times) {
      assertNotNull(input);
      row.put(
          column,
          MILLIS.format(LocalDateTime.parse((String) input).truncatedTo(ChronoUnit.MILLIS)));
      times.put(
          table + ":" + row.get("id") + ":" + column,
          new TimeBinding(table, row, column, input, call));
    }

    private void clearTime(
        String table, Map<String, Object> row, String column, Map<String, TimeBinding> times) {
      row.put(column, null);
      times.remove(table + ":" + row.get("id") + ":" + column);
    }

    private void assertWindow(LocalDateTime input, Map<String, Object> call) {
      assertFalse(input.isBefore(operationStarted.truncatedTo(ChronoUnit.MILLIS)));
      assertFalse(input.isAfter(LocalDateTime.parse((String) call.get("returnedAt"))));
    }

    /** 固定业务预期独立于UPDATE实参，不能把两个同错结果互比当绿。 */
    private void verifyOutcomes() {
      if (role == Role.REPLAY) {
        assertEquals(2, expected.get("order_reservation_intent").size());
        for (Map<String, Object> row : expected.get("order_reservation_intent"))
          assertEquals("COMMITTED", row.get("status"));
        assertEquals(1, expected.get("order_idempotency").size());
      }
      if (role == Role.INTENT) {
        for (int index = 1; index <= 5; index++) {
          Map<String, Object> row = byId("order_reservation_intent", 100L + index);
          assertEquals(
              index == 1 || index == 5
                  ? "COMMITTED"
                  : index == 2 ? "RELEASED" : index == 3 ? "INIT" : "RESERVED",
              row.get("status"));
          if (index == 3) {
            assertEquals(
                "java.lang.IllegalStateException: 库存预扣查询订单号与原意图不一致", row.get("last_error"));
            assertTrue(
                LocalDateTime.parse((String) row.get("next_retry_at")).isAfter(operationStarted));
          } else assertNull(row.get("last_error"));
          if (index == 1 || index == 2 || index == 5) assertNull(row.get("next_retry_at"));
        }
      }
      if (role == Role.OUTBOX) {
        for (int index = 1; index <= 4; index++) {
          Map<String, Object> row = byId("order_outbox", 200L + index);
          assertEquals(index == 1 || index == 4 ? "PUBLISHED" : "PENDING", row.get("status"));
          assertEquals(index == 2 ? 1L : 0L, row.get("attempts"));
          if (index == 2) {
            assertEquals("CM17EI_CONTROLLED_SEND_FAILURE", row.get("last_error"));
            assertTrue(
                LocalDateTime.parse((String) row.get("next_retry_at")).isAfter(operationStarted));
          } else assertNull(row.get("last_error"));
          if (index == 2 || index == 3) assertNull(row.get("published_at"));
        }
      }
      if (role == Role.ACTION) {
        for (int index = 1; index <= 5; index++) {
          Map<String, Object> row = byId("order_stock_action", 300L + index);
          assertEquals(
              index == 1 || index == 5
                  ? "SUCCEEDED"
                  : index == 2 ? "CONFLICT" : index == 3 ? "UNKNOWN" : "PENDING",
              row.get("status"));
          assertEquals(1L, row.get("attempts"));
          if (index == 2) assertEquals("库存已处于不可逆的相反终态: ROLLED_BACK", row.get("last_error"));
          else if (index == 3) {
            assertEquals(
                "java.lang.IllegalStateException: 库存状态查询订单号与原动作不一致", row.get("last_error"));
            assertTrue(
                LocalDateTime.parse((String) row.get("next_retry_at")).isAfter(operationStarted));
          } else assertNull(row.get("last_error"));
          if (index != 4) {
            assertNull(row.get("lease_owner"));
            assertNull(row.get("lease_until"));
          } else {
            assertNotNull(row.get("lease_owner"));
            assertNotNull(row.get("lease_until"));
            assertEquals(MILLIS.format(stale), row.get("next_retry_at"));
          }
        }
      }
    }

    private Map<String, Object> byId(String table, long id) {
      return expected.get(table).stream()
          .filter(row -> row.get("id").equals(id))
          .findFirst()
          .orElseThrow();
    }

    private void assertVO(OrderVO actual, String no, LocalDateTime created) throws Exception {
      assertNotNull(actual);
      assertEquals(no, actual.orderNo);
      assertEquals(7L, actual.userId);
      assertEquals("PENDING_PAYMENT", actual.status);
      assertEquals(new BigDecimal("16.50"), actual.totalAmount);
      assertEquals(new BigDecimal("16.50"), actual.payAmount);
      assertEquals(JSON.readTree(JSON.writeValueAsString(ADDRESS)), JSON.readTree(actual.addressSnapshot));
      assertEquals(created.atOffset(ZoneOffset.ofHours(8)), OffsetDateTime.parse(actual.createdAt));
      assertEquals(
          created.plusMinutes(30).atOffset(ZoneOffset.ofHours(8)),
          OffsetDateTime.parse(actual.expireAt));
      List<Map<String, Object>> rows =
          expected.get(itemTable(no)).stream()
              .filter(row -> row.get("order_no").equals(no))
              .sorted(Comparator.comparingLong(row -> ((Number) row.get("id")).longValue()))
              .toList();
      assertEquals(2, rows.size());
      assertEquals(2, actual.items.size());
      for (int index = 0; index < 2; index++) {
        OrderItemVO item = actual.items.get(index);
        Map<String, Object> row = rows.get(index);
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

    Facts facts() {
      assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
      Map<String, TableFact> tables = new TreeMap<>();
      for (String table : tableTypes(sql).keySet()) {
        assertTrue(expectedTables().contains(table));
        List<Map<String, Object>> rows =
            sql.queryForList("SELECT * FROM " + table + " ORDER BY id").stream()
                .map(OrderReplayRecoveryShardingShadowMySqlIT::canonicalRow)
                .toList();
        long count = sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        assertEquals(count, (long) rows.size());
        tables.put(
            table,
            new TableFact(
                count,
                checksum(sql, table),
                definition(sql, table),
                (String) sql.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"),
                rows));
      }
      return new Facts(database, tables, objects(sql));
    }

    private void verifyRows(Facts actual) throws Exception {
      assertEquals(expectedTables(), actual.tables.keySet());
      for (String table : expectedTables()) {
        String template =
            LEDGERS.contains(table)
                ? table
                : table.startsWith("mall_order_item_")
                    ? "mall_order_item_template"
                    : table.startsWith("order_status_log_")
                        ? "order_status_log_template"
                        : "mall_order_template";
        assertEquals(DEFINITIONS.get(template), actual.tables.get(table).definition);
        List<Map<String, Object>> planned =
            expected.get(table).stream()
                .sorted(Comparator.comparingLong(row -> ((Number) row.get("id")).longValue()))
                .toList();
        assertEquals(planned.size(), actual.tables.get(table).count);
        List<Map<String, Object>> rows = actual.tables.get(table).rows;
        assertEquals(planned.size(), rows.size());
        for (int index = 0; index < planned.size(); index++)
          assertFullRow(planned.get(index), rows.get(index));
      }
    }

    private void verifyFinal(Facts actual) throws Exception {
      assertEquals(database, actual.database);
      verifyObjects(actual.objects, role != Role.REPLAY);
      verifyRows(actual);
    }

    private void verifyObjects(Map<String, Object> actual, boolean triggered) {
      assertEquals(expectedTables(), ((Map<?, ?>) actual.get("tables")).keySet());
      assertTrue(
          ((Map<?, ?>) actual.get("tables")).values().stream().allMatch("BASE TABLE"::equals));
      assertEquals(List.of(), actual.get("views"));
      assertEquals(List.of(), actual.get("events"));
      assertEquals(List.of(), actual.get("routines"));
      assertEquals(triggered ? List.of(TRIGGER) : List.of(), actual.get("triggers"));
      if (triggered) {
        verifyDeclaredTrigger(triggerPlan);
        assertEquals(Map.of(TRIGGER, triggerPlan), actual.get("triggerDefinitions"));
      } else assertEquals(Map.of(), actual.get("triggerDefinitions"));
    }

    @SuppressWarnings("unchecked")
    private void verifyDeclaredTrigger(Map<String, Object> plan) {
      assertNotNull(plan);
      Map<String, Object> metadata = (Map<String, Object>) plan.get("metadata");
      String table =
          role == Role.INTENT
              ? "order_reservation_intent"
              : role == Role.OUTBOX ? "order_outbox" : "order_stock_action";
      assertEquals(database, metadata.get("trigger_schema"));
      assertEquals(TRIGGER, metadata.get("trigger_name"));
      assertEquals(database, metadata.get("event_object_schema"));
      assertEquals(table, metadata.get("event_object_table"));
      assertEquals("BEFORE", metadata.get("action_timing"));
      assertEquals("UPDATE", metadata.get("event_manipulation"));
      assertEquals(
          triggerAction.replaceAll("\\s+", ""),
          ((String) metadata.get("action_statement")).replaceAll("\\s+", ""));
      String ddl =
          (String) ((Map<String, Object>) plan.get("showCreate")).get("sql original statement");
      assertNotNull(ddl);
      assertTrue(ddl.contains(marker()));
      assertEquals(sha(ddl.getBytes(StandardCharsets.UTF_8)), plan.get("ddlSha256"));
    }

    private void verifyWire() {
      assertFalse(base.executions.isEmpty());
      if (role != Role.REPLAY) {
        assertTrue(
            base.executions.stream()
                .allMatch(event -> Boolean.FALSE.equals(event.get("transactionActive"))));
        assertTrue(
            clientCalls.stream()
                .allMatch(
                    event ->
                        Boolean.FALSE.equals(event.get("transactionActive"))
                            && event.get("authUser") == null));
      }
      for (Map<String, Object> event : base.executions) {
        String text = normalized((String) event.get("sql"));
        if (text.startsWith("insert into mall_order")) {
          assertEquals(Role.REPLAY, role);
          assertTrue(
              text.matches(
                  "insert into mall_order(?:_item)?_"
                      + anchor.format(DateTimeFormatter.ofPattern("uuuuMM"))
                      + "\\s*\\(.*"));
        }
      }
      if (logical && (role == Role.REPLAY || role == Role.INTENT)) {
        assertFalse(logicalInputs.isEmpty());
        if (role == Role.REPLAY)
          assertTrue(
              logicalInputs.stream().anyMatch(event -> event.get("method").equals("findOrder")));
        if (role == Role.INTENT)
          assertEquals(
              5,
              logicalInputs.stream()
                  .filter(event -> event.get("method").equals("orderExists"))
                  .count());
      } else assertEquals(List.of(), logicalInputs);
      if (logical && (role == Role.OUTBOX || role == Role.ACTION)) {
        assertSame(shadow, business.getDataSource());
        String table = role == Role.OUTBOX ? "order_outbox" : "order_stock_action";
        assertTrue(
            base.executions.stream()
                .anyMatch(
                    event -> normalized((String) event.get("sql")).startsWith("update " + table)));
      }
    }

    void closeShadow() throws Exception {
      if (shadow != null) {
        shadow.close();
        shadowClosed = true;
      }
    }
  }

  /** 单一最终时间列的原绑定与调用窗口，避免用宽时窗容纳任意偏移。 */
  private record TimeBinding(
      String table,
      Map<String, Object> row,
      String column,
      Object value,
      Map<String, Object> call) {}

  /** 原SQL/RowMapper调用透传，连同失败原链记录，不伪造返回。 */
  private static final class ObservingJdbc extends JdbcTemplate {
    /** 所属路与审计状态。 */
    private final Fixture fixture;

    ObservingJdbc(Fixture fixture, DataSource route) {
      super(route);
      this.fixture = fixture;
      setQueryTimeout(5);
    }

    private Map<String, Object> begin(String sql, Object[] values, String kind) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("sql", sql);
      event.put(
          "parameters",
          Arrays.stream(values).map(OrderReplayRecoveryShardingShadowMySqlIT::binding).toList());
      event.put("kind", kind);
      event.put("operation", fixture.operation);
      event.put("operationStartedAt", fixture.operationStarted.toString());
      event.put("startedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
      event.put("transactionActive", TransactionSynchronizationManager.isActualTransactionActive());
      fixture.calls.add(event);
      if (kind.equals("update")
          && "create-A".equals(fixture.operation)
          && normalized(sql).startsWith("insert into order_reservation_intent")) {
        assertNull(fixture.createdOrderNo);
        fixture.createdOrderNo = (String) values[1];
        assertTrue(
            fixture.createdOrderNo.matches(
                anchor.format(DateTimeFormatter.ofPattern("uuuuMM")) + "[0-9a-f]{32}"));
      }
      return event;
    }

    /** 真实UPDATE及原影响行数/异常同对象传播。 */
    @Override
    public int update(String sql, Object... values) {
      // 1. 记录原参数，不重建SQL或status事实。
      Map<String, Object> event = begin(sql, values, "update");
      // 2. 原JDBC调用必须执行，失败保原异常链。
      try {
        int changed = super.update(sql, values);
        event.put("changed", (long) changed);
        return changed;
      } catch (RuntimeException original) {
        event.put("error", error(original, fixture.marker()));
        throw original;
      }
      // 3. 返回或失败均采集真实结束窗口。
      finally {
        event.put("returnedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
      }
    }

    /** 原RowMapper运行，typed getter red不能被测试兼容层隐藏。 */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... values) {
      // 1. 记录实际原查询与reader路径。
      Map<String, Object> event = begin(sql, values, "query");
      // 2. 原reader自己读取真实ResultSet，不代读或修返回。
      try {
        List<T> result = super.query(sql, mapper, values);
        event.put("rows", result.size());
        return result;
      } catch (RuntimeException original) {
        event.put("error", error(original, fixture.marker()));
        throw original;
      }
      // 3. 不以审计异常包装或替换原业务异常。
      finally {
        event.put("returnedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
      }
    }
  }

  /** 唯一六入口逻辑候选；普通台账全部继承原Mapper，不增加default DS。 */
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

    /** 原orderExists的候选身份合同，逻辑名加原号月份范围定位。 */
    @Override
    public boolean orderExists(String table, String orderNo) {
      // 1. 原号和请求月份一致，不猜当前月或广播。
      requireMonth(table, "mall_order", orderNo, null);
      Object[] values = monthArguments(orderNo);
      String sql =
          "SELECT order_no FROM mall_order WHERE order_no=? AND created_at>=? AND created_at<?";
      record("orderExists", table, sql, List.of("order_no", "monthStart", "nextMonth"), values);
      // 2. 真实候选为空才代表未提交。
      List<Map<String, Object>> candidates = jdbc.queryForList(sql, values);
      if (candidates.isEmpty()) return false;
      // 3. 别名/重复/坏身份原样失败，禁止COUNT>0误授权。
      if (candidates.size() != 1
          || !(candidates.get(0).get("order_no") instanceof String)
          || !Objects.equals(orderNo, candidates.get(0).get("order_no")))
        throw new IllegalStateException("订单存在性候选身份与原意图不一致");
      return true;
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
        assertEquals(anchor, created);
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

    /** 本DataSource内原生连接唯一身份。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 原生close异常或身份异常会永久阻止清理。 */
    private boolean uncertain;

    /** 仅原业务公开入口窗口记录下发SQL。 */
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
            throw new SQLException("CM17EI_CATALOG_MISMATCH");
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
                      throw new SQLException("CM17EI_CATALOG_CHANGE_REJECTED");
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
        throw new SQLException("CM17EI_CREDENTIAL_MISMATCH");
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
      long count, String checksum, String definition, String ddl, List<Map<String, Object>> rows) {}

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

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("CM17EI_SHA_UNAVAILABLE", failure);
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
    return errors.stream().map(OrderReplayRecoveryShardingShadowMySqlIT::safeError).toList();
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
    Exception failure = new Exception("CM17EI_RESOURCE_OR_FACT_GATE_FAILED");
    for (Throwable error : errors) failure.addSuppressed(error);
    throw failure;
  }

  private static String sqlOf(Map<String, Object> event) {
    return normalized((String) event.get("sql"));
  }

  @SuppressWarnings("unchecked")
  private static List<Object> args(Map<String, Object> event) {
    return (List<Object>) event.get("parameters");
  }

  private static Map<String, List<Map<String, Object>>> deepRows(
      Map<String, List<Map<String, Object>>> rows) {
    Map<String, List<Map<String, Object>>> result = new TreeMap<>();
    for (var entry : rows.entrySet()) {
      List<Map<String, Object>> copies = new ArrayList<>();
      for (Map<String, Object> row : entry.getValue()) copies.add(new LinkedHashMap<>(row));
      result.put(entry.getKey(), copies);
    }
    return result;
  }

  /** 仅安全链字段；原对象继续传播，原SQL参数另存。 */
  private static Map<String, Object> error(Throwable failure, String marker) {
    Map<String, Object> result = new LinkedHashMap<>(safeError(failure));
    List<Map<String, Object>> chain = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    boolean proven = false;
    while (!pending.isEmpty()) {
      Throwable item = pending.removeFirst();
      if (!seen.add(item)) continue;
      Map<String, Object> detail = new LinkedHashMap<>(safeError(item));
      if (item instanceof SQLException sql) {
        detail.put("sqlState", String.valueOf(sql.getSQLState()));
        detail.put("vendorCode", sql.getErrorCode());
        if (sql.getNextException() != null) pending.add(sql.getNextException());
        boolean matches =
            "45000".equals(sql.getSQLState())
                && sql.getErrorCode() == 1644
                && String.valueOf(sql.getMessage()).contains(marker);
        detail.put("declaredMarker", matches);
        proven |= matches;
      }
      chain.add(detail);
      if (item.getCause() != null) pending.add(item.getCause());
    }
    result.put("chain", chain);
    result.put("declaredTrigger45000Vendor1644Marker", proven);
    return result;
  }

  private static OrderPO orderPO(String no, long id, LocalDateTime created) throws Exception {
    OrderPO row = new OrderPO();
    row.id = id;
    row.orderNo = no;
    row.userId = 7L;
    row.status = "PENDING_PAYMENT";
    row.totalAmount = new BigDecimal("16.50");
    row.payAmount = new BigDecimal("16.50");
    row.addressSnapshot = JSON.writeValueAsString(ADDRESS);
    row.expireAt = created.plusMinutes(30);
    row.createdAt = created;
    row.updatedAt = created;
    return row;
  }

  private static OrderItemPO itemPO(
      String no, long orderId, long id, long sku, LocalDateTime created) throws Exception {
    ProductClient.SkuView product = PRODUCTS.get(sku);
    assertNotNull(product);
    OrderItemPO row = new OrderItemPO();
    row.id = id;
    row.orderId = orderId;
    row.orderNo = no;
    row.productId = product.productId();
    row.skuId = sku;
    row.productNameSnapshot = product.productName();
    row.skuSnapshot = JSON.writeValueAsString(product.skuSnapshot());
    row.unitPrice = product.unitPrice();
    row.quantity = sku == 7L ? 1 : 2;
    row.lineAmount = new BigDecimal(sku == 7L ? "10.00" : "6.50");
    row.createdAt = created;
    return row;
  }

  private static Map<String, Object> orderGolden(String no, long id, LocalDateTime created)
      throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("order_no", no);
    row.put("user_id", 7L);
    row.put("status", "PENDING_PAYMENT");
    row.put("total_amount", "16.50");
    row.put("pay_amount", "16.50");
    row.put("address_snapshot", JSON.writeValueAsString(ADDRESS));
    row.put("expire_at", MILLIS.format(created.plusMinutes(30)));
    row.put("paid_at", null);
    row.put("cancelled_at", null);
    row.put("created_at", MILLIS.format(created));
    row.put("updated_at", MILLIS.format(created));
    return row;
  }

  private static Map<String, Object> itemGolden(
      String no, long orderId, long id, long sku, LocalDateTime created) throws Exception {
    ProductClient.SkuView product = PRODUCTS.get(sku);
    assertNotNull(product);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("order_id", orderId);
    row.put("order_no", no);
    row.put("product_id", product.productId());
    row.put("sku_id", sku);
    row.put("product_name_snapshot", product.productName());
    row.put("sku_snapshot", JSON.writeValueAsString(product.skuSnapshot()));
    row.put("unit_price", product.unitPrice().toPlainString());
    row.put("quantity", sku == 7L ? 1L : 2L);
    row.put("line_amount", sku == 7L ? "10.00" : "6.50");
    row.put("created_at", MILLIS.format(created));
    return row;
  }

  private static Map<String, Object> intentGolden(
      long id, String no, String key, String status, LocalDateTime created, LocalDateTime next)
      throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("order_no", no);
    row.put("user_id", 7L);
    row.put("idempotency_key", key);
    row.put(
        "items_snapshot",
        JSON.writeValueAsString(List.of(new StockClient.Line(7L, 1), new StockClient.Line(8L, 2))));
    row.put("status", status);
    row.put("last_error", null);
    row.put("next_retry_at", next == null ? null : MILLIS.format(next));
    row.put("created_at", MILLIS.format(created));
    row.put("updated_at", MILLIS.format(created));
    return row;
  }

  private static OrderOutboxPO outboxPO(String no, long id, LocalDateTime created)
      throws Exception {
    OrderOutboxPO row = new OrderOutboxPO();
    row.id = id;
    row.eventId = "order-timeout:" + no;
    row.eventType = "ORDER_TIMEOUT";
    row.orderNo = no;
    row.payload = (String) outboxGolden(no, id, created).get("payload");
    row.status = "PENDING";
    row.attempts = 0;
    row.nextRetryAt = created;
    row.createdAt = created;
    row.updatedAt = created;
    return row;
  }

  private static Map<String, Object> outboxGolden(String no, long id, LocalDateTime created)
      throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("event_id", "order-timeout:" + no);
    row.put("event_type", "ORDER_TIMEOUT");
    row.put("order_no", no);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", "order-timeout:" + no);
    payload.put("eventType", "ORDER_TIMEOUT");
    payload.put("orderNo", no);
    payload.put("expireAt", created.plusMinutes(30).atOffset(ZoneOffset.ofHours(8)).toString());
    payload.put("occurredAt", created.atOffset(ZoneOffset.ofHours(8)).toString());
    payload.put("traceId", TRACE);
    row.put("payload", JSON.writeValueAsString(payload));
    row.put("status", "PENDING");
    row.put("attempts", 0L);
    row.put("next_retry_at", MILLIS.format(created));
    row.put("last_error", null);
    row.put("published_at", null);
    row.put("created_at", MILLIS.format(created));
    row.put("updated_at", MILLIS.format(created));
    return row;
  }
}
