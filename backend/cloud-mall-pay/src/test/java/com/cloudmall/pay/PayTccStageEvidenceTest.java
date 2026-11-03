package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.TransactionManager;
import io.seata.tm.TransactionManagerHolder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 阶段 collector 的受控纯逻辑/JDBC 替身验收，不冒充真实 TC 或 Rabbit 故障。 */
class PayTccStageEvidenceTest {
  /** 单测只写 JUnit 自有临时目录。 */
  @TempDir Path artifacts;

  @Test
  void conflictHasPriorityAndMissingExecutionDoesNotInventBeginUnknown() {
    Map<String, Object> unknown = Map.of("state", "BEGIN_UNKNOWN");
    assertEquals(
        "PROTOCOL_CONFLICT", invoke("closureClassification", "AMOUNT_CONFLICT", unknown, null));
    assertEquals("BEGIN_UNKNOWN", invoke("closureClassification", null, unknown, "original"));
    assertEquals(
        "BEGIN_UNKNOWN", invoke("closureClassification", null, Map.of("state", "CLAIMED"), null));
    assertEquals("EVIDENCE_INCOMPLETE", invoke("closureClassification", null, null, null));
    assertEquals(
        "EVIDENCE_INCOMPLETE",
        invoke("closureClassification", null, Map.of("state", "COMMITTED"), "original"));
  }

  @Test
  void safeProjectionRedactsKeysAndDigestCanBeRecomputedFromPublishedFacts() throws Exception {
    String xid = "127.0.0.1:18091:original-fixture";
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("xid", xid);
    record.put("branch_id", 987654321L);
    record.put("operation_key", "original-operation");
    record.put("ledger_key", "controlled-pay-no");
    record.put("pay_no", "controlled-pay-no");
    record.put("amount", new BigDecimal("10.00"));
    record.put("last_error", "rpc observed " + xid);
    record.put("password_hash", "do-not-serialize-password");
    record.put("address_snapshot", "do-not-serialize-address");
    Map<String, Object> table =
        Map.of(
            "rows",
            List.of(record),
            "columnTypes",
            Map.of("xid", "VARCHAR", "branch_id", "BIGINT"));
    Map<String, Object> raw =
        Map.of(
            "pay",
            Map.of(
                "tables", Map.of("pay_tcc_execution", table), "captureStartedAt", "first-window"),
            "tc",
            Map.of("observedGlobalStatus", "Committed"),
            "mq",
            Map.of("messages", 1));
    Object safe = invoke("sanitize", raw, "", Set.of(xid));
    String serialized = new ObjectMapper().writeValueAsString(safe);
    assertFalse(serialized.contains(xid));
    assertFalse(serialized.contains("original-operation"));
    assertFalse(serialized.contains("987654321"));
    assertFalse(serialized.contains("do-not-serialize-password"));
    assertFalse(serialized.contains("do-not-serialize-address"));
    assertTrue(serialized.contains("controlled-pay-no"));
    assertTrue(serialized.contains("VARCHAR"));
    assertTrue(serialized.contains("10.00"));
    String originalDigest = (String) invoke("digest", invoke("safeFacts", raw, Set.of(xid)));
    String publishedDigest = (String) invoke("digest", invoke("safeFacts", safe, Set.of()));
    assertEquals(originalDigest, publishedDigest);
    assertEquals(safe, invoke("sanitize", safe, "", Set.of()));
    Object twoBranches =
        Map.of(
            "pay",
            Map.of(
                "tables",
                Map.of(
                    "tcc_fence_log",
                    Map.of("rows", List.of(Map.of("branch_id", 2L), Map.of("branch_id", 3L))))));
    Object safeBranches = invoke("sanitize", twoBranches, "", Set.of());
    String originalBranchDigest =
        (String) invoke("digest", invoke("safeFacts", twoBranches, Set.of()));
    String publishedBranchDigest =
        (String) invoke("digest", invoke("safeFacts", safeBranches, Set.of()));
    assertEquals(originalBranchDigest, publishedBranchDigest);
    Map<String, Object> changedWindow =
        Map.of(
            "pay",
            Map.of(
                "tables", Map.of("pay_tcc_execution", table), "captureStartedAt", "later-window"),
            "tc",
            Map.of("observedGlobalStatus", "Finished"),
            "mq",
            Map.of("messages", 0));
    assertEquals(originalDigest, invoke("digest", invoke("safeFacts", changedWindow, Set.of(xid))));
  }

  @Test
  void sourceBusinessTupleConflictIsReportedWithoutInferringFromTotalBalance() {
    Map<String, Object> payment = Map.of("debit_mode", "TCC", "amount", new BigDecimal("10.00"));
    Map<String, Object> wrongTuple =
        Map.of(
            "pay_no",
            "original-pay",
            "order_no",
            "different-order",
            "user_id",
            7L,
            "amount",
            new BigDecimal("10.00"));
    Map<String, Object> source =
        Map.of(
            "user",
            Map.of("tables", Map.of("balance_ledger", Map.of("rows", List.of(wrongTuple)))));
    assertEquals(
        "PROTOCOL_CONFLICT:ORDER_IDENTITY",
        invoke("immutableConflict", source, payment, "original-pay", "original-order", 7L));
    Map<String, Object> wrongMode =
        Map.of(
            "user",
            Map.of(
                "tables",
                Map.of(
                    "balance_debit_attempt", Map.of("rows", List.of(Map.of("mode", "LEGACY"))))));
    assertEquals(
        "PROTOCOL_CONFLICT:ATTEMPT_MODE",
        invoke("immutableConflict", wrongMode, payment, "original-pay", "original-order", 7L));
    Map<String, Object> wrongXid =
        Map.of(
            "pay",
            Map.of(
                "tables",
                Map.of(
                    "pay_tcc_execution", Map.of("rows", List.of(Map.of("xid", "original-xid"))))),
            "user",
            Map.of(
                "tables",
                Map.of(
                    "balance_tcc_debit", Map.of("rows", List.of(Map.of("xid", "different-xid"))))));
    assertEquals(
        "PROTOCOL_CONFLICT:ORIGINAL_XID",
        invoke("immutableConflict", wrongXid, payment, "original-pay", "original-order", 7L));
    assertNull(
        invoke(
            "immutableConflict",
            Map.of(
                "user",
                Map.of(
                    "tables",
                    Map.of(
                        "mall_user",
                        Map.of(
                            "rows",
                            List.of(Map.of("id", 7L, "balance", new BigDecimal("90.00"))))))),
            payment,
            "original-pay",
            "original-order",
            7L));
  }

  @Test
  void foreignDatabaseIsRejectedBeforeAnyBusinessReadOrArtifactWrite() throws Exception {
    JdbcTemplate database = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement ownership = mock(PreparedStatement.class);
    ResultSet identity = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT DATABASE()")).thenReturn(ownership);
    when(ownership.executeQuery()).thenReturn(identity);
    when(identity.next()).thenReturn(true);
    when(identity.getString(1)).thenReturn("cloudmall_pay");
    when(database.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    String run = "cm16c_" + "a".repeat(32);
    Map<String, JdbcTemplate> sources = intents(run);
    when(database.getDataSource()).thenReturn(source(run + "_pay"));
    sources.put("pay", database);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PayTccStageEvidence.capture(
                artifacts,
                "cm16c_" + "a".repeat(32),
                "created",
                "pay-fixture",
                "order-fixture",
                7,
                8,
                "202610",
                sources,
                Map.of(),
                Map.of()));
    verify(connection).prepareStatement("SELECT DATABASE()");
    verifyNoMoreInteractions(connection);
    try (var files = Files.list(artifacts)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void hundredAndFirstRowIsMarkedIncompleteAndNeverReportedAsComplete() throws Exception {
    JdbcTemplate database = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement ownership = mock(PreparedStatement.class);
    ResultSet identity = mock(ResultSet.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet records = mock(ResultSet.class);
    ResultSetMetaData metadata = mock(ResultSetMetaData.class);
    String schema = "cm16c_" + "b".repeat(32) + "_pay";
    when(database.getDataSource()).thenReturn(source(schema));
    when(connection.prepareStatement("SELECT DATABASE()")).thenReturn(ownership);
    when(connection.prepareStatement(startsWith("SELECT * FROM pay_record"))).thenReturn(statement);
    when(ownership.executeQuery()).thenReturn(identity);
    when(identity.next()).thenReturn(true);
    when(identity.getString(1)).thenReturn(schema);
    when(statement.executeQuery()).thenReturn(records);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    when(records.getMetaData()).thenReturn(metadata);
    when(metadata.getColumnCount()).thenReturn(1);
    when(metadata.getColumnLabel(1)).thenReturn("id");
    when(metadata.getColumnTypeName(1)).thenReturn("BIGINT");
    AtomicInteger cursor = new AtomicInteger();
    when(records.next()).thenAnswer(invocation -> cursor.incrementAndGet() <= 101);
    when(records.getObject("id")).thenAnswer(invocation -> cursor.get());
    when(database.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    Object query = invoke("subjectQuery", "pay_record", "pay-fixture", "order-fixture");
    List<String> missing = new ArrayList<>();
    Map<?, ?> result =
        (Map<?, ?>)
            invoke("databaseSnapshot", database, schema, Map.of("pay_record", query), missing);
    Map<?, ?> table = (Map<?, ?>) ((Map<?, ?>) result.get("tables")).get("pay_record");
    assertEquals("INCOMPLETE", result.get("status"));
    assertEquals("INCOMPLETE", table.get("status"));
    assertEquals(101, table.get("rowCount"));
    assertEquals(100, ((List<?>) table.get("rows")).size());
    assertEquals(List.of("BOUND_EXCEEDED:" + schema + ".pay_record"), missing);
    verify(connection).rollback();
    verify(statement).setQueryTimeout(5);
  }

  @Test
  void manifestOnlyReferencesImmutableJsonAndProcessMapUsesWhitelist() throws Exception {
    Files.writeString(artifacts.resolve("fixture.json"), "{}");
    Files.writeString(artifacts.resolve("live.log"), "growing-log");
    Files.writeString(artifacts.resolve("process.args"), "do-not-read-args");
    List<?> references = (List<?>) invoke("artifactManifest", artifacts);
    assertEquals(1, references.size());
    Map<?, ?> reference = (Map<?, ?>) references.get(0);
    assertEquals("fixture.json", reference.get("relativePath"));
    assertEquals(invoke("sha256", "{}"), reference.get("sha256"));
    Map<String, Object> processes =
        Map.of(
            "old",
            Map.of(
                "pid",
                1L,
                "appId",
                "stable-fixture-app",
                "commandLine",
                "do-not-serialize-command",
                "extra",
                "do-not-serialize-extra"),
            "verifiedBeginCounts",
            Map.of("old", 1, "new", 0));
    Object projected = invoke("processProjection", processes);
    String serialized =
        new ObjectMapper().writeValueAsString(invoke("sanitize", projected, "", Set.of()));
    assertFalse(serialized.contains("stable-fixture-app"));
    assertFalse(serialized.contains("do-not-serialize-command"));
    assertFalse(serialized.contains("do-not-serialize-extra"));
    assertTrue(serialized.contains("verifiedBeginCounts"));
  }

  @Test
  void allStaticIntentsAreCheckedBeforeAnyConnectionAndUnsafeParametersAreRejected()
      throws Exception {
    String run = "cm16c_" + "c".repeat(32);
    for (String invalid :
        List.of(
            "jdbc:mysql://127.0.0.1:13307/cloudmall_user?connectTimeout=3000&socketTimeout=5000",
            "jdbc:mysql://remote.example:13307/"
                + run
                + "_stock?connectTimeout=3000&socketTimeout=5000",
            "jdbc:mysql://127.0.0.1:13307/"
                + run
                + "_stock?connectTimeout=3000&socketTimeout=5000&allowMultiQueries=true",
            "jdbc:mysql://127.0.0.1:13307/"
                + run
                + "_stock?connectTimeout=3000&socketTimeout=5000&socketTimeout=1",
            "jdbc:mysql://127.0.0.1:13307/" + run + "_stock?connectTimeout=0&socketTimeout=5000")) {
      Map<String, JdbcTemplate> sources = intents(run);
      when(sources.get("stock").getDataSource())
          .thenReturn(new DriverManagerDataSource(invalid, "root", "root"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PayTccStageEvidence.capture(
                  artifacts, run, "invalid", "pay", "order", 7, 8, "202610", sources, Map.of(),
                  Map.of()));
      for (JdbcTemplate database : sources.values())
        verify(database, never()).execute(any(ConnectionCallback.class));
    }
    try (var files = Files.list(artifacts)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void unavailablePayKeepsUnknownSubjectAndFailedSourcesAreNotRetried() throws Exception {
    String run = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    Map<String, JdbcTemplate> sources = intents(run);
    for (JdbcTemplate database : sources.values())
      when(database.execute(any(ConnectionCallback.class)))
          .thenThrow(
              new CannotGetJdbcConnectionException(
                  "jdbc:secret raw-xid password",
                  new SQLException("private exception body", "42000", 1049)));
    Path phase =
        PayTccStageEvidence.capture(
            artifacts,
            run,
            "unavailable",
            "pay",
            "order",
            7,
            8,
            "202610",
            sources,
            Map.of(),
            Map.of("amount", "99.99", "debitMode", "TCC"));
    var bundle = new ObjectMapper().readTree(Files.readAllBytes(phase));
    for (String field :
        List.of(
            "amount",
            "debitMode",
            "executionId",
            "xidSha256",
            "payBranchSha256",
            "userBranchSha256")) assertTrue(bundle.at("/subject/" + field).isNull());
    assertEquals("EVIDENCE_INCOMPLETE", bundle.path("closureClassification").textValue());
    assertTrue(bundle.at("/sources/tc/xidSha256").isNull());
    assertEquals("NOT_OBSERVED", bundle.at("/sources/tc/status").textValue());
    assertTrue(bundle.at("/sources/pay/sourceChanged").isNull());
    for (String service : sources.keySet()) {
      verify(sources.get(service), times(1)).execute(any(ConnectionCallback.class));
      assertEquals(
          "UNAVAILABLE_NOT_VERIFIED",
          bundle.at("/sources/" + service + "/connectionOwnership").textValue());
      assertEquals(1049, bundle.at("/sources/" + service + "/safeError/vendorCode").intValue());
      assertTrue(bundle.at("/sources/" + service + "/tables").isEmpty());
      assertTrue(bundle.at("/sources/" + service + "/fence").isMissingNode());
    }
    String safe = bundle.toString();
    for (String secret : List.of("jdbc:secret", "raw-xid", "private exception body", "password"))
      assertFalse(safe.contains(secret));
  }

  @Test
  void laterForeignCatalogAndConfigurationChangesRemainHardRejections() throws Exception {
    String schema = "cm16c_" + "d".repeat(32) + "_pay";
    JdbcTemplate database = mock(JdbcTemplate.class);
    DriverManagerDataSource source = source(schema);
    when(database.getDataSource()).thenReturn(source);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet identity = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT DATABASE()")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(identity);
    when(identity.next()).thenReturn(true);
    when(identity.getString(1)).thenReturn(schema, "cloudmall_pay");
    when(database.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            call -> ((ConnectionCallback<?>) call.getArgument(0)).doInConnection(connection));
    Object binding = invoke("sourceBinding", database, schema);
    invoke("initialOwnership", binding, new ArrayList<String>());
    assertThrows(
        IllegalArgumentException.class,
        () -> invoke("databaseSnapshot", database, schema, Map.of(), new ArrayList<String>()));
    verify(connection, times(2)).prepareStatement("SELECT DATABASE()");
    verifyNoMoreInteractions(connection);
    source.setUrl(source.getUrl() + "&useSSL=false");
    assertThrows(
        IllegalArgumentException.class,
        () -> invoke("initialOwnership", binding, new ArrayList<String>()));
    verify(database, times(2)).execute(any(ConnectionCallback.class));
    try (var files = Files.list(artifacts)) {
      assertEquals(0, files.count());
    }
  }

  /** SYNTHETIC_RULE_ONLY：仅验证原 SDK 查询异常缺证分支，并无条件恢复同一原 holder。 */
  @Test
  void tcSdkStatusFailureRemainsUnavailableAndRestoresOriginalTransactionManager()
      throws Exception {
    // 1. 保存原槽位，仅替换为受控 SDK mock，不创建事务或访问真实 transport。
    TransactionManager original = TransactionManagerHolder.get();
    assertNotNull(original, "ORIGINAL_TRANSACTION_MANAGER_REQUIRED");
    TransactionManager unavailable = mock(TransactionManager.class);
    String selector = "synthetic-rule-only-tc-status-选择器";
    String privateMarker = "private-tc-sdk-error-marker";
    when(unavailable.getStatus(selector))
        .thenThrow(new TransactionException(privateMarker + ":" + selector));
    List<String> missing = new ArrayList<>();
    try {
      TransactionManagerHolder.set(unavailable);
      assertSame(unavailable, TransactionManagerHolder.get());

      // 2. 直接查询原 helper，SDK 异常必须保留来源缺证和安全时间，不能给出事务方向。
      Map<?, ?> snapshot = (Map<?, ?>) invoke("tcSnapshot", selector, missing);
      assertEquals("OFFICIAL_SEATA_1_5_2_GET_STATUS", snapshot.get("source"));
      assertEquals("UNAVAILABLE", snapshot.get("status"));
      assertEquals(
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(selector.getBytes(StandardCharsets.UTF_8))),
          snapshot.get("xidSha256"));
      assertEquals(Boolean.FALSE, snapshot.get("directionProvenByTc"));
      assertEquals(Boolean.FALSE, snapshot.get("branchDetailsObserved"));
      assertEquals(TransactionException.class.getSimpleName(), snapshot.get("errorType"));
      assertFalse(snapshot.containsKey("observedGlobalStatus"));
      assertEquals(List.of("SOURCE_UNAVAILABLE:TC"), missing);
      Map<String, OffsetDateTime> timestamps = new LinkedHashMap<>();
      for (String field : List.of("captureStartedAt", "observedAt", "captureEndedAt")) {
        assertInstanceOf(String.class, snapshot.get(field));
        OffsetDateTime time = OffsetDateTime.parse((String) snapshot.get(field));
        assertEquals(ZoneOffset.ofHours(8), time.getOffset());
        timestamps.put(field, time);
      }
      assertFalse(timestamps.get("observedAt").isBefore(timestamps.get("captureStartedAt")));
      assertFalse(timestamps.get("captureEndedAt").isBefore(timestamps.get("observedAt")));
      String serialized =
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "evidenceKind",
                      "SYNTHETIC_RULE_ONLY",
                      "snapshot",
                      snapshot,
                      "missingEvidence",
                      missing));
      assertTrue(serialized.contains("SYNTHETIC_RULE_ONLY"));
      assertFalse(serialized.contains(selector));
      assertFalse(serialized.contains(privateMarker));
      verify(unavailable, times(1)).getStatus(selector);
      verifyNoMoreInteractions(unavailable);
    } finally {
      // 3. 失败也恢复原对象，禁止以新默认实例替代或把 mock 留给后续测试。
      TransactionManagerHolder.set(original);
      assertSame(original, TransactionManagerHolder.get());
    }
  }

  private static DriverManagerDataSource source(String schema) {
    return new DriverManagerDataSource(
        "jdbc:mysql://127.0.0.1:13307/" + schema + "?connectTimeout=3000&socketTimeout=5000",
        "root",
        "root");
  }

  private static Map<String, JdbcTemplate> intents(String run) {
    Map<String, JdbcTemplate> sources = new LinkedHashMap<>();
    for (String service : List.of("pay", "user", "order", "stock")) {
      JdbcTemplate database = mock(JdbcTemplate.class);
      when(database.getDataSource()).thenReturn(source(run + "_" + service));
      sources.put(service, database);
    }
    return sources;
  }

  private static Object invoke(String method, Object... arguments) {
    return ReflectionTestUtils.invokeMethod(PayTccStageEvidence.class, method, arguments);
  }
}
