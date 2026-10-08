package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
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
  void uncertainReserve409AndBadErrorBodiesKeepTheirOriginalIntentRecoverable() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    List<String> bodies = new ArrayList<>();
    for (String code : uncertainReservationCodes())
      bodies.add(code == null ? "{\"code\":null}" : "{\"code\":\"" + code + "\"}");
    bodies.addAll(List.of("not-json", "{}", "null"));
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int caseNumber = 0;
    for (String body : bodies) {
      clearInvocations(stock);
      feign.FeignException remoteFailure = mock(feign.FeignException.class);
      when(remoteFailure.status()).thenReturn(409);
      when(remoteFailure.contentUTF8()).thenReturn(body);
      doThrow(remoteFailure).when(stock).reserve(any());
      String key = "reserve-ambiguous-409-" + caseNumber++;
      BizException returned =
          assertThrows(BizException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      checks.add(() -> assertEquals(202, returned.getHttpStatus(), body));
      checks.add(() -> assertEquals("ORDER_RESERVATION_UNKNOWN", returned.getCode(), body));
      checks.add(() -> assertEquals("RESERVE_UNKNOWN", intent.get("status"), body));
      checks.add(() -> assertNotNull(intent.get("last_error"), body));
      checks.add(() -> assertNotNull(intent.get("next_retry_at"), body));
      checks.add(() -> assertEquals(1, returned.getSuppressed().length, body));
      if (returned.getSuppressed().length > 0)
        checks.add(() -> assertSame(remoteFailure, returned.getSuppressed()[0], body));
      verify(stock).reserve(any());
      verify(stock, never()).rollback(anyString());
      verify(stock, never()).reservation(anyString());
      assertFailedCreateHasNoCommittedArtifacts();
    }
    assertAll(checks);
  }

  @Test
  void nonzeroReserveEnvelopeIsNotDurableRejectionWithoutItsDedicatedCode() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int caseNumber = 0;
    for (String code : uncertainReservationCodes()) {
      clearInvocations(stock);
      doReturn(ApiResponse.error(code, "reserve unknown")).when(stock).reserve(any());
      String key = "reserve-ambiguous-envelope-" + caseNumber++;
      BizException returned =
          assertThrows(BizException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      checks.add(() -> assertEquals(202, returned.getHttpStatus(), String.valueOf(code)));
      checks.add(
          () ->
              assertEquals("ORDER_RESERVATION_UNKNOWN", returned.getCode(), String.valueOf(code)));
      checks.add(() -> assertEquals("RESERVE_UNKNOWN", intent.get("status"), String.valueOf(code)));
      checks.add(() -> assertNotNull(intent.get("last_error"), String.valueOf(code)));
      checks.add(() -> assertNotNull(intent.get("next_retry_at"), String.valueOf(code)));
      verify(stock).reserve(any());
      verify(stock, never()).rollback(anyString());
      verify(stock, never()).reservation(anyString());
      assertFailedCreateHasNoCommittedArtifacts();
    }
    assertAll(checks);
  }

  @Test
  void dedicatedDurableRejectionWorksFor409AndDecodedEnvelopeButNot500() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String transport : List.of("409", "envelope", "500")) {
      clearInvocations(stock);
      if ("envelope".equals(transport)) {
        doReturn(ApiResponse.error("ORDER_STOCK_RESERVATION_REJECTED", "durable rejected"))
            .when(stock)
            .reserve(any());
      } else {
        feign.FeignException failure = mock(feign.FeignException.class);
        when(failure.status()).thenReturn(Integer.parseInt(transport));
        when(failure.contentUTF8()).thenReturn("{\"code\":\"ORDER_STOCK_RESERVATION_REJECTED\"}");
        doThrow(failure).when(stock).reserve(any());
      }
      String key = "reserve-durable-rejected-" + transport;
      BizException returned =
          assertThrows(BizException.class, () -> service.create(key, normalRequest()));
      Map<String, Object> intent =
          fixture.queryForMap(
              "SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
      boolean definitive = !"500".equals(transport);
      checks.add(() -> assertEquals(definitive ? 409 : 202, returned.getHttpStatus(), transport));
      checks.add(
          () ->
              assertEquals(
                  definitive ? ErrorCodes.STOCK : "ORDER_RESERVATION_UNKNOWN",
                  returned.getCode(),
                  transport));
      checks.add(
          () ->
              assertEquals(
                  definitive ? "RELEASED" : "RESERVE_UNKNOWN", intent.get("status"), transport));
      if (definitive) {
        checks.add(() -> assertNull(intent.get("last_error"), transport));
        checks.add(() -> assertNull(intent.get("next_retry_at"), transport));
      } else {
        assertNotNull(intent.get("last_error"));
        assertNotNull(intent.get("next_retry_at"));
      }
      verify(stock).reserve(any());
      verify(stock, never()).rollback(anyString());
      verify(stock, never()).reservation(anyString());
      assertFailedCreateHasNoCommittedArtifacts();
    }
    assertAll(checks);
  }

  /** 普通错误码、类型/大小写别名与未知值不能证明Stock已持久拒绝。 */
  private static List<String> uncertainReservationCodes() {
    return java.util.Arrays.asList(
        ErrorCodes.STOCK,
        ErrorCodes.CONFLICT,
        ErrorCodes.DUPLICATE,
        "ORDER_RESERVATION_KEY_REUSED",
        ErrorCodes.INTERNAL,
        "UNEXPECTED",
        null,
        "",
        " ",
        "order_stock_reservation_rejected",
        "ORDER_STOCK_RESERVATION_REJECTED ",
        "ÓRDER_STOCK_RESERVATION_REJECTED");
  }

  @Test
  void successfulReserveEnvelopeMustMatchOriginalOrderAndAReservedState() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int caseNumber = 0;
    for (String variant : List.of("other", "case", "accent", "trailing", "null", "blank")) {
      resetReservationSuccessFixture();
      clearInvocations(stock);
      doAnswer(
              invocation -> {
                StockClient.Reservation request = invocation.getArgument(0);
                String original = request.orderNo();
                Object responseOrder =
                    switch (variant) {
                      case "other" -> original + "other";
                      case "case" ->
                          original.equals(original.toUpperCase(java.util.Locale.ROOT))
                              ? original + "A"
                              : original.toUpperCase(java.util.Locale.ROOT);
                      case "accent" -> original + "é";
                      case "trailing" -> original + " ";
                      case "null" -> null;
                      case "blank" -> " ";
                      default -> throw new AssertionError(variant);
                    };
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("orderNo", responseOrder);
                response.put("status", "RESERVED");
                return ApiResponse.ok(response);
              })
          .when(stock)
          .reserve(any());
      collectUnknownReservationAssertions("success-identity-" + caseNumber++, checks);
    }
    for (String state :
        java.util.Arrays.asList(
            null,
            "",
            "UNKNOWN",
            "INIT",
            "NOT_FOUND",
            "REJECTED",
            "CONFIRMED",
            "ROLLED_BACK",
            "reserved",
            "RESERVED ",
            "RÉSERVED",
            "already_reserved",
            "ALREADY_RESERVED ")) {
      resetReservationSuccessFixture();
      clearInvocations(stock);
      doAnswer(
              invocation -> {
                StockClient.Reservation request = invocation.getArgument(0);
                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("orderNo", request.orderNo());
                response.put("status", state);
                return ApiResponse.ok(response);
              })
          .when(stock)
          .reserve(any());
      collectUnknownReservationAssertions("success-state-" + caseNumber++, checks);
    }
    assertAll(checks);
  }

  @Test
  void successfulReserveEnvelopeCannotUseMissingScalarOrNonTextualProof() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String shape :
        List.of(
            "null",
            "string",
            "number",
            "array",
            "empty",
            "missing-order",
            "missing-status",
            "numeric-order",
            "boolean-order",
            "numeric-status")) {
      resetReservationSuccessFixture();
      clearInvocations(stock);
      doAnswer(
              invocation -> {
                StockClient.Reservation request = invocation.getArgument(0);
                Object data =
                    switch (shape) {
                      case "null" -> null;
                      case "string" -> "reserved";
                      case "number" -> 123L;
                      case "array" -> List.of();
                      case "empty" -> Map.of();
                      case "missing-order" -> Map.of("status", "RESERVED");
                      case "missing-status" -> Map.of("orderNo", request.orderNo());
                      case "numeric-order" -> Map.of("orderNo", 123L, "status", "RESERVED");
                      case "boolean-order" -> Map.of("orderNo", true, "status", "RESERVED");
                      case "numeric-status" -> Map.of("orderNo", request.orderNo(), "status", 1);
                      default -> throw new AssertionError(shape);
                    };
                return ApiResponse.ok(data);
              })
          .when(stock)
          .reserve(any());
      collectUnknownReservationAssertions("success-shape-" + shape, checks);
    }
    assertAll(checks);
  }

  @Test
  void exactReservedAndAlreadyReservedProofKeepMapAndVoCompatibility() {
    AuthContext.set(7L, "USER");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    for (String state : List.of("RESERVED", "ALREADY_RESERVED")) {
      for (String shape : List.of("map", "vo")) {
        resetReservationSuccessFixture();
        clearInvocations(stock);
        doAnswer(
                invocation -> {
                  StockClient.Reservation request = invocation.getArgument(0);
                  return ApiResponse.ok(
                      "map".equals(shape)
                          ? Map.of("orderNo", request.orderNo(), "status", state)
                          : recoveryStatus(request.orderNo(), state));
                })
            .when(stock)
            .reserve(any());
        ApiResponse<?> returned =
            service.create("success-proof-" + state + "-" + shape, normalRequest());
        assertEquals("0", returned.code);
        String originalNo = ((OrderVO) returned.data).orderNo;
        assertEquals(
            "COMMITTED",
            fixture.queryForObject(
                "SELECT status FROM order_reservation_intent WHERE order_no=?",
                String.class,
                originalNo));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM order_idempotency", Integer.class));
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
        verify(stock).reserve(any());
        verify(stock, never()).reservation(anyString());
        verify(stock, never()).rollback(anyString());
      }
    }
  }

  /** 只清理本例随机库里的创建事实，批量红灯断言不能误用下一样本的数据。 */
  private void resetReservationSuccessFixture() {
    for (String table :
        List.of(
            "mall_order_item_202612",
            "mall_order_202612",
            "order_idempotency",
            "order_outbox",
            "order_reservation_intent")) fixture.update("DELETE FROM " + table);
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    clearInvocations(cart);
  }

  /** 当场捕获原行和副作用，批量断言未知证明不能授权确认、落单或补偿。 */
  private void collectUnknownReservationAssertions(
      String key, List<org.junit.jupiter.api.function.Executable> checks) {
    BizException unknown = null;
    try {
      service.create(key, normalRequest());
    } catch (BizException failure) {
      unknown = failure;
    }
    BizException captured = unknown;
    Map<String, Object> intent =
        fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE idempotency_key=?", key);
    checks.add(() -> assertNotNull(captured, key));
    if (captured != null) {
      checks.add(() -> assertEquals(202, captured.getHttpStatus(), key));
      checks.add(() -> assertEquals("ORDER_RESERVATION_UNKNOWN", captured.getCode(), key));
    }
    checks.add(() -> assertEquals("RESERVE_UNKNOWN", intent.get("status"), key));
    checks.add(() -> assertNotNull(intent.get("last_error"), key));
    checks.add(() -> assertNotNull(intent.get("next_retry_at"), key));
    for (String table :
        List.of(
            "mall_order_202612", "mall_order_item_202612", "order_idempotency", "order_outbox")) {
      Integer count = fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
      checks.add(() -> assertEquals(0, count, key + "/" + table));
    }
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    verify(stock).reserve(any());
    verify(stock, never()).reservation(anyString());
    verify(stock, never()).rollback(anyString());
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    int cartCalls =
        (int)
            mockingDetails(cart).getInvocations().stream()
                .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
                .count();
    checks.add(() -> assertEquals(0, cartCalls, key));
  }

  @Test
  void wrongOrMissingProductSkuCannotCreateAnyOrderOrReservationFacts() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String variant : List.of("wrong", "missing")) {
      Long responseSku = null;
      if ("wrong".equals(variant)) responseSku = 8L;
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(
                      responseSku,
                      9L,
                      "untrusted product",
                      "SKU-OTHER",
                      new BigDecimal("99.90"),
                      Map.of())))
          .when(products)
          .sku(7L);
      clearInvocations(products, stock, cart);
      var before = fixtureFacts();
      RuntimeException failure = null;
      try {
        transaction.execute(
            status -> service.create("untrusted-product-" + variant, normalRequest()));
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
      RuntimeException captured = failure;
      var after = fixtureFacts();
      checks.add(() -> assertInstanceOf(BizException.class, captured, variant));
      if (captured instanceof BizException controlled) {
        checks.add(() -> assertEquals(500, controlled.getHttpStatus(), variant));
        checks.add(() -> assertEquals(ErrorCodes.INTERNAL, controlled.getCode(), variant));
      }
      checks.add(() -> assertEquals(before, after, variant));
      // Interaction assertions must capture the per-case invocations before the next reset.
      long stockCalls = mockingDetails(stock).getInvocations().size();
      long writes =
          mockingDetails(cart).getInvocations().stream()
              .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
              .count();
      checks.add(() -> assertEquals(0, stockCalls, variant));
      checks.add(() -> assertEquals(0, writes, variant));
      verify(products).sku(7L);
      verify(cart).cleanupSnapshot(7L, 7L);
    }
    assertAll(checks);
  }

  @Test
  void aWrongLaterProductSkuCannotPersistAnEarlierValidLineOrReachStock() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    9L, 9L, "wrong second product", "SKU-9", new BigDecimal("99.90"), Map.of())))
        .when(products)
        .sku(8L);
    CreateOrderDTO request = normalRequest();
    OrderItemDTO second = new OrderItemDTO();
    second.skuId = 8L;
    second.quantity = 2;
    request.items = List.of(request.items.get(0), second);
    var before = fixtureFacts();
    RuntimeException failure = null;
    try {
      transaction.execute(status -> service.create("wrong-later-product", request));
    } catch (RuntimeException rejected) {
      failure = rejected;
    }
    RuntimeException captured = failure;
    assertAll(
        () -> assertInstanceOf(BizException.class, captured),
        () -> assertEquals(before, fixtureFacts()),
        () -> verifyNoInteractions(stock),
        () -> verify(cart, never()).cleanupItem(anyLong(), anyLong(), any()),
        () -> verify(cart, never()).deleteItem(anyLong(), anyLong()));
    BizException controlled = (BizException) captured;
    assertEquals(500, controlled.getHttpStatus());
    assertEquals(ErrorCodes.INTERNAL, controlled.getCode());
    verify(products).sku(7L);
    verify(products).sku(8L);
  }

  @Test
  void missingOrNegativeProductPricesCannotAuthorizeOrdinaryOrderFacts() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String fault : List.of("null", "negative", "later-negative")) {
      BigDecimal firstPrice =
          "null".equals(fault)
              ? null
              : "negative".equals(fault) ? new BigDecimal("-12.30") : BigDecimal.TEN;
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", firstPrice, Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      if ("later-negative".equals(fault)) {
        OrderItemDTO second = new OrderItemDTO();
        second.skuId = 8L;
        second.quantity = 1;
        request.items = List.of(request.items.get(0), second);
        doReturn(
                ApiResponse.ok(
                    new ProductClient.SkuView(
                        8L, 9L, "second", "SKU-8", new BigDecimal("-0.01"), Map.of())))
            .when(products)
            .sku(8L);
      }
      clearInvocations(products, stock, cart);
      var before = fixtureFacts();
      RuntimeException failure = null;
      try {
        transaction.execute(status -> service.create("bad-product-price-" + fault, request));
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
      RuntimeException captured = failure;
      var after = fixtureFacts();
      long stockCalls = mockingDetails(stock).getInvocations().size();
      long cartWrites =
          mockingDetails(cart).getInvocations().stream()
              .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
              .count();
      checks.add(() -> assertInstanceOf(BizException.class, captured, fault));
      if (captured instanceof BizException controlled) {
        checks.add(() -> assertEquals(500, controlled.getHttpStatus(), fault));
        checks.add(() -> assertEquals(ErrorCodes.INTERNAL, controlled.getCode(), fault));
      }
      checks.add(() -> assertEquals(before, after, fault));
      checks.add(() -> assertEquals(0, stockCalls, fault));
      checks.add(() -> assertEquals(0, cartWrites, fault));
      verify(products).sku(7L);
      if ("later-negative".equals(fault)) verify(products).sku(8L);
    }
    assertAll(checks);
  }

  @Test
  void missingOrNegativeSeckillPricesRollBackClaimsAndNeverResolveHeldRows() throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int sequence = 0;
    for (String fault : List.of("null", "negative")) {
      Map<String, Object> event = timeEvent("202612-bad-price-" + fault);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      saveRecovery(6000 + sequence++, String.valueOf(event.get("eventId")), event, "QUARANTINED");
      BigDecimal price = "null".equals(fault) ? null : new BigDecimal("-12.30");
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", price, Map.of())))
          .when(products)
          .sku(7L);
      clearInvocations(products);
      var before = fixtureFacts();
      IllegalStateException failure = null;
      try {
        service.consumeSeckill(event);
      } catch (IllegalStateException rejected) {
        failure = rejected;
      }
      IllegalStateException captured = failure;
      var after = fixtureFacts();
      checks.add(() -> assertNotNull(captured, fault));
      if (captured != null)
        checks.add(() -> assertInstanceOf(IllegalStateException.class, captured.getCause(), fault));
      checks.add(() -> assertEquals(before, after, fault));
      verify(products).sku(7L);
      verifyNoMoreInteractions(products);
    }
    assertAll(checks);
    verifyNoInteractions(stock, cart);
  }

  @Test
  void correctedSeckillPriceCanRetryAndLegalZeroAndScientificPricesKeepSqlValues()
      throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    Map<String, Object> retry = timeEvent("202612-corrected-price");
    retry.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(6100, String.valueOf(retry.get("eventId")), retry, "QUARANTINED");
    var before = fixtureFacts();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("-1.00"), Map.of())))
        .when(products)
        .sku(7L);
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(retry));
    assertEquals(before, fixtureFacts());
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("12.30"), Map.of())))
        .when(products)
        .sku(7L);
    service.consumeSeckill(retry);
    assertEquals("RESOLVED", recoveryRow(String.valueOf(retry.get("eventId"))).get("status"));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM seckill_order_dedup WHERE order_no=?",
            String.class,
            retry.get("orderNo")));
    assertPersistedCreation(
        String.valueOf(retry.get("orderNo")), LocalDateTime.parse("2026-12-31T23:59:59.999"));
    AuthContext.set(7L, "USER");
    int sequence = 0;
    for (String representation : List.of("0.00", "-0.0000", "1E+1", "12.3000")) {
      BigDecimal price = new BigDecimal(representation);
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", price, Map.of())))
          .when(products)
          .sku(7L);
      ApiResponse<?> ordinary =
          transaction.execute(
              status -> service.create("legal-price-" + representation, normalRequest()));
      String ordinaryNo = ((OrderVO) ordinary.data).orderNo;
      assertEquals(
          0,
          price.compareTo(
              fixture.queryForObject(
                  "SELECT total_amount FROM mall_order_202612 WHERE order_no=?",
                  BigDecimal.class,
                  ordinaryNo)));
      assertEquals(
          0,
          price.compareTo(
              fixture.queryForObject(
                  "SELECT unit_price FROM mall_order_item_202612 WHERE order_no=?",
                  BigDecimal.class,
                  ordinaryNo)));
      Map<String, Object> seckill = timeEvent("202612-legal-price-" + sequence++);
      seckill.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      service.consumeSeckill(seckill);
      assertEquals(
          0,
          price.compareTo(
              fixture.queryForObject(
                  "SELECT total_amount FROM mall_order_202612 WHERE order_no=?",
                  BigDecimal.class,
                  seckill.get("orderNo"))));
      assertEquals(
          0,
          price.compareTo(
              fixture.queryForObject(
                  "SELECT unit_price FROM mall_order_item_202612 WHERE order_no=?",
                  BigDecimal.class,
                  seckill.get("orderNo"))));
      assertEquals(
          "COMMITTED",
          fixture.queryForObject(
              "SELECT status FROM seckill_order_dedup WHERE order_no=?",
              String.class,
              seckill.get("orderNo")));
    }
  }

  @Test
  void nonCentProductPricesCannotRoundOrderUnitsAndLinesIntoDifferentFacts() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int sequence = 0;
    for (String sample : List.of("12.345", "0.001", "1E-3", "later")) {
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(
                      7L,
                      9L,
                      "product",
                      "SKU-7",
                      "later".equals(sample) ? BigDecimal.TEN : new BigDecimal(sample),
                      Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      request.items.get(0).quantity = 2;
      if ("later".equals(sample)) {
        OrderItemDTO second = new OrderItemDTO();
        second.skuId = 8L;
        second.quantity = 2;
        request.items = List.of(request.items.get(0), second);
        doReturn(
                ApiResponse.ok(
                    new ProductClient.SkuView(
                        8L, 9L, "second", "SKU-8", new BigDecimal("12.345"), Map.of())))
            .when(products)
            .sku(8L);
      }
      clearInvocations(products, stock, cart);
      var before = fixtureFacts();
      RuntimeException failure = null;
      String key = "non-cent-price-" + sequence++;
      try {
        transaction.execute(status -> service.create(key, request));
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
      RuntimeException captured = failure;
      var after = fixtureFacts();
      long stockCalls = mockingDetails(stock).getInvocations().size();
      long cartWrites =
          mockingDetails(cart).getInvocations().stream()
              .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
              .count();
      checks.add(() -> assertInstanceOf(BizException.class, captured, sample));
      if (captured instanceof BizException controlled) {
        checks.add(() -> assertEquals(500, controlled.getHttpStatus(), sample));
        checks.add(() -> assertEquals(ErrorCodes.INTERNAL, controlled.getCode(), sample));
      }
      checks.add(() -> assertEquals(before, after, sample));
      checks.add(() -> assertEquals(0, stockCalls, sample));
      checks.add(() -> assertEquals(0, cartWrites, sample));
      verify(products).sku(7L);
      if ("later".equals(sample)) verify(products).sku(8L);
    }
    assertAll(checks);
  }

  @Test
  void nonCentSeckillPricesCannotRoundFactsOrCompleteHeldRecovery() throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int sequence = 0;
    for (String sample : List.of("12.345", "0.001", "1E-3")) {
      Map<String, Object> event = timeEvent("202612-non-cent-" + sequence);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      saveRecovery(6200 + sequence++, String.valueOf(event.get("eventId")), event, "QUARANTINED");
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(
                      7L, 9L, "product", "SKU-7", new BigDecimal(sample), Map.of())))
          .when(products)
          .sku(7L);
      clearInvocations(products);
      var before = fixtureFacts();
      IllegalStateException failure = null;
      try {
        service.consumeSeckill(event);
      } catch (IllegalStateException rejected) {
        failure = rejected;
      }
      IllegalStateException captured = failure;
      var after = fixtureFacts();
      checks.add(() -> assertNotNull(captured, sample));
      if (captured != null)
        checks.add(
            () -> assertInstanceOf(IllegalStateException.class, captured.getCause(), sample));
      checks.add(() -> assertEquals(before, after, sample));
      verify(products).sku(7L);
      verifyNoMoreInteractions(products);
    }
    assertAll(checks);
  }

  @Test
  void correctedCentPrecisionRetriesAndLegalRepresentationsKeepExactSqlArithmetic()
      throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    Map<String, Object> retry = timeEvent("202612-corrected-cent");
    retry.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(6300, String.valueOf(retry.get("eventId")), retry, "QUARANTINED");
    var before = fixtureFacts();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("12.345"), Map.of())))
        .when(products)
        .sku(7L);
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(retry));
    assertEquals(before, fixtureFacts());
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("12.3400"), Map.of())))
        .when(products)
        .sku(7L);
    service.consumeSeckill(retry);
    assertEquals("RESOLVED", recoveryRow(String.valueOf(retry.get("eventId"))).get("status"));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM seckill_order_dedup WHERE order_no=?",
            String.class,
            retry.get("orderNo")));
    assertPersistedCreation(
        String.valueOf(retry.get("orderNo")), LocalDateTime.parse("2026-12-31T23:59:59.999"));
    AuthContext.set(7L, "USER");
    int sequence = 0;
    for (String representation : List.of("12.3400", "0.0100", "1E+1", "0.0000")) {
      BigDecimal price = new BigDecimal(representation);
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", price, Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      request.items.get(0).quantity = 2;
      ApiResponse<?> ordinary =
          transaction.execute(status -> service.create("cent-exact-" + representation, request));
      String orderNo = ((OrderVO) ordinary.data).orderNo;
      Map<String, Object> detail =
          fixture.queryForMap(
              "SELECT unit_price, quantity, line_amount FROM mall_order_item_202612 WHERE order_no=?",
              orderNo);
      BigDecimal storedPrice = (BigDecimal) detail.get("unit_price");
      BigDecimal storedLine = (BigDecimal) detail.get("line_amount");
      assertEquals(0, price.compareTo(storedPrice));
      assertEquals(0, storedPrice.multiply(BigDecimal.valueOf(2)).compareTo(storedLine));
      assertEquals(
          0,
          storedLine.compareTo(
              fixture.queryForObject(
                  "SELECT total_amount FROM mall_order_202612 WHERE order_no=?",
                  BigDecimal.class,
                  orderNo)));
      Map<String, Object> event = timeEvent("202612-cent-exact-" + sequence++);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      service.consumeSeckill(event);
      assertEquals(
          0,
          price.compareTo(
              fixture.queryForObject(
                  "SELECT unit_price FROM mall_order_item_202612 WHERE order_no=?",
                  BigDecimal.class,
                  event.get("orderNo"))));
      assertEquals(
          "COMMITTED",
          fixture.queryForObject(
              "SELECT status FROM seckill_order_dedup WHERE order_no=?",
              String.class,
              event.get("orderNo")));
    }
  }

  @Test
  void oversizedProductPricesLinesAndTotalsCannotReachStockOrLeaveIntentFacts() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String fault : List.of("unit", "line", "total")) {
      BigDecimal price =
          new BigDecimal(
              "unit".equals(fault)
                  ? "10000000000000000.00"
                  : "line".equals(fault) ? "5000000000000000.00" : "6000000000000000.00");
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", price, Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      if ("line".equals(fault)) request.items.get(0).quantity = 2;
      else if ("total".equals(fault)) {
        OrderItemDTO second = new OrderItemDTO();
        second.skuId = 8L;
        second.quantity = 1;
        request.items = List.of(request.items.get(0), second);
        doReturn(
                ApiResponse.ok(
                    new ProductClient.SkuView(
                        8L,
                        9L,
                        "second",
                        "SKU-8",
                        new BigDecimal("4000000000000000.00"),
                        Map.of())))
            .when(products)
            .sku(8L);
      }
      clearInvocations(products, stock, cart);
      var before = fixtureFacts();
      RuntimeException failure = null;
      try {
        transaction.execute(status -> service.create("amount-capacity-" + fault, request));
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
      RuntimeException captured = failure;
      var after = fixtureFacts();
      long stockCalls = mockingDetails(stock).getInvocations().size();
      long cartWrites =
          mockingDetails(cart).getInvocations().stream()
              .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
              .count();
      checks.add(() -> assertInstanceOf(BizException.class, captured, fault));
      if (captured instanceof BizException controlled) {
        checks.add(
            () ->
                assertEquals("unit".equals(fault) ? 500 : 400, controlled.getHttpStatus(), fault));
        checks.add(
            () ->
                assertEquals(
                    "unit".equals(fault) ? ErrorCodes.INTERNAL : ErrorCodes.INVALID,
                    controlled.getCode(),
                    fault));
      }
      checks.add(() -> assertEquals(before, after, fault));
      checks.add(() -> assertEquals(0, stockCalls, fault));
      checks.add(() -> assertEquals(0, cartWrites, fault));
      verify(products).sku(7L);
      if ("total".equals(fault)) verify(products).sku(8L);
    }
    assertAll(checks);
  }

  @Test
  void oversizedSeckillPriceRollsBackTheClaimAndKeepsHeldRecovery() throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    Map<String, Object> event = timeEvent("202612-amount-capacity");
    event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(6400, String.valueOf(event.get("eventId")), event, "QUARANTINED");
    var before = fixtureFacts();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("1E+16"), Map.of())))
        .when(products)
        .sku(7L);
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertEquals(before, fixtureFacts());
    verify(products).sku(7L);
    verifyNoInteractions(
        ReflectionTestUtils.getField(service, "stock"),
        ReflectionTestUtils.getField(service, "cart"));
  }

  @Test
  void exactAmountCapacityBoundariesPersistAndAnOversizedSeckillCanRetryAtMaximum()
      throws Exception {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    BigDecimal maximum = new BigDecimal("9999999999999999.99");
    int sequence = 0;
    for (String scenario : List.of("max", "line-boundary", "total-boundary")) {
      BigDecimal price =
          new BigDecimal(
              "line-boundary".equals(scenario)
                  ? "4999999999999999.99"
                  : "total-boundary".equals(scenario)
                      ? "9999999999999999.98"
                      : maximum.toPlainString());
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", price, Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      if ("line-boundary".equals(scenario)) request.items.get(0).quantity = 2;
      else if ("total-boundary".equals(scenario)) {
        OrderItemDTO second = new OrderItemDTO();
        second.skuId = 8L;
        second.quantity = 1;
        request.items = List.of(request.items.get(0), second);
        doReturn(
                ApiResponse.ok(
                    new ProductClient.SkuView(
                        8L, 9L, "second", "SKU-8", new BigDecimal("0.01"), Map.of())))
            .when(products)
            .sku(8L);
      }
      ApiResponse<?> created =
          transaction.execute(status -> service.create("valid-capacity-" + scenario, request));
      assertEquals("0", created.code);
      String orderNo = ((OrderVO) created.data).orderNo;
      BigDecimal total =
          fixture.queryForObject(
              "SELECT total_amount FROM mall_order_202612 WHERE order_no=?",
              BigDecimal.class,
              orderNo);
      if (!"line-boundary".equals(scenario)) assertEquals(maximum, total);
      BigDecimal summed = BigDecimal.ZERO;
      for (Map<String, Object> detail :
          fixture.queryForList(
              "SELECT unit_price, quantity, line_amount FROM mall_order_item_202612 WHERE order_no=?",
              orderNo)) {
        BigDecimal line = (BigDecimal) detail.get("line_amount");
        assertEquals(
            0,
            ((BigDecimal) detail.get("unit_price"))
                .multiply(BigDecimal.valueOf(((Number) detail.get("quantity")).longValue()))
                .compareTo(line));
        summed = summed.add(line);
      }
      assertEquals(0, total.compareTo(summed));
      assertEquals(
          total,
          fixture.queryForObject(
              "SELECT pay_amount FROM mall_order_202612 WHERE order_no=?",
              BigDecimal.class,
              orderNo));
      Map<String, Object> event = timeEvent("202612-valid-capacity-" + sequence++);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "product", "SKU-7", maximum, Map.of())))
          .when(products)
          .sku(7L);
      service.consumeSeckill(event);
      assertEquals(
          maximum,
          fixture.queryForObject(
              "SELECT total_amount FROM mall_order_202612 WHERE order_no=?",
              BigDecimal.class,
              event.get("orderNo")));
    }
    Map<String, Object> retry = timeEvent("202612-capacity-retry");
    retry.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(6500, String.valueOf(retry.get("eventId")), retry, "QUARANTINED");
    var before = fixtureFacts();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, 9L, "product", "SKU-7", new BigDecimal("1E+16"), Map.of())))
        .when(products)
        .sku(7L);
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(retry));
    assertEquals(before, fixtureFacts());
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 9L, "product", "SKU-7", maximum, Map.of())))
        .when(products)
        .sku(7L);
    service.consumeSeckill(retry);
    assertEquals("RESOLVED", recoveryRow(String.valueOf(retry.get("eventId"))).get("status"));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM seckill_order_dedup WHERE order_no=?",
            String.class,
            retry.get("orderNo")));
    assertPersistedCreation(
        String.valueOf(retry.get("orderNo")), LocalDateTime.parse("2026-12-31T23:59:59.999"));
  }

  @Test
  void missingOrOverwideProductMetadataCannotReachStockOrLeaveOrderFacts() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String fault : List.of("null-parent", "null-name", "wide-name", "later-wide")) {
      Long parentId = "null-parent".equals(fault) ? null : Long.valueOf(9L);
      String name =
          "null-name".equals(fault)
              ? null
              : "wide-name".equals(fault) ? "名".repeat(256) : "product";
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, parentId, name, "SKU-7", BigDecimal.TEN, Map.of())))
          .when(products)
          .sku(7L);
      CreateOrderDTO request = normalRequest();
      if ("later-wide".equals(fault)) {
        OrderItemDTO second = new OrderItemDTO();
        second.skuId = 8L;
        second.quantity = 1;
        request.items = List.of(request.items.get(0), second);
        doReturn(
                ApiResponse.ok(
                    new ProductClient.SkuView(
                        8L, 9L, "名".repeat(256), "SKU-8", BigDecimal.TEN, Map.of())))
            .when(products)
            .sku(8L);
      }
      clearInvocations(products, stock, cart);
      var before = fixtureFacts();
      RuntimeException failure = null;
      try {
        transaction.execute(status -> service.create("bad-product-metadata-" + fault, request));
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
      RuntimeException captured = failure;
      var after = fixtureFacts();
      long stockCalls = mockingDetails(stock).getInvocations().size();
      long cartWrites =
          mockingDetails(cart).getInvocations().stream()
              .filter(invocation -> !invocation.getMethod().getName().equals("cleanupSnapshot"))
              .count();
      checks.add(() -> assertInstanceOf(BizException.class, captured, fault));
      if (captured instanceof BizException controlled) {
        checks.add(() -> assertEquals(500, controlled.getHttpStatus(), fault));
        checks.add(() -> assertEquals(ErrorCodes.INTERNAL, controlled.getCode(), fault));
      }
      checks.add(() -> assertEquals(before, after, fault));
      checks.add(() -> assertEquals(0, stockCalls, fault));
      checks.add(() -> assertEquals(0, cartWrites, fault));
      verify(products).sku(7L);
      if ("later-wide".equals(fault)) verify(products).sku(8L);
    }
    assertAll(checks);
  }

  @Test
  void badSeckillMetadataRollsBackClaimsAndDoesNotCompleteHeldRecovery() throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int sequence = 0;
    for (String fault : List.of("null-parent", "null-name", "wide-name")) {
      Map<String, Object> event = timeEvent("202612-bad-metadata-" + fault);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      saveRecovery(6600 + sequence++, String.valueOf(event.get("eventId")), event, "QUARANTINED");
      Long parentId = "null-parent".equals(fault) ? null : Long.valueOf(9L);
      String name =
          "null-name".equals(fault)
              ? null
              : "wide-name".equals(fault) ? "名".repeat(256) : "product";
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, parentId, name, "SKU-7", BigDecimal.TEN, Map.of())))
          .when(products)
          .sku(7L);
      clearInvocations(products);
      var before = fixtureFacts();
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
      var after = fixtureFacts();
      checks.add(() -> assertInstanceOf(IllegalStateException.class, failure.getCause(), fault));
      checks.add(() -> assertEquals(before, after, fault));
      verify(products).sku(7L);
      verifyNoMoreInteractions(products);
    }
    assertAll(checks);
  }

  @Test
  void legalMetadataSurvivesSqlAndTheOriginalRejectedMessageCanRetry() throws Exception {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    int sequence = 0;
    for (String name : List.of("", " ", "尾空格 ", "名".repeat(255), "😀".repeat(255))) {
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(
                      7L, Long.MAX_VALUE, name, "SKU-7", BigDecimal.TEN, null)))
          .when(products)
          .sku(7L);
      ApiResponse<?> ordinary =
          transaction.execute(
              status ->
                  service.create(
                      "legal-metadata-"
                          + name.codePointCount(0, name.length())
                          + "-"
                          + name.length(),
                      normalRequest()));
      assertEquals("0", ordinary.code);
      String ordinaryNo = ((OrderVO) ordinary.data).orderNo;
      Map<String, Object> event = timeEvent("202612-legal-metadata-" + sequence++);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      service.consumeSeckill(event);
      for (String orderNo : List.of(ordinaryNo, String.valueOf(event.get("orderNo")))) {
        String itemTable = "mall_order_item_" + orderNo.substring(0, 6);
        Map<String, Object> detail =
            fixture.queryForMap(
                "SELECT product_id, product_name_snapshot, sku_snapshot FROM "
                    + itemTable
                    + " WHERE order_no=?",
                orderNo);
        assertEquals(Long.MAX_VALUE, ((Number) detail.get("product_id")).longValue());
        assertEquals(name, detail.get("product_name_snapshot"));
        assertTrue(
            new ObjectMapper().readTree(String.valueOf(detail.get("sku_snapshot"))).isNull());
      }
    }
    Map<String, Object> retry = timeEvent("202612-metadata-retry");
    retry.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(6700, String.valueOf(retry.get("eventId")), retry, "QUARANTINED");
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, null, null, "SKU-7", BigDecimal.TEN, null)))
        .when(products)
        .sku(7L);
    var before = fixtureFacts();
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(retry));
    assertEquals(before, fixtureFacts());
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L, Long.MAX_VALUE, "😀".repeat(255), "SKU-7", BigDecimal.TEN, null)))
        .when(products)
        .sku(7L);
    service.consumeSeckill(retry);
    assertEquals("RESOLVED", recoveryRow(String.valueOf(retry.get("eventId"))).get("status"));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM seckill_order_dedup WHERE order_no=?",
            String.class,
            retry.get("orderNo")));
    assertPersistedCreation(
        String.valueOf(retry.get("orderNo")), LocalDateTime.parse("2026-12-31T23:59:59.999"));
  }

  @Test
  void matchingProductIdentityUsesTheCurrentPriceAndSameSkuForStockAndSqlDetails() {
    AuthContext.set(7L, "USER");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L,
                    9L,
                    "current product",
                    "SKU-7",
                    new BigDecimal("12.30"),
                    Map.of("color", "blue"))))
        .when(products)
        .sku(7L);
    CreateOrderDTO request = normalRequest();
    request.items.get(0).quantity = 3;
    ApiResponse<?> response =
        transaction.execute(status -> service.create("matching-product-sku", request));
    assertEquals("0", response.code);
    var reservation = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stock).reserve(reservation.capture());
    assertEquals(7L, reservation.getValue().items().get(0).skuId());
    assertEquals(3, reservation.getValue().items().get(0).quantity());
    Map<String, Object> detail =
        fixture.queryForMap(
            "SELECT sku_id, quantity, unit_price, line_amount, product_name_snapshot FROM mall_order_item_202612");
    assertEquals(7L, ((Number) detail.get("sku_id")).longValue());
    assertEquals(3, ((Number) detail.get("quantity")).intValue());
    assertEquals(new BigDecimal("12.30"), detail.get("unit_price"));
    assertEquals(new BigDecimal("36.90"), detail.get("line_amount"));
    assertEquals("current product", detail.get("product_name_snapshot"));
    assertEquals(
        new BigDecimal("36.90"),
        fixture.queryForObject("SELECT total_amount FROM mall_order_202612", BigDecimal.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='matching-product-sku'",
            String.class));
  }

  @Test
  void cartCleanupWaitsForCommitAndObservesCommittedOrderAndIntent() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    java.util.concurrent.atomic.AtomicInteger visibleOrders =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    java.util.concurrent.atomic.AtomicReference<String> visibleIntent =
        new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(
            invocation -> {
              JdbcTemplate independent =
                  new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, "root", "root"));
              visibleOrders.set(
                  independent.queryForObject(
                      "SELECT COUNT(*) FROM mall_order_202612", Integer.class));
              visibleIntent.set(
                  independent.queryForObject(
                      "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-after-commit'",
                      String.class));
              return ApiResponse.ok(true);
            })
        .when(cart)
        .cleanupItem(anyLong(), anyLong(), any());
    java.util.concurrent.atomic.AtomicInteger callsBeforeCommit =
        new java.util.concurrent.atomic.AtomicInteger(-1);
    ApiResponse<?> result =
        transaction.execute(
            status -> {
              ApiResponse<?> created = service.create("cart-after-commit", normalRequest());
              callsBeforeCommit.set(
                  (int)
                      mockingDetails(cart).getInvocations().stream()
                          .filter(
                              invocation ->
                                  !invocation.getMethod().getName().equals("cleanupSnapshot"))
                          .count());
              return created;
            });
    assertAll(
        () -> assertEquals("0", result.code),
        () -> assertEquals(0, callsBeforeCommit.get()),
        () -> assertEquals(1, visibleOrders.get()),
        () -> assertEquals("COMMITTED", visibleIntent.get()));
    verify(cart).cleanupItem(eq(7L), eq(7L), any());
  }

  @Test
  void cleanupUsesPrecommitProofAndOriginalUserWithoutReadingTheChangedCartAgain() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    CartCleanupSnapshotDTO original =
        new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":1,\"addedAt\":\"original\"}");
    CartCleanupSnapshotDTO readded =
        new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":4,\"addedAt\":\"readded\"}");
    var current = new java.util.concurrent.atomic.AtomicReference<CartCleanupSnapshotDTO>(original);
    doAnswer(invocation -> ApiResponse.ok(current.get())).when(cart).cleanupSnapshot(7L, 7L);
    doAnswer(
            invocation -> {
              CartCleanupSnapshotDTO expected = invocation.getArgument(2);
              boolean deleted = current.compareAndSet(expected, null);
              return ApiResponse.ok(deleted);
            })
        .when(cart)
        .cleanupItem(anyLong(), anyLong(), any());
    CreateOrderDTO request = normalRequest();
    ApiResponse<?> created =
        transaction.execute(
            status -> {
              ApiResponse<?> response = service.create("cart-proof-before-commit", request);
              verify(cart).cleanupSnapshot(7L, 7L);
              verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
              current.set(readded);
              request.items.get(0).skuId = 999L;
              AuthContext.set(99L, "USER");
              return response;
            });
    assertEquals("0", created.code);
    assertEquals(readded, current.get());
    verify(cart).cleanupSnapshot(7L, 7L);
    verify(cart).cleanupItem(7L, 7L, original);
    verify(cart, never()).deleteItem(anyLong(), anyLong());
    verifyNoMoreInteractions(cart);
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-proof-before-commit'",
            String.class));
  }

  @Test
  void cartChangesDuringReserveKeepTheirNewItemAfterTheOrderReallyCommits() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartCleanupSnapshotDTO original =
        new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":1,\"addedAt\":\"original\"}");
    CartCleanupSnapshotDTO readded =
        new CartCleanupSnapshotDTO(
            7L, "{\"skuId\":7,\"quantity\":1,\"addedAt\":\"readded-during-reserve\"}");
    var current = new java.util.concurrent.atomic.AtomicReference<CartCleanupSnapshotDTO>(original);
    doAnswer(invocation -> ApiResponse.ok(current.get())).when(cart).cleanupSnapshot(7L, 7L);
    doAnswer(
            invocation -> {
              current.set(readded);
              StockClient.Reservation reservation = invocation.getArgument(0);
              return ApiResponse.ok(Map.of("orderNo", reservation.orderNo(), "status", "RESERVED"));
            })
        .when(stock)
        .reserve(any());
    doAnswer(
            invocation -> {
              CartCleanupSnapshotDTO expected = invocation.getArgument(2);
              return ApiResponse.ok(current.compareAndSet(expected, null));
            })
        .when(cart)
        .cleanupItem(anyLong(), anyLong(), any());
    assertEquals("0", service.create("cart-changed-during-reserve", normalRequest()).code);
    assertEquals(readded, current.get());
    var ordered = inOrder(cart, stock);
    ordered.verify(cart).cleanupSnapshot(7L, 7L);
    ordered.verify(stock).reserve(any());
    ordered.verify(cart).cleanupItem(7L, 7L, original);
    verifyNoMoreInteractions(cart);
    verify(stock, never()).rollback(anyString());
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-changed-during-reserve'",
            String.class));
  }

  @Test
  void partiallyPurchasedCartQuantityDoesNotGetDeletedAndStillCommitsTheOrder() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    doReturn(ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":5}")))
        .when(cart)
        .cleanupSnapshot(7L, 7L);
    assertEquals("0", service.create("cart-partially-purchased", normalRequest()).code);
    verify(cart).cleanupSnapshot(7L, 7L);
    verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
    verify(cart, never()).deleteItem(anyLong(), anyLong());
    verifyNoMoreInteractions(cart);
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-partially-purchased'",
            String.class));
  }

  @Test
  void outerRollbackNeverDeletesCartOrPretendsTheOrderCommitted() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    transaction.execute(
        status -> {
          assertEquals("0", service.create("cart-rollback", normalRequest()).code);
          status.setRollbackOnly();
          return null;
        });
    assertFailedCreateHasNoCommittedArtifacts();
    assertEquals(
        "RESERVED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-rollback'",
            String.class));
    verify(cart).cleanupSnapshot(7L, 7L);
    verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
    verify(cart, never()).deleteItem(anyLong(), anyLong());
    verifyNoMoreInteractions(cart);
    verify(stock, never()).rollback(anyString());
  }

  @Test
  void lateLocalReadFailureRollsBackWithoutRemovingCartItems() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    OrderSqlMapper observedOrders = spy(new OrderSqlMapper(fixture));
    org.springframework.dao.DataAccessResourceFailureException original =
        new org.springframework.dao.DataAccessResourceFailureException(
            "late order read unavailable");
    doThrow(original).when(observedOrders).findOrder(anyString(), anyString());
    ReflectionTestUtils.setField(service, "orderSqlMapper", observedOrders);
    doReturn(ApiResponse.ok("rolled back")).when(stock).rollback(anyString());
    doAnswer(invocation -> ApiResponse.ok(recoveryStatus(invocation.getArgument(0), "ROLLED_BACK")))
        .when(stock)
        .reservation(anyString());
    RuntimeException returned =
        assertThrows(
            RuntimeException.class,
            () -> service.create("cart-late-read-failure", normalRequest()));
    assertSame(original, returned);
    assertFailedCreateHasNoCommittedArtifacts();
    assertEquals(
        "RELEASED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE idempotency_key='cart-late-read-failure'",
            String.class));
    verify(stock).rollback(anyString());
    verify(stock).reservation(anyString());
    verify(cart).cleanupSnapshot(7L, 7L);
    verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
    verify(cart, never()).deleteItem(anyLong(), anyLong());
    verifyNoMoreInteractions(cart);
  }

  @Test
  void postCommitCartFailuresDoNotCompensateOrdersAndLaterSkuStillCleansUp() {
    AuthContext.set(7L, "USER");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    when(products.sku(8L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(8L, 9L, "商品8", "SKU-8", BigDecimal.TEN, Map.of())));
    doReturn(ApiResponse.ok("rolled back")).when(stock).rollback(anyString());
    doAnswer(invocation -> ApiResponse.ok(recoveryStatus(invocation.getArgument(0), "ROLLED_BACK")))
        .when(stock)
        .reservation(anyString());
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String failure : List.of("null", "null-data", "nonzero", "runtime")) {
      resetReservationSuccessFixture();
      clearInvocations(stock, cart);
      doAnswer(
              invocation -> {
                Long skuId = invocation.getArgument(0);
                if (skuId.equals(8L)) return ApiResponse.ok(true);
                return switch (failure) {
                  case "null" -> null;
                  case "null-data" -> ApiResponse.ok(null);
                  case "nonzero" -> ApiResponse.error("503", "cart unavailable");
                  case "runtime" -> throw new IllegalStateException("cart unavailable");
                  default -> throw new AssertionError(failure);
                };
              })
          .when(cart)
          .cleanupItem(anyLong(), anyLong(), any());
      CreateOrderDTO request = normalRequest();
      OrderItemDTO secondItem = new OrderItemDTO();
      secondItem.skuId = 8L;
      secondItem.quantity = 1;
      request.items = List.of(request.items.get(0), secondItem);
      RuntimeException returnedFailure = null;
      ApiResponse<?> returned = null;
      try {
        returned = service.create("cart-failure-" + failure, request);
      } catch (RuntimeException failed) {
        returnedFailure = failed;
      }
      RuntimeException observedFailure = returnedFailure;
      ApiResponse<?> observedResult = returned;
      Integer orders =
          fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class);
      String intentStatus =
          fixture.queryForObject(
              "SELECT status FROM order_reservation_intent WHERE idempotency_key=?",
              String.class,
              "cart-failure-" + failure);
      int secondCalls =
          (int)
              mockingDetails(cart).getInvocations().stream()
                  .filter(
                      invocation ->
                          invocation.getMethod().getName().equals("cleanupItem")
                              && Long.valueOf(8L).equals(invocation.getArgument(0)))
                  .count();
      int stockRollbacks =
          (int)
              mockingDetails(stock).getInvocations().stream()
                  .filter(invocation -> invocation.getMethod().getName().equals("rollback"))
                  .count();
      checks.add(() -> assertNull(observedFailure, failure));
      checks.add(() -> assertNotNull(observedResult, failure));
      if (observedResult != null) checks.add(() -> assertEquals("0", observedResult.code, failure));
      checks.add(() -> assertEquals(1, orders, failure));
      checks.add(() -> assertEquals("COMMITTED", intentStatus, failure));
      checks.add(() -> assertEquals(1, secondCalls, failure));
      checks.add(() -> assertEquals(0, stockRollbacks, failure));
    }
    assertAll(checks);
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
      verifyNoInteractions(stock);
      verify(cart).cleanupSnapshot(7L, 7L);
      verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
      verify(cart, never()).deleteItem(anyLong(), anyLong());
      verifyNoMoreInteractions(cart);
      assertEquals(before, fixtureFacts());
    }
    clearInvocations(stock, products, users, cart);
    ApiResponse<?> replay =
        transaction.execute(status -> service.create("Key-Case", normalRequest()));
    assertEquals("0", replay.code);
    assertEquals(((OrderVO) original.data).orderNo, ((OrderVO) replay.data).orderNo);
    verifyNoInteractions(stock);
    verify(cart).cleanupSnapshot(7L, 7L);
    verify(cart, never()).cleanupItem(anyLong(), anyLong(), any());
    verify(cart, never()).deleteItem(anyLong(), anyLong());
    verifyNoMoreInteractions(cart);
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
  void untrustedSeckillProductResponsesCannotCommitOrdersOrResolveHeldRecoveryRows() throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    int sequence = 0;
    for (String fault :
        List.of(
            "wrong-sku", "missing-sku", "bad-code", "null-code", "null-envelope", "null-data")) {
      Map<String, Object> event = timeEvent("202612-product-" + fault);
      event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
      saveRecovery(5000 + sequence++, String.valueOf(event.get("eventId")), event, "QUARANTINED");
      Long responseSku = 7L;
      if ("wrong-sku".equals(fault)) responseSku = 8L;
      else if ("missing-sku".equals(fault)) responseSku = null;
      ApiResponse<ProductClient.SkuView> response =
          ApiResponse.ok(
              new ProductClient.SkuView(
                  responseSku,
                  9L,
                  "untrusted seckill product",
                  "SKU-OTHER",
                  new BigDecimal("77.70"),
                  Map.of()));
      if ("bad-code".equals(fault)) response.code = "BROKEN";
      else if ("null-code".equals(fault)) response.code = null;
      else if ("null-envelope".equals(fault)) response = null;
      else if ("null-data".equals(fault)) response = ApiResponse.ok(null);
      doReturn(response).when(products).sku(7L);
      clearInvocations(products);
      var before = fixtureFacts();
      IllegalStateException failure = null;
      try {
        service.consumeSeckill(event);
      } catch (IllegalStateException rejected) {
        failure = rejected;
      }
      IllegalStateException captured = failure;
      var after = fixtureFacts();
      checks.add(() -> assertNotNull(captured, fault));
      if (captured != null)
        checks.add(() -> assertInstanceOf(IllegalStateException.class, captured.getCause(), fault));
      checks.add(() -> assertEquals(before, after, fault));
      verify(products).sku(7L);
      verifyNoMoreInteractions(products);
    }
    assertAll(checks);
    verifyNoInteractions(stock, cart);
  }

  @Test
  void correctedProductResponseRetriesTheSameSeckillMessageThenCommittedReplaySkipsProduct()
      throws Exception {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    Map<String, Object> event = timeEvent("202612-corrected-product");
    event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    saveRecovery(5100, String.valueOf(event.get("eventId")), event, "QUARANTINED");
    var before = fixtureFacts();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    8L, 9L, "wrong product", "SKU-8", new BigDecimal("77.70"), Map.of())))
        .when(products)
        .sku(7L);
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    assertEquals(before, fixtureFacts());
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(
                    7L,
                    9L,
                    "current product",
                    "SKU-7",
                    new BigDecimal("12.30"),
                    Map.of("color", "blue"))))
        .when(products)
        .sku(7L);
    service.consumeSeckill(event);
    String orderNo = String.valueOf(event.get("orderNo"));
    Map<String, Object> detail =
        fixture.queryForMap(
            "SELECT sku_id, quantity, unit_price, line_amount FROM mall_order_item_202612 WHERE order_no=?",
            orderNo);
    assertEquals(7L, ((Number) detail.get("sku_id")).longValue());
    assertEquals(1, ((Number) detail.get("quantity")).intValue());
    assertEquals(new BigDecimal("12.30"), detail.get("unit_price"));
    assertEquals(new BigDecimal("12.30"), detail.get("line_amount"));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM seckill_order_dedup WHERE order_no=?", String.class, orderNo));
    assertEquals("RESOLVED", recoveryRow(String.valueOf(event.get("eventId"))).get("status"));
    assertPersistedCreation(orderNo, LocalDateTime.parse("2026-12-31T23:59:59.999"));
    var committed = fixtureFacts();
    clearInvocations(products);
    doThrow(new IllegalStateException("Product must not be read for committed replay"))
        .when(products)
        .sku(anyLong());
    event.remove("occurredAt");
    service.consumeSeckill(event);
    verifyNoInteractions(products);
    assertEquals(committed, fixtureFacts());
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
    doAnswer(
            invocation -> {
              StockClient.Reservation request = invocation.getArgument(0);
              return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
            })
        .when(stock)
        .reserve(any());
    when(cart.cleanupSnapshot(anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              Long skuId = invocation.getArgument(0);
              return ApiResponse.ok(
                  new CartCleanupSnapshotDTO(
                      skuId, "{\"skuId\":" + skuId + ",\"quantity\":1,\"checked\":true}"));
            });
    when(cart.cleanupItem(anyLong(), anyLong(), any())).thenReturn(ApiResponse.ok(true));
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
