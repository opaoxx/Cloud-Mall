package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
            mock(OrderClient.class));
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
            mock(PayDebitIntentService.class), intents, records, user, orders);
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
