package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.service.impl.OrderOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/** 超时outbox真实SQL错误记录，broker异常为替身，每例只写随机数据库。 */
class OrderOutboxErrorMySqlIT {
  /** 原订单库只读核验和隔离库管理入口。 */
  private static JdbcTemplate source;

  /** 原订单库表清单和校验和。 */
  private static List<Map<String, Object>> originalFingerprint;

  /** 本例独立创建、可精确清理的数据库名。 */
  private String database;

  /** 隔离数据库中的实际事实查询入口。 */
  private JdbcTemplate fixture;

  /** 实际outbox持久层。 */
  private OrderOutboxMapper mapper;

  @BeforeAll
  static void readOriginalDatabase() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_order的cloudmall.mysql.test.url");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(url), "root", "root"));
    originalFingerprint = fingerprint();
  }

  @BeforeEach
  void createFixture() {
    database = "cm17au_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(source.queryForMap("SHOW CREATE TABLE order_outbox").get("Create Table")));
    mapper = new OrderOutboxMapper(fixture);
  }

  @AfterEach
  void removeFixture() {
    if (database != null && database.matches("cm17au_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AU fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(originalFingerprint, fingerprint());
    System.out.println("CM17AU original Order tables and checksums unchanged");
  }

  @Test
  void mapperPreservesCompleteCharactersAndNullsInBothSqlModes() throws Exception {
    List<String[]> samples =
        List.of(
            new String[] {null, null},
            new String[] {"", ""},
            new String[] {" \t", " \t"},
            new String[] {"短消息😀", "短消息😀"},
            new String[] {"a".repeat(511) + "😀", "a".repeat(511) + "😀"},
            new String[] {"😀".repeat(512), "😀".repeat(512)},
            new String[] {"😀".repeat(513), "😀".repeat(512)},
            new String[] {"a".repeat(510) + "😀中尾", "a".repeat(510) + "😀中"},
            new String[] {"a".repeat(512), "a".repeat(512)},
            new String[] {"a".repeat(513), "a".repeat(512)},
            new String[] {"中".repeat(512), "中".repeat(512)},
            new String[] {"中".repeat(513), "中".repeat(512)});
    LocalDateTime retryAt = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 456_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String[] sample : samples) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO outbox = pendingOutbox();
          assertEquals(1, modeMapper.insert(outbox));
          var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          assertEquals(1, modeMapper.recordOutboxFailure(outbox.eventId, sample[0], retryAt));
          var after = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          assertEquals(sample[1], after.get("last_error"));
          assertUnchangedEvent(before, after);
          assertEquals("PENDING", after.get("status"));
          assertEquals(1, ((Number) after.get("attempts")).intValue());
          assertEquals(
              retryAt,
              modeJdbc.queryForObject(
                  "SELECT next_retry_at FROM order_outbox", LocalDateTime.class));
        }
      }
    }
  }

  @Test
  void dispatcherDoesNotSplitUtf8OrLoseMessagesThatFitTheColumn() {
    for (String[] sample :
        List.of(
            new String[] {"中".repeat(333) + "😀", "中".repeat(333) + "😀"},
            new String[] {"😀".repeat(251), "😀".repeat(251)},
            new String[] {"😀".repeat(512), "😀".repeat(512)},
            new String[] {"😀".repeat(513), "😀".repeat(512)})) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      RabbitTemplate rabbit = failingRabbit(sample[0]);
      var dispatcher = dispatcher(rabbit);
      assertDoesNotThrow(dispatcher::dispatchPending);
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals(sample[1], after.get("last_error"));
      assertUnchangedEvent(before, after);
      assertEquals("PENDING", after.get("status"));
      assertEquals(1, ((Number) after.get("attempts")).intValue());
      assertTrue(
          fixture
              .queryForObject("SELECT next_retry_at FROM order_outbox", LocalDateTime.class)
              .isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))));
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void nullOrBlankExceptionMessagesStillUseTheOriginalClassName() {
    for (String message : Arrays.asList(null, "", " \t")) {
      fixture.update("DELETE FROM order_outbox");
      assertEquals(1, mapper.insert(pendingOutbox()));
      dispatcher(failingRabbit(message)).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals("IllegalStateException", after.get("last_error"));
      assertEquals("PENDING", after.get("status"));
      assertEquals(1, ((Number) after.get("attempts")).intValue());
      assertNull(after.get("published_at"));
    }
  }

  @Test
  void nonPendingRowsRemainCompletelyUntouched() {
    for (String status : List.of("PUBLISHED", "OTHER")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      outbox.status = status;
      if ("PUBLISHED".equals(status)) outbox.publishedAt = outbox.createdAt;
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals(
          0,
          mapper.recordOutboxFailure(
              outbox.eventId, "😀".repeat(513), outbox.nextRetryAt.plusMinutes(1)));
      assertEquals(
          0, mapper.recordOutboxFailure("missing-event", "😀".repeat(513), outbox.nextRetryAt));
      assertEquals(before, fixture.queryForMap("SELECT * FROM order_outbox"));
    }
  }

  @Test
  void failureRecordSqlErrorStillPropagatesWithoutRepeatingThePublish() {
    assertEquals(1, mapper.insert(pendingOutbox()));
    var before = fixture.queryForMap("SELECT * FROM order_outbox");
    fixture.execute(
        "CREATE TRIGGER cm17au_write_failure BEFORE UPDATE ON order_outbox"
            + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cm17au failure record rejected'");
    RabbitTemplate rabbit = failingRabbit("中".repeat(333) + "😀");
    var dispatcher = dispatcher(rabbit);
    assertThrows(org.springframework.dao.DataAccessException.class, dispatcher::dispatchPending);
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_outbox"));
    verify(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
  }

  /** 仅模拟broker抛错，实际Dispatcher和Mapper沿原路径保存本地失败事实。 */
  private static RabbitTemplate failingRabbit(String message) {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doThrow(new IllegalStateException(message))
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    return rabbit;
  }

  /** 使用原默认退避，避免本轮改变消息投递配置。 */
  private OrderOutboxDispatcher dispatcher(RabbitTemplate rabbit) {
    return new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment());
  }

  /** 原事件身份、载荷、创建与发布事实不能因记录错误而变化。 */
  private static void assertUnchangedEvent(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id", "event_id", "event_type", "order_no", "payload", "created_at", "published_at"))
      assertEquals(before.get(column), after.get(column), column);
  }

  /** 构造真实到期PENDING事件，JSON时间保持当前上海语义。 */
  private static OrderOutboxPO pendingOutbox() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8));
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.id = 1L;
    outbox.eventId = "timeout-error-event";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = "202610error1";
    outbox.createdAt = now.minusMinutes(5).withNano(0);
    outbox.updatedAt = outbox.createdAt;
    outbox.payload =
        "{\"expireAt\":\""
            + now.plusMinutes(30).atOffset(ZoneOffset.ofHours(8))
            + "\",\"occurredAt\":\""
            + outbox.createdAt.atOffset(ZoneOffset.ofHours(8))
            + "\"}";
    outbox.status = "PENDING";
    outbox.attempts = 0;
    outbox.nextRetryAt = outbox.createdAt;
    return outbox;
  }

  /** 原库核验只读，夹具只能在自行创建的随机库内变动。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_order", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  /** JDBC会话显式使用上海DATETIME语义。 */
  private static String shanghaiUrl(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
