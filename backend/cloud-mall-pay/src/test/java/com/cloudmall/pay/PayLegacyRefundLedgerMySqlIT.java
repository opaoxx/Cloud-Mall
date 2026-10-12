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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Pay Legacy退款账本确认真实Mapper验收；User/Order仅使用受控远程替身。 */
class PayLegacyRefundLedgerMySqlIT {
  /** 原cloudmall_pay只读连接。 */
  private static JdbcTemplate source;

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

  @BeforeAll
  static void setupDatabase() {
    String url = System.getProperty("cloudmall.pay.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("IT需要指向cloudmall_pay的cloudmall.pay.mysql.test.url");
    String username = System.getProperty("cloudmall.mysql.test.username", "root");
    String password = System.getProperty("cloudmall.mysql.test.password", "root");
    source = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    sourceFingerprint = fingerprint(source, "cloudmall_pay");
    database = "cm16t77_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
  }

  @AfterAll
  static void cleanupDatabase() {
    if (source == null || database == null || !database.matches("cm16t77_[0-9a-f]{32}")) return;
    try {
      assertEquals(sourceFingerprint, fingerprint(source, "cloudmall_pay"));
      System.out.println("CM16T77 original Pay tables/checksums unchanged");
    } finally {
      source.execute("DROP DATABASE IF EXISTS " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM16T77 fixture dropped: " + database);
    }
  }

  @BeforeEach
  void prepareOwnedPay() {
    String sourceUrl = System.getProperty("cloudmall.pay.mysql.test.url");
    String username = System.getProperty("cloudmall.mysql.test.username", "root");
    String password = System.getProperty("cloudmall.mysql.test.password", "root");
    DataSource owned =
        new DriverManagerDataSource(
            sourceUrl.replace("/cloudmall_pay", "/" + database), username, password);
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
    verify(markerUser).operation(7L, "custom-refund", 7L);
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
    verify(postUser).operation(7L, "refund:pay-1", 7L);
    assertEquals(2, postUserOperationCalls(postUser));
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
      assertTrue(secondReading.await(10, java.util.concurrent.TimeUnit.SECONDS));
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

  /** 三终态的两SQL共用一个事务连接，所有User/Order交互仍发生在短事务外。 */
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
    if (List.of("no-debit-cancel", "fresh-debit").contains(path))
      stubRefundOperation(user, "not-found", "pay-1", "10.00");
    else
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
