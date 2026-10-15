package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import feign.FeignException;
import feign.Request;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pay Legacy退款账本确认真实Mapper验收；User/Order仅使用受控远程替身。 */
class PayLegacyRefundLedgerMySqlIT {
  /** 原cloudmall_pay只读连接。 */
  private static JdbcTemplate source;

  /** 原库指纹与自有库DDL的有界连接池，避免逐SQL短连接耗尽客户端临时端口。 */
  private static com.zaxxer.hikari.HikariDataSource sourcePool;

  /** 全类复用的自有库连接池；并发租约演练最多六个连接，结束必须关闭。 */
  private static com.zaxxer.hikari.HikariDataSource ownedPool;

  /** 原库完整表清单与checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 本测试唯一owned schema。 */
  private static String database;

  /** owned库夹具连接。 */
  private JdbcTemplate fixture;

  /** 真实Pay意图Mapper。 */
  private PayDebitIntentMapper intents;

  /** 真实Pay记录Mapper。 */
  private PayRecordMapper records;

  /** 被测一致性服务。 */
  private PayConsistencyService consistency;

  /** 仅派发镜像验收使用的owned真实Mapper。 */
  private com.cloudmall.pay.mapper.PayTccDispatchMapper dispatchMirrorMapper;

  /** execution观察验收专用owned真实Mapper。 */
  private com.cloudmall.pay.mapper.PayTccExecutionMapper executionObservationMapper;

  /** delivery请求身份验收使用的owned真实Mapper。 */
  private com.cloudmall.pay.mapper.PayTccDeliveryMapper deliveryIdentityMapper;

  /** 取消恢复身份验收使用的owned实际Mapper。 */
  private com.cloudmall.pay.mapper.PayTccCancelMapper cancelRecoveryIdentityMapper;

  /** Fence身份验收的owned真实读取入口。 */
  private com.cloudmall.pay.mapper.PayTccFenceMapper fenceIdentityMapper;

  /** 真实receipt访问的接口委托计数器，仅回滚owner证据验收使用。 */
  private com.cloudmall.pay.mapper.PayTccReceiptMapper receiptRollbackMapper;

  @BeforeAll
  static void setupDatabase() {
    String url = System.getProperty("cloudmall.pay.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("IT需要指向cloudmall_pay的cloudmall.pay.mysql.test.url");
    String username = System.getProperty("cloudmall.mysql.test.username", "root");
    String password = System.getProperty("cloudmall.mysql.test.password", "root");
    sourcePool = boundedPool(url, username, password, "cm16t138-source-", 2);
    source = new JdbcTemplate(sourcePool);
    sourceFingerprint = fingerprint(source, "cloudmall_pay");
    database = "cm16t77_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    ownedPool =
        boundedPool(
            url.replace("/cloudmall_pay", "/" + database),
            username,
            password,
            "cm16t138-owned-",
            6);
  }

  @AfterAll
  static void cleanupDatabase() {
    try {
      if (source == null || database == null || !database.matches("cm16t77_[0-9a-f]{32}")) return;
      assertEquals(0, ownedPool.getHikariPoolMXBean().getActiveConnections());
      assertEquals(0, sourcePool.getHikariPoolMXBean().getActiveConnections());
      assertEquals(sourceFingerprint, fingerprint(source, "cloudmall_pay"));
      System.out.println("CM16T77 original Pay tables/checksums unchanged");
    } finally {
      try {
        if (ownedPool != null) ownedPool.close();
        if (source != null && database != null && database.matches("cm16t77_[0-9a-f]{32}")) {
          source.execute("DROP DATABASE IF EXISTS " + database);
          assertEquals(
              0,
              source.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                  Integer.class,
                  database));
          System.out.println("CM16T77 fixture dropped: " + database);
        }
      } finally {
        if (sourcePool != null) sourcePool.close();
        assertTrue(ownedPool == null || ownedPool.isClosed());
        assertTrue(sourcePool == null || sourcePool.isClosed());
        System.out.println("CM16T138 bounded Pay JDBC pools closed");
      }
    }
  }

  /** 每类只创建两池，保原事务/数据源语义，不新增测试依赖或固定监听。 */
  private static com.zaxxer.hikari.HikariDataSource boundedPool(
      String url, String username, String password, String prefix, int maximumConnections) {
    var configuration = new com.zaxxer.hikari.HikariConfig();
    configuration.setJdbcUrl(url);
    configuration.setUsername(username);
    configuration.setPassword(password);
    configuration.setMaximumPoolSize(maximumConnections);
    configuration.setMinimumIdle(1);
    configuration.setPoolName(prefix + UUID.randomUUID());
    return new com.zaxxer.hikari.HikariDataSource(configuration);
  }

  @BeforeEach
  void prepareOwnedPay() {
    DataSource owned = ownedPool;
    fixture = new JdbcTemplate(owned);
    fixture.execute("DROP TABLE IF EXISTS pay_debit_intent");
    fixture.execute("DROP TABLE IF EXISTS pay_record");
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      String definition =
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table");
      fixture.execute(definition);
    }
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(PayRecordMapper.class);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(owned);
    factory.setConfiguration(configuration);
    SqlSessionTemplate session;
    try {
      session = new SqlSessionTemplate(factory.getObject());
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    records = session.getMapper(PayRecordMapper.class);
    intents = new PayDebitIntentMapper(fixture);
    intents.ensureTable();
    consistency =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intents,
            records,
            mock(UserClient.class),
            mock(OrderClient.class),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                fixture.getDataSource()));
  }

  @Test
  void markerRefundBadLedgerResponsesStayOrderSyncUnknownWithoutPayTerminalWrite() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant :
        List.of("partial", "wrong-key", "wrong-state", "null-body", "not-found", "server-error"))
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            UserClient user = mock(UserClient.class);
            OrderClient orders = cancellingOrders();
            UserClient.BalanceOperationView debit =
                new UserClient.BalanceOperationView(
                    "pay-1", "DEBIT", "APPLIED", "10.00", "refund-custom");
            when(user.operation(7L, "pay-1", 7L)).thenReturn(ApiResponse.ok(debit));
            stubRefundOperation(user, variant, "refund-custom", "5.00");
            consistency = newService(user, orders);
            String result = consistency.process("pay-1");
            assertEquals("ORDER_SYNC_UNKNOWN", result);
            assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
            assertNotEquals("REFUNDED", paymentStatus());
            assertNotEquals("FAILED", paymentStatus());
            assertTrue(intentAttempts() > 0);
            assertNull(intentLeaseOwner());
            assertNotNull(intentNextRetry());
            assertTrue(retryErrorPresent());
            verify(user, never()).debit(any(), any(), any());
            verify(user, never()).refund(any(), any(), any());
          });
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  @Test
  void postRefundAckStillNeedsFixedLedgerProofAndPersistsPendingOnFailure() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant : List.of("wrong-key", "partial", "missing", "fault", "wrong-ack")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            UserClient user = mock(UserClient.class);
            when(user.operation(7L, "pay-1", 7L))
                .thenReturn(
                    ApiResponse.ok(
                        new UserClient.BalanceOperationView(
                            "pay-1", "DEBIT", "APPLIED", "10.00", null)));
            when(user.refund(eq(7L), eq(7L), any()))
                .thenReturn(
                    ApiResponse.ok(
                        new UserClient.BalanceView(
                            "wrong-ack".equals(variant) ? "other-refund" : "refund:pay-1",
                            "APPLIED",
                            "100.00")));
            stubRefundOperation(user, variant, "refund:pay-1", "5.00");
            consistency = newService(user, cancellingOrders());
            String result = consistency.process("pay-1");
            assertEquals("REFUND_PENDING", result);
            assertEquals("REFUND_PENDING", intentStatus());
            assertEquals("REFUND_PENDING", paymentStatus());
            assertTrue(intentAttempts() > 0);
            assertNull(intentLeaseOwner());
            assertNotNull(intentNextRetry());
            verify(user, never()).debit(any(), any(), any());
            verify(user, times(1)).refund(eq(7L), eq(7L), any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  @Test
  void validMarkerAndPostRefundLedgerProofsReachRefundedWithoutDuplicateRefund() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient markerUser = mock(UserClient.class);
    when(markerUser.operation(7L, "pay-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    "pay-1", "DEBIT", "APPLIED", "10.00", "custom-refund")));
    when(markerUser.operation(7L, "custom-refund", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    "custom-refund", "REFUND", "APPLIED", "10.000", null)));
    String markerResult = consistencyWith(markerUser, cancellingOrders()).process("pay-1");
    assertEquals("REFUNDED", markerResult);
    verify(markerUser, times(2)).operation(7L, "custom-refund", 7L);
    assertEquals("REFUNDED", paymentStatus());
    assertEquals("REFUNDED", intentStatus());
    var markerFacts = payFacts();

    seedIntentAndPayment("PENDING", "INIT");
    UserClient postUser = mock(UserClient.class);
    when(postUser.operation(7L, "pay-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", null)));
    when(postUser.operation(7L, "refund:pay-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    "refund:pay-1", "REFUND", "APPLIED", "10.000", null)));
    when(postUser.refund(eq(7L), eq(7L), any()))
        .thenReturn(
            ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));
    assertEquals("REFUNDED", consistencyWith(postUser, cancellingOrders()).process("pay-1"));
    verify(postUser, times(2)).operation(7L, "refund:pay-1", 7L);
    assertEquals(3, postUserOperationCalls(postUser));
    assertEquals("REFUNDED", paymentStatus());
    assertEquals("REFUNDED", intentStatus());
    verify(markerUser, never()).refund(any(), any(), any());
    verify(markerUser, never()).debit(any(), any(), any());
    verify(postUser, times(1)).refund(eq(7L), eq(7L), any());
    verify(postUser, never()).debit(any(), any(), any());
    assertEquals(markerFacts.get(0).size(), payFacts().get(0).size());
  }

  /** 同一service重领后，迟到旧retry不能改新owner的完整意图或清新租约。 */
  @Test
  void expiredSameInstanceAttemptCannotReleaseTheReclaimedLease() throws Exception {
    seedIntentAndPayment("PENDING", "INIT");
    var rejectedClaim = new java.util.concurrent.atomic.AtomicReference<String>();
    var diagnosticIntents = org.mockito.Mockito.spy(intents);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              int affectedRows = (Integer) invocation.callRealMethod();
              if (affectedRows == 0) {
                var facts =
                    fixture.queryForMap(
                        "SELECT *,NOW(3) AS database_now FROM pay_debit_intent WHERE"
                            + " pay_no='pay-1'");
                facts.put("application_now", invocation.getArgument(3));
                rejectedClaim.set(facts.toString());
              }
              return affectedRows;
            })
        .when(diagnosticIntents)
        .claim(any(), any(), any(), any());
    intents = diagnosticIntents;
    UserClient user = mock(UserClient.class);
    var firstReading = new java.util.concurrent.CountDownLatch(1);
    var secondReading = new java.util.concurrent.CountDownLatch(1);
    var releaseFirst = new java.util.concurrent.CountDownLatch(1);
    var releaseSecond = new java.util.concurrent.CountDownLatch(1);
    var readers = new java.util.concurrent.atomic.AtomicInteger();
    when(user.operation(7L, "pay-1", 7L))
        .thenAnswer(
            invocation -> {
              int attempt = readers.incrementAndGet();
              if (attempt == 1) {
                firstReading.countDown();
                assertTrue(releaseFirst.await(15, java.util.concurrent.TimeUnit.SECONDS));
              } else {
                secondReading.countDown();
                assertTrue(releaseSecond.await(15, java.util.concurrent.TimeUnit.SECONDS));
              }
              throw new IllegalStateException("owned-attempt-read-failure-" + attempt);
            });
    PayConsistencyService sameInstance = newService(user, cancellingOrders());
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> sameInstance.process("pay-1"));
      assertTrue(firstReading.await(10, java.util.concurrent.TimeUnit.SECONDS));
      String firstToken = intentLeaseOwner();
      fixture.update(
          "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1"
              + " SECOND,next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE pay_no='pay-1'");
      var second = workers.submit(() -> sameInstance.process("pay-1"));
      assertTrue(
          secondReading.await(10, java.util.concurrent.TimeUnit.SECONDS),
          () -> "second claim facts: " + rejectedClaim.get());
      var reclaimed = fixture.queryForMap("SELECT * FROM pay_debit_intent WHERE pay_no='pay-1'");
      var paymentBefore = fixture.queryForMap("SELECT * FROM pay_record WHERE pay_no='pay-1'");
      releaseFirst.countDown();
      assertEquals("INIT", first.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          reclaimed, fixture.queryForMap("SELECT * FROM pay_debit_intent WHERE pay_no='pay-1'"));
      assertNotEquals(firstToken, reclaimed.get("lease_owner"));
      assertEquals(
          paymentBefore, fixture.queryForMap("SELECT * FROM pay_record WHERE pay_no='pay-1'"));
      releaseSecond.countDown();
      assertEquals("INIT", second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(intentLeaseOwner());
      assertEquals(2, intentAttempts());
      assertTrue(retryErrorPresent());
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
    } finally {
      releaseFirst.countDown();
      releaseSecond.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** 原金融事实有效也不能让失去owner/deadline/mode资格的执行写支付终态。 */
  @Test
  void staleLegacyTerminalWriterCannotFinalizeAfterOwnershipChanges() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      for (String mutation : List.of("reclaimed", "expired", "mode-drift")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              UserClient user = mock(UserClient.class);
              String debitState = "FAILED".equals(terminal) ? "REJECTED" : "APPLIED";
              when(user.operation(7L, "pay-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "pay-1",
                              "DEBIT",
                              debitState,
                              "10.00",
                              "REFUNDED".equals(terminal) ? "custom-refund" : null)));
              when(user.operation(7L, "custom-refund", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "custom-refund", "REFUND", "APPLIED", "10.00", null)));
              OrderClient orders = mock(OrderClient.class);
              var changedIntent =
                  new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
              when(orders.paymentState("order-1", 7L))
                  .thenAnswer(
                      invocation -> {
                        if ("reclaimed".equals(mutation)) {
                          fixture.update(
                              "UPDATE pay_debit_intent SET"
                                  + " lease_owner='replacement-owner',lease_until=NOW(3)+INTERVAL"
                                  + " 30 SECOND WHERE pay_no='pay-1'");
                        } else if ("expired".equals(mutation)) {
                          fixture.update(
                              "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND"
                                  + " WHERE pay_no='pay-1'");
                        } else {
                          fixture.update(
                              "UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
                        }
                        changedIntent.set(
                            fixture.queryForMap(
                                "SELECT * FROM pay_debit_intent WHERE pay_no='pay-1'"));
                        return ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                "SUCCESS".equals(terminal) ? "PAID" : "CANCELLED",
                                BigDecimal.TEN,
                                7L,
                                "SUCCEEDED"));
                      });
              String result = newService(user, orders).process("pay-1");
              assertNotEquals(terminal, result, terminal + ":" + mutation);
              assertNotEquals(terminal, paymentStatus());
              assertNotEquals(terminal, intentStatus());
              if ("reclaimed".equals(mutation)) {
                assertEquals(
                    changedIntent.get(),
                    fixture.queryForMap("SELECT * FROM pay_debit_intent WHERE pay_no='pay-1'"));
              }
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** owner与deadline精确边界、模式和活动状态直接验生产终态SQL，三方向healthy SET保持。 */
  @Test
  void ownedTerminalSqlHonoursOwnerDeadlineModeAndActiveStatus() {
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      for (String scenario :
          List.of(
              "valid",
              "equal",
              "expired",
              "null-deadline",
              "other-owner",
              "owner-alias",
              "record-mode",
              "intent-mode",
              "inactive")) {
        seedIntentAndPayment("REFUNDED".equals(terminal) ? "REFUND_PENDING" : "PENDING", "INIT");
        var now =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String token = "owned-terminal-token";
        assertEquals(1, intents.claim("pay-1", token, now.plusSeconds(30), now));
        String sentToken = token;
        switch (scenario) {
          case "equal" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'", now);
          case "expired" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
                  now.minusNanos(1000000));
          case "null-deadline" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=NULL WHERE pay_no='pay-1'");
          case "other-owner" -> sentToken = "other";
          case "owner-alias" -> sentToken = token.toUpperCase(java.util.Locale.ROOT);
          case "record-mode" ->
              fixture.update("UPDATE pay_record SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "intent-mode" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "inactive" ->
              fixture.update("UPDATE pay_debit_intent SET status='SUCCESS' WHERE pay_no='pay-1'");
          default -> {}
        }
        var before = payFacts();
        int changed =
            switch (terminal) {
              case "SUCCESS" -> records.markOwnedLegacySuccess("pay-1", sentToken, now, now);
              case "REFUNDED" -> records.markOwnedLegacyRefunded("pay-1", sentToken, now);
              default -> records.markOwnedLegacyFailed("pay-1", sentToken, now);
            };
        assertEquals("valid".equals(scenario) ? 1 : 0, changed, terminal + ":" + scenario);
        if (!"valid".equals(scenario)) assertEquals(before, payFacts());
        else {
          assertEquals(terminal, paymentStatus());
          assertEquals(1, intents.finishOwnedLegacy("pay-1", token, terminal, now));
          assertEquals(terminal, intentStatus());
          assertNull(intentLeaseOwner());
        }
      }
    }
    for (String scenario : List.of("equal", "expired", "other-owner", "mode", "inactive")) {
      seedIntentAndPayment("PENDING", "INIT");
      var now =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
              .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      assertEquals(1, intents.claim("pay-1", "intent-terminal-token", now.plusSeconds(30), now));
      String token = "intent-terminal-token";
      if ("equal".equals(scenario) || "expired".equals(scenario))
        fixture.update(
            "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
            "equal".equals(scenario) ? now : now.minusNanos(1000000));
      if ("other-owner".equals(scenario)) token = "other";
      if ("mode".equals(scenario))
        fixture.update("UPDATE pay_debit_intent SET debit_mode='TCC' WHERE pay_no='pay-1'");
      if ("inactive".equals(scenario))
        fixture.update("UPDATE pay_debit_intent SET status='SUCCESS' WHERE pay_no='pay-1'");
      var before = payFacts();
      assertEquals(0, intents.finishOwnedLegacy("pay-1", token, "SUCCESS", now));
      assertEquals(before, payFacts());
    }
  }

  /** 订单读期间失去资格的旧执行不应改中间状态或发出三个远端写RPC。 */
  @Test
  void staleIntermediateActorMustNotMutateOrIssueWritingRpc() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("debit", "paid", "refund")) {
      for (String mutation : List.of("reclaimed", "expired", "mode-drift")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              UserClient user = mock(UserClient.class);
              if ("debit".equals(path)) stubRefundOperation(user, "not-found", "pay-1", "10.00");
              else
                when(user.operation(7L, "pay-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1", "DEBIT", "APPLIED", "10.00", null)));
              when(user.debit(eq(7L), eq(7L), any()))
                  .thenReturn(
                      ApiResponse.ok(new UserClient.BalanceView("pay-1", "APPLIED", "90.00")));
              when(user.refund(eq(7L), eq(7L), any()))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));
              when(user.operation(7L, "refund:pay-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "refund:pay-1", "REFUND", "APPLIED", "10.00", null)));
              OrderClient orders = mock(OrderClient.class);
              var changed = new java.util.concurrent.atomic.AtomicBoolean();
              var afterChange =
                  new java.util.concurrent.atomic.AtomicReference<
                      List<List<Map<String, Object>>>>();
              when(orders.paymentState("order-1", 7L))
                  .thenAnswer(
                      invocation -> {
                        if (changed.compareAndSet(false, true)) {
                          if ("reclaimed".equals(mutation))
                            fixture.update(
                                "UPDATE pay_debit_intent SET"
                                    + " lease_owner='replacement-owner',lease_until=NOW(3)+INTERVAL"
                                    + " 30 SECOND WHERE pay_no='pay-1'");
                          else if ("expired".equals(mutation))
                            fixture.update(
                                "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND"
                                    + " WHERE pay_no='pay-1'");
                          else
                            fixture.update(
                                "UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
                          afterChange.set(payFacts());
                        }
                        return ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                "refund".equals(path) ? "CANCELLED" : "PENDING_PAYMENT",
                                BigDecimal.TEN,
                                7L,
                                "SUCCEEDED"));
                      });
              newService(user, orders).process("pay-1");
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, never()).paid(any(), any());
              assertEquals(afterChange.get(), payFacts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 五个生产中间写/资格方法直接验证owner、期限、模式及活动状态边界。 */
  @Test
  void intermediateOwnershipSqlRejectsInvalidEligibilityAndKeepsHealthyRetry() {
    for (String operation : List.of("order", "refund", "stage", "retry", "lease")) {
      for (String scenario :
          List.of(
              "valid",
              "equal",
              "expired",
              "null-deadline",
              "other-owner",
              "owner-alias",
              "record-mode",
              "intent-mode",
              "inactive")) {
        seedIntentAndPayment("PENDING", "INIT");
        var now =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String token = "intermediate-owner";
        assertEquals(1, intents.claim("pay-1", token, now.plusSeconds(30), now));
        String sentToken = token;
        switch (scenario) {
          case "equal" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'", now);
          case "expired" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
                  now.minusNanos(1000000));
          case "null-deadline" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=NULL WHERE pay_no='pay-1'");
          case "other-owner" -> sentToken = "other";
          case "owner-alias" -> sentToken = token.toUpperCase(java.util.Locale.ROOT);
          case "record-mode" ->
              fixture.update("UPDATE pay_record SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "intent-mode" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "inactive" ->
              fixture.update("UPDATE pay_debit_intent SET status='SUCCESS' WHERE pay_no='pay-1'");
          default -> {}
        }
        var before = payFacts();
        int changed =
            switch (operation) {
              case "order" -> records.markOwnedLegacyOrderUnknown("pay-1", sentToken, now);
              case "refund" -> records.markOwnedLegacyRefundPending("pay-1", sentToken, now);
              case "stage" -> intents.updateOwnedLegacyStatus("pay-1", sentToken, "DEBITED", now);
              case "retry" ->
                  intents.recordRetryOwnedLegacy(
                      "pay-1",
                      sentToken,
                      "ORDER_SYNC_UNKNOWN",
                      "owned error",
                      now.plusSeconds(5),
                      now);
              default -> intents.hasOwnedLegacyLease("pay-1", sentToken, now) ? 1 : 0;
            };
        assertEquals("valid".equals(scenario) ? 1 : 0, changed, operation + ":" + scenario);
        if (!"valid".equals(scenario) || "lease".equals(operation))
          assertEquals(before, payFacts());
        else if ("retry".equals(operation)) {
          assertNull(intentLeaseOwner());
          assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
          assertTrue(retryErrorPresent());
          assertEquals(now.plusSeconds(5), intentNextRetry());
        }
      }
    }
  }

  /** 实际支付记录已执行后intent零行/异常/数据库触发器错误必须回滚原终态写。 */
  @Test
  void terminalPairFailureRollsBackPaymentAfterIntentFinishFault() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      for (String fault : List.of("zero", "exception", "trigger")) {
        assertions.add(
            () -> {
              fixture.execute("DROP TRIGGER IF EXISTS reject_owned_finish");
              seedIntentAndPayment("PENDING", "INIT");
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              when(user.operation(7L, "pay-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "pay-1",
                              "DEBIT",
                              "FAILED".equals(terminal) ? "REJECTED" : "APPLIED",
                              "10.00",
                              "REFUNDED".equals(terminal) ? "custom-refund" : null)));
              when(user.operation(7L, "custom-refund", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "custom-refund", "REFUND", "APPLIED", "10.00", null)));
              when(orders.paymentState("order-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new OrderClient.OrderView(
                              "order-1",
                              "SUCCESS".equals(terminal) ? "PAID" : "CANCELLED",
                              BigDecimal.TEN,
                              7L,
                              "SUCCEEDED")));
              PayDebitIntentMapper actualIntents = intents;
              var failingIntents = org.mockito.Mockito.spy(actualIntents);
              var sawPaymentTerminal = new java.util.concurrent.atomic.AtomicBoolean();
              var terminalWasTransactional = new java.util.concurrent.atomic.AtomicBoolean();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        sawPaymentTerminal.set(terminal.equals(paymentStatus()));
                        terminalWasTransactional.set(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if ("zero".equals(fault)) return 0;
                        if ("exception".equals(fault))
                          throw new IllegalStateException("owned-intent-finish-failure");
                        return invocation.callRealMethod();
                      })
                  .when(failingIntents)
                  .finishOwnedLegacy(eq("pay-1"), anyString(), eq(terminal), any());
              if ("trigger".equals(fault))
                fixture.execute(
                    "CREATE TRIGGER reject_owned_finish BEFORE UPDATE ON pay_debit_intent FOR EACH"
                        + " ROW BEGIN IF NEW.status IN ('SUCCESS','REFUNDED','FAILED') THEN SIGNAL"
                        + " SQLSTATE '45000' SET MESSAGE_TEXT='owned terminal failure'; END IF;"
                        + " END");
              intents = failingIntents;
              try {
                String result = newService(user, orders).process("pay-1");
                assertNotEquals(terminal, result);
                assertTrue(sawPaymentTerminal.get());
                assertNotEquals(terminal, paymentStatus());
                assertNotEquals(terminal, intentStatus());
                assertTrue(terminalWasTransactional.get());
                assertNull(
                    fixture.queryForObject(
                        "SELECT paid_at FROM pay_record WHERE pay_no='pay-1'",
                        java.sql.Timestamp.class));
                assertNull(intentLeaseOwner());
                assertTrue(retryErrorPresent());
                verify(user, never()).debit(any(), any(), any());
                verify(user, never()).refund(any(), any(), any());
              } finally {
                intents = actualIntents;
                fixture.execute("DROP TRIGGER IF EXISTS reject_owned_finish");
              }
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 三终态的原两SQL共用事务连接，所有User/Order交互仍在短事务外。 */
  @Test
  void healthyTerminalPairCommitsOnOneConnectionWithRpcOutside() {
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      when(user.operation(eq(7L), anyString(), eq(7L)))
          .thenAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                String key = invocation.getArgument(1);
                if ("custom-refund".equals(key))
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(key, "REFUND", "APPLIED", "10.00", null));
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1",
                        "DEBIT",
                        "FAILED".equals(terminal) ? "REJECTED" : "APPLIED",
                        "10.00",
                        "REFUNDED".equals(terminal) ? "custom-refund" : null));
              });
      OrderClient orders = mock(OrderClient.class);
      when(orders.paymentState("order-1", 7L))
          .thenAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1",
                        "SUCCESS".equals(terminal) ? "PAID" : "CANCELLED",
                        BigDecimal.TEN,
                        7L,
                        "SUCCEEDED"));
              });
      var connectionIds = new java.util.HashSet<Integer>();
      var observed = new ArrayList<String>();
      PayRecordMapper actualRecords = records;
      PayDebitIntentMapper actualIntents = intents;
      PayRecordMapper instrumentedRecords =
          (PayRecordMapper)
              java.lang.reflect.Proxy.newProxyInstance(
                  actualRecords.getClass().getClassLoader(),
                  new Class<?>[] {PayRecordMapper.class},
                  (proxy, method, arguments) -> {
                    if (method
                        .getName()
                        .equals(
                            "markOwnedLegacy"
                                + ("SUCCESS".equals(terminal)
                                    ? "Success"
                                    : "REFUNDED".equals(terminal) ? "Refunded" : "Failed"))) {
                      assertTrue(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isActualTransactionActive());
                      observed.add("payment");
                      java.sql.Connection connection =
                          org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                              fixture.getDataSource());
                      try {
                        connectionIds.add(System.identityHashCode(connection));
                      } finally {
                        org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                            connection, fixture.getDataSource());
                      }
                    }
                    try {
                      return method.invoke(actualRecords, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertTrue(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertEquals(terminal, paymentStatus());
                observed.add("intent");
                java.sql.Connection connection =
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                        fixture.getDataSource());
                try {
                  connectionIds.add(System.identityHashCode(connection));
                } finally {
                  org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                      connection, fixture.getDataSource());
                }
                return invocation.callRealMethod();
              })
          .when(instrumentedIntents)
          .finishOwnedLegacy(eq("pay-1"), anyString(), eq(terminal), any());
      records = instrumentedRecords;
      intents = instrumentedIntents;
      try {
        assertEquals(terminal, newService(user, orders).process("pay-1"));
        assertEquals(List.of("payment", "intent"), observed);
        assertEquals(1, connectionIds.size());
        assertEquals(terminal, paymentStatus());
        assertEquals(terminal, intentStatus());
        assertNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
      } finally {
        records = actualRecords;
        intents = actualIntents;
      }
    }
  }

  /** 真实caller本地事务不能被恢复器继承；拒绝后caller仍可自行提交owned写。 */
  @Test
  void actualCallerTransactionRemainsUsableWithoutRecoveryEffects() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(7L, "pay-1", 7L)).thenThrow(new IllegalStateException("owned-read-fault"));
    PayConsistencyService service = newService(user, orders);
    var before = payFacts();
    var caller =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                fixture.getDataSource()));
    caller.executeWithoutResult(
        status -> {
          assertTrue(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  IllegalStateException.class, () -> service.process("pay-1"));
          assertEquals("Legacy支付恢复不能继承事务上下文", failure.getMessage());
          assertEquals(before, payFacts());
          org.mockito.Mockito.verifyNoInteractions(user, orders);
          assertTrue(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          org.junit.jupiter.api.Assertions.assertFalse(status.isRollbackOnly());
          assertEquals(
              1,
              fixture.update(
                  "UPDATE pay_debit_intent SET last_error='caller-owned-write' WHERE"
                      + " pay_no='pay-1'"));
        });
    org.junit.jupiter.api.Assertions.assertFalse(
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive());
    assertEquals(
        "caller-owned-write",
        fixture.queryForObject(
            "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
    assertEquals(before.get(0), payFacts().get(0));
    assertEquals(0, intentAttempts());
    assertNull(intentLeaseOwner());
  }

  /** 仅自建XID上下文也在真实Mapper读取之前拒绝，不启动TC。 */
  @Test
  void inheritedGlobalContextLeavesOwnedFactsUntouched() {
    seedIntentAndPayment("PENDING", "INIT");
    var before = payFacts();
    org.junit.jupiter.api.Assertions.assertNull(io.seata.core.context.RootContext.getXID());
    io.seata.core.context.RootContext.bind("owned-native-entry-context");
    try {
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalStateException.class, () -> consistency.process("pay-1"));
      assertEquals("Legacy支付恢复不能继承事务上下文", failure.getMessage());
      assertEquals(before, payFacts());
      assertEquals("owned-native-entry-context", io.seata.core.context.RootContext.getXID());
    } finally {
      io.seata.core.context.RootContext.unbind();
    }
  }

  /** 两真实原表的单字段错配/共同非法域均在claim之前拒绝，完整事实保持。 */
  @Test
  void corruptOriginalPaymentFactsDoNotClaimOrCallRemoteServices() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String target : List.of("intent", "payment", "both")) {
      for (String variant :
          List.of(
              "pay-alias",
              "order-other",
              "order-blank",
              "user-other",
              "user-zero",
              "money-other",
              "money-zero",
              "money-negative")) {
        if ("both".equals(target)
            && List.of("order-other", "user-other", "money-other").contains(variant)) continue;
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              if (!"payment".equals(target)) changeOwnedRecoveryTuple("pay_debit_intent", variant);
              if (!"intent".equals(target)) changeOwnedRecoveryTuple("pay_record", variant);
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              var failure =
                  org.junit.jupiter.api.Assertions.assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> newService(user, orders).process("pay-1"),
                      target + ":" + variant);
              assertEquals("COMMON_CONFLICT", failure.getCode());
              assertEquals(409, failure.getHttpStatus());
              assertEquals("支付意图与原支付记录不一致", failure.getMessage());
              assertEquals(before, payFacts());
              org.mockito.Mockito.verifyNoInteractions(user, orders);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真实claim后受控改变源表，后置重读必须停止；不假称claim或fixture变更已回滚。 */
  @Test
  void changedOriginalFactsAfterClaimStopBeforeAnyRemoteOrStageWrite() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      for (String variant : List.of("order-other", "user-other", "money-other")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var claimedMapper = org.mockito.Mockito.spy(intents);
              var afterChange =
                  new java.util.concurrent.atomic.AtomicReference<
                      List<List<Map<String, Object>>>>();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        Object claimResult = invocation.callRealMethod();
                        assertEquals(1, claimResult);
                        changeOwnedRecoveryTuple(table, variant);
                        afterChange.set(payFacts());
                        return claimResult;
                      })
                  .when(claimedMapper)
                  .claim(eq("pay-1"), anyString(), any(), any());
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              PayConsistencyService service =
                  new PayConsistencyService(
                      mock(PayDebitIntentService.class),
                      claimedMapper,
                      records,
                      user,
                      orders,
                      new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                          fixture.getDataSource()));
              var failure =
                  org.junit.jupiter.api.Assertions.assertThrows(
                      com.cloudmall.common.error.BizException.class,
                      () -> service.process("pay-1"));
              assertEquals("COMMON_CONFLICT", failure.getCode());
              assertEquals(409, failure.getHttpStatus());
              assertEquals(afterChange.get(), payFacts());
              assertEquals(1, intentAttempts());
              assertNotNull(intentLeaseOwner());
              org.mockito.Mockito.verifyNoInteractions(user, orders);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 仅两个明确owned表允许受控损坏；变更列/value全部由固定switch选择。 */
  private void changeOwnedRecoveryTuple(String table, String variant) {
    assertTrue(List.of("pay_record", "pay_debit_intent").contains(table));
    String assignment =
        switch (variant) {
          case "pay-alias" -> "pay_no='PAY-1'";
          case "order-other" -> "order_no='order-other'";
          case "order-blank" -> "order_no=' '";
          case "user-other" -> "user_id=8";
          case "user-zero" -> "user_id=0";
          case "money-other" -> "amount=11";
          case "money-zero" -> "amount=0";
          case "money-negative" -> "amount=-1";
          default -> throw new AssertionError(variant);
        };
    assertEquals(1, fixture.update("UPDATE " + table + " SET " + assignment + " WHERE id=1"));
  }

  /** 终态短事务不得用已观察旧tuple结清现在的单边或双边改变事实。 */
  @Test
  void terminalTransactionRejectsChangedOriginalTuple() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      for (String changedSide : List.of("payment", "intent", "both")) {
        for (String mutation : List.of("user", "order", "order-alias", "amount")) {
          assertions.add(
              () -> {
                seedIntentAndPayment(
                    "REFUNDED".equals(terminal) ? "REFUND_PENDING" : "PENDING", "INIT");
                var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                String token = "t141-owned-token";
                assertEquals(
                    1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
                var expected = intents.findByPayNo("pay-1");
                String assignment =
                    switch (mutation) {
                      case "user" -> "user_id=8";
                      case "order" -> "order_no='order-other'";
                      case "order-alias" -> "order_no='ORDER-1'";
                      case "amount" -> "amount=11";
                      default -> throw new AssertionError(mutation);
                    };
                if (!"intent".equals(changedSide))
                  fixture.update("UPDATE pay_record SET " + assignment + " WHERE pay_no='pay-1'");
                if (!"payment".equals(changedSide))
                  fixture.update(
                      "UPDATE pay_debit_intent SET " + assignment + " WHERE pay_no='pay-1'");
                var before = payFacts();
                var service = newService(mock(UserClient.class), mock(OrderClient.class));
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                service, "commitOwnedLegacyTerminal", expected, token, terminal));
                assertEquals("支付终态原载荷或领取资格已变化", failure.getMessage());
                assertEquals(before, payFacts());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真正独立连接证明两表行锁保持到提交或回滚，失败时支付写也完整回滚。 */
  @Test
  void terminalSourceLocksBothRowsUntilCommitOrRollback() throws Exception {
    for (String terminal : List.of("SUCCESS", "REFUNDED", "FAILED")) {
      for (boolean rollback : List.of(false, true)) {
        seedIntentAndPayment("REFUNDED".equals(terminal) ? "REFUND_PENDING" : "PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t141-lock-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        var before = payFacts();
        var startWriters = new java.util.concurrent.CountDownLatch(1);
        var writersReady = new java.util.concurrent.CountDownLatch(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var writers = new ArrayList<java.util.concurrent.Future<Integer>>();
        PayRecordMapper actualRecords = records;
        PayDebitIntentMapper actualIntents = intents;
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        try {
          for (String table : List.of("pay_record", "pay_debit_intent")) {
            writers.add(
                workers.submit(
                    () -> {
                      try (var connection = ownedPool.getConnection();
                          var statement = connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout=10");
                        writersReady.countDown();
                        assertTrue(startWriters.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        return statement.executeUpdate(
                            "UPDATE " + table + " SET amount=amount WHERE pay_no='pay-1'");
                      }
                    }));
          }
          records =
              (PayRecordMapper)
                  java.lang.reflect.Proxy.newProxyInstance(
                      actualRecords.getClass().getClassLoader(),
                      new Class<?>[] {PayRecordMapper.class},
                      (proxy, method, arguments) -> {
                        Object result;
                        try {
                          result = method.invoke(actualRecords, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                        if ("lockOwnedLegacyTerminalSource".equals(method.getName())) {
                          assertNotNull(result);
                          assertTrue(writersReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
                          startWriters.countDown();
                          // 只读MySQL锁等待视图，按本类唯一owned库核两张实际阻塞表。
                          var deadline =
                              System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                          List<String> waitingTables;
                          do {
                            waitingTables =
                                source.queryForList(
                                    "SELECT DISTINCT locks.OBJECT_NAME FROM"
                                        + " performance_schema.data_lock_waits waits JOIN"
                                        + " performance_schema.data_locks locks ON"
                                        + " locks.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID"
                                        + " AND locks.ENGINE=waits.ENGINE WHERE"
                                        + " locks.OBJECT_SCHEMA=? ORDER BY locks.OBJECT_NAME",
                                    String.class,
                                    database);
                            if (waitingTables.size() == 2) break;
                            Thread.sleep(20);
                          } while (System.nanoTime() < deadline);
                          assertEquals(List.of("pay_debit_intent", "pay_record"), waitingTables);
                          for (var writer : writers)
                            org.junit.jupiter.api.Assertions.assertFalse(writer.isDone());
                        }
                        return result;
                      });
          if (rollback) {
            var failingIntents = org.mockito.Mockito.spy(actualIntents);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      // 第二写失败时第一写已在同事务中发生，必须由服务整体回滚。
                      assertEquals(terminal, paymentStatus());
                      return 0;
                    })
                .when(failingIntents)
                .finishOwnedLegacy(eq("pay-1"), eq(token), eq(terminal), any());
            intents = failingIntents;
          }
          var service = newService(user, orders);
          if (rollback) {
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () ->
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            service, "commitOwnedLegacyTerminal", expected, token, terminal));
            assertEquals("支付终态写入未满足当前租约", failure.getMessage());
          } else {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "commitOwnedLegacyTerminal", expected, token, terminal);
          }
          for (var writer : writers)
            assertEquals(1, writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
          if (rollback) assertEquals(before, payFacts());
          else {
            assertEquals(terminal, paymentStatus());
            assertEquals(terminal, intentStatus());
            assertEquals(7L, records.selectByPayNo("pay-1").get(0).userId);
            assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
            assertEquals(0, BigDecimal.TEN.compareTo(records.selectByPayNo("pay-1").get(0).amount));
          }
          org.mockito.Mockito.verifyNoInteractions(user, orders);
        } finally {
          records = actualRecords;
          intents = actualIntents;
          startWriters.countDown();
          workers.shutdown();
          assertTrue(workers.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));
        }
      }
    }
  }

  /** 受控末次Order事实；原接口异常语义仍由被测服务处理。 */
  private static ApiResponse<OrderClient.OrderView> successOrderProofResponse(String variant) {
    if ("fault".equals(variant))
      throw new IllegalStateException("controlled-success-order-unavailable");
    if ("null-response".equals(variant)) return null;
    if ("null-body".equals(variant)) return ApiResponse.ok(null);
    if ("bad-envelope".equals(variant))
      return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
    return ApiResponse.ok(
        new OrderClient.OrderView(
            "order-null".equals(variant)
                ? null
                : "order-other".equals(variant)
                    ? "other-order"
                    : "order-alias".equals(variant) ? "ORDER-1" : "order-1",
            "pending".equals(variant)
                ? "PENDING_PAYMENT"
                : "cancelled".equals(variant)
                    ? "CANCELLED"
                    : "status-null".equals(variant)
                        ? null
                        : "status-unknown".equals(variant)
                            ? "UNKNOWN"
                            : "completed".equals(variant) ? "COMPLETED" : "PAID",
            "amount-null".equals(variant)
                ? null
                : "amount-other".equals(variant)
                    ? new BigDecimal("11.00")
                    : new BigDecimal("10.000"),
            "user-null".equals(variant) ? null : "user-other".equals(variant) ? 8L : 7L,
            "stock-pending".equals(variant)
                ? "PENDING"
                : "stock-unknown".equals(variant)
                    ? "UNKNOWN"
                    : "stock-null".equals(variant)
                        ? null
                        : "stock-alias".equals(variant) ? "succeeded" : "SUCCEEDED"));
  }

  /** 三成功来源的末次订单缺证均等待；更早成功快照不能覆盖当前取消或未知。 */
  @Test
  void latestOrderProofIsRequiredBeforeSuccessWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("paid", "after-paid", "fresh-debit")) {
      for (String variant :
          List.of(
              "pending",
              "cancelled",
              "status-null",
              "status-unknown",
              "stock-pending",
              "stock-unknown",
              "stock-null",
              "stock-alias",
              "order-null",
              "order-other",
              "order-alias",
              "user-null",
              "user-other",
              "amount-null",
              "amount-other",
              "null-response",
              "null-body",
              "bad-envelope",
              "fault")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(path, "order-1", user, orders);
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              int expectedReads = "paid".equals(path) ? 2 : 3;
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        int readNumber = reads.incrementAndGet();
                        if (readNumber < expectedReads)
                          return successOrderProofResponse(
                              !"paid".equals(path) && readNumber == 1 ? "pending" : "paid");
                        // 末次Order读取须在两次User GET之后，保原错误优先级和事务外调用。
                        assertEquals(2, postUserOperationCalls(user));
                        return successOrderProofResponse(variant);
                      })
                  .when(orders)
                  .paymentState("order-1", 7L);
              assertEquals(
                  "ORDER_SYNC_UNKNOWN",
                  newService(user, orders).process("pay-1"),
                  path + ":" + variant);
              assertEquals(expectedReads, reads.get());
              assertEquals(2, postUserOperationCalls(user));
              verify(user, times("fresh-debit".equals(path) ? 1 : 0)).debit(any(), any(), any());
              verify(orders, times("paid".equals(path) ? 0 : 1)).paid(any(), any());
              verify(user, never()).refund(any(), any(), any());
              assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
              assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 六组末次成功订单，真实双表终态保持，Order/User查询都在事务外。 */
  @Test
  void latestValidOrderKeepsHealthySuccessPathsWithRealPay() {
    for (String path : List.of("paid", "after-paid", "fresh-debit")) {
      for (String terminalOrder : List.of("paid", "completed")) {
        seedIntentAndPayment("PENDING", "INIT");
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(path, "order-1", user, orders);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        int expectedReads = "paid".equals(path) ? 2 : 3;
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  int readNumber = reads.incrementAndGet();
                  if (readNumber == expectedReads) assertEquals(2, postUserOperationCalls(user));
                  return successOrderProofResponse(
                      readNumber == expectedReads
                          ? terminalOrder
                          : !"paid".equals(path) && readNumber == 1 ? "pending" : "paid");
                })
            .when(orders)
            .paymentState("order-1", 7L);
        assertEquals("SUCCESS", newService(user, orders).process("pay-1"));
        assertEquals(expectedReads, reads.get());
        assertEquals(2, postUserOperationCalls(user));
        assertEquals("SUCCESS", paymentStatus());
        assertEquals("SUCCESS", intentStatus());
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        assertNotNull(records.selectByPayNo("pay-1").get(0).paidAt);
        verify(user, times("fresh-debit".equals(path) ? 1 : 0)).debit(any(), any(), any());
        verify(orders, times("paid".equals(path) ? 0 : 1)).paid(any(), any());
        verify(user, never()).refund(any(), any(), any());
      }
    }
  }

  /** 最新订单补证后下一次同pay可成功，不重复已发扣款或订单支付请求。 */
  @Test
  void latestSuccessOrderProofCanResumeAfterEvidenceRepairWithRealPay() {
    for (String path : List.of("paid", "after-paid", "fresh-debit")) {
      seedIntentAndPayment("PENDING", "INIT");
      var before = payFacts();
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(path, "order-1", user, orders);
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      int expectedReads = "paid".equals(path) ? 2 : 3;
      org.mockito.Mockito.doAnswer(
              invocation -> {
                int readNumber = reads.incrementAndGet();
                return successOrderProofResponse(
                    readNumber >= expectedReads
                        ? "cancelled"
                        : !"paid".equals(path) && readNumber == 1 ? "pending" : "paid");
              })
          .when(orders)
          .paymentState("order-1", 7L);
      var service = newService(user, orders);
      assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
      assertEquals(expectedReads, reads.get());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                reads.incrementAndGet();
                return successOrderProofResponse("completed");
              })
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("SUCCESS", service.process("pay-1"));
      assertEquals(expectedReads + 2, reads.get());
      assertEquals(4, postUserOperationCalls(user));
      assertEquals("SUCCESS", paymentStatus());
      assertEquals("SUCCESS", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      var payment = records.selectByPayNo("pay-1").get(0);
      assertEquals("pay-1", payment.payNo);
      assertEquals("order-1", payment.orderNo);
      assertEquals(7L, payment.userId);
      assertEquals(new BigDecimal("10.00"), payment.amount);
      assertNotNull(payment.paidAt);
      verify(user, times("fresh-debit".equals(path) ? 1 : 0)).debit(any(), any(), any());
      verify(orders, times("paid".equals(path) ? 0 : 1)).paid(any(), any());
      verify(user, never()).refund(any(), any(), any());
    }
  }

  /** 两组阶段第二写失败时，catch清理之前必须看见第一写已整体回滚。 */
  @Test
  void intermediatePairRollsBackBeforeRetryReadbackWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (String failureMode : List.of("zero", "exception")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(
                  "DEBITED".equals(stage) ? "paid" : "applied-cancel", "order-1", user, orders);
              PayRecordMapper actualRecords = records;
              PayDebitIntentMapper actualIntents = intents;
              var beforeStage =
                  new java.util.concurrent.atomic.AtomicReference<
                      List<List<Map<String, Object>>>>();
              var retryReadback =
                  new java.util.concurrent.atomic.AtomicReference<
                      List<List<Map<String, Object>>>>();
              var stageFailed = new java.util.concurrent.atomic.AtomicBoolean();
              String paymentMethod =
                  "DEBITED".equals(stage)
                      ? "markOwnedLegacyOrderUnknown"
                      : "markOwnedLegacyRefundPending";
              records =
                  (PayRecordMapper)
                      java.lang.reflect.Proxy.newProxyInstance(
                          actualRecords.getClass().getClassLoader(),
                          new Class<?>[] {PayRecordMapper.class},
                          (proxy, method, arguments) -> {
                            if (paymentMethod.equals(method.getName()) && beforeStage.get() == null)
                              beforeStage.set(payFacts());
                            try {
                              return method.invoke(actualRecords, arguments);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                              throw failure.getCause();
                            }
                          });
              var failingIntents = org.mockito.Mockito.spy(actualIntents);
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        assertEquals(
                            "DEBITED".equals(stage) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING",
                            paymentStatus());
                        stageFailed.set(true);
                        if ("exception".equals(failureMode))
                          throw new IllegalStateException("controlled-stage-intent-write-failure");
                        return 0;
                      })
                  .when(failingIntents)
                  .updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any());
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        if (stageFailed.get() && retryReadback.get() == null) {
                          org.junit.jupiter.api.Assertions.assertFalse(
                              org.springframework.transaction.support
                                  .TransactionSynchronizationManager.isActualTransactionActive());
                          retryReadback.set(payFacts());
                        }
                        return invocation.callRealMethod();
                      })
                  .when(failingIntents)
                  .findByPayNo("pay-1");
              intents = failingIntents;
              try {
                assertEquals("ORDER_SYNC_UNKNOWN", newService(user, orders).process("pay-1"));
                assertNotNull(beforeStage.get());
                assertNotNull(retryReadback.get());
                assertEquals(beforeStage.get(), retryReadback.get(), stage + ":" + failureMode);
                assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
                assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertNotNull(intentNextRetry());
                assertTrue(retryErrorPresent());
                verify(user, never()).debit(any(), any(), any());
                verify(user, never()).refund(any(), any(), any());
                verify(orders, never()).paid(any(), any());
              } finally {
                records = actualRecords;
                intents = actualIntents;
              }
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 两组正常阶段原SQL在同一实际连接/事务提交，所有远端调用均在事务外。 */
  @Test
  void healthyIntermediatePairUsesOneConnectionWithRpcOutside() {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                String key = invocation.getArgument(1);
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        key, "pay-1".equals(key) ? "DEBIT" : "REFUND", "APPLIED", "10.00", null));
              })
          .when(user)
          .operation(eq(7L), anyString(), eq(7L));
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return ApiResponse.ok(
                    new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00"));
              })
          .when(user)
          .refund(eq(7L), eq(7L), any());
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1",
                        "DEBITED".equals(stage) ? "PAID" : "CANCELLED",
                        BigDecimal.TEN,
                        7L,
                        "SUCCEEDED"));
              })
          .when(orders)
          .paymentState("order-1", 7L);
      PayRecordMapper actualRecords = records;
      PayDebitIntentMapper actualIntents = intents;
      var connectionIds = new java.util.HashSet<Integer>();
      var observed = new ArrayList<String>();
      var completedStage = new java.util.concurrent.atomic.AtomicBoolean();
      String paymentMethod =
          "DEBITED".equals(stage) ? "markOwnedLegacyOrderUnknown" : "markOwnedLegacyRefundPending";
      records =
          (PayRecordMapper)
              java.lang.reflect.Proxy.newProxyInstance(
                  actualRecords.getClass().getClassLoader(),
                  new Class<?>[] {PayRecordMapper.class},
                  (proxy, method, arguments) -> {
                    if (paymentMethod.equals(method.getName()) && !completedStage.get()) {
                      assertTrue(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isActualTransactionActive());
                      observed.add("payment");
                      var connection =
                          org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                              fixture.getDataSource());
                      try {
                        connectionIds.add(System.identityHashCode(connection));
                      } finally {
                        org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                            connection, fixture.getDataSource());
                      }
                    }
                    try {
                      return method.invoke(actualRecords, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertTrue(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertEquals(
                    "DEBITED".equals(stage) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING",
                    paymentStatus());
                observed.add("intent");
                var connection =
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                        fixture.getDataSource());
                try {
                  connectionIds.add(System.identityHashCode(connection));
                } finally {
                  org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                      connection, fixture.getDataSource());
                }
                Object result = invocation.callRealMethod();
                completedStage.set(true);
                return result;
              })
          .when(instrumentedIntents)
          .updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any());
      intents = instrumentedIntents;
      try {
        String terminal = "DEBITED".equals(stage) ? "SUCCESS" : "REFUNDED";
        assertEquals(terminal, newService(user, orders).process("pay-1"));
        assertEquals(List.of("payment", "intent"), observed);
        assertEquals(1, connectionIds.size());
        assertEquals(terminal, paymentStatus());
        assertEquals(terminal, intentStatus());
        verify(user, never()).debit(any(), any(), any());
        verify(user, times("DEBITED".equals(stage) ? 0 : 1)).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      } finally {
        records = actualRecords;
        intents = actualIntents;
      }
    }
  }

  /** 阶段第二写失败后修复DAO，同一支付继续恢复，保原号、单记录和远端写预算。 */
  @Test
  void failedIntermediatePairResumesOnSamePaymentWithRealPay() {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "DEBITED".equals(stage) ? "paid" : "applied-cancel", "order-1", user, orders);
      PayDebitIntentMapper actualIntents = intents;
      var recoveringIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doReturn(0)
          .when(recoveringIntents)
          .updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any());
      intents = recoveringIntents;
      try {
        var service = newService(user, orders);
        assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
        assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
        assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        verify(user, never()).refund(any(), any(), any());
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
                + " pay_no='pay-1'");
        org.mockito.Mockito.doCallRealMethod()
            .when(recoveringIntents)
            .updateOwnedLegacyStatus(eq("pay-1"), anyString(), eq(stage), any());
        String terminal = "DEBITED".equals(stage) ? "SUCCESS" : "REFUNDED";
        assertEquals(terminal, service.process("pay-1"));
        assertEquals(terminal, paymentStatus());
        assertEquals(terminal, intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        assertEquals("pay-1", records.selectByPayNo("pay-1").get(0).payNo);
        assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
        assertEquals(new BigDecimal("10.00"), records.selectByPayNo("pay-1").get(0).amount);
        verify(user, never()).debit(any(), any(), any());
        verify(user, times("DEBITED".equals(stage) ? 0 : 1)).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 两阶段的原快照不得结算任一边或双边已变化的本地身份和金额。 */
  @Test
  void stageTransactionRejectsChangedOriginalTupleWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (String side : List.of("payment", "intent", "both")) {
        for (String mutation : List.of("user", "order", "order-alias", "amount")) {
          assertions.add(
              () -> {
                seedIntentAndPayment("PENDING", "INIT");
                var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                String token = "t144-owned-token";
                assertEquals(
                    1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
                var expected = intents.findByPayNo("pay-1");
                changeStageSourceTuple(side, mutation);
                var before = payFacts();
                var service = newService(mock(UserClient.class), mock(OrderClient.class));
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                service, "commitOwnedLegacyStage", expected, token, stage));
                assertEquals("支付阶段原载荷或领取资格已变化", failure.getMessage());
                assertEquals(before, payFacts());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 仅修改本类owned库两张表的固定字段，用于模拟route观察后的外部变更。 */
  private void changeStageSourceTuple(String side, String mutation) {
    String assignment =
        switch (mutation) {
          case "user" -> "user_id=8";
          case "order" -> "order_no='order-other'";
          case "order-alias" -> "order_no='ORDER-1'";
          case "amount" -> "amount=11";
          default -> throw new AssertionError(mutation);
        };
    if (!"intent".equals(side))
      fixture.update("UPDATE pay_record SET " + assignment + " WHERE pay_no='pay-1'");
    if (!"payment".equals(side))
      fixture.update("UPDATE pay_debit_intent SET " + assignment + " WHERE pay_no='pay-1'");
  }

  /** 真正独立连接证明两表行锁保持到提交或回滚，失败时支付写也完整回滚。 */
  @Test
  void stageSourceLocksBothRowsUntilCommitOrRollback() throws Exception {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (boolean rollback : List.of(false, true)) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t144-lock-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        var before = payFacts();
        var startWriters = new java.util.concurrent.CountDownLatch(1);
        var writersReady = new java.util.concurrent.CountDownLatch(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var writers = new ArrayList<java.util.concurrent.Future<Integer>>();
        PayRecordMapper actualRecords = records;
        PayDebitIntentMapper actualIntents = intents;
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        try {
          for (String table : List.of("pay_record", "pay_debit_intent")) {
            writers.add(
                workers.submit(
                    () -> {
                      try (var connection = ownedPool.getConnection();
                          var statement = connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout=10");
                        writersReady.countDown();
                        assertTrue(startWriters.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        return statement.executeUpdate(
                            "UPDATE " + table + " SET amount=amount WHERE pay_no='pay-1'");
                      }
                    }));
          }
          records =
              (PayRecordMapper)
                  java.lang.reflect.Proxy.newProxyInstance(
                      actualRecords.getClass().getClassLoader(),
                      new Class<?>[] {PayRecordMapper.class},
                      (proxy, method, arguments) -> {
                        Object result;
                        try {
                          result = method.invoke(actualRecords, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                        if ("lockOwnedLegacyStageSource".equals(method.getName())) {
                          assertNotNull(result);
                          assertTrue(writersReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
                          startWriters.countDown();
                          // 只读MySQL锁等待视图，按本类唯一owned库核两张实际阻塞表。
                          var deadline =
                              System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                          List<String> waitingTables;
                          do {
                            waitingTables =
                                source.queryForList(
                                    "SELECT DISTINCT locks.OBJECT_NAME FROM"
                                        + " performance_schema.data_lock_waits waits JOIN"
                                        + " performance_schema.data_locks locks ON"
                                        + " locks.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID"
                                        + " AND locks.ENGINE=waits.ENGINE WHERE"
                                        + " locks.OBJECT_SCHEMA=? ORDER BY locks.OBJECT_NAME",
                                    String.class,
                                    database);
                            if (waitingTables.size() == 2) break;
                            Thread.sleep(20);
                          } while (System.nanoTime() < deadline);
                          assertEquals(List.of("pay_debit_intent", "pay_record"), waitingTables);
                          for (var writer : writers)
                            org.junit.jupiter.api.Assertions.assertFalse(writer.isDone());
                        }
                        return result;
                      });
          if (rollback) {
            var failingIntents = org.mockito.Mockito.spy(actualIntents);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      // 第二写失败时第一写已在同事务中发生，必须由服务整体回滚。
                      assertEquals(
                          "DEBITED".equals(stage) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING",
                          paymentStatus());
                      return 0;
                    })
                .when(failingIntents)
                .updateOwnedLegacyStatus(eq("pay-1"), eq(token), eq(stage), any());
            intents = failingIntents;
          }
          var service = newService(user, orders);
          if (rollback) {
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () ->
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            service, "commitOwnedLegacyStage", expected, token, stage));
            assertEquals("支付终态写入未满足当前租约", failure.getMessage());
          } else {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "commitOwnedLegacyStage", expected, token, stage);
          }
          for (var writer : writers)
            assertEquals(1, writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
          if (rollback) assertEquals(before, payFacts());
          else {
            assertEquals(
                "DEBITED".equals(stage) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING", paymentStatus());
            assertEquals(stage, intentStatus());
            assertEquals(7L, records.selectByPayNo("pay-1").get(0).userId);
            assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
            assertEquals(0, BigDecimal.TEN.compareTo(records.selectByPayNo("pay-1").get(0).amount));
          }
          org.mockito.Mockito.verifyNoInteractions(user, orders);
        } finally {
          records = actualRecords;
          intents = actualIntents;
          startWriters.countDown();
          workers.shutdown();
          assertTrue(workers.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));
        }
      }
    }
  }

  /** 实际Order查询期间tuple改变后，拒绝下一阶段/写RPC，retry缺证亦不改changed facts。 */
  @Test
  void changedStageSourceStopsNextWritingRpcWithRealPay() {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (String mutation : List.of("user", "order", "order-alias", "amount")) {
        seedIntentAndPayment("PENDING", "INIT");
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(
            "DEBITED".equals(stage) ? "paid" : "applied-cancel", "order-1", user, orders);
        var afterChange =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  changeStageSourceTuple("both", mutation);
                  afterChange.set(payFacts());
                  return ApiResponse.ok(
                      new OrderClient.OrderView(
                          "order-1",
                          "DEBITED".equals(stage) ? "PENDING_PAYMENT" : "CANCELLED",
                          BigDecimal.TEN,
                          7L,
                          "SUCCEEDED"));
                })
            .when(orders)
            .paymentState("order-1", 7L);
        assertEquals("ORDER_SYNC_UNKNOWN", newService(user, orders).process("pay-1"));
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
        assertEquals("PENDING", paymentStatus());
        assertEquals("INIT", intentStatus());
        assertEquals(1, intentAttempts());
        assertNotNull(intentLeaseOwner());
        assertNotNull(intentNextRetry());
        assertEquals(afterChange.get(), payFacts());
      }
    }
  }

  /** 允许数值相同尾零、最小/最大原金额，健康source仍按原两写提交。 */
  @Test
  void healthyStageSourceKeepsNumericMoneyAndIdentity() {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (String amount : List.of("10.000", "0.010", "9999999999999999.990")) {
        seedIntentAndPayment("PENDING", "INIT");
        var expectedAmount = new BigDecimal(amount);
        fixture.update("UPDATE pay_record SET amount=? WHERE pay_no='pay-1'", expectedAmount);
        fixture.update("UPDATE pay_debit_intent SET amount=? WHERE pay_no='pay-1'", expectedAmount);
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t144-money-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        expected.amount = expectedAmount;
        var before = payFacts();
        var service = newService(mock(UserClient.class), mock(OrderClient.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "commitOwnedLegacyStage", expected, token, stage);
        assertEquals(
            "DEBITED".equals(stage) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING", paymentStatus());
        assertEquals(stage, intentStatus());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
  }

  /** 新source查询保原缺行/模式/status/精确owner/期限资格，不把零行改成无条件写。 */
  @Test
  void stageSourceRejectsLostEligibilityWithRealPay() {
    for (String stage : List.of("DEBITED", "REFUND_PENDING")) {
      for (String scenario :
          List.of(
              "payment-missing",
              "intent-missing",
              "owner-other",
              "owner-alias",
              "owner-null",
              "lease-expired",
              "lease-null",
              "payment-tcc",
              "intent-tcc",
              "payment-mode-alias",
              "intent-mode-alias",
              "payment-terminal",
              "intent-terminal",
              "payment-pay-alias",
              "intent-pay-alias")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t144-eligible-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        switch (scenario) {
          case "payment-missing" -> fixture.update("DELETE FROM pay_record WHERE pay_no='pay-1'");
          case "intent-missing" ->
              fixture.update("DELETE FROM pay_debit_intent WHERE pay_no='pay-1'");
          case "owner-other" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_owner='other-owner' WHERE pay_no='pay-1'");
          case "owner-alias" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_owner='T144-ELIGIBLE-TOKEN' WHERE"
                      + " pay_no='pay-1'");
          case "owner-null" ->
              fixture.update("UPDATE pay_debit_intent SET lease_owner=NULL WHERE pay_no='pay-1'");
          case "lease-expired" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
                  currentTime.minusSeconds(1));
          case "lease-null" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=NULL WHERE pay_no='pay-1'");
          case "payment-tcc" ->
              fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "intent-tcc" ->
              fixture.update("UPDATE pay_debit_intent SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "payment-mode-alias" ->
              fixture.update("UPDATE pay_record SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "intent-mode-alias" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "payment-terminal" ->
              fixture.update("UPDATE pay_record SET status='SUCCESS' WHERE pay_no='pay-1'");
          case "intent-terminal" ->
              fixture.update("UPDATE pay_debit_intent SET status='SUCCESS' WHERE pay_no='pay-1'");
          case "payment-pay-alias" ->
              fixture.update("UPDATE pay_record SET pay_no='PAY-1' WHERE pay_no='pay-1'");
          case "intent-pay-alias" ->
              fixture.update("UPDATE pay_debit_intent SET pay_no='PAY-1' WHERE pay_no='pay-1'");
          default -> throw new AssertionError(scenario);
        }
        var before = payFacts();
        var service = newService(mock(UserClient.class), mock(OrderClient.class));
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        service, "commitOwnedLegacyStage", expected, token, stage));
        assertEquals("支付阶段原载荷或领取资格已变化", failure.getMessage());
        assertEquals(before, payFacts(), stage + ":" + scenario);
      }
    }
  }

  /** 实际process的第二清理SQL零行或触发器报错时，第一清理SQL必须完整回滚。 */
  @Test
  void retryPairRollsBackWhenIntentWriteCannotCompleteWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String failureMode : List.of("zero", "sql-exception")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            UserClient user = mock(UserClient.class);
            OrderClient orders = mock(OrderClient.class);
            configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
            when(orders.paymentState("order-1", 7L))
                .thenThrow(new IllegalStateException("owned-order-read-unavailable"));
            PayRecordMapper actualRecords = records;
            PayDebitIntentMapper actualIntents = intents;
            var beforeRetry =
                new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
            records =
                (PayRecordMapper)
                    java.lang.reflect.Proxy.newProxyInstance(
                        actualRecords.getClass().getClassLoader(),
                        new Class<?>[] {PayRecordMapper.class},
                        (proxy, method, arguments) -> {
                          if ("markOwnedLegacyOrderUnknown".equals(method.getName()))
                            beforeRetry.set(payFacts());
                          try {
                            return method.invoke(actualRecords, arguments);
                          } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                          }
                        });
            var failingIntents = org.mockito.Mockito.spy(actualIntents);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
                      // 真实SQL错token零行，不以mock返回值冒充SQL资格拒绝。
                      if ("zero".equals(failureMode))
                        return actualIntents.recordRetryOwnedLegacy(
                            invocation.getArgument(0),
                            "owned-lost-qualification-token",
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5));
                      return invocation.callRealMethod();
                    })
                .when(failingIntents)
                .recordRetryOwnedLegacy(
                    eq("pay-1"), anyString(), eq("ORDER_SYNC_UNKNOWN"), anyString(), any(), any());
            intents = failingIntents;
            try {
              if ("sql-exception".equals(failureMode))
                fixture.execute(
                    "CREATE TRIGGER cm16t145_retry_failure BEFORE UPDATE ON pay_debit_intent FOR"
                        + " EACH ROW BEGIN IF NEW.last_error IS NOT NULL THEN SIGNAL SQLSTATE"
                        + " '45000' SET MESSAGE_TEXT='owned-retry-write-failure'; END IF; END");
              var service = newService(user, orders);
              if ("sql-exception".equals(failureMode)) {
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataAccessException.class,
                        () -> service.process("pay-1"));
                assertTrue(failure.getMessage().contains("owned-retry-write-failure"));
              } else assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
              assertNotNull(beforeRetry.get());
              assertEquals(beforeRetry.get(), payFacts(), failureMode);
              assertEquals("PENDING", paymentStatus());
              assertEquals("INIT", intentStatus());
              assertEquals(1, intentAttempts());
              assertNotNull(intentLeaseOwner());
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, never()).paid(any(), any());
            } finally {
              fixture.execute("DROP TRIGGER IF EXISTS cm16t145_retry_failure");
              records = actualRecords;
              intents = actualIntents;
            }
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 正常catch两写同一真实事务连接，原User/Order读取不进入重试事务。 */
  @Test
  void healthyRetryPairUsesOneConnectionWithRpcOutside() {
    seedIntentAndPayment("PENDING", "INIT");
    var before = payFacts();
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(7L, "pay-1", 7L))
        .thenAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", null));
            });
    when(orders.paymentState("order-1", 7L))
        .thenAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              throw new IllegalStateException("owned-order-read-unavailable");
            });
    PayRecordMapper actualRecords = records;
    PayDebitIntentMapper actualIntents = intents;
    var connectionIds = new java.util.HashSet<Integer>();
    var observed = new ArrayList<String>();
    records =
        (PayRecordMapper)
            java.lang.reflect.Proxy.newProxyInstance(
                actualRecords.getClass().getClassLoader(),
                new Class<?>[] {PayRecordMapper.class},
                (proxy, method, arguments) -> {
                  if ("markOwnedLegacyOrderUnknown".equals(method.getName())) {
                    assertTrue(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    observed.add("payment");
                    var connection =
                        org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                            fixture.getDataSource());
                    try {
                      connectionIds.add(System.identityHashCode(connection));
                    } finally {
                      org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                          connection, fixture.getDataSource());
                    }
                  }
                  try {
                    return method.invoke(actualRecords, arguments);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertTrue(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
              observed.add("intent");
              var connection =
                  org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                      fixture.getDataSource());
              try {
                connectionIds.add(System.identityHashCode(connection));
              } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                    connection, fixture.getDataSource());
              }
              return invocation.callRealMethod();
            })
        .when(instrumentedIntents)
        .recordRetryOwnedLegacy(
            eq("pay-1"), anyString(), eq("ORDER_SYNC_UNKNOWN"), anyString(), any(), any());
    intents = instrumentedIntents;
    try {
      assertEquals("ORDER_SYNC_UNKNOWN", newService(user, orders).process("pay-1"));
      assertEquals(List.of("payment", "intent"), observed);
      assertEquals(1, connectionIds.size());
      assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
      assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      assertNotNull(intentNextRetry());
      assertTrue(retryErrorPresent());
      assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    } finally {
      records = actualRecords;
      intents = actualIntents;
    }
  }

  /** 保INIT/REFUND_PENDING单intent写及512截断，首笔0不再单独推进intent。 */
  @Test
  void singleIntentRetryAndFirstZeroPreserveSafeDirection() {
    for (String retryStatus : List.of("INIT", "REFUND_PENDING", "ORDER_SYNC_UNKNOWN")) {
      seedIntentAndPayment("INIT".equals(retryStatus) ? "PENDING" : "REFUND_PENDING", "INIT");
      var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
      String token = "t145-single-token";
      assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
      var expected = intents.findByPayNo("pay-1");
      var before = payFacts();
      String error = "owned-retry-" + "x".repeat(600);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          newService(mock(UserClient.class), mock(OrderClient.class)),
          "recordOwnedLegacyRetry",
          expected,
          token,
          retryStatus,
          error);
      if ("ORDER_SYNC_UNKNOWN".equals(retryStatus)) {
        assertEquals(before, payFacts());
        assertEquals("INIT", intentStatus());
        assertEquals(token, intentLeaseOwner());
        continue;
      }
      assertEquals(before.get(0), payFacts().get(0));
      assertEquals(retryStatus, intentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(
          error.substring(0, 512),
          fixture.queryForObject(
              "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
      assertNotNull(intentNextRetry());
      assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
    }
  }

  /** retry回滚保留原lease；受控过期并修复DAO后下一次同pay恢复，不重发资金请求。 */
  @Test
  void failedRetryResumesAfterLeaseRecoveryOnSamePayment() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
    when(orders.paymentState("order-1", 7L))
        .thenThrow(new IllegalStateException("owned-order-read-unavailable"));
    PayDebitIntentMapper actualIntents = intents;
    var recoveringIntents = org.mockito.Mockito.spy(actualIntents);
    org.mockito.Mockito.doAnswer(
            invocation ->
                actualIntents.recordRetryOwnedLegacy(
                    invocation.getArgument(0),
                    "owned-lost-qualification-token",
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5)))
        .when(recoveringIntents)
        .recordRetryOwnedLegacy(anyString(), anyString(), anyString(), anyString(), any(), any());
    intents = recoveringIntents;
    try {
      var service = newService(user, orders);
      assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
      assertEquals("PENDING", paymentStatus());
      assertEquals("INIT", intentStatus());
      assertEquals(1, intentAttempts());
      assertNotNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE pay_no='pay-1'");
      org.mockito.Mockito.doCallRealMethod()
          .when(recoveringIntents)
          .recordRetryOwnedLegacy(anyString(), anyString(), anyString(), anyString(), any(), any());
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")))
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("SUCCESS", service.process("pay-1"));
      assertEquals("SUCCESS", paymentStatus());
      assertEquals("SUCCESS", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", records.selectByPayNo("pay-1").get(0).payNo);
      assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
      assertEquals(new BigDecimal("10.00"), records.selectByPayNo("pay-1").get(0).amount);
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    } finally {
      intents = actualIntents;
    }
  }

  /** 三方向XID存在时原owned事实完整保持，caller上下文不被清理。 */
  @Test
  void retryWithInheritedGlobalContextLeavesFactsUntouched() {
    for (String retryStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      seedIntentAndPayment("PENDING", "INIT");
      var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
      assertEquals(
          1,
          intents.claim("pay-1", "t145-context-token", currentTime.plusSeconds(30), currentTime));
      var expected = intents.findByPayNo("pay-1");
      var before = payFacts();
      var service = newService(mock(UserClient.class), mock(OrderClient.class));
      org.junit.jupiter.api.Assertions.assertNull(io.seata.core.context.RootContext.getXID());
      io.seata.core.context.RootContext.bind("owned-retry-context");
      try {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service,
            "recordOwnedLegacyRetry",
            expected,
            "t145-context-token",
            retryStatus,
            "owned-error");
        assertEquals(before, payFacts());
        assertEquals("owned-retry-context", io.seata.core.context.RootContext.getXID());
      } finally {
        io.seata.core.context.RootContext.unbind();
      }
    }
  }

  /** 三重试方向不得清理单边或双边已变身份/金额，完整changed facts必须保持。 */
  @Test
  void retryTransactionSkipsChangedOriginalTupleWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String retryStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String side : List.of("payment", "intent", "both")) {
        for (String mutation : List.of("user", "order", "order-alias", "amount")) {
          assertions.add(
              () -> {
                seedIntentAndPayment("PENDING", "INIT");
                var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                String token = "t146-owned-token";
                assertEquals(
                    1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
                var expected = intents.findByPayNo("pay-1");
                changeStageSourceTuple(side, mutation);
                var before = payFacts();
                var service = newService(mock(UserClient.class), mock(OrderClient.class));
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service, "recordOwnedLegacyRetry", expected, token, retryStatus, "owned-error");
                assertEquals(before, payFacts(), retryStatus + ":" + side + ":" + mutation);
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 三重试方向两表真实锁阻塞，原单写/两写commit或rollback后释放。 */
  @Test
  void retrySourceLocksBothRowsUntilCommitOrRollback() throws Exception {
    for (String retryStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (boolean rollback : List.of(false, true)) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t146-lock-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        var before = payFacts();
        var startWriters = new java.util.concurrent.CountDownLatch(1);
        var writersReady = new java.util.concurrent.CountDownLatch(2);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var writers = new ArrayList<java.util.concurrent.Future<Integer>>();
        PayRecordMapper actualRecords = records;
        PayDebitIntentMapper actualIntents = intents;
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        try {
          for (String table : List.of("pay_record", "pay_debit_intent")) {
            writers.add(
                workers.submit(
                    () -> {
                      try (var connection = ownedPool.getConnection();
                          var statement = connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout=10");
                        writersReady.countDown();
                        assertTrue(startWriters.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        return statement.executeUpdate(
                            "UPDATE " + table + " SET amount=amount WHERE pay_no='pay-1'");
                      }
                    }));
          }
          records =
              (PayRecordMapper)
                  java.lang.reflect.Proxy.newProxyInstance(
                      actualRecords.getClass().getClassLoader(),
                      new Class<?>[] {PayRecordMapper.class},
                      (proxy, method, arguments) -> {
                        Object result;
                        try {
                          result = method.invoke(actualRecords, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                          throw failure.getCause();
                        }
                        if ("lockOwnedLegacyRetrySource".equals(method.getName())) {
                          assertNotNull(result);
                          assertTrue(writersReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
                          startWriters.countDown();
                          // 只读MySQL锁等待视图，按本类唯一owned库核两张实际阻塞表。
                          var deadline =
                              System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                          List<String> waitingTables;
                          do {
                            waitingTables =
                                source.queryForList(
                                    "SELECT DISTINCT locks.OBJECT_NAME FROM"
                                        + " performance_schema.data_lock_waits waits JOIN"
                                        + " performance_schema.data_locks locks ON"
                                        + " locks.ENGINE_LOCK_ID=waits.REQUESTING_ENGINE_LOCK_ID"
                                        + " AND locks.ENGINE=waits.ENGINE WHERE"
                                        + " locks.OBJECT_SCHEMA=? ORDER BY locks.OBJECT_NAME",
                                    String.class,
                                    database);
                            if (waitingTables.size() == 2) break;
                            Thread.sleep(20);
                          } while (System.nanoTime() < deadline);
                          assertEquals(List.of("pay_debit_intent", "pay_record"), waitingTables);
                          for (var writer : writers)
                            org.junit.jupiter.api.Assertions.assertFalse(writer.isDone());
                        }
                        return result;
                      });
          if (rollback) {
            var failingIntents = org.mockito.Mockito.spy(actualIntents);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      // 第二写失败时第一写已在同事务中发生，必须由服务整体回滚。
                      assertEquals(
                          "ORDER_SYNC_UNKNOWN".equals(retryStatus)
                              ? "ORDER_SYNC_UNKNOWN"
                              : "PENDING",
                          paymentStatus());
                      return 0;
                    })
                .when(failingIntents)
                .recordRetryOwnedLegacy(
                    eq("pay-1"), eq(token), eq(retryStatus), anyString(), any(), any());
            intents = failingIntents;
          }
          var service = newService(user, orders);
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
              service, "recordOwnedLegacyRetry", expected, token, retryStatus, "owned-error");
          for (var writer : writers)
            assertEquals(1, writer.get(10, java.util.concurrent.TimeUnit.SECONDS));
          if (rollback) assertEquals(before, payFacts());
          else {
            assertEquals(
                "ORDER_SYNC_UNKNOWN".equals(retryStatus) ? "ORDER_SYNC_UNKNOWN" : "PENDING",
                paymentStatus());
            assertEquals(retryStatus, intentStatus());
            assertEquals(7L, records.selectByPayNo("pay-1").get(0).userId);
            assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
            assertEquals(0, BigDecimal.TEN.compareTo(records.selectByPayNo("pay-1").get(0).amount));
          }
          org.mockito.Mockito.verifyNoInteractions(user, orders);
        } finally {
          records = actualRecords;
          intents = actualIntents;
          startWriters.countDown();
          workers.shutdown();
          assertTrue(workers.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));
        }
      }
    }
  }

  /** 三方向45资格样本缺行/owner/lease/mode/status/pay别名变化时零cleanup写。 */
  @Test
  void retrySourceSkipsLostEligibilityWithoutWrites() {
    for (String retryStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String scenario :
          List.of(
              "payment-missing",
              "intent-missing",
              "owner-other",
              "owner-alias",
              "owner-null",
              "lease-expired",
              "lease-null",
              "payment-tcc",
              "intent-tcc",
              "payment-mode-alias",
              "intent-mode-alias",
              "payment-terminal",
              "intent-terminal",
              "payment-pay-alias",
              "intent-pay-alias")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t146-eligible-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        switch (scenario) {
          case "payment-missing" -> fixture.update("DELETE FROM pay_record WHERE pay_no='pay-1'");
          case "intent-missing" ->
              fixture.update("DELETE FROM pay_debit_intent WHERE pay_no='pay-1'");
          case "owner-other" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_owner='other-owner' WHERE pay_no='pay-1'");
          case "owner-alias" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_owner='T146-ELIGIBLE-TOKEN' WHERE"
                      + " pay_no='pay-1'");
          case "owner-null" ->
              fixture.update("UPDATE pay_debit_intent SET lease_owner=NULL WHERE pay_no='pay-1'");
          case "lease-expired" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
                  currentTime.minusSeconds(1));
          case "lease-null" ->
              fixture.update("UPDATE pay_debit_intent SET lease_until=NULL WHERE pay_no='pay-1'");
          case "payment-tcc" ->
              fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "intent-tcc" ->
              fixture.update("UPDATE pay_debit_intent SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "payment-mode-alias" ->
              fixture.update("UPDATE pay_record SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "intent-mode-alias" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET debit_mode='legacy' WHERE pay_no='pay-1'");
          case "payment-terminal" ->
              fixture.update("UPDATE pay_record SET status='SUCCESS' WHERE pay_no='pay-1'");
          case "intent-terminal" ->
              fixture.update("UPDATE pay_debit_intent SET status='SUCCESS' WHERE pay_no='pay-1'");
          case "payment-pay-alias" ->
              fixture.update("UPDATE pay_record SET pay_no='PAY-1' WHERE pay_no='pay-1'");
          case "intent-pay-alias" ->
              fixture.update("UPDATE pay_debit_intent SET pay_no='PAY-1' WHERE pay_no='pay-1'");
          default -> throw new AssertionError(scenario);
        }
        var before = payFacts();
        var service = newService(mock(UserClient.class), mock(OrderClient.class));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "recordOwnedLegacyRetry", expected, token, retryStatus, "owned-error");
        assertEquals(before, payFacts(), retryStatus + ":" + scenario);
      }
    }
  }

  /** 实际User/Order故障期间双表tuple改变后，三方向原facts与lease完整保留。 */
  @Test
  void changedTupleDuringRemoteFailureSkipsRetryWritesWithRealPay() {
    for (String retryStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String mutation : List.of("user", "order", "order-alias", "amount")) {
        boolean refundPending = "REFUND_PENDING".equals(retryStatus);
        seedIntentAndPayment(
            refundPending ? "REFUND_PENDING" : "PENDING",
            refundPending ? "REFUND_PENDING" : "INIT");
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
        var afterChange =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        if ("INIT".equals(retryStatus))
          org.mockito.Mockito.doAnswer(
                  invocation -> {
                    org.junit.jupiter.api.Assertions.assertFalse(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    changeStageSourceTuple("both", mutation);
                    afterChange.set(payFacts());
                    throw new IllegalStateException("owned-user-read-unavailable");
                  })
              .when(user)
              .operation(7L, "pay-1", 7L);
        else
          org.mockito.Mockito.doAnswer(
                  invocation -> {
                    org.junit.jupiter.api.Assertions.assertFalse(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    changeStageSourceTuple("both", mutation);
                    afterChange.set(payFacts());
                    throw new IllegalStateException("owned-order-read-unavailable");
                  })
              .when(orders)
              .paymentState("order-1", 7L);
        assertEquals(retryStatus, newService(user, orders).process("pay-1"));
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals(1, intentAttempts());
        assertNotNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      }
    }
  }

  /** 末次Order GET期间tuple变更时，退款收尾准备也不能改写changed facts。 */
  @Test
  void refundPreparationRejectsChangedTupleBeforeAnyWriteWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("marker", "post-refund")) {
      for (String side : List.of("payment", "intent", "both")) {
        for (String mutation : List.of("user", "order", "order-alias", "amount")) {
          assertions.add(
              () -> {
                seedIntentAndPayment(
                    "marker".equals(path) ? "PENDING" : "REFUND_PENDING",
                    "marker".equals(path) ? "INIT" : "REFUND_PENDING");
                var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                String token = "t147-owned-token";
                assertEquals(
                    1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
                var expected = intents.findByPayNo("pay-1");
                var afterChange =
                    new java.util.concurrent.atomic.AtomicReference<
                        List<List<Map<String, Object>>>>();
                OrderClient orders = mock(OrderClient.class);
                org.mockito.Mockito.doAnswer(
                        invocation -> {
                          org.junit.jupiter.api.Assertions.assertFalse(
                              org.springframework.transaction.support
                                  .TransactionSynchronizationManager.isActualTransactionActive());
                          changeStageSourceTuple(side, mutation);
                          // 明确旧更新时间，使post-refund同状态准备写的残留也可确定复现。
                          fixture.update(
                              "UPDATE pay_record SET updated_at='2020-01-01 00:00:00.000' WHERE"
                                  + " pay_no='pay-1'");
                          afterChange.set(payFacts());
                          return ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED"));
                        })
                    .when(orders)
                    .paymentState("order-1", 7L);
                UserClient user = mock(UserClient.class);
                stubRefundOperation(user, "healthy", "refund:pay-1", "10.00");
                var service = newService(user, orders);
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                service, "finishRefunded", expected, token, "refund:pay-1"));
                assertEquals("支付终态原载荷或领取资格已变化", failure.getMessage());
                assertEquals(afterChange.get(), payFacts(), path + ":" + side + ":" + mutation);
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** marker原PENDING，准备后的真实payment/intent SQL0及trigger异常都撤回准备。 */
  @Test
  void refundPreparationRollsBackWithTerminalSqlFailure() {
    for (String failureMode : List.of("payment-zero", "intent-zero", "intent-trigger")) {
      seedIntentAndPayment("PENDING", "INIT");
      var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
      String token = "t147-terminal-token";
      assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
      var expected = intents.findByPayNo("pay-1");
      var before = payFacts();
      OrderClient orders = cancellingOrders();
      PayRecordMapper actualRecords = records;
      PayDebitIntentMapper actualIntents = intents;
      records =
          (PayRecordMapper)
              java.lang.reflect.Proxy.newProxyInstance(
                  actualRecords.getClass().getClassLoader(),
                  new Class<?>[] {PayRecordMapper.class},
                  (proxy, method, arguments) -> {
                    if ("markOwnedLegacyRefunded".equals(method.getName())) {
                      assertTrue(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isActualTransactionActive());
                      assertEquals("REFUND_PENDING", paymentStatus());
                      if ("payment-zero".equals(failureMode))
                        return actualRecords.markOwnedLegacyRefunded(
                            (String) arguments[0],
                            "owned-lost-qualification-token",
                            (LocalDateTime) arguments[2]);
                    }
                    try {
                      return method.invoke(actualRecords, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var failingIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertEquals("REFUNDED", paymentStatus());
                if ("intent-zero".equals(failureMode))
                  return actualIntents.finishOwnedLegacy(
                      invocation.getArgument(0),
                      "owned-lost-qualification-token",
                      invocation.getArgument(2),
                      invocation.getArgument(3));
                return invocation.callRealMethod();
              })
          .when(failingIntents)
          .finishOwnedLegacy(eq("pay-1"), eq(token), eq("REFUNDED"), any());
      intents = failingIntents;
      try {
        if ("intent-trigger".equals(failureMode))
          fixture.execute(
              "CREATE TRIGGER cm16t147_finish_failure BEFORE UPDATE ON pay_debit_intent FOR EACH"
                  + " ROW BEGIN IF NEW.status='REFUNDED' THEN SIGNAL SQLSTATE '45000' SET"
                  + " MESSAGE_TEXT='owned-refund-terminal-failure'; END IF; END");
        UserClient user = mock(UserClient.class);
        stubRefundOperation(user, "healthy", "refund:pay-1", "10.00");
        var service = newService(user, orders);
        if ("intent-trigger".equals(failureMode)) {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  org.springframework.dao.DataAccessException.class,
                  () ->
                      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                          service, "finishRefunded", expected, token, "refund:pay-1"));
          assertTrue(failure.getMessage().contains("owned-refund-terminal-failure"));
        } else {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  IllegalStateException.class,
                  () ->
                      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                          service, "finishRefunded", expected, token, "refund:pay-1"));
          assertEquals("支付终态写入未满足当前租约", failure.getMessage());
        }
        assertEquals(before, payFacts(), failureMode);
        assertEquals("PENDING", paymentStatus());
        assertEquals("INIT", intentStatus());
      } finally {
        fixture.execute("DROP TRIGGER IF EXISTS cm16t147_finish_failure");
        records = actualRecords;
        intents = actualIntents;
      }
    }
  }

  /** marker/post-refund收尾三写同一实际连接，Order gate在事务外且时间顺序保持。 */
  @Test
  void healthyRefundPreparationAndTerminalUseOneConnection() {
    for (String path : List.of("marker", "post-refund")) {
      seedIntentAndPayment(
          "marker".equals(path) ? "PENDING" : "REFUND_PENDING",
          "marker".equals(path) ? "INIT" : "REFUND_PENDING");
      var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
      String token = "t147-healthy-token";
      assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
      var expected = intents.findByPayNo("pay-1");
      var before = payFacts();
      UserClient user = mock(UserClient.class);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "refund:pay-1", "REFUND", "APPLIED", "10.000", null));
              })
          .when(user)
          .operation(7L, "refund:pay-1", 7L);
      OrderClient orders = mock(OrderClient.class);
      when(orders.paymentState("order-1", 7L))
          .thenAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                return ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED"));
              });
      PayRecordMapper actualRecords = records;
      PayDebitIntentMapper actualIntents = intents;
      var connectionIds = new java.util.HashSet<Integer>();
      var observed = new ArrayList<String>();
      var preparedTime = new java.util.concurrent.atomic.AtomicReference<LocalDateTime>();
      records =
          (PayRecordMapper)
              java.lang.reflect.Proxy.newProxyInstance(
                  actualRecords.getClass().getClassLoader(),
                  new Class<?>[] {PayRecordMapper.class},
                  (proxy, method, arguments) -> {
                    if (List.of("markOwnedLegacyRefundPending", "markOwnedLegacyRefunded")
                        .contains(method.getName())) {
                      assertTrue(
                          org.springframework.transaction.support.TransactionSynchronizationManager
                              .isActualTransactionActive());
                      boolean preparation = "markOwnedLegacyRefundPending".equals(method.getName());
                      observed.add(preparation ? "prepare" : "payment");
                      if (preparation) preparedTime.set((LocalDateTime) arguments[2]);
                      else assertEquals("REFUND_PENDING", paymentStatus());
                      var connection =
                          org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                              fixture.getDataSource());
                      try {
                        connectionIds.add(System.identityHashCode(connection));
                      } finally {
                        org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                            connection, fixture.getDataSource());
                      }
                    }
                    try {
                      return method.invoke(actualRecords, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertTrue(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertEquals("REFUNDED", paymentStatus());
                observed.add("intent");
                var connection =
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                        fixture.getDataSource());
                try {
                  connectionIds.add(System.identityHashCode(connection));
                } finally {
                  org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                      connection, fixture.getDataSource());
                }
                return invocation.callRealMethod();
              })
          .when(instrumentedIntents)
          .finishOwnedLegacy(eq("pay-1"), eq(token), eq("REFUNDED"), any());
      intents = instrumentedIntents;
      try {
        assertEquals(
            "REFUNDED",
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                newService(user, orders), "finishRefunded", expected, token, "refund:pay-1"));
        assertEquals(List.of("prepare", "payment", "intent"), observed);
        assertEquals(1, connectionIds.size());
        assertEquals("REFUNDED", paymentStatus());
        assertEquals("REFUNDED", intentStatus());
        assertNull(intentLeaseOwner());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        var updatedAt =
            fixture.queryForObject(
                "SELECT updated_at FROM pay_record WHERE pay_no='pay-1'", LocalDateTime.class);
        org.junit.jupiter.api.Assertions.assertFalse(
            updatedAt.isBefore(
                preparedTime.get().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        verify(user).operation(7L, "refund:pay-1", 7L);
        verify(user, never()).refund(any(), any(), any());
        verify(user, never()).debit(any(), any(), any());
      } finally {
        records = actualRecords;
        intents = actualIntents;
      }
    }
  }

  /** marker终态失败只留下原catch等待；同service/pay修复后完成，不重复退款。 */
  @Test
  void markerRefundPreparationFailureResumesOnSamePayment() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("refund-marker", "order-1", user, orders);
    PayDebitIntentMapper actualIntents = intents;
    var recoveringIntents = org.mockito.Mockito.spy(actualIntents);
    org.mockito.Mockito.doReturn(0)
        .when(recoveringIntents)
        .finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
    intents = recoveringIntents;
    try {
      var service = newService(user, orders);
      assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
      assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
      assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      assertTrue(retryErrorPresent());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doCallRealMethod()
          .when(recoveringIntents)
          .finishOwnedLegacy(eq("pay-1"), anyString(), eq("REFUNDED"), any());
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", paymentStatus());
      assertEquals("REFUNDED", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    } finally {
      intents = actualIntents;
    }
  }

  /** 原tuple资格成立但payment退款方向拒绝正向清理0时，intent不得被单独推进。 */
  @Test
  void retryFirstSqlZeroPreservesIntentAndLeaseWithRealPay() {
    seedIntentAndPayment("REFUND_PENDING", "INIT");
    var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    String token = "t148-owned-token";
    assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
    var expected = intents.findByPayNo("pay-1");
    var before = payFacts();
    PayRecordMapper actualRecords = records;
    PayDebitIntentMapper actualIntents = intents;
    var firstResult = new java.util.concurrent.atomic.AtomicReference<Integer>();
    records =
        (PayRecordMapper)
            java.lang.reflect.Proxy.newProxyInstance(
                actualRecords.getClass().getClassLoader(),
                new Class<?>[] {PayRecordMapper.class},
                (proxy, method, arguments) -> {
                  Object result;
                  try {
                    result = method.invoke(actualRecords, arguments);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if ("markOwnedLegacyOrderUnknown".equals(method.getName()))
                    firstResult.set((Integer) result);
                  return result;
                });
    var observedIntents = org.mockito.Mockito.spy(actualIntents);
    intents = observedIntents;
    try {
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          newService(mock(UserClient.class), mock(OrderClient.class)),
          "recordOwnedLegacyRetry",
          expected,
          token,
          "ORDER_SYNC_UNKNOWN",
          "owned-error");
      assertEquals(0, firstResult.get());
      assertEquals(before, payFacts());
      assertEquals("REFUND_PENDING", paymentStatus());
      assertEquals("INIT", intentStatus());
      assertEquals(1, intentAttempts());
      assertEquals(token, intentLeaseOwner());
      verify(observedIntents, never())
          .recordRetryOwnedLegacy(any(), any(), any(), any(), any(), any());
    } finally {
      records = actualRecords;
      intents = actualIntents;
    }
  }

  /** Order故障期间PAY新退款方向拒绝first0，保原lease到期后同pay沿退款恢复。 */
  @Test
  void retryFirstZeroAfterOrderFailureKeepsRefundDirectionAndResumes() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
    var afterChange =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              fixture.update("UPDATE pay_record SET status='REFUND_PENDING' WHERE pay_no='pay-1'");
              afterChange.set(payFacts());
              throw new IllegalStateException("owned-order-read-unavailable");
            })
        .when(orders)
        .paymentState("order-1", 7L);
    PayDebitIntentMapper actualIntents = intents;
    var observedIntents = org.mockito.Mockito.spy(actualIntents);
    intents = observedIntents;
    try {
      var service = newService(user, orders);
      assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
      assertEquals(afterChange.get(), payFacts());
      assertEquals("REFUND_PENDING", paymentStatus());
      assertEquals("INIT", intentStatus());
      assertEquals(1, intentAttempts());
      assertNotNull(intentLeaseOwner());
      verify(observedIntents, never())
          .recordRetryOwnedLegacy(any(), any(), any(), any(), any(), any());
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      fixture.update(
          "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED")))
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", paymentStatus());
      assertEquals("REFUNDED", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    } finally {
      intents = actualIntents;
    }
  }

  /** 旧INIT快照之后当前intent进入退款方向时，阶段和重试均不得倒回正向。 */
  @Test
  void ownedRefundIntentCannotRegressUnderStaleStageOrRetry() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String currentStatus : List.of("REFUND_PENDING", "refund_pending", "REFUND_PENDING ")) {
      for (String targetStatus : List.of("DEBITED", "INIT", "ORDER_SYNC_UNKNOWN")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              String token = "t149-owned-token";
              assertEquals(
                  1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
              var expected = intents.findByPayNo("pay-1");
              fixture.update(
                  "UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", currentStatus);
              var before = payFacts();
              var service = newService(mock(UserClient.class), mock(OrderClient.class));
              if ("DEBITED".equals(targetStatus)) {
                var failure =
                    org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () ->
                            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                service, "commitOwnedLegacyStage", expected, token, targetStatus));
                assertEquals("支付终态写入未满足当前租约", failure.getMessage());
              } else
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    service,
                    "recordOwnedLegacyRetry",
                    expected,
                    token,
                    targetStatus,
                    "owned-error");
              assertEquals(before, payFacts(), currentStatus + ":" + targetStatus);
              assertEquals("PENDING", paymentStatus());
              assertEquals(currentStatus, intentStatus());
              assertEquals(token, intentLeaseOwner());
              assertEquals(1, intentAttempts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 现有列等价退款状态只接受精确退款目标；正常owned SQL及资格/SET保持。 */
  @Test
  void ownedRefundIntentRequiresExactRefundTargetAndKeepsHealthyWrites() {
    for (String currentStatus : List.of("REFUND_PENDING", "refund_pending", "REFUND_PENDING ")) {
      for (String targetStatus : List.of("REFUND_PENDING", "refund_pending", "REFUND_PENDING ")) {
        for (String operation : List.of("stage", "retry")) {
          seedIntentAndPayment("PENDING", "INIT");
          var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
          String token = "t149-target-token";
          assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
          fixture.update(
              "UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", currentStatus);
          var before = payFacts();
          int changed =
              "stage".equals(operation)
                  ? intents.updateOwnedLegacyStatus("pay-1", token, targetStatus, currentTime)
                  : intents.recordRetryOwnedLegacy(
                      "pay-1",
                      token,
                      targetStatus,
                      "owned-error",
                      currentTime.plusSeconds(5),
                      currentTime);
          boolean exactTarget = "REFUND_PENDING".equals(targetStatus);
          assertEquals(
              exactTarget ? 1 : 0, changed, currentStatus + ":" + targetStatus + ":" + operation);
          if (!exactTarget) assertEquals(before, payFacts());
          else {
            assertEquals("REFUND_PENDING", intentStatus());
            assertEquals(before.get(0), payFacts().get(0));
            assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            if ("stage".equals(operation)) assertEquals(token, intentLeaseOwner());
            else {
              assertNull(intentLeaseOwner());
              assertTrue(retryErrorPresent());
            }
          }
        }
      }
    }
  }

  /** Order读取后intent新退款方向在SQL时挡住DEBITED/paid，取消补证后同pay恢复。 */
  @Test
  void lateRefundIntentStopsOrderPaymentAndRecovers() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              fixture.update(
                  "UPDATE pay_debit_intent SET status='REFUND_PENDING' WHERE pay_no='pay-1'");
              return ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED"));
            })
        .when(orders)
        .paymentState("order-1", 7L);
    var service = newService(user, orders);
    assertEquals("REFUND_PENDING", service.process("pay-1"));
    assertEquals("PENDING", paymentStatus());
    assertEquals("REFUND_PENDING", intentStatus());
    assertEquals(1, intentAttempts());
    assertNull(intentLeaseOwner());
    assertTrue(retryErrorPresent());
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    verify(orders, never()).paid(any(), any());
    fixture.update(
        "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE pay_no='pay-1'");
    org.mockito.Mockito.doReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED")))
        .when(orders)
        .paymentState("order-1", 7L);
    assertEquals("REFUNDED", service.process("pay-1"));
    assertEquals("REFUNDED", paymentStatus());
    assertEquals("REFUNDED", intentStatus());
    assertEquals(2, intentAttempts());
    assertNull(intentLeaseOwner());
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
    assertEquals("pay-1", records.selectByPayNo("pay-1").get(0).payNo);
    assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
    assertEquals(new BigDecimal("10.00"), records.selectByPayNo("pay-1").get(0).amount);
    verify(user, never()).debit(any(), any(), any());
    verify(user).refund(any(), any(), any());
    verify(orders, never()).paid(any(), any());
  }

  /** 旧INIT快照后意图新退款方向，不得被SUCCESS/FAILED两表终态覆盖。 */
  @Test
  void ownedRefundIntentRejectsStaleSuccessAndFailureTerminals() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String currentStatus : List.of("REFUND_PENDING", "refund_pending", "REFUND_PENDING ")) {
      for (String terminalStatus : List.of("SUCCESS", "FAILED")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              String token = "t150-owned-token";
              assertEquals(
                  1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
              var expected = intents.findByPayNo("pay-1");
              fixture.update(
                  "UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", currentStatus);
              var before = payFacts();
              var service = newService(mock(UserClient.class), mock(OrderClient.class));
              var failure =
                  org.junit.jupiter.api.Assertions.assertThrows(
                      IllegalStateException.class,
                      () ->
                          org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                              service,
                              "commitOwnedLegacyTerminal",
                              expected,
                              token,
                              terminalStatus));
              assertEquals("支付终态写入未满足当前租约", failure.getMessage());
              assertEquals(before, payFacts(), currentStatus + ":" + terminalStatus);
              assertEquals("PENDING", paymentStatus());
              assertEquals(currentStatus, intentStatus());
              assertEquals(token, intentLeaseOwner());
              assertEquals(1, intentAttempts());
              assertNull(records.selectByPayNo("pay-1").get(0).paidAt);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 当前退款方向仅允许精确已退目标，原finish SET/错误清理/期限保持。 */
  @Test
  void ownedRefundIntentFinishRequiresExactRefundedTarget() {
    for (String currentStatus : List.of("REFUND_PENDING", "refund_pending", "REFUND_PENDING ")) {
      for (String targetStatus :
          List.of("REFUNDED", "refunded", "REFUNDED ", "SUCCESS", "FAILED")) {
        seedIntentAndPayment("REFUND_PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String token = "t150-target-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        fixture.update(
            "UPDATE pay_debit_intent SET status=?,last_error='owned-old-error' WHERE"
                + " pay_no='pay-1'",
            currentStatus);
        var before = payFacts();
        int changed = intents.finishOwnedLegacy("pay-1", token, targetStatus, currentTime);
        boolean exactTarget = "REFUNDED".equals(targetStatus);
        assertEquals(exactTarget ? 1 : 0, changed, currentStatus + ":" + targetStatus);
        if (!exactTarget) assertEquals(before, payFacts());
        else {
          assertEquals("REFUNDED", intentStatus());
          assertNull(intentLeaseOwner());
          assertNull(
              fixture.queryForObject(
                  "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
          assertEquals(currentTime.plusYears(100), intentNextRetry());
          assertEquals(before.get(0), payFacts().get(0));
          assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        }
      }
    }
  }

  /** SUCCESS末次User读取后意图转退款，PAY成功及paid_at回滚，取消补证后同pay退款。 */
  @Test
  void lateRefundIntentBlocksSuccessTerminalAndRecovers() {
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
    var userReads = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              if (userReads.incrementAndGet() == 2)
                fixture.update(
                    "UPDATE pay_debit_intent SET status='REFUND_PENDING' WHERE pay_no='pay-1'");
              return ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", null));
            })
        .when(user)
        .operation(7L, "pay-1", 7L);
    PayDebitIntentMapper actualIntents = intents;
    var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
    var sawSuccessBeforeFinish = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertTrue(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              assertEquals("SUCCESS", paymentStatus());
              assertNotNull(records.selectByPayNo("pay-1").get(0).paidAt);
              sawSuccessBeforeFinish.set(true);
              return invocation.callRealMethod();
            })
        .when(instrumentedIntents)
        .finishOwnedLegacy(eq("pay-1"), anyString(), eq("SUCCESS"), any());
    intents = instrumentedIntents;
    try {
      var service = newService(user, orders);
      assertEquals("REFUND_PENDING", service.process("pay-1"));
      assertEquals(2, userReads.get());
      assertTrue(sawSuccessBeforeFinish.get());
      assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
      assertNull(records.selectByPayNo("pay-1").get(0).paidAt);
      assertEquals("REFUND_PENDING", intentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      assertTrue(retryErrorPresent());
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED")))
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", paymentStatus());
      assertEquals("REFUNDED", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertNull(records.selectByPayNo("pay-1").get(0).paidAt);
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    } finally {
      intents = actualIntents;
    }
  }

  /** 旧lease查真后tuple漂移，三RPC不能对旧身份金额写入；前置阶段/claim保持。 */
  @Test
  void changedTupleAfterLeaseReadStopsLegacyWritingRpc() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String operation : List.of("DEBIT", "PAID", "REFUND")) {
      for (String side : List.of("payment", "intent", "both")) {
        for (String mutation : List.of("user", "order", "order-alias", "amount")) {
          assertions.add(
              () -> {
                seedIntentAndPayment("PENDING", "INIT");
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath(
                    "DEBIT".equals(operation)
                        ? "fresh-debit"
                        : "PAID".equals(operation) ? "paid" : "applied-cancel",
                    "order-1",
                    user,
                    orders);
                if ("PAID".equals(operation))
                  when(orders.paymentState("order-1", 7L))
                      .thenReturn(
                          ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")));
                PayDebitIntentMapper actualIntents = intents;
                var guardedIntents = org.mockito.Mockito.spy(actualIntents);
                var afterChange =
                    new java.util.concurrent.atomic.AtomicReference<
                        List<List<Map<String, Object>>>>();
                org.mockito.Mockito.doAnswer(
                        invocation -> {
                          org.junit.jupiter.api.Assertions.assertFalse(
                              org.springframework.transaction.support
                                  .TransactionSynchronizationManager.isActualTransactionActive());
                          assertTrue((Boolean) invocation.callRealMethod());
                          changeStageSourceTuple(side, mutation);
                          afterChange.set(payFacts());
                          return true;
                        })
                    .when(guardedIntents)
                    .hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
                intents = guardedIntents;
                try {
                  String expected =
                      "DEBIT".equals(operation)
                          ? "INIT"
                          : "PAID".equals(operation) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
                  String result = newService(user, orders).process("pay-1");
                  verify(user, never()).debit(any(), any(), any());
                  verify(user, never()).refund(any(), any(), any());
                  verify(orders, never()).paid(any(), any());
                  assertEquals(expected, result);
                  assertNotNull(afterChange.get());
                  assertEquals(afterChange.get(), payFacts());
                  assertEquals(1, intentAttempts());
                  assertNotNull(intentLeaseOwner());
                } finally {
                  intents = actualIntents;
                }
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** permission单只读SQL按操作phase/原tuple/资格拒绝，不锁或更改任何facts。 */
  @Test
  void legacyRpcPermissionMatchesExactPhaseAndQualification() {
    for (String operation : List.of("DEBIT", "PAID", "REFUND")) {
      for (String scenario :
          List.of(
              "healthy",
              "phase-other",
              "phase-alias",
              "operation-alias",
              "operation-unknown",
              "payment-missing",
              "intent-missing",
              "owner-other",
              "owner-alias",
              "lease-expired",
              "payment-tcc",
              "intent-tcc",
              "amount-other",
              "user-other",
              "order-alias")) {
        seedIntentAndPayment(
            "DEBIT".equals(operation)
                ? "PENDING"
                : "PAID".equals(operation) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING",
            "DEBIT".equals(operation)
                ? "INIT"
                : "PAID".equals(operation) ? "DEBITED" : "REFUND_PENDING");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String token = "t151-qualified-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        String requestedOperation = operation;
        switch (scenario) {
          case "phase-other" ->
              fixture.update("UPDATE pay_record SET status='SUCCESS' WHERE pay_no='pay-1'");
          case "phase-alias" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET status=LOWER(status) WHERE pay_no='pay-1'");
          case "operation-alias" ->
              requestedOperation = operation.toLowerCase(java.util.Locale.ROOT);
          case "operation-unknown" -> requestedOperation = "UNKNOWN";
          case "payment-missing" -> fixture.update("DELETE FROM pay_record WHERE pay_no='pay-1'");
          case "intent-missing" ->
              fixture.update("DELETE FROM pay_debit_intent WHERE pay_no='pay-1'");
          case "owner-other" -> token = "other-owner";
          case "owner-alias" -> token = token.toUpperCase(java.util.Locale.ROOT);
          case "lease-expired" ->
              fixture.update(
                  "UPDATE pay_debit_intent SET lease_until=? WHERE pay_no='pay-1'",
                  currentTime.minusSeconds(1));
          case "payment-tcc" ->
              fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "intent-tcc" ->
              fixture.update("UPDATE pay_debit_intent SET debit_mode='TCC' WHERE pay_no='pay-1'");
          case "amount-other" -> changeStageSourceTuple("both", "amount");
          case "user-other" -> changeStageSourceTuple("both", "user");
          case "order-alias" -> changeStageSourceTuple("both", "order-alias");
          default -> {}
        }
        var before = payFacts();
        assertEquals(
            "healthy".equals(scenario),
            intents.hasOwnedLegacyRpcPermission(expected, token, requestedOperation, currentTime),
            operation + ":" + scenario);
        assertEquals(before, payFacts());
      }
    }
  }

  /** 三写RPC各增加一次permission读，读与实际RPC均不在本地事务内。 */
  @Test
  void healthyLegacyWritingRpcUsesPermissionOutsideTransactions() {
    for (String operation : List.of("DEBIT", "PAID", "REFUND")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "DEBIT".equals(operation)
              ? "fresh-debit"
              : "PAID".equals(operation) ? "paid" : "applied-cancel",
          "order-1",
          user,
          orders);
      if ("PAID".equals(operation))
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                ApiResponse.ok(
                    new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
      PayDebitIntentMapper actualIntents = intents;
      var instrumentedIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertTrue((Boolean) invocation.callRealMethod());
                return true;
              })
          .when(instrumentedIntents)
          .hasOwnedLegacyRpcPermission(any(), anyString(), anyString(), any());
      intents = instrumentedIntents;
      try {
        assertEquals(
            "REFUND".equals(operation) ? "REFUNDED" : "SUCCESS",
            newService(user, orders).process("pay-1"));
        verify(instrumentedIntents)
            .hasOwnedLegacyRpcPermission(
                any(), anyString(), eq("DEBIT".equals(operation) ? "DEBIT" : operation), any());
        // fresh debit还需订单paid，原两个写RPC各有一次permission，不将它们混为一个。
        if ("DEBIT".equals(operation))
          verify(instrumentedIntents)
              .hasOwnedLegacyRpcPermission(any(), anyString(), eq("PAID"), any());
        verify(user, times("DEBIT".equals(operation) ? 1 : 0)).debit(any(), any(), any());
        verify(user, times("REFUND".equals(operation) ? 1 : 0)).refund(any(), any(), any());
        verify(orders, times("REFUND".equals(operation) ? 0 : 1)).paid(any(), any());
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 旧lease读后当前退款或phase变化，即使tuple一致也不发旧写RPC。 */
  @Test
  void changedPhaseAfterLeaseReadStopsWritingRpc() {
    for (String operation : List.of("DEBIT", "PAID", "REFUND")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "DEBIT".equals(operation)
              ? "fresh-debit"
              : "PAID".equals(operation) ? "paid" : "applied-cancel",
          "order-1",
          user,
          orders);
      if ("PAID".equals(operation))
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")));
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                assertTrue((Boolean) invocation.callRealMethod());
                fixture.update(
                    "UPDATE pay_debit_intent SET status='REFUND_PENDING' WHERE pay_no='pay-1'");
                fixture.update(
                    "UPDATE pay_record SET status=? WHERE pay_no='pay-1'",
                    "REFUND".equals(operation) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING");
                return true;
              })
          .when(guardedIntents)
          .hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
      intents = guardedIntents;
      try {
        assertEquals("REFUND_PENDING", newService(user, orders).process("pay-1"));
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
        assertEquals("REFUND_PENDING", intentStatus());
        assertEquals(
            "REFUND".equals(operation) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING", paymentStatus());
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        assertTrue(retryErrorPresent());
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 原INIT快照后当前INT已推进，INIT retry不能抹去当前阶段/租约/证据。 */
  @Test
  void initialRetryCannotRegressAdvancedIntentAfterObservation() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String currentStatus :
        List.of(
            "DEBITED",
            "debited",
            "DEBITED ",
            "ORDER_SYNC_UNKNOWN",
            "order_sync_unknown",
            "ORDER_SYNC_UNKNOWN ")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            String token = "t152-owned-token";
            assertEquals(
                1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
            var expected = intents.findByPayNo("pay-1");
            fixture.update(
                "UPDATE pay_debit_intent SET status=?,last_error='owned-old-evidence' WHERE"
                    + " pay_no='pay-1'",
                currentStatus);
            var before = payFacts();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                newService(mock(UserClient.class), mock(OrderClient.class)),
                "recordOwnedLegacyRetry",
                expected,
                token,
                "INIT",
                "owned-new-error");
            assertEquals(before, payFacts(), currentStatus);
            assertEquals(currentStatus, intentStatus());
            assertEquals(token, intentLeaseOwner());
            assertEquals(1, intentAttempts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** INIT目标只从原初始状态推进；其它合法等待/退款目标与原SET资格保持。 */
  @Test
  void initialRetryKeepsHealthyInitialAndAdvancedRetryTargets() {
    for (String currentStatus :
        List.of("INIT", "init", "INIT ", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String targetStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String token = "t152-target-token";
        assertEquals(1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
        fixture.update(
            "UPDATE pay_debit_intent SET status=?,last_error='owned-old-evidence' WHERE"
                + " pay_no='pay-1'",
            currentStatus);
        var before = payFacts();
        boolean currentInitial = List.of("INIT", "init", "INIT ").contains(currentStatus);
        boolean allowed =
            "REFUND_PENDING".equals(targetStatus)
                || ("INIT".equals(targetStatus) && currentInitial)
                || ("ORDER_SYNC_UNKNOWN".equals(targetStatus)
                    && !"REFUND_PENDING".equals(currentStatus));
        int changed =
            intents.recordRetryOwnedLegacy(
                "pay-1",
                token,
                targetStatus,
                "owned-new-error",
                currentTime.plusSeconds(5),
                currentTime);
        assertEquals(allowed ? 1 : 0, changed, currentStatus + ":" + targetStatus);
        if (!allowed) assertEquals(before, payFacts());
        else {
          assertEquals(targetStatus, intentStatus());
          assertNull(intentLeaseOwner());
          assertEquals(currentTime.plusSeconds(5), intentNextRetry());
          assertEquals(
              "owned-new-error",
              fixture.queryForObject(
                  "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
          assertEquals(before.get(0), payFacts().get(0));
          assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        }
      }
    }
  }

  /** 当前阶段在旧lease读后已推进，debit和INIT cleanup均拒绝；lease过期后原账本恢复。 */
  @Test
  void advancedPhaseRejectsStaleInitialRetryAndResumesWithoutDebit() {
    for (String advancedStatus : List.of("DEBITED", "ORDER_SYNC_UNKNOWN")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertTrue((Boolean) invocation.callRealMethod());
                fixture.update(
                    "UPDATE pay_record SET status='ORDER_SYNC_UNKNOWN' WHERE pay_no='pay-1'");
                fixture.update(
                    "UPDATE pay_debit_intent SET status=?,last_error='owned-existing-recovery'"
                        + " WHERE pay_no='pay-1'",
                    advancedStatus);
                afterChange.set(payFacts());
                return true;
              })
          .when(guardedIntents)
          .hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
      intents = guardedIntents;
      try {
        var service = newService(user, orders);
        assertEquals("INIT", service.process("pay-1"));
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals(advancedStatus, intentStatus());
        assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
        assertEquals(1, intentAttempts());
        assertNotNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
        fixture.update(
            "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE"
                + " pay_no='pay-1'");
        assertEquals("SUCCESS", service.process("pay-1"));
        assertEquals("SUCCESS", paymentStatus());
        assertEquals("SUCCESS", intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** INIT重试不能清除支付单已经推进而意图仍初始的原资格和恢复证据。 */
  @Test
  void initialRetryRejectsAdvancedPaymentAfterObservation() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStage :
        List.of(
            "ORDER_SYNC_UNKNOWN",
            "order_sync_unknown",
            "ORDER_SYNC_UNKNOWN ",
            "REFUND_PENDING",
            "refund_pending",
            "REFUND_PENDING ")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            String token = "t154-owned-token";
            assertEquals(
                1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
            var expected = intents.findByPayNo("pay-1");
            fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
            fixture.update(
                "UPDATE pay_debit_intent SET last_error='owned-payment-recovery-proof' WHERE"
                    + " pay_no='pay-1'");
            var before = payFacts();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                newService(mock(UserClient.class), mock(OrderClient.class)),
                "recordOwnedLegacyRetry",
                expected,
                token,
                "INIT",
                "late-initial-error");
            assertEquals(before, payFacts(), paymentStage);
            assertEquals(token, intentLeaseOwner());
            assertEquals(1, intentAttempts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** INIT仅接受初始两表，CI初始等价及其它原合法等待目标保持。 */
  @Test
  void initialRetrySqlRequiresPaymentPendingAndKeepsOtherTargets() {
    for (String paymentStage :
        List.of(
            "PENDING",
            "pending",
            "PENDING ",
            "ORDER_SYNC_UNKNOWN",
            "order_sync_unknown",
            "ORDER_SYNC_UNKNOWN ",
            "REFUND_PENDING",
            "refund_pending",
            "REFUND_PENDING ",
            "SUCCESS",
            "FAILED",
            "REFUNDED",
            "UNKNOWN")) {
      seedIntentAndPayment("PENDING", "INIT");
      var currentTime =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
              .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      assertEquals(
          1, intents.claim("pay-1", "t154-pair-token", currentTime.plusSeconds(30), currentTime));
      fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
      fixture.update(
          "UPDATE pay_debit_intent SET last_error='owned-existing-recovery' WHERE pay_no='pay-1'");
      var before = payFacts();
      boolean allowed = List.of("PENDING", "pending", "PENDING ").contains(paymentStage);
      assertEquals(
          allowed ? 1 : 0,
          intents.recordRetryOwnedLegacy(
              "pay-1",
              "t154-pair-token",
              "INIT",
              "owned-initial-error",
              currentTime.plusSeconds(5),
              currentTime),
          paymentStage);
      if (!allowed) assertEquals(before, payFacts());
      else {
        assertNull(intentLeaseOwner());
        assertEquals(currentTime.plusSeconds(5), intentNextRetry());
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
    for (String paymentStage :
        List.of(
            "ORDER_SYNC_UNKNOWN",
            "order_sync_unknown",
            "ORDER_SYNC_UNKNOWN ",
            "REFUND_PENDING",
            "refund_pending",
            "REFUND_PENDING ")) {
      for (String target : List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            1,
            intents.claim("pay-1", "t154-other-token", currentTime.plusSeconds(30), currentTime));
        fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
        var before = payFacts();
        assertEquals(
            1,
            intents.recordRetryOwnedLegacy(
                "pay-1",
                "t154-other-token",
                target,
                "x".repeat(600),
                currentTime.plusSeconds(5),
                currentTime));
        assertEquals(target, intentStatus());
        assertNull(intentLeaseOwner());
        assertEquals(currentTime.plusSeconds(5), intentNextRetry());
        assertEquals(
            "x".repeat(512),
            fixture.queryForObject(
                "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
    for (String paymentStage : List.of("PENDING", "pending", "PENDING ")) {
      for (String intentStage : List.of("INIT", "init", "INIT ")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            1,
            intents.claim("pay-1", "t154-healthy-token", currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
        fixture.update("UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", intentStage);
        var before = payFacts();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            newService(mock(UserClient.class), mock(OrderClient.class)),
            "recordOwnedLegacyRetry",
            expected,
            "t154-healthy-token",
            "INIT",
            "owned-initial-error");
        assertEquals("INIT", intentStatus());
        assertNull(intentLeaseOwner());
        assertEquals(
            "owned-initial-error",
            fixture.queryForObject(
                "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class));
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
  }

  /** 旧DEBIT权限检查后仅支付单推进，INIT retry拒绝，补证后沿原记录结清。 */
  @Test
  void paymentOnlyAdvanceRejectsInitialRetryAndResumesOriginalRoute() {
    for (String paymentStage : List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
      boolean refund = "REFUND_PENDING".equals(paymentStage);
      if (refund) {
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED")));
      }
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      var mutation = new java.util.concurrent.atomic.AtomicBoolean();
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertTrue((Boolean) invocation.callRealMethod());
                if (mutation.compareAndSet(false, true)) {
                  fixture.update(
                      "UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
                  fixture.update(
                      "UPDATE pay_debit_intent SET last_error='owned-payment-recovery-proof' WHERE"
                          + " pay_no='pay-1'");
                  afterChange.set(payFacts());
                }
                return true;
              })
          .when(guardedIntents)
          .hasOwnedLegacyLease(eq("pay-1"), anyString(), any());
      intents = guardedIntents;
      try {
        var service = newService(user, orders);
        assertEquals("INIT", service.process("pay-1"));
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals("INIT", intentStatus());
        assertEquals(paymentStage, paymentStatus());
        assertEquals(1, intentAttempts());
        assertNotNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
        fixture.update(
            "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE"
                + " pay_no='pay-1'");
        String terminal = refund ? "REFUNDED" : "SUCCESS";
        assertEquals(terminal, service.process("pay-1"));
        assertEquals(terminal, paymentStatus());
        assertEquals(terminal, intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        verify(user, never()).debit(any(), any(), any());
        verify(orders, never()).paid(any(), any());
        if (refund) verify(user).refund(any(), any(), any());
        else verify(user, never()).refund(any(), any(), any());
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 旧初始观察之后任何一边已推进，FAILED不得抹去当前pair/租约/证据。 */
  @Test
  void failedTerminalRejectsAdvancedPaymentOrIntentAfterObservation() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String scenario :
        List.of(
            "intent-debited",
            "intent-ordsync",
            "intent-debited-alias",
            "intent-ordsync-alias",
            "intent-debited-space",
            "intent-ordsync-space",
            "payment-ordsync",
            "payment-ordsync-alias",
            "payment-ordsync-space",
            "both")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            String token = "t153-owned-token";
            assertEquals(
                1, intents.claim("pay-1", token, currentTime.plusSeconds(30), currentTime));
            var expected = intents.findByPayNo("pay-1");
            String intentStatus =
                switch (scenario) {
                  case "intent-debited", "both" -> "DEBITED";
                  case "intent-ordsync" -> "ORDER_SYNC_UNKNOWN";
                  case "intent-debited-alias" -> "debited";
                  case "intent-ordsync-alias" -> "order_sync_unknown";
                  case "intent-debited-space" -> "DEBITED ";
                  case "intent-ordsync-space" -> "ORDER_SYNC_UNKNOWN ";
                  default -> "INIT";
                };
            String paymentStatus =
                switch (scenario) {
                  case "payment-ordsync", "both" -> "ORDER_SYNC_UNKNOWN";
                  case "payment-ordsync-alias" -> "order_sync_unknown";
                  case "payment-ordsync-space" -> "ORDER_SYNC_UNKNOWN ";
                  default -> "PENDING";
                };
            fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStatus);
            fixture.update(
                "UPDATE pay_debit_intent SET status=?,last_error='owned-recovery-proof' WHERE"
                    + " pay_no='pay-1'",
                intentStatus);
            var before = payFacts();
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () ->
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            newService(mock(UserClient.class), mock(OrderClient.class)),
                            "commitOwnedLegacyTerminal",
                            expected,
                            token,
                            "FAILED"));
            assertEquals("支付终态写入未满足当前租约", failure.getMessage());
            assertEquals(before, payFacts(), scenario);
            assertEquals(token, intentLeaseOwner());
            assertEquals(1, intentAttempts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** FAILED首SQL仅当前初始pair可写；finish仅INIT可FAILED，其它正确目标保持。 */
  @Test
  void failedOwnedSqlRequiresCurrentInitialPairAndKeepsOtherTargets() {
    for (String paymentStage :
        List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING", "SUCCESS")) {
      for (String intentStage :
          List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            1, intents.claim("pay-1", "t153-pair-token", currentTime.plusSeconds(30), currentTime));
        fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
        fixture.update("UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", intentStage);
        var before = payFacts();
        boolean initialPair = "PENDING".equals(paymentStage) && "INIT".equals(intentStage);
        assertEquals(
            initialPair ? 1 : 0,
            records.markOwnedLegacyFailed("pay-1", "t153-pair-token", currentTime));
        if (!initialPair) assertEquals(before, payFacts());
        else assertEquals("FAILED", paymentStatus());
      }
    }
    for (String currentStage :
        List.of("INIT", "init", "INIT ", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String target : List.of("FAILED", "SUCCESS", "REFUNDED")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            1,
            intents.claim("pay-1", "t153-target-token", currentTime.plusSeconds(30), currentTime));
        fixture.update(
            "UPDATE pay_debit_intent SET status=?,last_error='owned-old-evidence' WHERE"
                + " pay_no='pay-1'",
            currentStage);
        var before = payFacts();
        boolean currentInitial = List.of("INIT", "init", "INIT ").contains(currentStage);
        boolean allowed =
            "REFUNDED".equals(target)
                || ("FAILED".equals(target) && currentInitial)
                || ("SUCCESS".equals(target) && !"REFUND_PENDING".equals(currentStage));
        assertEquals(
            allowed ? 1 : 0,
            intents.finishOwnedLegacy("pay-1", "t153-target-token", target, currentTime));
        if (!allowed) assertEquals(before, payFacts());
        else {
          assertEquals(target, intentStatus());
          assertNull(intentLeaseOwner());
          assertEquals(currentTime.plusYears(100), intentNextRetry());
          assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        }
      }
    }
    for (String paymentStage : List.of("PENDING", "pending", "PENDING ")) {
      for (String intentStage : List.of("INIT", "init", "INIT ")) {
        seedIntentAndPayment("PENDING", "INIT");
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(
            1,
            intents.claim("pay-1", "t153-healthy-token", currentTime.plusSeconds(30), currentTime));
        var expected = intents.findByPayNo("pay-1");
        fixture.update("UPDATE pay_record SET status=? WHERE pay_no='pay-1'", paymentStage);
        fixture.update("UPDATE pay_debit_intent SET status=? WHERE pay_no='pay-1'", intentStage);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            newService(mock(UserClient.class), mock(OrderClient.class)),
            "commitOwnedLegacyTerminal",
            expected,
            "t153-healthy-token",
            "FAILED");
        assertEquals("FAILED", paymentStatus());
        assertEquals("FAILED", intentStatus());
        assertNull(intentLeaseOwner());
      }
    }
  }

  /** 无账本取消末次User 404时pair已推进，FAILED/INIT retry均拒绝，后续原来源退款。 */
  @Test
  void lateAdvancedPairRejectsAbsenceFailureAndRecoversAfterProof() {
    for (String advancedStatus : List.of("DEBITED", "ORDER_SYNC_UNKNOWN")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("no-debit-cancel", "order-1", user, orders);
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      var request =
          Request.create(
              Request.HttpMethod.GET,
              "http://cloud-mall-user/owned-failed-proof",
              Map.of(),
              null,
              StandardCharsets.UTF_8);
      var missing =
          FeignException.errorStatus(
              "operation",
              feign.Response.builder()
                  .status(404)
                  .reason("owned-missing")
                  .request(request)
                  .headers(Map.of())
                  .body(new byte[0])
                  .build());
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                int readNumber = reads.incrementAndGet();
                if (readNumber == 2) {
                  fixture.update(
                      "UPDATE pay_record SET status='ORDER_SYNC_UNKNOWN' WHERE pay_no='pay-1'");
                  fixture.update(
                      "UPDATE pay_debit_intent SET status=?,last_error='owned-existing-recovery'"
                          + " WHERE pay_no='pay-1'",
                      advancedStatus);
                  afterChange.set(payFacts());
                }
                if (readNumber <= 2) throw missing;
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "APPLIED", "10.00", null));
              })
          .when(user)
          .operation(7L, "pay-1", 7L);
      var service = newService(user, orders);
      assertEquals("INIT", service.process("pay-1"));
      assertEquals(2, reads.get());
      assertNotNull(afterChange.get());
      assertEquals(afterChange.get(), payFacts());
      assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
      assertEquals(advancedStatus, intentStatus());
      assertEquals(1, intentAttempts());
      assertNotNull(intentLeaseOwner());
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      fixture.update(
          "UPDATE pay_debit_intent SET lease_until=NOW(3)-INTERVAL 1 SECOND WHERE pay_no='pay-1'");
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", paymentStatus());
      assertEquals("REFUNDED", intentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 实际CI支付号查询读到另一原拼写终态，恢复不能直接返回该原行。 */
  @Test
  void executionLookupAliasCannotReturnAnotherTerminal() {
    var store = ownedExecutionObservationStore();
    seedOwnedExecution("COMMITTED", "COMMIT", "t175-xid");
    fixture.update("UPDATE pay_tcc_execution SET pay_no='PAY-1' WHERE id=1");
    var returnedBySql = executionObservationMapper.selectByPayNo("pay-1");
    assertNotNull(returnedBySql);
    assertEquals("PAY-1", returnedBySql.payNo);
    var before = ownedExecutionLookupFacts();
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    var receiptService = mock(com.cloudmall.pay.service.PayTccReceiptService.class);
    var evidence = mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class);
    var transactions = mock(com.cloudmall.pay.service.PayTccTransactionClient.class);
    var coordinator =
        new com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl(
            store, receiptService, bridge, evidence, transactions);
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(coordinator.recover("pay-1").payNo));
          assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
        },
        () ->
            assertNull(returned.get(), "alias execution must not be returned as requested payment"),
        () -> assertEquals(before, ownedExecutionLookupFacts()),
        () ->
            org.mockito.Mockito.verifyNoInteractions(
                bridge, receiptService, evidence, transactions));
  }

  /** 真实CI别名在读、绑定、决议、观察各早返回和CAS入口均拒绝，原三表不动。 */
  @Test
  void executionLookupAliasesCannotEnterLockedActions() {
    var store = ownedExecutionObservationStore();
    for (boolean uppercaseOriginal : List.of(false, true)) {
      String original = uppercaseOriginal ? "PAY-1" : "pay-1";
      String requested = uppercaseOriginal ? "pay-1" : "PAY-1";
      for (String action :
          List.of(
              "read",
              "bind-new",
              "bind-same",
              "decide-new",
              "decide-same",
              "observe-active",
              "observe-terminal")) {
        String state =
            action.equals("bind-new")
                ? "CLAIMED"
                : action.equals("observe-terminal") || action.equals("read")
                    ? "COMMITTED"
                    : "RUNNING";
        String decision =
            action.equals("decide-same") || state.equals("COMMITTED") ? "COMMIT" : "NONE";
        seedOwnedExecution(state, decision, action.equals("bind-new") ? null : "t175-xid");
        fixture.update("UPDATE pay_tcc_execution SET pay_no=? WHERE id=1", original);
        assertEquals(original, executionObservationMapper.selectByPayNo(requested).payNo);
        assertEquals(
            original,
            executionTransaction(() -> executionObservationMapper.selectForUpdate(requested))
                .payNo);
        var before = ownedExecutionLookupFacts();
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    executionTransaction(
                        () -> {
                          switch (action) {
                            case "read" -> {
                              return store.read(requested);
                            }
                            case "bind-new", "bind-same" -> store.bindXid(requested, "t175-xid");
                            case "decide-new", "decide-same" -> {
                              return store.decide(requested, "t175-xid", "COMMIT");
                            }
                            default -> {
                              return store.observe(requested, "UNKNOWN", "alias observation");
                            }
                          }
                          return null;
                        }),
                action);
        assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
        assertEquals(before, ownedExecutionLookupFacts());
      }
    }
  }

  /** 精确原支付号保持读/合法写/幂等与原缺失错误，不把终态清理或未知改成成功。 */
  @Test
  void exactExecutionLookupKeepsWritesTerminalsAndMissingSemantics() {
    var store = ownedExecutionObservationStore();
    for (String terminal : List.of("COMMITTED", "ROLLEDBACK", "CONFLICT")) {
      seedOwnedExecution(terminal, "COMMIT", "t175-xid");
      var before = ownedExecutionLookupFacts();
      assertEquals(terminal, store.read("pay-1").state);
      assertEquals(
          terminal, executionTransaction(() -> store.observe("pay-1", "UNKNOWN", "late")).state);
      assertEquals(before, ownedExecutionLookupFacts());
    }
    seedOwnedExecution("CLAIMED", "NONE", null);
    var payBefore = rawIntentTimeFacts();
    executionTransaction(
        () -> {
          store.bindXid("pay-1", "t175-xid");
          return null;
        });
    assertEquals("RUNNING", store.read("pay-1").state);
    assertEquals("t175-xid", store.read("pay-1").xid);
    var bound = ownedExecutionLookupFacts();
    executionTransaction(
        () -> {
          store.bindXid("pay-1", "t175-xid");
          return null;
        });
    assertEquals(bound, ownedExecutionLookupFacts());
    assertEquals(
        "COMMIT_REQUESTED",
        executionTransaction(() -> store.decide("pay-1", "t175-xid", "COMMIT")).state);
    var decided = ownedExecutionLookupFacts();
    assertEquals(
        "COMMIT_REQUESTED",
        executionTransaction(() -> store.decide("pay-1", "t175-xid", "COMMIT")).state);
    assertEquals(decided, ownedExecutionLookupFacts());
    var observed = executionTransaction(() -> store.observe("pay-1", "UNKNOWN", "owned-new-error"));
    assertEquals("COMMIT_REQUESTED", observed.state);
    assertEquals("COMMIT", observed.decision);
    assertEquals("owned-new-error", observed.lastError);
    assertEquals(payBefore, rawIntentTimeFacts());
    var beforeMissing = ownedExecutionLookupFacts();
    var absent =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class, () -> store.read("absent-pay"));
    assertEquals("PAY_TCC_EXECUTION_NOT_FOUND", absent.getCode());
    assertEquals(404, absent.getHttpStatus());
    for (String action : List.of("bind", "decide", "observe")) {
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () ->
                  executionTransaction(
                      () -> {
                        if (action.equals("bind")) {
                          store.bindXid("absent-pay", "t175-xid");
                          return null;
                        }
                        if (action.equals("decide"))
                          return store.decide("absent-pay", "t175-xid", "COMMIT");
                        return store.observe("absent-pay", "UNKNOWN", "absent");
                      }));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(beforeMissing, ownedExecutionLookupFacts());
    }
  }

  /** 原Pay/Intent与完整execution事实，只读或冲突不得改写。 */
  private List<List<Map<String, Object>>> ownedExecutionLookupFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    facts.add(fixture.queryForList("SELECT * FROM pay_tcc_execution ORDER BY id"));
    return facts;
  }

  /** 实际CI查询读到另一拼写XID，不能据该Fence输出COMMITTED。 */
  @Test
  void alienFenceXidCannotProveCommittedFunds() {
    var evidence = ownedFenceIdentityEvidence();
    seedOwnedFenceEvidence("CONFIRMED", 2, "T174-XID", 11L);
    var matched = fenceIdentityMapper.findByBranch("t174-xid", 11L);
    assertNotNull(matched);
    assertEquals("T174-XID", matched.xid);
    assertEquals(11L, matched.branchId);
    var before = ownedFenceFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(classifyOwnedFence(evidence, "CONFIRMED")));
          assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
        },
        () -> assertNull(returned.get(), "alien Fence must not produce terminal funds"),
        () -> assertEquals(before, ownedFenceFacts()));
  }

  /** 精确匹配的原Fence在真实查询下保持TRYING/终态/矛盾方向，完整五表不变。 */
  @Test
  void exactOwnedFenceKeepsAllConclusionsAndCleanup() {
    var evidence = ownedFenceIdentityEvidence();
    String[][] examples = {
      {"CONFIRMED", "CONFIRMED", "2", "COMMITTED"},
      {"CANCELLED", "CANCELLED", "3", "ROLLEDBACK"},
      {"CANCELLED", "CANCELLED", "4", "ROLLEDBACK"},
      {"TRYING", "TRY_ACCEPTED", "1", "TRYING"},
      {"CONFIRMED", "CANCELLED", "2", "CONFLICT"},
      {"CANCELLED", "CONFIRMED", "3", "CONFLICT"},
      {"TRYING", "CONFIRMED", "1", "UNKNOWN"}
    };
    for (String[] example : examples) {
      seedOwnedFenceEvidence(example[0], Integer.parseInt(example[2]), "t174-xid", 11L);
      var before = ownedFenceFacts();
      assertEquals(example[3], classifyOwnedFence(evidence, example[1]));
      assertEquals(before, ownedFenceFacts());
    }
    for (String state : List.of("CONFIRMED", "CANCELLED", "TRYING")) {
      int status = state.equals("TRYING") ? 1 : state.equals("CONFIRMED") ? 2 : 3;
      seedOwnedFenceEvidence(state, status, "t174-xid", 11L);
      fixture.update("DELETE FROM tcc_fence_log");
      var before = ownedFenceFacts();
      String userState = state.equals("TRYING") ? "TRY_ACCEPTED" : state;
      if (state.equals("TRYING"))
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> classifyOwnedFence(evidence, userState));
      else
        assertEquals(
            state.equals("CONFIRMED") ? "COMMITTED" : "ROLLEDBACK",
            classifyOwnedFence(evidence, userState));
      assertEquals(before, ownedFenceFacts());
    }
    seedOwnedFenceEvidence("CONFIRMED", 2, "t174-xid", 12L);
    assertNull(fenceIdentityMapper.findByBranch("t174-xid", 11L));
    var before = ownedFenceFacts();
    assertEquals("COMMITTED", classifyOwnedFence(evidence, "CONFIRMED"));
    assertEquals(before, ownedFenceFacts());
  }

  /** 真实CI返回错误XID的Fence在各合法status阶段均冲突，不修写任何来源事实。 */
  @Test
  void alienFenceOnEachFundPhaseRejectsWithoutMutation() {
    var evidence = ownedFenceIdentityEvidence();
    String[][] examples = {
      {"CONFIRMED", "CONFIRMED", "2"},
      {"CANCELLED", "CANCELLED", "3"},
      {"CANCELLED", "CANCELLED", "4"},
      {"TRYING", "TRY_ACCEPTED", "1"}
    };
    for (String[] example : examples) {
      seedOwnedFenceEvidence(example[0], Integer.parseInt(example[2]), "T174-XID", 11L);
      var lookedUp = fenceIdentityMapper.findByBranch("t174-xid", 11L);
      assertNotNull(lookedUp);
      assertEquals("T174-XID", lookedUp.xid);
      var before = ownedFenceFacts();
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> classifyOwnedFence(evidence, example[1]));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(before, ownedFenceFacts());
    }
  }

  /** CLAIMED零分支不能绕过有效域，被误分为普通UNKNOWN。 */
  @Test
  void claimedReceiptZeroBranchCannotBecomeUnknownEvidence() {
    assertClaimedReceiptBranchRejected(0L, "CONFIRMED");
  }

  /** CLAIMED负分支是矛盾事实，不能触发后续UNKNOWN恢复路径。 */
  @Test
  void claimedReceiptNegativeBranchCannotBecomeUnknownEvidence() {
    assertClaimedReceiptBranchRejected(-1L, "CONFIRMED");
  }

  /** User已完成/进行中/取消/缺状态均不能掩盖Pay CLAIMED非正分支。 */
  @Test
  void claimedReceiptEvidenceRejectsInvalidBranchAcrossUserStates() {
    for (Long branch : List.of(0L, -1L, Long.MIN_VALUE))
      for (String userState :
          java.util.Arrays.asList("CONFIRMED", "TRY_ACCEPTED", "CANCELLED", null))
        assertClaimedReceiptBranchRejected(branch, userState);
  }

  /** 合法CLAIMED的NULL/正分支保持UNKNOWN，不把存在Fence推断成资金结论。 */
  @Test
  void claimedReceiptEvidenceKeepsValidUnknownAndMissingReceipt() {
    for (Long branch : java.util.Arrays.asList(null, 1L, Long.MAX_VALUE))
      for (String userState :
          java.util.Arrays.asList("CONFIRMED", "TRY_ACCEPTED", "CANCELLED", null)) {
        var evidence = ownedFenceIdentityEvidence();
        seedOwnedFenceEvidence("CLAIMED", 1, "t174-xid", 11L);
        fixture.update("UPDATE pay_tcc_receipt SET branch_id=?", branch);
        var before = ownedFenceFacts();
        assertEquals("UNKNOWN", classifyOwnedFence(evidence, userState));
        assertEquals(before, ownedFenceFacts());
      }
    var evidence = ownedFenceIdentityEvidence();
    seedOwnedFenceEvidence("CLAIMED", 1, "t174-xid", 11L);
    fixture.update("DELETE FROM pay_tcc_receipt");
    var before = ownedFenceFacts();
    assertEquals("UNKNOWN", classifyOwnedFence(evidence, "CONFIRMED"));
    assertEquals(before, ownedFenceFacts());
  }

  /** 真Mapper及只读RR事务，不返回坏分支的UNKNOWN，不改变任何相关表。 */
  private void assertClaimedReceiptBranchRejected(Long branch, String userState) {
    var evidence = ownedFenceIdentityEvidence();
    seedOwnedFenceEvidence("CLAIMED", 1, "t174-xid", 11L);
    fixture.update("UPDATE pay_tcc_receipt SET branch_id=?", branch);
    var before = ownedFenceFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(classifyOwnedFence(evidence, userState)));
          assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () -> assertNull(returned.get()),
        () -> assertEquals(before, ownedFenceFacts()));
  }

  /** 相等的零金额不能因两端已CONFIRMED被分类为COMMITTED。 */
  @Test
  void zeroExecutionMoneyCannotProduceCommittedEvidence() {
    assertNonPositiveExecutionMoneyRejected(BigDecimal.ZERO, "CONFIRMED", false);
  }

  /** 相等的负金额不能因两端已CANCELLED被分类为ROLLEDBACK。 */
  @Test
  void negativeExecutionMoneyCannotProduceRolledBackEvidence() {
    assertNonPositiveExecutionMoneyRejected(new BigDecimal("-0.01"), "CANCELLED", false);
  }

  /** 四种receipt状态及receipt缺失都不能掩盖execution非正金额。 */
  @Test
  void nonPositiveExecutionMoneyRejectsAllStateAndMissingControls() {
    for (BigDecimal amount :
        List.of(BigDecimal.ZERO, new BigDecimal("-0.01"), new BigDecimal("-10.00"))) {
      for (String payState : List.of("CONFIRMED", "CANCELLED", "TRYING", "CLAIMED"))
        assertNonPositiveExecutionMoneyRejected(amount, payState, false);
      assertNonPositiveExecutionMoneyRejected(amount, "CONFIRMED", true);
    }
  }

  /** 正金额的原状态映射、数据库可存上下界与缺失UNKNOWN保持，不规范化输入。 */
  @Test
  void positiveExecutionMoneyKeepsExistingStateConclusions() {
    for (BigDecimal amount :
        List.of(
            new BigDecimal("0.01"), new BigDecimal("10.00"), new BigDecimal("9999999999999999.99")))
      for (String payState : List.of("CONFIRMED", "CANCELLED", "TRYING", "CLAIMED")) {
        var evidence = ownedFenceIdentityEvidence();
        seedOwnedFenceEvidence(
            payState,
            payState.equals("CONFIRMED") ? 2 : payState.equals("CANCELLED") ? 3 : 1,
            "t174-xid",
            11L);
        fixture.update("UPDATE pay_tcc_execution SET amount=?", amount);
        fixture.update("UPDATE pay_tcc_receipt SET amount=?", amount);
        String userState =
            payState.equals("TRYING")
                ? "TRY_ACCEPTED"
                : payState.equals("CLAIMED") ? "CONFIRMED" : payState;
        String expected =
            payState.equals("CONFIRMED")
                ? "COMMITTED"
                : payState.equals("CANCELLED")
                    ? "ROLLEDBACK"
                    : payState.equals("TRYING") ? "TRYING" : "UNKNOWN";
        var before = ownedFenceFacts();
        assertEquals(expected, classifyOwnedFence(evidence, userState));
        assertEquals(before, ownedFenceFacts());
      }
    var evidence = ownedFenceIdentityEvidence();
    seedOwnedFenceEvidence("CONFIRMED", 2, "t174-xid", 11L);
    fixture.update("DELETE FROM pay_tcc_receipt");
    var before = ownedFenceFacts();
    assertEquals("UNKNOWN", classifyOwnedFence(evidence, "CONFIRMED"));
    assertEquals(before, ownedFenceFacts());
  }

  /** 仅owned execution/receipt一致变异，User镜像取同一金额，原Pay/Intent不改。 */
  private void assertNonPositiveExecutionMoneyRejected(
      BigDecimal amount, String payState, boolean missingReceipt) {
    var evidence = ownedFenceIdentityEvidence();
    seedOwnedFenceEvidence(
        payState,
        payState.equals("CONFIRMED") ? 2 : payState.equals("CANCELLED") ? 3 : 1,
        "t174-xid",
        11L);
    fixture.update("UPDATE pay_tcc_execution SET amount=?", amount);
    fixture.update("UPDATE pay_tcc_receipt SET amount=?", amount);
    if (missingReceipt) fixture.update("DELETE FROM pay_tcc_receipt");
    String userState =
        payState.equals("TRYING")
            ? "TRY_ACCEPTED"
            : payState.equals("CLAIMED") ? "CONFIRMED" : payState;
    var before = ownedFenceFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(classifyOwnedFence(evidence, userState)));
          assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () -> assertNull(returned.get()),
        () -> assertEquals(before, ownedFenceFacts()));
  }

  /** 原execution/receipt/Fence表只在owned克隆，通过实际Mapper同RR快照读取。 */
  private com.cloudmall.pay.service.impl.PayTccExecutionEvidence ownedFenceIdentityEvidence() {
    ownedExecutionObservationStore();
    for (String table : List.of("pay_tcc_receipt", "tcc_fence_log")) {
      fixture.execute("DROP TABLE IF EXISTS " + table);
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    }
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccFenceMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      fenceIdentityMapper = session.getMapper(com.cloudmall.pay.mapper.PayTccFenceMapper.class);
      return new com.cloudmall.pay.service.impl.PayTccExecutionEvidence(
          session.getMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class),
          fenceIdentityMapper);
    } catch (Exception failure) {
      throw new IllegalStateException("owned Fence factory failed", failure);
    }
  }

  /** 同一个只读RR快照核对实际execution/receipt/Fence，User镜像仅受控传值。 */
  private String classifyOwnedFence(
      com.cloudmall.pay.service.impl.PayTccExecutionEvidence evidence, String userState) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    template.setReadOnly(true);
    template.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return template.execute(
        transactionStatus -> {
          var execution = executionObservationMapper.selectByPayNo("pay-1");
          var user = new com.cloudmall.pay.domain.vo.PayTccDispatchVO();
          user.payNo = execution.payNo;
          user.xid = execution.xid;
          user.userId = execution.userId;
          user.orderNo = execution.orderNo;
          user.operationKey = execution.operationKey;
          user.ledgerKey = execution.ledgerKey;
          user.amount = execution.amount.toPlainString();
          user.state = userState;
          user.userBranchId = 22L;
          return evidence.classify(execution, user);
        });
  }

  /** 生成完整两端同原XID，而Fence使用独立参数，可模拟CI别名/不同分支；只写owned。 */
  private void seedOwnedFenceEvidence(
      String payState, int status, String fenceXid, Long fenceBranch) {
    seedOwnedExecution("RUNNING", "NONE", "t174-xid");
    fixture.update("DELETE FROM pay_tcc_receipt");
    fixture.update("DELETE FROM tcc_fence_log");
    var createdAt = LocalDateTime.of(2026, 10, 1, 0, 0);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,branch_id,state,created_at,updated_at)"
            + " VALUES(1,'pay-1','t174-xid',7,'order-1',10,'tcc-debit:pay-1','pay-1',11,?,?,?)",
        payState,
        createdAt,
        createdAt);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES(?,?,?,?,?,?)",
        fenceXid,
        fenceBranch,
        com.cloudmall.pay.service.PayTccReceiptLocalResource.ACTION_NAME,
        status,
        createdAt,
        createdAt);
  }

  /** owned所有相关表完整事实，分类只读必须保持原值。 */
  private List<List<Map<String, Object>>> ownedFenceFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    for (String table : List.of("pay_tcc_execution", "pay_tcc_receipt", "tcc_fence_log"))
      facts.add(fixture.queryForList("SELECT * FROM " + table));
    return facts;
  }

  /** Java未识别而原SQL已保护的CI/PAD终态，实际写零行必须明确冲突而非静默readback。 */
  @Test
  void executionObservationZeroRowsDoNotReturnStaleRecord() {
    ownedExecutionObservationStore();
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state :
        List.of("committed", "COMMITTED ", "rolledback", "ROLLEDBACK ", "conflict", "CONFLICT ")) {
      assertions.add(
          () -> {
            seedOwnedExecution(state, "COMMIT", "t172-xid");
            var before = fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1");
            var payBefore = rawIntentTimeFacts();
            var actual = executionObservationMapper;
            var guarded =
                mock(
                    com.cloudmall.pay.mapper.PayTccExecutionMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(actual));
            var affectedRows = new java.util.concurrent.atomic.AtomicInteger(-99);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      int changed =
                          actual.updateObservation(
                              invocation.getArgument(0),
                              invocation.getArgument(1),
                              invocation.getArgument(2),
                              invocation.getArgument(3));
                      affectedRows.set(changed);
                      return changed;
                    })
                .when(guarded)
                .updateObservation(any(), any(), any(), any());
            var store = executionObservationStore(guarded);
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () ->
                        executionTransaction(
                            () -> store.observe("pay-1", "UNKNOWN", "owned-new-observation")),
                    state);
            assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
            assertEquals(0, affectedRows.get());
            assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1"));
            assertEquals(payBefore, rawIntentTimeFacts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 合法观察按原方向提交并返回最新字段，canonical终态重复观察仍全事实不动。 */
  @Test
  void executionObservationHealthyWritesAndTerminalsKeepFacts() {
    var store = ownedExecutionObservationStore();
    String[][] examples = {
      {"CLAIMED", "NONE", null, "UNKNOWN", "BEGIN_UNKNOWN"},
      {"RUNNING", "NONE", "t172-xid", "UNKNOWN", "UNKNOWN"},
      {"COMMIT_REQUESTED", "COMMIT", "t172-xid", "UNKNOWN", "COMMIT_REQUESTED"},
      {"ROLLBACK_REQUESTED", "ROLLBACK", "t172-xid", "UNKNOWN", "ROLLBACK_REQUESTED"},
      {"RUNNING", "NONE", "t172-xid", "COMMITTED", "COMMITTED"},
      {"RUNNING", "NONE", "t172-xid", "ROLLEDBACK", "ROLLEDBACK"},
      {"RUNNING", "NONE", "t172-xid", "CONFLICT", "CONFLICT"}
    };
    for (String[] example : examples) {
      seedOwnedExecution(example[0], example[1], example[2]);
      var before = fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1");
      var payBefore = rawIntentTimeFacts();
      var observed =
          executionTransaction(() -> store.observe("pay-1", example[3], "😀".repeat(513)));
      var after = fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1");
      assertEquals(example[4], observed.state);
      assertEquals(example[4], after.get("state"));
      assertEquals("😀".repeat(512), observed.lastError);
      assertEquals("😀".repeat(512), after.get("last_error"));
      assertEquals(after.get("updated_at"), observed.updatedAt);
      assertEquals(0, observed.updatedAt.getNano() % 1_000_000);
      for (String changed : List.of("state", "last_error", "updated_at")) {
        before.remove(changed);
        after.remove(changed);
      }
      assertEquals(before, after);
      assertEquals(payBefore, rawIntentTimeFacts());
    }
    for (String terminal : List.of("COMMITTED", "ROLLEDBACK", "CONFLICT")) {
      seedOwnedExecution(terminal, "COMMIT", "t172-xid");
      var before = fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1");
      assertEquals(
          terminal,
          executionTransaction(() -> store.observe("pay-1", "UNKNOWN", "new observation")).state);
      assertEquals(
          terminal,
          executionTransaction(() -> store.observe("pay-1", terminal, "new observation")).state);
      assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1"));
      String opposite = terminal.equals("COMMITTED") ? "ROLLEDBACK" : "COMMITTED";
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () ->
                  executionTransaction(() -> store.observe("pay-1", opposite, "new observation")));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1"));
    }
  }

  /** 同事务内受控终态变化使真实UPDATE零行，必须冲突且回滚全部受控变化。 */
  @Test
  void executionObservationSqlZeroRollsBackWithoutReadback() {
    ownedExecutionObservationStore();
    seedOwnedExecution("RUNNING", "NONE", "t172-xid");
    var before = fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1");
    var payBefore = rawIntentTimeFacts();
    var actual = executionObservationMapper;
    var guarded =
        mock(
            com.cloudmall.pay.mapper.PayTccExecutionMapper.class,
            org.mockito.AdditionalAnswers.delegatesTo(actual));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              fixture.update("UPDATE pay_tcc_execution SET state='COMMITTED' WHERE id=1");
              int changed =
                  actual.updateObservation(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3));
              assertEquals(0, changed);
              return changed;
            })
        .when(guarded)
        .updateObservation(any(), any(), any(), any());
    var store = executionObservationStore(guarded);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> executionTransaction(() -> store.observe("pay-1", "UNKNOWN", "new observation")));
    assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
    verify(guarded, never()).selectByPayNo(any());
    assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE id=1"));
    assertEquals(payBefore, rawIntentTimeFacts());
  }

  /** 历史空白owner不能被空白token释放execution恢复租约或改诊断。 */
  @Test
  void executionRecoveryFinishCannotReleaseBlankOwner() {
    var store = ownedExecutionRecoveryTokenStore();
    seedExecutionRecoveryToken("RUNNING", "NONE", " ");
    var before = executionRecoveryTokenFacts();
    boolean changed =
        dispatchTransaction(
            () ->
                store.finishRecovery(
                    "pay-1", " ", LocalDateTime.of(2027, 1, 1, 0, 0), "blank-recovery-proof"));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertFalse(
                changed, "blank token must not release recovery lease"),
        () -> assertEquals(before, executionRecoveryTokenFacts()));
  }

  /** emoji65虽数据库可存，但超过Java token域，不能收尾回滚投影租约。 */
  @Test
  void executionRecoveryFinishCannotReleaseOverlongOwner() {
    var store = ownedExecutionRecoveryTokenStore();
    String token = "😀".repeat(65);
    seedExecutionRecoveryToken("ROLLEDBACK", "ROLLBACK", token);
    var before = executionRecoveryTokenFacts();
    boolean changed =
        dispatchTransaction(
            () ->
                store.finishRecovery(
                    "pay-1", token, LocalDateTime.of(2027, 1, 1, 0, 0), "overlong-recovery-proof"));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertFalse(
                changed, "overlong token must not release recovery lease"),
        () -> assertEquals(before, executionRecoveryTokenFacts()));
  }

  /** 六类列可表示的非法token在普通恢复与回滚投影均不改三表。 */
  @Test
  void invalidExecutionRecoveryFinishTokensKeepAllFacts() {
    var store = ownedExecutionRecoveryTokenStore();
    for (String state : List.of("RUNNING", "ROLLEDBACK"))
      for (String token :
          java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u3000", "😀".repeat(65))) {
        seedExecutionRecoveryToken(state, state.equals("RUNNING") ? "NONE" : "ROLLBACK", token);
        var before = executionRecoveryTokenFacts();
        org.junit.jupiter.api.Assertions.assertFalse(
            dispatchTransaction(
                () ->
                    store.finishRecovery(
                        "pay-1", token, LocalDateTime.of(2027, 1, 1, 0, 0), "should-not-save")));
        assertEquals(before, executionRecoveryTokenFacts());
      }
  }

  /** 合法边界保原nextAt/Unicode诊断/资金字段，错owner/别名/过期仍零行。 */
  @Test
  void validExecutionRecoveryFinishTokensAndLeaseControlsRemainUnchanged() {
    var store = ownedExecutionRecoveryTokenStore();
    var nextAt = LocalDateTime.of(2027, 1, 1, 0, 0, 0, 123_000_000);
    for (String state : List.of("RUNNING", "ROLLEDBACK"))
      for (String token : List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64))) {
        seedExecutionRecoveryToken(state, state.equals("RUNNING") ? "NONE" : "ROLLBACK", token);
        var before = fixture.queryForMap("SELECT * FROM pay_tcc_execution");
        var paymentBefore = rawIntentTimeFacts();
        assertTrue(
            dispatchTransaction(
                () -> store.finishRecovery("pay-1", token, nextAt, "😀".repeat(513))));
        var after = fixture.queryForMap("SELECT * FROM pay_tcc_execution");
        assertNull(after.get("recovery_owner"));
        assertNull(after.get("recovery_lease_until"));
        assertEquals(nextAt, after.get("next_recovery_at"));
        assertEquals("😀".repeat(512), after.get("recovery_last_error"));
        for (String changed :
            List.of(
                "recovery_owner",
                "recovery_lease_until",
                "next_recovery_at",
                "recovery_last_error")) {
          before.remove(changed);
          after.remove(changed);
        }
        assertEquals(before, after);
        assertEquals(paymentBefore, rawIntentTimeFacts());
        var released = executionRecoveryTokenFacts();
        org.junit.jupiter.api.Assertions.assertFalse(
            dispatchTransaction(() -> store.finishRecovery("pay-1", token, nextAt, "late")));
        assertEquals(released, executionRecoveryTokenFacts());
      }
    for (String blocked : List.of("expired", "null-lease", "wrong-owner", "pay-alias", "missing")) {
      seedExecutionRecoveryToken("RUNNING", "NONE", "worker");
      if (blocked.equals("expired"))
        fixture.update(
            "UPDATE pay_tcc_execution SET recovery_lease_until=?",
            LocalDateTime.of(2026, 10, 1, 0, 0));
      if (blocked.equals("null-lease"))
        fixture.update("UPDATE pay_tcc_execution SET recovery_lease_until=NULL");
      String requestedPay =
          blocked.equals("pay-alias")
              ? "PAY-1"
              : blocked.equals("missing") ? "missing-pay" : "pay-1";
      String requestedToken = blocked.equals("wrong-owner") ? "WORKER" : "worker";
      var before = executionRecoveryTokenFacts();
      org.junit.jupiter.api.Assertions.assertFalse(
          dispatchTransaction(
              () -> store.finishRecovery(requestedPay, requestedToken, nextAt, "blocked")),
          blocked);
      assertEquals(before, executionRecoveryTokenFacts(), blocked);
    }
  }

  /** 只克隆原execution DDL，实际Recovery Mapper与Store使用同一owned有界连接池。 */
  private com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore
      ownedExecutionRecoveryTokenStore() {
    ownedExecutionObservationStore();
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      var mapper = session.getMapper(com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper.class);
      return new com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore(
          mapper, executionObservationMapper, records);
    } catch (Exception failure) {
      throw new IllegalStateException("owned recovery token factory failed", failure);
    }
  }

  /** 生成明确有效的历史owner场景，时间远离当前边界；只写owned的三张表。 */
  private void seedExecutionRecoveryToken(String state, String decision, String token) {
    seedOwnedExecution(state, decision, "t186-xid");
    fixture.update("UPDATE pay_record SET debit_mode='TCC'");
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update(
        "UPDATE pay_tcc_execution SET"
            + " recovery_owner=?,recovery_lease_until=?,recovery_attempts=7,next_recovery_at=?,recovery_last_error='owned-recovery-old'",
        token,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1),
        LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  /** Pay/Intent/Execution全字段快照，收尾不得触及资金状态或载荷。 */
  private List<List<Map<String, Object>>> executionRecoveryTokenFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    facts.add(fixture.queryForList("SELECT * FROM pay_tcc_execution ORDER BY id"));
    return facts;
  }

  /** owned库克隆原execution DDL，不启动应用或TC，只建立实际MyBatis读取和写入入口。 */
  private com.cloudmall.pay.service.impl.PayTccExecutionStore ownedExecutionObservationStore() {
    fixture.execute("DROP TABLE IF EXISTS pay_tcc_execution");
    fixture.execute(
        (String) source.queryForMap("SHOW CREATE TABLE pay_tcc_execution").get("Create Table"));
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      executionObservationMapper =
          session.getMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class);
      return executionObservationStore(executionObservationMapper);
    } catch (Exception failure) {
      throw new IllegalStateException("owned execution factory failed", failure);
    }
  }

  /** observe只使用execution，未使用的其他资源为替身，无远端调用。 */
  private com.cloudmall.pay.service.impl.PayTccExecutionStore executionObservationStore(
      com.cloudmall.pay.mapper.PayTccExecutionMapper mapper) {
    return new com.cloudmall.pay.service.impl.PayTccExecutionStore(
        mapper,
        records,
        mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class),
        mock(com.cloudmall.pay.mapper.PayTccReceiptMapper.class),
        mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class));
  }

  /** 在同owned数据源的短事务中调用真实Store及Mapper，异常必须传播并回滚。 */
  private com.cloudmall.pay.domain.po.PayTccExecutionPO executionTransaction(
      java.util.function.Supplier<com.cloudmall.pay.domain.po.PayTccExecutionPO> action) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    return template.execute(transactionStatus -> action.get());
  }

  /** 生成原execution不可变身份及已有诊断，只写owned克隆表。 */
  private void seedOwnedExecution(String state, String decision, String xid) {
    seedIntentAndPayment("PENDING", "INIT");
    fixture.update("DELETE FROM pay_tcc_execution");
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,last_error,created_at,updated_at)"
            + " VALUES(1,'pay-1',?,7,'order-1',10,'tcc-debit:pay-1','pay-1',?,?,'owned-old-error',?,?)",
        xid,
        state,
        decision,
        LocalDateTime.of(2026, 10, 1, 0, 0),
        LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  /** 无token不能认领，也不能留下NULL owner配未来lease及新attempt。 */
  @Test
  void deliveryClaimCannotPersistNullLeaseOwner() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("pay-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET next_retry_at=?", LocalDateTime.of(2026, 10, 1, 0, 0));
    var before = ownedDeliveryIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> deliveryIdentityTransaction(() -> store.claim("pay-1", null)));
          assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
        },
        () -> assertEquals(before, ownedDeliveryIdentityFacts()));
  }

  /** 空白owner与空白token相等仍无推进权，不能改Pay或delivery业务状态。 */
  @Test
  void deliveryStageCannotWriteWithBlankToken() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("pay-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET lease_owner=' ',lease_until=?",
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
    var before = ownedDeliveryIdentityFacts();
    boolean changed = dispatchTransaction(() -> store.stage("pay-1", " ", "ORDER_SYNC_UNKNOWN"));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertFalse(
                changed, "blank token must not authorize stage"),
        () -> assertEquals(before, ownedDeliveryIdentityFacts()));
  }

  /** 空白token不能清历史空白owner的租约、改错误或推迟重试。 */
  @Test
  void deliveryFinishCannotReleaseBlankOwner() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("pay-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET lease_owner='"
            + " ',lease_until=?,next_retry_at=?,last_error='owned-old-proof'",
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1),
        LocalDateTime.of(2026, 10, 1, 0, 0));
    var before = ownedDeliveryIdentityFacts();
    deliveryIdentityTransaction(
        () -> {
          store.finish("pay-1", " ", 1, "blank-owner-proof");
          return null;
        });
    assertEquals(before, ownedDeliveryIdentityFacts());
  }

  /** 原列可表示的五种非法owner在claim/stage/finish三入口均无任何事实写。 */
  @Test
  void deliveryInvalidLeaseTokensCannotMutateAnyFacts() {
    var store = ownedDeliveryIdentityStore();
    for (String token : java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u3000")) {
      seedOwnedDeliveryIdentity("pay-1");
      assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
      fixture.update(
          "UPDATE pay_tcc_delivery SET next_retry_at=?", LocalDateTime.of(2026, 10, 1, 0, 0));
      var beforeClaim = ownedDeliveryIdentityFacts();
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> deliveryIdentityTransaction(() -> store.claim("pay-1", token)));
      assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
      assertEquals(beforeClaim, ownedDeliveryIdentityFacts());
      fixture.update(
          "UPDATE pay_tcc_delivery SET lease_owner=?,lease_until=?,last_error='owned-bad-owner'",
          token,
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
      var beforeWrite = ownedDeliveryIdentityFacts();
      assertTrue(!dispatchTransaction(() -> store.stage("pay-1", token, "ORDER_SYNC_UNKNOWN")));
      assertEquals(beforeWrite, ownedDeliveryIdentityFacts());
      deliveryIdentityTransaction(
          () -> {
            store.finish("pay-1", token, 1, "should-not-save");
            return null;
          });
      assertEquals(beforeWrite, ownedDeliveryIdentityFacts());
    }
  }

  /** 非blank首尾空格不归一化，128 UTF16合法边界保持真实租约/推进/收尾。 */
  @Test
  void validDeliveryLeaseTokensKeepAllExistingWrites() {
    var store = ownedDeliveryIdentityStore();
    for (String token : List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64))) {
      seedOwnedDeliveryIdentity("pay-1");
      assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
      fixture.update(
          "UPDATE pay_tcc_delivery SET next_retry_at=?", LocalDateTime.of(2026, 10, 1, 0, 0));
      var executionBefore = fixture.queryForMap("SELECT * FROM pay_tcc_execution");
      var paymentBefore = fixture.queryForMap("SELECT * FROM pay_record");
      var claimed = deliveryIdentityTransaction(() -> store.claim("pay-1", token));
      assertEquals(token, claimed.leaseOwner);
      assertEquals(1, claimed.attempts);
      assertEquals(claimed.updatedAt.plusSeconds(60), claimed.leaseUntil);
      assertTrue(dispatchTransaction(() -> store.stage("pay-1", token, "ORDER_SYNC_UNKNOWN")));
      assertEquals(
          "ORDER_SYNC_UNKNOWN", fixture.queryForMap("SELECT * FROM pay_record").get("status"));
      var staged = store.read("pay-1");
      assertEquals("ORDER_SYNC_UNKNOWN", staged.state);
      assertEquals(token, staged.leaseOwner);
      assertEquals(claimed.leaseUntil, staged.leaseUntil);
      deliveryIdentityTransaction(
          () -> {
            store.finish("pay-1", token, 1, "lease-evidence");
            return null;
          });
      var finished = store.read("pay-1");
      assertNull(finished.leaseOwner);
      assertNull(finished.leaseUntil);
      assertEquals("ORDER_SYNC_UNKNOWN", finished.state);
      assertEquals(1, finished.attempts);
      assertEquals("lease-evidence", finished.lastError);
      assertTrue(finished.nextRetryAt.isAfter(LocalDateTime.of(2026, 10, 1, 0, 0)));
      assertEquals(executionBefore, fixture.queryForMap("SELECT * FROM pay_tcc_execution"));
      var paymentAfter = fixture.queryForMap("SELECT * FROM pay_record");
      for (String changed : List.of("status", "updated_at")) {
        paymentBefore.remove(changed);
        paymentAfter.remove(changed);
      }
      assertEquals(paymentBefore, paymentAfter);
      var beforeLate = ownedDeliveryIdentityFacts();
      assertTrue(!dispatchTransaction(() -> store.stage("pay-1", token, "SUCCESS")));
      deliveryIdentityTransaction(
          () -> {
            store.finish("pay-1", token, 1, "late");
            return null;
          });
      assertEquals(beforeLate, ownedDeliveryIdentityFacts());
    }
    seedOwnedDeliveryIdentity("pay-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET attempts=31,next_retry_at=?",
        LocalDateTime.of(2026, 10, 1, 0, 0));
    assertEquals(
        31, deliveryIdentityTransaction(() -> store.claim("pay-1", "bounded-worker")).attempts);
  }

  /** 原payment/execution一致也不能据其为另一个请求支付号创建交付任务。 */
  @Test
  void deliveryEnsureCannotCreateFromAnotherRequestedPayment() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("PAY-1");
    assertEquals("PAY-1", executionObservationMapper.selectByPayNo("pay-1").payNo);
    var before = ownedDeliveryIdentityFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      returned.set(deliveryIdentityTransaction(() -> store.ensure("pay-1")).payNo));
          assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
        },
        () ->
            assertNull(
                returned.get(), "alias ensure must not create another original payment task"),
        () -> assertEquals(before, ownedDeliveryIdentityFacts()));
  }

  /** 精确同源三表不能使别名请求取得另一任务lease或增加attempt。 */
  @Test
  void deliveryClaimCannotLeaseAnotherOriginalPayment() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("PAY-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("PAY-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET next_retry_at=?", LocalDateTime.of(2026, 10, 1, 0, 0));
    var before = ownedDeliveryIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            assertNull(
                deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-worker")),
                "alias request must not lease another original task"),
        () -> assertEquals(before, ownedDeliveryIdentityFacts()));
  }

  /** 普通读取不能输出另一个原支付号交付终态。 */
  @Test
  void deliveryReadCannotReturnAnotherPaymentTerminal() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("PAY-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("PAY-1")));
    fixture.update("UPDATE pay_tcc_delivery SET state='SUCCESS'");
    fixture.update("UPDATE pay_record SET status='SUCCESS'");
    var before = ownedDeliveryIdentityFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(store.read("pay-1").payNo));
          assertEquals("PAY_TCC_DELIVERY_CONFLICT", failure.getCode());
        },
        () -> assertNull(returned.get(), "alias read must not supply another original terminal"),
        () -> assertEquals(before, ownedDeliveryIdentityFacts()));
  }

  /** 两方向大小写请求×七状态，ensure/read拒绝，claim精确零行且四表不动。 */
  @Test
  void deliveryIdentityRejectsAliasesAcrossEveryState() {
    var store = ownedDeliveryIdentityStore();
    for (boolean uppercaseOriginal : List.of(false, true)) {
      String original = uppercaseOriginal ? "PAY-1" : "pay-1";
      String requested = uppercaseOriginal ? "pay-1" : "PAY-1";
      for (String state :
          List.of(
              "READY",
              "CANCEL_REQUESTED",
              "CANCEL_RECONCILING",
              "ORDER_SYNC_UNKNOWN",
              "REFUND_PENDING",
              "SUCCESS",
              "REFUNDED")) {
        seedOwnedDeliveryIdentity(original);
        assertNotNull(deliveryIdentityTransaction(() -> store.ensure(original)));
        fixture.update(
            "UPDATE pay_tcc_delivery SET state=?,next_retry_at=?",
            state,
            LocalDateTime.of(2026, 10, 1, 0, 0));
        var before = ownedDeliveryIdentityFacts();
        assertEquals(
            "PAY_TCC_DELIVERY_CONFLICT",
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> deliveryIdentityTransaction(() -> store.ensure(requested)))
                .getCode());
        assertEquals(
            "PAY_TCC_DELIVERY_CONFLICT",
            org.junit.jupiter.api.Assertions.assertThrows(
                    com.cloudmall.common.error.BizException.class, () -> store.read(requested))
                .getCode());
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        deliveryIdentityTransaction(
            () -> {
              assertEquals(
                  0,
                  deliveryIdentityMapper.claim(
                      requested, "t182-direct", currentTime.plusSeconds(60), currentTime));
              return null;
            });
        assertNull(deliveryIdentityTransaction(() -> store.claim(requested, "t182-worker")));
        assertEquals(before, ownedDeliveryIdentityFacts());
      }
    }
  }

  /** 精确原键保持唯一READY/重入、七状态只读、五活动lease/31上限及原阻断。 */
  @Test
  void canonicalDeliveryIdentityKeepsEnsureClaimAndReadSemantics() {
    var store = ownedDeliveryIdentityStore();
    seedOwnedDeliveryIdentity("pay-1");
    var before = ownedDeliveryIdentityFacts();
    var created = deliveryIdentityTransaction(() -> store.ensure("pay-1"));
    assertEquals("pay-1", created.payNo);
    assertEquals("READY", created.state);
    assertEquals("refund:pay-1", created.refundOperationKey);
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_tcc_delivery", Integer.class));
    var after = ownedDeliveryIdentityFacts();
    for (int tableIndex : List.of(0, 1, 2))
      assertEquals(before.get(tableIndex), after.get(tableIndex));
    assertEquals(created.id, deliveryIdentityTransaction(() -> store.ensure("pay-1")).id);
    assertEquals(after, ownedDeliveryIdentityFacts());
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    for (String state :
        List.of(
            "READY",
            "CANCEL_REQUESTED",
            "CANCEL_RECONCILING",
            "ORDER_SYNC_UNKNOWN",
            "REFUND_PENDING",
            "SUCCESS",
            "REFUNDED")) {
      seedOwnedDeliveryIdentity("pay-1");
      assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
      fixture.update(
          "UPDATE pay_tcc_delivery SET state=?,next_retry_at=?,last_error='owned-old-error'",
          state,
          LocalDateTime.of(2026, 10, 1, 0, 0));
      String paymentState =
          List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING", "SUCCESS", "REFUNDED").contains(state)
              ? state
              : "PENDING";
      fixture.update("UPDATE pay_record SET status=?", paymentState);
      var sourceFacts = ownedDeliveryIdentityFacts();
      var ensured = deliveryIdentityTransaction(() -> store.ensure("pay-1"));
      assertEquals(state, ensured.state);
      assertEquals(json.valueToTree(ensured), json.valueToTree(store.read("pay-1")));
      assertEquals(sourceFacts, ownedDeliveryIdentityFacts());
      if (state.equals("SUCCESS") || state.equals("REFUNDED")) {
        assertNull(deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-worker")));
        assertEquals(sourceFacts, ownedDeliveryIdentityFacts());
      } else {
        var claimed = deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-worker"));
        assertNotNull(claimed);
        assertEquals(state, claimed.state);
        assertEquals("t182-worker", claimed.leaseOwner);
        assertEquals(1, claimed.attempts);
        assertEquals(claimed.updatedAt.plusSeconds(60), claimed.leaseUntil);
        assertEquals("owned-old-error", claimed.lastError);
        var claimedFacts = ownedDeliveryIdentityFacts();
        assertNull(deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-second")));
        assertEquals(claimedFacts, ownedDeliveryIdentityFacts());
      }
    }
    seedOwnedDeliveryIdentity("pay-1");
    assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
    fixture.update(
        "UPDATE pay_tcc_delivery SET attempts=31,next_retry_at=?",
        LocalDateTime.of(2026, 10, 1, 0, 0));
    assertEquals(31, deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-cap")).attempts);
    for (String blocked :
        List.of(
            "future",
            "held",
            "execution-state",
            "decision",
            "legacy",
            "order-tuple",
            "refund-key",
            "missing")) {
      seedOwnedDeliveryIdentity("pay-1");
      assertNotNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
      fixture.update(
          "UPDATE pay_tcc_delivery SET next_retry_at=?", LocalDateTime.of(2026, 10, 1, 0, 0));
      switch (blocked) {
        case "future" ->
            fixture.update(
                "UPDATE pay_tcc_delivery SET next_retry_at=?",
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
        case "held" ->
            fixture.update(
                "UPDATE pay_tcc_delivery SET lease_owner='held-worker',lease_until=?",
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
        case "execution-state" -> fixture.update("UPDATE pay_tcc_execution SET state='RUNNING'");
        case "decision" -> fixture.update("UPDATE pay_tcc_execution SET decision='ROLLBACK'");
        case "legacy" -> fixture.update("UPDATE pay_record SET debit_mode='LEGACY'");
        case "order-tuple" -> fixture.update("UPDATE pay_record SET order_no='ORDER-1'");
        case "refund-key" ->
            fixture.update("UPDATE pay_tcc_delivery SET refund_operation_key='refund:other-pay'");
        default -> fixture.update("DELETE FROM pay_tcc_delivery");
      }
      var facts = ownedDeliveryIdentityFacts();
      assertNull(deliveryIdentityTransaction(() -> store.claim("pay-1", "t182-blocked")), blocked);
      assertEquals(facts, ownedDeliveryIdentityFacts());
    }
    for (String state : List.of("CLAIMED", "RUNNING", "ROLLEDBACK")) {
      seedOwnedDeliveryIdentity("PAY-1");
      fixture.update("UPDATE pay_tcc_execution SET state=?", state);
      var facts = ownedDeliveryIdentityFacts();
      assertNull(deliveryIdentityTransaction(() -> store.ensure("pay-1")));
      assertEquals(facts, ownedDeliveryIdentityFacts());
    }
    assertNull(store.read("absent-pay"));
  }

  /** 原Execution/Delivery DDL只克隆到owned，真实三Mapper同源，不启应用/TC。 */
  private com.cloudmall.pay.service.impl.PayTccDeliveryStore ownedDeliveryIdentityStore() {
    ownedExecutionObservationStore();
    fixture.execute("DROP TABLE IF EXISTS pay_tcc_delivery");
    fixture.execute(
        (String) source.queryForMap("SHOW CREATE TABLE pay_tcc_delivery").get("Create Table"));
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccDeliveryMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      deliveryIdentityMapper =
          session.getMapper(com.cloudmall.pay.mapper.PayTccDeliveryMapper.class);
      return new com.cloudmall.pay.service.impl.PayTccDeliveryStore(
          deliveryIdentityMapper,
          records,
          executionObservationMapper,
          mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class),
          mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class));
    } catch (Exception failure) {
      throw new IllegalStateException("owned delivery identity factory failed", failure);
    }
  }

  /** 同一个原支付号的TCC payment/COMMITTED execution，操作键也按原拼写保存。 */
  private void seedOwnedDeliveryIdentity(String originalPayNo) {
    seedOwnedExecution("COMMITTED", "COMMIT", "t182-xid");
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("UPDATE pay_record SET pay_no=?,debit_mode='TCC'", originalPayNo);
    fixture.update(
        "UPDATE pay_tcc_execution SET pay_no=?,operation_key=?,ledger_key=?",
        originalPayNo,
        "tcc-debit:" + originalPayNo,
        originalPayNo);
    fixture.update("DELETE FROM pay_tcc_delivery");
  }

  /** 程序化短事务验证实际FOR UPDATE/INSERT/claim，不代理Spring REQUIRES_NEW。 */
  private com.cloudmall.pay.domain.po.PayTccDeliveryPO deliveryIdentityTransaction(
      java.util.function.Supplier<com.cloudmall.pay.domain.po.PayTccDeliveryPO> action) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    return template.execute(transactionStatus -> action.get());
  }

  /** 原Pay/Intent/Execution/Delivery四表完整facts。 */
  private List<List<Map<String, Object>>> ownedDeliveryIdentityFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    for (String table : List.of("pay_tcc_execution", "pay_tcc_delivery"))
      facts.add(fixture.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    return facts;
  }

  /** 输入别名不能领取另一原支付号任务，更不能改变其租约/attempt。 */
  @Test
  void cancelRecoveryCannotClaimAnotherPaymentSpelling() {
    var store = ownedCancelRecoveryIdentityStore();
    seedOwnedCancelIdentity("REQUESTED", "PAY-1");
    assertEquals("PAY-1", cancelRecoveryIdentityMapper.selectByPayNo("pay-1").payNo);
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            assertNull(
                cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "t180-worker")),
                "input alias must not claim another original task"),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** claim0后的读取回退不能将另一原取消任务终态作为请求结果。 */
  @Test
  void cancelFallbackCannotReturnAnotherTerminal() {
    var store = ownedCancelRecoveryIdentityStore();
    seedOwnedCancelIdentity("COMPLETED", "PAY-1");
    fixture.update("UPDATE pay_record SET status='FAILED'");
    var orders = mock(OrderClient.class);
    var service = new com.cloudmall.pay.service.impl.PayTccCancelService(store, orders);
    var before = ownedCancelIdentityFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(service.reconcile("pay-1")));
          assertEquals("PAY_TCC_CANCEL_CONFLICT", failure.getCode());
        },
        () -> assertNull(returned.get(), "alias task must not supply a terminal result"),
        () -> assertEquals(before, ownedCancelIdentityFacts()),
        () -> org.mockito.Mockito.verifyNoInteractions(orders));
  }

  /** 三活动状态的真实CI双向请求别名均SQL零行，不碰另一原任务lease/attempt。 */
  @Test
  void cancelRecoveryAliasRequestsCannotMutateAnyActiveState() {
    var store = ownedCancelRecoveryIdentityStore();
    for (boolean uppercaseOriginal : List.of(false, true)) {
      String original = uppercaseOriginal ? "PAY-1" : "pay-1";
      String requested = uppercaseOriginal ? "pay-1" : "PAY-1";
      for (String state : List.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN")) {
        seedOwnedCancelIdentity(state, original);
        assertEquals(original, cancelRecoveryIdentityMapper.selectByPayNo(requested).payNo);
        var before = ownedCancelIdentityFacts();
        var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        cancelIdentityTransaction(
            () -> {
              assertEquals(
                  0,
                  cancelRecoveryIdentityMapper.claimRecovery(
                      requested, "t180-direct", currentTime.plusSeconds(60), currentTime));
              return null;
            });
        assertNull(cancelIdentityTransaction(() -> store.claimRecovery(requested, "t180-worker")));
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class, () -> store.read(requested));
        assertEquals("PAY_TCC_CANCEL_CONFLICT", failure.getCode());
        assertEquals(before, ownedCancelIdentityFacts());
      }
    }
  }

  /** 原键保持三活动状态/60秒租约/31上限；原资金互斥、到期和缺失门均保零行。 */
  @Test
  void canonicalCancelRecoveryKeepsLeaseAndAllExistingGates() {
    var store = ownedCancelRecoveryIdentityStore();
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    for (String state : List.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN")) {
      seedOwnedCancelIdentity(state, "pay-1");
      var before = ownedCancelIdentityFacts();
      var cancelBefore = fixture.queryForMap("SELECT * FROM pay_tcc_cancel");
      var claimed = cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "t180-worker"));
      assertNotNull(claimed);
      assertEquals("pay-1", claimed.payNo);
      assertEquals(state, claimed.state);
      assertEquals("t180-worker", claimed.leaseOwner);
      assertEquals(1, claimed.attempts);
      assertEquals(claimed.updatedAt.plusSeconds(60), claimed.leaseUntil);
      assertEquals("owned-original-error", claimed.lastError);
      assertEquals(json.valueToTree(claimed), json.valueToTree(store.read("pay-1")));
      var cancelAfter = fixture.queryForMap("SELECT * FROM pay_tcc_cancel");
      for (String changed : List.of("lease_owner", "lease_until", "attempts", "updated_at")) {
        cancelBefore.remove(changed);
        cancelAfter.remove(changed);
      }
      assertEquals(cancelBefore, cancelAfter);
      var after = ownedCancelIdentityFacts();
      for (int tableIndex : List.of(0, 1, 3, 4, 5))
        assertEquals(before.get(tableIndex), after.get(tableIndex));
      assertNull(cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "t180-second")));
      assertEquals(after, ownedCancelIdentityFacts());
    }
    seedOwnedCancelIdentity("REQUESTED", "pay-1");
    fixture.update("UPDATE pay_tcc_cancel SET attempts=31");
    assertEquals(
        31, cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "t180-cap")).attempts);
    for (String blocked :
        List.of(
            "missing",
            "future",
            "leased",
            "COMPLETED",
            "CONFLICT",
            "legacy",
            "failed",
            "order-tuple",
            "execution",
            "dispatch",
            "receipt",
            "intent")) {
      seedOwnedCancelIdentity("REQUESTED", "pay-1");
      switch (blocked) {
        case "missing" -> fixture.update("DELETE FROM pay_tcc_cancel");
        case "future" ->
            fixture.update(
                "UPDATE pay_tcc_cancel SET next_retry_at=?",
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
        case "leased" ->
            fixture.update(
                "UPDATE pay_tcc_cancel SET lease_owner='held-worker',lease_until=?",
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
        case "COMPLETED", "CONFLICT" ->
            fixture.update("UPDATE pay_tcc_cancel SET state=?", blocked);
        case "legacy" -> fixture.update("UPDATE pay_record SET debit_mode='LEGACY'");
        case "failed" -> fixture.update("UPDATE pay_record SET status='FAILED'");
        case "order-tuple" -> fixture.update("UPDATE pay_record SET order_no='ORDER-1'");
        default -> insertOwnedCancelBlockingFact(blocked);
      }
      var before = ownedCancelIdentityFacts();
      assertNull(
          cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "t180-blocked")), blocked);
      assertEquals(before, ownedCancelIdentityFacts());
    }
    assertNull(store.read("absent-pay"));
  }

  /** owned中构造单一互斥资金事实，其余原支付/取消资格保持，避免同时触发其它门。 */
  private void insertOwnedCancelBlockingFact(String kind) {
    var fixed = LocalDateTime.of(2026, 10, 1, 0, 0);
    if (kind.equals("intent")) {
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,created_at,updated_at)"
              + " VALUES(1,'pay-1','order-1',7,10,'INIT','LEGACY',0,?,?,?)",
          fixed,
          fixed,
          fixed);
      return;
    }
    String table =
        switch (kind) {
          case "execution" -> "pay_tcc_execution";
          case "dispatch" -> "pay_tcc_dispatch";
          case "receipt" -> "pay_tcc_receipt";
          default -> throw new IllegalArgumentException(kind);
        };
    String decisionColumn = kind.equals("execution") ? ",decision" : "";
    String decisionValue = kind.equals("execution") ? ",'NONE'" : "";
    String state =
        kind.equals("execution")
            ? "RUNNING"
            : kind.equals("dispatch") ? "POST_ATTEMPTED" : "CLAIMED";
    fixture.update(
        "INSERT INTO "
            + table
            + "(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at"
            + decisionColumn
            + ") VALUES(1,'pay-1','t180-fund-xid',7,'order-1',10,'tcc-debit:pay-1','pay-1',?,?,?"
            + decisionValue
            + ")",
        state,
        fixed,
        fixed);
  }

  /** UTF-16超限但数据库可存的token不能认领并改lease/attempt。 */
  @Test
  void cancelClaimCannotPersistOverlongLeaseToken() {
    var store = ownedCancelRecoveryIdentityStore();
    seedOwnedCancelIdentity("REQUESTED", "pay-1");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      cancelIdentityTransaction(
                          () -> store.claimRecovery("pay-1", "😀".repeat(65))));
          assertEquals("PAY_TCC_CANCEL_CONFLICT", failure.getCode());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 历史UTF-16超限owner与入参相等不能推进取消或Pay FAILED。 */
  @Test
  void cancelStageCannotWriteWithOverlongToken() {
    var store = ownedCancelRecoveryIdentityStore();
    seedOwnedCancelIdentity("REQUESTED", "pay-1");
    String token = "😀".repeat(65);
    fixture.update(
        "UPDATE pay_tcc_cancel SET lease_owner=?,lease_until=?",
        token,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
    var before = ownedCancelIdentityFacts();
    boolean changed = dispatchTransaction(() -> store.stage("pay-1", token, "COMPLETED", null));
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertFalse(
                changed, "overlong token must not authorize cancellation"),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 历史空白owner不能被空白token释放租约或覆写退避/错误。 */
  @Test
  void cancelFinishCannotReleaseBlankOwner() {
    var store = ownedCancelRecoveryIdentityStore();
    seedOwnedCancelIdentity("REQUESTED", "pay-1");
    fixture.update(
        "UPDATE pay_tcc_cancel SET lease_owner=' ',lease_until=?",
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
    var before = ownedCancelIdentityFacts();
    cancelIdentityTransaction(
        () -> {
          store.finish("pay-1", " ", 1, "blank-cancel-proof");
          return null;
        });
    assertEquals(before, ownedCancelIdentityFacts());
  }

  /** 六种数据库可存的非法token三入口均不改变六表事实。 */
  @Test
  void invalidCancelLeaseTokensCannotMutateAnyFacts() {
    var store = ownedCancelRecoveryIdentityStore();
    for (String token :
        java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u3000", "😀".repeat(65))) {
      seedOwnedCancelIdentity("REQUESTED", "pay-1");
      var beforeClaim = ownedCancelIdentityFacts();
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> cancelIdentityTransaction(() -> store.claimRecovery("pay-1", token)));
      assertEquals("PAY_TCC_CANCEL_CONFLICT", failure.getCode());
      assertEquals(beforeClaim, ownedCancelIdentityFacts());
      fixture.update(
          "UPDATE pay_tcc_cancel SET lease_owner=?,lease_until=?",
          token,
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
      var beforeWrite = ownedCancelIdentityFacts();
      org.junit.jupiter.api.Assertions.assertFalse(
          dispatchTransaction(() -> store.stage("pay-1", token, "COMPLETED", "should-not-save")));
      assertEquals(beforeWrite, ownedCancelIdentityFacts());
      cancelIdentityTransaction(
          () -> {
            store.finish("pay-1", token, 31, "should-not-save");
            return null;
          });
      assertEquals(beforeWrite, ownedCancelIdentityFacts());
    }
  }

  /** 四种合法token及三个active状态保持真实取消/Pay原子写、退避与旧lease门。 */
  @Test
  void validCancelLeaseTokensKeepExistingWritesAndLeaseGates() {
    var store = ownedCancelRecoveryIdentityStore();
    for (String token : List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64)))
      for (String state : List.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN")) {
        seedOwnedCancelIdentity(state, "pay-1");
        var paymentBefore = fixture.queryForMap("SELECT * FROM pay_record");
        var claimed = cancelIdentityTransaction(() -> store.claimRecovery("pay-1", token));
        assertEquals(token, claimed.leaseOwner);
        assertEquals(state, claimed.state);
        assertEquals(1, claimed.attempts);
        assertEquals(claimed.updatedAt.plusSeconds(60), claimed.leaseUntil);
        var beforeStale = ownedCancelIdentityFacts();
        org.junit.jupiter.api.Assertions.assertFalse(
            dispatchTransaction(() -> store.stage("pay-1", "different-worker", "COMPLETED", null)));
        cancelIdentityTransaction(
            () -> {
              store.finish("pay-1", "different-worker", 1, "stale");
              return null;
            });
        assertEquals(beforeStale, ownedCancelIdentityFacts());
        assertTrue(dispatchTransaction(() -> store.stage("pay-1", token, "COMPLETED", null)));
        var staged = store.read("pay-1");
        assertEquals("COMPLETED", staged.state);
        assertEquals(token, staged.leaseOwner);
        assertEquals(claimed.leaseUntil, staged.leaseUntil);
        assertEquals("FAILED", fixture.queryForMap("SELECT * FROM pay_record").get("status"));
        cancelIdentityTransaction(
            () -> {
              store.finish("pay-1", token, 1, "cancel-evidence");
              return null;
            });
        var finished = store.read("pay-1");
        assertNull(finished.leaseOwner);
        assertNull(finished.leaseUntil);
        assertEquals("COMPLETED", finished.state);
        assertEquals(1, finished.attempts);
        assertEquals("cancel-evidence", finished.lastError);
        assertEquals(finished.updatedAt.plusSeconds(5), finished.nextRetryAt);
        var paymentAfter = fixture.queryForMap("SELECT * FROM pay_record");
        for (String changed : List.of("status", "updated_at")) {
          paymentBefore.remove(changed);
          paymentAfter.remove(changed);
        }
        assertEquals(paymentBefore, paymentAfter);
        assertEquals(
            0, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        for (String table : List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt"))
          assertEquals(0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        var beforeLate = ownedCancelIdentityFacts();
        org.junit.jupiter.api.Assertions.assertFalse(
            dispatchTransaction(() -> store.stage("pay-1", token, "COMPLETED", null)));
        cancelIdentityTransaction(
            () -> {
              store.finish("pay-1", token, 1, "late");
              return null;
            });
        assertEquals(beforeLate, ownedCancelIdentityFacts());
      }
    seedOwnedCancelIdentity("REQUESTED", "pay-1");
    fixture.update("UPDATE pay_tcc_cancel SET attempts=31");
    assertEquals(
        31,
        cancelIdentityTransaction(() -> store.claimRecovery("pay-1", "bounded-worker")).attempts);
    cancelIdentityTransaction(
        () -> {
          store.finish("pay-1", "bounded-worker", 31, "cap");
          return null;
        });
    var capped = store.read("pay-1");
    assertEquals(capped.updatedAt.plusSeconds(300), capped.nextRetryAt);
    fixture.update(
        "UPDATE pay_tcc_cancel SET lease_owner='expired-worker',lease_until=?",
        LocalDateTime.of(2026, 10, 1, 0, 0));
    var expired = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertFalse(
        dispatchTransaction(() -> store.stage("pay-1", "expired-worker", "COMPLETED", null)));
    cancelIdentityTransaction(
        () -> {
          store.finish("pay-1", "expired-worker", 1, "expired");
          return null;
        });
    assertEquals(expired, ownedCancelIdentityFacts());
  }

  /** 历史emoji65 owner虽数据库可存，也不能授权Pay FAILED投影。 */
  @Test
  void rollbackProjectionCannotWriteWithOverlongLeaseToken() {
    var store = ownedRollbackTokenStore();
    String token = "😀".repeat(65);
    seedRollbackTokenProjection("PENDING", "ROLLBACK", token);
    var before = ownedCancelIdentityFacts();
    var result = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      result.set(
                          rollbackTokenTransaction(
                              () ->
                                  store.projectFailedClaimed(
                                      rollbackTokenPayment(), "t187-xid", token))),
                  () -> "unexpected projection result=" + result.get());
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 六类可存非法token在三decision和两可写/FAILED重入状态均无六表写。 */
  @Test
  void invalidRollbackProjectionTokensCannotChangeAnyFacts() {
    var store = ownedRollbackTokenStore();
    for (String decision : List.of("NONE", "COMMIT", "ROLLBACK"))
      for (String status : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "FAILED"))
        for (String token :
            java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u3000", "😀".repeat(65))) {
          seedRollbackTokenProjection(status, decision, token);
          var before = ownedCancelIdentityFacts();
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      rollbackTokenTransaction(
                          () ->
                              store.projectFailedClaimed(
                                  rollbackTokenPayment(), "t187-xid", token)));
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
          assertEquals(before, ownedCancelIdentityFacts());
        }
  }

  /** 四合法token保三decision的FAILED投影/重入，公开无租约和既有冲突门不变。 */
  @Test
  void validRollbackProjectionTokensKeepWritesAndPublicPath() {
    var store = ownedRollbackTokenStore();
    for (String token : List.of("worker", " worker ", "a".repeat(128), "😀".repeat(64)))
      for (String decision : List.of("NONE", "COMMIT", "ROLLBACK"))
        for (String status : List.of("PENDING", "ORDER_SYNC_UNKNOWN")) {
          seedRollbackTokenProjection(status, decision, token);
          var before = ownedCancelIdentityFacts();
          var paymentBefore = fixture.queryForMap("SELECT * FROM pay_record");
          assertEquals(
              "FAILED",
              rollbackTokenTransaction(
                  () -> store.projectFailedClaimed(rollbackTokenPayment(), "t187-xid", token)));
          var after = ownedCancelIdentityFacts();
          assertEquals(before.subList(1, before.size()), after.subList(1, after.size()));
          assertEquals("FAILED", fixture.queryForMap("SELECT * FROM pay_record").get("status"));
          var paymentAfter = fixture.queryForMap("SELECT * FROM pay_record");
          for (String changed : List.of("status", "updated_at")) {
            paymentBefore.remove(changed);
            paymentAfter.remove(changed);
          }
          assertEquals(paymentBefore, paymentAfter);
          var completed = ownedCancelIdentityFacts();
          assertEquals(
              "FAILED",
              rollbackTokenTransaction(
                  () -> store.projectFailedClaimed(rollbackTokenPayment(), "t187-xid", token)));
          assertEquals(completed, ownedCancelIdentityFacts());
        }
    seedRollbackTokenProjection("PENDING", "ROLLBACK", null);
    assertEquals(
        "FAILED",
        rollbackTokenTransaction(() -> store.projectFailed(rollbackTokenPayment(), "t187-xid")));
    for (String blocked :
        List.of(
            "wrong-owner",
            "expired",
            "null-lease",
            "running",
            "cancel",
            "intent",
            "pay-alias",
            "missing",
            "success",
            "refunded")) {
      seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
      var payment = rollbackTokenPayment();
      switch (blocked) {
        case "expired" ->
            fixture.update(
                "UPDATE pay_tcc_execution SET recovery_lease_until=?",
                LocalDateTime.of(2026, 10, 1, 0, 0));
        case "null-lease" ->
            fixture.update("UPDATE pay_tcc_execution SET recovery_lease_until=NULL");
        case "running" -> fixture.update("UPDATE pay_tcc_execution SET state='RUNNING'");
        case "cancel" ->
            fixture.update(
                "INSERT INTO"
                    + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,next_retry_at,created_at,updated_at)"
                    + " VALUES(1,'pay-1',7,'order-1',10,'REQUESTED',?,?,?)",
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0));
        case "intent" ->
            fixture.update(
                "INSERT INTO"
                    + " pay_debit_intent(id,pay_no,user_id,order_no,amount,status,debit_mode,next_retry_at,created_at,updated_at)"
                    + " VALUES(1,'pay-1',7,'order-1',10,'INIT','LEGACY',?,?,?)",
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0));
        case "pay-alias" -> payment.payNo = "PAY-1";
        case "missing" -> fixture.update("DELETE FROM pay_tcc_execution");
        case "success" -> fixture.update("UPDATE pay_record SET status='SUCCESS'");
        case "refunded" -> fixture.update("UPDATE pay_record SET status='REFUNDED'");
        default -> {}
      }
      String token = blocked.equals("wrong-owner") ? "WORKER" : "worker";
      var before = ownedCancelIdentityFacts();
      org.junit.jupiter.api.Assertions.assertThrows(
          com.cloudmall.common.error.BizException.class,
          () ->
              rollbackTokenTransaction(
                  () -> store.projectFailedClaimed(payment, "t187-xid", token)),
          blocked);
      assertEquals(before, ownedCancelIdentityFacts(), blocked);
    }
  }

  /** JSON缺少ledgerApplied不得被默认false当作无扣款证明写Pay FAILED。 */
  @Test
  void missingRollbackLedgerFlagCannotProjectPublicFailure() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var projection = rollbackLedgerPresenceService(store, "missing");
    var before = ownedCancelIdentityFacts();
    var result = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> result.set(projection.project(rollbackTokenPayment())),
                  () -> "unexpected missing-ledger result=" + result.get());
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** JSON null无扣款字段不得授权带lease的Pay FAILED投影。 */
  @Test
  void nullRollbackLedgerFlagCannotProjectClaimedFailure() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var projection = rollbackLedgerPresenceService(store, "null");
    var before = ownedCancelIdentityFacts();
    var result = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> result.set(projection.projectClaimed(rollbackTokenPayment(), "worker")),
                  () -> "unexpected null-ledger result=" + result.get());
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 缺字段/null及显式true在两投影入口均不能改变六表资金事实。 */
  @Test
  void incompleteRollbackLedgerProofCannotWriteAcrossStatesAndDecisions() {
    var store = ownedRollbackTokenStore();
    for (String decision : List.of("NONE", "COMMIT", "ROLLBACK"))
      for (String status : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "FAILED"))
        for (String variant : List.of("missing", "null", "true"))
          for (boolean claimed : List.of(false, true)) {
            seedRollbackTokenProjection(status, decision, "worker");
            var service = rollbackLedgerPresenceService(store, variant);
            var before = ownedCancelIdentityFacts();
            var failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                    com.cloudmall.common.error.BizException.class,
                    () -> {
                      if (claimed) service.projectClaimed(rollbackTokenPayment(), "worker");
                      else service.project(rollbackTokenPayment());
                    });
            assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
            assertEquals(202, failure.getHttpStatus());
            assertEquals(before, ownedCancelIdentityFacts());
          }
  }

  /** JSON显式false仍允许三decision的原投影/FAILED重入，payload和其它表保持。 */
  @Test
  void explicitNoLedgerProofKeepsBothProjectionPaths() {
    var store = ownedRollbackTokenStore();
    for (String decision : List.of("NONE", "COMMIT", "ROLLBACK"))
      for (String status : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "FAILED"))
        for (boolean claimed : List.of(false, true)) {
          seedRollbackTokenProjection(status, decision, "worker");
          var service = rollbackLedgerPresenceService(store, "false");
          var before = ownedCancelIdentityFacts();
          var paymentBefore = fixture.queryForMap("SELECT * FROM pay_record");
          assertEquals(
              "FAILED",
              claimed
                  ? service.projectClaimed(rollbackTokenPayment(), "worker")
                  : service.project(rollbackTokenPayment()));
          var after = ownedCancelIdentityFacts();
          assertEquals(before.subList(1, before.size()), after.subList(1, after.size()));
          assertEquals("FAILED", fixture.queryForMap("SELECT * FROM pay_record").get("status"));
          if (status.equals("FAILED")) assertEquals(before, after);
          else {
            var paymentAfter = fixture.queryForMap("SELECT * FROM pay_record");
            for (String changed : List.of("status", "updated_at")) {
              paymentBefore.remove(changed);
              paymentAfter.remove(changed);
            }
            assertEquals(paymentBefore, paymentAfter);
          }
          var completed = ownedCancelIdentityFacts();
          assertEquals(
              "FAILED",
              claimed
                  ? service.projectClaimed(rollbackTokenPayment(), "worker")
                  : service.project(rollbackTokenPayment()));
          assertEquals(completed, ownedCancelIdentityFacts());
        }
  }

  /** 本地proof/User为受控替身，真实JSON解User布尔；只有最终Store写使用owned真实事务。 */
  private com.cloudmall.pay.service.impl.PayTccRollbackProjectionService
      rollbackLedgerPresenceService(
          com.cloudmall.pay.service.impl.PayTccPublicStateStore actualStore, String ledgerVariant) {
    var payment = rollbackTokenPayment();
    var proof = new com.cloudmall.pay.domain.vo.PayTccDispatchVO();
    proof.payNo = payment.payNo;
    proof.userId = payment.userId;
    proof.orderNo = payment.orderNo;
    proof.amount = "10.00";
    proof.xid = "t187-xid";
    proof.userBranchId = 31L;
    proof.operationKey = "tcc-debit:pay-1";
    proof.ledgerKey = "pay-1";
    proof.state = "CANCELLED";
    var user = new com.cloudmall.pay.domain.vo.UserTccStateVO();
    user.payNo = payment.payNo;
    user.userId = payment.userId;
    user.orderNo = payment.orderNo;
    user.amount = "10.00";
    user.xid = proof.xid;
    user.branchId = proof.userBranchId;
    user.operationKey = proof.operationKey;
    user.ledgerKey = proof.ledgerKey;
    user.state = "CANCELLED";
    user.setLedgerApplied(false);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(user);
    if (ledgerVariant.equals("missing")) json.remove("ledgerApplied");
    else if (ledgerVariant.equals("null")) json.putNull("ledgerApplied");
    else if (ledgerVariant.equals("number-zero")) json.put("ledgerApplied", 0);
    else if (ledgerVariant.equals("text-false")) json.put("ledgerApplied", "false");
    else json.put("ledgerApplied", ledgerVariant.equals("true"));
    mutateUserEvidenceJson(json, ledgerVariant);
    com.cloudmall.pay.domain.vo.UserTccStateVO decoded;
    try {
      decoded = decodeUserEvidenceJson(mapper, json, ledgerVariant);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("owned User DTO decode failed", failure);
    }
    if (!ledgerVariant.startsWith("duplicate-")
        && !List.of("number-zero", "text-false").contains(ledgerVariant)) {
      assertEquals(
          ledgerVariant.equals("false")
              || ledgerVariant.equals("true")
              || ledgerVariant.startsWith("branch-")
              || ledgerVariant.startsWith("user-")
              || ledgerVariant.startsWith("amount-"),
          decoded.ledgerAppliedPresent);
      assertEquals(ledgerVariant.equals("true"), decoded.ledgerApplied);
    }
    var users = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(users.state(any(), any(), any(), any(), any(), any())).thenReturn(ApiResponse.ok(decoded));
    var boundary = mock(com.cloudmall.pay.service.impl.PayTccPublicStateStore.class);
    when(boundary.cancelledProof(any())).thenReturn(proof);
    org.mockito.Mockito.doAnswer(
            invocation ->
                rollbackTokenTransaction(
                    () ->
                        actualStore.projectFailed(
                            invocation.getArgument(0), invocation.getArgument(1))))
        .when(boundary)
        .projectFailed(any(), any());
    org.mockito.Mockito.doAnswer(
            invocation ->
                rollbackTokenTransaction(
                    () ->
                        actualStore.projectFailedClaimed(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2))))
        .when(boundary)
        .projectFailedClaimed(any(), any(), any());
    return new com.cloudmall.pay.service.impl.PayTccRollbackProjectionService(
        boundary, users, "internal-token");
  }

  /** TRYING响应缺少ledgerApplied不能用默认false授权原执行回滚决议。 */
  @Test
  void missingAbortLedgerFlagCannotChooseRollbackDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("RUNNING", "PENDING");
    var service = abortLedgerPresenceService(store, "missing");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> service.requestRollback(rollbackTokenPayment()));
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** TRYING JSON null布尔不能授权UNKNOWN原执行的回滚决议。 */
  @Test
  void nullAbortLedgerFlagCannotChooseRollbackDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("UNKNOWN", "ORDER_SYNC_UNKNOWN");
    var service = abortLedgerPresenceService(store, "null");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> service.requestRollback(rollbackTokenPayment()));
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
        },
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 缺字段/null/true在两个未决execution状态及两Pay状态均不选择回滚。 */
  @Test
  void incompleteAbortLedgerEvidenceKeepsAllDecisionFacts() {
    var store = ownedAbortLedgerStore();
    for (String state : List.of("RUNNING", "UNKNOWN"))
      for (String paymentStatus : List.of("PENDING", "ORDER_SYNC_UNKNOWN"))
        for (String variant : List.of("missing", "null", "true")) {
          seedAbortLedgerDecision(state, paymentStatus);
          var service = abortLedgerPresenceService(store, variant);
          var before = ownedCancelIdentityFacts();
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> service.requestRollback(rollbackTokenPayment()));
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
          assertEquals(before, ownedCancelIdentityFacts());
        }
  }

  /** 明确false仍只写原回滚请求四列；owner/lease/attempts与其它五表保持。 */
  @Test
  void explicitNoLedgerAbortPreservesOriginalDecisionWrites() {
    var store = ownedAbortLedgerStore();
    for (String state : List.of("RUNNING", "UNKNOWN"))
      for (String paymentStatus : List.of("PENDING", "ORDER_SYNC_UNKNOWN")) {
        seedAbortLedgerDecision(state, paymentStatus);
        var service = abortLedgerPresenceService(store, "false");
        var before = ownedCancelIdentityFacts();
        var executionBefore = fixture.queryForMap("SELECT * FROM pay_tcc_execution");
        service.requestRollback(rollbackTokenPayment());
        var after = ownedCancelIdentityFacts();
        assertEquals(before.subList(0, 3), after.subList(0, 3));
        assertEquals(before.subList(4, 6), after.subList(4, 6));
        var executionAfter = fixture.queryForMap("SELECT * FROM pay_tcc_execution");
        assertEquals("ROLLBACK_REQUESTED", executionAfter.get("state"));
        assertEquals("ROLLBACK", executionAfter.get("decision"));
        assertEquals(executionAfter.get("updated_at"), executionAfter.get("next_recovery_at"));
        assertNotEquals(
            executionBefore.get("next_recovery_at"), executionAfter.get("next_recovery_at"));
        for (String changed : List.of("state", "decision", "updated_at", "next_recovery_at")) {
          executionBefore.remove(changed);
          executionAfter.remove(changed);
        }
        assertEquals(executionBefore, executionAfter);
        var requested = ownedCancelIdentityFacts();
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> service.requestRollback(rollbackTokenPayment()));
        assertEquals(requested, ownedCancelIdentityFacts());
      }
    seedAbortLedgerDecision("RUNNING", "PENDING");
    fixture.update("UPDATE pay_tcc_execution SET decision='COMMIT',state='COMMIT_REQUESTED'");
    var beforeCommitWinner = ownedCancelIdentityFacts();
    var service = abortLedgerPresenceService(store, "false");
    org.junit.jupiter.api.Assertions.assertThrows(
        com.cloudmall.common.error.BizException.class,
        () -> service.requestRollback(rollbackTokenPayment()));
    assertEquals(beforeCommitWinner, ownedCancelIdentityFacts());
  }

  /** 仅Pay/Execution/Cancel/Intent是真实owned Mapper，分支与Fence证据为受控替身。 */
  private com.cloudmall.pay.service.impl.PayTccAbortStore ownedAbortLedgerStore() {
    ownedCancelRecoveryIdentityStore();
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      var dispatch = new com.cloudmall.pay.domain.po.PayTccDispatchPO();
      dispatch.payNo = "pay-1";
      dispatch.userId = 7L;
      dispatch.orderNo = "order-1";
      dispatch.amount = BigDecimal.TEN;
      dispatch.xid = "t189-xid";
      dispatch.operationKey = "tcc-debit:pay-1";
      dispatch.ledgerKey = "pay-1";
      dispatch.userBranchId = 31L;
      dispatch.state = "TRY_ACCEPTED";
      var receipt = new com.cloudmall.pay.domain.po.PayTccReceiptPO();
      receipt.payNo = "pay-1";
      receipt.xid = "t189-xid";
      receipt.branchId = 41L;
      var dispatches = mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class);
      var receipts = mock(com.cloudmall.pay.mapper.PayTccReceiptMapper.class);
      var evidence = mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class);
      when(dispatches.selectByPayNo(any())).thenReturn(dispatch);
      when(receipts.selectByPayNo(any())).thenReturn(receipt);
      when(evidence.classify(any(), any())).thenReturn("TRYING");
      return new com.cloudmall.pay.service.impl.PayTccAbortStore(
          records,
          session.getMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class),
          dispatches,
          receipts,
          evidence,
          cancelRecoveryIdentityMapper,
          intents);
    } catch (Exception failure) {
      throw new IllegalStateException("owned abort ledger factory failed", failure);
    }
  }

  /** 保留未来恢复时间与已有owner/lease/attempts，成功决议只能改变四个目标列。 */
  private void seedAbortLedgerDecision(String state, String paymentStatus) {
    seedRollbackTokenProjection(paymentStatus, "NONE", "owned-abort-worker");
    fixture.update(
        "UPDATE pay_tcc_execution SET"
            + " state=?,xid='t189-xid',recovery_attempts=7,next_recovery_at=?",
        state,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
  }

  /** 两个实际Store阶段分别进入程序化短事务，受控User GET不在这些事务内。 */
  private com.cloudmall.pay.service.impl.PayTccAbortService abortLedgerPresenceService(
      com.cloudmall.pay.service.impl.PayTccAbortStore actualStore, String ledgerVariant) {
    var user = new com.cloudmall.pay.domain.vo.UserTccStateVO();
    user.payNo = "pay-1";
    user.userId = 7L;
    user.orderNo = "order-1";
    user.amount = "10.00";
    user.xid = "t189-xid";
    user.branchId = 31L;
    user.operationKey = "tcc-debit:pay-1";
    user.ledgerKey = "pay-1";
    user.state = "TRYING";
    user.setLedgerApplied(false);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(user);
    if (ledgerVariant.equals("missing")) json.remove("ledgerApplied");
    else if (ledgerVariant.equals("null")) json.putNull("ledgerApplied");
    else if (ledgerVariant.equals("number-zero")) json.put("ledgerApplied", 0);
    else if (ledgerVariant.equals("text-false")) json.put("ledgerApplied", "false");
    else json.put("ledgerApplied", ledgerVariant.equals("true"));
    mutateUserEvidenceJson(json, ledgerVariant);
    com.cloudmall.pay.domain.vo.UserTccStateVO decoded;
    try {
      decoded = decodeUserEvidenceJson(mapper, json, ledgerVariant);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("owned abort User decode failed", failure);
    }
    if (!ledgerVariant.startsWith("duplicate-")
        && !List.of("number-zero", "text-false").contains(ledgerVariant)) {
      assertEquals(
          ledgerVariant.equals("false")
              || ledgerVariant.equals("true")
              || ledgerVariant.startsWith("branch-")
              || ledgerVariant.startsWith("user-")
              || ledgerVariant.startsWith("amount-"),
          decoded.ledgerAppliedPresent);
      assertEquals(ledgerVariant.equals("true"), decoded.ledgerApplied);
    }
    var users = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(users.state(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return ApiResponse.ok(decoded);
            });
    var boundary = mock(com.cloudmall.pay.service.impl.PayTccAbortStore.class);
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    org.mockito.Mockito.doAnswer(
            invocation ->
                transactions.execute(
                    status -> actualStore.preparedProof(invocation.getArgument(0))))
        .when(boundary)
        .preparedProof(any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              transactions.executeWithoutResult(
                  status ->
                      actualStore.requestRollback(
                          invocation.getArgument(0), invocation.getArgument(1)));
              return null;
            })
        .when(boundary)
        .requestRollback(any(), any());
    return new com.cloudmall.pay.service.impl.PayTccAbortService(boundary, users, "internal-token");
  }

  /** 数字0不能被强转为明确no-ledger并授权Pay FAILED投影。 */
  @Test
  void numericLedgerTokenCannotAuthorizeRollbackProjection() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var service = rollbackLedgerPresenceService(store, "number-zero");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.project(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 字符串false不能被强转为明确no-ledger并授权回滚决议。 */
  @Test
  void textualLedgerTokenCannotAuthorizeAbortDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("RUNNING", "PENDING");
    var service = abortLedgerPresenceService(store, "text-false");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.requestRollback(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 字符串false不能使Bridge生成新的可信CANCELLED终态。 */
  @Test
  void textualLedgerTokenCannotAuthorizeCancelledMirror() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(false);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", "text-false")));
    var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertNull(row.userBranchId));
  }

  /** 两种旧可强转标量在回滚投影/主动决议/派发镜像均不能形成可信结果。 */
  @Test
  void nonBooleanLedgerTokensStayBlockedAcrossConsumers() {
    for (String variant : List.of("number-zero", "text-false")) {
      var rollbackStore = ownedRollbackTokenStore();
      seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
      var projection = rollbackLedgerPresenceService(rollbackStore, variant);
      var beforeProjection = ownedCancelIdentityFacts();
      var publicFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.project(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", publicFailure.getCode());
      assertEquals(202, publicFailure.getHttpStatus());
      var claimedFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.projectClaimed(rollbackTokenPayment(), "worker"));
      assertEquals(202, claimedFailure.getHttpStatus());
      assertEquals(beforeProjection, ownedCancelIdentityFacts());
      var abortStore = ownedAbortLedgerStore();
      seedAbortLedgerDecision("RUNNING", "PENDING");
      var abort = abortLedgerPresenceService(abortStore, variant);
      var beforeAbort = ownedCancelIdentityFacts();
      var abortFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> abort.requestRollback(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", abortFailure.getCode());
      assertEquals(202, abortFailure.getHttpStatus());
      assertEquals(beforeAbort, ownedCancelIdentityFacts());
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(false);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      when(user.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", variant)));
      var payBefore = rawIntentTimeFacts();
      var beforeDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
      assertEquals("TRY_UNKNOWN", result.state);
      assertNull(result.userBranchId);
      var afterDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      assertEquals("TRY_UNKNOWN", afterDispatch.get("state"));
      assertNull(afterDispatch.get("user_branch_id"));
      for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
        beforeDispatch.remove(changed);
        afterDispatch.remove(changed);
      }
      assertEquals(beforeDispatch, afterDispatch);
      assertEquals(payBefore, rawIntentTimeFacts());
      verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
    }
  }

  /** 两字段的小数和文本身份均不能授权回滚、决议或可信派发镜像。 */
  @Test
  void nonNativeUserIdentityCannotAuthorizeAnyConsumer() {
    for (String variant :
        List.of("user-fractional", "branch-fractional", "user-text", "branch-text")) {
      var rollbackStore = ownedRollbackTokenStore();
      seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
      var projection = rollbackLedgerPresenceService(rollbackStore, variant);
      var beforeProjection = ownedCancelIdentityFacts();
      var publicFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.project(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", publicFailure.getCode());
      assertEquals(202, publicFailure.getHttpStatus());
      var claimedFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.projectClaimed(rollbackTokenPayment(), "worker"));
      assertEquals(202, claimedFailure.getHttpStatus());
      assertEquals(beforeProjection, ownedCancelIdentityFacts());
      var abortStore = ownedAbortLedgerStore();
      seedAbortLedgerDecision("RUNNING", "PENDING");
      var abort = abortLedgerPresenceService(abortStore, variant);
      var beforeAbort = ownedCancelIdentityFacts();
      var abortFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> abort.requestRollback(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", abortFailure.getCode());
      assertEquals(202, abortFailure.getHttpStatus());
      assertEquals(beforeAbort, ownedCancelIdentityFacts());
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(false);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      when(user.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", variant)));
      var payBefore = rawIntentTimeFacts();
      var beforeDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
      assertEquals("TRY_UNKNOWN", result.state);
      assertNull(result.userBranchId);
      var afterDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      assertEquals("TRY_UNKNOWN", afterDispatch.get("state"));
      assertNull(afterDispatch.get("user_branch_id"));
      for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
        beforeDispatch.remove(changed);
        afterDispatch.remove(changed);
      }
      assertEquals(beforeDispatch, afterDispatch);
      assertEquals(payBefore, rawIntentTimeFacts());
      verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
    }
  }

  /** 整数/小数金额token均不能授权回滚、决议或可信派发镜像。 */
  @Test
  void numericUserAmountCannotAuthorizeAnyConsumer() {
    for (String variant : List.of("amount-number-int", "amount-number-decimal")) {
      var rollbackStore = ownedRollbackTokenStore();
      seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
      var projection = rollbackLedgerPresenceService(rollbackStore, variant);
      var beforeProjection = ownedCancelIdentityFacts();
      var publicFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.project(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", publicFailure.getCode());
      assertEquals(202, publicFailure.getHttpStatus());
      var claimedFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.projectClaimed(rollbackTokenPayment(), "worker"));
      assertEquals(202, claimedFailure.getHttpStatus());
      assertEquals(beforeProjection, ownedCancelIdentityFacts());
      var abortStore = ownedAbortLedgerStore();
      seedAbortLedgerDecision("RUNNING", "PENDING");
      var abort = abortLedgerPresenceService(abortStore, variant);
      var beforeAbort = ownedCancelIdentityFacts();
      var abortFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> abort.requestRollback(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", abortFailure.getCode());
      assertEquals(202, abortFailure.getHttpStatus());
      assertEquals(beforeAbort, ownedCancelIdentityFacts());
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(false);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      when(user.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", variant)));
      var payBefore = rawIntentTimeFacts();
      var beforeDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
      assertEquals("TRY_UNKNOWN", result.state);
      assertNull(result.userBranchId);
      var afterDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      assertEquals("TRY_UNKNOWN", afterDispatch.get("state"));
      assertNull(afterDispatch.get("user_branch_id"));
      for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
        beforeDispatch.remove(changed);
        afterDispatch.remove(changed);
      }
      assertEquals(beforeDispatch, afterDispatch);
      assertEquals(payBefore, rawIntentTimeFacts());
      verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
    }
  }

  /** 十个已知字段即使等值重复也不能授权回滚、决议或可信镜像。 */
  @Test
  void duplicateKnownUserFieldsCannotAuthorizeAnyConsumer() {
    for (String variant :
        List.of(
            "duplicate-same-userId",
            "duplicate-same-payNo",
            "duplicate-same-orderNo",
            "duplicate-same-amount",
            "duplicate-same-operationKey",
            "duplicate-same-ledgerKey",
            "duplicate-same-xid",
            "duplicate-same-branchId",
            "duplicate-same-state",
            "duplicate-same-ledgerApplied")) {
      var rollbackStore = ownedRollbackTokenStore();
      seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
      var projection = rollbackLedgerPresenceService(rollbackStore, variant);
      var beforeProjection = ownedCancelIdentityFacts();
      var publicFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.project(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", publicFailure.getCode());
      assertEquals(202, publicFailure.getHttpStatus());
      var claimedFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> projection.projectClaimed(rollbackTokenPayment(), "worker"));
      assertEquals(202, claimedFailure.getHttpStatus());
      assertEquals(beforeProjection, ownedCancelIdentityFacts());
      var abortStore = ownedAbortLedgerStore();
      seedAbortLedgerDecision("RUNNING", "PENDING");
      var abort = abortLedgerPresenceService(abortStore, variant);
      var beforeAbort = ownedCancelIdentityFacts();
      var abortFailure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> abort.requestRollback(rollbackTokenPayment()));
      assertEquals("PAY_ORDER_SYNC_UNKNOWN", abortFailure.getCode());
      assertEquals(202, abortFailure.getHttpStatus());
      assertEquals(beforeAbort, ownedCancelIdentityFacts());
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(false);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      when(user.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", variant)));
      var payBefore = rawIntentTimeFacts();
      var beforeDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
      assertEquals("TRY_UNKNOWN", result.state);
      assertNull(result.userBranchId);
      var afterDispatch = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
      assertEquals("TRY_UNKNOWN", afterDispatch.get("state"));
      assertNull(afterDispatch.get("user_branch_id"));
      for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
        beforeDispatch.remove(changed);
        afterDispatch.remove(changed);
      }
      assertEquals(beforeDispatch, afterDispatch);
      assertEquals(payBefore, rawIntentTimeFacts());
      verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
    }
  }

  /** 小数branchId不能截断为原分支并授权Pay FAILED。 */
  @Test
  void fractionalUserBranchCannotAuthorizeRollbackProjection() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var service = rollbackLedgerPresenceService(store, "branch-fractional");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.project(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 小数userId不能截断为原用户并授权回滚决议。 */
  @Test
  void fractionalUserIdentityCannotAuthorizeAbortDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("RUNNING", "PENDING");
    var service = abortLedgerPresenceService(store, "user-fractional");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.requestRollback(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 已有branch7不能匹配原始JSON branch7.9并生成可信CANCELLED。 */
  @Test
  void fractionalBranchCannotMatchExistingDispatchBranch() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(false);
    fixture.update("UPDATE pay_tcc_dispatch SET user_branch_id=7");
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", "branch-fractional")));
    var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertEquals(7L, row.userBranchId));
  }

  /** 数字金额不能转成文本并授权Pay FAILED。 */
  @Test
  void numericUserAmountCannotAuthorizeRollbackProjection() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var service = rollbackLedgerPresenceService(store, "amount-number-int");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.project(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 小数金额token不能冒充文本证明并选择ROLLBACK。 */
  @Test
  void decimalUserAmountCannotAuthorizeAbortDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("RUNNING", "PENDING");
    var service = abortLedgerPresenceService(store, "amount-number-decimal");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.requestRollback(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 数字金额不能生成可信CANCELLED镜像或学习分支。 */
  @Test
  void numericAmountCannotAuthorizeCancelledDispatchMirror() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(false);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", "amount-number-int")));
    var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertNull(row.userBranchId));
  }

  /** 重复账本字段不能以末次false覆盖前一次true并授权FAILED。 */
  @Test
  void duplicateUserLedgerCannotAuthorizeRollbackProjection() {
    var store = ownedRollbackTokenStore();
    seedRollbackTokenProjection("PENDING", "ROLLBACK", "worker");
    var service = rollbackLedgerPresenceService(store, "duplicate-conflict-ledger");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.project(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 重复userId不能以末次匹配身份选择ROLLBACK。 */
  @Test
  void duplicateUserIdentityCannotAuthorizeAbortDecision() {
    var store = ownedAbortLedgerStore();
    seedAbortLedgerDecision("RUNNING", "PENDING");
    var service = abortLedgerPresenceService(store, "duplicate-conflict-user");
    var before = ownedCancelIdentityFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> service.requestRollback(rollbackTokenPayment())),
        () -> assertEquals(before, ownedCancelIdentityFacts()));
  }

  /** 重复state不能只按末次CANCELLED生成可信镜像或学习分支。 */
  @Test
  void duplicateUserStateCannotAuthorizeCancelledDispatchMirror() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(false);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", "duplicate-conflict-state")));
    var result = bridgeLedgerTransactions(persistence, user).recover("pay-1");
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertNull(row.userBranchId));
  }

  /** 原始JSON保留重复字段，不能先经ObjectNode去重再测试decoder。 */
  private com.cloudmall.pay.domain.vo.UserTccStateVO decodeUserEvidenceJson(
      com.fasterxml.jackson.databind.ObjectMapper mapper,
      com.fasterxml.jackson.databind.node.ObjectNode json,
      String variant)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    String payload = mapper.writeValueAsString(json);
    if (variant.equals("duplicate-conflict-ledger"))
      payload = "{\"ledgerApplied\":true," + payload.substring(1);
    else if (variant.equals("duplicate-conflict-user"))
      payload = "{\"userId\":8," + payload.substring(1);
    else if (variant.equals("duplicate-conflict-state"))
      payload = "{\"state\":\"CONFIRMED\"," + payload.substring(1);
    else if (variant.startsWith("duplicate-same-")) {
      String field = variant.substring("duplicate-same-".length());
      payload =
          "{\""
              + field
              + "\":"
              + mapper.writeValueAsString(json.get(field))
              + ","
              + payload.substring(1);
    }
    return mapper.readValue(payload, com.cloudmall.pay.domain.vo.UserTccStateVO.class);
  }

  /** 只扩受控User JSON身份/金额类型，账本标志仍是明确原生false。 */
  private void mutateUserEvidenceJson(
      com.fasterxml.jackson.databind.node.ObjectNode json, String variant) {
    if (variant.equals("user-fractional"))
      json.put("userId", new BigDecimal(json.get("userId").asText() + ".9"));
    else if (variant.equals("branch-fractional"))
      json.put("branchId", new BigDecimal(json.get("branchId").asText() + ".9"));
    else if (variant.equals("user-text")) json.put("userId", json.get("userId").asText());
    else if (variant.equals("branch-text")) json.put("branchId", json.get("branchId").asText());
    else if (variant.equals("amount-number-int")) json.put("amount", 10);
    else if (variant.equals("amount-number-decimal")) json.put("amount", new BigDecimal("10.00"));
  }

  /** 原DDL和实际Pay/Execution/Cancel/Intent Mapper验证本地门，不执行远端证明。 */
  private com.cloudmall.pay.service.impl.PayTccPublicStateStore ownedRollbackTokenStore() {
    ownedCancelRecoveryIdentityStore();
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      return new com.cloudmall.pay.service.impl.PayTccPublicStateStore(
          records,
          session.getMapper(com.cloudmall.pay.mapper.PayTccExecutionMapper.class),
          mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class),
          mock(com.cloudmall.pay.service.impl.PayTccExecutionEvidence.class),
          cancelRecoveryIdentityMapper,
          intents);
    } catch (Exception failure) {
      throw new IllegalStateException("owned rollback token factory failed", failure);
    }
  }

  /** 只在owned构造原载荷匹配及未来lease，所有互斥来源表保持空。 */
  private void seedRollbackTokenProjection(String status, String decision, String token) {
    seedOwnedExecution("ROLLEDBACK", decision, "t187-xid");
    fixture.update("UPDATE pay_record SET debit_mode='TCC',status=?", status);
    fixture.update("DELETE FROM pay_debit_intent");
    for (String table : List.of("pay_tcc_cancel", "pay_tcc_dispatch", "pay_tcc_receipt"))
      fixture.update("DELETE FROM " + table);
    fixture.update(
        "UPDATE pay_tcc_execution SET recovery_owner=?,recovery_lease_until=?",
        token,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
  }

  /** 输入Pay VO与owned原载荷精确对应，状态不代替数据库当前读。 */
  private com.cloudmall.pay.domain.vo.PayVO rollbackTokenPayment() {
    var payment = new com.cloudmall.pay.domain.vo.PayVO();
    payment.payNo = "pay-1";
    payment.userId = 7L;
    payment.orderNo = "order-1";
    payment.amount = BigDecimal.TEN;
    payment.status = "PENDING";
    return payment;
  }

  /** 程序化owned短事务证明本地写与回滚，不代理REQUIRES_NEW或NOT_SUPPORTED。 */
  private String rollbackTokenTransaction(java.util.function.Supplier<String> action) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    return template.execute(transactionStatus -> action.get());
  }

  /** 克隆原来源表，缺失cancel时用实际Mapper仅建owned表；原库不初始化新业务表。 */
  private com.cloudmall.pay.service.impl.PayTccCancelStore ownedCancelRecoveryIdentityStore() {
    boolean originalCancelExists =
        source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='cloudmall_pay'"
                    + " AND table_name='pay_tcc_cancel'",
                Integer.class)
            == 1;
    for (String table :
        List.of("pay_tcc_cancel", "pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt")) {
      fixture.execute("DROP TABLE IF EXISTS " + table);
      if (table.equals("pay_tcc_cancel") && !originalCancelExists) continue;
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    }
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccCancelMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      cancelRecoveryIdentityMapper =
          session.getMapper(com.cloudmall.pay.mapper.PayTccCancelMapper.class);
      if (!originalCancelExists) cancelRecoveryIdentityMapper.ensureTable();
      return new com.cloudmall.pay.service.impl.PayTccCancelStore(
          cancelRecoveryIdentityMapper,
          records,
          mock(com.cloudmall.pay.mapper.PayTccExecutionMapper.class),
          mock(com.cloudmall.pay.mapper.PayTccDispatchMapper.class),
          mock(com.cloudmall.pay.mapper.PayTccReceiptMapper.class),
          intents);
    } catch (Exception failure) {
      throw new IllegalStateException("owned cancel identity factory failed", failure);
    }
  }

  /** 精确来源、旧的到期任务和无任何资金执行；不使用DB NOW或紧邻时钟边界。 */
  private void seedOwnedCancelIdentity(String state, String originalPayNo) {
    seedIntentAndPayment("PENDING", "INIT");
    fixture.update("DELETE FROM pay_debit_intent");
    for (String table :
        List.of("pay_tcc_cancel", "pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt"))
      fixture.update("DELETE FROM " + table);
    fixture.update("UPDATE pay_record SET pay_no=?,debit_mode='TCC'", originalPayNo);
    var fixed = LocalDateTime.of(2026, 10, 1, 0, 0);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,attempts,next_retry_at,last_error,created_at,updated_at)"
            + " VALUES(1,?,7,'order-1',10,?,0,?,'owned-original-error',?,?)",
        originalPayNo,
        state,
        fixed,
        fixed,
        fixed);
  }

  /** 同owned数据源程序化短事务，仅证明本地Mapper/Store，不代理REQUIRES_NEW。 */
  private com.cloudmall.pay.domain.po.PayTccCancelPO cancelIdentityTransaction(
      java.util.function.Supplier<com.cloudmall.pay.domain.po.PayTccCancelPO> action) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    return template.execute(transactionStatus -> action.get());
  }

  /** 恢复SQL涉及六表的完整事实，拒绝不得修改资金/租约/退避字段。 */
  private List<List<Map<String, Object>>> ownedCancelIdentityFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    for (String table :
        List.of("pay_tcc_cancel", "pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt"))
      facts.add(fixture.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    return facts;
  }

  /** 实际receipt CI查询读到另一原支付号，Facade不能返回其终态。 */
  @Test
  void receiptLookupAliasCannotReturnAnotherPayment() {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
    fixture.update("UPDATE pay_tcc_receipt SET pay_no='PAY-1',state='CONFIRMED',branch_id=11");
    assertEquals(
        "PAY-1",
        fixture.queryForMap("SELECT * FROM pay_tcc_receipt WHERE pay_no='pay-1'").get("pay_no"));
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    var facade =
        new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, store, resource);
    var before = ownedReceiptClaimFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(facade.read("pay-1").payNo));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        },
        () -> assertNull(returned.get(), "alias receipt must not be returned as requested payment"),
        () -> assertEquals(before, ownedReceiptClaimFacts()),
        () -> org.mockito.Mockito.verifyNoInteractions(bridge, resource));
  }

  /** 真实CI双向别名四状态read/lock均拒绝，原callback拒绝仍无写。 */
  @Test
  void receiptLookupAliasesRejectAcrossAllPersistedStates() {
    var store = ownedReceiptClaimStore();
    var callback = new com.cloudmall.pay.service.impl.PayTccReceiptBusinessCallback(store);
    for (boolean uppercaseOriginal : List.of(false, true)) {
      String original = uppercaseOriginal ? "PAY-1" : "pay-1";
      String requested = uppercaseOriginal ? "pay-1" : "PAY-1";
      for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
        var dispatch = seedOwnedReceiptClaim();
        assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
        fixture.update(
            "UPDATE pay_tcc_receipt SET pay_no=?,state=?,branch_id=?",
            original,
            state,
            state.equals("CLAIMED") ? null : 11L);
        assertEquals(
            original,
            fixture
                .queryForMap("SELECT * FROM pay_tcc_receipt WHERE pay_no=?", requested)
                .get("pay_no"));
        var before = ownedReceiptClaimFacts();
        var readFailure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class, () -> store.read(requested));
        assertEquals("PAY_TCC_RECEIPT_CONFLICT", readFailure.getCode());
        var lockFailure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    dispatchTransaction(
                        () -> {
                          store.lock(requested);
                          return true;
                        }));
        assertEquals("PAY_TCC_RECEIPT_CONFLICT", lockFailure.getCode());
        for (String action : List.of("try", "confirm", "cancel")) {
          var context = receiptLookupContext(requested);
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      dispatchTransaction(
                          () -> {
                            if (action.equals("try")) return callback.tryBusiness(context);
                            if (action.equals("confirm")) return callback.confirmBusiness(context);
                            return callback.cancelBusiness(context);
                          }));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        }
        assertEquals(before, ownedReceiptClaimFacts());
      }
    }
  }

  /** 精确读取/锁定/VO保持原字段，终态和缺失callback控制仍只读、不注册资源。 */
  @Test
  void exactReceiptLookupsKeepNullAndCallbackSemantics() {
    var store = ownedReceiptClaimStore();
    var callback = new com.cloudmall.pay.service.impl.PayTccReceiptBusinessCallback(store);
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    var facade =
        new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, store, resource);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      var dispatch = seedOwnedReceiptClaim();
      assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
      fixture.update(
          "UPDATE pay_tcc_receipt SET state=?,branch_id=?",
          state,
          state.equals("CLAIMED") ? null : 11L);
      var before = ownedReceiptClaimFacts();
      var read = store.read("pay-1");
      assertEquals("pay-1", read.payNo);
      assertEquals(state, read.state);
      assertTrue(
          dispatchTransaction(
              () -> {
                assertEquals(json.valueToTree(read), json.valueToTree(store.lock("pay-1")));
                return true;
              }));
      var view = facade.read("pay-1");
      assertEquals(read.payNo, view.payNo);
      assertEquals(read.xid, view.xid);
      assertEquals(read.branchId, view.branchId);
      assertEquals(read.state, view.state);
      assertEquals(read.operationKey, view.operationKey);
      assertEquals(read.ledgerKey, view.ledgerKey);
      if (state.equals("CONFIRMED") || state.equals("CANCELLED")) {
        assertEquals(
            state.equals("CONFIRMED"),
            dispatchTransaction(() -> callback.confirmBusiness(receiptLookupContext("pay-1"))));
        assertEquals(
            state.equals("CANCELLED"),
            dispatchTransaction(() -> callback.cancelBusiness(receiptLookupContext("pay-1"))));
      }
      assertEquals(before, ownedReceiptClaimFacts());
    }
    var before = ownedReceiptClaimFacts();
    assertNull(store.read("absent-pay"));
    assertTrue(
        dispatchTransaction(
            () -> {
              assertNull(store.lock("absent-pay"));
              return true;
            }));
    var missing =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class, () -> facade.read("absent-pay"));
    assertEquals("PAY_TCC_RECEIPT_NOT_FOUND", missing.getCode());
    assertEquals(404, missing.getHttpStatus());
    assertTrue(
        !dispatchTransaction(() -> callback.confirmBusiness(receiptLookupContext("absent-pay"))));
    assertTrue(
        !dispatchTransaction(() -> callback.cancelBusiness(receiptLookupContext("absent-pay"))));
    var missingTry =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () ->
                dispatchTransaction(
                    () -> callback.tryBusiness(receiptLookupContext("absent-pay"))));
    assertEquals("PAY_TCC_RECEIPT_CONFLICT", missingTry.getCode());
    assertEquals(before, ownedReceiptClaimFacts());
    org.mockito.Mockito.verifyNoInteractions(bridge, resource);
  }

  /** 原callback载荷的受控context，只调用纯业务callback而非官方Fence/TC handler。 */
  private io.seata.rm.tcc.api.BusinessActionContext receiptLookupContext(String payNo) {
    var request = new com.cloudmall.pay.domain.dto.PayTccDispatchDTO();
    request.payNo = payNo;
    request.userId = 7L;
    request.orderNo = "order-1";
    request.amount = BigDecimal.TEN;
    var context =
        new io.seata.rm.tcc.api.BusinessActionContext("t171-xid", "11", Map.of("request", request));
    context.setActionName(com.cloudmall.pay.service.PayTccReceiptLocalResource.ACTION_NAME);
    return context;
  }

  /** 实际原支付CI别名不能证明receipt认领，更不能插入新CLAIMED并获得winner。 */
  @Test
  void receiptClaimCannotUseAnotherOriginalPayment() {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    fixture.update("UPDATE pay_record SET pay_no='PAY-1' WHERE pay_no='pay-1'");
    assertEquals("PAY-1", records.selectByPayNo("pay-1").get(0).payNo);
    var before = ownedReceiptClaimFacts();
    var winner = new java.util.concurrent.atomic.AtomicReference<Boolean>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> winner.set(dispatchTransaction(() -> store.claim(dispatch))));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        },
        () -> assertNull(winner.get(), "alien source must not grant receipt claim winner"),
        () -> assertEquals(before, ownedReceiptClaimFacts()));
  }

  /** 两个实际CI来源独立或同时别名，首次及四种既有receipt重放均拒绝且四表不动。 */
  @Test
  void receiptClaimRejectsAliasSourcesBeforeFirstOrReplay() {
    var store = ownedReceiptClaimStore();
    for (String phase : List.of("first", "CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (String sourceKind : List.of("payment", "anchor", "both")) {
        var dispatch = seedOwnedReceiptClaim();
        if (!phase.equals("first")) {
          assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
          fixture.update(
              "UPDATE pay_tcc_receipt SET state=?,branch_id=?",
              phase,
              phase.equals("CLAIMED") ? null : 11L);
        }
        if (!sourceKind.equals("anchor"))
          fixture.update("UPDATE pay_record SET pay_no='PAY-1' WHERE pay_no='pay-1'");
        if (!sourceKind.equals("payment"))
          fixture.update("UPDATE pay_tcc_dispatch SET pay_no='PAY-1' WHERE id=1");
        assertEquals(
            sourceKind.equals("anchor") ? "pay-1" : "PAY-1",
            records.selectByPayNo("pay-1").get(0).payNo);
        assertEquals(
            sourceKind.equals("payment") ? "pay-1" : "PAY-1",
            dispatchMirrorMapper.selectByPayNo("pay-1").payNo);
        var before = ownedReceiptClaimFacts();
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> dispatchTransaction(() -> store.claim(dispatch)),
                phase + "/" + sourceKind);
        assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
        assertEquals(before, ownedReceiptClaimFacts());
      }
  }

  /** 精确来源只插入一次CLAIMED，合法既有状态重放和原缺来源/模式拒绝保持。 */
  @Test
  void receiptClaimExactSourcesKeepWinnerReplayAndOriginalRejections() {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    var sourceBefore = ownedDispatchLookupFacts();
    assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
    var claimed = fixture.queryForMap("SELECT * FROM pay_tcc_receipt");
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
    assertEquals("pay-1", claimed.get("pay_no"));
    assertEquals("t171-xid", claimed.get("xid"));
    assertEquals("CLAIMED", claimed.get("state"));
    assertNull(claimed.get("branch_id"));
    assertEquals("tcc-debit:pay-1", claimed.get("operation_key"));
    assertEquals("pay-1", claimed.get("ledger_key"));
    assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) claimed.get("amount")));
    assertEquals(sourceBefore, ownedDispatchLookupFacts());
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      fixture.update(
          "UPDATE pay_tcc_receipt SET state=?,branch_id=?",
          state,
          state.equals("CLAIMED") ? null : 11L);
      var before = ownedReceiptClaimFacts();
      assertTrue(!dispatchTransaction(() -> store.claim(dispatch)));
      assertEquals(before, ownedReceiptClaimFacts());
    }
    fixture.update("UPDATE pay_record SET status='SUCCESS' WHERE pay_no='pay-1'");
    fixture.update("UPDATE pay_tcc_dispatch SET state='CONFIRMED' WHERE id=1");
    var beforeReplay = ownedReceiptClaimFacts();
    assertTrue(!dispatchTransaction(() -> store.claim(dispatch)));
    assertEquals(beforeReplay, ownedReceiptClaimFacts());
    for (String invalid : List.of("missing-payment", "missing-anchor", "legacy-mode")) {
      var requested = seedOwnedReceiptClaim();
      if (invalid.equals("missing-payment")) fixture.update("DELETE FROM pay_record");
      else if (invalid.equals("missing-anchor")) fixture.update("DELETE FROM pay_tcc_dispatch");
      else fixture.update("UPDATE pay_record SET debit_mode='LEGACY'");
      var before = ownedReceiptClaimFacts();
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> dispatchTransaction(() -> store.claim(requested)));
      assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
      assertEquals(before, ownedReceiptClaimFacts());
    }
  }

  /** 已有POST消耗标记不能新建receipt并触发Pay资源注册。 */
  @Test
  void consumedPostEvidenceCannotCreateFreshPayReceipt() {
    assertFreshReceiptRejected("posted");
  }

  /** 已观察User分支不能靠PREPARED状态重新获得Pay receipt winner。 */
  @Test
  void observedUserBranchCannotCreateFreshPayReceipt() {
    assertFreshReceiptRejected("observed-branch");
  }

  /** 首次认领各阶段证据单变异均409，无receipt写和资源尝试。 */
  @Test
  void freshReceiptPermissionPredicatesBlockWithoutResourceCalls() {
    for (String variant :
        List.of(
            "posted",
            "observed-branch",
            "input-branch",
            "input-null-state",
            "input-state",
            "current-state")) assertFreshReceiptRejected(variant);
  }

  /** 合法fresh只一次资源尝试；已有四状态在非fresh证据下仍只返回原事实。 */
  @Test
  void freshReceiptWinnerAndExistingReplayKeepTheirBoundaries() {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    when(resource.prepare(any(), any())).thenReturn(true);
    var facade = receiptClaimFacade(store, dispatch, resource);
    var request = receiptClaimRequest(dispatch);
    assertEquals("CLAIMED", facade.prepare(request).state);
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
    var afterFresh = ownedReceiptClaimFacts();
    assertEquals("CLAIMED", facade.prepare(request).state);
    assertEquals(afterFresh, ownedReceiptClaimFacts());
    verify(resource).prepare(any(), any());
    fixture.update("UPDATE pay_record SET status='SUCCESS'");
    fixture.update(
        "UPDATE pay_tcc_dispatch SET state='CONFIRMED',post_attempted_at='2026-10-15"
            + " 00:00:00.123',user_branch_id=7");
    dispatch.state = "UNKNOWN";
    dispatch.userBranchId = 7L;
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      fixture.update(
          "UPDATE pay_tcc_receipt SET state=?,branch_id=?",
          state,
          state.equals("CLAIMED") ? null : 11L);
      var before = ownedReceiptClaimFacts();
      var replayResource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
      var replayFacade = receiptClaimFacade(store, dispatch, replayResource);
      var result = replayFacade.prepare(request);
      assertEquals(state, result.state);
      assertEquals("t171-xid", result.xid);
      assertEquals(state.equals("CLAIMED") ? null : 11L, result.branchId);
      assertEquals(before, ownedReceiptClaimFacts());
      org.mockito.Mockito.verifyNoInteractions(replayResource);
    }
  }

  /** 实际claim/四表事实加Facade资源调用边界，不运行真实LocalTCC/TC。 */
  private void assertFreshReceiptRejected(String variant) {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    mutateFreshReceiptEvidence(dispatch, variant);
    var before = ownedReceiptClaimFacts();
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    when(resource.prepare(any(), any())).thenReturn(true);
    var facade = receiptClaimFacade(store, dispatch, resource);
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> facade.prepare(receiptClaimRequest(dispatch)));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () -> org.mockito.Mockito.verifyNoInteractions(resource),
        () -> assertEquals(before, ownedReceiptClaimFacts()));
  }

  /** 只变异一个派发阶段证据，其余载荷/Pay/receipt源保持原样。 */
  private void mutateFreshReceiptEvidence(
      com.cloudmall.pay.domain.vo.PayTccDispatchVO dispatch, String variant) {
    switch (variant) {
      case "posted" ->
          fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at='2026-10-15 00:00:00.123'");
      case "observed-branch" -> fixture.update("UPDATE pay_tcc_dispatch SET user_branch_id=7");
      case "input-branch" -> dispatch.userBranchId = 7L;
      case "input-null-state" -> dispatch.state = null;
      case "input-state" -> dispatch.state = "TRY_ACCEPTED";
      case "current-state" -> fixture.update("UPDATE pay_tcc_dispatch SET state='TRY_ACCEPTED'");
      default -> throw new IllegalArgumentException("unknown fresh receipt fixture variant");
    }
  }

  /** claim独立程序化提交，read是真实Mapper，bridge/resource仅为受控替身。 */
  private com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl receiptClaimFacade(
      com.cloudmall.pay.service.impl.PayTccReceiptStore actual,
      com.cloudmall.pay.domain.vo.PayTccDispatchVO dispatch,
      com.cloudmall.pay.service.PayTccReceiptLocalResource resource) {
    return receiptClaimFacade(actual, dispatch, resource, () -> {});
  }

  /** 后置hook只在claim短事务结束后运行，资源注册仍为外部受控替身。 */
  private com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl receiptClaimFacade(
      com.cloudmall.pay.service.impl.PayTccReceiptStore actual,
      com.cloudmall.pay.domain.vo.PayTccDispatchVO dispatch,
      com.cloudmall.pay.service.PayTccReceiptLocalResource resource,
      Runnable afterClaim) {
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    when(bridge.prepare(any())).thenReturn(dispatch);
    var boundary = mock(com.cloudmall.pay.service.impl.PayTccReceiptStore.class);
    when(boundary.claim(any()))
        .thenAnswer(
            invocation -> {
              boolean winner = dispatchTransaction(() -> actual.claim(invocation.getArgument(0)));
              afterClaim.run();
              return winner;
            });
    when(boundary.read(any())).thenAnswer(invocation -> actual.read(invocation.getArgument(0)));
    return new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, boundary, resource);
  }

  /** 仅构造与原锚点匹配的既有DTO，不添加协议字段。 */
  private com.cloudmall.pay.domain.dto.PayTccDispatchDTO receiptClaimRequest(
      com.cloudmall.pay.domain.vo.PayTccDispatchVO dispatch) {
    var request = new com.cloudmall.pay.domain.dto.PayTccDispatchDTO();
    request.payNo = dispatch.payNo;
    request.userId = dispatch.userId;
    request.orderNo = dispatch.orderNo;
    request.amount = new BigDecimal(dispatch.amount);
    return request;
  }

  /** winner资源调用后receipt xid变化，不能把另一xid作为原准备结果。 */
  @Test
  void receiptWinnerReadbackCannotReturnChangedXid() {
    assertReceiptReadbackRejected(false, "UPDATE pay_tcc_receipt SET xid='changed-receipt-xid'");
  }

  /** replay claim结束后operation变化，不能返回已漂移的receipt键。 */
  @Test
  void receiptReplayReadbackCannotReturnChangedOperation() {
    assertReceiptReadbackRejected(
        true, "UPDATE pay_tcc_receipt SET operation_key='changed-receipt-operation'");
  }

  /** winner/replay读回七字段分别变异，原payNo门及新的完整载荷门均拒绝。 */
  @Test
  void receiptReadbackPayloadChangesCannotPublishForeignViews() {
    for (String mutation :
        List.of(
            "UPDATE pay_tcc_receipt SET pay_no='PAY-1'",
            "UPDATE pay_tcc_receipt SET xid='changed-receipt-xid'",
            "UPDATE pay_tcc_receipt SET user_id=8",
            "UPDATE pay_tcc_receipt SET order_no='changed-order'",
            "UPDATE pay_tcc_receipt SET amount=11.00",
            "UPDATE pay_tcc_receipt SET operation_key='changed-receipt-operation'",
            "UPDATE pay_tcc_receipt SET ledger_key='changed-ledger'"))
      for (boolean replay : List.of(false, true)) assertReceiptReadbackRejected(replay, mutation);
  }

  /** 同原载荷的四状态和金额等价可读回，实际缺行保持原503及资源尝试次数。 */
  @Test
  void receiptReadbackKeepsProgressEqualMoneyAndMissingResult() {
    for (boolean replay : List.of(false, true))
      for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
        var store = ownedReceiptClaimStore();
        var dispatch = seedOwnedReceiptClaim();
        dispatch.amount = "10.000";
        if (replay) assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
        var afterProgress =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        Runnable progress =
            () -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              fixture.update(
                  "UPDATE pay_tcc_receipt SET state=?,branch_id=?,updated_at=NOW(3)",
                  state,
                  state.equals("CLAIMED") ? null : 11L);
              afterProgress.set(ownedReceiptClaimFacts());
            };
        var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
        if (!replay)
          when(resource.prepare(any(), any()))
              .thenAnswer(
                  invocation -> {
                    progress.run();
                    return true;
                  });
        var facade = receiptClaimFacade(store, dispatch, resource, replay ? progress : () -> {});
        var result = facade.prepare(receiptClaimRequest(dispatch));
        assertEquals(state, result.state);
        assertEquals(state.equals("CLAIMED") ? null : 11L, result.branchId);
        assertEquals("t171-xid", result.xid);
        assertEquals(afterProgress.get(), ownedReceiptClaimFacts());
        verify(resource, org.mockito.Mockito.times(replay ? 0 : 1)).prepare(any(), any());
        assertEquals("10.000", dispatch.amount);
      }
    for (boolean replay : List.of(false, true)) {
      var store = ownedReceiptClaimStore();
      var dispatch = seedOwnedReceiptClaim();
      if (replay) assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
      var afterRemove =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      Runnable remove =
          () -> {
            org.junit.jupiter.api.Assertions.assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            fixture.update("DELETE FROM pay_tcc_receipt");
            afterRemove.set(ownedReceiptClaimFacts());
          };
      var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
      if (!replay)
        when(resource.prepare(any(), any()))
            .thenAnswer(
                invocation -> {
                  remove.run();
                  return true;
                });
      var facade = receiptClaimFacade(store, dispatch, resource, replay ? remove : () -> {});
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> facade.prepare(receiptClaimRequest(dispatch)));
      assertEquals("PAY_TCC_RECEIPT_UNKNOWN", failure.getCode());
      assertEquals(503, failure.getHttpStatus());
      assertEquals(afterRemove.get(), ownedReceiptClaimFacts());
      verify(resource, org.mockito.Mockito.times(replay ? 0 : 1)).prepare(any(), any());
    }
  }

  /** 真实claim/读回之间注入owned变更，原winner调用不撤回但不能追加注册/写入。 */
  private void assertReceiptReadbackRejected(boolean replay, String mutationSql) {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    if (replay) assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
    var afterChange =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    Runnable mutation =
        () -> {
          org.junit.jupiter.api.Assertions.assertFalse(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          fixture.update(mutationSql);
          afterChange.set(ownedReceiptClaimFacts());
        };
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    if (!replay)
      when(resource.prepare(any(), any()))
          .thenAnswer(
              invocation -> {
                mutation.run();
                return true;
              });
    var facade = receiptClaimFacade(store, dispatch, resource, replay ? mutation : () -> {});
    var returned =
        new java.util.concurrent.atomic.AtomicReference<
            com.cloudmall.pay.domain.vo.PayTccReceiptVO>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(facade.prepare(receiptClaimRequest(dispatch))));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () ->
            assertNull(
                returned.get() == null
                    ? null
                    : returned.get().xid + "|" + returned.get().operationKey));
    org.junit.jupiter.api.Assertions.assertNotNull(afterChange.get());
    assertEquals(afterChange.get(), ownedReceiptClaimFacts());
    verify(resource, org.mockito.Mockito.times(replay ? 0 : 1)).prepare(any(), any());
  }

  /** 已有receipt的零分支不能被重放为有效资源事实。 */
  @Test
  void receiptReplayCannotAcceptZeroBranch() {
    assertExistingReceiptEvidenceRejected("TRYING", 0L);
  }

  /** winner资源尝试后读回的未知状态不能作为准备结果发布。 */
  @Test
  void receiptWinnerReadbackCannotAcceptUnknownState() {
    assertReceiptReadbackRejected(false, "UPDATE pay_tcc_receipt SET state='UNKNOWN'");
  }

  /** 实际DB保留坏状态和分支，重放只拒绝而不重认领或注册。 */
  @Test
  void receiptReplayRejectsInvalidStateBranchMatrix() {
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      for (Long branch : List.of(0L, -1L)) assertExistingReceiptEvidenceRejected(state, branch);
      if (!state.equals("CLAIMED")) assertExistingReceiptEvidenceRejected(state, null);
    }
    for (String state : List.of("UNKNOWN", "", "trying", "TRYING "))
      assertExistingReceiptEvidenceRejected(state, 11L);
  }

  /** winner/replay事务结束后的坏证据不能越过prepare读回门。 */
  @Test
  void receiptReadbackRejectsInvalidStateBranchMatrix() {
    for (boolean replay : List.of(false, true)) {
      for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
        for (long branch : List.of(0L, -1L))
          assertReceiptReadbackRejected(
              replay, "UPDATE pay_tcc_receipt SET state='" + state + "',branch_id=" + branch);
        if (!state.equals("CLAIMED"))
          assertReceiptReadbackRejected(
              replay, "UPDATE pay_tcc_receipt SET state='" + state + "',branch_id=NULL");
      }
      for (String state : List.of("UNKNOWN", "", "trying", "TRYING "))
        assertReceiptReadbackRejected(
            replay, "UPDATE pay_tcc_receipt SET state='" + state + "',branch_id=11");
    }
  }

  /** 合法正分支上界及CLAIMED未知/已绑定均保留，不推断TC注册和资金结果。 */
  @Test
  void receiptValidStateBranchMatrixKeepsReplayAndReadbackProgress() {
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (Long branch : java.util.Arrays.asList(null, 1L, Long.MAX_VALUE)) {
        if (branch == null && !state.equals("CLAIMED")) continue;
        var store = ownedReceiptClaimStore();
        var dispatch = seedOwnedReceiptClaim();
        assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
        fixture.update("UPDATE pay_tcc_receipt SET state=?,branch_id=?", state, branch);
        fixture.update("UPDATE pay_record SET status='SUCCESS'");
        fixture.update(
            "UPDATE pay_tcc_dispatch SET"
                + " state='CONFIRMED',user_branch_id=7,post_attempted_at=NOW(3)");
        dispatch.state = null;
        dispatch.userBranchId = 7L;
        var before = ownedReceiptClaimFacts();
        var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
        var result =
            receiptClaimFacade(store, dispatch, resource).prepare(receiptClaimRequest(dispatch));
        assertEquals(state, result.state);
        assertEquals(branch, result.branchId);
        assertEquals(before, ownedReceiptClaimFacts());
        org.mockito.Mockito.verifyNoInteractions(resource);
      }
    for (boolean replay : List.of(false, true)) {
      var store = ownedReceiptClaimStore();
      var dispatch = seedOwnedReceiptClaim();
      if (replay) assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
      var afterProgress =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      Runnable progress =
          () -> {
            org.junit.jupiter.api.Assertions.assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            fixture.update(
                "UPDATE pay_tcc_receipt SET state='CLAIMED',branch_id=?", Long.MAX_VALUE);
            afterProgress.set(ownedReceiptClaimFacts());
          };
      var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
      if (!replay)
        when(resource.prepare(any(), any()))
            .thenAnswer(
                invocation -> {
                  progress.run();
                  return true;
                });
      var result =
          receiptClaimFacade(store, dispatch, resource, replay ? progress : () -> {})
              .prepare(receiptClaimRequest(dispatch));
      assertEquals("CLAIMED", result.state);
      assertEquals(Long.MAX_VALUE, result.branchId);
      assertEquals(afterProgress.get(), ownedReceiptClaimFacts());
      verify(resource, org.mockito.Mockito.times(replay ? 0 : 1)).prepare(any(), any());
    }
  }

  /** 已有坏行原样保留，actual claim返回前拒绝，资源替身不能被触发。 */
  private void assertExistingReceiptEvidenceRejected(String state, Long branch) {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
    fixture.update("UPDATE pay_tcc_receipt SET state=?,branch_id=?", state, branch);
    var before = ownedReceiptClaimFacts();
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    var facade = receiptClaimFacade(store, dispatch, resource);
    var returned =
        new java.util.concurrent.atomic.AtomicReference<
            com.cloudmall.pay.domain.vo.PayTccReceiptVO>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(facade.prepare(receiptClaimRequest(dispatch))));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () ->
            assertNull(
                returned.get() == null
                    ? null
                    : returned.get().state + "|" + returned.get().branchId),
        () -> assertEquals(before, ownedReceiptClaimFacts()),
        () -> org.mockito.Mockito.verifyNoInteractions(resource));
  }

  /** 零owner分支不构成另一有效资源，不得提前ACK被拒回滚。 */
  @Test
  void rejectedReceiptRollbackCannotAckZeroOwnerBranch() {
    assertRejectedReceiptOwner("TRYING", 0L, false);
  }

  /** 负owner分支即使已标终态也不能证明有效分支归属。 */
  @Test
  void rejectedReceiptRollbackCannotAckNegativeOwnerBranch() {
    assertRejectedReceiptOwner("CONFIRMED", -1L, false);
  }

  /** 原拒绝控制与非正branch均不ACK，同/异XID分别验证而不改owned事实。 */
  @Test
  void rejectedReceiptRollbackRejectsInvalidOwnerMatrix() {
    for (boolean otherXid : List.of(false, true)) {
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
        for (Long branch : java.util.Arrays.asList(null, 0L, -1L))
          assertRejectedReceiptOwner(state, branch, otherXid);
      for (Long branch : java.util.Arrays.asList(null, 0L, -1L, 11L, Long.MAX_VALUE)) {
        // 同XID的CLAIMED/null由原callback合法空取消，不属于另一owner提前ACK。
        if (!otherXid && branch == null) continue;
        assertRejectedReceiptOwner("CLAIMED", branch, otherXid);
      }
      for (String state : List.of("UNKNOWN", "", "trying", "TRYING "))
        assertRejectedReceiptOwner(state, 11L, otherXid);
    }
  }

  /** 合法另一owner可ACK，Fence/当前分支归属短路继续由原真实流程处理。 */
  @Test
  void rejectedReceiptRollbackKeepsValidOwnersAndPreconditionQueries() {
    for (boolean otherXid : List.of(false, true))
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
        for (Long branch : List.of(8L, Long.MAX_VALUE)) {
          var resource = ownedReceiptRollbackResource();
          var context = seedOwnedRejectedReceiptOwner(state, branch, otherXid);
          var before = ownedReceiptRollbackFacts();
          assertTrue(withOwnedReceiptFence(() -> resource.rollback(context)));
          assertEquals(before, ownedReceiptRollbackFacts());
          assertReceiptOwnerQueryOrder(context);
        }
    var emptyCancelResource = ownedReceiptRollbackResource();
    var emptyCancelContext = seedOwnedRejectedReceiptOwner("CLAIMED", null, false);
    var beforeEmptyCancel = ownedReceiptRollbackFacts();
    assertTrue(withOwnedReceiptFence(() -> emptyCancelResource.rollback(emptyCancelContext)));
    var afterEmptyCancel = ownedReceiptRollbackFacts();
    assertEquals(beforeEmptyCancel.subList(0, 3), afterEmptyCancel.subList(0, 3));
    assertEquals(beforeEmptyCancel.get(4), afterEmptyCancel.get(4));
    var expectedReceipt = new java.util.LinkedHashMap<>(beforeEmptyCancel.get(3).get(0));
    var actualReceipt = afterEmptyCancel.get(3).get(0);
    expectedReceipt.put("state", "CANCELLED");
    expectedReceipt.put("branch_id", 7L);
    expectedReceipt.put("updated_at", actualReceipt.get("updated_at"));
    assertEquals(expectedReceipt, actualReceipt);
    assertTrue(
        ((LocalDateTime) actualReceipt.get("updated_at"))
            .isAfter((LocalDateTime) beforeEmptyCancel.get(3).get(0).get("updated_at")));
    assertReceiptOwnerQueryOrder(emptyCancelContext);
    for (String gate :
        List.of(
            "fence-action", "fence-confirmed", "fence-cancelled", "own-receipt", "missing-fence")) {
      var resource = ownedReceiptRollbackResource();
      var context = seedOwnedRejectedReceiptOwner("CONFIRMED", 8L, false);
      switch (gate) {
        case "fence-action" ->
            fixture.update("UPDATE tcc_fence_log SET action_name='other-action'");
        case "fence-confirmed" -> fixture.update("UPDATE tcc_fence_log SET status=2");
        case "fence-cancelled" -> fixture.update("UPDATE tcc_fence_log SET status=3");
        case "own-receipt" -> fixture.update("UPDATE pay_tcc_receipt SET branch_id=7");
        case "missing-fence" -> {
          fixture.update("DELETE FROM tcc_fence_log");
          fixture.update("UPDATE pay_tcc_receipt SET branch_id=0");
        }
        default -> throw new IllegalArgumentException("unknown receipt rollback gate");
      }
      var before = ownedReceiptRollbackFacts();
      org.junit.jupiter.api.Assertions.assertFalse(
          withOwnedReceiptFence(() -> resource.rollback(context)));
      assertEquals(before, ownedReceiptRollbackFacts());
      if (gate.equals("missing-fence")) assertReceiptOwnerQueryOrder(context);
      else {
        verify(receiptRollbackMapper, never()).findOwners(any(), any(), any(), any());
        verify(receiptRollbackMapper, times(gate.equals("own-receipt") ? 1 : 0))
            .countByBranch(context.getXid(), context.getBranchId());
      }
    }
  }

  /** CI匹配另一XID的SUSPENDED不能用于原分支的被拒回滚ACK。 */
  @Test
  void receiptRollbackCannotBorrowAliasFence() {
    assertReceiptInitialFenceAliasRejected(false, "T171-XID");
  }

  /** 即使已有本分支CONFIRMED receipt，异拼写Fence也不能作为提交证明。 */
  @Test
  void receiptCommitCannotBorrowAliasFence() {
    assertReceiptInitialFenceAliasRejected(true, "T171-XID");
  }

  /** 首读SQL别名与二读异常Mapper结果分别阻断，不能回落callback成功。 */
  @Test
  void receiptSettlementRejectsFenceIdentityMismatchAtBothReads() {
    for (boolean committing : List.of(false, true))
      for (String alias : List.of("T171-XID", "t171-XID"))
        assertReceiptInitialFenceAliasRejected(committing, alias);
    for (String field :
        List.of("xid", "branch", "action", "null-xid", "null-branch", "null-action")) {
      var resource = ownedReceiptRollbackResource();
      var context = seedOwnedRejectedReceiptOwner("CANCELLED", 7L, false);
      var before = ownedReceiptRollbackFacts();
      var readCount = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                var current =
                    (com.cloudmall.pay.domain.po.PayTccFencePO)
                        org.mockito.Mockito.mockingDetails(fenceIdentityMapper)
                            .getMockCreationSettings()
                            .getDefaultAnswer()
                            .answer(invocation);
                if (readCount.incrementAndGet() == 2) {
                  switch (field) {
                    case "xid" -> current.xid = "T171-XID";
                    case "branch" -> current.branchId = 8L;
                    case "action" -> current.actionName = "other-action";
                    case "null-xid" -> current.xid = null;
                    case "null-branch" -> current.branchId = null;
                    case "null-action" -> current.actionName = null;
                    default ->
                        throw new IllegalArgumentException("unknown returned Fence identity field");
                  }
                }
                return current;
              })
          .when(fenceIdentityMapper)
          .findByBranch(context.getXid(), context.getBranchId());
      org.junit.jupiter.api.Assertions.assertFalse(
          withOwnedReceiptFence(() -> resource.rollback(context)));
      assertEquals(2, readCount.get());
      assertEquals(before, ownedReceiptRollbackFacts());
      verify(receiptRollbackMapper, never()).countByBranch(any(), any());
      verify(receiptRollbackMapper, never()).findOwners(any(), any(), any(), any());
    }
  }

  /** 精确终态与Fence缺失永久终态保持，错误方向false且不补写Fence。 */
  @Test
  void receiptSettlementKeepsExactAndMissingFenceTerminalEvidence() {
    for (String fenceMode : List.of("exact", "missing", "nonmatching-padding"))
      for (boolean committing : List.of(false, true)) {
        var resource = ownedReceiptRollbackResource();
        var context =
            seedOwnedRejectedReceiptOwner(committing ? "CONFIRMED" : "CANCELLED", 7L, false);
        if (fenceMode.equals("missing")) fixture.update("DELETE FROM tcc_fence_log");
        else fixture.update("UPDATE tcc_fence_log SET status=?", committing ? 2 : 3);
        if (fenceMode.equals("nonmatching-padding")) {
          fixture.update("UPDATE tcc_fence_log SET xid=?", context.getXid() + " ");
          assertNull(fenceIdentityMapper.findByBranch(context.getXid(), context.getBranchId()));
          org.mockito.Mockito.clearInvocations(fenceIdentityMapper, receiptRollbackMapper);
        }
        var before = ownedReceiptRollbackFacts();
        assertTrue(
            withOwnedReceiptFence(
                () -> committing ? resource.commit(context) : resource.rollback(context)));
        assertEquals(before, ownedReceiptRollbackFacts());
        verify(receiptRollbackMapper, never()).findOwners(any(), any(), any(), any());
      }
    for (boolean committing : List.of(false, true)) {
      var resource = ownedReceiptRollbackResource();
      var context =
          seedOwnedRejectedReceiptOwner(committing ? "CANCELLED" : "CONFIRMED", 7L, false);
      fixture.update("UPDATE tcc_fence_log SET status=?", committing ? 3 : 2);
      var before = ownedReceiptRollbackFacts();
      org.junit.jupiter.api.Assertions.assertFalse(
          withOwnedReceiptFence(
              () -> committing ? resource.commit(context) : resource.rollback(context)));
      assertEquals(before, ownedReceiptRollbackFacts());
    }
  }

  /** 只在owned改变Fence XID，先证真实CI查询命中别名，再核公开入口false与全表不变。 */
  private void assertReceiptInitialFenceAliasRejected(boolean committing, String alias) {
    var resource = ownedReceiptRollbackResource();
    var context =
        seedOwnedRejectedReceiptOwner(
            committing ? "CONFIRMED" : "TRYING", committing ? 7L : 8L, false);
    fixture.update("UPDATE tcc_fence_log SET xid=?,status=?", alias, committing ? 2 : 4);
    var matched = fenceIdentityMapper.findByBranch(context.getXid(), context.getBranchId());
    assertNotNull(matched);
    assertEquals(alias, matched.xid);
    assertNotEquals(context.getXid(), matched.xid);
    org.mockito.Mockito.clearInvocations(fenceIdentityMapper, receiptRollbackMapper);
    var before = ownedReceiptRollbackFacts();
    boolean accepted =
        withOwnedReceiptFence(
            () -> committing ? resource.commit(context) : resource.rollback(context));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> org.junit.jupiter.api.Assertions.assertFalse(accepted),
        () -> assertEquals(before, ownedReceiptRollbackFacts()));
    verify(fenceIdentityMapper).findByBranch(context.getXid(), context.getBranchId());
    org.mockito.Mockito.verifyNoInteractions(receiptRollbackMapper);
  }

  /** 只有payNo大小写别名命中不能证明当前请求的另一owner。 */
  @Test
  void rejectedReceiptOwnerCannotBorrowPayNumberAlias() {
    assertReceiptOwnerKeyAssociation(false, "payNo", "TRYING", 8L);
  }

  /** 只有XID大小写别名命中不能提前ACK被拒分支。 */
  @Test
  void rejectedReceiptOwnerCannotBorrowXidAlias() {
    assertReceiptOwnerKeyAssociation(false, "xid", "TRYING", 8L);
  }

  /** 四键单独CI命中或全CI命中，在三个先进态都不能代替精确唯一键。 */
  @Test
  void rejectedReceiptOwnerRejectsPureAliasKeyMatrix() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
      for (String field : List.of("payNo", "xid", "operationKey", "ledgerKey", "all"))
        assertReceiptOwnerKeyAssociation(false, field, state, 8L);
  }

  /** 每个单独exact键保持原OR归属语义，不能误改成四字段全等。 */
  @Test
  void rejectedReceiptOwnerKeepsEachExactUniqueKeyAssociation() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
      for (String field : List.of("payNo", "xid", "operationKey", "ledgerKey"))
        for (Long branch : List.of(8L, Long.MAX_VALUE))
          assertReceiptOwnerKeyAssociation(true, field, state, branch);
  }

  /** SQL实际命中一条owner；独立核exact关联数量，原查询门与五表不变同时断言。 */
  private void assertReceiptOwnerKeyAssociation(
      boolean exact, String field, String state, Long branch) {
    var resource = ownedReceiptRollbackResource();
    var context = seedOwnedRejectedReceiptOwner(state, branch, false);
    String ownerPayNo = "foreign-pay";
    String ownerXid = "foreign-xid";
    String ownerOperation = "foreign-operation";
    String ownerLedger = "foreign-ledger";
    if (field.equals("payNo") || field.equals("all")) ownerPayNo = exact ? "pay-1" : "PAY-1";
    if (field.equals("xid") || field.equals("all"))
      ownerXid = exact ? context.getXid() : context.getXid().toUpperCase(java.util.Locale.ROOT);
    if (field.equals("operationKey") || field.equals("all"))
      ownerOperation = exact ? "tcc-debit:pay-1" : "TCC-DEBIT:PAY-1";
    if (field.equals("ledgerKey") || field.equals("all")) ownerLedger = exact ? "pay-1" : "PAY-1";
    fixture.update(
        "UPDATE pay_tcc_receipt SET pay_no=?,xid=?,operation_key=?,ledger_key=?",
        ownerPayNo,
        ownerXid,
        ownerOperation,
        ownerLedger);
    var matches =
        receiptRollbackMapper.findOwners("pay-1", context.getXid(), "tcc-debit:pay-1", "pay-1");
    assertEquals(1, matches.size());
    var matched = matches.get(0);
    int exactCount =
        (java.util.Objects.equals(matched.payNo, "pay-1") ? 1 : 0)
            + (java.util.Objects.equals(matched.xid, context.getXid()) ? 1 : 0)
            + (java.util.Objects.equals(matched.operationKey, "tcc-debit:pay-1") ? 1 : 0)
            + (java.util.Objects.equals(matched.ledgerKey, "pay-1") ? 1 : 0);
    assertEquals(exact ? 1 : 0, exactCount);
    org.mockito.Mockito.clearInvocations(receiptRollbackMapper, fenceIdentityMapper);
    var before = ownedReceiptRollbackFacts();
    boolean accepted = withOwnedReceiptFence(() -> resource.rollback(context));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(exact, accepted),
        () -> assertEquals(before, ownedReceiptRollbackFacts()),
        () -> assertReceiptOwnerQueryOrder(context));
  }

  /** 无有效原XID的owner不能仅凭状态和唯一键使被拒分支提前ACK。 */
  @Test
  void rejectedReceiptOwnerCannotAckWithBlankXid() {
    assertRejectedReceiptOwnerPayload("xid-empty", "TRYING");
  }

  /** 零金额owner不符合原资源入站条件，不能证明有效的另一资源。 */
  @Test
  void rejectedReceiptOwnerCannotAckWithZeroAmount() {
    assertRejectedReceiptOwnerPayload("amount-zero", "TRYING");
  }

  /** DB可表达的四域坏值与NOTNULL字段的异常Mapper返回分别拒绝。 */
  @Test
  void rejectedReceiptOwnerRejectsInvalidPayloadDomains() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
      for (String mutation :
          List.of(
              "xid-empty",
              "xid-space",
              "xid-controls",
              "user-zero",
              "user-negative",
              "order-empty",
              "order-space",
              "order-controls",
              "amount-zero",
              "amount-negative")) assertRejectedReceiptOwnerPayload(mutation, state);
    for (String field : List.of("xid", "userId", "orderNo", "amount")) {
      var resource = ownedReceiptRollbackResource();
      var context = seedOwnedRejectedReceiptOwner("TRYING", 8L, false);
      var before = ownedReceiptRollbackFacts();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                @SuppressWarnings("unchecked")
                var owners =
                    (List<com.cloudmall.pay.domain.po.PayTccReceiptPO>)
                        org.mockito.Mockito.mockingDetails(receiptRollbackMapper)
                            .getMockCreationSettings()
                            .getDefaultAnswer()
                            .answer(invocation);
                assertEquals(1, owners.size());
                var owner = owners.get(0);
                switch (field) {
                  case "xid" -> owner.xid = null;
                  case "userId" -> owner.userId = null;
                  case "orderNo" -> owner.orderNo = null;
                  case "amount" -> owner.amount = null;
                  default -> throw new IllegalArgumentException("unknown returned owner field");
                }
                return owners;
              })
          .when(receiptRollbackMapper)
          .findOwners("pay-1", context.getXid(), "tcc-debit:pay-1", "pay-1");
      org.junit.jupiter.api.Assertions.assertFalse(
          withOwnedReceiptFence(() -> resource.rollback(context)));
      assertEquals(before, ownedReceiptRollbackFacts());
      assertReceiptOwnerQueryOrder(context);
    }
  }

  /** 仅校验合法域，不要求owner与incoming用户/订单/金额/XID全等。 */
  @Test
  void rejectedReceiptOwnerKeepsValidIndependentPayloadDomains() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED"))
      for (String variant : List.of("original", "different", "positive-bounds")) {
        var resource = ownedReceiptRollbackResource();
        var context = seedOwnedRejectedReceiptOwner(state, 8L, false);
        if (variant.equals("different"))
          fixture.update(
              "UPDATE pay_tcc_receipt SET xid=?,user_id=?,order_no=?,amount=?",
              "other-owner-xid",
              8L,
              "other-owner-order",
              new BigDecimal("11.00"));
        if (variant.equals("positive-bounds"))
          fixture.update(
              "UPDATE pay_tcc_receipt SET xid=?,user_id=?,order_no=?,amount=?",
              "bound-owner-xid",
              Long.MAX_VALUE,
              "bound-owner-order",
              new BigDecimal("9999999999999999.99"));
        var before = ownedReceiptRollbackFacts();
        assertTrue(withOwnedReceiptFence(() -> resource.rollback(context)));
        assertEquals(before, ownedReceiptRollbackFacts());
        assertReceiptOwnerQueryOrder(context);
      }
  }

  /** 仅owned注入可存坏值，公开rollback不ACK、不修复owner、不改五表。 */
  private void assertRejectedReceiptOwnerPayload(String mutation, String state) {
    var resource = ownedReceiptRollbackResource();
    var context = seedOwnedRejectedReceiptOwner(state, 8L, false);
    switch (mutation) {
      case "xid-empty" -> fixture.update("UPDATE pay_tcc_receipt SET xid=?", "");
      case "xid-space" -> fixture.update("UPDATE pay_tcc_receipt SET xid=?", "   ");
      case "xid-controls" -> fixture.update("UPDATE pay_tcc_receipt SET xid=?", "\t\n");
      case "user-zero" -> fixture.update("UPDATE pay_tcc_receipt SET user_id=0");
      case "user-negative" -> fixture.update("UPDATE pay_tcc_receipt SET user_id=-1");
      case "order-empty" -> fixture.update("UPDATE pay_tcc_receipt SET order_no=?", "");
      case "order-space" -> fixture.update("UPDATE pay_tcc_receipt SET order_no=?", "   ");
      case "order-controls" -> fixture.update("UPDATE pay_tcc_receipt SET order_no=?", "\t\n");
      case "amount-zero" -> fixture.update("UPDATE pay_tcc_receipt SET amount=0");
      case "amount-negative" -> fixture.update("UPDATE pay_tcc_receipt SET amount=-0.01");
      default -> throw new IllegalArgumentException("unknown owner payload mutation");
    }
    var before = ownedReceiptRollbackFacts();
    boolean accepted = withOwnedReceiptFence(() -> resource.rollback(context));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> org.junit.jupiter.api.Assertions.assertFalse(accepted),
        () -> assertEquals(before, ownedReceiptRollbackFacts()),
        () -> assertReceiptOwnerQueryOrder(context));
  }

  /** 独立read不能把未知状态当作有效receipt事实发布。 */
  @Test
  void publicReceiptReadCannotPublishUnknownState() {
    assertReceiptPublicReadRejected("UNKNOWN", 8L);
  }

  /** 独立read不能发布零分支号的CONFIRMED终态。 */
  @Test
  void publicReceiptReadCannotPublishZeroConfirmedBranch() {
    assertReceiptPublicReadRejected("CONFIRMED", 0L);
  }

  /** 四态的坏分支、缺失分支与未知/别名状态均保守409，无写入或资源调用。 */
  @Test
  void publicReceiptReadRejectsInvalidStateBranchMatrix() {
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")) {
      for (Long branch : List.of(0L, -1L)) assertReceiptPublicReadRejected(state, branch);
      if (!state.equals("CLAIMED")) assertReceiptPublicReadRejected(state, null);
    }
    for (String state : List.of("UNKNOWN", "", "trying", "TRYING "))
      assertReceiptPublicReadRejected(state, 8L);
  }

  /** 四态合法分支、missing404与先于域门的原payNo身份拒绝均保持。 */
  @Test
  void publicReceiptReadKeepsValidEvidenceAndLookupPriority() {
    for (String state : List.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED"))
      for (Long branch : java.util.Arrays.asList(null, 1L, Long.MAX_VALUE)) {
        if (branch == null && !state.equals("CLAIMED")) continue;
        var store = ownedReceiptClaimStore();
        var dispatch = seedOwnedReceiptClaim();
        assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
        fixture.update("UPDATE pay_tcc_receipt SET state=?,branch_id=?", state, branch);
        var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
        var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
        var facade =
            new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, store, resource);
        var before = ownedReceiptClaimFacts();
        var result = facade.read(dispatch.payNo);
        assertEquals(dispatch.payNo, result.payNo);
        assertEquals(dispatch.xid, result.xid);
        assertEquals(state, result.state);
        assertEquals(branch, result.branchId);
        assertEquals(dispatch.operationKey, result.operationKey);
        assertEquals(dispatch.ledgerKey, result.ledgerKey);
        assertEquals(before, ownedReceiptClaimFacts());
        org.mockito.Mockito.verifyNoInteractions(bridge, resource);
      }
    for (String scenario : List.of("missing", "alias-and-invalid")) {
      var store = ownedReceiptClaimStore();
      var dispatch = seedOwnedReceiptClaim();
      assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
      if (scenario.equals("missing")) fixture.update("DELETE FROM pay_tcc_receipt");
      else fixture.update("UPDATE pay_tcc_receipt SET pay_no='PAY-1',state='UNKNOWN',branch_id=0");
      var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
      var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
      var facade =
          new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, store, resource);
      var before = ownedReceiptClaimFacts();
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class, () -> facade.read(dispatch.payNo));
      assertEquals(
          scenario.equals("missing") ? "PAY_TCC_RECEIPT_NOT_FOUND" : "PAY_TCC_RECEIPT_CONFLICT",
          failure.getCode());
      assertEquals(scenario.equals("missing") ? 404 : 409, failure.getHttpStatus());
      if (!scenario.equals("missing")) assertEquals("Pay receipt 原事实不一致", failure.getMessage());
      assertEquals(before, ownedReceiptClaimFacts());
      org.mockito.Mockito.verifyNoInteractions(bridge, resource);
    }
  }

  /** actual Store一次读取，错误不返回VO，源与receipt四表保持注入后的原样。 */
  private void assertReceiptPublicReadRejected(String state, Long branch) {
    var store = ownedReceiptClaimStore();
    var dispatch = seedOwnedReceiptClaim();
    assertTrue(dispatchTransaction(() -> store.claim(dispatch)));
    fixture.update("UPDATE pay_tcc_receipt SET state=?,branch_id=?", state, branch);
    var bridge = mock(com.cloudmall.pay.service.PayTccBridgeService.class);
    var resource = mock(com.cloudmall.pay.service.PayTccReceiptLocalResource.class);
    var facade =
        new com.cloudmall.pay.service.impl.PayTccReceiptServiceImpl(bridge, store, resource);
    var before = ownedReceiptClaimFacts();
    var returned =
        new java.util.concurrent.atomic.AtomicReference<
            com.cloudmall.pay.domain.vo.PayTccReceiptVO>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(facade.read(dispatch.payNo)));
          assertEquals("PAY_TCC_RECEIPT_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () ->
            assertNull(
                returned.get() == null
                    ? null
                    : returned.get().state + "|" + returned.get().branchId),
        () -> assertEquals(before, ownedReceiptClaimFacts()),
        () -> org.mockito.Mockito.verifyNoInteractions(bridge, resource));
  }

  /** public rollback的返回、五表事实和真实owner查询计数同时断言。 */
  private void assertRejectedReceiptOwner(String state, Long branch, boolean otherXid) {
    var resource = ownedReceiptRollbackResource();
    var context = seedOwnedRejectedReceiptOwner(state, branch, otherXid);
    var before = ownedReceiptRollbackFacts();
    boolean accepted = withOwnedReceiptFence(() -> resource.rollback(context));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> org.junit.jupiter.api.Assertions.assertFalse(accepted),
        () -> assertEquals(before, ownedReceiptRollbackFacts()),
        () -> assertReceiptOwnerQueryOrder(context));
  }

  /** 每个计数wrapper都转发真实Mapper；锁和事务仍由同一owned数据源执行。 */
  private com.cloudmall.pay.service.impl.PayTccReceiptLocalResourceImpl
      ownedReceiptRollbackResource() {
    var store = ownedReceiptClaimStore();
    fixture.execute("DROP TABLE IF EXISTS tcc_fence_log");
    fixture.execute(
        (String) source.queryForMap("SHOW CREATE TABLE tcc_fence_log").get("Create Table"));
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccFenceMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      receiptRollbackMapper =
          mock(
              com.cloudmall.pay.mapper.PayTccReceiptMapper.class,
              org.mockito.AdditionalAnswers.delegatesTo(
                  session.getMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class)));
      fenceIdentityMapper =
          mock(
              com.cloudmall.pay.mapper.PayTccFenceMapper.class,
              org.mockito.AdditionalAnswers.delegatesTo(
                  session.getMapper(com.cloudmall.pay.mapper.PayTccFenceMapper.class)));
      return new com.cloudmall.pay.service.impl.PayTccReceiptLocalResourceImpl(
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool),
          new com.cloudmall.pay.service.impl.PayTccReceiptBusinessCallback(store),
          fenceIdentityMapper,
          receiptRollbackMapper);
    } catch (Exception failure) {
      throw new IllegalStateException("owned receipt rollback factory failed", failure);
    }
  }

  /** owner与被拒分支同payNo，原source保持；仅owned注入分支/状态或另一XID。 */
  private io.seata.rm.tcc.api.BusinessActionContext seedOwnedRejectedReceiptOwner(
      String state, Long branch, boolean otherXid) {
    var dispatch = seedOwnedReceiptClaim();
    var candidate = new com.cloudmall.pay.domain.po.PayTccReceiptPO();
    candidate.id = 1L;
    candidate.payNo = dispatch.payNo;
    candidate.xid = otherXid ? "other-owner-xid" : dispatch.xid;
    candidate.userId = dispatch.userId;
    candidate.orderNo = dispatch.orderNo;
    candidate.amount = new BigDecimal(dispatch.amount);
    candidate.operationKey = dispatch.operationKey;
    candidate.ledgerKey = dispatch.ledgerKey;
    candidate.createdAt = LocalDateTime.of(2026, 10, 15, 0, 0);
    candidate.updatedAt = candidate.createdAt;
    assertEquals(1, receiptRollbackMapper.claim(candidate));
    fixture.update("UPDATE pay_tcc_receipt SET state=?,branch_id=?", state, branch);
    var context = new io.seata.rm.tcc.api.BusinessActionContext();
    context.setXid(dispatch.xid);
    context.setBranchId(7L);
    context.setActionName(com.cloudmall.pay.service.PayTccReceiptLocalResource.ACTION_NAME);
    context.setActionContext(Map.of("request", receiptClaimRequest(dispatch)));
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES(?,?,?,4,NOW(3),NOW(3))",
        context.getXid(),
        context.getBranchId(),
        context.getActionName());
    org.mockito.Mockito.clearInvocations(receiptRollbackMapper, fenceIdentityMapper);
    return context;
  }

  /** 读取前后五表全字段，不只比较状态，以核实拒绝没有补写或覆盖owner。 */
  private List<List<Map<String, Object>>> ownedReceiptRollbackFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(ownedReceiptClaimFacts());
    facts.add(fixture.queryForList("SELECT * FROM tcc_fence_log ORDER BY xid,branch_id"));
    return facts;
  }

  /** 首次Fence→receipt读→二次Fence→count0→owner锁，验证原查询门顺序和次数。 */
  private void assertReceiptOwnerQueryOrder(io.seata.rm.tcc.api.BusinessActionContext context) {
    var ordered = org.mockito.Mockito.inOrder(fenceIdentityMapper, receiptRollbackMapper);
    ordered.verify(fenceIdentityMapper).findByBranch(context.getXid(), context.getBranchId());
    ordered.verify(receiptRollbackMapper).selectByPayNo("pay-1");
    ordered.verify(fenceIdentityMapper).findByBranch(context.getXid(), context.getBranchId());
    ordered.verify(receiptRollbackMapper).countByBranch(context.getXid(), context.getBranchId());
    ordered
        .verify(receiptRollbackMapper)
        .findOwners("pay-1", context.getXid(), "tcc-debit:pay-1", "pay-1");
    ordered.verifyNoMoreInteractions();
  }

  /** 官方handler仅绑定owned同步事务；finally恢复static，空队列不触发异步清理写。 */
  private boolean withOwnedReceiptFence(java.util.function.Supplier<Boolean> action) {
    Class<?> handler = io.seata.rm.tcc.TCCFenceHandler.class;
    var previousDataSource =
        (DataSource)
            org.springframework.test.util.ReflectionTestUtils.getField(handler, "dataSource");
    var previousTemplate =
        (org.springframework.transaction.support.TransactionTemplate)
            org.springframework.test.util.ReflectionTestUtils.getField(
                handler, "transactionTemplate");
    var queue =
        (java.util.concurrent.BlockingQueue<?>)
            org.springframework.test.util.ReflectionTestUtils.getField(handler, "LOG_QUEUE");
    assertTrue(queue.isEmpty());
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    io.seata.rm.tcc.TCCFenceHandler.setDataSource(ownedPool);
    io.seata.rm.tcc.TCCFenceHandler.setTransactionTemplate(template);
    try {
      return action.get();
    } finally {
      io.seata.rm.tcc.TCCFenceHandler.setDataSource(previousDataSource);
      io.seata.rm.tcc.TCCFenceHandler.setTransactionTemplate(previousTemplate);
      org.junit.jupiter.api.Assertions.assertSame(
          previousDataSource,
          org.springframework.test.util.ReflectionTestUtils.getField(handler, "dataSource"));
      org.junit.jupiter.api.Assertions.assertSame(
          previousTemplate,
          org.springframework.test.util.ReflectionTestUtils.getField(
              handler, "transactionTemplate"));
      assertTrue(queue.isEmpty());
      org.junit.jupiter.api.Assertions.assertFalse(
          org.springframework.transaction.support.TransactionSynchronizationManager
              .isActualTransactionActive());
    }
  }

  /** 原Pay/Dispatch/Receipt实际Mapper共享owned数据源；cancel gate仅为无取消替身。 */
  private com.cloudmall.pay.service.impl.PayTccReceiptStore ownedReceiptClaimStore() {
    ownedDispatchPersistence();
    fixture.execute("DROP TABLE IF EXISTS pay_tcc_receipt");
    fixture.execute(
        (String) source.queryForMap("SHOW CREATE TABLE pay_tcc_receipt").get("Create Table"));
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      return new com.cloudmall.pay.service.impl.PayTccReceiptStore(
          session.getMapper(com.cloudmall.pay.mapper.PayTccReceiptMapper.class),
          records,
          dispatchMirrorMapper,
          mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class));
    } catch (Exception failure) {
      throw new IllegalStateException("owned receipt claim factory failed", failure);
    }
  }

  /** 只在owned准备精确TCC/PENDING支付和PREPARED锚点，返回源变异前原dispatch视图。 */
  private com.cloudmall.pay.domain.vo.PayTccDispatchVO seedOwnedReceiptClaim() {
    seedOwnedDispatch("PREPARED", null);
    fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='pay-1'");
    fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at=NULL");
    fixture.update("DELETE FROM pay_tcc_receipt");
    return com.cloudmall.pay.domain.vo.PayTccDispatchVO.from(
        dispatchMirrorMapper.selectByPayNo("pay-1"));
  }

  /** 原Pay/Intent/Dispatch/Receipt四表完整facts，冲突和重放不改任意字段。 */
  private List<List<Map<String, Object>>> ownedReceiptClaimFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(ownedDispatchLookupFacts());
    facts.add(fixture.queryForList("SELECT * FROM pay_tcc_receipt ORDER BY id"));
    return facts;
  }

  /** 实际CI查询返回另一原支付号，不能由恢复入口输出该终态。 */
  @Test
  void dispatchAliasCannotBeReturnedByRecovery() {
    var persistence = ownedDispatchPersistence();
    seedOwnedDispatch("CONFIRMED", 7L);
    fixture.update("UPDATE pay_tcc_dispatch SET pay_no='PAY-1' WHERE id=1");
    assertEquals("PAY-1", dispatchMirrorMapper.selectByPayNo("pay-1").payNo);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    var bridge = ownedDispatchBridge(persistence, user);
    var before = ownedDispatchLookupFacts();
    var returned = new java.util.concurrent.atomic.AtomicReference<String>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(bridge.recover("pay-1").payNo));
          assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
        },
        () ->
            assertNull(returned.get(), "alias dispatch must not be returned as requested payment"),
        () -> assertEquals(before, ownedDispatchLookupFacts()),
        () -> org.mockito.Mockito.verifyNoInteractions(user));
  }

  /** 实际锁定CI别名不能写入镜像，原三个owned表完整事实不动。 */
  @Test
  void dispatchAliasCannotBeMirrored() {
    var persistence = ownedDispatchPersistence();
    seedOwnedDispatch("POST_ATTEMPTED", 7L);
    fixture.update("UPDATE pay_tcc_dispatch SET pay_no='PAY-1' WHERE id=1");
    assertEquals("PAY-1", dispatchMirrorMapper.selectByPayNo("pay-1").payNo);
    var before = ownedDispatchLookupFacts();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () ->
                      dispatchTransaction(
                          () ->
                              persistence.mirror(
                                  dispatchMirrorAnchor("pay-1"),
                                  "POST_ATTEMPTED",
                                  "TRY_UNKNOWN",
                                  7L,
                                  "alias write",
                                  LocalDateTime.of(2026, 10, 14, 12, 0))));
          assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
        },
        () -> assertEquals(before, ownedDispatchLookupFacts()));
  }

  /** 真实CI查询双向别名在六种恢复/镜像状态均拒绝，不调用User或改三表facts。 */
  @Test
  void dispatchLookupAliasesRejectAllRecoveryAndMirrorStates() {
    var persistence = ownedDispatchPersistence();
    var updatedAt = LocalDateTime.of(2026, 10, 14, 12, 0);
    for (boolean uppercaseOriginal : List.of(false, true)) {
      String original = uppercaseOriginal ? "PAY-1" : "pay-1";
      String requested = uppercaseOriginal ? "pay-1" : "PAY-1";
      for (String state :
          List.of(
              "CONFIRMED",
              "CANCELLED",
              "PREPARED",
              "POST_ATTEMPTED",
              "TRY_UNKNOWN",
              "TRY_ACCEPTED")) {
        seedOwnedDispatch(state, 7L);
        fixture.update("UPDATE pay_tcc_dispatch SET pay_no=? WHERE id=1", original);
        assertEquals(original, dispatchMirrorMapper.selectByPayNo(requested).payNo);
        var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
        var bridge = ownedDispatchBridge(persistence, user);
        var before = ownedDispatchLookupFacts();
        var recovered =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> bridge.recover(requested),
                state);
        assertEquals("PAY_TCC_DISPATCH_CONFLICT", recovered.getCode());
        assertEquals(before, ownedDispatchLookupFacts());
        var mirrored =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    dispatchTransaction(
                        () ->
                            persistence.mirror(
                                dispatchMirrorAnchor(requested),
                                "stale-state",
                                "TRY_UNKNOWN",
                                7L,
                                "alias mirror",
                                updatedAt)),
                state);
        assertEquals("PAY_TCC_DISPATCH_CONFLICT", mirrored.getCode());
        assertEquals(before, ownedDispatchLookupFacts());
        org.mockito.Mockito.verifyNoInteractions(user);
      }
    }
  }

  /** 精确原键保持终态/PREPARED只读、活动状态GET镜像及缺失404，不新增POST。 */
  @Test
  void exactDispatchLookupKeepsRecoveryMirrorAndMissingSemantics() {
    var persistence = ownedDispatchPersistence();
    for (String state :
        List.of(
            "CONFIRMED",
            "CANCELLED",
            "PREPARED",
            "POST_ATTEMPTED",
            "TRY_UNKNOWN",
            "TRY_ACCEPTED")) {
      seedOwnedDispatch(state, 7L);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      var proof = new com.cloudmall.pay.domain.vo.UserTccStateVO();
      proof.payNo = "pay-1";
      proof.xid = "t171-xid";
      proof.userId = 7L;
      proof.orderNo = "order-1";
      proof.operationKey = "tcc-debit:pay-1";
      proof.ledgerKey = "pay-1";
      proof.amount = "10.00";
      proof.branchId = 7L;
      proof.state = "CONFIRMED";
      proof.setLedgerApplied(true);
      when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(ApiResponse.ok(proof));
      var bridge = ownedDispatchBridge(persistence, user);
      var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      var payBefore = rawIntentTimeFacts();
      var result = bridge.recover("pay-1");
      assertEquals("pay-1", result.payNo);
      var after = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      if (List.of("CONFIRMED", "CANCELLED", "PREPARED").contains(state)) {
        assertEquals(state, result.state);
        assertTrue(
            !dispatchTransaction(
                () ->
                    persistence.mirror(
                        dispatchMirrorAnchor("pay-1"),
                        "stale-state",
                        "TRY_UNKNOWN",
                        7L,
                        "late",
                        LocalDateTime.of(2026, 10, 14, 12, 0))));
        assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1"));
        org.mockito.Mockito.verifyNoInteractions(user);
      } else {
        assertEquals("CONFIRMED", result.state);
        assertEquals("CONFIRMED", after.get("state"));
        assertEquals(7L, result.userBranchId);
        assertNull(result.lastError);
        verify(user)
            .state(7L, "tcc-debit:pay-1", 7L, "cloud-mall-pay", "owned-callback", "t171-xid");
        verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
        for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
          before.remove(changed);
          after.remove(changed);
        }
        assertEquals(before, after);
      }
      assertEquals(payBefore, rawIntentTimeFacts());
    }
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    var bridge = ownedDispatchBridge(persistence, user);
    var before = ownedDispatchLookupFacts();
    var missing =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class, () -> bridge.recover("absent-pay"));
    assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", missing.getCode());
    assertEquals(404, missing.getHttpStatus());
    var missingMirror =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () ->
                dispatchTransaction(
                    () ->
                        persistence.mirror(
                            dispatchMirrorAnchor("absent-pay"),
                            "POST_ATTEMPTED",
                            "TRY_UNKNOWN",
                            7L,
                            "absent",
                            LocalDateTime.of(2026, 10, 14, 12, 0))));
    assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", missingMirror.getCode());
    assertEquals(404, missingMirror.getHttpStatus());
    assertEquals(before, ownedDispatchLookupFacts());
    org.mockito.Mockito.verifyNoInteractions(user);
  }

  /** GET缺ledgerApplied的TRYING不能成为新的受理镜像或学习User branch。 */
  @Test
  void bridgeMissingLedgerGetCannotMirrorAcceptedState() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(false);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("TRYING", "missing")));
    var bridge = bridgeLedgerTransactions(persistence, user);
    var result = bridge.recover("pay-1");
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertNull(row.userBranchId));
  }

  /** POST null布尔的CANCELLED不能成为新的终态；首次POST权限仍须消耗。 */
  @Test
  void bridgeNullLedgerPostCannotMirrorCancelledState() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(true);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.prepare(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("CANCELLED", "null")));
    var result = bridgeLedgerPost(bridgeLedgerTransactions(persistence, user));
    var row = dispatchMirrorMapper.selectByPayNo("pay-1");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("TRY_UNKNOWN", result.state),
        () -> assertEquals("TRY_UNKNOWN", row.state),
        () -> assertNull(row.userBranchId),
        () -> assertNotNull(row.postAttemptedAt));
    verify(user).prepare(any(), any(), any(), any(), any(), any());
  }

  /** 缺字段/null跨GET/POST及TRYING/CANCELLED只镜像未知，不学习新branch或重发。 */
  @Test
  void incompleteBridgeLedgerEvidenceKeepsUnknownAndOnePost() {
    var persistence = ownedDispatchPersistence();
    for (String state : List.of("TRYING", "CANCELLED"))
      for (String variant : List.of("missing", "null"))
        for (boolean post : List.of(false, true)) {
          seedBridgeLedgerRow(post);
          var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
          var response = ApiResponse.ok(bridgeLedgerReply(state, variant));
          when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
          when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
          var bridge = bridgeLedgerTransactions(persistence, user);
          var payBefore = rawIntentTimeFacts();
          var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
          var result = post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1");
          assertEquals("TRY_UNKNOWN", result.state);
          assertNull(result.userBranchId);
          assertEquals("BizException", result.lastError);
          var after = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
          assertEquals("TRY_UNKNOWN", after.get("state"));
          assertNull(after.get("user_branch_id"));
          if (post) assertNotNull(after.get("post_attempted_at"));
          else assertEquals(before.get("post_attempted_at"), after.get("post_attempted_at"));
          for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
            before.remove(changed);
            after.remove(changed);
          }
          if (post) {
            before.remove("post_attempted_at");
            after.remove("post_attempted_at");
          }
          assertEquals(before, after);
          assertEquals(payBefore, rawIntentTimeFacts());
          assertEquals("TRY_UNKNOWN", bridge.recover("pay-1").state);
          assertNull(dispatchMirrorMapper.selectByPayNo("pay-1").userBranchId);
          verify(user, org.mockito.Mockito.times(post ? 1 : 2))
              .state(any(), any(), any(), any(), any(), any());
          verify(user, org.mockito.Mockito.times(post ? 1 : 0))
              .prepare(any(), any(), any(), any(), any(), any());
          assertEquals(payBefore, rawIntentTimeFacts());
        }
  }

  /** 显式布尔的原可信镜像保持，已有受理不降级/UNKNOWN早返/branch冲突不变。 */
  @Test
  void explicitBridgeLedgerEvidenceAndEarlierGuardsRemainUnchanged() {
    var persistence = ownedDispatchPersistence();
    for (String state : List.of("TRYING", "CANCELLED", "CONFIRMED"))
      for (boolean post : List.of(false, true)) {
        seedBridgeLedgerRow(post);
        var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
        var response =
            ApiResponse.ok(bridgeLedgerReply(state, state.equals("CONFIRMED") ? "true" : "false"));
        when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
        when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
        var bridge = bridgeLedgerTransactions(persistence, user);
        var payBefore = rawIntentTimeFacts();
        var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
        var result = post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1");
        assertEquals(state.equals("TRYING") ? "TRY_ACCEPTED" : state, result.state);
        assertEquals(7L, result.userBranchId);
        assertNull(result.lastError);
        var after = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch");
        for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
          before.remove(changed);
          after.remove(changed);
        }
        if (post) {
          assertNotNull(after.get("post_attempted_at"));
          before.remove("post_attempted_at");
          after.remove("post_attempted_at");
        }
        assertEquals(before, after);
        assertEquals(payBefore, rawIntentTimeFacts());
      }
    for (String state : List.of("TRYING", "CANCELLED"))
      for (String variant : List.of("missing", "null")) {
        seedBridgeLedgerRow(false);
        fixture.update("UPDATE pay_tcc_dispatch SET state='TRY_ACCEPTED',user_branch_id=7");
        var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
        when(user.state(any(), any(), any(), any(), any(), any()))
            .thenReturn(ApiResponse.ok(bridgeLedgerReply(state, variant)));
        var before = ownedDispatchLookupFacts();
        assertEquals(
            "TRY_ACCEPTED", bridgeLedgerTransactions(persistence, user).recover("pay-1").state);
        assertEquals(before, ownedDispatchLookupFacts());
        verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
      }
    for (boolean post : List.of(false, true)) {
      seedBridgeLedgerRow(post);
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      var response = ApiResponse.ok(bridgeLedgerReply("UNKNOWN", "missing"));
      when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
      when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
      var bridge = bridgeLedgerTransactions(persistence, user);
      var result = post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1");
      assertEquals("TRY_UNKNOWN", result.state);
      assertNull(result.userBranchId);
      assertEquals(post ? "User POST 状态未知" : "User 状态仍未知", result.lastError);
    }
    seedBridgeLedgerRow(false);
    fixture.update("UPDATE pay_tcc_dispatch SET user_branch_id=7");
    var conflicting = bridgeLedgerReply("TRYING", "missing");
    conflicting.branchId = 9L;
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.state(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(conflicting));
    var before = ownedDispatchLookupFacts();
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.cloudmall.common.error.BizException.class,
            () -> bridgeLedgerTransactions(persistence, user).recover("pay-1"));
    assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
    assertEquals(409, failure.getHttpStatus());
    assertEquals(before, ownedDispatchLookupFacts());
  }

  /** 只在owned准备TCC原支付与派发，POST场景明确尚未消耗权限。 */
  private void seedBridgeLedgerRow(boolean post) {
    seedOwnedDispatch(post ? "PREPARED" : "POST_ATTEMPTED", null);
    fixture.update("UPDATE pay_record SET debit_mode='TCC'");
    fixture.update("DELETE FROM pay_debit_intent");
    if (post) fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at=NULL");
  }

  /** 实际Jackson解析字段存在性，User状态为受控TRYING/CANCELLED/CONFIRMED/UNKNOWN。 */
  private com.cloudmall.pay.domain.vo.UserTccStateVO bridgeLedgerReply(
      String state, String ledgerVariant) {
    var user = new com.cloudmall.pay.domain.vo.UserTccStateVO();
    user.payNo = "pay-1";
    user.userId = 7L;
    user.orderNo = "order-1";
    user.amount = "10.00";
    user.xid = "t171-xid";
    user.operationKey = "tcc-debit:pay-1";
    user.ledgerKey = "pay-1";
    user.branchId = 7L;
    user.state = state;
    user.setLedgerApplied(false);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(user);
    if (ledgerVariant.equals("missing")) json.remove("ledgerApplied");
    else if (ledgerVariant.equals("null")) json.putNull("ledgerApplied");
    else if (ledgerVariant.equals("number-zero")) json.put("ledgerApplied", 0);
    else if (ledgerVariant.equals("text-false")) json.put("ledgerApplied", "false");
    else json.put("ledgerApplied", ledgerVariant.equals("true"));
    mutateUserEvidenceJson(json, ledgerVariant);
    try {
      var decoded = decodeUserEvidenceJson(mapper, json, ledgerVariant);
      if (!ledgerVariant.startsWith("duplicate-")
          && !List.of("number-zero", "text-false").contains(ledgerVariant)) {
        assertEquals(
            ledgerVariant.equals("false")
                || ledgerVariant.equals("true")
                || ledgerVariant.startsWith("branch-")
                || ledgerVariant.startsWith("user-")
                || ledgerVariant.startsWith("amount-"),
            decoded.ledgerAppliedPresent);
      }
      return decoded;
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("owned bridge User decode failed", failure);
    }
  }

  /** 真实Persistence的三个方法各自进入短事务，未运行Spring REQUIRES_NEW代理。 */
  private com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl bridgeLedgerTransactions(
      com.cloudmall.pay.service.impl.PayTccDispatchPersistence actual,
      com.cloudmall.pay.feign.UserTccClient user) {
    return bridgeLedgerTransactions(actual, user, () -> {});
  }

  /** 只在领取短事务开始前调用受控hook，其余prepare/mirror事务边界不变。 */
  private com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl bridgeLedgerTransactions(
      com.cloudmall.pay.service.impl.PayTccDispatchPersistence actual,
      com.cloudmall.pay.feign.UserTccClient user,
      Runnable beforeClaim) {
    return bridgeLedgerTransactions(actual, user, beforeClaim, () -> {}, () -> {});
  }

  /** 后置hook在实际claim/mirror事务提交后、Bridge读回之前执行，其余场景默认noop。 */
  private com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl bridgeLedgerTransactions(
      com.cloudmall.pay.service.impl.PayTccDispatchPersistence actual,
      com.cloudmall.pay.feign.UserTccClient user,
      Runnable beforeClaim,
      Runnable afterClaim,
      Runnable afterMirror) {
    var boundary = mock(com.cloudmall.pay.service.impl.PayTccDispatchPersistence.class);
    var transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    org.mockito.Mockito.doAnswer(
            invocation ->
                transactions.execute(
                    transactionStatus -> actual.prepare(invocation.getArgument(0))))
        .when(boundary)
        .prepare(any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              beforeClaim.run();
              boolean claimed =
                  dispatchTransaction(
                      () ->
                          actual.markPostAttempted(
                              invocation.getArgument(0), invocation.getArgument(1)));
              afterClaim.run();
              return claimed;
            })
        .when(boundary)
        .markPostAttempted(any(), any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              boolean mirrored =
                  dispatchTransaction(
                      () ->
                          actual.mirror(
                              invocation.getArgument(0),
                              invocation.getArgument(1),
                              invocation.getArgument(2),
                              invocation.getArgument(3),
                              invocation.getArgument(4),
                              invocation.getArgument(5)));
              afterMirror.run();
              return mirrored;
            })
        .when(boundary)
        .mirror(any(), any(), any(), any(), any(), any());
    return ownedDispatchBridge(boundary, user);
  }

  /** 公开dispatch重用原prepared锚点，只为一次POST提供原XID上下文并finally还原。 */
  private com.cloudmall.pay.domain.vo.PayTccDispatchVO bridgeLedgerPost(
      com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl bridge) {
    String original = io.seata.core.context.RootContext.unbind();
    io.seata.core.context.RootContext.bind("t171-xid");
    try {
      var request = new com.cloudmall.pay.domain.dto.PayTccDispatchDTO();
      request.payNo = "pay-1";
      request.userId = 7L;
      request.orderNo = "order-1";
      request.amount = BigDecimal.TEN;
      return bridge.dispatch(request);
    } finally {
      io.seata.core.context.RootContext.unbind();
      if (original != null) io.seata.core.context.RootContext.bind(original);
    }
  }

  /** 仅构造真实Bridge逻辑及owned Mapper，User为替身，不启用应用或真实网络。 */
  private com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl ownedDispatchBridge(
      com.cloudmall.pay.service.impl.PayTccDispatchPersistence persistence,
      com.cloudmall.pay.feign.UserTccClient user) {
    var configuration = mock(com.cloudmall.pay.config.PayTccBridgeConfiguration.class);
    when(configuration.isEnabled()).thenReturn(true);
    return new com.cloudmall.pay.service.impl.PayTccBridgeServiceImpl(
        records, dispatchMirrorMapper, persistence, user, configuration, "owned-callback");
  }

  /** 完整原Pay/Intent/Dispatch事实，只对owned克隆读取。 */
  private List<List<Map<String, Object>>> ownedDispatchLookupFacts() {
    var facts = new ArrayList<List<Map<String, Object>>>(rawIntentTimeFacts());
    facts.add(fixture.queryForList("SELECT * FROM pay_tcc_dispatch ORDER BY id"));
    return facts;
  }

  /** public镜像面对长诊断应正常提交512字符前缀，而不是数据库DataTooLong。 */
  @Test
  void dispatchMirrorBoundsLongErrorBeforeRealSql() {
    var store = ownedDispatchPersistence();
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String error : List.of("a".repeat(513), "a".repeat(511) + "😀tail")) {
      assertions.add(
          () -> {
            seedOwnedDispatch("POST_ATTEMPTED", null);
            var updatedAt = LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789000000);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                    assertTrue(
                        dispatchTransaction(
                            () ->
                                store.mirror(
                                    dispatchMirrorAnchor("pay-1"),
                                    "stale-state",
                                    "TRY_UNKNOWN",
                                    7L,
                                    error,
                                    updatedAt))));
            assertEquals(
                error.startsWith("a".repeat(513)) ? "a".repeat(512) : "a".repeat(511) + "😀",
                fixture.queryForObject(
                    "SELECT last_error FROM pay_tcc_dispatch WHERE id=1", String.class));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真实dispatch列接受完整512字符前缀，状态/分支/时间之外所有原载荷保持。 */
  @Test
  void dispatchMirrorPrefixesFitColumnAndKeepFacts() {
    var store = ownedDispatchPersistence();
    String[][] examples = {
      {null, null},
      {"", ""},
      {"a".repeat(511), "a".repeat(511)},
      {"a".repeat(512), "a".repeat(512)},
      {"a".repeat(513), "a".repeat(512)},
      {"界".repeat(513), "界".repeat(512)},
      {"😀".repeat(511), "😀".repeat(511)},
      {"😀".repeat(512), "😀".repeat(512)},
      {"😀".repeat(513), "😀".repeat(512)},
      {"a".repeat(511) + "😀tail", "a".repeat(511) + "😀"},
      {"😀".repeat(256) + "a".repeat(256) + "tail", "😀".repeat(256) + "a".repeat(256)},
      {"错".repeat(255) + "😀".repeat(257) + "tail", "错".repeat(255) + "😀".repeat(257)},
      {"é".repeat(257), "é".repeat(256)}
    };
    var updatedAt = LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789000000);
    for (String[] example : examples) {
      seedOwnedDispatch("POST_ATTEMPTED", null);
      var original = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      var payBefore = rawIntentTimeFacts();
      assertTrue(
          dispatchTransaction(
              () ->
                  store.mirror(
                      dispatchMirrorAnchor("pay-1"),
                      "stale-state",
                      "TRY_UNKNOWN",
                      7L,
                      example[0],
                      updatedAt)));
      var after = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      assertEquals(example[1], after.get("last_error"));
      assertEquals("TRY_UNKNOWN", after.get("state"));
      assertEquals(7L, after.get("user_branch_id"));
      assertEquals(updatedAt, after.get("updated_at"));
      assertEquals(
          example[1] == null ? null : example[1].codePointCount(0, example[1].length()),
          fixture.queryForObject(
              "SELECT CHAR_LENGTH(last_error) FROM pay_tcc_dispatch WHERE id=1", Integer.class));
      assertEquals(
          example[1] == null ? null : example[1].getBytes(StandardCharsets.UTF_8).length,
          fixture.queryForObject(
              "SELECT OCTET_LENGTH(last_error) FROM pay_tcc_dispatch WHERE id=1", Integer.class));
      for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
        original.remove(changed);
        after.remove(changed);
      }
      assertEquals(original, after);
      assertEquals(payBefore, rawIntentTimeFacts());
    }
    seedOwnedDispatch("POST_ATTEMPTED", 7L);
    assertTrue(
        dispatchTransaction(
            () ->
                store.mirror(
                    dispatchMirrorAnchor("pay-1"),
                    "stale-state",
                    "TRY_UNKNOWN",
                    null,
                    null,
                    updatedAt)));
    assertEquals(
        7L,
        fixture.queryForObject(
            "SELECT user_branch_id FROM pay_tcc_dispatch WHERE id=1", Long.class));
    assertNull(
        fixture.queryForObject("SELECT last_error FROM pay_tcc_dispatch WHERE id=1", String.class));
  }

  /** 原终态/分支/受理门不写；真实CAS零行仍抛原错误，短事务回滚整个变化。 */
  @Test
  void dispatchMirrorGuardsAndZeroRowRollbackKeepFacts() {
    var store = ownedDispatchPersistence();
    var updatedAt = LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789000000);
    for (String state : List.of("CONFIRMED", "CANCELLED", "PREPARED", "TRY_ACCEPTED")) {
      seedOwnedDispatch(state, 7L);
      var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      var payBefore = rawIntentTimeFacts();
      assertTrue(
          !dispatchTransaction(
              () ->
                  store.mirror(
                      dispatchMirrorAnchor("pay-1"),
                      "stale-state",
                      "TRY_UNKNOWN",
                      7L,
                      "😀".repeat(513),
                      updatedAt)));
      assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1"));
      assertEquals(payBefore, rawIntentTimeFacts());
    }
    for (Long branch : List.of(0L, -1L, 8L)) {
      seedOwnedDispatch("POST_ATTEMPTED", 7L);
      var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
      org.junit.jupiter.api.Assertions.assertThrows(
          com.cloudmall.common.error.BizException.class,
          () ->
              dispatchTransaction(
                  () ->
                      store.mirror(
                          dispatchMirrorAnchor("pay-1"),
                          "stale-state",
                          "TRY_UNKNOWN",
                          branch,
                          "a".repeat(513),
                          updatedAt)));
      assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1"));
    }
    seedOwnedDispatch("CONFIRMED", 7L);
    var terminal = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
    org.junit.jupiter.api.Assertions.assertThrows(
        com.cloudmall.common.error.BizException.class,
        () ->
            dispatchTransaction(
                () ->
                    store.mirror(
                        dispatchMirrorAnchor("pay-1"),
                        "stale-state",
                        "CANCELLED",
                        7L,
                        "a".repeat(513),
                        updatedAt)));
    assertEquals(terminal, fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1"));
    seedOwnedDispatch("POST_ATTEMPTED", 7L);
    var before = fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1");
    var payBefore = rawIntentTimeFacts();
    var actual = dispatchMirrorMapper;
    var guarded =
        org.mockito.Mockito.mock(
            com.cloudmall.pay.mapper.PayTccDispatchMapper.class,
            org.mockito.AdditionalAnswers.delegatesTo(actual));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              fixture.update("UPDATE pay_tcc_dispatch SET state='TRY_ACCEPTED' WHERE id=1");
              int changed =
                  actual.mirrorState(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      invocation.getArgument(4),
                      invocation.getArgument(5));
              assertEquals(0, changed);
              return changed;
            })
        .when(guarded)
        .mirrorState(any(), any(), any(), any(), any(), any());
    var rejected =
        new com.cloudmall.pay.service.impl.PayTccDispatchPersistence(
            guarded, records, mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class));
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                dispatchTransaction(
                    () ->
                        rejected.mirror(
                            dispatchMirrorAnchor("pay-1"),
                            "stale-state",
                            "TRY_UNKNOWN",
                            7L,
                            "😀".repeat(513),
                            updatedAt)));
    assertEquals("TCC 派发镜像状态未更新", failure.getMessage());
    assertEquals(before, fixture.queryForMap("SELECT * FROM pay_tcc_dispatch WHERE id=1"));
    assertEquals(payBefore, rawIntentTimeFacts());
  }

  /** GET返回前原xid变化，旧响应不能写到新全局事务载荷。 */
  @Test
  void changedDispatchXidCannotConsumeOldGetProof() {
    assertDispatchMirrorDrift(false, "UPDATE pay_tcc_dispatch SET xid='changed-xid'", false);
  }

  /** POST已消耗后原operation变化，旧受理响应不能写到另一操作键。 */
  @Test
  void changedDispatchOperationCannotConsumeOldPostProof() {
    assertDispatchMirrorDrift(
        true, "UPDATE pay_tcc_dispatch SET operation_key='changed-operation'", false);
  }

  /** 八个原锚点字段在GET/POST窗口漂移，可信响应与空响应均不可写新镜像。 */
  @Test
  void dispatchMirrorAnchorChangesBlockTrustedAndUnknownResponses() {
    var mutations =
        List.of(
            "UPDATE pay_tcc_dispatch SET id=2",
            "UPDATE pay_tcc_dispatch SET pay_no='PAY-1'",
            "UPDATE pay_tcc_dispatch SET xid='changed-xid'",
            "UPDATE pay_tcc_dispatch SET user_id=8",
            "UPDATE pay_tcc_dispatch SET order_no='changed-order'",
            "UPDATE pay_tcc_dispatch SET amount=11.00",
            "UPDATE pay_tcc_dispatch SET operation_key='changed-operation'",
            "UPDATE pay_tcc_dispatch SET ledger_key='changed-ledger'");
    for (String mutation : mutations)
      for (boolean post : List.of(false, true))
        for (boolean empty : List.of(false, true)) assertDispatchMirrorDrift(post, mutation, empty);
  }

  /** 远端读取期间仅状态/诊断/时间推进允许原镜像，未知不降级既有受理。 */
  @Test
  void dispatchMirrorKeepsMutableProgressAndOnePostPermission() {
    for (boolean post : List.of(false, true))
      for (boolean empty : List.of(false, true)) {
        var persistence = ownedDispatchPersistence();
        seedBridgeLedgerRow(post);
        var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
        var afterProgress =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        org.mockito.stubbing.Answer<ApiResponse<com.cloudmall.pay.domain.vo.UserTccStateVO>>
            response =
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  fixture.update(
                      "UPDATE pay_tcc_dispatch SET"
                          + " state='TRY_ACCEPTED',user_branch_id=7,last_error='concurrent-progress',created_at='2026-10-01"
                          + " 00:00:00.123',updated_at='2026-10-02 00:00:00.123'");
                  afterProgress.set(ownedDispatchLookupFacts());
                  return empty
                      ? null
                      : ApiResponse.ok(bridgeLedgerReply(post ? "TRYING" : "CANCELLED", "false"));
                };
        if (post) when(user.prepare(any(), any(), any(), any(), any(), any())).thenAnswer(response);
        else when(user.state(any(), any(), any(), any(), any(), any())).thenAnswer(response);
        var bridge = bridgeLedgerTransactions(persistence, user);
        var result = post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1");
        assertEquals(empty || post ? "TRY_ACCEPTED" : "CANCELLED", result.state);
        assertEquals(7L, result.userBranchId);
        var after = ownedDispatchLookupFacts();
        if (empty) assertEquals(afterProgress.get(), after);
        else {
          assertNull(result.lastError);
          assertEquals(afterProgress.get().subList(0, 2), after.subList(0, 2));
          var beforeDispatch = new java.util.HashMap<>(afterProgress.get().get(2).get(0));
          var afterDispatch = new java.util.HashMap<>(after.get(2).get(0));
          for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
            beforeDispatch.remove(changed);
            afterDispatch.remove(changed);
          }
          assertEquals(beforeDispatch, afterDispatch);
        }
        verify(user, org.mockito.Mockito.times(post ? 1 : 0))
            .prepare(any(), any(), any(), any(), any(), any());
        verify(user, org.mockito.Mockito.times(post ? 0 : 1))
            .state(any(), any(), any(), any(), any(), any());
      }
  }

  /** 受控User RPC窗口只改owned载荷，镜像不得在注入变更之后再改任何三表事实。 */
  private void assertDispatchMirrorDrift(boolean post, String mutationSql, boolean emptyResponse) {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(post);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    var afterDrift =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    org.mockito.stubbing.Answer<ApiResponse<com.cloudmall.pay.domain.vo.UserTccStateVO>> response =
        invocation -> {
          org.junit.jupiter.api.Assertions.assertFalse(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          fixture.update(mutationSql);
          afterDrift.set(ownedDispatchLookupFacts());
          return emptyResponse
              ? null
              : ApiResponse.ok(bridgeLedgerReply(post ? "TRYING" : "CANCELLED", "false"));
        };
    if (post) when(user.prepare(any(), any(), any(), any(), any(), any())).thenAnswer(response);
    else when(user.state(any(), any(), any(), any(), any(), any())).thenAnswer(response);
    var bridge = bridgeLedgerTransactions(persistence, user);
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> {
                    if (post) bridgeLedgerPost(bridge);
                    else bridge.recover("pay-1");
                  });
          assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () -> {
          org.junit.jupiter.api.Assertions.assertNotNull(afterDrift.get());
          assertEquals(afterDrift.get(), ownedDispatchLookupFacts());
        });
    verify(user, org.mockito.Mockito.times(post ? 1 : 0))
        .prepare(any(), any(), any(), any(), any(), any());
    verify(user, org.mockito.Mockito.times(post ? 0 : 1))
        .state(any(), any(), any(), any(), any(), any());
  }

  /** 原xid在领取前变化时，晚到镜像409仍不能弥补已经发出的POST。 */
  @Test
  void changedDispatchBeforeClaimCannotIssueOldUserPost() {
    assertDispatchPostClaimDrift("UPDATE pay_tcc_dispatch SET xid='changed-before-claim'");
  }

  /** PREPARED上已存在POST时间，不能重领并重新发送User Try。 */
  @Test
  void preparedDispatchWithConsumedMarkerCannotPostAgain() {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(true);
    fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at='2026-10-15 00:00:00.123'");
    var before = ownedDispatchLookupFacts();
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.prepare(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("TRYING", "false")));
    var result = bridgeLedgerPost(bridgeLedgerTransactions(persistence, user));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("PREPARED", result.state),
        () -> org.mockito.Mockito.verifyNoInteractions(user),
        () -> assertEquals(before, ownedDispatchLookupFacts()));
  }

  /** 八字段在领取前改变时均不消费POST权限，不等待晚到镜像门才拒绝。 */
  @Test
  void changedPostClaimFieldsKeepPermissionAndSuppressAllUserRpc() {
    for (String mutation :
        List.of(
            "UPDATE pay_tcc_dispatch SET id=2",
            "UPDATE pay_tcc_dispatch SET pay_no='PAY-1'",
            "UPDATE pay_tcc_dispatch SET xid='changed-before-claim'",
            "UPDATE pay_tcc_dispatch SET user_id=8",
            "UPDATE pay_tcc_dispatch SET order_no='changed-order'",
            "UPDATE pay_tcc_dispatch SET amount=11.00",
            "UPDATE pay_tcc_dispatch SET operation_key='changed-operation'",
            "UPDATE pay_tcc_dispatch SET ledger_key='changed-ledger'"))
      assertDispatchPostClaimDrift(mutation);
  }

  /** 五种当前状态与marker双矩阵仅允许PREPARED/空marker发一次POST。 */
  @Test
  void postClaimStateAndMarkerMatrixKeepsSinglePostPermission() {
    for (String state :
        List.of("PREPARED", "POST_ATTEMPTED", "TRY_ACCEPTED", "CANCELLED", "CONFIRMED"))
      for (boolean marked : List.of(false, true)) {
        var persistence = ownedDispatchPersistence();
        seedBridgeLedgerRow(true);
        var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
        var response = ApiResponse.ok(bridgeLedgerReply("TRYING", "false"));
        when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
        when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
        var afterHook =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        var bridge =
            bridgeLedgerTransactions(
                persistence,
                user,
                () -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  fixture.update(
                      "UPDATE pay_tcc_dispatch SET state=?,post_attempted_at=?",
                      state,
                      marked ? LocalDateTime.of(2026, 10, 15, 0, 0, 0, 123000000) : null);
                  afterHook.set(ownedDispatchLookupFacts());
                });
        var result = bridgeLedgerPost(bridge);
        boolean permitted = state.equals("PREPARED") && !marked;
        if (!permitted) {
          assertEquals(state, result.state);
          org.mockito.Mockito.verifyNoInteractions(user);
          assertEquals(afterHook.get(), ownedDispatchLookupFacts());
        } else {
          assertEquals("TRY_ACCEPTED", result.state);
          var afterFirst = ownedDispatchLookupFacts();
          org.junit.jupiter.api.Assertions.assertNotNull(
              afterFirst.get(2).get(0).get("post_attempted_at"));
          assertEquals("TRY_ACCEPTED", bridgeLedgerPost(bridge).state);
          verify(user).prepare(any(), any(), any(), any(), any(), any());
          verify(user).state(any(), any(), any(), any(), any(), any());
          var afterSecond = ownedDispatchLookupFacts();
          assertEquals(afterHook.get().subList(0, 2), afterSecond.subList(0, 2));
          var firstDispatch = new java.util.HashMap<>(afterFirst.get(2).get(0));
          var secondDispatch = new java.util.HashMap<>(afterSecond.get(2).get(0));
          for (String changed : List.of("state", "user_branch_id", "last_error", "updated_at")) {
            firstDispatch.remove(changed);
            secondDispatch.remove(changed);
          }
          assertEquals(firstDispatch, secondDispatch);
        }
      }
  }

  /** hook在原snapshot读取后、实际claim短事务前注入owned变化，核无权限写也无User RPC。 */
  private void assertDispatchPostClaimDrift(String mutationSql) {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(true);
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    when(user.prepare(any(), any(), any(), any(), any(), any()))
        .thenReturn(ApiResponse.ok(bridgeLedgerReply("TRYING", "false")));
    var afterDrift =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    var bridge =
        bridgeLedgerTransactions(
            persistence,
            user,
            () -> {
              org.junit.jupiter.api.Assertions.assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              fixture.update(mutationSql);
              afterDrift.set(ownedDispatchLookupFacts());
            });
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class, () -> bridgeLedgerPost(bridge));
          assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () -> org.mockito.Mockito.verifyNoInteractions(user),
        () -> {
          org.junit.jupiter.api.Assertions.assertNotNull(afterDrift.get());
          assertEquals(afterDrift.get(), ownedDispatchLookupFacts());
        });
  }

  /** mirror已经提交后出现原键CI别名，不能把别名行作为本次恢复结果返回。 */
  @Test
  void mirrorReadbackAliasCannotBeReturnedAsOriginalDispatch() {
    assertDispatchReadbackDrift(false, false, false, "UPDATE pay_tcc_dispatch SET pay_no='PAY-1'");
  }

  /** claim false后原xid改变，不能直接返回另一全局事务载荷。 */
  @Test
  void failedClaimReadbackCannotReturnChangedXid() {
    assertDispatchReadbackDrift(
        true, true, false, "UPDATE pay_tcc_dispatch SET xid='changed-after-failed-claim'");
  }

  /** 八字段覆盖两种mirror响应与两个入口，以及无User调用的claim false读回。 */
  @Test
  void readbackAnchorChangesCannotReturnForeignPayloads() {
    for (String mutation :
        List.of(
            "UPDATE pay_tcc_dispatch SET id=2",
            "UPDATE pay_tcc_dispatch SET pay_no='PAY-1'",
            "UPDATE pay_tcc_dispatch SET xid='changed-readback-xid'",
            "UPDATE pay_tcc_dispatch SET user_id=8",
            "UPDATE pay_tcc_dispatch SET order_no='changed-order'",
            "UPDATE pay_tcc_dispatch SET amount=11.00",
            "UPDATE pay_tcc_dispatch SET operation_key='changed-operation'",
            "UPDATE pay_tcc_dispatch SET ledger_key='changed-ledger'")) {
      for (boolean post : List.of(false, true))
        for (boolean empty : List.of(false, true))
          assertDispatchReadbackDrift(post, false, empty, mutation);
      assertDispatchReadbackDrift(true, true, false, mutation);
    }
  }

  /** mutable进展仍返回最新视图，缺行保原404回退，读回不产生额外数据库写。 */
  @Test
  void readbackKeepsMutableProgressAndMissingRowFallback() {
    for (int scenario = 0; scenario < 3; scenario++) {
      boolean post = scenario != 0;
      boolean failedClaim = scenario == 2;
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(post);
      if (failedClaim)
        fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at='2026-10-15 00:00:00.123'");
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      var response = ApiResponse.ok(bridgeLedgerReply("TRYING", "false"));
      when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
      when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
      var afterHook =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      String progressedState = failedClaim ? "TRY_ACCEPTED" : "CONFIRMED";
      Runnable progress =
          () -> {
            org.junit.jupiter.api.Assertions.assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            fixture.update(
                "UPDATE pay_tcc_dispatch SET"
                    + " state=?,user_branch_id=7,last_error='newer-progress',updated_at='2026-10-15"
                    + " 02:00:00.123'",
                progressedState);
            afterHook.set(ownedDispatchLookupFacts());
          };
      var bridge =
          bridgeLedgerTransactions(
              persistence,
              user,
              () -> {},
              failedClaim ? progress : () -> {},
              failedClaim ? () -> {} : progress);
      var result = post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1");
      assertEquals(progressedState, result.state);
      assertEquals(7L, result.userBranchId);
      assertEquals("newer-progress", result.lastError);
      assertEquals(afterHook.get(), ownedDispatchLookupFacts());
      verify(user, org.mockito.Mockito.times(post && !failedClaim ? 1 : 0))
          .prepare(any(), any(), any(), any(), any(), any());
      verify(user, org.mockito.Mockito.times(!post ? 1 : 0))
          .state(any(), any(), any(), any(), any(), any());
    }
    for (boolean failedClaim : List.of(false, true)) {
      var persistence = ownedDispatchPersistence();
      seedBridgeLedgerRow(failedClaim);
      if (failedClaim)
        fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at='2026-10-15 00:00:00.123'");
      var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
      when(user.state(any(), any(), any(), any(), any(), any()))
          .thenReturn(ApiResponse.ok(bridgeLedgerReply("TRYING", "false")));
      var afterHook =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      Runnable remove =
          () -> {
            org.junit.jupiter.api.Assertions.assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            fixture.update("DELETE FROM pay_tcc_dispatch");
            afterHook.set(ownedDispatchLookupFacts());
          };
      var bridge =
          bridgeLedgerTransactions(
              persistence,
              user,
              () -> {},
              failedClaim ? remove : () -> {},
              failedClaim ? () -> {} : remove);
      var failure =
          org.junit.jupiter.api.Assertions.assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> {
                if (failedClaim) bridgeLedgerPost(bridge);
                else bridge.recover("pay-1");
              });
      assertEquals("PAY_TCC_DISPATCH_NOT_FOUND", failure.getCode());
      assertEquals(404, failure.getHttpStatus());
      assertEquals(afterHook.get(), ownedDispatchLookupFacts());
      verify(user, never()).prepare(any(), any(), any(), any(), any(), any());
      verify(user, org.mockito.Mockito.times(failedClaim ? 0 : 1))
          .state(any(), any(), any(), any(), any(), any());
    }
  }

  /** 在真实短事务结束后的读回窗口注入owned变化，核原结果拒绝且无后续写/RPC。 */
  private void assertDispatchReadbackDrift(
      boolean post, boolean failedClaim, boolean emptyResponse, String mutationSql) {
    var persistence = ownedDispatchPersistence();
    seedBridgeLedgerRow(post);
    if (failedClaim)
      fixture.update("UPDATE pay_tcc_dispatch SET post_attempted_at='2026-10-15 00:00:00.123'");
    var user = mock(com.cloudmall.pay.feign.UserTccClient.class);
    ApiResponse<com.cloudmall.pay.domain.vo.UserTccStateVO> response =
        emptyResponse
            ? null
            : ApiResponse.ok(bridgeLedgerReply(post ? "TRYING" : "CANCELLED", "false"));
    when(user.prepare(any(), any(), any(), any(), any(), any())).thenReturn(response);
    when(user.state(any(), any(), any(), any(), any(), any())).thenReturn(response);
    var afterHook =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    Runnable mutation =
        () -> {
          org.junit.jupiter.api.Assertions.assertFalse(
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .isActualTransactionActive());
          fixture.update(mutationSql);
          afterHook.set(ownedDispatchLookupFacts());
        };
    var bridge =
        bridgeLedgerTransactions(
            persistence,
            user,
            () -> {},
            failedClaim ? mutation : () -> {},
            failedClaim ? () -> {} : mutation);
    var returned =
        new java.util.concurrent.atomic.AtomicReference<
            com.cloudmall.pay.domain.vo.PayTccDispatchVO>();
    org.junit.jupiter.api.Assertions.assertAll(
        () -> {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> returned.set(post ? bridgeLedgerPost(bridge) : bridge.recover("pay-1")));
          assertEquals("PAY_TCC_DISPATCH_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
        },
        () ->
            assertNull(
                returned.get() == null ? null : returned.get().payNo + "|" + returned.get().xid));
    org.junit.jupiter.api.Assertions.assertNotNull(afterHook.get());
    assertEquals(afterHook.get(), ownedDispatchLookupFacts());
    if (failedClaim) org.mockito.Mockito.verifyNoInteractions(user);
    else {
      verify(user, org.mockito.Mockito.times(post ? 1 : 0))
          .prepare(any(), any(), any(), any(), any(), any());
      verify(user, org.mockito.Mockito.times(post ? 0 : 1))
          .state(any(), any(), any(), any(), any(), any());
    }
  }

  /** 旧直接Store测试读取真实PO，但保留请求原键以继续验证CI别名；Bridge不使用此helper。 */
  private com.cloudmall.pay.domain.po.PayTccDispatchPO dispatchMirrorAnchor(String requestedPayNo) {
    var expected = dispatchMirrorMapper.selectByPayNo(requestedPayNo);
    if (expected == null) expected = new com.cloudmall.pay.domain.po.PayTccDispatchPO();
    expected.payNo = requestedPayNo;
    return expected;
  }

  /** 仅在owned库克隆原dispatch DDL，以原真实MyBatis Mapper执行public镜像逻辑。 */
  private com.cloudmall.pay.service.impl.PayTccDispatchPersistence ownedDispatchPersistence() {
    fixture.execute("DROP TABLE IF EXISTS pay_tcc_dispatch");
    String definition =
        (String) source.queryForMap("SHOW CREATE TABLE pay_tcc_dispatch").get("Create Table");
    fixture.execute(definition);
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(com.cloudmall.pay.mapper.PayTccDispatchMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(ownedPool);
    factory.setConfiguration(configuration);
    try {
      var session = new SqlSessionTemplate(factory.getObject());
      dispatchMirrorMapper = session.getMapper(com.cloudmall.pay.mapper.PayTccDispatchMapper.class);
      return new com.cloudmall.pay.service.impl.PayTccDispatchPersistence(
          dispatchMirrorMapper, records, mock(com.cloudmall.pay.mapper.PayTccCancelMapper.class));
    } catch (Exception failure) {
      throw new IllegalStateException("owned dispatch factory failed", failure);
    }
  }

  /** 包装同owned数据源的短事务，实际FOR UPDATE与CAS在同一连接内完成。 */
  private boolean dispatchTransaction(java.util.function.Supplier<Boolean> action) {
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownedPool));
    return Boolean.TRUE.equals(template.execute(transactionStatus -> action.get()));
  }

  /** 只在owned表生成唯一派发事实，保固定操作键、分支及原创建时间。 */
  private void seedOwnedDispatch(String state, Long branchId) {
    seedIntentAndPayment("PENDING", "INIT");
    fixture.update("DELETE FROM pay_tcc_dispatch");
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,post_attempted_at,user_branch_id,last_error,created_at,updated_at)"
            + " VALUES(1,'pay-1','t171-xid',7,'order-1',10,'tcc-debit:pay-1','pay-1',?,?,?,'owned-original-error',?,?)",
        state,
        LocalDateTime.of(2026, 10, 1, 0, 0),
        branchId,
        LocalDateTime.of(2026, 10, 1, 0, 0),
        LocalDateTime.of(2026, 10, 1, 0, 0));
  }

  /** 两种实际重试写入应保留512码点边界上的完整补充字符，不能存为替换字符。 */
  @Test
  void retryErrorDoesNotSplitSupplementaryCharacter() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    String expected = "a".repeat(511) + "😀";
    String error = expected + "tail";
    for (boolean owned : List.of(false, true)) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var currentTime =
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            assertEquals(
                1, intents.claim("pay-1", "t169-owner", currentTime.plusSeconds(30), currentTime));
            int changed =
                owned
                    ? intents.recordRetryOwnedLegacy(
                        "pay-1",
                        "t169-owner",
                        "INIT",
                        error,
                        currentTime.plusSeconds(60),
                        currentTime)
                    : intents.recordRetry(
                        "pay-1",
                        "t169-owner",
                        "INIT",
                        error,
                        currentTime.plusSeconds(60),
                        currentTime);
            assertEquals(1, changed);
            assertEquals(
                expected,
                fixture.queryForObject(
                    "SELECT last_error FROM pay_debit_intent WHERE id=1", String.class));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 实际VARCHAR按字符数保存前缀，NULL/BMP/补充字符/组合码点及原重试事实保持。 */
  @Test
  void retryErrorPrefixesRespectDatabaseCharacterCapacity() {
    String[][] examples = {
      {null, null},
      {"", ""},
      {"a".repeat(511), "a".repeat(511)},
      {"a".repeat(512), "a".repeat(512)},
      {"a".repeat(513), "a".repeat(512)},
      {"界".repeat(513), "界".repeat(512)},
      {"😀".repeat(511), "😀".repeat(511)},
      {"😀".repeat(512), "😀".repeat(512)},
      {"😀".repeat(513), "😀".repeat(512)},
      {"a".repeat(511) + "😀tail", "a".repeat(511) + "😀"},
      {"😀".repeat(256) + "a".repeat(256) + "tail", "😀".repeat(256) + "a".repeat(256)},
      {"错".repeat(255) + "😀".repeat(257) + "tail", "错".repeat(255) + "😀".repeat(257)},
      {"é".repeat(257), "é".repeat(256)}
    };
    for (boolean owned : List.of(false, true)) {
      for (String target : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        for (String[] example : examples) {
          seedIntentAndPayment(
              target.equals("INIT") ? "PENDING" : target,
              target.equals("ORDER_SYNC_UNKNOWN") ? "DEBITED" : target);
          fixture.update(
              "UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
          var currentTime =
              LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                  .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
          assertEquals(
              1, intents.claim("pay-1", "t169-owner", currentTime.plusSeconds(30), currentTime));
          var before = rawIntentTimeFacts();
          int changed =
              owned
                  ? intents.recordRetryOwnedLegacy(
                      "pay-1",
                      "t169-owner",
                      target,
                      example[0],
                      currentTime.plusSeconds(60),
                      currentTime)
                  : intents.recordRetry(
                      "pay-1",
                      "t169-owner",
                      target,
                      example[0],
                      currentTime.plusSeconds(60),
                      currentTime);
          assertEquals(1, changed);
          String stored =
              fixture.queryForObject(
                  "SELECT last_error FROM pay_debit_intent WHERE id=1", String.class);
          assertEquals(example[1], stored);
          assertEquals(
              example[1] == null ? null : example[1].codePointCount(0, example[1].length()),
              fixture.queryForObject(
                  "SELECT CHAR_LENGTH(last_error) FROM pay_debit_intent WHERE id=1",
                  Integer.class));
          assertEquals(
              example[1] == null ? null : example[1].getBytes(StandardCharsets.UTF_8).length,
              fixture.queryForObject(
                  "SELECT OCTET_LENGTH(last_error) FROM pay_debit_intent WHERE id=1",
                  Integer.class));
          if (stored != null)
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                    StandardCharsets.UTF_8
                        .newEncoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(stored)));
          assertEquals(target, intentStatus());
          assertNull(intentLeaseOwner());
          assertNull(intents.findByPayNo("pay-1").leaseUntil);
          assertEquals(currentTime.plusSeconds(60), intentNextRetry());
          assertEquals(1, intentAttempts());
          assertEquals(before.get(0), rawIntentTimeFacts().get(0));
          assertEquals(
              immutableRecoveryFacts(before), immutableRecoveryFacts(rawIntentTimeFacts()));
        }
      }
    }
  }

  /** 真实process保存完整诊断并按旧退避释放租约，拒绝资格不能被Unicode绑定改变。 */
  @Test
  void unicodeRetryErrorsKeepProcessAndRejectionRules() {
    seedIntentAndPayment("PENDING", "INIT");
    var before = rawIntentTimeFacts();
    String exceptionPrefix = IllegalStateException.class.getName() + ": ";
    String retainedMessage = "m".repeat(511 - exceptionPrefix.length()) + "😀";
    var failure = new IllegalStateException(retainedMessage + "tail");
    String expected = exceptionPrefix + retainedMessage;
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(eq(7L), anyString(), eq(7L))).thenThrow(failure);
    var service = newService(user, orders);
    var started =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    assertEquals("INIT", service.process("pay-1"));
    var finished =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    assertEquals(
        expected,
        fixture.queryForObject("SELECT last_error FROM pay_debit_intent WHERE id=1", String.class));
    assertNull(intentLeaseOwner());
    assertNull(intents.findByPayNo("pay-1").leaseUntil);
    assertEquals(1, intentAttempts());
    assertEquals("PENDING", paymentStatus());
    assertEquals("INIT", intentStatus());
    assertTrue(!intentNextRetry().isBefore(started.plusSeconds(5)));
    assertTrue(!intentNextRetry().isAfter(finished.plusSeconds(5)));
    assertTrue(intentNextRetry().isAfter(finished));
    assertEquals(before.get(0), rawIntentTimeFacts().get(0));
    assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(rawIntentTimeFacts()));
    var after = rawIntentTimeFacts();
    service.recoverDue();
    assertEquals(after, rawIntentTimeFacts());
    verify(user, times(1)).operation(eq(7L), anyString(), eq(7L));
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(orders);
    for (String rejected :
        List.of("other-owner", "expired", "record-mode", "intent-mode", "refund-direction")) {
      seedIntentAndPayment("PENDING", "INIT");
      var currentTime =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
              .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      assertEquals(
          1, intents.claim("pay-1", "t169-owner", currentTime.plusSeconds(30), currentTime));
      fixture.update("UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
      if (rejected.equals("expired"))
        fixture.update("UPDATE pay_debit_intent SET lease_until=? WHERE id=1", currentTime);
      if (rejected.equals("record-mode"))
        fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE id=1");
      if (rejected.equals("intent-mode"))
        fixture.update("UPDATE pay_debit_intent SET debit_mode='TCC' WHERE id=1");
      if (rejected.equals("refund-direction"))
        fixture.update("UPDATE pay_debit_intent SET status='REFUND_PENDING' WHERE id=1");
      var rejectedFacts = rawIntentTimeFacts();
      String token = rejected.equals("other-owner") ? "t169-other" : "t169-owner";
      assertEquals(
          0,
          intents.recordRetryOwnedLegacy(
              "pay-1", token, "INIT", "😀".repeat(513), currentTime.plusSeconds(60), currentTime));
      assertEquals(rejectedFacts, rawIntentTimeFacts());
      if (rejected.equals("other-owner")) {
        assertEquals(
            0,
            intents.recordRetry(
                "pay-1",
                token,
                "INIT",
                "😀".repeat(513),
                currentTime.plusSeconds(60),
                currentTime));
        assertEquals(rejectedFacts, rawIntentTimeFacts());
      }
    }
  }

  /** 合法切换日期在四读取入口必须保留数据库日期组件，而非Timestamp混合历失败。 */
  @Test
  void cutoverTimesReadThroughLookupAndCandidates() {
    String expectedTimezone = System.getProperty("cloudmall.pay.mysql.test.expected-timezone");
    String actualTimezone = java.util.TimeZone.getDefault().getID();
    if (expectedTimezone != null) assertEquals(expectedTimezone, actualTimezone);
    System.out.println("CM16T168 test timezone=" + actualTimezone);
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String field : List.of("next_retry_at", "created_at", "updated_at", "lease_until")) {
      for (String value :
          List.of(
              "1582-10-05 12:34:56.789", "1582-10-10 12:34:56.789", "1582-10-14 12:34:56.789")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              writeOwnedHistoricalTime(field, value, 1L);
              var before = rawIntentTimeFacts();
              var currentTime = LocalDateTime.of(2101, 1, 1, 0, 0);
              org.junit.jupiter.api.Assertions.assertAll(
                  field + ":" + value,
                  () ->
                      assertExactIntentFacts(
                          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                              () -> intents.findByPayNo("pay-1")),
                          before.get(1).get(0)),
                  () ->
                      assertExactIntentFacts(
                          org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                              () -> intents.findByPayNoForUpdate("pay-1")),
                          before.get(1).get(0)),
                  () -> {
                    var candidates =
                        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                            () -> intents.findDue(currentTime, 1));
                    assertEquals(1, candidates.size());
                    assertExactIntentFacts(candidates.get(0), before.get(1).get(0));
                  },
                  () -> {
                    var candidates =
                        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                            () -> intents.findDueAfter(currentTime, null, 1));
                    assertEquals(1, candidates.size());
                    assertExactIntentFacts(candidates.get(0), before.get(1).get(0));
                  },
                  () -> assertEquals(before, rawIntentTimeFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 年份边界、切换前后和现代毫秒日期保持，NULL投影及原due资格不新增授权。 */
  @Test
  void typedTimesKeepBoundaryComponentsAndNulls() {
    var currentTime = LocalDateTime.of(2101, 1, 1, 0, 0);
    for (String value :
        List.of(
            "1000-01-01 00:00:00.000",
            "1582-10-04 12:34:56.789",
            "1582-10-05 12:34:56.789",
            "1582-10-10 12:34:56.789",
            "1582-10-14 12:34:56.789",
            "1582-10-15 12:34:56.789",
            "2026-10-14 12:34:56.789",
            "9999-12-31 23:59:59.999")) {
      for (boolean leasePresent : List.of(false, true)) {
        seedIntentAndPayment("PENDING", "INIT");
        fixture.update(
            "UPDATE pay_debit_intent SET"
                + " lease_owner='owned-typed-time',last_error='owned-typed-proof',attempts=7 WHERE"
                + " id=1");
        for (String field : List.of("next_retry_at", "created_at", "updated_at"))
          writeOwnedHistoricalTime(field, value, 1L);
        if (leasePresent) writeOwnedHistoricalTime("lease_until", value, 1L);
        var before = rawIntentTimeFacts();
        var expected = before.get(1).get(0);
        assertExactIntentFacts(intents.findByPayNo("pay-1"), expected);
        assertExactIntentFacts(intents.findByPayNoForUpdate("pay-1"), expected);
        boolean due = storedIntentTime(value).compareTo(currentTime) <= 0;
        var timeCandidates = intents.findDue(currentTime, 1);
        var cursorCandidates = intents.findDueAfter(currentTime, null, 1);
        assertEquals(due ? 1 : 0, timeCandidates.size());
        assertEquals(due ? 1 : 0, cursorCandidates.size());
        if (due) {
          assertExactIntentFacts(timeCandidates.get(0), expected);
          assertExactIntentFacts(cursorCandidates.get(0), expected);
        }
        var projected = projectedIntentMapper("times");
        var missing = new java.util.LinkedHashMap<>(expected);
        for (String field : List.of("next_retry_at", "created_at", "updated_at"))
          missing.put(field, null);
        assertExactIntentFacts(projected.findByPayNo("pay-1"), missing);
        assertExactIntentFacts(projected.findByPayNoForUpdate("pay-1"), missing);
        assertNull(intents.findByPayNo("typed-missing"));
        assertNull(intents.findByPayNoForUpdate("typed-missing"));
        assertEquals(before, rawIntentTimeFacts());
      }
    }
  }

  /** 合法历史头不能被过滤或阻断整批，三轮最多50项原预算恢复全部103条记录。 */
  @Test
  void validHistoricalHeadAndHealthyTailRecoverAcrossTicks() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var fields = List.of("next_retry_at", "created_at", "updated_at", "lease_until");
    var historicalTimes =
        List.of("1582-10-05 12:34:56.789", "1582-10-10 12:34:56.789", "1582-10-14 12:34:56.789");
    for (long identifier = 1; identifier <= 103; identifier++) {
      String payNo = "t168-pay-" + identifier;
      String orderNo = "t168-order-" + identifier;
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'PENDING','LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,?,?)",
          identifier,
          payNo,
          orderNo,
          currentTime.minusDays(1),
          currentTime,
          currentTime);
      if (identifier <= 100) {
        int variant = (int) (identifier - 1);
        writeOwnedHistoricalTime(
            fields.get(variant % 4), historicalTimes.get((variant / 4) % 3), identifier);
      }
    }
    var before = rawIntentTimeFacts();
    assertEquals(50, intents.findDue(currentTime, 50).size());
    assertEquals(
        java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList(),
        intents.findDueAfter(currentTime, null, 50).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(before, rawIntentTimeFacts());
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(eq(7L), anyString(), eq(7L)))
        .thenThrow(new IllegalStateException("owned-typed-time-read-fault"));
    var service = newService(user, orders);
    for (int tick = 0; tick < 3; tick++) {
      service.recoverDue();
      int processed = Math.min((tick + 1) * 50, 103);
      assertEquals(
          processed,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM pay_debit_intent WHERE attempts=1 AND lease_owner IS NULL"
                  + " AND last_error LIKE '%owned-typed-time-read-fault%'",
              Integer.class));
      verify(user, times(processed)).operation(eq(7L), anyString(), eq(7L));
    }
    var after = rawIntentTimeFacts();
    assertEquals(before.get(0), after.get(0));
    assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(after));
    service.recoverDue();
    verify(user, times(103)).operation(eq(7L), anyString(), eq(7L));
    assertEquals(after, rawIntentTimeFacts());
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(orders);
  }

  /** 日期期望来自owned原存储CHAR事实，逐项比对全部14字段，不依赖Timestamp基线。 */
  private static void assertExactIntentFacts(
      com.cloudmall.pay.domain.po.PayDebitIntentPO observed, Map<String, Object> facts) {
    assertNotNull(observed);
    assertEquals(
        java.util.Arrays.asList(
            facts.get("id"),
            facts.get("pay_no"),
            facts.get("order_no"),
            facts.get("user_id"),
            facts.get("amount"),
            facts.get("status"),
            facts.get("debit_mode"),
            facts.get("attempts"),
            storedIntentTime(facts.get("next_retry_at")),
            facts.get("lease_owner"),
            storedIntentTime(facts.get("lease_until")),
            facts.get("last_error"),
            storedIntentTime(facts.get("created_at")),
            storedIntentTime(facts.get("updated_at"))),
        java.util.Arrays.asList(
            observed.id,
            observed.payNo,
            observed.orderNo,
            observed.userId,
            observed.amount,
            observed.status,
            observed.debitMode,
            observed.attempts,
            observed.nextRetryAt,
            observed.leaseOwner,
            observed.leaseUntil,
            observed.lastError,
            observed.createdAt,
            observed.updatedAt));
  }

  /** 已知合法DATETIME(3)的CAST CHAR文本按本批期望日期解析，NULL保缺失。 */
  private static LocalDateTime storedIntentTime(Object text) {
    return text == null ? null : LocalDateTime.parse(((String) text).replace(' ', 'T'));
  }

  /** 非零但超出当月日数的历史时间不得进入扫描或在领取时被覆盖修写。 */
  @Test
  void invalidMonthDaysDoNotScanOrClaim() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String field : List.of("next_retry_at", "created_at", "updated_at", "lease_until")) {
      for (String value :
          List.of(
              "2026-02-29 00:00:00.000",
              "2026-02-30 00:00:00.000",
              "2026-02-31 00:00:00.000",
              "2026-04-31 00:00:00.000",
              "2026-06-31 00:00:00.000",
              "2026-09-31 00:00:00.000",
              "2026-11-31 00:00:00.000",
              "1900-02-29 00:00:00.000",
              "2100-02-29 00:00:00.000")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              writeOwnedHistoricalTimeInMode(field, value, 1L, "ALLOW_INVALID_DATES");
              var before = rawIntentTimeFacts();
              // 包含2100非闰年样本，固定更晚的due参考时间，避免未来时间条件掩盖月日缺口。
              var currentTime = LocalDateTime.of(2101, 1, 1, 0, 0);
              org.junit.jupiter.api.Assertions.assertAll(
                  field + ":" + value,
                  () ->
                      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                          () -> assertTrue(intents.findDue(currentTime, 1).isEmpty())),
                  () ->
                      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                          () -> assertTrue(intents.findDueAfter(currentTime, null, 1).isEmpty())),
                  () ->
                      assertEquals(
                          0,
                          intents.claim(
                              "pay-1",
                              "t167-invalid-token",
                              currentTime.plusSeconds(30),
                              currentTime)),
                  () -> assertEquals(before, rawIntentTimeFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 100个超出当月日数的坏头先过滤，健康尾仍按原顺序扫描并实际领取恢复。 */
  @Test
  void invalidMonthDayHeadCannotBlockHealthyRecovery() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var fields = List.of("next_retry_at", "created_at", "updated_at", "lease_until");
    var invalidTimes =
        List.of(
            "2026-02-29 00:00:00.000",
            "2026-02-30 00:00:00.000",
            "2026-02-31 00:00:00.000",
            "2026-04-31 00:00:00.000",
            "2026-06-31 00:00:00.000",
            "2026-09-31 00:00:00.000",
            "1900-02-29 00:00:00.000",
            "1000-02-29 00:00:00.000");
    for (long identifier = 1; identifier <= 103; identifier++) {
      String payNo = "t167-pay-" + identifier;
      String orderNo = "t167-order-" + identifier;
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'PENDING','LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          currentTime,
          currentTime);
      var due = currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,'owned-month-day-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          due,
          currentTime,
          currentTime);
      if (identifier <= 100) {
        int variant = (int) (identifier - 1);
        writeOwnedHistoricalTimeInMode(
            fields.get(variant % 4),
            invalidTimes.get((variant / 4) % invalidTimes.size()),
            identifier,
            "ALLOW_INVALID_DATES");
      }
    }
    var before = rawIntentTimeFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 0).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L, 102L, 103L),
        intents.findDueAfter(currentTime, null, 50).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, rawIntentTimeFacts());
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(eq(7L), anyString(), eq(7L)))
        .thenThrow(new IllegalStateException("owned-month-day-user-read-fault"));
    var service = newService(user, orders);
    service.recoverDue();
    assertEquals(
        3,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id>100 AND attempts=1 AND lease_owner IS"
                + " NULL AND last_error LIKE '%owned-month-day-user-read-fault%'",
            Integer.class));
    verify(user, times(3)).operation(eq(7L), anyString(), eq(7L));
    assertEquals(before.get(0), rawIntentTimeFacts().get(0));
    assertEquals(before.get(1).subList(0, 100), rawIntentTimeFacts().get(1).subList(0, 100));
    service.recoverDue();
    verify(user, times(3)).operation(eq(7L), anyString(), eq(7L));
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(orders);
    assertEquals(before.get(1).subList(0, 100), rawIntentTimeFacts().get(1).subList(0, 100));
  }

  /** 合法闰日、世纪非闰月末、30/31天月末和年份边界保原due及nullable租约资格。 */
  @Test
  void validMonthEndsPreserveRecoveryAndClaimRules() {
    var currentTime = LocalDateTime.of(2101, 1, 1, 0, 0);
    for (String field : List.of("next_retry_at", "created_at", "updated_at", "lease_until")) {
      for (String value :
          List.of(
              "1000-02-28 00:00:00.000",
              "1900-02-28 00:00:00.000",
              "2000-02-29 12:34:56.789",
              "2024-02-29 12:34:56.789",
              "2100-02-28 00:00:00.000",
              "2026-04-30 00:00:00.000",
              "2026-01-31 00:00:00.000",
              "9999-12-31 23:59:59.999")) {
        seedIntentAndPayment("PENDING", "INIT");
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=?,last_error='owned-month-day-proof' WHERE"
                + " id=1",
            currentTime);
        writeOwnedHistoricalTimeInMode(field, value, 1L, "ALLOW_INVALID_DATES");
        var before = rawIntentTimeFacts();
        boolean eligible =
            !(value.startsWith("9999")
                && (field.equals("next_retry_at") || field.equals("lease_until")));
        assertEquals(eligible ? 1 : 0, intents.findDue(currentTime, 1).size(), field + ":" + value);
        assertEquals(
            eligible ? 1 : 0,
            intents.findDueAfter(currentTime, null, 1).size(),
            field + ":" + value);
        assertEquals(before, rawIntentTimeFacts());
        assertEquals(
            eligible ? 1 : 0,
            intents.claim(
                "pay-1", "t167-month-end-token", currentTime.plusSeconds(30), currentTime),
            field + ":" + value);
        if (eligible) {
          assertEquals(1, intentAttempts());
          assertEquals("t167-month-end-token", intentLeaseOwner());
          assertEquals(before.get(0), rawIntentTimeFacts().get(0));
          var expected = new java.util.LinkedHashMap<>(before.get(1).get(0));
          var claimed = new java.util.LinkedHashMap<>(rawIntentTimeFacts().get(1).get(0));
          for (String changed : List.of("lease_owner", "lease_until", "attempts", "updated_at")) {
            expected.remove(changed);
            claimed.remove(changed);
          }
          assertEquals(expected, claimed);
        } else assertEquals(before, rawIntentTimeFacts());
      }
    }
  }

  /** 原schema历史零时间不能进入实际JDBC扫描，也不能领取并顺带改坏metadata。 */
  @Test
  void zeroCalendarTimesDoNotScanOrClaim() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String field : List.of("next_retry_at", "created_at", "updated_at", "lease_until")) {
      for (String value :
          List.of(
              "0000-00-00 00:00:00.000",
              "0000-01-01 00:00:00.000",
              "2026-00-15 00:00:00.000",
              "2026-01-00 00:00:00.000")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              writeOwnedHistoricalTime(field, value, 1L);
              var before = rawIntentTimeFacts();
              var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                  () -> assertTrue(intents.findDue(currentTime, 1).isEmpty()), field + ":" + value);
              org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                  () -> assertTrue(intents.findDueAfter(currentTime, null, 1).isEmpty()),
                  field + ":" + value);
              assertEquals(
                  0,
                  intents.claim(
                      "pay-1", "t166-zero-token", currentTime.plusSeconds(30), currentTime));
              assertEquals(before, rawIntentTimeFacts());
              assertEquals(0, intentAttempts());
              assertNull(intentLeaseOwner());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 100个零日期坏头先过滤，正常尾项按原时间/主键扫描并能进入实际恢复轮转。 */
  @Test
  void zeroCalendarHeadCannotBlockHealthyRecovery() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var fields = List.of("next_retry_at", "created_at", "updated_at", "lease_until");
    var badTimes =
        List.of(
            "0000-00-00 00:00:00.000",
            "0000-01-01 00:00:00.000",
            "2026-00-15 00:00:00.000",
            "2026-01-00 00:00:00.000");
    for (long identifier = 1; identifier <= 103; identifier++) {
      String payNo = "t166-pay-" + identifier;
      String orderNo = "t166-order-" + identifier;
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'PENDING','LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          currentTime,
          currentTime);
      var due = currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,'owned-calendar-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          due,
          currentTime,
          currentTime);
      if (identifier <= 100) {
        int variant = (int) (identifier - 1);
        writeOwnedHistoricalTime(
            fields.get(variant % 4), badTimes.get((variant / 4) % 4), identifier);
      }
    }
    var before = rawIntentTimeFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 0).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, rawIntentTimeFacts());
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(eq(7L), anyString(), eq(7L)))
        .thenThrow(new IllegalStateException("owned-calendar-user-read-fault"));
    var service = newService(user, orders);
    service.recoverDue();
    assertEquals(
        3,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id>100 AND attempts=1"
                + " AND lease_owner IS NULL AND last_error LIKE '%owned-calendar-user-read-fault%'",
            Integer.class));
    verify(user, times(3)).operation(eq(7L), anyString(), eq(7L));
    assertEquals(before.get(0), rawIntentTimeFacts().get(0));
    assertEquals(before.get(1).subList(0, 100), rawIntentTimeFacts().get(1).subList(0, 100));
    service.recoverDue();
    verify(user, times(3)).operation(eq(7L), anyString(), eq(7L));
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(orders);
    assertEquals(before.get(1).subList(0, 100), rawIntentTimeFacts().get(1).subList(0, 100));
  }

  /** 非零日期含1000/9999边界保持，NULL/到期租约可领取，未来重试或租约仍拒绝。 */
  @Test
  void nonzeroCalendarBoundariesPreserveDueAndLeaseRules() {
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    for (String historicalTime :
        List.of("1000-01-01 00:00:00.000", "2026-01-15 12:34:56.789", "9999-12-31 23:59:59.999")) {
      for (String lease : List.of("NULL", "EXPIRED", "EQUAL", "FUTURE", "ZERO_YEAR")) {
        seedIntentAndPayment("PENDING", "INIT");
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=?,last_error='owned-calendar-proof' WHERE"
                + " id=1",
            currentTime);
        writeOwnedHistoricalTime("created_at", historicalTime, 1L);
        writeOwnedHistoricalTime("updated_at", historicalTime, 1L);
        if (lease.equals("EXPIRED"))
          writeOwnedHistoricalTime("lease_until", "1000-01-01 00:00:00.000", 1L);
        if (lease.equals("EQUAL"))
          fixture.update("UPDATE pay_debit_intent SET lease_until=? WHERE id=1", currentTime);
        if (lease.equals("FUTURE"))
          writeOwnedHistoricalTime("lease_until", "9999-12-31 23:59:59.999", 1L);
        // 非零月日仍不能接受1000年下界之外的历史年；仅owned SESSION允许模拟。
        if (lease.equals("ZERO_YEAR"))
          writeOwnedHistoricalTime("lease_until", "0999-01-01 00:00:00.000", 1L);
        var before = rawIntentTimeFacts();
        boolean eligible = !lease.equals("FUTURE") && !lease.equals("ZERO_YEAR");
        assertEquals(eligible ? 1 : 0, intents.findDue(currentTime, 1).size());
        assertEquals(eligible ? 1 : 0, intents.findDueAfter(currentTime, null, 1).size());
        assertEquals(before, rawIntentTimeFacts());
        assertEquals(
            eligible ? 1 : 0,
            intents.claim("pay-1", "t166-healthy-token", currentTime.plusSeconds(30), currentTime));
        if (eligible) {
          assertEquals(1, intentAttempts());
          assertEquals("t166-healthy-token", intentLeaseOwner());
          assertEquals(before.get(0), rawIntentTimeFacts().get(0));
          var claimed = new java.util.LinkedHashMap<>(rawIntentTimeFacts().get(1).get(0));
          var expected = new java.util.LinkedHashMap<>(before.get(1).get(0));
          for (String changed : List.of("lease_owner", "lease_until", "attempts", "updated_at")) {
            claimed.remove(changed);
            expected.remove(changed);
          }
          assertEquals(expected, claimed);
        } else assertEquals(before, rawIntentTimeFacts());
      }
    }
    seedIntentAndPayment("PENDING", "INIT");
    writeOwnedHistoricalTime("next_retry_at", "9999-12-31 23:59:59.999", 1L);
    var before = rawIntentTimeFacts();
    assertTrue(intents.findDue(currentTime, 1).isEmpty());
    assertTrue(intents.findDueAfter(currentTime, null, 1).isEmpty());
    assertEquals(
        0, intents.claim("pay-1", "t166-future-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(before, rawIntentTimeFacts());
  }

  /** 仅借用本类owned连接模拟历史写入，SESSION模式finally恢复，禁止全局/原业务库。 */
  private void writeOwnedHistoricalTime(String field, String value, long identifier) {
    writeOwnedHistoricalTimeInMode(field, value, identifier, "");
  }

  /** 固定owned历史fixture模式临时作用于借用连接，退出时恢复原SESSION配置。 */
  private void writeOwnedHistoricalTimeInMode(
      String field, String value, long identifier, String fixtureMode) {
    assertTrue(List.of("", "ALLOW_INVALID_DATES").contains(fixtureMode));
    assertTrue(List.of("next_retry_at", "created_at", "updated_at", "lease_until").contains(field));
    try (var connection = ownedPool.getConnection();
        var statement = connection.createStatement()) {
      assertEquals(database, connection.getCatalog());
      String originalMode;
      try (var result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
        assertTrue(result.next());
        originalMode = result.getString(1);
      }
      try (var configure = connection.prepareStatement("SET SESSION sql_mode=?")) {
        configure.setString(1, fixtureMode);
        configure.execute();
      }
      try (var update =
          connection.prepareStatement("UPDATE pay_debit_intent SET " + field + "=? WHERE id=?")) {
        update.setString(1, value);
        update.setLong(2, identifier);
        assertEquals(1, update.executeUpdate());
        try (var stored =
            connection.prepareStatement(
                "SELECT CAST(" + field + " AS CHAR) FROM pay_debit_intent WHERE id=?")) {
          stored.setLong(1, identifier);
          try (var result = stored.executeQuery()) {
            assertTrue(result.next());
            assertEquals(value, result.getString(1));
          }
        }
      } finally {
        try (var restore = connection.prepareStatement("SET SESSION sql_mode=?")) {
          restore.setString(1, originalMode);
          restore.execute();
        }
        try (var restored = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
          assertTrue(restored.next());
          assertEquals(originalMode, restored.getString(1));
        }
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("owned historical datetime fixture failed", failure);
    }
  }

  /** 对owned意图四时间强制CHAR取原事实，避免检查坏日期时被JDBC转换中断。 */
  private List<List<Map<String, Object>>> rawIntentTimeFacts() {
    return List.of(
        fixture.queryForList("SELECT * FROM pay_record ORDER BY id"),
        fixture.queryForList(
            "SELECT id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,CAST(next_retry_at"
                + " AS CHAR) AS next_retry_at,lease_owner,CAST(lease_until AS CHAR) AS"
                + " lease_until,last_error,CAST(created_at AS CHAR) AS created_at,CAST(updated_at"
                + " AS CHAR) AS updated_at FROM pay_debit_intent ORDER BY id"));
  }

  /** SQL NULL时间必须读为NULL，而不是NPE或用当前时间替代真实缺失。 */
  @Test
  void intentTimeMappingPreservesSqlNullWithoutFailure() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String missing : List.of("next_retry_at", "created_at", "updated_at", "times")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var before = payFacts();
            var expected = intents.findByPayNo("pay-1");
            var projected = projectedIntentMapper(missing);
            var observed =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> projected.findByPayNo("pay-1"), missing);
            assertNotNull(observed);
            assertEquals(
                List.of("next_retry_at", "times").contains(missing) ? null : expected.nextRetryAt,
                observed.nextRetryAt);
            assertEquals(
                List.of("created_at", "times").contains(missing) ? null : expected.createdAt,
                observed.createdAt);
            assertEquals(
                List.of("updated_at", "times").contains(missing) ? null : expected.updatedAt,
                observed.updatedAt);
            assertEquals(1L, observed.id);
            assertEquals(7L, observed.userId);
            assertEquals(0, observed.attempts);
            assertEquals(new BigDecimal("10.00"), observed.amount);
            assertEquals(before, payFacts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** NULL时间经普通/锁定查找都保真实缺失，其余11字段及原存储行完整保持。 */
  @Test
  void nullTimeProjectionKeepsOtherFieldsAndLookupVariants() {
    seedIntentAndPayment("PENDING", "INIT");
    fixture.update(
        "UPDATE pay_debit_intent SET"
            + " attempts=7,lease_owner='owned-time-observation',lease_until=NOW(3)+INTERVAL 10"
            + " MINUTE,last_error='owned-existing-proof' WHERE id=1");
    var before = payFacts();
    var expected = intents.findByPayNo("pay-1");
    for (String missing : List.of("next_retry_at", "created_at", "updated_at", "times")) {
      var projected = projectedIntentMapper(missing);
      for (var observed :
          List.of(projected.findByPayNo("pay-1"), projected.findByPayNoForUpdate("pay-1"))) {
        assertEquals(
            List.of("next_retry_at", "times").contains(missing) ? null : expected.nextRetryAt,
            observed.nextRetryAt);
        assertEquals(
            List.of("created_at", "times").contains(missing) ? null : expected.createdAt,
            observed.createdAt);
        assertEquals(
            List.of("updated_at", "times").contains(missing) ? null : expected.updatedAt,
            observed.updatedAt);
        assertEquals(expected.id, observed.id);
        assertEquals(expected.userId, observed.userId);
        assertEquals(expected.attempts, observed.attempts);
        assertEquals(expected.payNo, observed.payNo);
        assertEquals(expected.orderNo, observed.orderNo);
        assertEquals(expected.amount, observed.amount);
        assertEquals(expected.status, observed.status);
        assertEquals(expected.debitMode, observed.debitMode);
        assertEquals(expected.leaseOwner, observed.leaseOwner);
        assertEquals(expected.leaseUntil, observed.leaseUntil);
        assertEquals(expected.lastError, observed.lastError);
      }
      assertNull(projected.findByPayNo("owned-missing-pay"));
      assertNull(projected.findByPayNoForUpdate("owned-missing-pay"));
      assertEquals(before, payFacts());
    }
    var stored = intents.findByPayNo("pay-1");
    assertSameNonBoxedIntentFacts(expected, stored);
    assertEquals(before, payFacts());
  }

  /** 非NULL日期保原存储组件，避免沿用随测试JVM时区漂移的Timestamp基线。 */
  @Test
  void nonNullTimeMappingPreservesStoredDateComponents() {
    var dates =
        List.of(
            LocalDateTime.of(1000, 1, 1, 0, 0),
            LocalDateTime.of(2026, 10, 14, 12, 34, 56, 789000000),
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999000000));
    for (var date : dates) {
      for (boolean leasePresent : List.of(false, true)) {
        seedIntentAndPayment("PENDING", "INIT");
        fixture.update(
            "UPDATE pay_debit_intent SET"
                + " next_retry_at=?,created_at=?,updated_at=?,lease_owner=?,lease_until=?,last_error='owned-existing-proof'"
                + " WHERE id=1",
            date,
            date,
            date,
            leasePresent ? "owned-original-time" : null,
            leasePresent ? date : null);
        var before = payFacts();
        for (var observed :
            List.of(intents.findByPayNo("pay-1"), intents.findByPayNoForUpdate("pay-1"))) {
          assertEquals(date, observed.nextRetryAt);
          assertEquals(date, observed.createdAt);
          assertEquals(date, observed.updatedAt);
          assertEquals(leasePresent ? date : null, observed.leaseUntil);
          assertExactIntentFacts(observed, rawIntentTimeFacts().get(1).get(0));
          assertEquals(date.getNano(), observed.nextRetryAt.getNano());
          assertEquals(leasePresent ? "owned-original-time" : null, observed.leaseOwner);
          assertEquals(1L, observed.id);
          assertEquals(7L, observed.userId);
          assertEquals(0, observed.attempts);
          assertEquals(new BigDecimal("10.00"), observed.amount);
          assertEquals("owned-existing-proof", observed.lastError);
        }
        assertEquals(before, payFacts());
      }
    }
  }

  /** 实际JDBC NULL投影应保持三个boxed缺失值，不能伪造成0，读取不改存储事实。 */
  @Test
  void boxedIntentMappingPreservesSqlNullFacts() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String missing : List.of("id", "user_id", "attempts", "all")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var before = payFacts();
            var projected = projectedIntentMapper(missing);
            var observed = projected.findByPayNo("pay-1");
            assertNotNull(observed);
            if (List.of("id", "all").contains(missing)) assertNull(observed.id, missing + ":id");
            else assertEquals(1L, observed.id);
            if (List.of("user_id", "all").contains(missing))
              assertNull(observed.userId, missing + ":user");
            else assertEquals(7L, observed.userId);
            if (List.of("attempts", "all").contains(missing))
              assertNull(observed.attempts, missing + ":attempts");
            else assertEquals(0, observed.attempts);
            assertEquals("pay-1", observed.payNo);
            assertEquals("order-1", observed.orderNo);
            assertEquals(new BigDecimal("10.00"), observed.amount);
            assertEquals(before, payFacts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 只替换固定查找SQL的SELECT字段投影，走生产ROW_MAPPER/真实driver，不ALTER schema。 */
  private PayDebitIntentMapper projectedIntentMapper(String missing) {
    assertTrue(
        List.of(
                "id",
                "user_id",
                "attempts",
                "all",
                "next_retry_at",
                "created_at",
                "updated_at",
                "times")
            .contains(missing));
    var columns =
        List.of(
            "id",
            "pay_no",
            "order_no",
            "user_id",
            "amount",
            "status",
            "debit_mode",
            "attempts",
            "next_retry_at",
            "lease_owner",
            "lease_until",
            "last_error",
            "created_at",
            "updated_at");
    String projection =
        columns.stream()
            .map(
                column ->
                    (column.equals(missing)
                            || ("all".equals(missing)
                                && List.of("id", "user_id", "attempts").contains(column))
                            || ("times".equals(missing)
                                && List.of("next_retry_at", "created_at", "updated_at")
                                    .contains(column)))
                        ? "NULL AS " + column
                        : column)
            .collect(java.util.stream.Collectors.joining(","));
    var projectedFixture =
        new JdbcTemplate(fixture.getDataSource()) {
          /** 实际读取本测试owned行，仅固定非锁定/锁定查找可使用NULL投影。 */
          @Override
          public <RowType> List<RowType> query(
              String queryText,
              org.springframework.jdbc.core.RowMapper<RowType> rowMapper,
              Object... bindings) {
            assertTrue(
                List.of(
                        "SELECT * FROM pay_debit_intent WHERE pay_no=?",
                        "SELECT * FROM pay_debit_intent WHERE pay_no=? FOR UPDATE")
                    .contains(queryText));
            String suffix = queryText.endsWith(" FOR UPDATE") ? " FOR UPDATE" : "";
            return super.query(
                "SELECT " + projection + " FROM pay_debit_intent WHERE pay_no=?" + suffix,
                rowMapper,
                bindings);
          }
        };
    return new PayDebitIntentMapper(projectedFixture);
  }

  /** 非NULL真实Long/Integer值含0/负值/边界均原样，不因boxed读取调整数值或写入。 */
  @Test
  void boxedIntentMappingKeepsNumericBoundariesAndReadOnlyFacts() {
    for (long numeric : List.of(Long.MIN_VALUE, 0L, 1L, 7L, Long.MAX_VALUE)) {
      seedIntentAndPayment("PENDING", "INIT");
      int attempts =
          numeric == Long.MIN_VALUE
              ? Integer.MIN_VALUE
              : numeric == Long.MAX_VALUE ? Integer.MAX_VALUE : (int) numeric;
      fixture.update(
          "UPDATE pay_debit_intent SET id=?,user_id=?,attempts=?,last_error='owned-existing-proof'"
              + " WHERE pay_no='pay-1'",
          numeric,
          numeric,
          attempts);
      var before = payFacts();
      var observed = intents.findByPayNo("pay-1");
      var locked = intents.findByPayNoForUpdate("pay-1");
      for (var result : List.of(observed, locked)) {
        assertEquals(numeric, result.id);
        assertEquals(numeric, result.userId);
        assertEquals(attempts, result.attempts);
        assertEquals("pay-1", result.payNo);
        assertEquals("order-1", result.orderNo);
        assertEquals(new BigDecimal("10.00"), result.amount);
        assertEquals("owned-existing-proof", result.lastError);
      }
      assertEquals(before, payFacts());
      assertNull(intents.findByPayNo("owned-missing-pay"));
      assertNull(intents.findByPayNoForUpdate("owned-missing-pay"));
      assertEquals(before, payFacts());
    }
  }

  /** 两查找使用同一生产ROW_MAPPER，NULL与其余完整字段/日期/金额保持，schema未迁移。 */
  @Test
  void boxedNullProjectionKeepsOtherFieldsAndLookupVariants() {
    seedIntentAndPayment("PENDING", "INIT");
    fixture.update(
        "UPDATE pay_debit_intent SET"
            + " attempts=7,lease_owner='owned-observation-only',lease_until=NOW(3)+INTERVAL 10"
            + " MINUTE,last_error='owned-existing-proof' WHERE id=1");
    var before = payFacts();
    var expected = intents.findByPayNo("pay-1");
    for (String missing : List.of("id", "user_id", "attempts", "all")) {
      var projected = projectedIntentMapper(missing);
      for (var result :
          List.of(projected.findByPayNo("pay-1"), projected.findByPayNoForUpdate("pay-1"))) {
        assertEquals(List.of("id", "all").contains(missing) ? null : expected.id, result.id);
        assertEquals(
            List.of("user_id", "all").contains(missing) ? null : expected.userId, result.userId);
        assertEquals(
            List.of("attempts", "all").contains(missing) ? null : expected.attempts,
            result.attempts);
        assertSameNonBoxedIntentFacts(expected, result);
      }
      assertNull(projected.findByPayNo("owned-missing-pay"));
      assertNull(projected.findByPayNoForUpdate("owned-missing-pay"));
      assertEquals(before, payFacts());
    }
    var stored = intents.findByPayNo("pay-1");
    assertEquals(1L, stored.id);
    assertEquals(7L, stored.userId);
    assertEquals(7, stored.attempts);
    assertSameNonBoxedIntentFacts(expected, stored);
    assertEquals(before, payFacts());
  }

  /** 只比较本批不改的字段，包含NULL租约/错误和原日期/金额数值语义。 */
  private static void assertSameNonBoxedIntentFacts(
      com.cloudmall.pay.domain.po.PayDebitIntentPO expected,
      com.cloudmall.pay.domain.po.PayDebitIntentPO observed) {
    assertEquals(expected.payNo, observed.payNo);
    assertEquals(expected.orderNo, observed.orderNo);
    assertEquals(expected.amount, observed.amount);
    assertEquals(expected.status, observed.status);
    assertEquals(expected.debitMode, observed.debitMode);
    assertEquals(expected.nextRetryAt, observed.nextRetryAt);
    assertEquals(expected.leaseOwner, observed.leaseOwner);
    assertEquals(expected.leaseUntil, observed.leaseUntil);
    assertEquals(expected.lastError, observed.lastError);
    assertEquals(expected.createdAt, observed.createdAt);
    assertEquals(expected.updatedAt, observed.updatedAt);
  }

  /** 负恢复计数不能被领取时递增修写，原坏事实和metadata须保持。 */
  @Test
  void negativeAttemptsCannotClaimRecovery() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (int attempts : List.of(-1, -7, Integer.MIN_VALUE)) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            fixture.update(
                "UPDATE pay_debit_intent SET attempts=?,last_error='owned-existing-proof' WHERE"
                    + " id=1",
                attempts);
            var before = payFacts();
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(
                0,
                intents.claim(
                    "pay-1", "t163-negative-token", currentTime.plusSeconds(30), currentTime),
                "attempts=" + attempts);
            assertEquals(before, payFacts());
            assertEquals(attempts, intentAttempts());
            assertNull(intentLeaseOwner());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 实际process拒绝负计数，包含预route后计数变化，无任何远端调用或metadata写。 */
  @Test
  void negativeAttemptEligibilityPreservesServiceFactsAndPreClaimDrift() {
    for (int attempts : List.of(-1, -7, Integer.MIN_VALUE)) {
      for (String paymentStage : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        for (String intentStage :
            List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
          seedIntentAndPayment(paymentStage, intentStage);
          fixture.update(
              "UPDATE pay_debit_intent SET attempts=?,last_error='owned-existing-proof' WHERE id=1",
              attempts);
          var before = payFacts();
          UserClient user = mock(UserClient.class);
          OrderClient orders = mock(OrderClient.class);
          assertEquals(intentStage, newService(user, orders).process("pay-1"));
          assertEquals(before, payFacts());
          assertEquals(attempts, intentAttempts());
          assertNull(intentLeaseOwner());
          org.mockito.Mockito.verifyNoInteractions(user, orders);
        }
      }
      seedIntentAndPayment("PENDING", "INIT");
      fixture.update("UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                fixture.update("UPDATE pay_debit_intent SET attempts=? WHERE id=1", attempts);
                afterChange.set(payFacts());
                assertEquals(0, invocation.callRealMethod());
                return 0;
              })
          .when(guardedIntents)
          .claim(eq("pay-1"), anyString(), any(), any());
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      intents = guardedIntents;
      try {
        assertEquals("INIT", newService(user, orders).process("pay-1"));
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals(attempts, intentAttempts());
        assertNull(intentLeaseOwner());
        org.mockito.Mockito.verifyNoInteractions(user, orders);
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 100负计数头先过滤，合法0/7/MAX尾按原顺序扫描，MAX仍能饱和领取。 */
  @Test
  void recoveryScanFiltersNegativeAttemptsBeforeLimit() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var negatives = List.of(-1, -7, Integer.MIN_VALUE);
    for (long identifier = 1; identifier <= 103; identifier++) {
      boolean negative = identifier <= 100;
      int attempts =
          negative
              ? negatives.get((int) ((identifier - 1) % negatives.size()))
              : identifier == 101 ? 0 : identifier == 102 ? Integer.MAX_VALUE : 7;
      String payNo = "t163-pay-" + identifier;
      String orderNo = "t163-order-" + identifier;
      var due =
          negative
              ? currentTime.minusDays(1)
              : currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      String paymentStage =
          identifier == 102
              ? "order_sync_unknown"
              : identifier == 103 ? "REFUND_PENDING " : "PENDING";
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,?,'LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          paymentStage,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',?,?,'owned-existing-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          attempts,
          due,
          currentTime,
          currentTime);
    }
    var before = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, payFacts());
    assertEquals(
        1,
        intents.claim("t163-pay-102", "t163-max-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(
        Integer.MAX_VALUE,
        fixture.queryForObject(
            "SELECT attempts FROM pay_debit_intent WHERE id=102", Integer.class));
    assertEquals(
        0, intents.claim("t163-pay-1", "t163-bad-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(before.get(0), payFacts().get(0));
    assertEquals(before.get(1).subList(0, 100), payFacts().get(1).subList(0, 100));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id<=100 AND (attempts>=0 OR lease_owner IS"
                + " NOT NULL OR last_error<>'owned-existing-proof')",
            Integer.class));
  }

  /** INT最大尝试数仍可合法领取，不因UPDATE范围错误阻断恢复。 */
  @Test
  void maximumAttemptsCanClaimWithoutOverflow() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStage : List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      assertions.add(
          () -> {
            seedIntentAndPayment(paymentStage, "INIT");
            fixture.update(
                "UPDATE pay_debit_intent SET attempts=?,last_error='owned-existing-proof' WHERE"
                    + " id=1",
                Integer.MAX_VALUE);
            var before = payFacts();
            var currentTime =
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                    assertEquals(
                        1,
                        intents.claim(
                            "pay-1", "t162-max-token", currentTime.plusSeconds(30), currentTime)),
                paymentStage);
            assertEquals(Integer.MAX_VALUE, intentAttempts());
            assertEquals("t162-max-token", intentLeaseOwner());
            assertEquals(before.get(0), payFacts().get(0));
            assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 普通计数递增、MAX饱和，busy不写，过期换owner后旧token仍无写资格。 */
  @Test
  void attemptCounterBoundariesPreserveLeaseAndOwnership() {
    for (int originalAttempts : List.of(0, 7, Integer.MAX_VALUE - 1, Integer.MAX_VALUE)) {
      seedIntentAndPayment("PENDING", "INIT");
      fixture.update(
          "UPDATE pay_debit_intent SET attempts=?,last_error='owned-existing-proof' WHERE id=1",
          originalAttempts);
      var before = payFacts();
      var currentTime =
          LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
              .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      assertEquals(1, intents.findDue(currentTime, 1).size());
      assertEquals(1, intents.findDueAfter(currentTime, null, 1).size());
      assertEquals(before, payFacts());
      int firstCount =
          originalAttempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : originalAttempts + 1;
      assertEquals(
          1, intents.claim("pay-1", "t162-first-owner", currentTime.plusSeconds(30), currentTime));
      assertEquals(firstCount, intentAttempts());
      assertEquals("t162-first-owner", intentLeaseOwner());
      assertEquals(
          currentTime.plusSeconds(30),
          fixture.queryForObject(
              "SELECT lease_until FROM pay_debit_intent WHERE id=1", LocalDateTime.class));
      assertEquals(
          currentTime,
          fixture.queryForObject(
              "SELECT updated_at FROM pay_debit_intent WHERE id=1", LocalDateTime.class));
      assertEquals(
          "owned-existing-proof",
          fixture.queryForObject(
              "SELECT last_error FROM pay_debit_intent WHERE id=1", String.class));
      assertEquals(before.get(0), payFacts().get(0));
      assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      var busyFacts = payFacts();
      assertEquals(
          0, intents.claim("pay-1", "t162-busy-owner", currentTime.plusSeconds(30), currentTime));
      assertEquals(busyFacts, payFacts());
      fixture.update(
          "UPDATE pay_debit_intent SET lease_until=? WHERE id=1", currentTime.minusSeconds(1));
      int secondCount = firstCount == Integer.MAX_VALUE ? Integer.MAX_VALUE : firstCount + 1;
      assertEquals(
          1, intents.claim("pay-1", "t162-second-owner", currentTime.plusSeconds(30), currentTime));
      assertEquals(secondCount, intentAttempts());
      assertEquals("t162-second-owner", intentLeaseOwner());
      var reclaimed = payFacts();
      assertTrue(!intents.hasOwnedLegacyLease("pay-1", "t162-first-owner", currentTime));
      assertTrue(intents.hasOwnedLegacyLease("pay-1", "t162-second-owner", currentTime));
      assertEquals(
          0, intents.updateOwnedLegacyStatus("pay-1", "t162-first-owner", "DEBITED", currentTime));
      assertEquals(
          0,
          intents.recordRetryOwnedLegacy(
              "pay-1",
              "t162-first-owner",
              "INIT",
              "old-owner-error",
              currentTime.plusSeconds(5),
              currentTime));
      assertEquals(
          0, intents.finishOwnedLegacy("pay-1", "t162-first-owner", "FAILED", currentTime));
      assertEquals(reclaimed, payFacts());
    }
  }

  /** MAX计数既能健康SUCCESS，也能读故障后60秒退避再同pay恢复，不重置或追加资金写。 */
  @Test
  void maximumAttemptsStillRecoverAfterControlledReadFailure() {
    for (boolean failFirst : List.of(false, true)) {
      seedIntentAndPayment("PENDING", "INIT");
      fixture.update(
          "UPDATE pay_debit_intent SET attempts=?,last_error='owned-existing-proof' WHERE id=1",
          Integer.MAX_VALUE);
      var before = payFacts();
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
      var service = newService(user, orders);
      if (failFirst) {
        org.mockito.Mockito.doThrow(new IllegalStateException("owned-t162-user-proof-fault"))
            .when(user)
            .operation(7L, "pay-1", 7L);
        var started = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals("INIT", service.process("pay-1"));
        var finished = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(Integer.MAX_VALUE, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals("PENDING", paymentStatus());
        assertEquals("INIT", intentStatus());
        assertTrue(retryErrorPresent());
        assertTrue(
            !intentNextRetry()
                .isBefore(
                    started.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        assertTrue(!intentNextRetry().isAfter(finished.plusSeconds(60)));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertTrue(intents.findDue(finished, 1).isEmpty());
        assertTrue(intents.findDueAfter(finished, null, 1).isEmpty());
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE id=1");
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "APPLIED", "10.000", null)))
            .when(user)
            .operation(7L, "pay-1", 7L);
      }
      assertEquals("SUCCESS", service.process("pay-1"));
      assertEquals("SUCCESS", paymentStatus());
      assertEquals("SUCCESS", intentStatus());
      assertEquals(Integer.MAX_VALUE, intentAttempts());
      assertNull(intentLeaseOwner());
      assertNotNull(records.selectByPayNo("pay-1").get(0).paidAt);
      assertEquals(7L, records.selectByPayNo("pay-1").get(0).userId);
      assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
      assertEquals(0, BigDecimal.TEN.compareTo(records.selectByPayNo("pay-1").get(0).amount));
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 两表共同Java空白pay/order key不能授权领取，空白种类不按SQL trim缩窄。 */
  @Test
  void recoveryClaimRejectsJavaBlankKeys() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String field : List.of("pay_no", "order_no")) {
      for (String value : javaBlankRecoveryKeys()) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              changeCoherentRecoveryKey(field, value);
              var before = payFacts();
              var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              String requested = "pay_no".equals(field) ? value : "pay-1";
              String description =
                  field + ":" + value.codePoints().mapToObj(Integer::toHexString).toList();
              assertEquals(
                  0,
                  intents.claim(
                      requested, "t161-blank-token", currentTime.plusSeconds(30), currentTime),
                  description);
              assertEquals(before, payFacts());
              assertEquals(
                  0,
                  fixture.queryForObject(
                      "SELECT attempts FROM pay_debit_intent WHERE id=1", Integer.class));
              assertNull(
                  fixture.queryForObject(
                      "SELECT lease_owner FROM pay_debit_intent WHERE id=1", String.class));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 本机Java17已枚举25空白码点；覆盖空串、混合空白及每个单独码点。 */
  private static List<String> javaBlankRecoveryKeys() {
    var values = new ArrayList<String>(List.of("", "\t \n", "\u1680\u2000\u3000"));
    for (int codePoint = 0; codePoint <= 0x3000; codePoint++) {
      if (Character.isWhitespace(codePoint)) values.add(new String(Character.toChars(codePoint)));
    }
    assertEquals(28, values.size());
    assertTrue(values.stream().allMatch(String::isBlank));
    return values;
  }

  /** 只允许owned两表同步设置固定key字段，不改变模式、数值或恢复metadata。 */
  private void changeCoherentRecoveryKey(String field, String value) {
    assertTrue(List.of("pay_no", "order_no").contains(field));
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      assertEquals(1, fixture.update("UPDATE " + table + " SET " + field + "=? WHERE id=1", value));
    }
  }

  /** 预route后双方订单号同变Java空白，真实claim拒绝而不先写metadata。 */
  @Test
  void blankOrderDriftBeforeClaimPreservesFactsAndRouteError() {
    for (String value : javaBlankRecoveryKeys()) {
      seedIntentAndPayment("PENDING", "INIT");
      fixture.update("UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                changeCoherentRecoveryKey("order_no", value);
                afterChange.set(payFacts());
                assertEquals(0, invocation.callRealMethod());
                return 0;
              })
          .when(guardedIntents)
          .claim(eq("pay-1"), anyString(), any(), any());
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      intents = guardedIntents;
      try {
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> newService(user, orders).process("pay-1"));
        assertEquals("COMMON_CONFLICT", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        assertEquals("支付意图与原支付记录不一致", failure.getMessage());
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals(0, intentAttempts());
        assertNull(intentLeaseOwner());
        org.mockito.Mockito.verifyNoInteractions(user, orders);
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** Java非blank特殊字符与混合key保持，原DDL唯一性下100空白头先过滤再LIMIT。 */
  @Test
  void nonBlankKeySemanticsAndBlankHeadScanRemainCorrect() {
    var nonblankValues =
        List.of(
            new String(Character.toChars(0)),
            "\u0085",
            "\u00a0",
            "\u2007",
            "\u202f",
            "\u180e",
            "\u200b",
            "\ufeff",
            " \tpay-1\n",
            "订单",
            "😀");
    for (String field : List.of("pay_no", "order_no")) {
      for (String value : nonblankValues) {
        assertTrue(!value.isBlank());
        seedIntentAndPayment("PENDING", "INIT");
        changeCoherentRecoveryKey(field, value);
        var before = payFacts();
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String requested = "pay_no".equals(field) ? value : "pay-1";
        assertEquals(
            List.of(requested),
            intents.findDue(currentTime, 1).stream().map(candidate -> candidate.payNo).toList());
        assertEquals(
            List.of(requested),
            intents.findDueAfter(currentTime, null, 1).stream()
                .map(candidate -> candidate.payNo)
                .toList());
        assertEquals(before, payFacts());
        assertEquals(
            0, intents.claim(null, "t161-null-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(before, payFacts());
        assertEquals(
            1,
            intents.claim(requested, "t161-valid-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(
            0,
            intents.claim(
                requested, "t161-second-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(
            1,
            fixture.queryForObject(
                "SELECT attempts FROM pay_debit_intent WHERE id=1", Integer.class));
        assertEquals(
            "t161-valid-token",
            fixture.queryForObject(
                "SELECT lease_owner FROM pay_debit_intent WHERE id=1", String.class));
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    for (long identifier = 1; identifier <= 103; identifier++) {
      boolean blank = identifier <= 100;
      String payNo = "t161-pay-" + identifier;
      // 只读collation探针已证明这100个Java空白key互异，保原两表unique索引及VARCHAR64。
      String orderNo =
          blank
              ? (identifier <= 50
                  ? "\t".repeat((int) identifier)
                  : "\n".repeat((int) identifier - 50))
              : "t161-order-" + identifier;
      if (blank) assertTrue(orderNo.isBlank());
      var due =
          blank
              ? currentTime.minusDays(1)
              : currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      String paymentStage =
          identifier == 102
              ? "order_sync_unknown"
              : identifier == 103 ? "REFUND_PENDING " : "PENDING";
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,?,'LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          paymentStage,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,'owned-existing-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          due,
          currentTime,
          currentTime);
    }
    var before = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, payFacts());
    assertEquals(
        1,
        intents.claim("t161-pay-102", "t161-tail-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id<=100 AND (attempts<>0 OR lease_owner IS"
                + " NOT NULL OR last_error<>'owned-existing-proof')",
            Integer.class));
  }

  /** 两表一致的非正用户或金额不能授权领取，保原租约/尝试/证据。 */
  @Test
  void recoveryClaimRejectsCoherentNonPositiveNumbers() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        List.of(
            "user-zero",
            "user-negative",
            "user-min",
            "amount-zero",
            "amount-negative",
            "amount-min")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            changeCoherentRecoveryNumber(mutation);
            var before = payFacts();
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(
                0,
                intents.claim(
                    "pay-1", "t160-domain-token", currentTime.plusSeconds(30), currentTime),
                mutation);
            assertEquals(before, payFacts());
            assertEquals(0, intentAttempts());
            assertNull(intentLeaseOwner());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 仅在owned两表同步设置固定非法数值，模拟一致性门之后仍缺数值域门。 */
  private void changeCoherentRecoveryNumber(String mutation) {
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      int changed =
          switch (mutation) {
            case "user-zero" -> fixture.update("UPDATE " + table + " SET user_id=? WHERE id=1", 0L);
            case "user-negative" ->
                fixture.update("UPDATE " + table + " SET user_id=? WHERE id=1", -1L);
            case "user-min" ->
                fixture.update("UPDATE " + table + " SET user_id=? WHERE id=1", Long.MIN_VALUE);
            case "amount-zero" ->
                fixture.update("UPDATE " + table + " SET amount=? WHERE id=1", BigDecimal.ZERO);
            case "amount-negative" ->
                fixture.update(
                    "UPDATE " + table + " SET amount=? WHERE id=1", new BigDecimal("-0.01"));
            case "amount-min" ->
                fixture.update(
                    "UPDATE " + table + " SET amount=? WHERE id=1",
                    new BigDecimal("-9999999999999999.99"));
            default -> throw new AssertionError(mutation);
          };
      assertEquals(1, changed);
    }
  }

  /** 预route后双方一致变成非正数值，真实claim拒绝且原409不写metadata或远端。 */
  @Test
  void coherentNumericDriftBeforeClaimPreservesFactsAndRouteError() {
    for (String mutation :
        List.of(
            "user-zero",
            "user-negative",
            "user-min",
            "amount-zero",
            "amount-negative",
            "amount-min")) {
      seedIntentAndPayment("PENDING", "INIT");
      fixture.update("UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
      PayDebitIntentMapper actualIntents = intents;
      var guardedIntents = org.mockito.Mockito.spy(actualIntents);
      var afterChange =
          new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                changeCoherentRecoveryNumber(mutation);
                afterChange.set(payFacts());
                assertEquals(0, invocation.callRealMethod());
                return 0;
              })
          .when(guardedIntents)
          .claim(eq("pay-1"), anyString(), any(), any());
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      intents = guardedIntents;
      try {
        var failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> newService(user, orders).process("pay-1"));
        assertEquals("COMMON_CONFLICT", failure.getCode());
        assertEquals(409, failure.getHttpStatus());
        assertEquals("支付意图与原支付记录不一致", failure.getMessage());
        assertNotNull(afterChange.get());
        assertEquals(afterChange.get(), payFacts());
        assertEquals(0, intentAttempts());
        assertNull(intentLeaseOwner());
        org.mockito.Mockito.verifyNoInteractions(user, orders);
      } finally {
        intents = actualIntents;
      }
    }
  }

  /** 正用户及DECIMAL正边界保持，非正数值头先过滤，扫描只读排序/游标不变。 */
  @Test
  void positiveNumericBoundariesAndRecoveryScanRemainEligible() {
    for (Long userId : List.of(1L, 7L, Long.MAX_VALUE)) {
      for (String amountText : List.of("0.01", "10.000", "9999999999999999.99")) {
        seedIntentAndPayment("PENDING", "INIT");
        for (String table : List.of("pay_record", "pay_debit_intent"))
          fixture.update(
              "UPDATE " + table + " SET user_id=?,amount=? WHERE id=1",
              userId,
              new BigDecimal(amountText));
        var before = payFacts();
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            List.of("pay-1"),
            intents.findDue(currentTime, 1).stream().map(candidate -> candidate.payNo).toList());
        assertEquals(
            List.of("pay-1"),
            intents.findDueAfter(currentTime, null, 1).stream()
                .map(candidate -> candidate.payNo)
                .toList());
        assertEquals(before, payFacts());
        assertEquals(
            1,
            intents.claim(
                "pay-1", "t160-positive-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(
            0,
            intents.claim("pay-1", "t160-second-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(1, intentAttempts());
        assertEquals("t160-positive-token", intentLeaseOwner());
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
      }
    }
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var mutations =
        List.of(
            "user-zero",
            "user-negative",
            "user-min",
            "amount-zero",
            "amount-negative",
            "amount-min");
    for (long identifier = 1; identifier <= 103; identifier++) {
      boolean broken = identifier <= 100;
      String mutation =
          broken ? mutations.get((int) ((identifier - 1) % mutations.size())) : "healthy";
      long userId =
          switch (mutation) {
            case "user-zero" -> 0L;
            case "user-negative" -> -1L;
            case "user-min" -> Long.MIN_VALUE;
            default -> identifier == 102 ? Long.MAX_VALUE : 1L;
          };
      BigDecimal amount =
          switch (mutation) {
            case "amount-zero" -> BigDecimal.ZERO;
            case "amount-negative" -> new BigDecimal("-0.01");
            case "amount-min" -> new BigDecimal("-9999999999999999.99");
            default ->
                new BigDecimal(
                    identifier == 101
                        ? "0.01"
                        : identifier == 102 ? "9999999999999999.99" : "10.000");
          };
      String payNo = "t160-pay-" + identifier;
      String orderNo = "t160-order-" + identifier;
      var due =
          broken
              ? currentTime.minusDays(1)
              : currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      String paymentStage =
          identifier == 102
              ? "order_sync_unknown"
              : identifier == 103 ? "REFUND_PENDING " : "PENDING";
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,'LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          userId,
          amount,
          paymentStage,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,'INIT','LEGACY',0,?,'owned-existing-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          userId,
          amount,
          due,
          currentTime,
          currentTime);
    }
    var before = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, payFacts());
    assertEquals(
        1,
        intents.claim("t160-pay-102", "t160-tail-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id<=100 AND (attempts<>0 OR lease_owner IS"
                + " NOT NULL OR last_error<>'owned-existing-proof')",
            Integer.class));
  }

  /** 两表基本tuple不一致或请求键为CI别名时，不得领取并修改原意图。 */
  @Test
  void recoveryClaimRejectsPairMismatchAndRequestedAliases() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      for (String mutation :
          List.of("user", "order", "order-alias", "order-space", "amount", "pay-alias")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              changeRecoveryPairField(table, mutation);
              var before = payFacts();
              var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              assertEquals(
                  0,
                  intents.claim(
                      "pay-1", "t159-pair-token", currentTime.plusSeconds(30), currentTime),
                  table + ":" + mutation);
              assertEquals(before, payFacts());
              assertEquals(0, intentAttempts());
              assertNull(intentLeaseOwner());
            });
      }
    }
    for (String requested : List.of("PAY-1", "páy-1", "pay-1 ")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            var before = payFacts();
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(
                0,
                intents.claim(
                    requested, "t159-alias-token", currentTime.plusSeconds(30), currentTime),
                requested);
            assertEquals(before, payFacts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 只修改owned两表的固定字段，模拟当前pair单边变化；禁止任意表/字段输入。 */
  private void changeRecoveryPairField(String table, String mutation) {
    assertTrue(List.of("pay_record", "pay_debit_intent").contains(table));
    String assignment =
        switch (mutation) {
          case "user" -> "user_id=8";
          case "order" -> "order_no='order-other'";
          case "order-alias" -> "order_no='ORDER-1'";
          case "order-space" -> "order_no='order-1 '";
          case "amount" -> "amount=11";
          case "pay-alias" -> "pay_no='PAY-1'";
          default -> throw new AssertionError(mutation);
        };
    assertEquals(1, fixture.update("UPDATE " + table + " SET " + assignment + " WHERE id=1"));
  }

  /** 预route后单边变化必须由真实claim拒绝，不先修改lease/attempt再报原409。 */
  @Test
  void pairDriftBeforeClaimPreservesFactsAndOriginalRouteError() {
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      for (String mutation :
          List.of("user", "order", "order-alias", "order-space", "amount", "pay-alias")) {
        seedIntentAndPayment("PENDING", "INIT");
        fixture.update("UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE id=1");
        PayDebitIntentMapper actualIntents = intents;
        var guardedIntents = org.mockito.Mockito.spy(actualIntents);
        var afterChange =
            new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  changeRecoveryPairField(table, mutation);
                  afterChange.set(payFacts());
                  assertEquals(0, invocation.callRealMethod());
                  return 0;
                })
            .when(guardedIntents)
            .claim(eq("pay-1"), anyString(), any(), any());
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        intents = guardedIntents;
        try {
          var failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  com.cloudmall.common.error.BizException.class,
                  () -> newService(user, orders).process("pay-1"));
          assertEquals("COMMON_CONFLICT", failure.getCode());
          assertEquals(409, failure.getHttpStatus());
          assertEquals("支付意图与原支付记录不一致", failure.getMessage());
          assertNotNull(afterChange.get());
          assertEquals(afterChange.get(), payFacts());
          assertEquals(0, intentAttempts());
          assertNull(intentLeaseOwner());
          org.mockito.Mockito.verifyNoInteractions(user, orders);
        } finally {
          intents = actualIntents;
        }
      }
    }
  }

  /** 基本pair不一致的100头先过滤，健康相等数值/phaseCI及原扫描排序/游标保持。 */
  @Test
  void recoveryPairFilterPrecedesLimitAndKeepsCoherentClaims() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var mutations = List.of("user", "order", "order-alias", "order-space", "amount", "pay-alias");
    for (long identifier = 1; identifier <= 103; identifier++) {
      boolean broken = identifier <= 100;
      String payNo = "t159-pay-" + identifier;
      String orderNo = "t159-order-" + identifier;
      var due =
          broken
              ? currentTime.minusDays(1)
              : currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      String paymentStage =
          identifier == 102
              ? "order_sync_unknown"
              : identifier == 103 ? "REFUND_PENDING " : "PENDING";
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,?,'LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          paymentStage,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,'owned-existing-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          due,
          currentTime,
          currentTime);
      if (broken) {
        String table =
            ((identifier - 1) / mutations.size()) % 2 == 0 ? "pay_record" : "pay_debit_intent";
        String mutation = mutations.get((int) ((identifier - 1) % mutations.size()));
        assertTrue(List.of("pay_record", "pay_debit_intent").contains(table));
        switch (mutation) {
          case "user" ->
              fixture.update("UPDATE " + table + " SET user_id=8 WHERE id=?", identifier);
          case "order" ->
              fixture.update(
                  "UPDATE " + table + " SET order_no=? WHERE id=?", orderNo + "-other", identifier);
          case "order-alias" ->
              fixture.update(
                  "UPDATE " + table + " SET order_no=? WHERE id=?",
                  orderNo.toUpperCase(java.util.Locale.ROOT),
                  identifier);
          case "order-space" ->
              fixture.update(
                  "UPDATE " + table + " SET order_no=? WHERE id=?", orderNo + " ", identifier);
          case "amount" ->
              fixture.update("UPDATE " + table + " SET amount=11 WHERE id=?", identifier);
          case "pay-alias" ->
              fixture.update(
                  "UPDATE " + table + " SET pay_no=? WHERE id=?",
                  payNo.toUpperCase(java.util.Locale.ROOT),
                  identifier);
          default -> throw new AssertionError(mutation);
        }
      }
    }
    var before = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(before, payFacts());
    // 两边同步合法变化仍可匹配：本门不把旧快照或不可变字段保证加入claim。
    for (String table : List.of("pay_record", "pay_debit_intent")) {
      fixture.update(
          "UPDATE " + table + " SET user_id=8,order_no='t159-coherent-order',amount=? WHERE id=102",
          new BigDecimal("20.000"));
    }
    var coherentFacts = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(coherentFacts, payFacts());
    assertEquals(
        1,
        intents.claim(
            "t159-pay-102", "t159-coherent-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(
        0,
        intents.claim(
            "t159-pay-102", "t159-second-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(coherentFacts.get(0), payFacts().get(0));
    assertEquals(immutableRecoveryFacts(coherentFacts), immutableRecoveryFacts(payFacts()));
    assertEquals(
        List.of(103L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id<=100 AND (attempts<>0 OR lease_owner IS"
                + " NOT NULL OR last_error<>'owned-existing-proof')",
            Integer.class));
  }

  /** 已终态和非可恢复PAY不得领取INIT意图，不能改原租约/尝试/恢复证据。 */
  @Test
  void inactivePaymentCannotClaimLegacyRecoveryWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStage :
        List.of(
            "SUCCESS",
            "success",
            "SUCCESS ",
            "FAILED",
            "failed",
            "FAILED ",
            "REFUNDED",
            "refunded",
            "REFUNDED ",
            "UNKNOWN",
            "",
            " ")) {
      assertions.add(
          () -> {
            seedIntentAndPayment(paymentStage, "INIT");
            fixture.update(
                "UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE"
                    + " pay_no='pay-1'");
            var before = payFacts();
            var currentTime = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            assertEquals(
                0,
                intents.claim(
                    "pay-1", "t158-owned-token", currentTime.plusSeconds(30), currentTime),
                paymentStage);
            assertEquals(before, payFacts());
            assertEquals(0, intentAttempts());
            assertNull(intentLeaseOwner());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 无资格PAY在实际process中不领取或调用远端，合法active与CI阶段仍可领取。 */
  @Test
  void paymentPhaseEligibilityPreservesInactiveFactsAndHealthyClaims() {
    for (String paymentStage :
        List.of(
            "SUCCESS",
            "success",
            "SUCCESS ",
            "FAILED",
            "failed",
            "FAILED ",
            "REFUNDED",
            "refunded",
            "REFUNDED ",
            "UNKNOWN",
            "",
            " ")) {
      for (String intentStage :
          List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        seedIntentAndPayment(paymentStage, intentStage);
        fixture.update(
            "UPDATE pay_debit_intent SET last_error='owned-existing-proof' WHERE pay_no='pay-1'");
        var before = payFacts();
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        assertEquals(intentStage, newService(user, orders).process("pay-1"));
        assertEquals(before, payFacts());
        assertEquals(0, intentAttempts());
        assertNull(intentLeaseOwner());
        org.mockito.Mockito.verifyNoInteractions(user, orders);
      }
    }
    for (String paymentStage :
        List.of(
            "PENDING",
            "pending",
            "PENDING ",
            "ORDER_SYNC_UNKNOWN",
            "order_sync_unknown",
            "ORDER_SYNC_UNKNOWN ",
            "REFUND_PENDING",
            "refund_pending",
            "REFUND_PENDING ")) {
      for (String intentStage :
          List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        seedIntentAndPayment(paymentStage, intentStage);
        var before = payFacts();
        var currentTime =
            LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(
            List.of("pay-1"),
            intents.findDue(currentTime, 1).stream().map(candidate -> candidate.payNo).toList());
        assertEquals(
            List.of("pay-1"),
            intents.findDueAfter(currentTime, null, 1).stream()
                .map(candidate -> candidate.payNo)
                .toList());
        assertEquals(before, payFacts());
        assertEquals(
            1,
            intents.claim("pay-1", "t158-active-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(
            0,
            intents.claim("pay-1", "t158-second-token", currentTime.plusSeconds(30), currentTime));
        assertEquals(1, intentAttempts());
        assertEquals("t158-active-token", intentLeaseOwner());
        assertEquals(paymentStage, paymentStatus());
        assertEquals(intentStage, intentStatus());
        assertEquals(before.get(0), payFacts().get(0));
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertTrue(intents.findDue(currentTime, 1).isEmpty());
        assertTrue(intents.findDueAfter(currentTime, null, 1).isEmpty());
      }
    }
  }

  /** 两种真实扫描先过滤100无资格头再LIMIT，时间/主键/游标/到期边界及只读事实保持。 */
  @Test
  void recoveryScanFiltersInactivePaymentsBeforeLimitAndKeepsOrdering() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    var currentTime =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var inactiveStages =
        List.of(
            "SUCCESS",
            "success",
            "SUCCESS ",
            "FAILED",
            "failed",
            "FAILED ",
            "REFUNDED",
            "refunded",
            "REFUNDED ",
            "UNKNOWN",
            "",
            " ");
    for (long identifier = 1; identifier <= 103; identifier++) {
      boolean inactive = identifier <= 100;
      String payNo = "t158-pay-" + identifier;
      String orderNo = "t158-order-" + identifier;
      String paymentStage =
          inactive
              ? inactiveStages.get((int) ((identifier - 1) % inactiveStages.size()))
              : identifier == 101
                  ? "PENDING"
                  : identifier == 102 ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
      var due =
          inactive
              ? currentTime.minusDays(1)
              : currentTime.minusSeconds(identifier == 101 ? 3 : identifier == 102 ? 5 : 4);
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,?,'LEGACY',?,?)",
          identifier,
          payNo,
          orderNo,
          paymentStage,
          currentTime,
          currentTime);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,last_error,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,?,'owned-existing-proof',?,?)",
          identifier,
          payNo,
          orderNo,
          due,
          currentTime,
          currentTime);
    }
    var before = payFacts();
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 1).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(102L, 103L, 101L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(102L),
        intents.findDueAfter(currentTime, 101L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, 102L, 1).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertTrue(intents.findDueAfter(currentTime, 103L, 1).isEmpty());
    assertEquals(
        List.of(102L),
        intents.findDue(currentTime, 0).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L),
        intents.findDueAfter(currentTime, null, 0).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(before, payFacts());
    fixture.update(
        "UPDATE pay_debit_intent SET lease_owner='owned-busy',lease_until=? WHERE id=101",
        currentTime.plusSeconds(30));
    fixture.update(
        "UPDATE pay_debit_intent SET next_retry_at=? WHERE id=102", currentTime.plusSeconds(1));
    var busyFacts = payFacts();
    assertEquals(
        List.of(103L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(103L),
        intents.findDueAfter(currentTime, null, 50).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(busyFacts, payFacts());
    fixture.update("UPDATE pay_debit_intent SET lease_until=? WHERE id=101", currentTime);
    fixture.update("UPDATE pay_debit_intent SET next_retry_at=? WHERE id=102", currentTime);
    fixture.update("UPDATE pay_record SET status='FAILED' WHERE id=103");
    var boundaryFacts = payFacts();
    assertEquals(
        List.of(101L, 102L),
        intents.findDue(currentTime, 50).stream().map(candidate -> candidate.id).toList());
    assertEquals(
        List.of(101L, 102L),
        intents.findDueAfter(currentTime, null, 50).stream()
            .map(candidate -> candidate.id)
            .toList());
    assertEquals(boundaryFacts, payFacts());
    assertEquals(
        0,
        intents.claim(
            "t158-pay-103", "t158-terminal-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(boundaryFacts, payFacts());
    assertEquals(
        1,
        intents.claim(
            "t158-pay-101", "t158-boundary-token", currentTime.plusSeconds(30), currentTime));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id<=100 AND (attempts<>0 OR lease_owner IS"
                + " NOT NULL OR last_error<>'owned-existing-proof')",
            Integer.class));
  }

  /** 末次User复核期间订单变化，旧未扣款/拒绝来源不能授权当前FAILED。 */
  @Test
  void latestOrderProofIsRequiredBeforeFailureWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("absence", "rejected")) {
      for (String variant :
          List.of(
              "pending",
              "paid",
              "completed",
              "status-null",
              "status-unknown",
              "order-other",
              "order-alias",
              "user-other",
              "amount-other",
              "null-response",
              "null-body",
              "bad-envelope",
              "fault")) {
        if ("rejected".equals(path) && "pending".equals(variant)) continue;
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              UserClient missingUser = mock(UserClient.class);
              stubRefundOperation(missingUser, "not-found", "pay-1", "10.00");
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(
                  "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1", user, orders);
              var changed = new java.util.concurrent.atomic.AtomicBoolean();
              var userReads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (userReads.incrementAndGet() == 2) changed.set(true);
                        if ("absence".equals(path)) return missingUser.operation(7L, "pay-1", 7L);
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1", "DEBIT", "REJECTED", "10.000", null));
                      })
                  .when(user)
                  .operation(7L, "pay-1", 7L);
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        return successOrderProofResponse(changed.get() ? variant : "cancelled");
                      })
                  .when(orders)
                  .paymentState("order-1", 7L);
              assertEquals("INIT", newService(user, orders).process("pay-1"), path + ":" + variant);
              assertEquals(2, userReads.get());
              assertEquals("INIT", intentStatus());
              assertEquals("PENDING", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
              verify(orders, times(2)).paymentState("order-1", 7L);
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, never()).paid(any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 失败末次Order缺证可补取消或原扣款成功证据，同pay恢复不追加远端写。 */
  @Test
  void latestFailureOrderRepairResumesOriginalPayment() {
    for (String path : List.of("absence", "rejected")) {
      for (String repaired : List.of("cancelled", "applied-paid")) {
        if ("rejected".equals(path) && "applied-paid".equals(repaired)) continue;
        seedIntentAndPayment("PENDING", "INIT");
        var before = payFacts();
        UserClient user = mock(UserClient.class);
        UserClient missingUser = mock(UserClient.class);
        stubRefundOperation(missingUser, "not-found", "pay-1", "10.00");
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(
            "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1", user, orders);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var userReads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  if (userReads.incrementAndGet() == 2) changed.set(true);
                  if ("absence".equals(path)) return missingUser.operation(7L, "pay-1", 7L);
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "REJECTED", "10.00", null));
                })
            .when(user)
            .operation(7L, "pay-1", 7L);
        org.mockito.Mockito.doAnswer(
                invocation -> successOrderProofResponse(changed.get() ? "paid" : "cancelled"))
            .when(orders)
            .paymentState("order-1", 7L);
        var service = newService(user, orders);
        assertEquals("INIT", service.process("pay-1"));
        assertEquals("PENDING", paymentStatus());
        assertEquals("INIT", intentStatus());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        assertTrue(retryErrorPresent());
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE"
                + " pay_no='pay-1'");
        boolean successful = "applied-paid".equals(repaired);
        if (successful)
          org.mockito.Mockito.doReturn(
                  ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "APPLIED", "10.000", null)))
              .when(user)
              .operation(7L, "pay-1", 7L);
        org.mockito.Mockito.doReturn(successOrderProofResponse(successful ? "paid" : "cancelled"))
            .when(orders)
            .paymentState("order-1", 7L);
        String terminal = successful ? "SUCCESS" : "FAILED";
        assertEquals(terminal, service.process("pay-1"));
        assertEquals(terminal, paymentStatus());
        assertEquals(terminal, intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        if (!successful)
          assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        else assertNotNull(records.selectByPayNo("pay-1").get(0).paidAt);
        assertEquals(7L, records.selectByPayNo("pay-1").get(0).userId);
        assertEquals("order-1", records.selectByPayNo("pay-1").get(0).orderNo);
        assertEquals(0, BigDecimal.TEN.compareTo(records.selectByPayNo("pay-1").get(0).amount));
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      }
    }
  }

  /** 健康缺失和拒绝来源经两次User读取后仍可FAILED，读取在实际事务外。 */
  @Test
  void latestStableDebitKeepsHealthyFailurePathsWithRealPay() {
    for (String path : java.util.List.of("absence", "rejected")) {
      for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
        if ("absence".equals(path) && !"CANCELLED".equals(orderStatus)) continue;
        seedIntentAndPayment("PENDING", "INIT");
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(
            "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1", user, orders);
        var service = newService(user, orders);
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", orderStatus, new BigDecimal("10.000"), 7L, "UNKNOWN")));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  reads.incrementAndGet();
                  if ("absence".equals(path))
                    throw feign.FeignException.errorStatus(
                        "operation",
                        feign.Response.builder()
                            .status(404)
                            .reason("controlled-missing")
                            .request(
                                feign.Request.create(
                                    feign.Request.HttpMethod.GET,
                                    "http://cloud-mall-user/controlled-proof",
                                    java.util.Map.of(),
                                    null,
                                    java.nio.charset.StandardCharsets.UTF_8))
                            .headers(java.util.Map.of())
                            .body(new byte[0])
                            .build());
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "REJECTED", "10.000", null));
                })
            .when(user)
            .operation(7L, "pay-1", 7L);
        assertEquals("FAILED", service.process("pay-1"));
        assertEquals(2, reads.get());
        verify(orders, times(2)).paymentState("order-1", 7L);
        assertEquals("FAILED", intentStatus());
        assertEquals("FAILED", paymentStatus());
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      }
    }
  }

  /** 末次失败证据变化后，同支付修正原账本可退款或明确拒绝，无新扣款。 */
  @Test
  void latestFailureProofCanResumeAfterEvidenceRepairWithRealPay() {
    for (String scenario : java.util.List.of("absence", "rejected", "marker-repair")) {
      String path = "absence".equals(scenario) ? "absence" : "rejected";
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1", user, orders);
      var service = newService(user, orders);
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                if (reads.incrementAndGet() == 1) {
                  if ("absence".equals(path))
                    throw feign.FeignException.errorStatus(
                        "operation",
                        feign.Response.builder()
                            .status(404)
                            .reason("controlled-missing")
                            .request(
                                feign.Request.create(
                                    feign.Request.HttpMethod.GET,
                                    "http://cloud-mall-user/controlled-proof",
                                    java.util.Map.of(),
                                    null,
                                    java.nio.charset.StandardCharsets.UTF_8))
                            .headers(java.util.Map.of())
                            .body(new byte[0])
                            .build());
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "REJECTED", "10.00", null));
                }
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1",
                        "DEBIT",
                        "marker-repair".equals(scenario) ? "REJECTED" : "APPLIED",
                        "10.000",
                        "marker-repair".equals(scenario) ? "refund:pay-1" : null));
              })
          .when(user)
          .operation(7L, "pay-1", 7L);
      assertEquals("INIT", service.process("pay-1"));
      assertEquals(2, reads.get());
      assertEquals("INIT", intentStatus());
      assertEquals("PENDING", paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1",
                      "DEBIT",
                      "marker-repair".equals(scenario) ? "REJECTED" : "APPLIED",
                      "10.000",
                      null)))
          .when(user)
          .operation(7L, "pay-1", 7L);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")))
          .when(orders)
          .paymentState("order-1", 7L);
      String expectedTerminal = "marker-repair".equals(scenario) ? "FAILED" : "REFUNDED";
      assertEquals(expectedTerminal, service.process("pay-1"));
      assertEquals(expectedTerminal, intentStatus());
      assertEquals(expectedTerminal, paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times("marker-repair".equals(scenario) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 失败终态前原扣款来源变化不能被早期缺失或拒绝覆盖。 */
  @Test
  void latestDebitProofIsRequiredBeforeFailureWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("absence", "rejected")) {
      for (String variant :
          java.util.List.of(
              "applied",
              "marker",
              "marker-blank",
              "key-null",
              "key-other",
              "key-alias",
              "type",
              "amount-null",
              "amount-other",
              "state-null",
              "state-other",
              "null-envelope",
              "null-body",
              "bad-envelope",
              "fault",
              "missing-or-rejected")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(
                  "absence".equals(path) ? "no-debit-cancel" : "rejected", "order-1", user, orders);
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (reads.incrementAndGet() == 1) {
                          if ("absence".equals(path))
                            throw feign.FeignException.errorStatus(
                                "operation",
                                feign.Response.builder()
                                    .status(404)
                                    .reason("controlled-missing")
                                    .request(
                                        feign.Request.create(
                                            feign.Request.HttpMethod.GET,
                                            "http://cloud-mall-user/controlled-proof",
                                            java.util.Map.of(),
                                            null,
                                            java.nio.charset.StandardCharsets.UTF_8))
                                    .headers(java.util.Map.of())
                                    .body(new byte[0])
                                    .build());
                          return ApiResponse.ok(
                              new UserClient.BalanceOperationView(
                                  "pay-1", "DEBIT", "REJECTED", "10.00", null));
                        }
                        if ("missing-or-rejected".equals(variant) && "rejected".equals(path))
                          throw feign.FeignException.errorStatus(
                              "operation",
                              feign.Response.builder()
                                  .status(404)
                                  .reason("controlled-missing")
                                  .request(
                                      feign.Request.create(
                                          feign.Request.HttpMethod.GET,
                                          "http://cloud-mall-user/controlled-proof",
                                          java.util.Map.of(),
                                          null,
                                          java.nio.charset.StandardCharsets.UTF_8))
                                  .headers(java.util.Map.of())
                                  .body(new byte[0])
                                  .build());
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-failure-proof-unavailable");
                        if ("null-envelope".equals(variant)) return null;
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "key-null".equals(variant)
                                    ? null
                                    : "key-other".equals(variant)
                                        ? "pay-other"
                                        : "key-alias".equals(variant) ? "PAY-1" : "pay-1",
                                "type".equals(variant) ? "REFUND" : "DEBIT",
                                "applied".equals(variant)
                                    ? "APPLIED"
                                    : "state-null".equals(variant)
                                        ? null
                                        : "state-other".equals(variant) ? "UNKNOWN" : "REJECTED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant) ? "11.00" : "10.000",
                                "marker".equals(variant)
                                    ? "refund:pay-1"
                                    : "marker-blank".equals(variant) ? "" : null));
                      })
                  .when(user)
                  .operation(7L, "pay-1", 7L);
              String result = newService(user, orders).process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals("INIT", result),
                  () -> assertEquals(2, reads.get()),
                  () -> verify(user, never()).debit(any(), any(), any()),
                  () -> verify(user, never()).refund(any(), any(), any()),
                  () -> verify(orders, never()).paid(any(), any()));
              assertEquals("INIT", intentStatus());
              assertEquals("PENDING", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** REFUND证明只允许NULL关联marker，早期和末次确认都不能接受非NULL。 */
  @Test
  void nonNullRefundMarkerCannotAuthorizeCompletionWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("marker", "post-refund", "after-paid")) {
      for (String timing : List.of("early", "latest")) {
        for (String marker : List.of("", " ", "refund:pay-1", "other-refund")) {
          assertions.add(
              () -> {
                seedIntentAndPayment("PENDING", "INIT");
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath(
                    "marker".equals(path)
                        ? "refund-marker"
                        : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
                    "order-1",
                    user,
                    orders);
                var refundReads = new java.util.concurrent.atomic.AtomicInteger();
                org.mockito.Mockito.doAnswer(
                        invocation -> {
                          org.junit.jupiter.api.Assertions.assertFalse(
                              org.springframework.transaction.support
                                  .TransactionSynchronizationManager.isActualTransactionActive());
                          int readNumber = refundReads.incrementAndGet();
                          return ApiResponse.ok(
                              new UserClient.BalanceOperationView(
                                  "refund:pay-1",
                                  "REFUND",
                                  "APPLIED",
                                  "10.000",
                                  "early".equals(timing) || readNumber > 1 ? marker : null));
                        })
                    .when(user)
                    .operation(7L, "refund:pay-1", 7L);
                String retry = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
                assertEquals(
                    retry,
                    newService(user, orders).process("pay-1"),
                    path + ":" + timing + ":" + marker);
                assertEquals(retry, paymentStatus());
                assertEquals(retry, intentStatus());
                assertEquals("early".equals(timing) ? 1 : 2, refundReads.get());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertTrue(retryErrorPresent());
                verify(user, never()).debit(any(), any(), any());
                verify(user, times("marker".equals(path) ? 0 : 1)).refund(any(), any(), any());
                verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 非NULL退款marker缺证后，补同key NULL事实可恢复且不重复已发退款。 */
  @Test
  void refundMarkerRepairResumesSamePaymentWithoutAdditionalRefund() {
    for (String path : List.of("marker", "post-refund", "after-paid")) {
      for (String timing : List.of("early", "latest")) {
        seedIntentAndPayment("PENDING", "INIT");
        var before = payFacts();
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(
            "marker".equals(path)
                ? "refund-marker"
                : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
            "order-1",
            user,
            orders);
        String key = "marker".equals(path) ? "custom-refund" : "refund:pay-1";
        if ("marker".equals(path))
          when(user.operation(7L, "pay-1", 7L))
              .thenReturn(
                  ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1", "DEBIT", "APPLIED", "10.00", key)));
        var refundReads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  int readNumber = refundReads.incrementAndGet();
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          key,
                          "REFUND",
                          "APPLIED",
                          "10.000",
                          "early".equals(timing) || readNumber > 1 ? key : null));
                })
            .when(user)
            .operation(7L, key, 7L);
        var service = newService(user, orders);
        String retry = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
        assertEquals(retry, service.process("pay-1"));
        assertEquals(retry, paymentStatus());
        assertEquals(retry, intentStatus());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertEquals(1, intentAttempts());
        assertNull(intentLeaseOwner());
        assertTrue(retryErrorPresent());
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE"
                + " pay_no='pay-1'");
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.00", key)))
            .when(user)
            .operation(7L, "pay-1", 7L);
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(key, "REFUND", "APPLIED", "1E+1", null)))
            .when(user)
            .operation(7L, key, 7L);
        assertEquals("REFUNDED", service.process("pay-1"));
        assertEquals("REFUNDED", paymentStatus());
        assertEquals("REFUNDED", intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        verify(user, times("early".equals(timing) ? 3 : 4)).operation(7L, key, 7L);
        verify(user, never()).debit(any(), any(), any());
        verify(user, times("marker".equals(path) ? 0 : 1)).refund(any(), any(), any());
        verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
      }
    }
  }

  /** 末次Order读期间退款证据变坏，早期全额账本不能授权REFUNDED。 */
  @Test
  void latestRefundLedgerIsRequiredAfterFinalOrderReadWithRealPay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : List.of("marker", "post-refund", "after-paid")) {
      for (String variant :
          List.of(
              "partial", "wrong-state", "not-found", "null-body", "wrong-key", "server-error")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              UserClient user = mock(UserClient.class);
              UserClient lateUser = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(
                  "marker".equals(path)
                      ? "refund-marker"
                      : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
                  "order-1",
                  user,
                  orders);
              stubRefundOperation(lateUser, variant, "refund:pay-1", "5.00");
              var finalOrderRead = new java.util.concurrent.atomic.AtomicBoolean();
              var orderReads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (finalOrderRead.get()) return lateUser.operation(7L, "refund:pay-1", 7L);
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "refund:pay-1", "REFUND", "APPLIED", "10.000", null));
                      })
                  .when(user)
                  .operation(7L, "refund:pay-1", 7L);
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        int readNumber = orderReads.incrementAndGet();
                        if (readNumber == ("after-paid".equals(path) ? 3 : 2))
                          finalOrderRead.set(true);
                        return ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                "after-paid".equals(path) && readNumber == 1
                                    ? "PENDING_PAYMENT"
                                    : "CANCELLED",
                                BigDecimal.TEN,
                                7L,
                                "SUCCEEDED"));
                      })
                  .when(orders)
                  .paymentState("order-1", 7L);
              String expected = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
              assertEquals(
                  expected, newService(user, orders).process("pay-1"), path + ":" + variant);
              assertEquals(expected, paymentStatus());
              assertEquals(expected, intentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertTrue(retryErrorPresent());
              verify(user, never()).debit(any(), any(), any());
              verify(user, times("marker".equals(path) ? 0 : 1)).refund(any(), any(), any());
              verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 末次退款缺证保原等待，补同key原账本后同pay完成，不重复退款。 */
  @Test
  void latestRefundLedgerRepairResumesOriginalPaymentWithoutRefundReplay() {
    for (String path : List.of("marker", "post-refund", "after-paid")) {
      for (String variant : List.of("partial", "not-found")) {
        seedIntentAndPayment("PENDING", "INIT");
        var before = payFacts();
        UserClient user = mock(UserClient.class);
        UserClient lateUser = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath(
            "marker".equals(path)
                ? "refund-marker"
                : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
            "order-1",
            user,
            orders);
        stubRefundOperation(lateUser, variant, "refund:pay-1", "5.00");
        var finalOrderRead = new java.util.concurrent.atomic.AtomicBoolean();
        var orderReads = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  org.junit.jupiter.api.Assertions.assertFalse(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive());
                  if (finalOrderRead.get()) return lateUser.operation(7L, "refund:pay-1", 7L);
                  return ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "refund:pay-1", "REFUND", "APPLIED", "10.00", null));
                })
            .when(user)
            .operation(7L, "refund:pay-1", 7L);
        org.mockito.Mockito.doAnswer(
                invocation -> {
                  int readNumber = orderReads.incrementAndGet();
                  if (readNumber == ("after-paid".equals(path) ? 3 : 2)) finalOrderRead.set(true);
                  return ApiResponse.ok(
                      new OrderClient.OrderView(
                          "order-1",
                          "after-paid".equals(path) && readNumber == 1
                              ? "PENDING_PAYMENT"
                              : "CANCELLED",
                          BigDecimal.TEN,
                          7L,
                          "SUCCEEDED"));
                })
            .when(orders)
            .paymentState("order-1", 7L);
        var service = newService(user, orders);
        String retry = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
        assertEquals(retry, service.process("pay-1"));
        assertEquals(retry, paymentStatus());
        assertEquals(retry, intentStatus());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertTrue(retryErrorPresent());
        assertNull(intentLeaseOwner());
        fixture.update(
            "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE"
                + " pay_no='pay-1'");
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "APPLIED", "10.00", "refund:pay-1")))
            .when(user)
            .operation(7L, "pay-1", 7L);
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "refund:pay-1", "REFUND", "APPLIED", "1E+1", null)))
            .when(user)
            .operation(7L, "refund:pay-1", 7L);
        assertEquals("REFUNDED", service.process("pay-1"));
        assertEquals("REFUNDED", paymentStatus());
        assertEquals("REFUNDED", intentStatus());
        assertEquals(2, intentAttempts());
        assertNull(intentLeaseOwner());
        assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
        assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
        assertEquals(
            1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
        verify(user, times(4)).operation(7L, "refund:pay-1", 7L);
        verify(user, never()).debit(any(), any(), any());
        verify(user, times("marker".equals(path) ? 0 : 1)).refund(any(), any(), any());
        verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
      }
    }
  }

  /** 三类退款入口当前订单正确取消时保持原REFUNDED，Order GET在终态事务外。 */
  @Test
  void latestValidOrderKeepsHealthyRefundedPathsWithRealPay() {
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "marker".equals(path)
              ? "refund-marker"
              : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
          "order-1",
          user,
          orders);
      var service = newService(user, orders);
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                int readNumber = reads.incrementAndGet();
                return ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1",
                        "after-paid".equals(path) && readNumber == 1
                            ? "PENDING_PAYMENT"
                            : "CANCELLED",
                        new BigDecimal("10.000"),
                        7L,
                        "SUCCEEDED"));
              })
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("after-paid".equals(path) ? 3 : 2, reads.get());
      assertEquals("REFUNDED", intentStatus());
      assertEquals("REFUNDED", paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orders, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
          .paid(any(), any());
      verify(user, org.mockito.Mockito.times(3)).operation(eq(7L), anyString(), eq(7L));
    }
  }

  /** 退款已发或marker已证明后补最新取消证据，同pay恢复不再发退款。 */
  @Test
  void latestOrderProofCanResumeAfterEvidenceRepairWithRealPay() {
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(
          "marker".equals(path)
              ? "refund-marker"
              : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
          "order-1",
          user,
          orders);
      var service = newService(user, orders);
      int expectedReads = "after-paid".equals(path) ? 3 : 2;
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                int readNumber = reads.incrementAndGet();
                String status =
                    readNumber < expectedReads
                        ? "after-paid".equals(path) && readNumber == 1
                            ? "PENDING_PAYMENT"
                            : "CANCELLED"
                        : "PAID";
                return ApiResponse.ok(
                    new OrderClient.OrderView("order-1", status, BigDecimal.TEN, 7L, "SUCCEEDED"));
              })
          .when(orders)
          .paymentState("order-1", 7L);
      String expectedRetry = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
      assertEquals(expectedRetry, service.process("pay-1"));
      assertEquals(expectedReads, reads.get());
      assertEquals(expectedRetry, intentStatus());
      assertEquals(expectedRetry, paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", "APPLIED", "10.000", "refund:pay-1")))
          .when(user)
          .operation(7L, "pay-1", 7L);
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")))
          .when(orders)
          .paymentState("order-1", 7L);
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", intentStatus());
      assertEquals("REFUNDED", paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
          .refund(any(), any(), any());
      verify(orders, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
          .paid(any(), any());
    }
  }

  /** 退款终态前需当前取消/库存证据，不能沿用User退款操作前的旧Order。 */
  @Test
  void latestOrderProofIsRequiredBeforeRefundedWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("marker", "post-refund", "after-paid")) {
      for (String variant :
          java.util.List.of(
              "pending",
              "paid",
              "completed",
              "stock-pending",
              "stock-unknown",
              "stock-null",
              "order-null",
              "order-other",
              "order-alias",
              "user-other",
              "amount-null",
              "amount-other",
              "null-body",
              "fault",
              "bad-envelope")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(
                  "marker".equals(path)
                      ? "refund-marker"
                      : "after-paid".equals(path) ? "after-paid" : "applied-cancel",
                  "order-1",
                  user,
                  orders);
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              int expectedReads = "after-paid".equals(path) ? 3 : 2;
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        int readNumber = reads.incrementAndGet();
                        if (readNumber < expectedReads)
                          return ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1",
                                  "after-paid".equals(path) && readNumber == 1
                                      ? "PENDING_PAYMENT"
                                      : "CANCELLED",
                                  BigDecimal.TEN,
                                  7L,
                                  "SUCCEEDED"));
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-order-recheck-unavailable");
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-null".equals(variant)
                                    ? null
                                    : "order-other".equals(variant)
                                        ? "order-other"
                                        : "order-alias".equals(variant) ? "ORDER-1" : "order-1",
                                "pending".equals(variant)
                                    ? "PENDING_PAYMENT"
                                    : "paid".equals(variant)
                                        ? "PAID"
                                        : "completed".equals(variant) ? "COMPLETED" : "CANCELLED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant)
                                        ? new BigDecimal("11.00")
                                        : new BigDecimal("10.000"),
                                "user-other".equals(variant) ? 8L : 7L,
                                "stock-pending".equals(variant)
                                    ? "PENDING"
                                    : "stock-unknown".equals(variant)
                                        ? "UNKNOWN"
                                        : "stock-null".equals(variant) ? null : "SUCCEEDED"));
                      })
                  .when(orders)
                  .paymentState("order-1", 7L);
              String expected = "marker".equals(path) ? "ORDER_SYNC_UNKNOWN" : "REFUND_PENDING";
              String result = newService(user, orders).process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(expected, result),
                  () -> assertEquals(expectedReads, reads.get()),
                  () ->
                      verify(user, org.mockito.Mockito.times("marker".equals(path) ? 0 : 1))
                          .refund(any(), any(), any()),
                  () ->
                      verify(orders, org.mockito.Mockito.times("after-paid".equals(path) ? 1 : 0))
                          .paid(any(), any()),
                  () -> verify(user, never()).debit(any(), any(), any()));
              assertEquals(expected, intentStatus());
              assertEquals(expected, paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 三类成功路径须观察两次原账本，最新正确APPLIED且无退款标记才成功。 */
  @Test
  void latestValidDebitKeepsHealthySuccessPathsWithRealPay() {
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(path, "order-1", user, orders);
      var service = newService(user, orders);
      if (!"paid".equals(path))
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                ApiResponse.ok(
                    new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      org.mockito.Mockito.doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                if (reads.incrementAndGet() == 1 && "fresh-debit".equals(path))
                  throw feign.FeignException.errorStatus(
                      "operation",
                      feign.Response.builder()
                          .status(404)
                          .reason("controlled-missing")
                          .request(
                              feign.Request.create(
                                  feign.Request.HttpMethod.GET,
                                  "http://cloud-mall-user/controlled-proof",
                                  java.util.Map.of(),
                                  null,
                                  java.nio.charset.StandardCharsets.UTF_8))
                          .headers(java.util.Map.of())
                          .body(new byte[0])
                          .build());
                return ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "APPLIED", "10.000", null));
              })
          .when(user)
          .operation(7L, "pay-1", 7L);
      assertEquals("SUCCESS", service.process("pay-1"));
      assertEquals(2, reads.get());
      assertEquals("SUCCESS", intentStatus());
      assertEquals("SUCCESS", paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      verify(user, org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
          .debit(any(), any(), any());
      verify(orders, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1)).paid(any(), any());
      verify(user, never()).refund(any(), any(), any());
    }
  }

  /** 末次账本缺证后，同支付补正确账本再恢复，不重发此前扣款。 */
  @Test
  void latestDebitProofCanResumeAfterEvidenceRepairWithRealPay() {
    String path = "fresh-debit";
    seedIntentAndPayment("PENDING", "INIT");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath(path, "order-1", user, orders);
    var service = newService(user, orders);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              reads.incrementAndGet();
              throw feign.FeignException.errorStatus(
                  "operation",
                  feign.Response.builder()
                      .status(404)
                      .reason("controlled-missing")
                      .request(
                          feign.Request.create(
                              feign.Request.HttpMethod.GET,
                              "http://cloud-mall-user/controlled-proof",
                              java.util.Map.of(),
                              null,
                              java.nio.charset.StandardCharsets.UTF_8))
                      .headers(java.util.Map.of())
                      .body(new byte[0])
                      .build());
            })
        .when(user)
        .operation(7L, "pay-1", 7L);
    assertEquals("ORDER_SYNC_UNKNOWN", service.process("pay-1"));
    assertEquals(2, reads.get());
    assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
    assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
    assertEquals(1, intentAttempts());
    assertNull(intentLeaseOwner());
    fixture.update(
        "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE pay_no='pay-1'");
    org.mockito.Mockito.doReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
        .when(user)
        .operation(7L, "pay-1", 7L);
    when(orders.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
    assertEquals("SUCCESS", service.process("pay-1"));
    assertEquals("SUCCESS", intentStatus());
    assertEquals("SUCCESS", paymentStatus());
    assertEquals(2, intentAttempts());
    assertNull(intentLeaseOwner());
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
    assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
    verify(user).debit(any(), any(), any());
    verify(orders).paid(any(), any());
    verify(user, org.mockito.Mockito.times(4)).operation(7L, "pay-1", 7L);
    verify(user, never()).refund(any(), any(), any());
  }

  /** 成功前的最新原扣款缺证/矛盾不能被较早读或POST确认覆盖。 */
  @Test
  void latestDebitProofIsRequiredBeforeSuccessWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path : java.util.List.of("paid", "after-paid", "fresh-debit")) {
      for (String variant :
          java.util.List.of(
              "missing",
              "null-envelope",
              "null-body",
              "bad-envelope",
              "fault",
              "key-null",
              "key-other",
              "key-alias",
              "type",
              "amount-null",
              "amount-other",
              "rejected",
              "state-null",
              "marker",
              "marker-blank")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(path, "order-1", user, orders);
              if (!"paid".equals(path))
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
              var reads = new java.util.concurrent.atomic.AtomicInteger();
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        org.junit.jupiter.api.Assertions.assertFalse(
                            org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive());
                        if (reads.incrementAndGet() == 1) {
                          if ("fresh-debit".equals(path))
                            throw feign.FeignException.errorStatus(
                                "operation",
                                feign.Response.builder()
                                    .status(404)
                                    .reason("controlled-missing")
                                    .request(
                                        feign.Request.create(
                                            feign.Request.HttpMethod.GET,
                                            "http://cloud-mall-user/controlled-proof",
                                            java.util.Map.of(),
                                            null,
                                            java.nio.charset.StandardCharsets.UTF_8))
                                    .headers(java.util.Map.of())
                                    .body(new byte[0])
                                    .build());
                          return ApiResponse.ok(
                              new UserClient.BalanceOperationView(
                                  "pay-1", "DEBIT", "APPLIED", "10.00", null));
                        }
                        if ("missing".equals(variant))
                          throw feign.FeignException.errorStatus(
                              "operation",
                              feign.Response.builder()
                                  .status(404)
                                  .reason("controlled-missing")
                                  .request(
                                      feign.Request.create(
                                          feign.Request.HttpMethod.GET,
                                          "http://cloud-mall-user/controlled-proof",
                                          java.util.Map.of(),
                                          null,
                                          java.nio.charset.StandardCharsets.UTF_8))
                                  .headers(java.util.Map.of())
                                  .body(new byte[0])
                                  .build());
                        if ("fault".equals(variant))
                          throw new IllegalStateException("controlled-user-recheck-unavailable");
                        if ("null-envelope".equals(variant)) return null;
                        if ("null-body".equals(variant)) return ApiResponse.ok(null);
                        if ("bad-envelope".equals(variant))
                          return ApiResponse.error("CONTROLLED_BAD_RESPONSE", "controlled");
                        return ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "key-null".equals(variant)
                                    ? null
                                    : "key-other".equals(variant)
                                        ? "pay-other"
                                        : "key-alias".equals(variant) ? "PAY-1" : "pay-1",
                                "type".equals(variant) ? "REFUND" : "DEBIT",
                                "rejected".equals(variant)
                                    ? "REJECTED"
                                    : "state-null".equals(variant) ? null : "APPLIED",
                                "amount-null".equals(variant)
                                    ? null
                                    : "amount-other".equals(variant) ? "11.00" : "10.000",
                                "marker".equals(variant)
                                    ? "refund:pay-1"
                                    : "marker-blank".equals(variant) ? "" : null));
                      })
                  .when(user)
                  .operation(7L, "pay-1", 7L);
              String result = newService(user, orders).process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals("ORDER_SYNC_UNKNOWN", result),
                  () -> assertEquals(2, reads.get()),
                  () ->
                      verify(user, org.mockito.Mockito.times("fresh-debit".equals(path) ? 1 : 0))
                          .debit(any(), any(), any()),
                  () ->
                      verify(orders, org.mockito.Mockito.times("paid".equals(path) ? 0 : 1))
                          .paid(any(), any()),
                  () -> verify(user, never()).refund(any(), any(), any()));
              assertEquals("ORDER_SYNC_UNKNOWN", intentStatus());
              assertEquals("ORDER_SYNC_UNKNOWN", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 六组退款方向在取消和库存完成时保原退款或已退款marker恢复。 */
  @Test
  void completedCancellationKeepsRefundAndMarkerRecoveryWithRealPay() {
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if (!"REFUND_PENDING".equals(originalStatus) && !"REFUND_PENDING".equals(paymentStatus))
          continue;
        for (boolean markerPresent : java.util.List.of(false, true)) {
          seedIntentAndPayment(paymentStatus, originalStatus);
          UserClient user = mock(UserClient.class);
          OrderClient orders = mock(OrderClient.class);
          configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
          org.mockito.Mockito.doReturn(
                  ApiResponse.ok(
                      new UserClient.BalanceOperationView(
                          "pay-1",
                          "DEBIT",
                          "APPLIED",
                          "10.000",
                          markerPresent ? "refund:pay-1" : null)))
              .when(user)
              .operation(7L, "pay-1", 7L);
          assertEquals("REFUNDED", newService(user, orders).process("pay-1"));
          assertEquals("REFUNDED", intentStatus());
          assertEquals("REFUNDED", paymentStatus());
          assertEquals(1, intentAttempts());
          assertNull(intentLeaseOwner());
          verify(user, never()).debit(any(), any(), any());
          verify(user, org.mockito.Mockito.times(markerPresent ? 0 : 1))
              .refund(any(), any(), any());
          verify(orders, never()).paid(any(), any());
        }
      }
    }
  }

  /** 取消/回滚证据补齐后，同支付下一次恢复只执行原退款一次。 */
  @Test
  void refundRecoveryCanResumeAfterCancellationEvidenceWithRealPay() {
    for (String paymentStatus : java.util.List.of("PENDING", "REFUND_PENDING")) {
      String originalStatus = "PENDING".equals(paymentStatus) ? "REFUND_PENDING" : "INIT";
      seedIntentAndPayment(paymentStatus, originalStatus);
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
      var service = newService(user, orders);
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("REFUND_PENDING", service.process("pay-1"));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      assertEquals("REFUND_PENDING", intentStatus());
      assertEquals(paymentStatus, paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "CANCELLED", new BigDecimal("10.000"), 7L, "SUCCEEDED")));
      assertEquals("REFUNDED", service.process("pay-1"));
      assertEquals("REFUNDED", intentStatus());
      assertEquals("REFUNDED", paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      assertEquals(
          new BigDecimal("10.00"),
          fixture.queryForObject("SELECT amount FROM pay_record", BigDecimal.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 已记录退款方向但取消/库存证据不完整时，不得回到正向支付或写阶段。 */
  @Test
  void refundRecoveryRequiresCancelledOrderAndCompletedStockWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if (!"REFUND_PENDING".equals(originalStatus) && !"REFUND_PENDING".equals(paymentStatus))
          continue;
        for (String orderStatus :
            java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
          for (String stockAction :
              java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
            if ("CANCELLED".equals(orderStatus) && "SUCCEEDED".equals(stockAction)) continue;
            assertions.add(
                () -> {
                  seedIntentAndPayment(paymentStatus, originalStatus);
                  var before = payFacts();
                  UserClient user = mock(UserClient.class);
                  OrderClient orders = mock(OrderClient.class);
                  configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
                  when(orders.paymentState("order-1", 7L))
                      .thenReturn(
                          ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1",
                                  orderStatus,
                                  new BigDecimal("10.000"),
                                  7L,
                                  stockAction)));
                  String result = newService(user, orders).process("pay-1");
                  org.junit.jupiter.api.Assertions.assertAll(
                      () -> assertEquals("REFUND_PENDING", result),
                      () -> verify(orders, never()).paid(any(), any()),
                      () -> verify(user, never()).debit(any(), any(), any()),
                      () -> verify(user, never()).refund(any(), any(), any()));
                  assertEquals("REFUND_PENDING", intentStatus());
                  assertEquals(paymentStatus, paymentStatus());
                  assertEquals(1, intentAttempts());
                  assertNull(intentLeaseOwner());
                  assertNotNull(intentNextRetry());
                  assertTrue(
                      fixture
                          .queryForObject(
                              "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                              String.class)
                          .contains("退款恢复阶段缺少订单取消或库存回滚证据"));
                  assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
                });
          }
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 正常拒绝仍FAILED，冲突来源修正后同支付可恢复成功/退款/初始拒绝。 */
  @Test
  void rejectedDebitKeepsHealthyFailureAndCanResumeAfterRepairWithRealPay() {
    for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("rejected", "order-1", user, orders);
      var service = newService(user, orders);
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", orderStatus, BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("FAILED", service.process("pay-1"));
      assertEquals("FAILED", intentStatus());
      assertEquals("FAILED", paymentStatus());
      assertNull(intentLeaseOwner());
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
    for (String scenario : java.util.List.of("advanced-success", "advanced-refund", "marker")) {
      boolean refundPending = "advanced-refund".equals(scenario);
      boolean markerOnly = "marker".equals(scenario);
      String originalStatus = refundPending ? "REFUND_PENDING" : markerOnly ? "INIT" : "DEBITED";
      String paymentStatus = refundPending ? "REFUND_PENDING" : "PENDING";
      seedIntentAndPayment(paymentStatus, originalStatus);
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("rejected", "order-1", user, orders);
      var service = newService(user, orders);
      if (markerOnly)
        org.mockito.Mockito.doReturn(
                ApiResponse.ok(
                    new UserClient.BalanceOperationView(
                        "pay-1", "DEBIT", "REJECTED", "10.000", "refund:pay-1")))
            .when(user)
            .operation(7L, "pay-1", 7L);
      String expectedRetry =
          refundPending ? "REFUND_PENDING" : markerOnly ? "INIT" : "ORDER_SYNC_UNKNOWN";
      assertEquals(expectedRetry, service.process("pay-1"));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      assertEquals(expectedRetry, intentStatus());
      assertEquals("INIT".equals(expectedRetry) ? "PENDING" : expectedRetry, paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", markerOnly ? "REJECTED" : "APPLIED", "10.000", null)))
          .when(user)
          .operation(7L, "pay-1", 7L);
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      refundPending || markerOnly ? "CANCELLED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      String expectedTerminal = refundPending ? "REFUNDED" : markerOnly ? "FAILED" : "SUCCESS";
      assertEquals(expectedTerminal, service.process("pay-1"));
      assertEquals(expectedTerminal, intentStatus());
      assertEquals(expectedTerminal, paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      assertEquals(
          new BigDecimal("10.00"),
          fixture.queryForObject("SELECT amount FROM pay_record", BigDecimal.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times(refundPending ? 1 : 0)).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 扣款拒绝与已推进阶段矛盾时，不能直接将支付恢复抹为FAILED。 */
  @Test
  void rejectedDebitCannotEraseAdvancedRecoveryWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String paymentStatus :
          java.util.List.of("PENDING", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        if ("INIT".equals(originalStatus) && "PENDING".equals(paymentStatus)) continue;
        for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
          assertions.add(
              () -> {
                seedIntentAndPayment(paymentStatus, originalStatus);
                var before = payFacts();
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath("rejected", "order-1", user, orders);
                org.mockito.Mockito.doReturn(
                        ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1", "DEBIT", "REJECTED", "10.000", null)))
                    .when(user)
                    .operation(7L, "pay-1", 7L);
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                "SUCCEEDED")));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                            || "REFUND_PENDING".equals(paymentStatus)
                        ? "REFUND_PENDING"
                        : "DEBITED".equals(originalStatus)
                                || "ORDER_SYNC_UNKNOWN".equals(originalStatus)
                                || "ORDER_SYNC_UNKNOWN".equals(paymentStatus)
                            ? "ORDER_SYNC_UNKNOWN"
                            : "INIT";
                assertEquals(expected, newService(user, orders).process("pay-1"));
                verify(user, never()).debit(any(), any(), any());
                verify(user, never()).refund(any(), any(), any());
                verify(orders, never()).paid(any(), any());
                assertEquals(expected, intentStatus());
                assertEquals(
                    "ORDER_SYNC_UNKNOWN".equals(expected) ? "ORDER_SYNC_UNKNOWN" : paymentStatus,
                    paymentStatus());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertNotNull(intentNextRetry());
                assertTrue(
                    fixture
                        .queryForObject(
                            "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                            String.class)
                        .contains("扣款拒绝事实与资金恢复状态不一致"));
                assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** REJECTED携任意非NULL退款标记属于冲突来源，不能自动FAILED。 */
  @Test
  void rejectedDebitCannotContainRefundMarkerWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    String originalStatus = "INIT";
    String paymentStatus = "PENDING";
    for (String refundMarker : java.util.List.of("refund:pay-1", "refund-other", "", " ")) {
      for (String orderStatus : java.util.List.of("PENDING_PAYMENT", "CANCELLED")) {
        assertions.add(
            () -> {
              seedIntentAndPayment(paymentStatus, originalStatus);
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath("rejected", "order-1", user, orders);
              org.mockito.Mockito.doReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceOperationView(
                              "pay-1", "DEBIT", "REJECTED", "10.000", refundMarker)))
                  .when(user)
                  .operation(7L, "pay-1", 7L);
              when(orders.paymentState("order-1", 7L))
                  .thenReturn(
                      ApiResponse.ok(
                          new OrderClient.OrderView(
                              "order-1", orderStatus, new BigDecimal("10.000"), 7L, "SUCCEEDED")));
              String expected =
                  "REFUND_PENDING".equals(originalStatus) || "REFUND_PENDING".equals(paymentStatus)
                      ? "REFUND_PENDING"
                      : "DEBITED".equals(originalStatus)
                              || "ORDER_SYNC_UNKNOWN".equals(originalStatus)
                              || "ORDER_SYNC_UNKNOWN".equals(paymentStatus)
                          ? "ORDER_SYNC_UNKNOWN"
                          : "INIT";
              assertEquals(expected, newService(user, orders).process("pay-1"));
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, never()).paid(any(), any());
              assertEquals(expected, intentStatus());
              assertEquals(
                  "ORDER_SYNC_UNKNOWN".equals(expected) ? "ORDER_SYNC_UNKNOWN" : paymentStatus,
                  paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(
                  fixture
                      .queryForObject(
                          "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                          String.class)
                      .contains("扣款拒绝事实与资金恢复状态不一致"));
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** INIT与支付记录阶段错位缺证后，补原账本仍可沿同支付恢复。 */
  @Test
  void paymentProjectionCanResumeAfterOriginalDebitWithRealPay() {
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      seedIntentAndPayment(paymentStatus, "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
      var service = newService(user, orders);
      boolean refundPending = "REFUND_PENDING".equals(paymentStatus);
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      refundPending ? "CANCELLED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      assertEquals(paymentStatus, service.process("pay-1"));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      assertEquals(paymentStatus, intentStatus());
      assertEquals(paymentStatus, paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(user)
          .operation(7L, "pay-1", 7L);
      String expectedTerminal = refundPending ? "REFUNDED" : "SUCCESS";
      assertEquals(expectedTerminal, service.process("pay-1"));
      assertEquals(expectedTerminal, intentStatus());
      assertEquals(expectedTerminal, paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      assertEquals(
          new BigDecimal("10.00"),
          fixture.queryForObject("SELECT amount FROM pay_record", BigDecimal.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times(refundPending ? 1 : 0)).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** INIT意图不能覆盖已推进的支付记录来源；缺账本只保原重试方向。 */
  @Test
  void paymentProjectionPreventsFreshFundsWhenDebitMissingWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String orderStatus :
          java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
        for (String stockAction :
            java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
          assertions.add(
              () -> {
                seedIntentAndPayment(paymentStatus, "INIT");
                var before = payFacts();
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String result = newService(user, orders).process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(paymentStatus, result),
                    () -> verify(user, never()).debit(any(), any(), any()),
                    () -> verify(orders, never()).paid(any(), any()),
                    () -> verify(user, never()).refund(any(), any(), any()));
                assertEquals(paymentStatus, intentStatus());
                assertEquals(paymentStatus, paymentStatus());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertNotNull(intentNextRetry());
                assertTrue(retryErrorPresent());
                assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 原支付记录的等待方向在账本/订单故障及APPLIED之后失败时仍须保留。 */
  @Test
  void paymentProjectionRetryDirectionSurvivesReadFailuresWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String paymentStatus : java.util.List.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String failurePoint : java.util.List.of("user", "order", "bad-debit", "stock-pending")) {
        assertions.add(
            () -> {
              seedIntentAndPayment(paymentStatus, "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
              if ("user".equals(failurePoint))
                org.mockito.Mockito.doThrow(
                        new IllegalStateException("controlled-user-read-failure"))
                    .when(user)
                    .operation(7L, "pay-1", 7L);
              else {
                org.mockito.Mockito.doReturn(
                        ApiResponse.ok(
                            new UserClient.BalanceOperationView(
                                "pay-1",
                                "DEBIT",
                                "APPLIED",
                                "bad-debit".equals(failurePoint) ? "11.00" : "10.000",
                                null)))
                    .when(user)
                    .operation(7L, "pay-1", 7L);
                if ("order".equals(failurePoint))
                  when(orders.paymentState("order-1", 7L))
                      .thenThrow(new IllegalStateException("controlled-order-read-failure"));
                else
                  when(orders.paymentState("order-1", 7L))
                      .thenReturn(
                          ApiResponse.ok(
                              new OrderClient.OrderView(
                                  "order-1", "PAID", BigDecimal.TEN, 7L, "UNKNOWN")));
              }
              String result = newService(user, orders).process("pay-1");
              assertEquals(paymentStatus, result);
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, never()).paid(any(), any());
              assertEquals(paymentStatus, intentStatus());
              assertEquals(paymentStatus, paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(retryErrorPresent());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 推进阶段缺证后补原账本，下一次同支付恢复成功或完成原退款。 */
  @Test
  void advancedRecoveryResumesAfterOriginalDebitProofWithRealPay() {
    for (String originalStatus :
        java.util.List.of("DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      seedIntentAndPayment(
          "REFUND_PENDING".equals(originalStatus) ? "REFUND_PENDING" : "PENDING", originalStatus);
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
      var service = newService(user, orders);
      boolean refundPending = "REFUND_PENDING".equals(originalStatus);
      String orderStatus =
          refundPending ? "CANCELLED" : "DEBITED".equals(originalStatus) ? "PAID" : "COMPLETED";
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", orderStatus, new BigDecimal("10.000"), 7L, "SUCCEEDED")));
      String expectedRetry = refundPending ? "REFUND_PENDING" : "ORDER_SYNC_UNKNOWN";
      assertEquals(expectedRetry, service.process("pay-1"));
      verify(user, never()).debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
      assertEquals(expectedRetry, intentStatus());
      assertEquals(expectedRetry, paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(user)
          .operation(7L, "pay-1", 7L);
      String expectedTerminal = refundPending ? "REFUNDED" : "SUCCESS";
      assertEquals(expectedTerminal, service.process("pay-1"));
      assertEquals(expectedTerminal, intentStatus());
      assertEquals(expectedTerminal, paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      assertEquals(
          new BigDecimal("10.00"),
          fixture.queryForObject("SELECT amount FROM pay_record", BigDecimal.class));
      verify(user, never()).debit(any(), any(), any());
      verify(user, org.mockito.Mockito.times(refundPending ? 1 : 0)).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 已推进资金阶段不能将缺失账本当作未扣款；只保留原方向重试。 */
  @Test
  void missingDebitProofPreservesAdvancedRecoveryWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String originalStatus :
        java.util.List.of("DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      for (String orderStatus :
          java.util.List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
        for (String stockAction :
            java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
          assertions.add(
              () -> {
                seedIntentAndPayment(
                    "REFUND_PENDING".equals(originalStatus) ? "REFUND_PENDING" : "PENDING",
                    originalStatus);
                var before = payFacts();
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                        ? "REFUND_PENDING"
                        : "ORDER_SYNC_UNKNOWN";
                String result = newService(user, orders).process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(expected, result),
                    () -> verify(user, never()).debit(any(), any(), any()),
                    () -> verify(orders, never()).paid(any(), any()),
                    () -> verify(user, never()).refund(any(), any(), any()));
                assertEquals(expected, intentStatus());
                assertEquals(expected, paymentStatus());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertNotNull(intentNextRetry());
                assertTrue(
                    fixture
                        .queryForObject(
                            "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                            String.class)
                        .contains("已有资金恢复状态但原扣款账本缺失"));
                assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 原支付缺证后读到正确APPLIED账本可恢复成功，不再次发扣款。 */
  @Test
  void originalDebitProofCanResumeWithoutRepeatingFundsWithRealPay() {
    for (String scenario : java.util.List.of("PAID", "COMPLETED", "bad-ack")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
      var service = newService(user, orders);
      if ("bad-ack".equals(scenario))
        when(user.debit(eq(7L), eq(7L), any()))
            .thenReturn(
                ApiResponse.ok(new UserClient.BalanceView("pay-other", "APPLIED", "90.00")));
      else
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", scenario, BigDecimal.TEN, 7L, "SUCCEEDED")));
      assertEquals("INIT", service.process("pay-1"));
      assertEquals("INIT", intentStatus());
      assertEquals("PENDING", paymentStatus());
      assertEquals(1, intentAttempts());
      assertNull(intentLeaseOwner());
      fixture.update(
          "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 MINUTE WHERE"
              + " pay_no='pay-1'");
      org.mockito.Mockito.doReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView("pay-1", "DEBIT", "APPLIED", "10.000", null)))
          .when(user)
          .operation(7L, "pay-1", 7L);
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1",
                      "COMPLETED".equals(scenario) ? "COMPLETED" : "PAID",
                      new BigDecimal("10.000"),
                      7L,
                      "SUCCEEDED")));
      assertEquals("SUCCESS", service.process("pay-1"));
      assertEquals("SUCCESS", intentStatus());
      assertEquals("SUCCESS", paymentStatus());
      assertEquals(2, intentAttempts());
      assertNull(intentLeaseOwner());
      assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM pay_debit_intent", Integer.class));
      assertEquals("pay-1", fixture.queryForObject("SELECT pay_no FROM pay_record", String.class));
      assertEquals(
          new BigDecimal("10.00"),
          fixture.queryForObject("SELECT amount FROM pay_record", BigDecimal.class));
      verify(user, org.mockito.Mockito.times("bad-ack".equals(scenario) ? 1 : 0))
          .debit(any(), any(), any());
      verify(user, never()).refund(any(), any(), any());
      verify(orders, never()).paid(any(), any());
    }
  }

  /** 未观察当前支付扣款时，已支付订单不得借终态再触发新扣款。 */
  @Test
  void paidOrderWithoutDebitCannotStartNewFundsWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String orderStatus : java.util.List.of("PAID", "COMPLETED")) {
      for (String stockAction : java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
        for (String originalStatus :
            java.util.List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
          assertions.add(
              () -> {
                seedIntentAndPayment(
                    "REFUND_PENDING".equals(originalStatus) ? "REFUND_PENDING" : "PENDING",
                    originalStatus);
                var before = payFacts();
                UserClient user = mock(UserClient.class);
                OrderClient orders = mock(OrderClient.class);
                configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1",
                                orderStatus,
                                new BigDecimal("10.000"),
                                7L,
                                stockAction)));
                String expected =
                    "REFUND_PENDING".equals(originalStatus)
                        ? "REFUND_PENDING"
                        : "INIT".equals(originalStatus) ? "INIT" : "ORDER_SYNC_UNKNOWN";
                String errorMessage =
                    "INIT".equals(originalStatus) ? "订单已支付但未观察到原扣款事实" : "已有资金恢复状态但原扣款账本缺失";
                String result = newService(user, orders).process("pay-1");
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(expected, result),
                    () -> verify(user, org.mockito.Mockito.times(0)).debit(any(), any(), any()),
                    () -> verify(orders, never()).paid(any(), any()),
                    () -> verify(user, never()).refund(any(), any(), any()));
                assertEquals(expected, intentStatus());
                assertEquals(
                    "REFUND_PENDING".equals(expected)
                        ? "REFUND_PENDING"
                        : "INIT".equals(expected) ? "PENDING" : "ORDER_SYNC_UNKNOWN",
                    paymentStatus());
                assertEquals(1, intentAttempts());
                assertNull(intentLeaseOwner());
                assertNotNull(intentNextRetry());
                assertTrue(
                    fixture
                        .queryForObject(
                            "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                            String.class)
                        .contains(errorMessage));
                assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 扣款回执必须属于原支付，坏回执不能推进订单或本地终态。 */
  @Test
  void debitAcknowledgementMustNameOriginalPaymentWithRealPay() {
    var assertions = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String acknowledgementKey :
        java.util.Arrays.asList(
            null, "", " ", "pay-other", "PAY-1", "páy-1", "pay-1 ", "refund:pay-1")) {
      for (String originalStatus : java.util.List.of("INIT")) {
        assertions.add(
            () -> {
              seedIntentAndPayment(
                  "REFUND_PENDING".equals(originalStatus) ? "REFUND_PENDING" : "PENDING",
                  originalStatus);
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath("fresh-debit", "order-1", user, orders);
              when(user.debit(eq(7L), eq(7L), any()))
                  .thenReturn(
                      ApiResponse.ok(
                          new UserClient.BalanceView(acknowledgementKey, "APPLIED", "90.00")));
              String expected =
                  "REFUND_PENDING".equals(originalStatus)
                      ? "REFUND_PENDING"
                      : "INIT".equals(originalStatus) ? "INIT" : "ORDER_SYNC_UNKNOWN";
              String errorMessage = "用户余额扣款未确认";
              String result = newService(user, orders).process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(expected, result),
                  () -> verify(user, org.mockito.Mockito.times(1)).debit(any(), any(), any()),
                  () -> verify(orders, never()).paid(any(), any()),
                  () -> verify(user, never()).refund(any(), any(), any()));
              assertEquals(expected, intentStatus());
              assertEquals(
                  "REFUND_PENDING".equals(expected)
                      ? "REFUND_PENDING"
                      : "INIT".equals(expected) ? "PENDING" : "ORDER_SYNC_UNKNOWN",
                  paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              assertTrue(
                  fixture
                      .queryForObject(
                          "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'",
                          String.class)
                      .contains(errorMessage));
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 未知订单状态在七条恢复决策中都不能触发新资金写或支付终态。 */
  @Test
  void unknownOrderStatusCannotStartFundsOrFinishRealPayment() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path :
        List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      for (String variant :
          java.util.Arrays.asList(
              null,
              "",
              "UNKNOWN",
              "CLOSED",
              "paid",
              "PAID ",
              "COMPLETED ",
              "PENDING_PAYMENT ",
              "CANCELLED\n",
              "páid")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(path, "order-1", user, orders);
              var response =
                  ApiResponse.ok(
                      new OrderClient.OrderView(
                          "order-1", variant, new BigDecimal("10.000"), 7L, "SUCCEEDED"));
              if ("after-paid".equals(path))
                when(orders.paymentState("order-1", 7L))
                    .thenReturn(
                        ApiResponse.ok(
                            new OrderClient.OrderView(
                                "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
                        response);
              else when(orders.paymentState("order-1", 7L)).thenReturn(response);
              String expected =
                  List.of("no-debit-cancel", "fresh-debit", "rejected").contains(path)
                      ? "INIT"
                      : "ORDER_SYNC_UNKNOWN";
              String result = newService(user, orders).process("pay-1");
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(expected, result, path + ":" + variant),
                  () -> verify(user, never()).debit(any(), any(), any()),
                  () -> assertNotEquals("FAILED", paymentStatus()));
              assertEquals(expected, intentStatus());
              assertEquals(
                  "INIT".equals(expected) ? "PENDING" : "ORDER_SYNC_UNKNOWN", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              String error =
                  fixture.queryForObject(
                      "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class);
              assertTrue(error.contains("订单支付状态暂不可判定"));
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 未知订单读只记录退款等待重试，不终结真实Pay记录或重发资金RPC。 */
  @Test
  void unknownOrderStatusKeepsRealRefundPendingDirection() {
    seedIntentAndPayment("REFUND_PENDING", "REFUND_PENDING");
    var before = payFacts();
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
    when(orders.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "UNKNOWN", BigDecimal.TEN, 7L, "SUCCEEDED")));
    assertEquals("REFUND_PENDING", newService(user, orders).process("pay-1"));
    assertEquals("REFUND_PENDING", paymentStatus());
    assertEquals("REFUND_PENDING", intentStatus());
    assertEquals(1, intentAttempts());
    assertNull(intentLeaseOwner());
    assertNotNull(intentNextRetry());
    assertTrue(
        fixture
            .queryForObject(
                "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class)
            .contains("订单支付状态暂不可判定"));
    assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    verify(orders, never()).paid(any(), any());
  }

  /** 正常PAID/COMPLETED仅在库存动作成功时结清，否则真实两表继续等待。 */
  @Test
  void recognizedPaidStatesKeepRealStockCompletionGate() {
    for (String status : List.of("PAID", "COMPLETED")) {
      for (String stockAction : java.util.Arrays.asList("SUCCEEDED", "PENDING", "UNKNOWN", null)) {
        seedIntentAndPayment("PENDING", "INIT");
        UserClient user = mock(UserClient.class);
        OrderClient orders = mock(OrderClient.class);
        configureRemoteOrderIdentityPath("paid", "order-1", user, orders);
        when(orders.paymentState("order-1", 7L))
            .thenReturn(
                ApiResponse.ok(
                    new OrderClient.OrderView(
                        "order-1", status, new BigDecimal("10.000"), 7L, stockAction)));
        String expected = "SUCCEEDED".equals(stockAction) ? "SUCCESS" : "ORDER_SYNC_UNKNOWN";
        assertEquals(expected, newService(user, orders).process("pay-1"));
        assertEquals(expected, paymentStatus());
        assertEquals(expected, intentStatus());
        assertNull(intentLeaseOwner());
        verify(user, never()).debit(any(), any(), any());
        verify(user, never()).refund(any(), any(), any());
        verify(orders, never()).paid(any(), any());
      }
    }
  }

  /** 七种决策的错订单读都只能保留原retry，真实两表不能被该返回结清。 */
  @Test
  void wrongOrderIdentityCannotSettleOrStartFundsWithRealMappers() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String path :
        List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      for (String variant :
          List.of("other", "null", "blank", "case-alias", "accent-alias", "space-alias")) {
        assertions.add(
            () -> {
              seedIntentAndPayment("PENDING", "INIT");
              var before = payFacts();
              UserClient user = mock(UserClient.class);
              OrderClient orders = mock(OrderClient.class);
              configureRemoteOrderIdentityPath(path, wrongRemoteOrderNumber(variant), user, orders);
              String expected =
                  List.of("no-debit-cancel", "fresh-debit", "rejected").contains(path)
                      ? "INIT"
                      : "ORDER_SYNC_UNKNOWN";
              assertEquals(
                  expected, newService(user, orders).process("pay-1"), path + ":" + variant);
              assertEquals(expected, intentStatus());
              assertEquals(
                  "INIT".equals(expected) ? "PENDING" : "ORDER_SYNC_UNKNOWN", paymentStatus());
              assertEquals(1, intentAttempts());
              assertNull(intentLeaseOwner());
              assertNotNull(intentNextRetry());
              String error =
                  fixture.queryForObject(
                      "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class);
              assertTrue(error.contains("订单身份、归属或金额与支付意图不一致"));
              verify(user, never()).debit(any(), any(), any());
              verify(user, never()).refund(any(), any(), any());
              verify(orders, times("after-paid".equals(path) ? 1 : 0)).paid(any(), any());
              assertEquals(immutableRecoveryFacts(before), immutableRecoveryFacts(payFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 同order身份的七条链真实落终态，已退款marker不重新退款。 */
  @Test
  void exactRemoteOrderIdentityKeepsHealthyRealTerminalDecisions() {
    for (String path :
        List.of(
            "no-debit-cancel",
            "fresh-debit",
            "rejected",
            "applied-cancel",
            "paid",
            "refund-marker",
            "after-paid")) {
      seedIntentAndPayment("PENDING", "INIT");
      UserClient user = mock(UserClient.class);
      OrderClient orders = mock(OrderClient.class);
      configureRemoteOrderIdentityPath(path, "order-1", user, orders);
      String expected =
          switch (path) {
            case "no-debit-cancel", "rejected" -> "FAILED";
            case "fresh-debit", "paid" -> "SUCCESS";
            default -> "REFUNDED";
          };
      assertEquals(expected, newService(user, orders).process("pay-1"));
      assertEquals(expected, paymentStatus());
      assertEquals(expected, intentStatus());
      assertNull(intentLeaseOwner());
      if ("fresh-debit".equals(path)) verify(user).debit(eq(7L), eq(7L), any());
      else verify(user, never()).debit(any(), any(), any());
      if (List.of("applied-cancel", "after-paid").contains(path))
        verify(user).refund(eq(7L), eq(7L), any());
      else verify(user, never()).refund(any(), any(), any());
      verify(orders, times(List.of("fresh-debit", "after-paid").contains(path) ? 1 : 0))
          .paid(any(), any());
    }
  }

  /** 退款等待方向遇错order保留原retry，不因此变为初始或结清。 */
  @Test
  void wrongOrderIdentityRetainsActualRefundPendingIntent() {
    seedIntentAndPayment("REFUND_PENDING", "REFUND_PENDING");
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    configureRemoteOrderIdentityPath("applied-cancel", "order-other", user, orders);
    assertEquals("REFUND_PENDING", newService(user, orders).process("pay-1"));
    assertEquals("REFUND_PENDING", intentStatus());
    assertEquals("REFUND_PENDING", paymentStatus());
    assertTrue(retryErrorPresent());
    assertTrue(
        fixture
            .queryForObject(
                "SELECT last_error FROM pay_debit_intent WHERE pay_no='pay-1'", String.class)
            .contains("订单身份、归属或金额与支付意图不一致"));
    assertNull(intentLeaseOwner());
    verify(user, never()).refund(any(), any(), any());
    verify(user, never()).debit(any(), any(), any());
    verify(orders, never()).paid(any(), any());
  }

  /** 保存原资金身份、金额、paid_at及创建时间；只排除允许变化的retry/stage字段。 */
  private static List<List<Map<String, Object>>> immutableRecoveryFacts(
      List<List<Map<String, Object>>> facts) {
    var immutable = new ArrayList<List<Map<String, Object>>>();
    for (int tableIndex = 0; tableIndex < facts.size(); tableIndex++) {
      var rows = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> original : facts.get(tableIndex)) {
        var row = new java.util.HashMap<>(original);
        for (String field : List.of("status", "updated_at")) row.remove(field);
        if (tableIndex == 1)
          for (String field :
              List.of("attempts", "next_retry_at", "lease_owner", "lease_until", "last_error"))
            row.remove(field);
        rows.add(row);
      }
      immutable.add(rows);
    }
    return immutable;
  }

  /** Remote仅替身，真实PayMapper；只破坏订单号，正金额尾零和其它事实保持。 */
  private static void configureRemoteOrderIdentityPath(
      String path, String returnedOrderNo, UserClient user, OrderClient orders) {
    if ("no-debit-cancel".equals(path)) {
      stubRefundOperation(user, "not-found", "pay-1", "10.00");
    } else if ("fresh-debit".equals(path)) {
      Request request =
          Request.create(
              Request.HttpMethod.GET,
              "http://cloud-mall-user/controlled-proof",
              Map.of(),
              null,
              StandardCharsets.UTF_8);
      var missing =
          FeignException.errorStatus(
              "operation",
              feign.Response.builder()
                  .status(404)
                  .reason("controlled-missing")
                  .request(request)
                  .headers(Map.of())
                  .body(new byte[0])
                  .build());
      when(user.operation(7L, "pay-1", 7L))
          .thenThrow(missing)
          .thenReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1", "DEBIT", "APPLIED", "10.000", null)));
    } else
      when(user.operation(7L, "pay-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new UserClient.BalanceOperationView(
                      "pay-1",
                      "DEBIT",
                      "rejected".equals(path) ? "REJECTED" : "APPLIED",
                      "10.00",
                      "refund-marker".equals(path) ? "refund:pay-1" : null)));
    when(user.operation(7L, "refund:pay-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new UserClient.BalanceOperationView(
                    "refund:pay-1", "REFUND", "APPLIED", "10.00", null)));
    when(user.debit(eq(7L), eq(7L), any()))
        .thenReturn(ApiResponse.ok(new UserClient.BalanceView("pay-1", "APPLIED", "90.00")));
    when(user.refund(eq(7L), eq(7L), any()))
        .thenReturn(
            ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")));
    when(orders.paid("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
    String status =
        "paid".equals(path) ? "PAID" : "fresh-debit".equals(path) ? "PENDING_PAYMENT" : "CANCELLED";
    var returned =
        new OrderClient.OrderView(
            returnedOrderNo, status, new BigDecimal("10.000"), 7L, "SUCCEEDED");
    if ("after-paid".equals(path))
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(
                  new OrderClient.OrderView(
                      "order-1", "PENDING_PAYMENT", BigDecimal.TEN, 7L, "SUCCEEDED")),
              ApiResponse.ok(returned));
    else if ("fresh-debit".equals(path))
      when(orders.paymentState("order-1", 7L))
          .thenReturn(
              ApiResponse.ok(returned),
              ApiResponse.ok(
                  new OrderClient.OrderView("order-1", "PAID", BigDecimal.TEN, 7L, "SUCCEEDED")));
    else when(orders.paymentState("order-1", 7L)).thenReturn(ApiResponse.ok(returned));
  }

  /** 缺失/别单以及三种CI别名必须逐字符拒绝。 */
  private static String wrongRemoteOrderNumber(String variant) {
    return switch (variant) {
      case "other" -> "order-other";
      case "null" -> null;
      case "blank" -> " ";
      case "case-alias" -> "ORDER-1";
      case "accent-alias" -> "órder-1";
      case "space-alias" -> "order-1 ";
      default -> throw new AssertionError(variant);
    };
  }

  /** 真实REFUND_PENDING原支付与四种活动intent可继续owned退款并原子结清。 */
  @Test
  void durableRefundPendingPaymentResumesFromEveryActiveIntentStage() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String status : List.of("INIT", "DEBITED", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("REFUND_PENDING", status);
            UserClient user = mock(UserClient.class);
            OrderClient orders = mock(OrderClient.class);
            configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
            assertEquals("REFUNDED", newService(user, orders).process("pay-1"), status);
            assertEquals("REFUNDED", paymentStatus());
            assertEquals("REFUNDED", intentStatus());
            assertEquals(1, intentAttempts());
            assertNull(intentLeaseOwner());
            assertTrue(
                fixture.queryForObject(
                    "SELECT last_error IS NULL FROM pay_debit_intent WHERE pay_no='pay-1'",
                    Boolean.class));
            verify(user).refund(eq(7L), eq(7L), any());
            verify(user, never()).debit(any(), any(), any());
            verify(orders, never()).paid(any(), any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** RPC/退款proof故障实际落pending后，第二次领取必须沿同key恢复而非永久stage0。 */
  @Test
  void retryAfterRefundFaultReclaimsAndCompletesTheSamePayment() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String fault : List.of("rpc-fault", "proof-missing", "proof-partial")) {
      assertions.add(
          () -> {
            seedIntentAndPayment("PENDING", "INIT");
            UserClient user = mock(UserClient.class);
            OrderClient orders = mock(OrderClient.class);
            configureRemoteOrderIdentityPath("applied-cancel", "order-1", user, orders);
            if ("rpc-fault".equals(fault))
              when(user.refund(eq(7L), eq(7L), any()))
                  .thenThrow(new IllegalStateException("owned-refund-rpc-fault"));
            else
              stubRefundOperation(
                  user,
                  "proof-missing".equals(fault) ? "not-found" : "partial",
                  "refund:pay-1",
                  "5.00");
            PayConsistencyService service = newService(user, orders);
            assertEquals("REFUND_PENDING", service.process("pay-1"));
            assertEquals("REFUND_PENDING", paymentStatus());
            assertEquals("REFUND_PENDING", intentStatus());
            assertEquals(1, intentAttempts());
            assertNull(intentLeaseOwner());
            assertTrue(retryErrorPresent());
            // 1. 仅使本测试owned行到期，不等待系统时钟或修改源库。
            assertEquals(
                1,
                fixture.update(
                    "UPDATE pay_debit_intent SET next_retry_at=NOW(3)-INTERVAL 1 SECOND WHERE"
                        + " pay_no='pay-1'"));
            // 2. 先用doReturn替换故障stub，避免when调用旧thenThrow或404响应。
            org.mockito.Mockito.doReturn(
                    ApiResponse.ok(new UserClient.BalanceView("refund:pay-1", "APPLIED", "100.00")))
                .when(user)
                .refund(eq(7L), eq(7L), any());
            org.mockito.Mockito.doReturn(
                    ApiResponse.ok(
                        new UserClient.BalanceOperationView(
                            "refund:pay-1", "REFUND", "APPLIED", "10.00", null)))
                .when(user)
                .operation(7L, "refund:pay-1", 7L);
            assertEquals("REFUNDED", service.process("pay-1"), fault);
            assertEquals("REFUNDED", paymentStatus());
            assertEquals("REFUNDED", intentStatus());
            assertEquals(2, intentAttempts());
            assertNull(intentLeaseOwner());
            assertTrue(
                fixture.queryForObject(
                    "SELECT last_error IS NULL FROM pay_debit_intent WHERE pay_no='pay-1'",
                    Boolean.class));
            var requests = org.mockito.ArgumentCaptor.forClass(UserClient.RefundRequest.class);
            verify(user, times(2)).refund(eq(7L), eq(7L), requests.capture());
            for (UserClient.RefundRequest request : requests.getAllValues()) {
              assertEquals("refund:pay-1", request.refundOperationKey());
              assertEquals("pay-1", request.originalOperationKey());
              assertEquals("pay-1", request.payNo());
              assertEquals("order-1", request.orderNo());
              assertEquals(0, BigDecimal.TEN.compareTo(request.amount()));
            }
            verify(user, never()).debit(any(), any(), any());
            verify(orders, never()).paid(any(), any());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 坏载荷在领取前拒绝，不能永久占满扫描队头并阻塞健康后项。 */
  @Test
  void corruptHeadCannotStarveHealthyRecoveryAcrossBoundedTicks() {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    for (long identifier = 1; identifier <= 111; identifier++) {
      boolean blocked = identifier <= 60;
      String payNo = (blocked ? "blocked-scan-" : "healthy-scan-") + identifier;
      String orderNo = "order-" + payNo;
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'PENDING','LEGACY',NOW(3),NOW(3))",
          identifier,
          payNo,
          orderNo);
      fixture.update(
          "INSERT INTO"
              + " pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,next_retry_at,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'INIT','LEGACY',0,NOW(3)-INTERVAL 1 DAY,NOW(3),NOW(3))",
          identifier,
          payNo,
          blocked ? "mismatched-" + payNo : orderNo);
    }
    var blockedBefore = blockedRecoveryFacts();
    UserClient user = mock(UserClient.class);
    OrderClient orders = mock(OrderClient.class);
    when(user.operation(eq(7L), anyString(), eq(7L)))
        .thenThrow(new IllegalStateException("owned-scan-user-read-fault"));
    PayConsistencyService service = newService(user, orders);
    // 1. 三轮各最多50个候选，应越过60坏行并实际领取全部51个健康后项。
    for (int tick = 0; tick < 3; tick++) service.recoverDue();
    assertEquals(
        51,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE id>60 AND attempts=1 AND lease_owner IS"
                + " NULL AND last_error LIKE '%owned-scan-user-read-fault%'",
            Integer.class));
    verify(user, times(51)).operation(eq(7L), anyString(), eq(7L));
    assertEquals(blockedBefore, blockedRecoveryFacts());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT SUM(attempts) FROM pay_debit_intent WHERE id<=60", Integer.class));
    // 2. 到尾部后回环读取最小主键坏头，坏事实仍不改且不新增远端调用。
    service.recoverDue();
    assertEquals(blockedBefore, blockedRecoveryFacts());
    verify(user, times(51)).operation(eq(7L), anyString(), eq(7L));
    verify(user, never()).debit(any(), any(), any());
    verify(user, never()).refund(any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(orders);
  }

  /** 保存队头两张owned表的完整事实，包括不会改动的错误/租约/时间字段。 */
  private List<List<Map<String, Object>>> blockedRecoveryFacts() {
    return List.of(
        fixture.queryForList("SELECT * FROM pay_record WHERE id<=60 ORDER BY id"),
        fixture.queryForList("SELECT * FROM pay_debit_intent WHERE id<=60 ORDER BY id"));
  }

  /** 创建LEGACY支付意图和取消/待支付支付记录。 */
  private void seedIntentAndPayment(String paymentStatus, String intentStatus) {
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,'pay-1','order-1',7,10,?,'LEGACY',NOW(3),NOW(3))",
        paymentStatus);
    fixture.update(
        "INSERT INTO"
            + " pay_debit_intent(id,pay_no,order_no,user_id,amount,debit_mode,status,attempts,next_retry_at,created_at,updated_at)"
            + " VALUES(1,'pay-1','order-1',7,10,'LEGACY',?,0,NOW(3)-INTERVAL 1"
            + " MINUTE,NOW(3),NOW(3))",
        intentStatus);
  }

  /** 构造订单已取消且库存动作成功的远端读替身。 */
  private static OrderClient cancellingOrders() {
    OrderClient orders = mock(OrderClient.class);
    when(orders.paymentState("order-1", 7L))
        .thenReturn(
            ApiResponse.ok(
                new OrderClient.OrderView(
                    "order-1", "CANCELLED", BigDecimal.TEN, 7L, "SUCCEEDED")));
    return orders;
  }

  /** 创建包含owned真实Mapper的Pay服务。 */
  private PayConsistencyService newService(UserClient user, OrderClient orders) {
    consistency =
        new PayConsistencyService(
            mock(PayDebitIntentService.class),
            intents,
            records,
            user,
            orders,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                fixture.getDataSource()));
    return consistency;
  }

  private PayConsistencyService consistencyWith(UserClient user, OrderClient orders) {
    return newService(user, orders);
  }

  /** 配置退款账本响应坏变体。 */
  private static void stubRefundOperation(
      UserClient user, String variant, String key, String amount) {
    if (List.of("not-found", "server-error", "fault").contains(variant)) {
      int status = "not-found".equals(variant) ? 404 : 500;
      Request request =
          Request.create(
              Request.HttpMethod.GET,
              "http://cloud-mall-user/owned-refund-proof",
              Map.of(),
              null,
              StandardCharsets.UTF_8);
      when(user.operation(7L, key, 7L))
          .thenThrow(
              FeignException.errorStatus(
                  "operation",
                  feign.Response.builder()
                      .status(status)
                      .reason("owned fault")
                      .request(request)
                      .headers(Map.of())
                      .body(new byte[0])
                      .build()));
      return;
    }
    UserClient.BalanceOperationView operation =
        List.of("null-body", "missing").contains(variant)
            ? null
            : new UserClient.BalanceOperationView(
                "wrong-key".equals(variant) ? "other" : key,
                "REFUND",
                "wrong-state".equals(variant) ? "REJECTED" : "APPLIED",
                "partial".equals(variant) ? amount : "10.00",
                null);
    when(user.operation(7L, key, 7L)).thenReturn(ApiResponse.ok(operation));
  }

  private List<List<Map<String, Object>>> payFacts() {
    return List.of("pay_record", "pay_debit_intent").stream()
        .map(table -> fixture.queryForList("SELECT * FROM " + table + " ORDER BY id"))
        .toList();
  }

  private String paymentStatus() {
    return fixture.queryForObject(
        "SELECT status FROM pay_record WHERE pay_no='pay-1'", String.class);
  }

  private String intentStatus() {
    return fixture.queryForObject(
        "SELECT status FROM pay_debit_intent WHERE pay_no='pay-1'", String.class);
  }

  private boolean retryErrorPresent() {
    return fixture.queryForObject(
        "SELECT last_error IS NOT NULL FROM pay_debit_intent WHERE pay_no='pay-1'", Boolean.class);
  }

  private int intentAttempts() {
    return fixture.queryForObject(
        "SELECT attempts FROM pay_debit_intent WHERE pay_no='pay-1'", Integer.class);
  }

  private String intentLeaseOwner() {
    return fixture.queryForObject(
        "SELECT lease_owner FROM pay_debit_intent WHERE pay_no='pay-1'", String.class);
  }

  private LocalDateTime intentNextRetry() {
    return fixture.queryForObject(
        "SELECT next_retry_at FROM pay_debit_intent WHERE pay_no='pay-1'", LocalDateTime.class);
  }

  private static int postUserOperationCalls(UserClient user) {
    return mockingDetails(user).getInvocations().stream()
        .filter(invocation -> "operation".equals(invocation.getMethod().getName()))
        .mapToInt(invocation -> 1)
        .sum();
  }

  private static List<Map<String, Object>> fingerprint(JdbcTemplate jdbcTemplate, String schema) {
    List<Map<String, Object>> values =
        new ArrayList<>(jdbcTemplate.queryForList("SHOW TABLES FROM " + schema));
    for (String table : jdbcTemplate.queryForList("SHOW TABLES FROM " + schema, String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      values.addAll(jdbcTemplate.queryForList("CHECKSUM TABLE " + schema + "." + table));
    }
    return values;
  }
}
