package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 MySQL 验证常规和秒杀订单创建的上海毫秒时间语义。 */
class OrderCreationPrecisionMySqlIT {
  /** 只读核对原订单库的连接。 */
  private static JdbcTemplate source;

  /** 测试前原库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前测试独立创建的数据库名。 */
  private String database;

  /** 隔离库的真实 JDBC 入口。 */
  private JdbcTemplate fixture;

  /** 外层本地 RR 事务，与被测注解事务使用同一数据源。 */
  private TransactionTemplate transaction;

  /** 经注解事务代理的真实订单 Service。 */
  private OrderServiceImpl service;

  /** 隔离库连接地址。 */
  private String isolatedUrl;

  @BeforeAll
  static void setupSource() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_order 测试 URL");
    source =
        new JdbcTemplate(new DriverManagerDataSource(withShanghaiTimezone(url), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void setupFixture() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    database = "cm17f_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_order", "/" + database));
    DataSource dataSource = new DriverManagerDataSource(isolatedUrl, "root", "root");
    fixture = new JdbcTemplate(dataSource);
    cloneFromSource("mall_order_template", "mall_order_template");
    cloneFromSource("mall_order_item_template", "mall_order_item_template");
    cloneFromSource("order_idempotency", "order_idempotency");
    fixture.execute("CREATE TABLE mall_order_202612 LIKE mall_order_template");
    fixture.execute("CREATE TABLE mall_order_202701 LIKE mall_order_template");
    fixture.execute("CREATE TABLE mall_order_item_202612 LIKE mall_order_item_template");
    fixture.execute("CREATE TABLE mall_order_item_202701 LIKE mall_order_item_template");
    OrderReservationIntentMapper intentMapper = new OrderReservationIntentMapper(fixture);
    OrderOutboxMapper outboxMapper = new OrderOutboxMapper(fixture);
    SeckillOrderDedupMapper dedupMapper = new SeckillOrderDedupMapper(fixture);
    SeckillMessageRecoveryMapper recoveryMapper = new SeckillMessageRecoveryMapper(fixture);
    intentMapper.ensureTable();
    outboxMapper.ensureTable();
    dedupMapper.ensureTable();
    recoveryMapper.ensureTable();
    OrderSqlMapper sqlMapper = new OrderSqlMapper(fixture);
    OrderReservationIntentService intentTarget = new OrderReservationIntentService(intentMapper);
    OrderReservationIntentService intentProxy = transactionProxy(intentTarget, dataSource);
    service =
        new OrderServiceImpl(
            mock(StockClient.class),
            mock(ProductClient.class),
            mock(UserClient.class),
            mock(CartClient.class),
            sqlMapper,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "reservationIntentService", intentProxy);
    ReflectionTestUtils.setField(service, "reservationIntentMapper", intentMapper);
    ReflectionTestUtils.setField(service, "orderOutboxMapper", outboxMapper);
    ReflectionTestUtils.setField(service, "seckillOrderDedupMapper", dedupMapper);
    ReflectionTestUtils.setField(service, "seckillMessageRecoveryMapper", recoveryMapper);
    ReflectionTestUtils.setField(
        service,
        "creationClock",
        Clock.fixed(
            OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 999_500_000, ZoneOffset.ofHours(8))
                .toInstant(),
            ZoneOffset.ofHours(8)));
    configureMocks();
    service = transactionProxy(service, dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(false);
  }

  @AfterEach
  void cleanupFixture() {
    // 清理本用例的中断后再访问数据库，避免测试线程信号影响夹具回收。
    Thread.interrupted();
    AuthContext.clear();
    if (database != null && database.matches("cm17f_[0-9a-f]{32}")) {
      String dropped = database;
      source.execute("DROP DATABASE " + dropped);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              dropped));
      System.out.println("已清理订单创建测试隔离库: " + dropped);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17F original tables and checksums unchanged");
  }

  @Test
  void failedCreateCompensationCannotReleaseOnUnprovenStateOrWrongIdentity() {
    installCompensationFailureTrigger();
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(anyString());
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    List<String> cases =
        new ArrayList<>(
            java.util.Arrays.asList(
                null,
                "",
                "UNKNOWN",
                "INIT",
                "NOT_FOUND",
                "RESERVED",
                "ALREADY_RESERVED",
                "CONFIRMED",
                "rolled_back",
                "ROLLED_BACK ",
                "RÓLLED_BACK",
                "rejected",
                "REJECTED ",
                "RÉJECTED"));
    cases.addAll(
        List.of(
            "wrong-order",
            "case-order",
            "accent-order",
            "trailing-order",
            "null-order",
            "blank-order"));
    int caseNumber = 0;
    for (String scenario : cases) {
      clearInvocations(stock);
      when(stock.reservation(anyString()))
          .thenAnswer(
              invocation -> {
                String orderNo = invocation.getArgument(0);
                String responseOrder = orderNo;
                String responseState = scenario;
                if (scenario != null && scenario.endsWith("-order")) {
                  responseState = "ROLLED_BACK";
                  responseOrder =
                      switch (scenario) {
                        case "wrong-order" -> orderNo + "other";
                        case "case-order" ->
                            orderNo.equals(orderNo.toUpperCase(java.util.Locale.ROOT))
                                ? orderNo + "A"
                                : orderNo.toUpperCase(java.util.Locale.ROOT);
                        case "accent-order" -> orderNo + "é";
                        case "trailing-order" -> orderNo + " ";
                        case "null-order" -> null;
                        case "blank-order" -> " ";
                        default -> throw new AssertionError(scenario);
                      };
                }
                return ApiResponse.ok(recoveryStatus(responseOrder, responseState));
              });
      String key = "sync-proof-bad-" + caseNumber++;
      RuntimeException original =
          assertThrows(RuntimeException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      String orderNo = (String) intent.get("order_no");
      checks.add(() -> assertEquals("RESERVED", intent.get("status"), String.valueOf(scenario)));
      checks.add(() -> assertNotNull(intent.get("last_error"), String.valueOf(scenario)));
      checks.add(() -> assertNotNull(intent.get("next_retry_at"), String.valueOf(scenario)));
      checks.add(() -> assertEquals(1, original.getSuppressed().length, String.valueOf(scenario)));
      assertTrue(original.getMessage().contains("cm17bq create rejected"));
      verify(stock).reserve(any());
      verify(stock).rollback(orderNo);
      int queryCount =
          (int)
              mockingDetails(stock).getInvocations().stream()
                  .filter(invocation -> invocation.getMethod().getName().equals("reservation"))
                  .count();
      checks.add(() -> assertEquals(1, queryCount, String.valueOf(scenario)));
      assertFailedCreateHasNoCommittedArtifacts();
      assertEquals(7L, ((Number) intent.get("user_id")).longValue());
      assertEquals(key, intent.get("idempotency_key"));
      assertTrue(String.valueOf(intent.get("items_snapshot")).contains("skuId"));
    }
    assertAll(checks);
  }

  @Test
  void failedCreateCompensationKeepsIntentWhenItsPostWriteQueryIsUnavailable() {
    installCompensationFailureTrigger();
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(anyString());
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String failure : List.of("null-envelope", "null-data", "nonzero-code", "query-error")) {
      clearInvocations(stock);
      when(stock.reservation(anyString()))
          .thenAnswer(
              invocation -> {
                return switch (failure) {
                  case "null-envelope" -> null;
                  case "null-data" -> ApiResponse.ok(null);
                  case "nonzero-code" -> ApiResponse.error("503", "query unknown");
                  case "query-error" ->
                      throw new IllegalStateException("post-rollback query failed");
                  default -> throw new AssertionError(failure);
                };
              });
      String key = "sync-proof-query-" + failure;
      RuntimeException original =
          assertThrows(RuntimeException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      checks.add(() -> assertEquals("RESERVED", intent.get("status"), failure));
      checks.add(() -> assertNotNull(intent.get("last_error"), failure));
      checks.add(() -> assertNotNull(intent.get("next_retry_at"), failure));
      checks.add(() -> assertEquals(1, original.getSuppressed().length, failure));
      assertTrue(original.getMessage().contains("cm17bq create rejected"));
      verify(stock).reserve(any());
      verify(stock).rollback((String) intent.get("order_no"));
      int queryCount =
          (int)
              mockingDetails(stock).getInvocations().stream()
                  .filter(invocation -> invocation.getMethod().getName().equals("reservation"))
                  .count();
      checks.add(() -> assertEquals(1, queryCount, failure));
      assertFailedCreateHasNoCommittedArtifacts();
    }
    assertAll(checks);
  }

  @Test
  void failedCreateCompensationReleasesOnlyAfterExactOriginalTerminalProof() {
    installCompensationFailureTrigger();
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(anyString());
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String state : List.of("ROLLED_BACK", "REJECTED")) {
      clearInvocations(stock);
      when(stock.reservation(anyString()))
          .thenAnswer(
              invocation -> ApiResponse.ok(recoveryStatus(invocation.getArgument(0), state)));
      String key = "sync-proof-terminal-" + state;
      RuntimeException original =
          assertThrows(RuntimeException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      assertEquals("RELEASED", intent.get("status"));
      assertNull(intent.get("last_error"));
      assertNull(intent.get("next_retry_at"));
      assertEquals(0, original.getSuppressed().length);
      assertTrue(original.getMessage().contains("cm17bq create rejected"));
      verify(stock).reserve(any());
      verify(stock).rollback((String) intent.get("order_no"));
      int queryCount =
          (int)
              mockingDetails(stock).getInvocations().stream()
                  .filter(invocation -> invocation.getMethod().getName().equals("reservation"))
                  .count();
      checks.add(() -> assertEquals(1, queryCount, state));
      assertFailedCreateHasNoCommittedArtifacts();
    }
    assertAll(checks);
  }

  /** 只在随机库用真实订单INSERT触发器制造预扣确认后的落库失败。 */
  private void installCompensationFailureTrigger() {
    fixture.execute(
        "CREATE TRIGGER cm17bq_create_failure BEFORE INSERT ON mall_order_202612 FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cm17bq create rejected'");
  }

  /** 本地订单事务失败后不残留订单、明细、幂等记录或timeout消息。 */
  private void assertFailedCreateHasNoCommittedArtifacts() {
    for (String table :
        List.of("mall_order_202612", "mall_order_item_202612", "order_idempotency", "order_outbox"))
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
  }

  @Test
  void normalCreateUsesOneTruncatedShanghaiMillisecondForAllCreatedFields() throws Exception {
    AuthContext.set(7L, "USER");
    ApiResponse<?> response =
        transaction.execute(status -> service.create("idem-normal", normalRequest()));
    assertEquals("0", response.code);
    Map<String, Object> order =
        fixture.queryForMap("SELECT * FROM mall_order_202612 WHERE order_no LIKE '202612%'");
    Timestamp expectedCreated =
        Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000));
    assertEquals(expectedCreated.toLocalDateTime(), local(order.get("created_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(order.get("updated_at")));
    assertEquals(
        Timestamp.valueOf(LocalDateTime.of(2027, 1, 1, 0, 29, 59, 999_000_000)),
        Timestamp.valueOf(local(order.get("expire_at"))));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM mall_order_item_202612 WHERE order_no=?",
            Timestamp.class,
            order.get("order_no")));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM order_reservation_intent WHERE order_no=?",
            Timestamp.class,
            order.get("order_no")));
    Map<String, Object> outbox =
        fixture.queryForMap("SELECT * FROM order_outbox WHERE order_no=?", order.get("order_no"));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("created_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("updated_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("next_retry_at")));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM order_idempotency WHERE idempotency_key='idem-normal'",
            Timestamp.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE order_no=?",
            String.class,
            order.get("order_no")));
    assertPersistedCreation(
        String.valueOf(order.get("order_no")), expectedCreated.toLocalDateTime());
  }

  @Test
  void completedNormalOrderAliasesCannotReplayTheOriginalOrder() {
    AuthContext.set(7L, "USER");
    ApiResponse<?> original =
        transaction.execute(status -> service.create("Key-Case", normalRequest()));
    assertEquals("0", original.code);
    var before = fixtureFacts();
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    UserClient users = (UserClient) ReflectionTestUtils.getField(service, "users");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    clearInvocations(stock, products, users, cart);
    OrderSqlMapper identityMapper = new OrderSqlMapper(fixture);
    var originalIdentity = identityMapper.findIdempotentOrders(7L, "Key-Case").get(0);
    assertEquals("Key-Case", originalIdentity.idempotencyKey);
    assertEquals(7L, originalIdentity.userId);
    assertEquals(((OrderVO) original.data).orderNo, originalIdentity.orderNo);
    assertEquals(LocalDateTime.parse("2026-12-31T23:59:59.999"), originalIdentity.createdAt);
    assertEquals(
        List.of(originalIdentity.orderNo), identityMapper.findIdempotentOrderNos(7L, "Key-Case"));
    for (String alias : List.of("KEY-CASE", "Kéy-Case", "Key-Case ")) {
      BizException failure =
          assertThrows(
              BizException.class,
              () -> transaction.execute(status -> service.create(alias, normalRequest())));
      assertEquals(409, failure.getHttpStatus());
      assertEquals(ErrorCodes.DUPLICATE, failure.getCode());
      verifyNoInteractions(stock, products, users, cart);
      assertEquals(before, fixtureFacts());
    }
    ApiResponse<?> replay =
        transaction.execute(status -> service.create("Key-Case", normalRequest()));
    assertEquals("0", replay.code);
    assertEquals(((OrderVO) original.data).orderNo, ((OrderVO) replay.data).orderNo);
    verifyNoInteractions(stock, products, users, cart);
    assertEquals(before, fixtureFacts());
  }

  @Test
  void committedIntentAliasesCannotReplayWhenIdempotencyProjectionIsMissing() {
    AuthContext.set(7L, "USER");
    ApiResponse<?> original =
        transaction.execute(status -> service.create("Key-Case", normalRequest()));
    assertEquals("0", original.code);
    fixture.update("DELETE FROM order_idempotency");
    var before = fixtureFacts();
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    UserClient users = (UserClient) ReflectionTestUtils.getField(service, "users");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    for (String alias : List.of("KEY-CASE", "Kéy-Case", "Key-Case ")) {
      clearInvocations(stock, products, users, cart);
      BizException failure =
          assertThrows(
              BizException.class,
              () -> transaction.execute(status -> service.create(alias, normalRequest())));
      assertEquals(409, failure.getHttpStatus());
      assertEquals(ErrorCodes.DUPLICATE, failure.getCode());
      verify(users).addresses();
      verify(products).sku(7L);
      verifyNoInteractions(stock, cart);
      assertEquals(before, fixtureFacts());
    }
    clearInvocations(stock, products, users, cart);
    ApiResponse<?> replay =
        transaction.execute(status -> service.create("Key-Case", normalRequest()));
    assertEquals("0", replay.code);
    assertEquals(((OrderVO) original.data).orderNo, ((OrderVO) replay.data).orderNo);
    verifyNoInteractions(stock, cart);
    assertEquals(before, fixtureFacts());
  }

  @Test
  void identicalNormalOrderKeysRemainIndependentAcrossUsers() {
    AuthContext.set(7L, "USER");
    ApiResponse<?> first =
        transaction.execute(status -> service.create("same-key", normalRequest()));
    assertEquals("0", first.code);
    AuthContext.set(8L, "USER");
    ApiResponse<?> second =
        transaction.execute(status -> service.create("same-key", normalRequest()));
    assertEquals("0", second.code);
    assertNotEquals(((OrderVO) first.data).orderNo, ((OrderVO) second.data).orderNo);
    assertEquals(7L, ((OrderVO) first.data).userId);
    assertEquals(8L, ((OrderVO) second.data).userId);
    assertEquals(
        2, fixture.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
    assertEquals(
        2, fixture.queryForObject("SELECT COUNT(*) FROM order_reservation_intent", Integer.class));
    verify((StockClient) ReflectionTestUtils.getField(service, "stock"), times(2)).reserve(any());
  }

  @Test
  void wrongStockOrderCannotFinishIntentAndMatchingFactCanRecoverLater() {
    OrderReservationIntentPO original = insertStaleRecoveryIntent("first");
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    StockClient stock = mock(StockClient.class);
    doReturn(ApiResponse.ok(recoveryStatus(original.orderNo + "other", "ROLLED_BACK")))
        .when(stock)
        .reservation(original.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(
            stock,
            new OrderReservationIntentMapper(fixture),
            new OrderSqlMapper(fixture),
            new ObjectMapper());

    recovery.recoverStaleReservations();

    Map<String, Object> deferred = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    assertDeferredRecoveryIdentity(before, deferred);
    verify(stock, never()).reserve(any());
    verify(stock, never()).rollback(anyString());
    assertNoRecoveryOrderOrMessage(original);

    doReturn(ApiResponse.ok(recoveryStatus(original.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(original.orderNo);
    fixture.update(
        "UPDATE order_reservation_intent SET next_retry_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
    recovery.recoverStaleReservations();
    Map<String, Object> resolved = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    assertEquals("RELEASED", resolved.get("status"));
    assertNull(resolved.get("last_error"));
    assertNull(resolved.get("next_retry_at"));
    assertEquals(before.get("order_no"), resolved.get("order_no"));
    assertEquals(before.get("items_snapshot"), resolved.get("items_snapshot"));
    assertNoRecoveryOrderOrMessage(original);
    verify(stock, never()).reserve(any());
    verify(stock, never()).rollback(anyString());
  }

  @Test
  void wrongIdentityInSecondQueryKeepsRealIntentPendingAfterOriginalReserve() {
    OrderReservationIntentPO original = insertStaleRecoveryIntent("second");
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    StockClient stock = mock(StockClient.class);
    doReturn(
            ApiResponse.ok(recoveryStatus(original.orderNo, "NOT_FOUND")),
            ApiResponse.ok(recoveryStatus(original.orderNo + "other", "ROLLED_BACK")))
        .when(stock)
        .reservation(original.orderNo);
    doReturn(ApiResponse.ok(Map.of("reserved", true))).when(stock).reserve(any());
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(
            stock,
            new OrderReservationIntentMapper(fixture),
            new OrderSqlMapper(fixture),
            new ObjectMapper());

    recovery.recoverStaleReservations();

    assertDeferredRecoveryIdentity(
        before, fixture.queryForMap("SELECT * FROM order_reservation_intent"));
    var replay = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stock).reserve(replay.capture());
    assertEquals(original.orderNo, replay.getValue().orderNo());
    assertEquals(7L, replay.getValue().items().get(0).skuId());
    assertEquals(1, replay.getValue().items().get(0).quantity());
    verify(stock, never()).rollback(anyString());
    assertNoRecoveryOrderOrMessage(original);
  }

  @Test
  void failedClaimLeavesFirstIntentUntouchedAndLaterIntentCanFinish() {
    var first = insertStaleRecoveryIntent("badclaim", 1L);
    var second = insertStaleRecoveryIntent("healthy", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    fixture.execute(
        "CREATE TRIGGER cm17ar_claim_failure BEFORE UPDATE ON order_reservation_intent"
            + " FOR EACH ROW BEGIN IF OLD.id=1 THEN SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='cm17ar claim rejected'; END IF; END");
    StockClient stock = mock(StockClient.class);
    doReturn(ApiResponse.ok(recoveryStatus(second.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(second.orderNo);
    var recovery =
        new OrderReservationRecovery(
            stock,
            new OrderReservationIntentMapper(fixture),
            new OrderSqlMapper(fixture),
            new ObjectMapper());

    assertDoesNotThrow(recovery::recoverStaleReservations);

    assertEquals(before, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        "RELEASED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE id=2", String.class));
    verify(stock, never()).reservation(first.orderNo);
    verify(stock).reservation(second.orderNo);
    verify(stock, never()).reserve(any());
    verify(stock, never()).rollback(anyString());
  }

  @Test
  void failedReleaseWritesOnceWithoutExtraFailureWriteAndLaterIntentCanFinish() {
    var first = insertStaleRecoveryIntent("badrelease", 1L);
    var second = insertStaleRecoveryIntent("healthy", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    fixture.execute(
        "CREATE TRIGGER cm17ar_release_failure BEFORE UPDATE ON order_reservation_intent"
            + " FOR EACH ROW BEGIN IF OLD.id=1 AND NEW.status='RELEASED' THEN SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='cm17ar release rejected'; END IF; END");
    StockClient stock = mock(StockClient.class);
    doReturn(
            ApiResponse.ok(recoveryStatus(first.orderNo, "RESERVED")),
            ApiResponse.ok(recoveryStatus(first.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(first.orderNo);
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(first.orderNo);
    doReturn(ApiResponse.ok(recoveryStatus(second.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(second.orderNo);
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    recovery.recoverStaleReservations();

    var after = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    assertHeldRecoveryFacts(before, after);
    assertEquals(
        "RELEASED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE id=2", String.class));
    verify(observedMapper).markReleased(eq(first.orderNo), any());
    verify(observedMapper, never()).recordFailure(eq(first.orderNo), anyString(), any());
    verify(stock).rollback(first.orderNo);
    verify(stock).reservation(second.orderNo);
    verify(stock, never()).reserve(any());
  }

  @Test
  void failedFailureRecordKeepsLeaseAndLaterIntentCanFinish() {
    var first = insertStaleRecoveryIntent("badrecord", 1L);
    var second = insertStaleRecoveryIntent("healthy", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    fixture.execute(
        "CREATE TRIGGER cm17ar_record_failure BEFORE UPDATE ON order_reservation_intent"
            + " FOR EACH ROW BEGIN IF OLD.id=1 AND NEW.last_error IS NOT NULL THEN SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='cm17ar record rejected'; END IF; END");
    StockClient stock = mock(StockClient.class);
    doReturn(ApiResponse.ok(recoveryStatus(first.orderNo + "other", "ROLLED_BACK")))
        .when(stock)
        .reservation(first.orderNo);
    doReturn(ApiResponse.ok(recoveryStatus(second.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(second.orderNo);
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    assertDoesNotThrow(recovery::recoverStaleReservations);

    assertHeldRecoveryFacts(
        before, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        "RELEASED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE id=2", String.class));
    verify(observedMapper).recordFailure(eq(first.orderNo), anyString(), any());
    verify(stock).reservation(second.orderNo);
    verify(stock, never()).reserve(any());
    verify(stock, never()).rollback(anyString());
  }

  @Test
  void missingIntentTableStillPropagatesScanFailureWithoutStockCalls() {
    fixture.execute("DROP TABLE order_reservation_intent");
    StockClient stock = mock(StockClient.class);
    var recovery =
        new OrderReservationRecovery(
            stock,
            new OrderReservationIntentMapper(fixture),
            new OrderSqlMapper(fixture),
            new ObjectMapper());
    assertThrows(
        org.springframework.dao.DataAccessException.class, recovery::recoverStaleReservations);
    verifyNoInteractions(stock);
  }

  @Test
  void preInterruptedRecoveryLeavesRealIntentFactsUntouched() {
    var first = insertStaleRecoveryIntent("interruptfirst", 1L);
    insertStaleRecoveryIntent("interruptsecond", 2L);
    var before = fixtureFacts();
    StockClient stock = mock(StockClient.class);
    doReturn(ApiResponse.ok(recoveryStatus(first.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(first.orderNo);
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    Thread.currentThread().interrupt();
    recovery.recoverStaleReservations();
    boolean interruptPreserved = Thread.currentThread().isInterrupted();
    Thread.interrupted();

    assertTrue(interruptPreserved);
    verifyNoInteractions(stock, observedMapper);
    assertEquals(before, fixtureFacts());
  }

  @Test
  void interruptAfterRealClaimKeepsOnlyTheCommittedLeaseAndNoLaterWork() {
    var first = insertStaleRecoveryIntent("interruptclaim", 1L);
    var second = insertStaleRecoveryIntent("interruptsecond", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    var secondBefore = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2");
    StockClient stock = mock(StockClient.class);
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    doAnswer(
            invocation -> {
              Object claimed = invocation.callRealMethod();
              Thread.currentThread().interrupt();
              return claimed;
            })
        .when(observedMapper)
        .claimRecovery(eq(first.orderNo), any(), any());
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    recovery.recoverStaleReservations();
    boolean interruptPreserved = Thread.currentThread().isInterrupted();
    Thread.interrupted();

    assertTrue(interruptPreserved);
    assertHeldRecoveryFacts(
        before, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        secondBefore, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2"));
    verifyNoInteractions(stock);
    verify(observedMapper, never()).markReleased(anyString(), any());
    verify(observedMapper, never()).recordFailure(anyString(), anyString(), any());
    var held = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    doReturn(ApiResponse.ok(recoveryStatus(second.orderNo, "ROLLED_BACK")))
        .when(stock)
        .reservation(second.orderNo);
    recovery.recoverStaleReservations();
    assertEquals(held, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        "RELEASED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE id=2", String.class));
    verify(stock, never()).reservation(first.orderNo);
    verify(stock).reservation(second.orderNo);
  }

  @Test
  void interruptAfterRollbackReplyDoesNotFinishOrRecordTheRealIntent() {
    var first = insertStaleRecoveryIntent("interruptrollback", 1L);
    insertStaleRecoveryIntent("interruptsecond", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    var secondBefore = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2");
    StockClient stock = mock(StockClient.class);
    doReturn(ApiResponse.ok(recoveryStatus(first.orderNo, "RESERVED")))
        .when(stock)
        .reservation(first.orderNo);
    when(stock.rollback(first.orderNo))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              return ApiResponse.ok(Map.of("rolledBack", true));
            });
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    recovery.recoverStaleReservations();
    boolean interruptPreserved = Thread.currentThread().isInterrupted();
    Thread.interrupted();

    assertTrue(interruptPreserved);
    assertHeldRecoveryFacts(
        before, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        secondBefore, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2"));
    verify(stock).rollback(first.orderNo);
    verify(observedMapper, never()).markReleased(anyString(), any());
    verify(observedMapper, never()).recordFailure(anyString(), anyString(), any());
  }

  @Test
  void wrappedInterruptedFailureAfterRealClaimRestoresSignalWithoutNewWrites() {
    var first = insertStaleRecoveryIntent("interruptwrapped", 1L);
    insertStaleRecoveryIntent("interruptsecond", 2L);
    var before = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1");
    var secondBefore = fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2");
    StockClient stock = mock(StockClient.class);
    var observedMapper = spy(new OrderReservationIntentMapper(fixture));
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "claim interrupted after commit",
                  new InterruptedException("claim acknowledgement interrupted"));
            })
        .when(observedMapper)
        .claimRecovery(eq(first.orderNo), any(), any());
    var recovery =
        new OrderReservationRecovery(
            stock, observedMapper, new OrderSqlMapper(fixture), new ObjectMapper());

    recovery.recoverStaleReservations();
    boolean interruptPreserved = Thread.currentThread().isInterrupted();
    Thread.interrupted();

    assertTrue(interruptPreserved);
    assertHeldRecoveryFacts(
        before, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=1"));
    assertEquals(
        secondBefore, fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=2"));
    verifyNoInteractions(stock);
    verify(observedMapper, never()).recordFailure(anyString(), anyString(), any());
  }

  /** 数据库失败只能留下已提交的认领元数据，不得猜终态或追加失败写。 */
  private void assertHeldRecoveryFacts(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id",
            "order_no",
            "user_id",
            "idempotency_key",
            "items_snapshot",
            "status",
            "created_at")) {
      assertEquals(before.get(column), after.get(column), column);
    }
    assertNull(after.get("last_error"));
    assertNotNull(after.get("next_retry_at"));
  }

  /** 按当前上海时间创建真正陈旧的原意图，物理月表仍在本例隔离库内。 */
  private OrderReservationIntentPO insertStaleRecoveryIntent(String suffix) {
    return insertStaleRecoveryIntent(suffix, 1L);
  }

  /** 构造同批可独立恢复的意图，主键与请求键互不冲突。 */
  private OrderReservationIntentPO insertStaleRecoveryIntent(String suffix, long intentId) {
    OrderReservationIntentPO original = new OrderReservationIntentPO();
    original.id = intentId;
    original.userId = 7L;
    original.idempotencyKey = "recovery-identity-" + suffix;
    original.itemsSnapshot = "[{\"skuId\":7,\"quantity\":1}]";
    original.status = "RESERVE_UNKNOWN";
    original.createdAt =
        OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(5).toLocalDateTime();
    original.updatedAt = original.createdAt;
    original.orderNo =
        original.createdAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
            + "recover"
            + suffix;
    fixture.execute(
        "CREATE TABLE IF NOT EXISTS mall_order_"
            + original.orderNo.substring(0, 6)
            + " LIKE mall_order_template");
    assertEquals(1, new OrderReservationIntentMapper(fixture).insertIfAbsent(original));
    return original;
  }

  /** 构造既有Stock响应VO，包含原查询身份及状态。 */
  private static StockReservationStatusVO recoveryStatus(String orderNo, String status) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = status;
    fact.items = List.of();
    return fact;
  }

  /** 只有失败元数据可变化；原意图状态、身份、载荷和创建时间必须保持。 */
  private void assertDeferredRecoveryIdentity(
      Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id",
            "order_no",
            "user_id",
            "idempotency_key",
            "items_snapshot",
            "status",
            "created_at")) {
      assertEquals(before.get(column), after.get(column), column);
    }
    assertNotNull(after.get("last_error"));
    assertTrue(
        local(after.get("next_retry_at"))
            .isAfter(OffsetDateTime.now(ZoneOffset.ofHours(8)).toLocalDateTime()));
  }

  /** 错单证据不得创建订单、幂等或outbox；只保留唯一原意图。 */
  private void assertNoRecoveryOrderOrMessage(OrderReservationIntentPO original) {
    for (String table :
        List.of(
            "mall_order_" + original.orderNo.substring(0, 6),
            "order_idempotency",
            "order_outbox")) {
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM order_reservation_intent", Integer.class));
  }

  /** 对比隔离库全部业务事实，拒绝/重放不得写新意图、订单、幂等或outbox。 */
  private Map<String, List<Map<String, Object>>> fixtureFacts() {
    Map<String, List<Map<String, Object>>> facts = new java.util.LinkedHashMap<>();
    for (String table : fixture.queryForList("SHOW TABLES", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法隔离表名");
      facts.put(table, fixture.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    }
    return facts;
  }

  @Test
  void explicitUtcSeckillInstantUsesJanuaryLocalMillisecondsAndDuplicateIsIdempotent()
      throws Exception {
    ProductClient products = mock(ProductClient.class);
    ProductClient.SkuView sku =
        new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", new BigDecimal("12.30"), Map.of());
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku));
    ReflectionTestUtils.setField(service, "products", products);
    String address = "{\"receiver\":\"小明\",\"phone\":\"13800138000\"}";
    String requestHash = SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, address);
    Map<String, Object> event =
        Map.of(
            "orderNo",
            "20270101-seckill-utc",
            "eventId",
            "event-utc-1",
            "eventType",
            "SECKILL_ORDER_REQUEST",
            "activityId",
            11L,
            "idempotencyKey",
            "request-utc-1",
            "requestHash",
            requestHash,
            "skuId",
            7L,
            "userId",
            8L,
            "addressSnapshot",
            address,
            "occurredAt",
            "2026-12-31T16:00:00.000900Z");
    transaction.execute(
        status -> {
          service.consumeSeckill(event);
          return null;
        });
    transaction.execute(
        status -> {
          service.consumeSeckill(event);
          return null;
        });
    Map<String, Object> order =
        fixture.queryForMap(
            "SELECT * FROM mall_order_202701 WHERE order_no=?", "20270101-seckill-utc");
    assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), local(order.get("created_at")));
    assertTrue(String.valueOf(order.get("address_snapshot")).contains("小明"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM order_outbox WHERE event_id=?",
            Integer.class,
            "order-timeout:20270101-seckill-utc"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM seckill_order_dedup WHERE idempotency_key=?",
            Integer.class,
            "request-utc-1"));
    Map<String, Object> dedup =
        fixture.queryForMap(
            "SELECT * FROM seckill_order_dedup WHERE idempotency_key='request-utc-1'");
    assertEquals(requestHash, dedup.get("request_hash"));
    assertEquals("event-utc-1", dedup.get("event_id"));
    assertEquals("COMMITTED", dedup.get("status"));
    assertPersistedCreation("20270101-seckill-utc", LocalDateTime.of(2027, 1, 1, 0, 0));
  }

  @Test
  void seckillMonthEndDoesNotRoundIntoNextMonthAndExactMillisStayUnchanged() throws Exception {
    for (String[] sample :
        List.of(
            new String[] {
              "202612-end", "2026-12-31T23:59:59.999500+08:00", "2026-12-31T23:59:59.999"
            },
            new String[] {
              "202701-exact", "2027-01-01T00:00:00.123+08:00", "2027-01-01T00:00:00.123"
            })) {
      Map<String, Object> event =
          Map.of(
              "orderNo",
              sample[0],
              "eventId",
              "event:" + sample[0],
              "eventType",
              "SECKILL_ORDER_REQUEST",
              "activityId",
              11L,
              "idempotencyKey",
              "request:" + sample[0],
              "requestHash",
              SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, "{}"),
              "skuId",
              7L,
              "userId",
              8L,
              "addressSnapshot",
              "{}",
              "occurredAt",
              sample[1]);
      service.consumeSeckill(event);
      assertPersistedCreation(sample[0], LocalDateTime.parse(sample[2]));
    }
  }

  @Test
  void invalidOccurredAtFailsWithoutLeavingOrderOrDedupRows() {
    ProductClient products = mock(ProductClient.class);
    when(products.sku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", BigDecimal.TEN, Map.of())));
    ReflectionTestUtils.setField(service, "products", products);
    Map<String, Object> event =
        Map.of(
            "orderNo", "20261231-seckill-invalid",
            "eventId", "event-invalid",
            "eventType", "SECKILL_ORDER_REQUEST",
            "activityId", 11L,
            "idempotencyKey", "request-invalid",
            "requestHash", SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, "{}"),
            "skuId", 7L,
            "userId", 8L,
            "addressSnapshot", "{}",
            "occurredAt", "not-an-offset-time");
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.execute(
                status -> {
                  service.consumeSeckill(event);
                  return null;
                }));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_order_dedup", Integer.class));
    assertEquals(0, fixture.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
  }

  private void assertPersistedCreation(String orderNo, LocalDateTime expected) throws Exception {
    String month = orderNo.substring(0, 6);
    Map<String, Object> order =
        fixture.queryForMap("SELECT * FROM mall_order_" + month + " WHERE order_no=?", orderNo);
    assertEquals(expected, local(order.get("created_at")));
    assertEquals(expected, local(order.get("updated_at")));
    assertEquals(expected.plusMinutes(30), local(order.get("expire_at")));
    assertEquals(
        expected,
        fixture.queryForObject(
            "SELECT created_at FROM mall_order_item_" + month + " WHERE order_no=?",
            LocalDateTime.class,
            orderNo));
    Map<String, Object> outbox =
        fixture.queryForMap("SELECT * FROM order_outbox WHERE order_no=?", orderNo);
    for (String field : List.of("created_at", "updated_at", "next_retry_at"))
      assertEquals(expected, local(outbox.get(field)));
    var payload = new ObjectMapper().readTree(String.valueOf(outbox.get("payload")));
    assertEquals(
        expected.atOffset(ZoneOffset.ofHours(8)),
        OffsetDateTime.parse(payload.path("occurredAt").asText()));
    assertEquals(
        expected.plusMinutes(30).atOffset(ZoneOffset.ofHours(8)),
        OffsetDateTime.parse(payload.path("expireAt").asText()));
    assertEquals("order-timeout:" + orderNo, payload.path("eventId").asText());
  }

  private LocalDateTime local(Object value) {
    return value instanceof Timestamp timestamp
        ? timestamp.toLocalDateTime()
        : (LocalDateTime) value;
  }

  private CreateOrderDTO normalRequest() {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    com.cloudmall.order.domain.dto.OrderItemDTO item =
        new com.cloudmall.order.domain.dto.OrderItemDTO();
    item.skuId = 7L;
    item.quantity = 1;
    request.items = List.of(item);
    return request;
  }

  @Test
  void ambiguousAddressCannotCreateAnOrderUsingTheOldCanonicalHash() {
    Map<String, Object> event = timeEvent("202612-address-integrity");
    event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    for (String[] sample :
        List.of(
            new String[] {"{} {}", "{}"},
            new String[] {"{\"name\":\"\\uD800\"}", "{\"name\":\"?\"}"})) {
      event.put("addressSnapshot", sample[0]);
      event.put("requestHash", SeckillRequestFingerprint.hash(11, 7, 8, 1, sample[1]));
      assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    }
    for (String table :
        List.of(
            "mall_order_202612", "mall_order_item_202612", "seckill_order_dedup", "order_outbox")) {
      assertEquals(0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
    verifyNoInteractions(ReflectionTestUtils.getField(service, "products"));
  }

  @Test
  void firstDeliveryWithoutVerifiableTimeRollsBackBeforeProductLookup() {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    int index = 0;
    for (Object invalid : new Object[] {null, "", "  ", 123L, "invalid-date"}) {
      Map<String, Object> event = timeEvent("202612-invalid-time-" + index++);
      event.put("occurredAt", invalid);
      assertTimeRejected(event, products);
    }
    assertTimeRejected(timeEvent("202612-missing-time"), products);
  }

  @Test
  void explicitWrongMonthCannotCreateOrderInTheOriginalNumberShard() {
    Map<String, Object> event = timeEvent("202612-wrong-month");
    event.put("occurredAt", "2027-01-01T00:00:00+08:00");
    assertTimeRejected(event, (ProductClient) ReflectionTestUtils.getField(service, "products"));
  }

  @Test
  void committedDuplicateStillSucceedsWhenLegacyRedeliveryHasNoTime() {
    Map<String, Object> event = timeEvent("202612-committed-legacy");
    event.put("occurredAt", "2026-12-31T23:59:59.999500+08:00");
    service.consumeSeckill(event);
    var before =
        fixture.queryForList(
            "CHECKSUM TABLE"
                + " mall_order_202612,mall_order_item_202612,order_outbox,seckill_order_dedup");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    clearInvocations(products);
    event.remove("occurredAt");
    service.consumeSeckill(event);
    verifyNoInteractions(products);
    assertEquals(
        before,
        fixture.queryForList(
            "CHECKSUM TABLE"
                + " mall_order_202612,mall_order_item_202612,order_outbox,seckill_order_dedup"));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
  }

  @Test
  void deadLetterTimeFailuresKeepStoredPayloadAndQuarantineWithoutRpcOrRepublish()
      throws Exception {
    StockClient stock = mock(StockClient.class);
    org.springframework.amqp.rabbit.core.RabbitTemplate rabbit =
        mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
    com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);
    SeckillMessageRecoveryMapper recoveryMapper =
        (SeckillMessageRecoveryMapper)
            ReflectionTestUtils.getField(service, "seckillMessageRecoveryMapper");
    var recovery =
        new com.cloudmall.order.service.impl.SeckillMessageRecovery(
            recoveryMapper, rabbit, new ObjectMapper(), stock);
    doAnswer(
            invocation -> {
              org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                  invocation.getArgument(3);
              correlation
                  .getFuture()
                  .set(
                      new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(
                          true, null));
              return null;
            })
        .when(rabbit)
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    for (String kind : List.of("missing", "wrong-month")) {
      String orderNo = "202612-recovery-" + kind;
      Map<String, Object> event = timeEvent(orderNo);
      if (kind.equals("wrong-month")) event.put("occurredAt", "2027-01-01T00:00:00+08:00");
      Map<String, Object> stockFact = new java.util.LinkedHashMap<>(event);
      stockFact.put("status", "ACCEPTED");
      when(stock.seckillReservationFact(orderNo, "cloud-mall-order"))
          .thenReturn(ApiResponse.ok(stockFact));
      var properties = new org.springframework.amqp.core.MessageProperties();
      properties.setDeliveryTag(17L);
      recovery.consumeDeadLetter(
          new org.springframework.amqp.core.Message(
              new ObjectMapper().writeValueAsBytes(event), properties),
          channel);
      String eventId = String.valueOf(event.get("eventId"));
      Map<String, Object> stored =
          fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
      fixture.update(
          "UPDATE seckill_message_recovery SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
              + " event_id=?",
          eventId);
      recovery.replayDue();
      Map<String, Object> after =
          fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
      assertEquals("QUARANTINED", after.get("status"));
      assertTrue(String.valueOf(after.get("last_error")).contains("ORDER_SECKILL_TIME_UNVERIFIED"));
      assertEquals(stored.get("payload"), after.get("payload"));
      assertEquals(stored.get("request_hash"), after.get("request_hash"));
      assertNull(after.get("lease_owner"));
      assertTrue(local(after.get("next_retry_at")).isAfter(LocalDateTime.now().plusMinutes(4)));
    }
    verify(channel, times(2)).basicAck(17L, false);
    verifyNoInteractions(stock);
    verify(rabbit, never())
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
  }

  @Test
  void firstCommittedConsumerResolvesAllMatchingRecoverableStates() throws Exception {
    int sequence = 0;
    for (String state : List.of("REPLAY_PENDING", "REPLAYED", "QUARANTINED", "VERIFYING")) {
      Map<String, Object> event = timeEvent("202612-resolve-first-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      saveRecovery(100 + sequence++, String.valueOf(event.get("eventId")), event, state);
      Map<String, Object> before = recoveryRow(String.valueOf(event.get("eventId")));
      service.consumeSeckill(event);
      Map<String, Object> after = recoveryRow(String.valueOf(event.get("eventId")));
      assertEquals("RESOLVED", after.get("status"), state);
      assertNull(after.get("lease_owner"));
      assertNull(after.get("lease_until"));
      for (String field :
          List.of(
              "payload", "request_hash", "created_at", "attempts", "next_retry_at", "last_error"))
        assertEquals(before.get(field), after.get(field));
    }
  }

  @Test
  void committedDuplicateResolvesHeldRowsWithoutTimeAndAcceptsHexCase() throws Exception {
    int sequence = 0;
    for (String state : List.of("QUARANTINED", "VERIFYING")) {
      Map<String, Object> event = timeEvent("202612-resolve-duplicate-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      service.consumeSeckill(event);
      event.remove("occurredAt");
      event.put(
          "requestHash",
          String.valueOf(event.get("requestHash")).toUpperCase(java.util.Locale.ROOT));
      long recordId = 100 + sequence++;
      String eventId = String.valueOf(event.get("eventId"));
      saveRecovery(recordId, eventId, event, state);
      service.consumeSeckill(event);
      Map<String, Object> resolved = recoveryRow(eventId);
      assertEquals("RESOLVED", resolved.get("status"));
      assertNull(resolved.get("lease_owner"));
      recoveryMapper()
          .markReplayed(recordId, "held-owner", 99, LocalDateTime.now().plusDays(1), "late");
      recoveryMapper()
          .markVerification(
              recordId, "held-owner", "VERIFYING", "late", LocalDateTime.now().plusDays(1));
      assertEquals(resolved, recoveryRow(eventId));
    }
  }

  @Test
  void reusedEventIdCannotResolveAnotherOrderBusinessTuple() throws Exception {
    Map<String, Object> original = timeEvent("202612-original-identity");
    original.put("occurredAt", "2026-12-01T12:00:00+08:00");
    String eventId = String.valueOf(original.get("eventId"));
    saveRecovery(100, eventId, original, "REPLAY_PENDING");
    Map<String, Object> before = recoveryRow(eventId);
    Map<String, Object> incoming = timeEvent("202612-other-identity");
    incoming.put("eventId", eventId);
    incoming.put("occurredAt", "2026-12-01T12:00:00+08:00");
    service.consumeSeckill(incoming);
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM mall_order_202612 WHERE order_no=?",
            Integer.class,
            incoming.get("orderNo")));
    assertEquals(before, recoveryRow(eventId));
  }

  @Test
  void exactEventIdentityAndPayloadTypesAreRequiredForResolution() throws Exception {
    int sequence = 0;
    for (String fault :
        List.of(
            "case",
            "trailing",
            "inner-event",
            "user-string",
            "key-number",
            "body-hash",
            "metadata-hash")) {
      Map<String, Object> incoming = timeEvent("202612-resolution-fault-" + sequence);
      incoming.put("occurredAt", "2026-12-01T12:00:00+08:00");
      if (fault.equals("key-number")) incoming.put("idempotencyKey", "123");
      String storedId = String.valueOf(incoming.get("eventId"));
      Map<String, Object> payload = new java.util.LinkedHashMap<>(incoming);
      switch (fault) {
        case "case" -> {
          incoming.put("eventId", storedId.toUpperCase(java.util.Locale.ROOT));
          payload.put("eventId", incoming.get("eventId"));
        }
        case "trailing" -> {
          incoming.put("eventId", storedId + " ");
          payload.put("eventId", incoming.get("eventId"));
        }
        case "inner-event" -> payload.put("eventId", "other-event");
        case "user-string" -> payload.put("userId", "8");
        case "key-number" -> payload.put("idempotencyKey", 123);
        case "body-hash" -> payload.put("requestHash", "b".repeat(64));
        default -> {}
      }
      // 元数据hash与incoming保持一致，body-hash用例单独验证内层hash防线。
      recoveryMapper()
          .saveDlq(
              100 + sequence++,
              storedId,
              String.valueOf(incoming.get("requestHash")),
              new ObjectMapper().writeValueAsString(payload),
              "REPLAY_PENDING",
              LocalDateTime.now());
      if (fault.equals("metadata-hash"))
        fixture.update(
            "UPDATE seckill_message_recovery SET request_hash=? WHERE event_id=?",
            "b".repeat(64),
            storedId);
      Map<String, Object> before = recoveryRow(storedId);
      service.consumeSeckill(incoming);
      assertEquals(before, recoveryRow(storedId), fault);
    }
  }

  @Test
  void conflictResolvedAndUnknownStatesRemainUntouchedByCommittedEvidence() throws Exception {
    int sequence = 0;
    for (String state : List.of("CONFLICT", "RESOLVED", "UNRECOGNIZED")) {
      Map<String, Object> event = timeEvent("202612-resolution-terminal-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      String eventId = String.valueOf(event.get("eventId"));
      saveRecovery(100 + sequence++, eventId, event, state);
      Map<String, Object> before = recoveryRow(eventId);
      service.consumeSeckill(event);
      assertEquals(before, recoveryRow(eventId));
    }
  }

  @Test
  void largeLongUserIdentityUsesExactJsonIntegerRatherThanStringCoercion() throws Exception {
    Map<String, Object> event = timeEvent("202612-resolution-large-user");
    event.put("userId", Long.MAX_VALUE);
    event.put("requestHash", SeckillRequestFingerprint.hash(11, 7, Long.MAX_VALUE, 1, "{}"));
    event.put("occurredAt", "2026-12-01T12:00:00+08:00");
    String eventId = String.valueOf(event.get("eventId"));
    saveRecovery(100, eventId, event, "QUARANTINED");
    assertEquals(
        "UNSIGNED INTEGER",
        fixture.queryForObject(
            "SELECT JSON_TYPE(JSON_EXTRACT(payload,'$.userId')) FROM seckill_message_recovery WHERE"
                + " event_id=?",
            String.class,
            eventId));
    service.consumeSeckill(event);
    assertEquals("RESOLVED", recoveryRow(eventId).get("status"));
    assertEquals(
        Long.MAX_VALUE,
        fixture.queryForObject("SELECT user_id FROM mall_order_202612", Long.class));
  }

  private SeckillMessageRecoveryMapper recoveryMapper() {
    return (SeckillMessageRecoveryMapper)
        ReflectionTestUtils.getField(service, "seckillMessageRecoveryMapper");
  }

  private void saveRecovery(long id, String eventId, Map<String, Object> event, String status)
      throws Exception {
    recoveryMapper()
        .saveDlq(
            id,
            eventId,
            String.valueOf(event.get("requestHash")),
            new ObjectMapper().writeValueAsString(event),
            status,
            LocalDateTime.now());
    fixture.update(
        "UPDATE seckill_message_recovery SET"
            + " lease_owner='held-owner',lease_until=?,last_error='keep original' WHERE event_id=?",
        LocalDateTime.now().plusMinutes(1),
        eventId);
  }

  private Map<String, Object> recoveryRow(String eventId) {
    return fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
  }

  private Map<String, Object> timeEvent(String orderNo) {
    return new java.util.LinkedHashMap<>(
        Map.of(
            "orderNo",
            orderNo,
            "eventId",
            SeckillRequestFingerprint.eventId(orderNo),
            "eventType",
            "SECKILL_ORDER_REQUEST",
            "payloadVersion",
            1,
            "activityId",
            11L,
            "skuId",
            7L,
            "userId",
            8L,
            "idempotencyKey",
            "time:" + orderNo,
            "addressSnapshot",
            "{}",
            "requestHash",
            SeckillRequestFingerprint.hash(11, 7, 8, 1, "{}")));
  }

  private void assertTimeRejected(Map<String, Object> event, ProductClient products) {
    clearInvocations(products);
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    boolean stableReason = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (String.valueOf(cause.getMessage()).contains("ORDER_SECKILL_TIME_UNVERIFIED"))
        stableReason = true;
    }
    assertTrue(stableReason);
    verifyNoInteractions(products);
    for (String table :
        List.of(
            "mall_order_202612",
            "mall_order_202701",
            "mall_order_item_202612",
            "mall_order_item_202701",
            "order_outbox",
            "seckill_order_dedup")) {
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }
  }

  private void configureMocks() {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    UserClient users = (UserClient) ReflectionTestUtils.getField(service, "users");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    when(products.sku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", BigDecimal.TEN, Map.of())));
    when(stock.reserve(any())).thenReturn(ApiResponse.ok(null));
    when(cart.deleteItem(anyLong(), anyLong())).thenReturn(ApiResponse.ok(null));
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L, "receiver", "小明"))));
  }

  @SuppressWarnings("unchecked")
  private <T> T transactionProxy(T target, DataSource dataSource) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    TransactionInterceptor interceptor =
        new TransactionInterceptor(
            new DataSourceTransactionManager(dataSource),
            new AnnotationTransactionAttributeSource());
    factory.addAdvice(interceptor);
    return (T) factory.getProxy();
  }

  private void cloneFromSource(String sourceTable, String targetTable) {
    String ddl =
        String.valueOf(
            source
                .queryForMap("SHOW CREATE TABLE cloudmall_order." + sourceTable)
                .get("Create Table"));
    ddl = ddl.replace("`" + sourceTable + "`", "`" + targetTable + "`");
    fixture.execute(ddl);
  }

  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result = new ArrayList<>();
    List<String> tables = source.queryForList("SHOW TABLES FROM cloudmall_order", String.class);
    result.addAll(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : tables) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法表名: " + table);
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
