package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 只演练新建空 User/Order/Stock/Pay catalog 缺失；所有 fixture 都是采集器材料，不代表真实资金交易。 */
class PayTccStageEvidenceMySqlIT {
  /** 安全观察仅序列化本测试显式构造的字段，不序列化异常对象。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 四来源固定角色及本轮允许清理的后缀。 */
  private static final List<String> SERVICES = List.of("pay", "user", "order", "stock");

  /** 清理之前必须证明不存在资金、冻结、TC 和待处理 worker 行。 */
  private static final Set<String> UNRESOLVED_TABLES =
      Set.of(
          "mall_user",
          "balance_ledger",
          "balance_tcc_debit",
          "user_tcc_receive",
          "balance_debit_attempt",
          "tcc_fence_log",
          "pay_debit_intent",
          "pay_tcc_execution",
          "pay_tcc_dispatch",
          "pay_tcc_receipt",
          "pay_tcc_delivery",
          "pay_tcc_cancel",
          "order_stock_action",
          "order_outbox");

  @Test
  void userInitialCatalogUnavailableStillProducesSafePhase() throws Exception {
    // 1. 显式连接门只接受既有本机 MySQL；随机四库创建后逐一证明真实 catalog 归属。
    String sourceUrl = boundedLocalUrl();
    String runId = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    String payNo = "collector-pay-" + runId.substring(6, 18);
    String orderNo = "202610-collector-" + runId.substring(6, 18);
    Path artifacts = Path.of("target", "cm16c-stage-source", runId).toAbsolutePath();
    Files.createDirectories(artifacts);
    JdbcTemplate admin = jdbc(sourceUrl);
    List<String> created = new ArrayList<>();
    Map<String, JdbcTemplate> databases = new LinkedHashMap<>();
    Map<String, Object> verifiedCatalogs = new LinkedHashMap<>();
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("schemaVersion", "CM18_INITIAL_SOURCE_OBSERVATION_V1");
    observation.put("runId", runId);
    observation.put("scenario", "USER_INITIAL_CATALOG_UNAVAILABLE");
    observation.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
    observation.put("automaticActionsAllowed", false);
    observation.put("retention", "NO_AUTOMATIC_EXPIRY");
    List<Map<String, Object>> cleanup = new ArrayList<>();
    try {
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        assertEquals(0, exists(admin, schema));
        admin.execute(
            "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        created.add(schema);
        JdbcTemplate database =
            new ReadOnlyCaptureJdbc(
                new DriverManagerDataSource(
                    sourceUrl.replace("/cloudmall_user?", "/" + schema + "?"), "root", "root"));
        databases.put(service, database);
        assertEquals(schema, database.queryForObject("SELECT DATABASE()", String.class));
        verifiedCatalogs.put(service, Map.of("schema", schema, "actualCatalogVerified", true));
      }
      assertEquals(4, created.size());
      assertEquals(0, tableCount(admin, owned(runId, "user")));
      prepareCollectorOnlyRows(databases, payNo, orderNo);
      Map<String, Object> before = healthyFacts(databases);
      Map<String, Object> fixture = new LinkedHashMap<>();
      fixture.put("runId", runId);
      // 既有离线 profile 的逻辑 namespace/实际建库名单；本测试没有创建 Nacos namespace。
      fixture.put("group", runId);
      fixture.put("databases", created);
      fixture.put("scenarioId", "USER_INITIAL_CATALOG_UNAVAILABLE");
      fixture.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      fixture.put("createdSchemas", created);
      fixture.put("verifiedCatalogs", verifiedCatalogs);
      fixture.put("userWasCompletelyEmpty", true);
      fixture.put("payNo", payNo);
      fixture.put("orderNo", orderNo);
      fixture.put("userId", 7);
      fixture.put("skuId", 22);
      fixture.put("month", "202610");
      fixture.put("createdAt", now());
      fixture.put("collectorSourceSha256", collectorSourceSha());
      fixture.put("connectTimeoutMillis", 3000);
      fixture.put("socketTimeoutMillis", 5000);
      writeNew(artifacts.resolve("fixture.json"), fixture);

      // 2. 只删除本轮已验证且完全空的 User 库；原 DataSource 对象及 URL 保持不变。
      String userSchema = owned(runId, "user");
      assertTrue(created.contains(userSchema));
      assertEquals(0, tableCount(admin, userSchema));
      DriverManagerDataSource userDataSource =
          (DriverManagerDataSource) databases.get("user").getDataSource();
      String unchangedUserUrl = userDataSource.getUrl();
      admin.execute("DROP DATABASE " + userSchema);
      assertEquals(0, exists(admin, userSchema));
      assertSame(userDataSource, databases.get("user").getDataSource());
      assertEquals(unchangedUserUrl, userDataSource.getUrl());
      observation.put("userCatalogDroppedOnlyAfterVerifiedEmpty", true);
      observation.put("sameUserDataSourceAndConfiguration", true);
      observation.put("captureStartedAt", now());
      long started = System.nanoTime();
      Path phase = null;
      Exception captureFailure = null;
      for (JdbcTemplate database : databases.values())
        ((ReadOnlyCaptureJdbc) database).checking = true;
      try {
        phase =
            PayTccStageEvidence.capture(
                artifacts,
                runId,
                "initial-user-unavailable",
                payNo,
                orderNo,
                7,
                22,
                "202610",
                databases,
                Map.of("scenarioId", "USER_INITIAL_CATALOG_UNAVAILABLE"),
                Map.of(
                    "scenarioId",
                    "USER_INITIAL_CATALOG_UNAVAILABLE",
                    "fixtureRole",
                    "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
                    "observedOutcome",
                    "USER_INITIAL_CATALOG_UNAVAILABLE"));
      } catch (Exception unavailable) {
        captureFailure = unavailable;
      } finally {
        for (JdbcTemplate database : databases.values())
          ((ReadOnlyCaptureJdbc) database).checking = false;
      }
      long elapsed = System.nanoTime() - started;
      observation.put("captureEndedAt", now());
      observation.put("captureElapsedNanos", elapsed);
      observation.put("captureReturnedPhase", phase != null);
      observation.put("stageFileCount", stageFileCount(artifacts));
      observation.put("safeError", safeError(captureFailure));
      if (phase != null)
        observation.put("phaseRelativePath", artifacts.relativize(phase).toString());
      Map<String, Object> readOnlyAudit = new LinkedHashMap<>();
      for (var entry : databases.entrySet()) {
        ReadOnlyCaptureJdbc tracked = (ReadOnlyCaptureJdbc) entry.getValue();
        readOnlyAudit.put(
            entry.getKey(),
            Map.of(
                "connectionAttempts",
                tracked.connectionAttempts,
                "ownershipStatements",
                tracked.ownershipStatements,
                "businessSelectStatements",
                tracked.businessSelectStatements,
                "writes",
                0,
                "queryTimeoutBoundVerified",
                true));
      }
      observation.put("collectorReadOnlyAudit", readOnlyAudit);
      observation.put("healthyFactsBefore", before);
      observation.put("healthyFactsAfter", healthyFacts(databases));
      observation.put("userCatalogAbsentAfterCapture", exists(admin, userSchema) == 0);
      writeNew(artifacts.resolve("capture-observation.json"), observation);

      // 3. red 必须命中真实 1049 且没有阶段文件；期望仍是返回缺证包，不能反转成接受漏包。
      assertTrue(elapsed <= Duration.ofSeconds(30).toNanos(), "CAPTURE_EXCEEDED_30_SECOND_BOUND");
      assertEquals(before, observation.get("healthyFactsAfter"));
      assertEquals(0, exists(admin, userSchema), "COLLECTOR_MUST_NOT_RECREATE_USER");
      if (captureFailure != null) {
        assertEquals(
            1049,
            safeError(captureFailure).get("vendorCode"),
            "RED_MUST_BE_INITIAL_UNKNOWN_CATALOG");
        assertEquals(0L, stageFileCount(artifacts));
      }
      assertNull(captureFailure, "INITIAL_SOURCE_UNAVAILABLE_MUST_RETURN_SAFE_PHASE");
      assertNotNull(phase);
      JsonNode bundle = JSON.readTree(Files.readAllBytes(phase));
      assertEquals("CM18_PHASE_EVIDENCE_V1", bundle.path("schemaVersion").textValue());
      assertEquals("ISOLATED_TEST_PHASE_ONLY", bundle.path("scope").textValue());
      assertFalse(bundle.path("automaticActionsAllowed").booleanValue());
      assertEquals("EVIDENCE_INCOMPLETE", bundle.path("closureClassification").textValue());
      assertEquals("UNAVAILABLE", bundle.at("/sources/user/status").textValue());
      assertEquals(
          "UNAVAILABLE_NOT_VERIFIED", bundle.at("/sources/user/connectionOwnership").textValue());
      assertEquals(userSchema, bundle.at("/sources/user/configuredSchema").textValue());
      assertTrue(bundle.at("/sources/user/actualSchema").isMissingNode());
      assertTrue(bundle.at("/sources/user/tables").isEmpty());
      assertTrue(bundle.at("/sources/user/fence").isMissingNode());
      assertEquals("42000", bundle.at("/sources/user/safeError/sqlState").textValue());
      assertEquals(1049, bundle.at("/sources/user/safeError/vendorCode").intValue());
      assertEquals("INITIAL_OWNERSHIP", bundle.at("/sources/user/safeError/stage").textValue());
      ReadOnlyCaptureJdbc unavailableUser = (ReadOnlyCaptureJdbc) databases.get("user");
      assertEquals(1, unavailableUser.connectionAttempts);
      assertEquals(0, unavailableUser.ownershipStatements);
      assertEquals(0, unavailableUser.businessSelectStatements);
      for (String service : List.of("pay", "order", "stock")) {
        assertEquals("OBSERVED", bundle.at("/sources/" + service + "/status").textValue());
        assertEquals(
            "VERIFIED", bundle.at("/sources/" + service + "/connectionOwnership").textValue());
      }
      assertEquals("NOT_OBSERVED", bundle.at("/sources/tc/status").textValue());
      assertTrue(bundle.at("/subject/xidSha256").isNull());
      assertTrue(bundle.path("missingEvidence").toString().contains("SOURCE_UNAVAILABLE"));
      assertEquals("10.00", bundle.at("/subject/amount").textValue());
      assertEquals("LEGACY", bundle.at("/subject/debitMode").textValue());
      assertEquals(1L, stageFileCount(artifacts));
    } finally {
      // 4. fixture 准备和清理与 collector 分开记录；只遍历本轮精确白名单，发现未决则保留。
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        if (!created.contains(schema)) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("schema", schema);
        if (exists(admin, schema) == 0) item.put("status", "ALREADY_ABSENT");
        else if (!noUnresolvedRows(admin, schema)) item.put("status", "PRESERVED_UNRESOLVED");
        else {
          admin.execute("DROP DATABASE " + schema);
          assertEquals(0, exists(admin, schema));
          item.put("status", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS");
        }
        cleanup.add(item);
      }
      writeNew(
          artifacts.resolve("cleanup-observation.json"),
          Map.of(
              "runId",
              runId,
              "completedAt",
              now(),
              "fixtureRole",
              "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
              "resources",
              cleanup,
              "artifactsPreserved",
              true,
              "retention",
              "NO_AUTOMATIC_EXPIRY"));
    }
  }

  /** Order 空 catalog 的真实首次连接失败；健康 User 仅提供空表，不提供资金事实。 */
  @Test
  void orderInitialCatalogUnavailableStillProducesSafePhase() throws Exception {
    // 1. 显式连接门只接受既有本机 MySQL；随机四库创建后逐一证明真实 catalog 归属。
    String sourceUrl = boundedLocalUrl();
    String runId = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    String payNo = "collector-pay-" + runId.substring(6, 18);
    String orderNo = "202610-collector-" + runId.substring(6, 18);
    Path artifacts = Path.of("target", "cm16c-stage-source", runId).toAbsolutePath();
    Files.createDirectories(artifacts);
    JdbcTemplate admin = jdbc(sourceUrl);
    List<String> created = new ArrayList<>();
    Map<String, JdbcTemplate> databases = new LinkedHashMap<>();
    Map<String, Object> verifiedCatalogs = new LinkedHashMap<>();
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("schemaVersion", "CM18_INITIAL_SOURCE_OBSERVATION_V1");
    observation.put("runId", runId);
    observation.put("scenario", "ORDER_INITIAL_CATALOG_UNAVAILABLE");
    observation.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
    observation.put("automaticActionsAllowed", false);
    observation.put("retention", "NO_AUTOMATIC_EXPIRY");
    List<Map<String, Object>> cleanup = new ArrayList<>();
    Map<String, Object> initialHealthyFacts = Map.of();
    boolean initialHealthyFactsVerified = false;
    boolean captureFactsVerified = false;
    Throwable primaryFailure = null;
    try {
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        assertEquals(0, exists(admin, schema));
        admin.execute(
            "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        created.add(schema);
        JdbcTemplate database =
            new ReadOnlyCaptureJdbc(
                new DriverManagerDataSource(
                    sourceUrl.replace("/cloudmall_user?", "/" + schema + "?"), "root", "root"));
        databases.put(service, database);
        assertEquals(schema, database.queryForObject("SELECT DATABASE()", String.class));
        verifiedCatalogs.put(service, Map.of("schema", schema, "actualCatalogVerified", true));
      }
      assertEquals(4, created.size());
      assertEquals(0, tableCount(admin, owned(runId, "order")));
      prepareOrderUnavailableRows(databases, payNo, orderNo);
      assertEquals(0, tableCount(admin, owned(runId, "order")));
      assertEquals(5, tableCount(admin, owned(runId, "user")));
      Map<String, Object> before = healthyFacts(databases, List.of("pay", "user", "stock"));
      initialHealthyFacts = before;
      for (String service : List.of("pay", "user", "stock")) {
        Map<?, ?> tables = (Map<?, ?>) before.get(service);
        assertEquals(orderSourceTableWhitelist(service), tables.keySet());
        assertTrue(
            noUnresolvedRows(admin, owned(runId, service)), "INITIAL_FIXTURE_HAS_UNRESOLVED_ROWS");
      }
      initialHealthyFactsVerified = true;
      Map<String, Object> fixture = new LinkedHashMap<>();
      fixture.put("runId", runId);
      // 既有离线 profile 的逻辑 namespace/实际建库名单；本测试没有创建 Nacos namespace。
      fixture.put("group", runId);
      fixture.put("databases", created);
      fixture.put("scenarioId", "ORDER_INITIAL_CATALOG_UNAVAILABLE");
      fixture.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      fixture.put("createdSchemas", created);
      fixture.put("verifiedCatalogs", verifiedCatalogs);
      fixture.put("orderWasCompletelyEmpty", true);
      fixture.put("payNo", payNo);
      fixture.put("orderNo", orderNo);
      fixture.put("userId", 7);
      fixture.put("skuId", 22);
      fixture.put("month", "202610");
      fixture.put("createdAt", now());
      fixture.put("collectorSourceSha256", collectorSourceSha());
      fixture.put("connectTimeoutMillis", 3000);
      fixture.put("socketTimeoutMillis", 5000);
      writeNew(artifacts.resolve("fixture.json"), fixture);

      // 2. 只删除本轮已验证且完全空的 Order 库；原 DataSource 对象及 URL 保持不变。
      String orderSchema = owned(runId, "order");
      assertTrue(created.contains(orderSchema));
      assertEquals(0, tableCount(admin, orderSchema));
      DriverManagerDataSource orderDataSource =
          (DriverManagerDataSource) databases.get("order").getDataSource();
      String unchangedOrderUrl = orderDataSource.getUrl();
      admin.execute("DROP DATABASE " + orderSchema);
      assertEquals(0, exists(admin, orderSchema));
      assertSame(orderDataSource, databases.get("order").getDataSource());
      assertEquals(unchangedOrderUrl, orderDataSource.getUrl());
      observation.put("orderCatalogDroppedOnlyAfterVerifiedEmpty", true);
      observation.put("sameOrderDataSourceAndConfiguration", true);
      observation.put("captureStartedAt", now());
      long started = System.nanoTime();
      Path phase = null;
      Exception captureFailure = null;
      for (JdbcTemplate database : databases.values())
        ((ReadOnlyCaptureJdbc) database).checking = true;
      try {
        phase =
            PayTccStageEvidence.capture(
                artifacts,
                runId,
                "initial-order-unavailable",
                payNo,
                orderNo,
                7,
                22,
                "202610",
                databases,
                Map.of("scenarioId", "ORDER_INITIAL_CATALOG_UNAVAILABLE"),
                Map.of(
                    "scenarioId",
                    "ORDER_INITIAL_CATALOG_UNAVAILABLE",
                    "fixtureRole",
                    "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
                    "observedOutcome",
                    "ORDER_INITIAL_CATALOG_UNAVAILABLE"));
      } catch (Exception unavailable) {
        captureFailure = unavailable;
      } finally {
        for (JdbcTemplate database : databases.values())
          ((ReadOnlyCaptureJdbc) database).checking = false;
      }
      long elapsed = System.nanoTime() - started;
      observation.put("captureEndedAt", now());
      observation.put("captureElapsedNanos", elapsed);
      observation.put("captureReturnedPhase", phase != null);
      observation.put("stageFileCount", stageFileCount(artifacts));
      observation.put("safeError", safeError(captureFailure, "ORDER"));
      if (phase != null)
        observation.put("phaseRelativePath", artifacts.relativize(phase).toString());
      Map<String, Object> readOnlyAudit = new LinkedHashMap<>();
      for (var entry : databases.entrySet()) {
        ReadOnlyCaptureJdbc tracked = (ReadOnlyCaptureJdbc) entry.getValue();
        readOnlyAudit.put(
            entry.getKey(),
            Map.of(
                "connectionAttempts",
                tracked.connectionAttempts,
                "ownershipStatements",
                tracked.ownershipStatements,
                "businessSelectStatements",
                tracked.businessSelectStatements,
                "writes",
                0,
                "queryTimeoutBoundVerified",
                true));
      }
      observation.put("collectorReadOnlyAudit", readOnlyAudit);
      observation.put("healthyFactsBefore", before);
      observation.put(
          "healthyFactsAfter", healthyFacts(databases, List.of("pay", "user", "stock")));
      observation.put("orderCatalogAbsentAfterCapture", exists(admin, orderSchema) == 0);
      writeNew(artifacts.resolve("capture-observation.json"), observation);

      // 3. red 必须命中真实 1049 且没有阶段文件；期望仍是返回缺证包，不能反转成接受漏包。
      assertTrue(elapsed <= Duration.ofSeconds(30).toNanos(), "CAPTURE_EXCEEDED_30_SECOND_BOUND");
      assertEquals(before, observation.get("healthyFactsAfter"));
      assertEquals(0, exists(admin, orderSchema), "COLLECTOR_MUST_NOT_RECREATE_ORDER");
      if (captureFailure != null) {
        assertEquals(
            1049,
            safeError(captureFailure, "ORDER").get("vendorCode"),
            "RED_MUST_BE_INITIAL_UNKNOWN_CATALOG");
        assertEquals(0L, stageFileCount(artifacts));
      }
      assertNull(captureFailure, "INITIAL_SOURCE_UNAVAILABLE_MUST_RETURN_SAFE_PHASE");
      assertNotNull(phase);
      JsonNode bundle = JSON.readTree(Files.readAllBytes(phase));
      assertEquals("CM18_PHASE_EVIDENCE_V1", bundle.path("schemaVersion").textValue());
      assertEquals("ISOLATED_TEST_PHASE_ONLY", bundle.path("scope").textValue());
      assertFalse(bundle.path("automaticActionsAllowed").booleanValue());
      assertEquals("EVIDENCE_INCOMPLETE", bundle.path("closureClassification").textValue());
      assertEquals("UNAVAILABLE", bundle.at("/sources/order/status").textValue());
      assertEquals(
          "UNAVAILABLE_NOT_VERIFIED", bundle.at("/sources/order/connectionOwnership").textValue());
      assertEquals(orderSchema, bundle.at("/sources/order/configuredSchema").textValue());
      assertTrue(bundle.at("/sources/order/actualSchema").isMissingNode());
      assertTrue(bundle.at("/sources/order/tables").isEmpty());
      assertTrue(bundle.at("/sources/order/fence").isMissingNode());
      assertEquals("42000", bundle.at("/sources/order/safeError/sqlState").textValue());
      assertEquals(1049, bundle.at("/sources/order/safeError/vendorCode").intValue());
      assertEquals("INITIAL_OWNERSHIP", bundle.at("/sources/order/safeError/stage").textValue());
      ReadOnlyCaptureJdbc unavailableOrder = (ReadOnlyCaptureJdbc) databases.get("order");
      assertEquals(1, unavailableOrder.connectionAttempts);
      assertEquals(0, unavailableOrder.ownershipStatements);
      assertEquals(0, unavailableOrder.businessSelectStatements);
      for (String service : List.of("pay", "user", "stock")) {
        assertEquals("OBSERVED", bundle.at("/sources/" + service + "/status").textValue());
        assertEquals(
            "VERIFIED", bundle.at("/sources/" + service + "/connectionOwnership").textValue());
        assertEquals(
            owned(runId, service), bundle.at("/sources/" + service + "/schema").textValue());
        assertEquals(
            owned(runId, service),
            bundle.at("/sources/" + service + "/configuredSchema").textValue());
        ReadOnlyCaptureJdbc healthySource = (ReadOnlyCaptureJdbc) databases.get(service);
        assertTrue(healthySource.ownershipStatements > 0, "HEALTHY_SOURCE_OWNERSHIP_NOT_OBSERVED");
        assertEquals(healthySource.connectionAttempts, healthySource.ownershipStatements);
      }
      assertEquals("ORDER", bundle.at("/sources/order/safeError/owner").textValue());
      for (String table :
          List.of(
              "mall_user",
              "user_tcc_receive",
              "balance_debit_attempt",
              "balance_tcc_debit",
              "balance_ledger")) {
        assertEquals("EMPTY", bundle.at("/sources/user/tables/" + table + "/status").textValue());
        assertEquals(0, bundle.at("/sources/user/tables/" + table + "/rowCount").intValue());
      }
      assertFalse(bundle.at("/sources/pay/sourceChanged").asBoolean(true));
      assertEquals("NOT_OBSERVED", bundle.at("/sources/tc/status").textValue());
      assertTrue(bundle.at("/subject/xidSha256").isNull());
      assertTrue(bundle.path("missingEvidence").toString().contains("SOURCE_UNAVAILABLE"));
      assertEquals("10.00", bundle.at("/subject/amount").textValue());
      assertEquals("LEGACY", bundle.at("/subject/debitMode").textValue());
      assertEquals(1L, stageFileCount(artifacts));
      captureFactsVerified = true;
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      // 4. 每库独立核查。健康库只有初始、采集窗口和当前全表事实都通过才获得清理资格。
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        if (!created.contains(schema)) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("schema", schema);
        String cleanupStep = "VERIFY_EXISTENCE";
        try {
          if (exists(admin, schema) == 0) item.put("status", "ALREADY_ABSENT");
          else if (service.equals("order")) item.put("status", "PRESERVED_FAILED_SOURCE_PRESENT");
          else if (!initialHealthyFactsVerified
              || !captureFactsVerified
              || !initialHealthyFacts.containsKey(service))
            item.put("status", "PRESERVED_UNVERIFIED_FACTS");
          else {
            cleanupStep = "VERIFY_CURRENT_OWNERSHIP";
            JdbcTemplate database = databases.get(service);
            if (!schema.equals(database.queryForObject("SELECT DATABASE()", String.class)))
              item.put("status", "PRESERVED_UNVERIFIED_OWNERSHIP");
            else {
              cleanupStep = "VERIFY_CURRENT_HEALTHY_FACTS";
              Object currentFacts = healthyFacts(databases, List.of(service)).get(service);
              boolean factsUnchanged = initialHealthyFacts.get(service).equals(currentFacts);
              item.put("currentHealthyFacts", currentFacts);
              item.put("healthyFactsMatchInitial", factsUnchanged);
              if (!factsUnchanged) item.put("status", "PRESERVED_FACTS_CHANGED");
              else if (!orderSourceTableWhitelist(service)
                      .equals(
                          Set.copyOf(
                              admin.queryForList(
                                  "SELECT table_name FROM information_schema.tables WHERE"
                                      + " table_schema=?",
                                  String.class,
                                  schema)))
                  || !noUnresolvedRows(admin, schema)) item.put("status", "PRESERVED_UNRESOLVED");
              else {
                cleanupStep = "DROP_VERIFIED_OWNED_SCHEMA";
                admin.execute("DROP DATABASE " + schema);
                cleanupStep = "VERIFY_ABSENCE";
                assertEquals(0, exists(admin, schema));
                item.put("status", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS");
              }
            }
          }
        } catch (Exception | AssertionError cleanupFailure) {
          item.put("status", "CLEANUP_FAILED");
          Map<String, Object> error = safeError(cleanupFailure);
          error.put("owner", service.toUpperCase(java.util.Locale.ROOT));
          error.put("stage", cleanupStep);
          error.put("category", "CLEANUP_OPERATION_FAILED");
          item.put("safeError", error);
        } finally {
          cleanup.add(item);
        }
      }
      boolean allCreatedSchemasAbsentVerified =
          cleanup.stream()
                  .allMatch(
                      item ->
                          Set.of("ALREADY_ABSENT", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS")
                              .contains(item.get("status")))
              && cleanup.size() == created.size();
      Map<String, Object> cleanupAudit = new LinkedHashMap<>();
      cleanupAudit.put("runId", runId);
      cleanupAudit.put("completedAt", now());
      cleanupAudit.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      cleanupAudit.put("initialHealthyFacts", initialHealthyFacts);
      cleanupAudit.put("initialHealthyFactsVerified", initialHealthyFactsVerified);
      cleanupAudit.put("captureFactsVerified", captureFactsVerified);
      cleanupAudit.put("resources", cleanup);
      cleanupAudit.put("allCreatedSchemasAbsentVerified", allCreatedSchemasAbsentVerified);
      cleanupAudit.put("artifactsPreserved", true);
      cleanupAudit.put("retention", "NO_AUTOMATIC_EXPIRY");
      try {
        writeNew(artifacts.resolve("cleanup-observation.json"), cleanupAudit);
      } catch (Exception auditFailure) {
        IllegalStateException safeFailure = new IllegalStateException("CLEANUP_AUDIT_UNAVAILABLE");
        if (primaryFailure != null) primaryFailure.addSuppressed(safeFailure);
        else throw safeFailure;
      }
      if (!allCreatedSchemasAbsentVerified) {
        AssertionError incomplete = new AssertionError("OWNED_FIXTURE_CLEANUP_INCOMPLETE");
        if (primaryFailure != null) primaryFailure.addSuppressed(incomplete);
        else throw incomplete;
      }
    }
  }

  /** Stock 空 catalog 的真实首次连接失败；保留健康三源原 SQL 采集投影。 */
  @Test
  void stockInitialCatalogUnavailableStillProducesSafePhase() throws Exception {
    // 1. 显式连接门只接受既有本机 MySQL；随机四库创建后逐一证明真实 catalog 归属。
    String sourceUrl = boundedLocalUrl();
    String runId = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    String payNo = "collector-pay-" + runId.substring(6, 18);
    String orderNo = "202610-collector-" + runId.substring(6, 18);
    Path artifacts = Path.of("target", "cm16c-stage-source", runId).toAbsolutePath();
    Files.createDirectories(artifacts);
    JdbcTemplate admin = jdbc(sourceUrl);
    List<String> created = new ArrayList<>();
    Map<String, JdbcTemplate> databases = new LinkedHashMap<>();
    Map<String, Object> verifiedCatalogs = new LinkedHashMap<>();
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("schemaVersion", "CM18_INITIAL_SOURCE_OBSERVATION_V1");
    observation.put("runId", runId);
    observation.put("scenario", "STOCK_INITIAL_CATALOG_UNAVAILABLE");
    observation.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
    observation.put("automaticActionsAllowed", false);
    observation.put("retention", "NO_AUTOMATIC_EXPIRY");
    List<Map<String, Object>> cleanup = new ArrayList<>();
    Map<String, Object> initialHealthyFacts = Map.of();
    boolean initialHealthyFactsVerified = false;
    boolean captureFactsVerified = false;
    Throwable primaryFailure = null;
    Map<String, Long> seededRowCounts =
        Map.of("pay_record", 1L, "mall_order_202610", 1L, "mall_order_item_202610", 1L);
    try {
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        assertEquals(0, exists(admin, schema));
        admin.execute(
            "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        created.add(schema);
        JdbcTemplate database =
            new ReadOnlyCaptureJdbc(
                new DriverManagerDataSource(
                    sourceUrl.replace("/cloudmall_user?", "/" + schema + "?"), "root", "root"));
        databases.put(service, database);
        assertEquals(schema, database.queryForObject("SELECT DATABASE()", String.class));
        verifiedCatalogs.put(service, Map.of("schema", schema, "actualCatalogVerified", true));
      }
      assertEquals(4, created.size());
      assertEquals(0, tableCount(admin, owned(runId, "stock")));
      prepareStockUnavailableRows(databases, payNo, orderNo);
      assertEquals(0, tableCount(admin, owned(runId, "stock")));
      assertEquals(5, tableCount(admin, owned(runId, "user")));
      Map<String, Object> before = healthyFacts(databases, List.of("pay", "user", "order"));
      initialHealthyFacts = before;
      for (String service : List.of("pay", "user", "order")) {
        Map<?, ?> tables = (Map<?, ?>) before.get(service);
        assertEquals(stockSourceTableWhitelist(service), tables.keySet());
        for (Object table : tables.keySet())
          assertEquals(
              seededRowCounts.getOrDefault(table, 0L),
              ((Map<?, ?>) tables.get(table)).get("rowCount"));
        assertTrue(
            noUnresolvedRows(admin, owned(runId, service)), "INITIAL_FIXTURE_HAS_UNRESOLVED_ROWS");
      }
      assertEquals(
          1,
          databases
              .get("pay")
              .queryForObject(
                  "SELECT COUNT(*) FROM pay_record WHERE id=1 AND BINARY pay_no=BINARY ?"
                      + " AND BINARY order_no=BINARY ? AND user_id=7 AND amount=10.00"
                      + " AND debit_mode='LEGACY' AND status='PENDING'",
                  Integer.class,
                  payNo,
                  orderNo));
      assertEquals(
          1,
          databases
              .get("order")
              .queryForObject(
                  "SELECT COUNT(*) FROM mall_order_202610 WHERE id=11 AND BINARY order_no=BINARY ?"
                      + " AND user_id=7 AND status='PENDING_PAYMENT' AND total_amount=10.00 AND"
                      + " pay_amount=10.00",
                  Integer.class,
                  orderNo));
      assertEquals(
          1,
          databases
              .get("order")
              .queryForObject(
                  "SELECT COUNT(*) FROM mall_order_item_202610 WHERE id=12 AND order_id=11"
                      + " AND BINARY order_no=BINARY ? AND sku_id=22 AND quantity=1"
                      + " AND unit_price=10.00 AND line_amount=10.00",
                  Integer.class,
                  orderNo));
      initialHealthyFactsVerified = true;
      Map<String, Object> fixture = new LinkedHashMap<>();
      fixture.put("runId", runId);
      // 既有离线 profile 的逻辑 namespace/实际建库名单；本测试没有创建 Nacos namespace。
      fixture.put("group", runId);
      fixture.put("databases", created);
      fixture.put("scenarioId", "STOCK_INITIAL_CATALOG_UNAVAILABLE");
      fixture.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      fixture.put("createdSchemas", created);
      fixture.put("verifiedCatalogs", verifiedCatalogs);
      fixture.put("stockWasCompletelyEmpty", true);
      fixture.put("payNo", payNo);
      fixture.put("orderNo", orderNo);
      fixture.put("userId", 7);
      fixture.put("skuId", 22);
      fixture.put("month", "202610");
      fixture.put("createdAt", now());
      fixture.put("collectorSourceSha256", collectorSourceSha());
      fixture.put("connectTimeoutMillis", 3000);
      fixture.put("socketTimeoutMillis", 5000);
      writeNew(artifacts.resolve("fixture.json"), fixture);

      // 2. 只删除本轮已验证且完全空的 Stock 库；原 DataSource 对象及 URL 保持不变。
      String stockSchema = owned(runId, "stock");
      assertTrue(created.contains(stockSchema));
      assertEquals(0, tableCount(admin, stockSchema));
      DriverManagerDataSource stockDataSource =
          (DriverManagerDataSource) databases.get("stock").getDataSource();
      String unchangedStockUrl = stockDataSource.getUrl();
      assertEquals(
          stockSchema, databases.get("stock").queryForObject("SELECT DATABASE()", String.class));
      assertEquals(0, tableCount(admin, stockSchema));
      assertSame(stockDataSource, databases.get("stock").getDataSource());
      assertEquals(unchangedStockUrl, stockDataSource.getUrl());
      admin.execute("DROP DATABASE " + stockSchema);
      assertEquals(0, exists(admin, stockSchema));
      assertSame(stockDataSource, databases.get("stock").getDataSource());
      assertEquals(unchangedStockUrl, stockDataSource.getUrl());
      observation.put("stockCatalogDroppedOnlyAfterVerifiedEmpty", true);
      observation.put("sameStockDataSourceAndConfiguration", true);
      observation.put("captureStartedAt", now());
      long started = System.nanoTime();
      Path phase = null;
      Exception captureFailure = null;
      for (JdbcTemplate database : databases.values())
        ((ReadOnlyCaptureJdbc) database).checking = true;
      try {
        phase =
            PayTccStageEvidence.capture(
                artifacts,
                runId,
                "initial-stock-unavailable",
                payNo,
                orderNo,
                7,
                22,
                "202610",
                databases,
                Map.of("scenarioId", "STOCK_INITIAL_CATALOG_UNAVAILABLE"),
                Map.of(
                    "scenarioId",
                    "STOCK_INITIAL_CATALOG_UNAVAILABLE",
                    "fixtureRole",
                    "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
                    "observedOutcome",
                    "STOCK_INITIAL_CATALOG_UNAVAILABLE"));
      } catch (Exception unavailable) {
        captureFailure = unavailable;
      } finally {
        for (JdbcTemplate database : databases.values())
          ((ReadOnlyCaptureJdbc) database).checking = false;
      }
      long elapsed = System.nanoTime() - started;
      observation.put("captureEndedAt", now());
      observation.put("captureElapsedNanos", elapsed);
      observation.put("captureReturnedPhase", phase != null);
      observation.put("stageFileCount", stageFileCount(artifacts));
      observation.put("safeError", stockSafeError(captureFailure));
      if (phase != null)
        observation.put("phaseRelativePath", artifacts.relativize(phase).toString());
      Map<String, Object> readOnlyAudit = new LinkedHashMap<>();
      for (var entry : databases.entrySet()) {
        ReadOnlyCaptureJdbc tracked = (ReadOnlyCaptureJdbc) entry.getValue();
        readOnlyAudit.put(
            entry.getKey(),
            Map.of(
                "connectionAttempts",
                tracked.connectionAttempts,
                "ownershipStatements",
                tracked.ownershipStatements,
                "businessSelectStatements",
                tracked.businessSelectStatements,
                "writes",
                0,
                "queryTimeoutBoundVerified",
                true));
      }
      observation.put("collectorReadOnlyAudit", readOnlyAudit);
      observation.put("healthyFactsBefore", before);
      observation.put(
          "healthyFactsAfter", healthyFacts(databases, List.of("pay", "user", "order")));
      observation.put("stockCatalogAbsentAfterCapture", exists(admin, stockSchema) == 0);
      writeNew(artifacts.resolve("capture-observation.json"), observation);

      // 3. red 必须命中真实 1049 且没有阶段文件；期望仍是返回缺证包，不能反转成接受漏包。
      assertTrue(elapsed <= Duration.ofSeconds(30).toNanos(), "CAPTURE_EXCEEDED_30_SECOND_BOUND");
      assertEquals(before, observation.get("healthyFactsAfter"));
      assertEquals(0, exists(admin, stockSchema), "COLLECTOR_MUST_NOT_RECREATE_STOCK");
      if (captureFailure != null) {
        assertEquals(
            1049,
            stockSafeError(captureFailure).get("vendorCode"),
            "RED_MUST_BE_INITIAL_UNKNOWN_CATALOG");
        assertEquals(0L, stageFileCount(artifacts));
      }
      assertNull(captureFailure, "INITIAL_SOURCE_UNAVAILABLE_MUST_RETURN_SAFE_PHASE");
      assertNotNull(phase);
      JsonNode bundle = JSON.readTree(Files.readAllBytes(phase));
      assertEquals("CM18_PHASE_EVIDENCE_V1", bundle.path("schemaVersion").textValue());
      assertEquals("ISOLATED_TEST_PHASE_ONLY", bundle.path("scope").textValue());
      assertFalse(bundle.path("automaticActionsAllowed").booleanValue());
      assertEquals("EVIDENCE_INCOMPLETE", bundle.path("closureClassification").textValue());
      assertEquals("UNAVAILABLE", bundle.at("/sources/stock/status").textValue());
      assertEquals(
          "UNAVAILABLE_NOT_VERIFIED", bundle.at("/sources/stock/connectionOwnership").textValue());
      assertEquals(stockSchema, bundle.at("/sources/stock/configuredSchema").textValue());
      assertTrue(bundle.at("/sources/stock/actualSchema").isMissingNode());
      assertTrue(bundle.at("/sources/stock/tables").isEmpty());
      assertTrue(bundle.at("/sources/stock/fence").isMissingNode());
      assertEquals("42000", bundle.at("/sources/stock/safeError/sqlState").textValue());
      assertEquals(1049, bundle.at("/sources/stock/safeError/vendorCode").intValue());
      assertEquals("INITIAL_OWNERSHIP", bundle.at("/sources/stock/safeError/stage").textValue());
      ReadOnlyCaptureJdbc unavailableStock = (ReadOnlyCaptureJdbc) databases.get("stock");
      assertEquals(1, unavailableStock.connectionAttempts);
      assertEquals(0, unavailableStock.ownershipStatements);
      assertEquals(0, unavailableStock.businessSelectStatements);
      for (String service : List.of("pay", "user", "order")) {
        assertEquals("OBSERVED", bundle.at("/sources/" + service + "/status").textValue());
        assertEquals(
            "VERIFIED", bundle.at("/sources/" + service + "/connectionOwnership").textValue());
        assertEquals(
            owned(runId, service), bundle.at("/sources/" + service + "/schema").textValue());
        assertEquals(
            owned(runId, service),
            bundle.at("/sources/" + service + "/configuredSchema").textValue());
        ReadOnlyCaptureJdbc healthySource = (ReadOnlyCaptureJdbc) databases.get(service);
        assertTrue(healthySource.ownershipStatements > 0, "HEALTHY_SOURCE_OWNERSHIP_NOT_OBSERVED");
        assertEquals(healthySource.connectionAttempts, healthySource.ownershipStatements);
      }
      assertEquals("STOCK", bundle.at("/sources/stock/safeError/owner").textValue());
      for (String service : List.of("pay", "user", "order"))
        for (String table : stockSourceTableWhitelist(service)) {
          long expectedCount = seededRowCounts.getOrDefault(table, 0L);
          assertEquals(
              expectedCount == 0 ? "EMPTY" : "OBSERVED",
              bundle.at("/sources/" + service + "/tables/" + table + "/status").textValue());
          assertEquals(
              expectedCount,
              bundle.at("/sources/" + service + "/tables/" + table + "/rowCount").longValue());
        }
      assertFalse(bundle.at("/sources/pay/sourceChanged").asBoolean(true));
      assertEquals("NOT_OBSERVED", bundle.at("/sources/tc/status").textValue());
      for (String field :
          List.of("executionId", "xidSha256", "payBranchSha256", "userBranchSha256"))
        assertTrue(bundle.at("/subject/" + field).isNull());
      assertEquals("NOT_APPLICABLE", bundle.at("/sources/stock/seckill/status").textValue());
      assertTrue(bundle.path("missingEvidence").toString().contains("SOURCE_UNAVAILABLE"));
      assertEquals("10.00", bundle.at("/subject/amount").textValue());
      assertEquals("LEGACY", bundle.at("/subject/debitMode").textValue());
      assertEquals(1L, stageFileCount(artifacts));
      captureFactsVerified = true;
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      // 4. 每库独立核查。健康库只有初始、采集窗口和当前全表事实都通过才获得清理资格。
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        if (!created.contains(schema)) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("schema", schema);
        String cleanupStep = "VERIFY_EXISTENCE";
        try {
          if (exists(admin, schema) == 0) item.put("status", "ALREADY_ABSENT");
          else if (service.equals("stock")) item.put("status", "PRESERVED_FAILED_SOURCE_PRESENT");
          else if (!initialHealthyFactsVerified
              || !captureFactsVerified
              || !initialHealthyFacts.containsKey(service))
            item.put("status", "PRESERVED_UNVERIFIED_FACTS");
          else {
            cleanupStep = "VERIFY_CURRENT_OWNERSHIP";
            JdbcTemplate database = databases.get(service);
            if (!schema.equals(database.queryForObject("SELECT DATABASE()", String.class)))
              item.put("status", "PRESERVED_UNVERIFIED_OWNERSHIP");
            else {
              cleanupStep = "VERIFY_CURRENT_HEALTHY_FACTS";
              Object currentFacts = healthyFacts(databases, List.of(service)).get(service);
              boolean factsUnchanged = initialHealthyFacts.get(service).equals(currentFacts);
              item.put("currentHealthyFacts", currentFacts);
              item.put("healthyFactsMatchInitial", factsUnchanged);
              if (!factsUnchanged) item.put("status", "PRESERVED_FACTS_CHANGED");
              else if (!stockSourceTableWhitelist(service)
                      .equals(
                          Set.copyOf(
                              admin.queryForList(
                                  "SELECT table_name FROM information_schema.tables WHERE"
                                      + " table_schema=?",
                                  String.class,
                                  schema)))
                  || !noUnresolvedRows(admin, schema)) item.put("status", "PRESERVED_UNRESOLVED");
              else {
                cleanupStep = "DROP_VERIFIED_OWNED_SCHEMA";
                admin.execute("DROP DATABASE " + schema);
                cleanupStep = "VERIFY_ABSENCE";
                assertEquals(0, exists(admin, schema));
                item.put("status", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS");
              }
            }
          }
        } catch (Exception | AssertionError cleanupFailure) {
          item.put("status", "CLEANUP_FAILED");
          Map<String, Object> error = safeError(cleanupFailure);
          error.put("owner", service.toUpperCase(java.util.Locale.ROOT));
          error.put("stage", cleanupStep);
          error.put("category", "CLEANUP_OPERATION_FAILED");
          item.put("safeError", error);
        } finally {
          cleanup.add(item);
        }
      }
      boolean allCreatedSchemasAbsentVerified =
          cleanup.stream()
                  .allMatch(
                      item ->
                          Set.of("ALREADY_ABSENT", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS")
                              .contains(item.get("status")))
              && cleanup.size() == created.size();
      Map<String, Object> cleanupAudit = new LinkedHashMap<>();
      cleanupAudit.put("runId", runId);
      cleanupAudit.put("completedAt", now());
      cleanupAudit.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      cleanupAudit.put("initialHealthyFacts", initialHealthyFacts);
      cleanupAudit.put("initialHealthyFactsVerified", initialHealthyFactsVerified);
      cleanupAudit.put("captureFactsVerified", captureFactsVerified);
      cleanupAudit.put("resources", cleanup);
      cleanupAudit.put("allCreatedSchemasAbsentVerified", allCreatedSchemasAbsentVerified);
      cleanupAudit.put("artifactsPreserved", true);
      cleanupAudit.put("retention", "NO_AUTOMATIC_EXPIRY");
      try {
        writeNew(artifacts.resolve("cleanup-observation.json"), cleanupAudit);
      } catch (Exception auditFailure) {
        IllegalStateException safeFailure = new IllegalStateException("CLEANUP_AUDIT_UNAVAILABLE");
        if (primaryFailure != null) primaryFailure.addSuppressed(safeFailure);
        else throw safeFailure;
      }
      if (!allCreatedSchemasAbsentVerified) {
        AssertionError incomplete = new AssertionError("OWNED_FIXTURE_CLEANUP_INCOMPLETE");
        if (primaryFailure != null) primaryFailure.addSuppressed(incomplete);
        else throw incomplete;
      }
    }
  }

  /** Pay 首次连接不可用时保留未知事实；健康 Order 金额不能替代 Pay 金额或模式。 */
  @Test
  void payInitialCatalogUnavailableStillProducesSafeUnknownSubject() throws Exception {
    // 1. 显式连接门只接受既有本机 MySQL；随机四库创建后逐一证明真实 catalog 归属。
    String sourceUrl = boundedLocalUrl();
    String runId = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    String payNo = "collector-pay-" + runId.substring(6, 18);
    String orderNo = "202610-collector-" + runId.substring(6, 18);
    Path artifacts = Path.of("target", "cm16c-stage-source", runId).toAbsolutePath();
    Files.createDirectories(artifacts);
    JdbcTemplate admin = jdbc(sourceUrl);
    List<String> created = new ArrayList<>();
    Map<String, JdbcTemplate> databases = new LinkedHashMap<>();
    Map<String, Object> verifiedCatalogs = new LinkedHashMap<>();
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("schemaVersion", "CM18_INITIAL_SOURCE_OBSERVATION_V1");
    observation.put("runId", runId);
    observation.put("scenario", "PAY_INITIAL_CATALOG_UNAVAILABLE");
    observation.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
    observation.put("automaticActionsAllowed", false);
    observation.put("retention", "NO_AUTOMATIC_EXPIRY");
    List<Map<String, Object>> cleanup = new ArrayList<>();
    Map<String, Object> initialHealthyFacts = Map.of();
    boolean initialHealthyFactsVerified = false;
    boolean captureFactsVerified = false;
    Throwable primaryFailure = null;
    Map<String, Long> seededRowCounts =
        Map.of(
            "mall_order_202610",
            1L,
            "mall_order_item_202610",
            1L,
            "stock_reservation_request",
            1L,
            "stock_flow",
            1L,
            "stock_sku",
            1L);
    try {
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        assertEquals(0, exists(admin, schema));
        admin.execute(
            "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        created.add(schema);
        JdbcTemplate database =
            new ReadOnlyCaptureJdbc(
                new DriverManagerDataSource(
                    sourceUrl.replace("/cloudmall_user?", "/" + schema + "?"), "root", "root"));
        databases.put(service, database);
        assertEquals(schema, database.queryForObject("SELECT DATABASE()", String.class));
        verifiedCatalogs.put(service, Map.of("schema", schema, "actualCatalogVerified", true));
      }
      assertEquals(4, created.size());
      assertEquals(0, tableCount(admin, owned(runId, "pay")));
      preparePayUnavailableRows(databases, orderNo);
      assertEquals(0, tableCount(admin, owned(runId, "pay")));
      assertEquals(5, tableCount(admin, owned(runId, "user")));
      Map<String, Object> before = healthyFacts(databases, List.of("order", "user", "stock"));
      initialHealthyFacts = before;
      for (String service : List.of("order", "user", "stock")) {
        Map<?, ?> tables = (Map<?, ?>) before.get(service);
        assertEquals(paySourceTableWhitelist(service), tables.keySet());
        for (Object table : tables.keySet())
          assertEquals(
              seededRowCounts.getOrDefault(table, 0L),
              ((Map<?, ?>) tables.get(table)).get("rowCount"));
        assertTrue(
            noUnresolvedRows(admin, owned(runId, service)), "INITIAL_FIXTURE_HAS_UNRESOLVED_ROWS");
      }
      assertEquals(
          1,
          databases
              .get("order")
              .queryForObject(
                  "SELECT COUNT(*) FROM mall_order_202610 WHERE id=11 AND BINARY order_no=BINARY ?"
                      + " AND user_id=7 AND status='PENDING_PAYMENT' AND total_amount=10.00 AND"
                      + " pay_amount=10.00",
                  Integer.class,
                  orderNo));
      assertEquals(
          1,
          databases
              .get("order")
              .queryForObject(
                  "SELECT COUNT(*) FROM mall_order_item_202610 WHERE id=12 AND order_id=11"
                      + " AND BINARY order_no=BINARY ? AND sku_id=22 AND quantity=1"
                      + " AND unit_price=10.00 AND line_amount=10.00",
                  Integer.class,
                  orderNo));
      String reservationItems = "[{\"skuId\":22,\"quantity\":1}]";
      assertEquals(
          1,
          databases
              .get("stock")
              .queryForObject(
                  "SELECT COUNT(*) FROM stock_reservation_request WHERE id=31 AND BINARY"
                      + " order_no=BINARY ? AND scene='NORMAL' AND status='RESERVED' AND"
                      + " JSON_LENGTH(items_snapshot)=1 AND"
                      + " JSON_EXTRACT(items_snapshot,'$[0].skuId')=22 AND"
                      + " JSON_EXTRACT(items_snapshot,'$[0].quantity')=1 AND items_hash=?",
                  Integer.class,
                  orderNo,
                  sha(reservationItems.getBytes(StandardCharsets.UTF_8))));
      assertEquals(
          1,
          databases
              .get("stock")
              .queryForObject(
                  "SELECT COUNT(*) FROM stock_flow WHERE id=32 AND BINARY order_no=BINARY ? AND"
                      + " sku_id=22 AND quantity=1 AND flow_type='RESERVE' AND BINARY"
                      + " idempotency_key=BINARY ?",
                  Integer.class,
                  orderNo,
                  orderNo + ":RESERVE:22"));
      assertEquals(
          1,
          databases
              .get("stock")
              .queryForObject(
                  "SELECT COUNT(*) FROM stock_sku WHERE sku_id=22 AND total_quantity=10"
                      + " AND available_quantity=9 AND reserved_quantity=1 AND sold_quantity=0",
                  Integer.class));
      initialHealthyFactsVerified = true;
      Map<String, Object> fixture = new LinkedHashMap<>();
      fixture.put("runId", runId);
      // 既有离线 profile 的逻辑 namespace/实际建库名单；本测试没有创建 Nacos namespace。
      fixture.put("group", runId);
      fixture.put("databases", created);
      fixture.put("scenarioId", "PAY_INITIAL_CATALOG_UNAVAILABLE");
      fixture.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      fixture.put("createdSchemas", created);
      fixture.put("verifiedCatalogs", verifiedCatalogs);
      fixture.put("payWasCompletelyEmpty", true);
      fixture.put("payNo", payNo);
      fixture.put("orderNo", orderNo);
      fixture.put("userId", 7);
      fixture.put("skuId", 22);
      fixture.put("month", "202610");
      fixture.put("createdAt", now());
      fixture.put("collectorSourceSha256", collectorSourceSha());
      fixture.put("connectTimeoutMillis", 3000);
      fixture.put("socketTimeoutMillis", 5000);
      writeNew(artifacts.resolve("fixture.json"), fixture);

      // 2. 只删除本轮已验证且完全空的 Pay 库；原 DataSource 对象及 URL 保持不变。
      String paySchema = owned(runId, "pay");
      assertTrue(created.contains(paySchema));
      assertEquals(0, tableCount(admin, paySchema));
      DriverManagerDataSource payDataSource =
          (DriverManagerDataSource) databases.get("pay").getDataSource();
      String unchangedPayUrl = payDataSource.getUrl();
      assertEquals(
          paySchema, databases.get("pay").queryForObject("SELECT DATABASE()", String.class));
      assertEquals(0, tableCount(admin, paySchema));
      assertSame(payDataSource, databases.get("pay").getDataSource());
      assertEquals(unchangedPayUrl, payDataSource.getUrl());
      admin.execute("DROP DATABASE " + paySchema);
      assertEquals(0, exists(admin, paySchema));
      assertSame(payDataSource, databases.get("pay").getDataSource());
      assertEquals(unchangedPayUrl, payDataSource.getUrl());
      observation.put("payCatalogDroppedOnlyAfterVerifiedEmpty", true);
      observation.put("samePayDataSourceAndConfiguration", true);
      observation.put("captureStartedAt", now());
      long started = System.nanoTime();
      Path phase = null;
      Exception captureFailure = null;
      for (JdbcTemplate database : databases.values())
        ((ReadOnlyCaptureJdbc) database).checking = true;
      try {
        phase =
            PayTccStageEvidence.capture(
                artifacts,
                runId,
                "initial-pay-unavailable",
                payNo,
                orderNo,
                7,
                22,
                "202610",
                databases,
                Map.of("scenarioId", "PAY_INITIAL_CATALOG_UNAVAILABLE"),
                Map.of(
                    "scenarioId",
                    "PAY_INITIAL_CATALOG_UNAVAILABLE",
                    "fixtureRole",
                    "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
                    "observedOutcome",
                    "PAY_INITIAL_CATALOG_UNAVAILABLE"));
      } catch (Exception unavailable) {
        captureFailure = unavailable;
      } finally {
        for (JdbcTemplate database : databases.values())
          ((ReadOnlyCaptureJdbc) database).checking = false;
      }
      long elapsed = System.nanoTime() - started;
      observation.put("captureEndedAt", now());
      observation.put("captureElapsedNanos", elapsed);
      observation.put("captureReturnedPhase", phase != null);
      observation.put("stageFileCount", stageFileCount(artifacts));
      observation.put("safeError", paySafeError(captureFailure));
      if (phase != null)
        observation.put("phaseRelativePath", artifacts.relativize(phase).toString());
      Map<String, Object> readOnlyAudit = new LinkedHashMap<>();
      for (var entry : databases.entrySet()) {
        ReadOnlyCaptureJdbc tracked = (ReadOnlyCaptureJdbc) entry.getValue();
        readOnlyAudit.put(
            entry.getKey(),
            Map.of(
                "connectionAttempts",
                tracked.connectionAttempts,
                "ownershipStatements",
                tracked.ownershipStatements,
                "businessSelectStatements",
                tracked.businessSelectStatements,
                "writes",
                0,
                "queryTimeoutBoundVerified",
                true));
      }
      observation.put("collectorReadOnlyAudit", readOnlyAudit);
      observation.put("healthyFactsBefore", before);
      observation.put(
          "healthyFactsAfter", healthyFacts(databases, List.of("order", "user", "stock")));
      observation.put("payCatalogAbsentAfterCapture", exists(admin, paySchema) == 0);
      writeNew(artifacts.resolve("capture-observation.json"), observation);

      // 3. red 必须命中真实 1049 且没有阶段文件；期望仍是返回缺证包，不能反转成接受漏包。
      assertTrue(elapsed <= Duration.ofSeconds(30).toNanos(), "CAPTURE_EXCEEDED_30_SECOND_BOUND");
      assertEquals(before, observation.get("healthyFactsAfter"));
      assertEquals(0, exists(admin, paySchema), "COLLECTOR_MUST_NOT_RECREATE_PAY");
      if (captureFailure != null) {
        assertEquals(
            1049,
            paySafeError(captureFailure).get("vendorCode"),
            "RED_MUST_BE_INITIAL_UNKNOWN_CATALOG");
        assertEquals(0L, stageFileCount(artifacts));
      }
      assertNull(captureFailure, "INITIAL_SOURCE_UNAVAILABLE_MUST_RETURN_SAFE_PHASE");
      assertNotNull(phase);
      JsonNode bundle = JSON.readTree(Files.readAllBytes(phase));
      assertEquals("CM18_PHASE_EVIDENCE_V1", bundle.path("schemaVersion").textValue());
      assertEquals("ISOLATED_TEST_PHASE_ONLY", bundle.path("scope").textValue());
      assertFalse(bundle.path("automaticActionsAllowed").booleanValue());
      assertEquals("EVIDENCE_INCOMPLETE", bundle.path("closureClassification").textValue());
      assertEquals("UNAVAILABLE", bundle.at("/sources/pay/status").textValue());
      assertEquals(
          "UNAVAILABLE_NOT_VERIFIED", bundle.at("/sources/pay/connectionOwnership").textValue());
      assertEquals(paySchema, bundle.at("/sources/pay/schema").textValue());
      assertEquals(paySchema, bundle.at("/sources/pay/configuredSchema").textValue());
      assertTrue(bundle.at("/sources/pay/actualSchema").isMissingNode());
      assertTrue(bundle.at("/sources/pay/tables").isEmpty());
      assertTrue(bundle.at("/sources/pay/fence").isMissingNode());
      assertEquals("42000", bundle.at("/sources/pay/safeError/sqlState").textValue());
      assertEquals(1049, bundle.at("/sources/pay/safeError/vendorCode").intValue());
      assertEquals("INITIAL_OWNERSHIP", bundle.at("/sources/pay/safeError/stage").textValue());
      ReadOnlyCaptureJdbc unavailablePay = (ReadOnlyCaptureJdbc) databases.get("pay");
      assertEquals(1, unavailablePay.connectionAttempts);
      assertEquals(0, unavailablePay.ownershipStatements);
      assertEquals(0, unavailablePay.businessSelectStatements);
      for (String service : List.of("order", "user", "stock")) {
        assertEquals("OBSERVED", bundle.at("/sources/" + service + "/status").textValue());
        assertEquals(
            "VERIFIED", bundle.at("/sources/" + service + "/connectionOwnership").textValue());
        assertEquals(
            owned(runId, service), bundle.at("/sources/" + service + "/schema").textValue());
        assertEquals(
            owned(runId, service),
            bundle.at("/sources/" + service + "/configuredSchema").textValue());
        ReadOnlyCaptureJdbc healthySource = (ReadOnlyCaptureJdbc) databases.get(service);
        assertTrue(healthySource.ownershipStatements > 0, "HEALTHY_SOURCE_OWNERSHIP_NOT_OBSERVED");
        assertEquals(healthySource.connectionAttempts, healthySource.ownershipStatements);
      }
      assertEquals("PAY", bundle.at("/sources/pay/safeError/owner").textValue());
      for (String service : List.of("order", "user", "stock"))
        for (String table : paySourceTableWhitelist(service)) {
          long expectedCount = seededRowCounts.getOrDefault(table, 0L);
          assertEquals(
              expectedCount == 0 ? "EMPTY" : "OBSERVED",
              bundle.at("/sources/" + service + "/tables/" + table + "/status").textValue());
          assertEquals(
              expectedCount,
              bundle.at("/sources/" + service + "/tables/" + table + "/rowCount").longValue());
        }
      for (String field :
          List.of("anchorBeforeDigest", "anchorAfterDigest", "anchorAfter", "sourceChanged"))
        assertTrue(bundle.at("/sources/pay/" + field).isNull());
      assertEquals("NOT_OBSERVED", bundle.at("/sources/tc/status").textValue());
      assertTrue(bundle.at("/sources/tc/xidSha256").isNull());
      assertTrue(bundle.at("/sources/tc/directionProvenByTc").isBoolean());
      assertFalse(bundle.at("/sources/tc/directionProvenByTc").booleanValue());
      for (String field :
          List.of(
              "amount",
              "debitMode",
              "executionId",
              "xidSha256",
              "operation_key_sha256",
              "ledger_key_sha256",
              "payBranchSha256",
              "userBranchSha256",
              "refundKeySha256")) assertTrue(bundle.at("/subject/" + field).isNull());
      assertEquals(payNo, bundle.at("/subject/payNo").textValue());
      assertEquals(orderNo, bundle.at("/subject/orderNo").textValue());
      assertEquals(7L, bundle.at("/subject/userId").longValue());
      assertEquals(22L, bundle.at("/subject/skuId").longValue());
      assertEquals("202610", bundle.at("/subject/month").textValue());
      List<String> missingCodes = new ArrayList<>();
      for (JsonNode missing : bundle.path("missingEvidence")) missingCodes.add(missing.textValue());
      assertTrue(missingCodes.contains("SOURCE_UNAVAILABLE:PAY"));
      assertTrue(missingCodes.contains("SOURCE_UNAVAILABLE:PAY_ANCHOR"));
      assertEquals("NOT_APPLICABLE", bundle.at("/sources/stock/seckill/status").textValue());
      assertEquals(1L, stageFileCount(artifacts));
      captureFactsVerified = true;
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      // 4. 每库独立核查。健康库只有初始、采集窗口和当前全表事实都通过才获得清理资格。
      for (String service : SERVICES) {
        String schema = owned(runId, service);
        if (!created.contains(schema)) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("schema", schema);
        String cleanupStep = "VERIFY_EXISTENCE";
        try {
          if (exists(admin, schema) == 0) item.put("status", "ALREADY_ABSENT");
          else if (service.equals("pay")) item.put("status", "PRESERVED_FAILED_SOURCE_PRESENT");
          else if (!initialHealthyFactsVerified
              || !captureFactsVerified
              || !initialHealthyFacts.containsKey(service))
            item.put("status", "PRESERVED_UNVERIFIED_FACTS");
          else {
            cleanupStep = "VERIFY_CURRENT_OWNERSHIP";
            JdbcTemplate database = databases.get(service);
            if (!schema.equals(database.queryForObject("SELECT DATABASE()", String.class)))
              item.put("status", "PRESERVED_UNVERIFIED_OWNERSHIP");
            else {
              cleanupStep = "VERIFY_CURRENT_HEALTHY_FACTS";
              Object currentFacts = healthyFacts(databases, List.of(service)).get(service);
              boolean factsUnchanged = initialHealthyFacts.get(service).equals(currentFacts);
              item.put("currentHealthyFacts", currentFacts);
              item.put("healthyFactsMatchInitial", factsUnchanged);
              if (!factsUnchanged) item.put("status", "PRESERVED_FACTS_CHANGED");
              else if (!paySourceTableWhitelist(service)
                      .equals(
                          Set.copyOf(
                              admin.queryForList(
                                  "SELECT table_name FROM information_schema.tables WHERE"
                                      + " table_schema=?",
                                  String.class,
                                  schema)))
                  || !noUnresolvedRows(admin, schema)) item.put("status", "PRESERVED_UNRESOLVED");
              else {
                cleanupStep = "DROP_VERIFIED_OWNED_SCHEMA";
                admin.execute("DROP DATABASE " + schema);
                cleanupStep = "VERIFY_ABSENCE";
                assertEquals(0, exists(admin, schema));
                item.put("status", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS");
              }
            }
          }
        } catch (Exception | AssertionError cleanupFailure) {
          item.put("status", "CLEANUP_FAILED");
          Map<String, Object> error = safeError(cleanupFailure);
          error.put("owner", service.toUpperCase(java.util.Locale.ROOT));
          error.put("stage", cleanupStep);
          error.put("category", "CLEANUP_OPERATION_FAILED");
          item.put("safeError", error);
        } finally {
          cleanup.add(item);
        }
      }
      boolean allCreatedSchemasAbsentVerified =
          cleanup.stream()
                  .allMatch(
                      item ->
                          Set.of("ALREADY_ABSENT", "DROPPED_OWNED_AFTER_NO_UNRESOLVED_ROWS")
                              .contains(item.get("status")))
              && cleanup.size() == created.size();
      Map<String, Object> cleanupAudit = new LinkedHashMap<>();
      cleanupAudit.put("runId", runId);
      cleanupAudit.put("completedAt", now());
      cleanupAudit.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
      cleanupAudit.put("initialHealthyFacts", initialHealthyFacts);
      cleanupAudit.put("initialHealthyFactsVerified", initialHealthyFactsVerified);
      cleanupAudit.put("captureFactsVerified", captureFactsVerified);
      cleanupAudit.put("resources", cleanup);
      cleanupAudit.put("allCreatedSchemasAbsentVerified", allCreatedSchemasAbsentVerified);
      cleanupAudit.put("artifactsPreserved", true);
      cleanupAudit.put("retention", "NO_AUTOMATIC_EXPIRY");
      try {
        writeNew(artifacts.resolve("cleanup-observation.json"), cleanupAudit);
      } catch (Exception auditFailure) {
        IllegalStateException safeFailure = new IllegalStateException("CLEANUP_AUDIT_UNAVAILABLE");
        if (primaryFailure != null) primaryFailure.addSuppressed(safeFailure);
        else throw safeFailure;
      }
      if (!allCreatedSchemasAbsentVerified) {
        AssertionError incomplete = new AssertionError("OWNED_FIXTURE_CLEANUP_INCOMPLETE");
        if (primaryFailure != null) primaryFailure.addSuppressed(incomplete);
        else throw incomplete;
      }
    }
  }

  /** 仅在 capture 窗口追踪真实 JDBC；DataSource 保持原类型，setup/cleanup 不计入只读断言。 */
  private static final class ReadOnlyCaptureJdbc extends JdbcTemplate {
    /** 只有 collector 调用期间开启；不是数据库或业务的替身。 */
    private boolean checking;

    /** execute 进入真实 DataSource 的次数，包含初次 getConnection 失败。 */
    private int connectionAttempts;

    /** 真正成功连接后发出的 catalog 验证 SELECT 数。 */
    private int ownershipStatements;

    /** 固定业务 SELECT 数，故障 User 必须为零。 */
    private int businessSelectStatements;

    private ReadOnlyCaptureJdbc(DriverManagerDataSource source) {
      super(source);
      setQueryTimeout(5);
    }

    /** 对真实连接加只读观察代理；所有 SQLException 保留原 cause 供 Spring 正常翻译。 */
    @Override
    public <T> T execute(ConnectionCallback<T> action) {
      if (!checking) return super.execute(action);
      connectionAttempts++;
      return super.execute(
          (ConnectionCallback<T>)
              connection -> action.doInConnection(readOnlyConnection(connection)));
    }

    private Connection readOnlyConnection(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                String name = method.getName();
                if (Set.of("commit", "createStatement", "prepareCall").contains(name))
                  throw new AssertionError("COLLECTOR_WRITE_OR_UNOBSERVED_STATEMENT");
                if (name.equals("prepareStatement")) {
                  String sql = (String) arguments[0];
                  assertTrue(
                      sql.startsWith("SELECT ") && !sql.contains(";"),
                      "COLLECTOR_ONLY_SELECT_ALLOWED");
                  if (sql.equals("SELECT DATABASE()")) ownershipStatements++;
                  else businessSelectStatements++;
                  PreparedStatement statement =
                      (PreparedStatement) call(connection, method, arguments);
                  int[] queryTimeout = {0};
                  return Proxy.newProxyInstance(
                      PreparedStatement.class.getClassLoader(),
                      new Class<?>[] {PreparedStatement.class},
                      (statementProxy, statementMethod, statementArguments) -> {
                        if (statementMethod.getName().equals("setQueryTimeout"))
                          queryTimeout[0] = (Integer) statementArguments[0];
                        if (statementMethod.getName().startsWith("execute"))
                          assertTrue(
                              queryTimeout[0] > 0 && queryTimeout[0] <= 5,
                              "COLLECTOR_QUERY_TIMEOUT_REQUIRED");
                        return call(statement, statementMethod, statementArguments);
                      });
                }
                return call(connection, method, arguments);
              });
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }

  /** 只接受既有本机 catalog 的显式 IT 门；复用认证/TLS 参数并固定私有连接超时。 */
  private static String boundedLocalUrl() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.startsWith("jdbc:mysql://"))
      throw new IllegalStateException("EXPLICIT_LOCAL_MYSQL_IT_URL_REQUIRED");
    URI uri = URI.create(url.substring(5));
    if (!Set.of("localhost", "127.0.0.1").contains(uri.getHost())
        || uri.getPort() != 13307
        || !"/cloudmall_user".equals(uri.getRawPath())
        || uri.getUserInfo() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("UNSUPPORTED_MYSQL_IT_TARGET");
    Map<String, String> options = new LinkedHashMap<>();
    if (uri.getRawQuery() != null)
      for (String pair : uri.getRawQuery().split("&")) {
        String[] parts = pair.split("=", -1);
        if (parts.length != 2
            || !Set.of(
                    "useSSL",
                    "allowPublicKeyRetrieval",
                    "serverTimezone",
                    "connectTimeout",
                    "socketTimeout")
                .contains(parts[0])
            || options.putIfAbsent(parts[0], parts[1]) != null)
          throw new IllegalArgumentException("UNSUPPORTED_MYSQL_IT_OPTION");
      }
    for (String key : List.of("useSSL", "allowPublicKeyRetrieval"))
      if (options.containsKey(key) && !Set.of("true", "false").contains(options.get(key)))
        throw new IllegalArgumentException("UNSUPPORTED_MYSQL_IT_OPTION");
    if (options.containsKey("serverTimezone")
        && !"Asia/Shanghai".equals(options.get("serverTimezone")))
      throw new IllegalArgumentException("UNSUPPORTED_MYSQL_IT_TIMEZONE");
    options.put("connectTimeout", "3000");
    options.put("socketTimeout", "5000");
    options.putIfAbsent("serverTimezone", "Asia/Shanghai");
    List<String> query = new ArrayList<>();
    options.forEach((key, value) -> query.add(key + "=" + value));
    return "jdbc:mysql://" + uri.getHost() + ":13307/cloudmall_user?" + String.join("&", query);
  }

  private static JdbcTemplate jdbc(String url) {
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  private static String owned(String runId, String service) {
    if (!runId.matches("cm16c_[0-9a-f]{32}") || !SERVICES.contains(service))
      throw new IllegalArgumentException("INVALID_OWNED_SCHEMA");
    return runId + "_" + service;
  }

  private static int exists(JdbcTemplate admin, String schema) {
    return admin.queryForObject(
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
        Integer.class,
        schema);
  }

  private static int tableCount(JdbcTemplate admin, String schema) {
    return admin.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",
        Integer.class,
        schema);
  }

  /** 最小列来自 collector 固定查询；无原表复制、无账号、账本、冻结或 TC 行。 */
  private static void prepareCollectorOnlyRows(
      Map<String, JdbcTemplate> databases, String payNo, String orderNo) {
    JdbcTemplate pay = databases.get("pay");
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel"))
      pay.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id"
              + " BIGINT,amount DECIMAL(18,2),debit_mode VARCHAR(16),status VARCHAR(32))");
    pay.execute("CREATE TABLE pay_callback_log (id BIGINT PRIMARY KEY,pay_no VARCHAR(64))");
    pay.update(
        "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,debit_mode,status)"
            + " VALUES(1,?,?,7,10.00,'LEGACY','PENDING')",
        payNo,
        orderNo);
    JdbcTemplate order = databases.get("order");
    order.execute(
        "CREATE TABLE mall_order_202610 (id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id"
            + " BIGINT,status VARCHAR(32),total_amount DECIMAL(18,2),pay_amount DECIMAL(18,2))");
    order.execute(
        "CREATE TABLE mall_order_item_202610 (id BIGINT PRIMARY KEY,order_id BIGINT,order_no"
            + " VARCHAR(64),sku_id BIGINT,quantity INT,unit_price DECIMAL(18,2),line_amount"
            + " DECIMAL(18,2))");
    order.execute("CREATE TABLE order_stock_action (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.execute("CREATE TABLE order_outbox (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.update(
        "INSERT INTO mall_order_202610 VALUES(11,?,7,'PENDING_PAYMENT',10.00,10.00)", orderNo);
    order.update("INSERT INTO mall_order_item_202610 VALUES(12,11,?,22,1,10.00,10.00)", orderNo);
    JdbcTemplate stock = databases.get("stock");
    stock.execute(
        "CREATE TABLE stock_reservation_request (id BIGINT PRIMARY KEY,order_no VARCHAR(64),scene"
            + " VARCHAR(16),status VARCHAR(32),items_snapshot JSON,items_hash VARCHAR(64))");
    stock.execute(
        "CREATE TABLE stock_flow (id BIGINT PRIMARY KEY,order_no VARCHAR(64),sku_id BIGINT,quantity"
            + " INT,flow_type VARCHAR(16),idempotency_key VARCHAR(128))");
    stock.execute(
        "CREATE TABLE stock_sku (sku_id BIGINT PRIMARY KEY,total_quantity INT,available_quantity"
            + " INT,reserved_quantity INT,sold_quantity INT)");
    String items = "[{\"skuId\":22,\"quantity\":1}]";
    stock.update(
        "INSERT INTO stock_reservation_request VALUES(31,?,'NORMAL','RESERVED',?,?)",
        orderNo,
        items,
        sha(items.getBytes(StandardCharsets.UTF_8)));
    stock.update(
        "INSERT INTO stock_flow VALUES(32,?,22,1,'RESERVE',?)", orderNo, orderNo + ":RESERVE:22");
    stock.update("INSERT INTO stock_sku VALUES(22,10,9,1,0)");
  }

  /** 全部健康 fixture 表清单、行数和 checksum；既不读取原库行也不输出账户凭据。 */
  private static Map<String, Object> healthyFacts(Map<String, JdbcTemplate> databases) {
    Map<String, Object> facts = new LinkedHashMap<>();
    for (String service : List.of("pay", "order", "stock")) {
      JdbcTemplate database = databases.get(service);
      Map<String, Object> tables = new LinkedHashMap<>();
      for (String table : database.queryForList("SHOW TABLES", String.class)) {
        if (!table.matches("[a-z0-9_]+")) throw new IllegalStateException("INVALID_FIXTURE_TABLE");
        tables.put(
            table,
            Map.of(
                "rowCount",
                database.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                "checksum",
                database.queryForMap("CHECKSUM TABLE " + table).get("Checksum")));
      }
      facts.put(service, tables);
    }
    return facts;
  }

  private static boolean noUnresolvedRows(JdbcTemplate admin, String schema) {
    if (!schema.matches("cm16c_[0-9a-f]{32}_(pay|user|order|stock)"))
      throw new IllegalArgumentException("INVALID_CLEANUP_SCHEMA");
    for (String table :
        admin.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=?",
            String.class,
            schema))
      if (UNRESOLVED_TABLES.contains(table)
          && admin.queryForObject("SELECT COUNT(*) FROM " + schema + "." + table, Long.class) != 0)
        return false;
    return true;
  }

  /** 最多八层 cause，只保留类别与 SQLState/vendorCode，不保留 message、stack 或 URL。 */
  private static Map<String, Object> safeError(Throwable failure) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("stage", "INITIAL_OWNERSHIP");
    error.put("owner", "USER");
    error.put("category", failure == null ? "NONE" : "JDBC_SOURCE_UNAVAILABLE");
    if (failure != null) error.put("exceptionType", failure.getClass().getSimpleName());
    for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause())
      if (failure instanceof SQLException sql) {
        if (sql.getSQLState() != null && sql.getSQLState().matches("[A-Za-z0-9]{1,5}"))
          error.put("sqlState", sql.getSQLState());
        error.put("vendorCode", sql.getErrorCode());
        break;
      }
    return error;
  }

  /** Order 始终保持零表；只准备原 Pay/Stock SQL fixture 和五张 User 空投影表。 */
  private static void prepareOrderUnavailableRows(
      Map<String, JdbcTemplate> databases, String payNo, String orderNo) {
    JdbcTemplate pay = databases.get("pay");
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel"))
      pay.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id"
              + " BIGINT,amount DECIMAL(18,2),debit_mode VARCHAR(16),status VARCHAR(32))");
    pay.execute("CREATE TABLE pay_callback_log (id BIGINT PRIMARY KEY,pay_no VARCHAR(64))");
    pay.update(
        "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,debit_mode,status)"
            + " VALUES(1,?,?,7,10.00,'LEGACY','PENDING')",
        payNo,
        orderNo);
    JdbcTemplate stock = databases.get("stock");
    stock.execute(
        "CREATE TABLE stock_reservation_request (id BIGINT PRIMARY KEY,order_no VARCHAR(64),scene"
            + " VARCHAR(16),status VARCHAR(32),items_snapshot JSON,items_hash VARCHAR(64))");
    stock.execute(
        "CREATE TABLE stock_flow (id BIGINT PRIMARY KEY,order_no VARCHAR(64),sku_id BIGINT,quantity"
            + " INT,flow_type VARCHAR(16),idempotency_key VARCHAR(128))");
    stock.execute(
        "CREATE TABLE stock_sku (sku_id BIGINT PRIMARY KEY,total_quantity INT,available_quantity"
            + " INT,reserved_quantity INT,sold_quantity INT)");
    String items = "[{\"skuId\":22,\"quantity\":1}]";
    stock.update(
        "INSERT INTO stock_reservation_request VALUES(31,?,'NORMAL','RESERVED',?,?)",
        orderNo,
        items,
        sha(items.getBytes(StandardCharsets.UTF_8)));
    stock.update(
        "INSERT INTO stock_flow VALUES(32,?,22,1,'RESERVE',?)", orderNo, orderNo + ":RESERVE:22");
    stock.update("INSERT INTO stock_sku VALUES(22,10,9,1,0)");
    JdbcTemplate user = databases.get("user");
    user.execute(
        "CREATE TABLE mall_user (id BIGINT PRIMARY KEY,balance DECIMAL(18,2),frozen_balance"
            + " DECIMAL(18,2),status INT)");
    for (String table :
        List.of("user_tcc_receive", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger"))
      user.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id BIGINT)");
  }

  /** 本 Order 用例的健康三源全表清单、行数和 checksum，不读取缺失来源。 */
  private static Map<String, Object> healthyFacts(
      Map<String, JdbcTemplate> databases, List<String> services) {
    Map<String, Object> facts = new LinkedHashMap<>();
    for (String service : services) {
      JdbcTemplate database = databases.get(service);
      Map<String, Object> tables = new LinkedHashMap<>();
      for (String table : database.queryForList("SHOW TABLES", String.class)) {
        if (!table.matches("[a-z0-9_]+")) throw new IllegalStateException("INVALID_FIXTURE_TABLE");
        tables.put(
            table,
            Map.of(
                "rowCount",
                database.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                "checksum",
                database.queryForMap("CHECKSUM TABLE " + table).get("Checksum")));
      }
      facts.put(service, tables);
    }
    return facts;
  }

  /** 只读核对本 Order 失败用例准备的固定表集合，未知表不得取得清理资格。 */
  private static Set<String> orderSourceTableWhitelist(String service) {
    return switch (service) {
      case "pay" ->
          Set.of(
              "pay_record",
              "pay_debit_intent",
              "pay_tcc_execution",
              "pay_tcc_dispatch",
              "pay_tcc_receipt",
              "pay_tcc_delivery",
              "pay_tcc_cancel",
              "pay_callback_log");
      case "user" ->
          Set.of(
              "mall_user",
              "user_tcc_receive",
              "balance_debit_attempt",
              "balance_tcc_debit",
              "balance_ledger");
      case "order" -> Set.of();
      case "stock" -> Set.of("stock_reservation_request", "stock_flow", "stock_sku");
      default -> throw new IllegalArgumentException("INVALID_ORDER_SOURCE_SERVICE");
    };
  }

  /** 复用原安全异常投影，仅本用例固定标明 Order 责任源，不改变原 User helper。 */
  private static Map<String, Object> safeError(Throwable failure, String owner) {
    if (!"ORDER".equals(owner)) throw new IllegalArgumentException("INVALID_ORDER_SOURCE_OWNER");
    Map<String, Object> error = safeError(failure);
    error.put("owner", owner);
    return error;
  }

  /** Stock 用例仅准备 Pay/Order 固定 SQL 种子与 User 空表，完全不访问 Stock 建表。 */
  private static void prepareStockUnavailableRows(
      Map<String, JdbcTemplate> databases, String payNo, String orderNo) {
    JdbcTemplate pay = databases.get("pay");
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel"))
      pay.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id"
              + " BIGINT,amount DECIMAL(18,2),debit_mode VARCHAR(16),status VARCHAR(32))");
    pay.execute("CREATE TABLE pay_callback_log (id BIGINT PRIMARY KEY,pay_no VARCHAR(64))");
    pay.update(
        "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,debit_mode,status)"
            + " VALUES(1,?,?,7,10.00,'LEGACY','PENDING')",
        payNo,
        orderNo);
    JdbcTemplate order = databases.get("order");
    order.execute(
        "CREATE TABLE mall_order_202610 (id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id"
            + " BIGINT,status VARCHAR(32),total_amount DECIMAL(18,2),pay_amount DECIMAL(18,2))");
    order.execute(
        "CREATE TABLE mall_order_item_202610 (id BIGINT PRIMARY KEY,order_id BIGINT,order_no"
            + " VARCHAR(64),sku_id BIGINT,quantity INT,unit_price DECIMAL(18,2),line_amount"
            + " DECIMAL(18,2))");
    order.execute("CREATE TABLE order_stock_action (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.execute("CREATE TABLE order_outbox (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.update(
        "INSERT INTO mall_order_202610 VALUES(11,?,7,'PENDING_PAYMENT',10.00,10.00)", orderNo);
    order.update("INSERT INTO mall_order_item_202610 VALUES(12,11,?,22,1,10.00,10.00)", orderNo);
    JdbcTemplate user = databases.get("user");
    user.execute(
        "CREATE TABLE mall_user (id BIGINT PRIMARY KEY,balance DECIMAL(18,2),frozen_balance"
            + " DECIMAL(18,2),status INT)");
    for (String table :
        List.of("user_tcc_receive", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger"))
      user.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id BIGINT)");
  }

  /** 本 Stock 用例独立固定表白名单，不扩展原 Order 用例的清理资格。 */
  private static Set<String> stockSourceTableWhitelist(String service) {
    return switch (service) {
      case "pay", "user" -> orderSourceTableWhitelist(service);
      case "order" ->
          Set.of(
              "mall_order_202610", "mall_order_item_202610", "order_stock_action", "order_outbox");
      case "stock" -> Set.of();
      default -> throw new IllegalArgumentException("INVALID_STOCK_SOURCE_SERVICE");
    };
  }

  /** 复用原安全 cause 投影，只为本用例固定标明 Stock 责任源。 */
  private static Map<String, Object> stockSafeError(Throwable failure) {
    Map<String, Object> error = safeError(failure);
    error.put("owner", "STOCK");
    return error;
  }

  /** Pay 保持零表；只准备原 Order/Stock SQL 种子和 User 五张空投影表。 */
  private static void preparePayUnavailableRows(
      Map<String, JdbcTemplate> databases, String orderNo) {
    JdbcTemplate order = databases.get("order");
    order.execute(
        "CREATE TABLE mall_order_202610 (id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id"
            + " BIGINT,status VARCHAR(32),total_amount DECIMAL(18,2),pay_amount DECIMAL(18,2))");
    order.execute(
        "CREATE TABLE mall_order_item_202610 (id BIGINT PRIMARY KEY,order_id BIGINT,order_no"
            + " VARCHAR(64),sku_id BIGINT,quantity INT,unit_price DECIMAL(18,2),line_amount"
            + " DECIMAL(18,2))");
    order.execute("CREATE TABLE order_stock_action (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.execute("CREATE TABLE order_outbox (id BIGINT PRIMARY KEY,order_no VARCHAR(64))");
    order.update(
        "INSERT INTO mall_order_202610 VALUES(11,?,7,'PENDING_PAYMENT',10.00,10.00)", orderNo);
    order.update("INSERT INTO mall_order_item_202610 VALUES(12,11,?,22,1,10.00,10.00)", orderNo);
    JdbcTemplate stock = databases.get("stock");
    stock.execute(
        "CREATE TABLE stock_reservation_request (id BIGINT PRIMARY KEY,order_no VARCHAR(64),scene"
            + " VARCHAR(16),status VARCHAR(32),items_snapshot JSON,items_hash VARCHAR(64))");
    stock.execute(
        "CREATE TABLE stock_flow (id BIGINT PRIMARY KEY,order_no VARCHAR(64),sku_id BIGINT,quantity"
            + " INT,flow_type VARCHAR(16),idempotency_key VARCHAR(128))");
    stock.execute(
        "CREATE TABLE stock_sku (sku_id BIGINT PRIMARY KEY,total_quantity INT,available_quantity"
            + " INT,reserved_quantity INT,sold_quantity INT)");
    String items = "[{\"skuId\":22,\"quantity\":1}]";
    stock.update(
        "INSERT INTO stock_reservation_request VALUES(31,?,'NORMAL','RESERVED',?,?)",
        orderNo,
        items,
        sha(items.getBytes(StandardCharsets.UTF_8)));
    stock.update(
        "INSERT INTO stock_flow VALUES(32,?,22,1,'RESERVE',?)", orderNo, orderNo + ":RESERVE:22");
    stock.update("INSERT INTO stock_sku VALUES(22,10,9,1,0)");
    JdbcTemplate user = databases.get("user");
    user.execute(
        "CREATE TABLE mall_user (id BIGINT PRIMARY KEY,balance DECIMAL(18,2),frozen_balance"
            + " DECIMAL(18,2),status INT)");
    for (String table :
        List.of("user_tcc_receive", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger"))
      user.execute(
          "CREATE TABLE "
              + table
              + " (id BIGINT PRIMARY KEY,pay_no VARCHAR(64),order_no VARCHAR(64),user_id BIGINT)");
  }

  /** 本 Pay 用例的固定表清单，直接复用旧健康角色对应的集合而不修改旧 helper。 */
  private static Set<String> paySourceTableWhitelist(String service) {
    return switch (service) {
      case "user", "stock" -> orderSourceTableWhitelist(service);
      case "order" -> stockSourceTableWhitelist(service);
      case "pay" -> Set.of();
      default -> throw new IllegalArgumentException("INVALID_PAY_SOURCE_SERVICE");
    };
  }

  /** 固定 Pay 责任源，仍只保留原有安全 cause 投影字段。 */
  private static Map<String, Object> paySafeError(Throwable failure) {
    Map<String, Object> error = safeError(failure);
    error.put("owner", "PAY");
    return error;
  }

  private static long stageFileCount(Path artifacts) throws Exception {
    try (var files = Files.list(artifacts)) {
      return files
          .filter(path -> path.getFileName().toString().startsWith("stage-evidence-"))
          .count();
    }
  }

  private static String collectorSourceSha() throws Exception {
    Path source =
        Path.of(
            System.getProperty("basedir", "."),
            "src",
            "test",
            "java",
            "com",
            "cloudmall",
            "pay",
            "PayTccStageEvidence.java");
    return Files.isRegularFile(source) ? sha(Files.readAllBytes(source)) : "UNAVAILABLE";
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA256_UNAVAILABLE");
    }
  }

  private static String now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
  }

  private static void writeNew(Path path, Object value) throws Exception {
    Files.write(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
        StandardOpenOption.CREATE_NEW);
  }
}
