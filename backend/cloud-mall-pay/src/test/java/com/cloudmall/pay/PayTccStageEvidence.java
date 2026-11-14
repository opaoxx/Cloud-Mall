package com.cloudmall.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.TransactionManagerHolder;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 仅采集当前隔离 fixture 的逐笔阶段事实；不推进业务、TC 或消息交付。 */
final class PayTccStageEvidence {
  /** 与评审冻结的有界集合上限一致，多读一行仅用于识别超限。 */
  private static final int ROW_BOUND = 100;

  /** JSON 只写安全投影，禁止把连接、进程或任意业务对象直接序列化。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 固定本机管理入口；仅使用 GET，不访问消息内容端点。 */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 数据库事实允许输出的固定列名，未知列仅报告名称，不输出值。 */
  private static final Set<String> SAFE_COLUMNS =
      Set.of(
          "id",
          "pay_no",
          "order_no",
          "user_id",
          "amount",
          "status",
          "state",
          "decision",
          "debit_mode",
          "mode",
          "xid",
          "branch_id",
          "user_branch_id",
          "operation_key",
          "ledger_key",
          "original_operation_key",
          "refund_operation_key",
          "operation_type",
          "balance",
          "frozen_balance",
          "balance_before",
          "balance_after",
          "balance_after_try",
          "attempts",
          "recovery_attempts",
          "lease_owner",
          "lease_until",
          "recovery_owner",
          "recovery_lease_until",
          "last_error",
          "recovery_last_error",
          "next_retry_at",
          "next_recovery_at",
          "created_at",
          "updated_at",
          "paid_at",
          "cancelled_at",
          "post_attempted_at",
          "processed_at",
          "callback_id",
          "callback_status",
          "payload",
          "action_name",
          "gmt_create",
          "gmt_modified",
          "order_id",
          "product_id",
          "sku_id",
          "quantity",
          "unit_price",
          "line_amount",
          "total_amount",
          "pay_amount",
          "expire_at",
          "action_type",
          "event_id",
          "event_type",
          "idempotency_key",
          "published_at",
          "scene",
          "items_snapshot",
          "items_hash",
          "result_code",
          "flow_type",
          "total_quantity",
          "available_quantity",
          "reserved_quantity",
          "sold_quantity",
          "version");

  private PayTccStageEvidence() {}

  /**
   * 为指定 fixture/阶段生成不可覆盖的安全 JSON 包。输入数据库必须精确归属本轮随机命名空间。
   *
   * @return 已写入的独立阶段包；不完整事实仍落包，非法资源归属直接拒绝
   * @throws Exception 文件无法保存、输入不合法或数据库不属于当前 fixture 时抛出
   */
  public static Path capture(
      Path artifacts,
      String batch,
      String phase,
      String payNo,
      String orderNo,
      long userId,
      long skuId,
      String month,
      Map<String, JdbcTemplate> databases,
      Map<String, Object> processes,
      Map<String, Object> verifiedAssertions)
      throws Exception {
    return capture(
        13307,
        15674,
        artifacts,
        batch,
        phase,
        payNo,
        orderNo,
        userId,
        skuId,
        month,
        databases,
        processes,
        verifiedAssertions);
  }

  /**
   * 从明确的 Rabbit 管理入口采集本轮 vhost 元数据；数据库和资金采集语义保持。
   *
   * @return 不可覆盖的阶段证据文件
   * @throws Exception 输入非法或证据保存失败时抛出
   */
  public static Path capture(
      PayTccDeliveryEnvironment environment,
      Path artifacts,
      String batch,
      String phase,
      String payNo,
      String orderNo,
      long userId,
      long skuId,
      String month,
      Map<String, JdbcTemplate> databases,
      Map<String, Object> processes,
      Map<String, Object> verifiedAssertions)
      throws Exception {
    return capture(
        environment.mysqlPort(),
        environment.rabbitManagementPort(),
        artifacts,
        batch,
        phase,
        payNo,
        orderNo,
        userId,
        skuId,
        month,
        databases,
        processes,
        verifiedAssertions);
  }

  private static Path capture(
      int mysqlPort,
      int rabbitManagementPort,
      Path artifacts,
      String batch,
      String phase,
      String payNo,
      String orderNo,
      long userId,
      long skuId,
      String month,
      Map<String, JdbcTemplate> databases,
      Map<String, Object> processes,
      Map<String, Object> verifiedAssertions)
      throws Exception {
    // 1. 先验证全部来源归属，禁止以 collector 读取原业务库或任意表。
    require(batch != null && batch.matches("cm16c_[0-9a-f]{32}"), "fixture runId invalid");
    require(phase != null && phase.matches("[A-Za-z0-9_-]{1,64}"), "phase invalid");
    require(payNo != null && payNo.matches("[A-Za-z0-9_-]{1,64}"), "payNo invalid");
    require(orderNo != null && orderNo.matches("[A-Za-z0-9_-]{1,64}"), "orderNo invalid");
    require(month != null && month.matches("[0-9]{6}"), "month invalid");
    YearMonth.parse(month, DateTimeFormatter.ofPattern("uuuuMM"));
    require(userId > 0 && skuId > 0, "subject identity invalid");
    require(
        artifacts != null && databases != null && processes != null && verifiedAssertions != null,
        "capture maps required");
    Map<String, SourceBinding> bindings = new LinkedHashMap<>();
    for (String service : List.of("pay", "user", "order", "stock"))
      bindings.put(
          service, sourceBinding(databases.get(service), batch + "_" + service, mysqlPort));

    // 2. 静态意图全部合法之后才连接；每源初始一次，失败源本次不再重读。
    long captureStartedNanos = System.nanoTime();
    String startedAt = now();
    List<String> missing = new ArrayList<>();
    missing.add("TC_BRANCH_NOT_OBSERVED:TC");
    missing.add("MQ_EVENT_CAUSALITY_UNPROVEN:MQ_CONSUMER");
    Map<String, Map<String, Object>> initial = new LinkedHashMap<>();
    for (var entry : bindings.entrySet())
      initial.put(entry.getKey(), initialOwnership(entry.getValue(), missing));
    Map<String, Object> sources = new LinkedHashMap<>();
    Map<String, Object> pay =
        snapshotIfAvailable(
            bindings.get("pay"), initial.get("pay"), payQueries(payNo, orderNo), missing);
    sources.put("pay", pay);
    List<Map<String, Object>> executions = rows(pay, "pay_tcc_execution");
    String originalXid = originalXid(executions, payNo, orderNo, userId);
    Set<String> fenceXids = new LinkedHashSet<>();
    collectXids(pay, fenceXids);
    Map<String, Query> userQueries = new LinkedHashMap<>();
    for (String table :
        List.of("user_tcc_receive", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger"))
      userQueries.put(table, subjectQuery(table, payNo, orderNo));
    userQueries.put(
        "mall_user",
        new Query(
            "SELECT id,balance,frozen_balance,status FROM mall_user WHERE id=?", List.of(userId)));
    Map<String, Object> user =
        snapshotIfAvailable(bindings.get("user"), initial.get("user"), userQueries, missing);
    collectXids(user, fenceXids);
    if (!"UNAVAILABLE".equals(user.get("status")))
      user.put("fence", databaseSnapshot(bindings.get("user"), fenceQueries(fenceXids), missing));
    if (!"UNAVAILABLE".equals(pay.get("status")))
      pay.put("fence", databaseSnapshot(bindings.get("pay"), fenceQueries(fenceXids), missing));
    sources.put("user", user);
    Map<String, Query> orderQueries = new LinkedHashMap<>();
    for (String table :
        List.of(
            "mall_order_" + month,
            "mall_order_item_" + month,
            "order_stock_action",
            "order_outbox"))
      orderQueries.put(
          table,
          new Query(
              "SELECT * FROM " + table + " WHERE BINARY order_no=BINARY ?", List.of(orderNo)));
    sources.put(
        "order",
        snapshotIfAvailable(bindings.get("order"), initial.get("order"), orderQueries, missing));
    Map<String, Query> stockQueries = new LinkedHashMap<>();
    for (String table : List.of("stock_reservation_request", "stock_flow"))
      stockQueries.put(
          table,
          new Query(
              "SELECT * FROM " + table + " WHERE BINARY order_no=BINARY ?", List.of(orderNo)));
    stockQueries.put(
        "stock_sku", new Query("SELECT * FROM stock_sku WHERE sku_id=?", List.of(skuId)));
    Map<String, Object> stock =
        snapshotIfAvailable(bindings.get("stock"), initial.get("stock"), stockQueries, missing);
    stock.put(
        "seckill", Map.of("status", "NOT_APPLICABLE", "reason", "NORMAL_RESERVATION_FIXTURE"));
    sources.put("stock", stock);

    // 3. TC 仅查询已核对的原 execution XID；MQ 元数据不证明逐 event 交付。
    sources.put("tc", tcSnapshot(originalXid, missing));
    sources.put("mq", mqSnapshot(batch, missing, rabbitManagementPort));
    Map<String, Object> payAfter =
        "UNAVAILABLE".equals(pay.get("status"))
            ? null
            : databaseSnapshot(bindings.get("pay"), payQueries(payNo, orderNo), missing);
    Set<String> knownXids = new LinkedHashSet<>(fenceXids);
    if (payAfter != null) collectXids(payAfter, knownXids);
    Map<String, Object> payBeforeAnchors = new LinkedHashMap<>(pay);
    payBeforeAnchors.remove("fence");
    boolean comparable =
        "OBSERVED".equals(pay.get("status"))
            && payAfter != null
            && "OBSERVED".equals(payAfter.get("status"));
    String beforeDigest = comparable ? digest(safeFacts(payBeforeAnchors, knownXids)) : null;
    String afterDigest = comparable ? digest(safeFacts(payAfter, knownXids)) : null;
    boolean payChanged = comparable && !beforeDigest.equals(afterDigest);
    pay.put("anchorBeforeDigest", beforeDigest);
    pay.put("anchorAfterDigest", afterDigest);
    pay.put("anchorAfter", payAfter);
    pay.put("sourceChanged", comparable ? payChanged : null);
    if (payChanged) missing.add("SOURCE_CHANGED:PAY");
    if (!comparable) missing.add("SOURCE_UNAVAILABLE:PAY_ANCHOR");

    // 4. 输出业务实测与保守总分类，不能以终态业务断言替代缺失的 TC/MQ 证据。
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("payNo", payNo);
    subject.put("orderNo", orderNo);
    subject.put("userId", userId);
    subject.put("skuId", skuId);
    subject.put("month", month);
    Map<String, Object> payment = single(rows(pay, "pay_record"));
    Map<String, Object> execution = single(executions);
    subject.put("amount", payment == null ? null : payment.get("amount"));
    subject.put("debitMode", payment == null ? null : payment.get("debit_mode"));
    subject.put("executionId", execution == null ? null : execution.get("id"));
    subject.put("xidSha256", originalXid == null ? null : sha256(originalXid));
    for (String key : List.of("operation_key", "ledger_key"))
      subject.put(
          key + "_sha256",
          execution == null || execution.get(key) == null
              ? null
              : sha256(execution.get(key).toString()));
    subject.put("payBranchSha256", fingerprint(single(rows(pay, "pay_tcc_receipt")), "branch_id"));
    subject.put(
        "userBranchSha256", fingerprint(single(rows(user, "user_tcc_receive")), "branch_id"));
    subject.put(
        "refundKeySha256",
        fingerprint(single(rows(pay, "pay_tcc_delivery")), "refund_operation_key"));
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("schemaVersion", "CM18_PHASE_EVIDENCE_V1");
    bundle.put("scope", "ISOLATED_TEST_PHASE_ONLY");
    bundle.put("automaticActionsAllowed", false);
    bundle.put("bundleId", UUID.randomUUID().toString());
    bundle.put("runId", batch);
    bundle.put(
        "scenarioId",
        verifiedAssertions.getOrDefault(
            "scenarioId", processes.getOrDefault("scenarioId", "M4_ISOLATED_ROLLBACK")));
    bundle.put("phase", phase);
    bundle.put("captureStartedAt", startedAt);
    bundle.put("captureEndedAt", now());
    bundle.put("captureElapsedNanos", System.nanoTime() - captureStartedNanos);
    bundle.put("snapshotConsistency", "SEQUENTIAL_SOURCE_WINDOWS_NOT_CROSS_SOURCE_ATOMIC");
    bundle.put("subject", subject);
    bundle.put("processes", processProjection(processes));
    bundle.put("sources", sources);
    bundle.put(
        "observedOutcome", verifiedAssertions.getOrDefault("observedOutcome", "NOT_EVALUATED"));
    bundle.put("verifiedAssertions", verifiedAssertions);
    String conflict =
        payChanged ? null : immutableConflict(sources, payment, payNo, orderNo, userId);
    String classification = closureClassification(conflict, execution, originalXid);
    bundle.put("closureClassification", classification);
    if (conflict != null) missing.add(conflict);
    if ("BEGIN_UNKNOWN".equals(classification)) missing.add("BEGIN_ORIGINAL_XID_UNPROVEN:PAY_TC");
    if (payment == null) missing.add("PAYMENT_UNIQUE_ROW_MISSING:PAY");
    bundle.put("missingEvidence", missing);
    bundle.put(
        "notInspected",
        List.of(
            "COMPLETE_LOCAL_ORIGINAL_KEY_BRANCH_DIRECTION_RELATIONSHIPS",
            "TC_BRANCH_DETAILS",
            "MQ_EVENT_CONSUMER_CAUSALITY"));
    bundle.put(
        "retention", Map.of("minimumDays", 30, "incompleteOrFailure", "NO_AUTOMATIC_EXPIRY"));
    bundle.put("factsDigest", digest(safeFacts(sources, knownXids)));
    for (SourceBinding binding : bindings.values()) binding.verifyUnchanged();
    Files.createDirectories(artifacts);
    bundle.put("artifactManifest", artifactManifest(artifacts));
    Path destination =
        artifacts.resolve(
            "stage-evidence-" + payNo + "-" + phase + "-" + UUID.randomUUID() + ".json");
    Files.writeString(
        destination,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sanitize(bundle, "", knownXids)),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    return destination;
  }

  /** 绑定真实使用的 DataSource 及固定配置，后续每次连接前后都重新核对，禁止运行中换源。 */
  private record SourceBinding(
      JdbcTemplate database, DriverManagerDataSource source, String url, String expected) {
    private void verifyUnchanged() {
      SourceBinding current = sourceBinding(database, expected);
      require(current.source == source && current.url.equals(url), "source configuration changed");
    }

    private String service() {
      return expected.substring(expected.lastIndexOf('_') + 1).toUpperCase(Locale.ROOT);
    }
  }

  /** 仅解析固定 MySQL 协议、回环目标、精确 catalog 与有限参数，不连接也不打印 URL。 */
  private static SourceBinding sourceBinding(JdbcTemplate database, String expected) {
    return sourceBinding(database, expected, 13307);
  }

  /** 精确绑定该轮 MySQL 端口，保留 catalog、凭据和有限连接选项的原校验。 */
  private static SourceBinding sourceBinding(
      JdbcTemplate database, String expected, int mysqlPort) {
    require(
        database != null && expected.matches("cm16c_[0-9a-f]{32}_(pay|user|order|stock)"),
        "source required");
    require(
        database.getDataSource() != null
            && database.getDataSource().getClass() == DriverManagerDataSource.class,
        "unsupported source type");
    DriverManagerDataSource source = (DriverManagerDataSource) database.getDataSource();
    require(
        "root".equals(source.getUsername()) && "root".equals(source.getPassword()),
        "unsupported source credentials");
    require(
        source.getCatalog() == null
            && source.getSchema() == null
            && (source.getConnectionProperties() == null
                || source.getConnectionProperties().isEmpty()),
        "unsupported source overrides");
    String url = source.getUrl();
    require(
        url != null && url.startsWith("jdbc:mysql://") && !url.contains("%") && !url.contains("\\"),
        "source URL invalid");
    URI parsed;
    try {
      parsed = URI.create(url.substring(5));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("source URL invalid");
    }
    require(
        "mysql".equals(parsed.getScheme())
            && ("localhost".equals(parsed.getHost()) || "127.0.0.1".equals(parsed.getHost()))
            && parsed.getPort() == mysqlPort
            && parsed.getUserInfo() == null
            && parsed.getFragment() == null
            && ("/" + expected).equals(parsed.getRawPath()),
        "source target does not belong to fixture");
    Map<String, String> parameters = new LinkedHashMap<>();
    if (parsed.getRawQuery() != null)
      for (String entry : parsed.getRawQuery().split("&", -1)) {
        String[] pair = entry.split("=", -1);
        require(
            pair.length == 2
                && !pair[1].isEmpty()
                && parameters.putIfAbsent(pair[0], pair[1]) == null,
            "source parameter invalid");
        boolean allowed =
            switch (pair[0]) {
              case "serverTimezone", "connectionTimeZone" -> "Asia/Shanghai".equals(pair[1]);
              case "characterEncoding" -> Set.of("UTF-8", "UTF8", "utf8").contains(pair[1]);
              case "connectionCollation" -> "utf8mb4_unicode_ci".equals(pair[1]);
              case "useSSL",
                      "allowPublicKeyRetrieval",
                      "requireSSL",
                      "verifyServerCertificate",
                      "useUnicode",
                      "forceConnectionTimeZoneToSession" ->
                  Set.of("true", "false").contains(pair[1]);
              case "connectTimeout" -> boundedMillis(pair[1], 3000);
              case "socketTimeout" -> boundedMillis(pair[1], 5000);
              default -> false;
            };
        require(allowed, "unsupported source parameter");
      }
    require(
        parameters.containsKey("connectTimeout") && parameters.containsKey("socketTimeout"),
        "bounded source timeouts required");
    require(
        !"true".equals(parameters.get("requireSSL")) || !"false".equals(parameters.get("useSSL")),
        "contradictory source TLS parameters");
    return new SourceBinding(database, source, url, expected);
  }

  private static boolean boundedMillis(String value, int maximum) {
    if (!value.matches("[1-9][0-9]{0,3}")) return false;
    try {
      return Integer.parseInt(value) <= maximum;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static Map<String, Object> sourceSkeleton(SourceBinding binding) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("schema", binding.expected);
    source.put("configuredSchema", binding.expected);
    source.put("configurationOwnership", "OWNED_FIXTURE_CONFIGURED");
    source.put("connectionOwnership", "UNAVAILABLE_NOT_VERIFIED");
    source.put("tables", new LinkedHashMap<>());
    return source;
  }

  private static Map<String, Object> initialOwnership(SourceBinding binding, List<String> missing) {
    binding.verifyUnchanged();
    Map<String, Object> initial = sourceSkeleton(binding);
    initial.put("captureStartedAt", now());
    try {
      binding.database.execute(
          (ConnectionCallback<Void>)
              connection -> {
                binding.verifyUnchanged();
                requireOwned(connection, binding.expected);
                binding.verifyUnchanged();
                return null;
              });
      binding.verifyUnchanged();
      initial.put("connectionOwnership", "VERIFIED");
      initial.put("status", "OBSERVED");
    } catch (org.springframework.dao.DataAccessException unavailable) {
      binding.verifyUnchanged();
      initial.put("status", "UNAVAILABLE");
      initial.put("safeError", safeError(unavailable, "INITIAL_OWNERSHIP", binding.service()));
      missing.add("SOURCE_UNAVAILABLE:" + binding.service());
    }
    initial.put("captureEndedAt", now());
    return initial;
  }

  private static Map<String, Object> snapshotIfAvailable(
      SourceBinding binding,
      Map<String, Object> initial,
      Map<String, Query> queries,
      List<String> missing) {
    return "UNAVAILABLE".equals(initial.get("status"))
        ? initial
        : databaseSnapshot(binding, queries, missing);
  }

  /** 有界 cause 链只提取固定分类及 SQLState/vendorCode，不输出异常正文、凭据或目标 URL。 */
  private static Map<String, Object> safeError(Throwable failure, String stage, String owner) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("category", "JDBC_SOURCE_UNAVAILABLE");
    safe.put("stage", stage);
    safe.put("owner", owner);
    for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause())
      if (failure instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.matches("[A-Za-z0-9]{1,5}")) safe.put("sqlState", state);
        safe.put("vendorCode", sql.getErrorCode());
        break;
      }
    return safe;
  }

  private static Map<String, Query> payQueries(String payNo, String orderNo) {
    Map<String, Query> queries = new LinkedHashMap<>();
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel")) queries.put(table, subjectQuery(table, payNo, orderNo));
    queries.put(
        "pay_callback_log",
        new Query("SELECT * FROM pay_callback_log WHERE BINARY pay_no=BINARY ?", List.of(payNo)));
    return queries;
  }

  private static Query subjectQuery(String table, String payNo, String orderNo) {
    return new Query(
        "SELECT * FROM " + table + " WHERE BINARY pay_no=BINARY ? OR BINARY order_no=BINARY ?",
        List.of(payNo, orderNo));
  }

  private static Map<String, Query> fenceQueries(Set<String> xids) {
    if (xids.isEmpty()) return Map.of();
    return Map.of(
        "tcc_fence_log",
        new Query(
            "SELECT * FROM tcc_fence_log WHERE xid IN ("
                + String.join(",", java.util.Collections.nCopies(xids.size(), "?"))
                + ")",
            new ArrayList<>(xids)));
  }

  /** 单库 RR 只读事务在回调内关闭，绝不跨远端查询持有连接或锁。 */
  private static Map<String, Object> databaseSnapshot(
      JdbcTemplate database, String expected, Map<String, Query> queries, List<String> missing) {
    return databaseSnapshot(sourceBinding(database, expected), queries, missing);
  }

  private static Map<String, Object> databaseSnapshot(
      SourceBinding binding, Map<String, Query> queries, List<String> missing) {
    binding.verifyUnchanged();
    String expected = binding.expected;
    Map<String, Object> snapshot = sourceSkeleton(binding);
    snapshot.put("captureStartedAt", now());
    Map<String, Object> tables = new LinkedHashMap<>();
    snapshot.put("tables", tables);
    try {
      binding.database.execute(
          (ConnectionCallback<Void>)
              connection -> {
                binding.verifyUnchanged();
                requireOwned(connection, expected);
                binding.verifyUnchanged();
                snapshot.put("connectionOwnership", "VERIFIED");
                boolean autoCommit = connection.getAutoCommit();
                boolean readOnly = connection.isReadOnly();
                int isolation = connection.getTransactionIsolation();
                try {
                  connection.setReadOnly(true);
                  connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                  connection.setAutoCommit(false);
                  for (var entry : queries.entrySet()) {
                    Map<String, Object> table = new LinkedHashMap<>();
                    table.put("captureStartedAt", now());
                    try {
                      List<Map<String, Object>> records =
                          query(connection, entry.getValue(), table);
                      table.put("rowCount", records.size());
                      table.put(
                          "rows",
                          records.size() > ROW_BOUND ? records.subList(0, ROW_BOUND) : records);
                      table.put(
                          "status",
                          records.size() > ROW_BOUND
                              ? "INCOMPLETE"
                              : records.isEmpty() ? "EMPTY" : "OBSERVED");
                      if (records.size() > ROW_BOUND)
                        missing.add("BOUND_EXCEEDED:" + expected + "." + entry.getKey());
                    } catch (SQLException unavailable) {
                      table.put("status", "UNAVAILABLE");
                      table.put(
                          "safeError", safeError(unavailable, "TABLE_QUERY", binding.service()));
                      missing.add("SOURCE_UNAVAILABLE:" + expected + "." + entry.getKey());
                    }
                    table.put("captureEndedAt", now());
                    tables.put(entry.getKey(), table);
                  }
                } finally {
                  connection.rollback();
                  connection.setAutoCommit(autoCommit);
                  connection.setTransactionIsolation(isolation);
                  connection.setReadOnly(readOnly);
                }
                return null;
              });
      binding.verifyUnchanged();
      snapshot.put(
          "status",
          tables.values().stream()
                  .anyMatch(
                      value ->
                          !Set.of("OBSERVED", "EMPTY").contains(((Map<?, ?>) value).get("status")))
              ? "INCOMPLETE"
              : "OBSERVED");
    } catch (org.springframework.dao.DataAccessException unavailable) {
      binding.verifyUnchanged();
      snapshot.put("status", "UNAVAILABLE");
      snapshot.put("safeError", safeError(unavailable, "SNAPSHOT", binding.service()));
      missing.add("SOURCE_UNAVAILABLE:" + binding.service());
    }
    snapshot.put("captureEndedAt", now());
    return snapshot;
  }

  private static List<Map<String, Object>> query(
      Connection connection, Query query, Map<String, Object> metadata) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(query.sql() + " LIMIT " + (ROW_BOUND + 1))) {
      statement.setQueryTimeout(5);
      for (int parameter = 0; parameter < query.parameters().size(); parameter++)
        statement.setObject(parameter + 1, query.parameters().get(parameter));
      try (ResultSet result = statement.executeQuery()) {
        Map<String, Object> types = new TreeMap<>();
        List<String> omitted = new ArrayList<>();
        for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
          String name = result.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT);
          if (SAFE_COLUMNS.contains(name))
            types.put(name, result.getMetaData().getColumnTypeName(column));
          else omitted.add(name);
        }
        metadata.put("columnTypes", types);
        metadata.put("omittedColumns", omitted);
        List<Map<String, Object>> records = new ArrayList<>();
        while (result.next()) {
          Map<String, Object> row = new TreeMap<>();
          for (String column : types.keySet()) row.put(column, result.getObject(column));
          records.add(row);
        }
        return records;
      }
    }
  }

  private static void requireOwned(Connection connection, String expected) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT DATABASE()")) {
      statement.setQueryTimeout(5);
      try (ResultSet result = statement.executeQuery()) {
        require(
            result.next() && expected.equals(result.getString(1)),
            "database does not belong to fixture");
      }
    }
  }

  private static Map<String, Object> tcSnapshot(String xid, List<String> missing) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("source", "OFFICIAL_SEATA_1_5_2_GET_STATUS");
    result.put("captureStartedAt", now());
    result.put("xidSha256", xid == null ? null : sha256(xid));
    result.put("branchDetailsObserved", false);
    result.put("directionProvenByTc", false);
    try {
      if (xid == null) {
        result.put("status", "NOT_OBSERVED");
        missing.add("ORIGINAL_EXECUTION_XID_MISSING:PAY_TC");
      } else {
        GlobalStatus status = TransactionManagerHolder.get().getStatus(xid);
        result.put("status", "OBSERVED");
        result.put("observedGlobalStatus", status == null ? null : status.name());
        result.put(
            "directionProvenByTc",
            status != null
                && Set.of(
                        GlobalStatus.Committed,
                        GlobalStatus.Rollbacked,
                        GlobalStatus.TimeoutRollbacked)
                    .contains(status));
        if (status == null || status == GlobalStatus.UnKnown) missing.add("TC_UNKNOWN:TC");
        if (status == GlobalStatus.Finished) missing.add("TC_FINISHED_DIRECTION_UNPROVEN:TC");
      }
    } catch (Exception unavailable) {
      result.put("status", "UNAVAILABLE");
      result.put("errorType", unavailable.getClass().getSimpleName());
      missing.add("SOURCE_UNAVAILABLE:TC");
    }
    result.put("observedAt", now());
    result.put("captureEndedAt", now());
    return result;
  }

  private static Map<String, Object> mqSnapshot(
      String batch, List<String> missing, int rabbitManagementPort) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("source", "RABBIT_MANAGEMENT_METADATA_GET");
    result.put("scope", "VHOST_QUEUE_AGGREGATE");
    result.put("eventCausalityProven", false);
    result.put("vhost", batch);
    result.put("captureStartedAt", now());
    try {
      Map<?, ?> vhost =
          JSON.readValue(rabbitGet("/api/vhosts/" + batch, rabbitManagementPort), Map.class);
      require(batch.equals(vhost.get("name")), "Rabbit vhost does not belong to fixture");
      Object value =
          JSON.readValue(
              rabbitGet(
                  "/api/queues/" + batch + "?page=1&page_size=100&pagination=true",
                  rabbitManagementPort),
              Object.class);
      List<?> queues;
      if (value instanceof Map<?, ?> page) {
        queues = (List<?>) page.get("items");
        Number total = (Number) page.get("total_count");
        if (total != null && total.intValue() > ROW_BOUND) missing.add("BOUND_EXCEEDED:MQ_QUEUES");
      } else queues = (List<?>) value;
      List<Map<String, Object>> projected = new ArrayList<>();
      for (Object queue : queues.subList(0, Math.min(queues.size(), ROW_BOUND))) {
        Map<?, ?> raw = (Map<?, ?>) queue;
        require(batch.equals(raw.get("vhost")), "Rabbit queue does not belong to fixture");
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key :
            List.of(
                "name",
                "vhost",
                "messages",
                "messages_ready",
                "messages_unacknowledged",
                "consumers",
                "state")) safe.put(key, raw.get(key));
        projected.add(safe);
      }
      result.put("queues", projected);
      result.put("status", "OBSERVED");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      result.put("status", "UNAVAILABLE");
      missing.add("SOURCE_UNAVAILABLE:MQ_INTERRUPTED");
    } catch (Exception unavailable) {
      result.put("status", "UNAVAILABLE");
      result.put("errorType", unavailable.getClass().getSimpleName());
      missing.add("SOURCE_UNAVAILABLE:MQ");
    }
    result.put("captureEndedAt", now());
    return result;
  }

  private static String rabbitGet(String path, int rabbitManagementPort) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + rabbitManagementPort + path))
            .timeout(Duration.ofSeconds(5))
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString("root:root".getBytes(StandardCharsets.UTF_8)))
            .GET()
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new IllegalStateException("Rabbit metadata unavailable HTTP " + response.statusCode());
    return response.body();
  }

  private static String originalXid(
      List<Map<String, Object>> records, String payNo, String orderNo, long userId) {
    Map<String, Object> execution = single(records);
    if (execution == null
        || !payNo.equals(execution.get("pay_no"))
        || !orderNo.equals(execution.get("order_no"))
        || !(execution.get("user_id") instanceof Number owner)
        || owner.longValue() != userId) return null;
    Object xid = execution.get("xid");
    return xid instanceof String text && !text.isBlank() ? text : null;
  }

  private static String immutableConflict(
      Map<String, Object> sources,
      Map<String, Object> payment,
      String payNo,
      String orderNo,
      long userId) {
    if (payment == null) return null;
    Object expectedXid = null;
    if (sources.get("pay") instanceof Map<?, ?> paySource) {
      @SuppressWarnings("unchecked")
      Map<String, Object> paySnapshot = (Map<String, Object>) paySource;
      Map<String, Object> execution = single(rows(paySnapshot, "pay_tcc_execution"));
      if (execution != null) expectedXid = execution.get("xid");
    }
    List<Map<String, Object>> records = new ArrayList<>();
    collectRows(sources, records);
    for (Map<String, Object> row : records) {
      if (row.containsKey("pay_no") && !payNo.equals(row.get("pay_no")))
        return "PROTOCOL_CONFLICT:PAY_IDENTITY";
      if (row.containsKey("order_no") && !orderNo.equals(row.get("order_no")))
        return "PROTOCOL_CONFLICT:ORDER_IDENTITY";
      if (row.get("user_id") instanceof Number owner && owner.longValue() != userId)
        return "PROTOCOL_CONFLICT:USER_IDENTITY";
      if (row.get("amount") instanceof BigDecimal amount
          && payment.get("amount") instanceof BigDecimal expected
          && amount.compareTo(expected) != 0) return "PROTOCOL_CONFLICT:AMOUNT";
      if (row.containsKey("debit_mode")
          && !Objects.equals(payment.get("debit_mode"), row.get("debit_mode")))
        return "PROTOCOL_CONFLICT:PERSISTED_MODE";
      if (row.containsKey("mode") && !Objects.equals(payment.get("debit_mode"), row.get("mode")))
        return "PROTOCOL_CONFLICT:ATTEMPT_MODE";
      if (expectedXid != null && row.get("xid") != null && !expectedXid.equals(row.get("xid")))
        return "PROTOCOL_CONFLICT:ORIGINAL_XID";
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> snapshot, String table) {
    Map<?, ?> tables = (Map<?, ?>) snapshot.get("tables");
    Map<?, ?> value = tables == null ? null : (Map<?, ?>) tables.get(table);
    return value == null || !(value.get("rows") instanceof List<?>)
        ? List.of()
        : (List<Map<String, Object>>) value.get("rows");
  }

  private static Map<String, Object> single(List<Map<String, Object>> records) {
    return records.size() == 1 ? records.get(0) : null;
  }

  @SuppressWarnings("unchecked")
  private static void collectRows(Object value, List<Map<String, Object>> records) {
    if (value instanceof Map<?, ?> map) {
      if (map.get("rows") instanceof List<?> rows)
        for (Object row : rows) records.add((Map<String, Object>) row);
      else for (Object child : map.values()) collectRows(child, records);
    }
  }

  private static void collectXids(Object value, Set<String> xids) {
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if ("xid".equals(entry.getKey())
            && entry.getValue() instanceof String text
            && !text.isBlank()) xids.add(text);
        else collectXids(entry.getValue(), xids);
      }
    } else if (value instanceof Iterable<?> entries)
      for (Object entry : entries) collectXids(entry, xids);
  }

  /** 不将身份键、诊断正文、密码、地址、连接串、命令行或 MQ body 写入阶段包。 */
  private static Object sanitize(Object value, String key, Set<String> xids) throws Exception {
    if (value == null) return null;
    String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
    if (normalized.equals("columntypes")) return value;
    if (normalized.contains("password")
        || normalized.contains("token")
        || normalized.contains("authorization")
        || normalized.contains("secret")
        || normalized.contains("address")
        || normalized.contains("command")
        || normalized.contains("environment")
        || normalized.contains("jdbc")
        || normalized.equals("body")) return "OMITTED";
    if (normalized.equals("safeerror")
        && value instanceof Map<?, ?> diagnostic
        && "JDBC_SOURCE_UNAVAILABLE".equals(diagnostic.get("category"))) {
      Map<String, Object> safe = new TreeMap<>();
      safe.put("category", "JDBC_SOURCE_UNAVAILABLE");
      if (List.of("INITIAL_OWNERSHIP", "SNAPSHOT", "TABLE_QUERY").contains(diagnostic.get("stage")))
        safe.put("stage", diagnostic.get("stage"));
      if (List.of("PAY", "USER", "ORDER", "STOCK").contains(diagnostic.get("owner")))
        safe.put("owner", diagnostic.get("owner"));
      if (diagnostic.get("sqlState") instanceof String state && state.matches("[A-Za-z0-9]{1,5}"))
        safe.put("sqlState", state);
      if (diagnostic.get("vendorCode") instanceof Integer code) safe.put("vendorCode", code);
      return safe;
    }
    if ((normalized.equals("payload") || normalized.endsWith("error"))
        && value instanceof Map<?, ?> safeDiagnostic
        && safeDiagnostic.containsKey("present")
        && safeDiagnostic.containsKey("sha256")) return value;
    if (normalized.equals("payload") || normalized.endsWith("error"))
      return Map.of("present", !value.toString().isEmpty(), "sha256", sha256(value.toString()));
    if (!normalized.endsWith("sha256")
        && (normalized.endsWith("xid")
            || normalized.endsWith("branchid")
            || normalized.endsWith("operationkey")
            || normalized.endsWith("ledgerkey")
            || normalized.endsWith("leaseowner")
            || normalized.equals("recoveryowner")
            || normalized.equals("eventid")
            || normalized.equals("idempotencykey")
            || normalized.equals("callbackid")
            || normalized.equals("appid"))) {
      if (value instanceof String fingerprint && fingerprint.matches("sha256:[0-9a-f]{64}"))
        return value;
      return "sha256:" + sha256(value.toString());
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> safe = new TreeMap<>();
      for (var entry : map.entrySet())
        safe.put(
            entry.getKey().toString(), sanitize(entry.getValue(), entry.getKey().toString(), xids));
      return safe;
    }
    if (value instanceof Iterable<?> values) {
      List<Object> safe = new ArrayList<>();
      for (Object entry : values) safe.add(sanitize(entry, key, xids));
      return safe;
    }
    if (value instanceof BigDecimal amount) return amount.toPlainString();
    if (value instanceof Timestamp timestamp)
      return timestamp
          .toLocalDateTime()
          .atOffset(ZoneOffset.ofHours(8))
          .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"));
    if (value instanceof java.time.LocalDateTime timestamp)
      return timestamp
          .atOffset(ZoneOffset.ofHours(8))
          .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"));
    if (value instanceof String text) {
      if ("items_snapshot".equals(key)) {
        try {
          return sanitize(JSON.readValue(text, List.class), "items", xids);
        } catch (Exception invalid) {
          return Map.of("present", true, "sha256", sha256(text), "parseable", false);
        }
      }
      for (String xid : xids) text = text.replace(xid, "sha256:" + sha256(xid));
      if (text.contains("jdbc:") || text.contains("root:root")) return "OMITTED";
      return text;
    }
    if (value instanceof Number || value instanceof Boolean) return value;
    return value.toString();
  }

  /** 事实摘要排除动态观察时刻、远端聚合、PID；表记录以 canonical 安全内容排序。 */
  private static Object safeFacts(Object value, Set<String> xids) throws Exception {
    return pruneFacts(sanitize(value, "", xids));
  }

  private static Object pruneFacts(Object value) throws Exception {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> facts = new TreeMap<>();
      for (var entry : map.entrySet()) {
        String key = entry.getKey().toString();
        if (List.of(
                "captureStartedAt",
                "captureEndedAt",
                "observedAt",
                "tc",
                "mq",
                "anchorAfter",
                "anchorBeforeDigest",
                "anchorAfterDigest",
                "sourceChanged")
            .contains(key)) continue;
        facts.put(key, pruneFacts(entry.getValue()));
      }
      return facts;
    }
    if (value instanceof List<?> list) {
      List<Object> facts = new ArrayList<>();
      for (Object entry : list) facts.add(pruneFacts(entry));
      facts.sort(
          java.util.Comparator.comparing(
              entry -> {
                try {
                  return JSON.writeValueAsString(entry);
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              }));
      return facts;
    }
    return value;
  }

  private static List<Map<String, Object>> artifactManifest(Path artifacts) throws Exception {
    List<Map<String, Object>> references = new ArrayList<>();
    try (var files = Files.list(artifacts)) {
      for (Path file :
          files
              .filter(Files::isRegularFile)
              .filter(
                  file -> {
                    String name = file.getFileName().toString();
                    return name.equals("fixture.json")
                        || (name.startsWith("stage-evidence-") && name.endsWith(".json"));
                  })
              .sorted()
              .toList()) {
        references.add(
            Map.of(
                "relativePath",
                artifacts.relativize(file).toString(),
                "bytes",
                Files.size(file),
                "sha256",
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
                "verification",
                "OBSERVED_FILE_BYTES"));
      }
    }
    return references;
  }

  private static String fingerprint(Map<String, Object> row, String column) {
    return row == null || row.get(column) == null ? null : sha256(row.get(column).toString());
  }

  private static String closureClassification(
      String conflict, Map<String, Object> execution, String xid) {
    if (conflict != null) return "PROTOCOL_CONFLICT";
    if (execution != null && ("BEGIN_UNKNOWN".equals(execution.get("state")) || xid == null))
      return "BEGIN_UNKNOWN";
    return "EVIDENCE_INCOMPLETE";
  }

  /** 进程只保固定观察元数据；原命令行、环境及任意 extra 字段不进入包。 */
  private static Map<String, Object> processProjection(Map<String, Object> processes) {
    Map<String, Object> safe = new LinkedHashMap<>();
    List<String> fields =
        List.of(
            "pid",
            "generation",
            "appId",
            "startedAt",
            "exitedAt",
            "observedAt",
            "modeArgument",
            "artifact",
            "jarSha256",
            "log");
    for (String generation : List.of("old", "new")) {
      if (processes.get(generation) instanceof Map<?, ?> process) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields)
          if (process.containsKey(field)) values.put(field, process.get(field));
        values.put("generation", generation);
        values.put(
            "omittedNonWhitelistFields",
            process.keySet().stream().anyMatch(key -> !fields.contains(key.toString())));
        safe.put(generation, values);
      }
    }
    for (String field :
        List.of("oldExitedAt", "newExitedAt", "dueAdjustment", "verifiedBeginCounts"))
      if (processes.containsKey(field)) safe.put(field, processes.get(field));
    return safe;
  }

  private static String digest(Object value) throws Exception {
    return sha256(JSON.writeValueAsString(value));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"));
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalArgumentException(reason);
  }

  /** 固定白名单查询与绑定参数，不接受外部 SQL 或表名。 */
  private record Query(String sql, List<?> parameters) {}
}
