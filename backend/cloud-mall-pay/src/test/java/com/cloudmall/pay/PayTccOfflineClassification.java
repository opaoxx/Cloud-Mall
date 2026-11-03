package com.cloudmall.pay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** 本地操作员固定归档索引上的离线证据关联；没有网络、SQL、业务恢复或清理能力。 */
public final class PayTccOfflineClassification {
  /** 冻结的单文件、总字节、文件、引用、集合与文本上限。 */
  private static final int FILE_LIMIT = 4 * 1024 * 1024,
      TOTAL_LIMIT = 64 * 1024 * 1024,
      FILE_COUNT = 512,
      EDGE_LIMIT = 4096,
      ROW_LIMIT = 100,
      STRING_LIMIT = 256 * 1024;

  /** 在构树前启用重复字段拒绝；所有读取还必须经过独立 token/UTF-8 边界门。 */
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  /** 原阶段摘要排除的非业务事实字段，与已冻结 phase profile 保持一致。 */
  private static final Set<String> PHASE_OMIT =
      Set.of(
          "captureStartedAt",
          "captureEndedAt",
          "observedAt",
          "tc",
          "mq",
          "anchorAfter",
          "anchorBeforeDigest",
          "anchorAfterDigest",
          "sourceChanged");

  /** TC 原 factsDigest 精确字段；数组事件顺序不作改写。 */
  private static final List<String> TC_FIELDS =
      List.of(
          "xidSha256",
          "globalOutcome",
          "directionProvenByTc",
          "branchDetailsObserved",
          "pay",
          "user",
          "events",
          "status",
          "missingEvidence");

  /** 固定资源和消息路由，不能由输入说明或 expectedClassification 覆盖。 */
  private static final String PAY_RESOURCE = "cloudmallPayDebitReceipt",
      USER_RESOURCE = "cloudmallUserBalanceDebit",
      QUEUE = "cloudmall.order.timeout.dlx",
      EXCHANGE = "cloudmall.order.timeout.dlx.exchange";

  private PayTccOfflineClassification() {}

  /**
   * 生成新报告；可预期输入失败写安全拒绝包，输出写失败抛固定错误。
   *
   * @return CREATE_NEW 写入的报告路径
   * @throws Exception 无法安全写报告时抛出，不吞掉写失败
   */
  public static Path generate(Path inputManifest, Path outputDirectory) throws Exception {
    // 1. 固定输入字节并建立只从归档根读取的索引，原路径只作逻辑标识。
    ObjectNode report =
        object(
            "schemaVersion",
            "CM18_OFFLINE_CLASSIFICATION_REPORT_V1",
            "scope",
            "ISOLATED_ARCHIVED_EVIDENCE_ONLY",
            "trustModel",
            "LOCAL_OPERATOR_PINNED_INDEX",
            "sourceMode",
            "ARCHIVED_HISTORICAL");
    report.put("reportId", UUID.randomUUID().toString());
    report.put("generatedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
    report
        .put("automaticActionsAllowed", false)
        .put("cleanupAuthorized", false)
        .put("m1Complete", false)
        .put("wholeCm18Complete", false)
        .put("liveSourceReadPerformed", false)
        .put("archivedFileReadPerformed", true)
        .put("networkCalls", 0)
        .put("sqlStatements", 0);
    report.set(
        "retention",
        object("successMinimumDays", "30", "failureIncompleteOrUnresolved", "NO_AUTOMATIC_EXPIRY"));
    ArrayNode samples = report.putArray("samples");
    byte[] inputBytes = null;
    JsonNode input = NullNode.instance;
    Archive archive = null;
    try {
      inputBytes = readBytes(inputManifest, 1024 * 1024);
      report.put("inputManifestSha256", hash(inputBytes));
      input = parse(inputBytes);
      demand(
          "CM18_OFFLINE_CLASSIFICATION_INPUT_V1".equals(text(input.path("schemaVersion"))),
          "UNSUPPORTED_PROFILE");
      JsonNode entries = input.path("samples");
      demand(entries.isArray() && entries.size() <= ROW_LIMIT, "BOUND_EXCEEDED");
      Path indexPath = Path.of(requiredText(input.at("/archiveManifest/path")));
      if (!indexPath.isAbsolute())
        indexPath = inputManifest.toAbsolutePath().getParent().resolve(indexPath);
      archive = new Archive(indexPath, requiredHash(input.at("/archiveManifest/sha256")));
      report.put("archiveManifestSha256", archive.indexHash);
      // 2. 每个样本独立拒绝/计算；不由 PASS、说明或其他阶段的可变状态推导资金。
      Set<String> ids = new HashSet<>();
      for (JsonNode sample : entries) {
        String id = text(sample.path("id"));
        try {
          demand(
              id != null && id.matches("[A-Za-z0-9_-]{1,80}") && ids.add(id),
              "INPUT_SAMPLE_ID_INVALID");
          String kind = requiredText(sample.path("evidenceKind"));
          demand(
              Set.of("REAL_ARCHIVED_EVIDENCE", "SYNTHETIC_RULE_ONLY").contains(kind),
              "INPUT_EVIDENCE_KIND_INVALID");
          Context context = new Context(sample, archive);
          samples.add(context.evaluate());
        } catch (InputFailure rejected) {
          samples.add(rejection(id, rejected.code));
        } catch (Exception unavailable) {
          samples.add(rejection(id, "INPUT_UNAVAILABLE"));
        }
      }
      demand(hash(readBytes(inputManifest, 1024 * 1024)).equals(hash(inputBytes)), "INPUT_CHANGED");
      archive.verifyStable();
      report.put("reportGenerationComplete", true);
    } catch (InputFailure rejected) {
      report.put("reportGenerationComplete", false).put("inputRejection", rejected.code);
      rejectBatch(samples, input, rejected.code);
      if (samples.isEmpty()) report.put("inputAcceptance", "REJECTED");
    } catch (Exception unavailable) {
      report
          .put("reportGenerationComplete", false)
          .put("inputAcceptance", "REJECTED")
          .put("inputRejection", "INPUT_UNAVAILABLE");
      rejectBatch(samples, input, "INPUT_UNAVAILABLE");
    }
    // 3. 只序列化安全报告投影；语义摘要排除本次 UUID/时刻，重跑保持一致。
    ObjectNode counts = report.putObject("coverage");
    Set<String> payments = new HashSet<>();
    Set<String> realClasses = new HashSet<>();
    int real = 0, controlled = 0, rejected = 0;
    for (JsonNode sample : samples) {
      if ("REJECTED".equals(text(sample.path("inputAcceptance")))) rejected++;
      else {
        if ("REAL_ARCHIVED_EVIDENCE".equals(text(sample.path("evidenceKind")))) {
          real++;
          realClasses.add(sample.path("classification").asText());
        } else controlled++;
        payments.add(
            sample.path("runId").asText() + ":" + sample.at("/subject/payNoSha256").asText());
      }
    }
    counts
        .put("phaseSamples", samples.size())
        .put("differentPayments", payments.size())
        .put("realArchivedPhaseSamples", real)
        .put("syntheticRuleSamples", controlled)
        .put("rejectedSamples", rejected);
    ArrayNode uncovered = counts.putArray("liveNotCovered");
    for (String classification :
        List.of(
            "LEGACY_CONFIRMED",
            "TCC_TRYING",
            "TCC_COMMITTED",
            "TCC_CANCELLED",
            "PROTOCOL_CONFLICT",
            "BEGIN_UNKNOWN",
            "EVIDENCE_INCOMPLETE"))
      if (!realClasses.contains(classification)) uncovered.add(classification);
    counts.put("completeRealSourceFailureMatrixProven", false);
    if (archive != null)
      report.set(
          "readCounts",
          objectNumbers(
              "uniqueFiles",
              archive.cache.size(),
              "uniqueArtifactBytes",
              archive.totalBytes,
              "referenceEdges",
              archive.edges));
    report.put(
        "semanticDigestScope",
        "INPUT_HASH_ARCHIVE_HASH_SAMPLES_COVERAGE_EXCLUDING_REPORT_UUID_AND_GENERATION_TIME");
    ObjectNode semantic = JSON.createObjectNode();
    for (String field :
        List.of(
            "inputManifestSha256",
            "archiveManifestSha256",
            "samples",
            "coverage",
            "inputRejection")) if (report.has(field)) semantic.set(field, report.get(field));
    report.put("semanticDigest", digest(semantic, false, false));
    try (InputStream own =
        PayTccOfflineClassification.class.getResourceAsStream(
            "PayTccOfflineClassification.class")) {
      report.put("toolClassSha256", own == null ? "UNAVAILABLE" : hash(own.readAllBytes()));
    }
    try {
      localPath(outputDirectory);
      Files.createDirectories(outputDirectory);
      Path output =
          outputDirectory.resolve("cm18-offline-classification-" + UUID.randomUUID() + ".json");
      Files.write(
          output,
          JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
          StandardOpenOption.CREATE_NEW);
      return output;
    } catch (Exception writeFailure) {
      throw new IllegalStateException("REPORT_WRITE_UNAVAILABLE");
    }
  }

  /** 标准 Java test classpath 入口；0=合法计算，2=输入拒绝，3=无法落报告。 */
  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      System.err.println("INPUT_ARGUMENTS_INVALID");
      System.exit(2);
      return;
    }
    try {
      Path output = generate(Path.of(arguments[0]), Path.of(arguments[1]));
      JsonNode report = parse(readBytes(output, TOTAL_LIMIT));
      System.out.println(output);
      if (report.has("inputRejection") || report.at("/coverage/rejectedSamples").intValue() > 0)
        System.exit(2);
    } catch (Exception failure) {
      System.err.println("REPORT_WRITE_UNAVAILABLE");
      System.exit(3);
    }
  }

  /** 原索引/归档完整性异常仅携带固定原因，不把底层路径或异常正文写报告。 */
  private static final class InputFailure extends Exception {
    /** 固定安全原因码。 */
    private final String code;

    private InputFailure(String code) {
      super(code);
      this.code = code;
    }
  }

  /** 归档中的逻辑身份与真正可读相对路径；original 永远不是文件访问参数。 */
  private record Entry(String original, String relative, long bytes, String sha) {}

  /** 单次运行的有界归档闭包与按内容去重缓存。 */
  private static final class Archive {
    /** 固定索引真实路径、其真实父目录及最初摘要。 */
    private final Path index, root;

    /** 操作者固定的归档索引 SHA，完成前再次核验。 */
    private final String indexHash;

    /** original 逻辑索引与 SHA 内容入口。 */
    private final Map<String, Entry> originals = new HashMap<>(), hashes = new HashMap<>();

    /** 只缓存通过边界/文件/语义核验的 JSON。 */
    private final Map<String, JsonNode> cache = new HashMap<>();

    /** 闭包按逻辑身份访问一次，所有样本共享总预算。 */
    private final Set<String> closed = new HashSet<>();

    /** 正在展开的逻辑引用；循环不能作为已验证闭包跳过。 */
    private final Set<String> visiting = new HashSet<>();

    /** 本批已读取且按内容去重的归档字节数。 */
    private long totalBytes;

    /** 本批闭包已经遍历的引用边数。 */
    private int edges;

    private Archive(Path path, String expectedHash) throws Exception {
      localPath(path);
      index = path.toRealPath();
      root = index.getParent();
      byte[] bytes = readBytes(index, 2 * 1024 * 1024);
      indexHash = hash(bytes);
      demand(indexHash.equals(expectedHash), "INTEGRITY_MISMATCH");
      JsonNode manifest = parse(bytes);
      demand(manifest.isArray() && manifest.size() <= FILE_COUNT, "BOUND_EXCEEDED");
      for (JsonNode item : manifest) {
        String original = logical(requiredText(item.path("original")));
        String relative = relative(requiredText(item.path("archivedRelativePath")));
        long size = integer(item.path("bytes"));
        demand(size >= 0 && size <= FILE_LIMIT, "BOUND_EXCEEDED");
        Entry entry = new Entry(original, relative, size, requiredHash(item.path("sha256")));
        Entry old = originals.putIfAbsent(original, entry);
        demand(
            old == null || old.sha.equals(entry.sha) && old.bytes == size, "AMBIGUOUS_REFERENCE");
        hashes.putIfAbsent(entry.sha, entry);
      }
    }

    private JsonNode selected(String sha) throws Exception {
      Entry entry = hashes.get(sha);
      demand(entry != null, "INPUT_MISSING");
      return load(entry);
    }

    private JsonNode load(Entry entry) throws Exception {
      JsonNode value = cache.get(entry.sha);
      if (value == null) {
        demand(cache.size() < FILE_COUNT, "BOUND_EXCEEDED");
        Path file = root.resolve(entry.relative).normalize();
        demand(file.startsWith(root), "PATH_OUTSIDE_ARCHIVE");
        Path actual;
        try {
          actual = file.toRealPath();
        } catch (java.io.IOException missing) {
          throw new InputFailure("INPUT_MISSING");
        }
        demand(actual.startsWith(root) && Files.isRegularFile(actual), "PATH_OUTSIDE_ARCHIVE");
        byte[] bytes = readBytes(actual, FILE_LIMIT);
        demand(bytes.length == entry.bytes && hash(bytes).equals(entry.sha), "INTEGRITY_MISMATCH");
        totalBytes += bytes.length;
        demand(totalBytes <= TOTAL_LIMIT, "BOUND_EXCEEDED");
        value = parse(bytes);
        profile(value);
        cache.put(entry.sha, value);
      }
      if (!closed.contains(entry.original)) {
        demand(visiting.add(entry.original), "INPUT_REFERENCE_CYCLE");
        try {
          for (String field : List.of("artifactManifest", "attemptArtifacts")) {
            JsonNode references = value.get(field);
            if (references != null) {
              demand(references.isArray(), "INPUT_REFERENCE_INVALID");
              for (JsonNode reference : references) reference(entry, reference);
            }
          }
          if (value.has("anchorArtifactManifest"))
            reference(entry, value.get("anchorArtifactManifest"));
          closed.add(entry.original);
        } finally {
          visiting.remove(entry.original);
        }
      }
      return value;
    }

    private JsonNode reference(Entry parent, JsonNode reference) throws Exception {
      demand(++edges <= EDGE_LIMIT, "BOUND_EXCEEDED");
      String name = relative(requiredText(reference.path("relativePath")));
      String prefix = parent.original.substring(0, parent.original.lastIndexOf('/') + 1);
      Entry target = originals.get(prefix + name);
      demand(target != null, "INPUT_MISSING");
      demand(
          target.sha.equals(requiredHash(reference.path("sha256")))
              && target.bytes == integer(reference.path("bytes")),
          "INTEGRITY_MISMATCH");
      return load(target);
    }

    private void verifyStable() throws Exception {
      demand(indexHash.equals(hash(readBytes(index, 2 * 1024 * 1024))), "INPUT_CHANGED");
    }
  }

  /** 在构建树之前检查 UTF-8、深度、字符串、重复字段和多根/尾随内容。 */
  private static JsonNode parse(byte[] bytes) throws InputFailure {
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      try (JsonParser parser = JSON.getFactory().createParser(decoded)) {
        int depth = 0, roots = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (depth == 0 && token != JsonToken.END_ARRAY && token != JsonToken.END_OBJECT)
            demand(++roots == 1, "JSON_INVALID");
          if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY)
            demand(++depth <= 64, "BOUND_EXCEEDED");
          else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
          if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME)
            demand(
                parser.getTextLength() <= STRING_LIMIT
                    && parser.getText().getBytes(StandardCharsets.UTF_8).length <= STRING_LIMIT,
                "BOUND_EXCEEDED");
        }
        demand(roots == 1 && depth == 0, "JSON_INVALID");
      }
      return JSON.readTree(decoded);
    } catch (InputFailure rejected) {
      throw rejected;
    } catch (Exception invalid) {
      throw new InputFailure("JSON_INVALID");
    }
  }

  private static byte[] readBytes(Path file, int bound) throws InputFailure {
    try {
      localPath(file);
      demand(Files.isRegularFile(file), "INPUT_MISSING");
      demand(Files.size(file) <= bound, "BOUND_EXCEEDED");
      try (InputStream stream = Files.newInputStream(file)) {
        byte[] bytes = stream.readNBytes(bound + 1);
        demand(bytes.length <= bound, "BOUND_EXCEEDED");
        return bytes;
      }
    } catch (InputFailure rejected) {
      throw rejected;
    } catch (Exception unavailable) {
      throw new InputFailure("INPUT_MISSING");
    }
  }

  private static void profile(JsonNode value) throws Exception {
    String schema = text(value.path("schemaVersion"));
    if (schema == null) {
      demand(
          value.has("group") && value.has("databases")
              || value.has("sourceTime")
                  && value.has("daemonIdentitySha256")
                  && value.has("tcIdentitySha256"),
          "UNSUPPORTED_PROFILE");
      return;
    }
    demand(
        Set.of(
                "CM18_PHASE_EVIDENCE_V1",
                "CM18_TC_BRANCH_EVIDENCE_V1",
                "CM18_MQ_EVENT_EVIDENCE_V1",
                "CM18_ORDER_TIMEOUT_PROCESS_TRACE_V1")
            .contains(schema),
        "UNSUPPORTED_PROFILE");
    safeProjection(value, "");
    if (schema.equals("CM18_PHASE_EVIDENCE_V1")) {
      demand(
          "ISOLATED_TEST_PHASE_ONLY".equals(text(value.path("scope")))
              && value.path("automaticActionsAllowed").isBoolean()
              && !value.path("automaticActionsAllowed").booleanValue(),
          "UNSUPPORTED_PROFILE");
      demand(
          digest(value.path("sources"), true, true).equals(requiredHash(value.path("factsDigest"))),
          "SEMANTIC_DIGEST_MISMATCH");
    } else if (schema.equals("CM18_TC_BRANCH_EVIDENCE_V1")) {
      demand(
          "ISOLATED_TEST_TC_SERVER_OBSERVATION".equals(text(value.path("scope")))
              && "SEATA_1_5_2_SERVER_NATIVE_DOCKER_LOG".equals(text(value.path("source"))),
          "UNSUPPORTED_PROFILE");
      demand(
          value.path("factsDigestFields").equals(JSON.valueToTree(TC_FIELDS)),
          "SEMANTIC_DIGEST_MISMATCH");
      ObjectNode facts = JSON.createObjectNode();
      for (String field : TC_FIELDS) facts.set(field, value.path(field));
      demand(
          digest(facts, false, false).equals(requiredHash(value.path("factsDigest"))),
          "SEMANTIC_DIGEST_MISMATCH");
    } else if (schema.equals("CM18_MQ_EVENT_EVIDENCE_V1"))
      demand(
          "ISOLATED_TEST_EVENT_CAUSALITY".equals(text(value.path("scope"))), "UNSUPPORTED_PROFILE");
  }

  private static void safeProjection(JsonNode value, String key) throws InputFailure {
    if (key.equals("columnTypes")) return;
    String normalized = key.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    demand(
        !normalized.contains("password")
            && !normalized.equals("token")
            && !normalized.equals("authorization")
            && !normalized.equals("addresssnapshot")
            && !normalized.equals("body"),
        "UNSAFE_PROFILE");
    if (!value.isNull()
        && (normalized.equals("xid")
            || normalized.endsWith("branchid")
            || normalized.endsWith("operationkey")
            || normalized.endsWith("ledgerkey")
            || normalized.equals("idempotencykey")))
      demand(fingerprint(value) != null, "UNSAFE_PROFILE");
    if (value.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        safeProjection(field.getValue(), field.getKey());
      }
    } else if (value.isArray()) for (JsonNode item : value) safeProjection(item, key);
    else if (value.isTextual())
      demand(
          !value.textValue().contains("jdbc:") && !value.textValue().contains("root:root"),
          "UNSAFE_PROFILE");
  }

  private static JsonNode canonical(JsonNode value, boolean phase, boolean sortArrays)
      throws Exception {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      TreeMap<String, JsonNode> fields = new TreeMap<>();
      value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
      for (var field : fields.entrySet())
        if (!phase || !PHASE_OMIT.contains(field.getKey()))
          result.set(field.getKey(), canonical(field.getValue(), phase, sortArrays));
      return result;
    }
    if (value.isArray()) {
      List<JsonNode> items = new ArrayList<>();
      for (JsonNode item : value) items.add(canonical(item, phase, sortArrays));
      if (sortArrays) items.sort(Comparator.comparing(JsonNode::toString));
      ArrayNode result = JSON.createArrayNode();
      items.forEach(result::add);
      return result;
    }
    return value;
  }

  private static String digest(JsonNode value, boolean phase, boolean sortArrays) throws Exception {
    return hash(JSON.writeValueAsBytes(canonical(value, phase, sortArrays)));
  }

  private static String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String hash(String value) throws Exception {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(JsonNode value) {
    return value.isTextual() ? value.textValue() : null;
  }

  private static String requiredText(JsonNode value) throws InputFailure {
    String result = text(value);
    demand(result != null && !result.isBlank(), "INPUT_FIELD_MISSING");
    return result;
  }

  private static String requiredHash(JsonNode value) throws InputFailure {
    String result = requiredText(value);
    demand(result.matches("[0-9a-f]{64}"), "INPUT_HASH_INVALID");
    return result;
  }

  private static long integer(JsonNode value) throws InputFailure {
    demand(value.isIntegralNumber() && value.canConvertToLong(), "INPUT_INTEGER_INVALID");
    return value.longValue();
  }

  private static String fingerprint(JsonNode value) {
    String result = text(value);
    if (result == null) return null;
    return result.matches("sha256:[0-9a-f]{64}")
        ? result.substring(7)
        : result.matches("[0-9a-f]{64}") ? result : null;
  }

  private static String logical(String value) {
    return value.replace('\\', '/');
  }

  private static String relative(String value) throws InputFailure {
    String result = logical(value);
    demand(
        result.equals(result.trim())
            && !result.startsWith("/")
            && !result.contains(":")
            && !result.contains("\u0000"),
        "PATH_OUTSIDE_ARCHIVE");
    for (String part : result.split("/"))
      demand(!part.equals("..") && !part.isBlank(), "PATH_OUTSIDE_ARCHIVE");
    return result;
  }

  private static void demand(boolean condition, String code) throws InputFailure {
    if (!condition) throw new InputFailure(code);
  }

  private static ObjectNode object(String... fields) {
    ObjectNode result = JSON.createObjectNode();
    for (int field = 0; field < fields.length; field += 2)
      result.put(fields[field], fields[field + 1]);
    return result;
  }

  private static ObjectNode objectNumbers(
      String first,
      long firstValue,
      String second,
      long secondValue,
      String third,
      long thirdValue) {
    ObjectNode result = JSON.createObjectNode();
    return result.put(first, firstValue).put(second, secondValue).put(third, thirdValue);
  }

  private static ObjectNode rejection(String id, String code) {
    ObjectNode result =
        object(
            "id",
            id != null && id.matches("[A-Za-z0-9_-]{1,80}") ? id : "REJECTED_SAMPLE",
            "inputAcceptance",
            "REJECTED",
            "classification",
            "EVIDENCE_INCOMPLETE",
            "moneyOutcome",
            "UNKNOWN",
            "deliveryOutcome",
            "UNKNOWN");
    result.put("settlementComplete", false);
    result.set("retention", retention(false));
    result
        .putArray("blockingReasons")
        .add(
            object(
                "code",
                code,
                "owner",
                "LOCAL_OPERATOR/QA",
                "nextEvidenceSource",
                "ORIGINAL_PINNED_ARCHIVE",
                "retention",
                "NO_AUTOMATIC_EXPIRY"));
    return result;
  }

  /** 成功证据至少保留三十天；失败、缺证或未决证据不得自动到期。 */
  private static ObjectNode retention(boolean successful) {
    ObjectNode result =
        object("policy", successful ? "SUCCESS_MINIMUM_30_DAYS" : "NO_AUTOMATIC_EXPIRY");
    result.put("automaticDeletionAllowed", false);
    if (successful) result.put("minimumDays", 30);
    return result;
  }

  private static void rejectBatch(ArrayNode samples, JsonNode input, String code) {
    samples.removeAll();
    if (input.path("samples").isArray() && input.path("samples").size() <= ROW_LIMIT)
      for (JsonNode sample : input.path("samples"))
        samples.add(rejection(text(sample.path("id")), code));
  }

  private static void localPath(Path path) throws InputFailure {
    String value = path.toString();
    demand(
        !value.startsWith("\\\\")
            && !value.startsWith("//")
            && (!value.contains(":") || value.matches("[A-Za-z]:[\\\\/].*")),
        "PATH_OUTSIDE_ARCHIVE");
  }

  /** 一个 phase 的关系计算上下文，禁止把其他阶段的可变事实合并进来。 */
  private static final class Context {
    /** 通过文件/引用/摘要门的原样 JSON；不直接写入输出报告。 */
    private final JsonNode input, phase, tc, mq, base;

    /** 已完成闭包校验的原索引，用于核 anchor/attempt 的内容关系。 */
    private final Archive archive;

    /** 逐规则安全证明、来源质量、固定责任与阻塞码。 */
    private final ArrayNode rules = JSON.createArrayNode();

    private final ObjectNode quality = JSON.createObjectNode();
    private final Map<String, String> gaps = new LinkedHashMap<>(),
        conflicts = new LinkedHashMap<>();

    /** 固定的原身份/原资金，值只在内存用于精确关系比较。 */
    private final String payNo, orderNo, run, month, mode;

    private final long userId;
    private final BigDecimal amount;

    /** 当前 phase 的本地主行及逐笔资金事实。 */
    private JsonNode payment, execution, debit, refund, account, delivery, order, reservation;

    /** 原分支和 XID 指纹，以及已证明的独立结果。 */
    private String xid, payBranch, userBranch, money = "UNKNOWN", outcome = "UNKNOWN";

    private boolean beginUnknown, complete;

    private Context(JsonNode sample, Archive archive) throws Exception {
      this.archive = archive;
      input = sample;
      phase = archive.selected(requiredHash(sample.path("phaseSha256")));
      demand(
          "CM18_PHASE_EVIDENCE_V1".equals(text(phase.path("schemaVersion"))),
          "UNSUPPORTED_PROFILE");
      tc =
          sample.hasNonNull("tcSha256")
              ? archive.selected(requiredHash(sample.path("tcSha256")))
              : NullNode.instance;
      mq =
          sample.hasNonNull("mqSha256")
              ? archive.selected(requiredHash(sample.path("mqSha256")))
              : NullNode.instance;
      base =
          sample.hasNonNull("basePhaseSha256")
              ? archive.selected(requiredHash(sample.path("basePhaseSha256")))
              : NullNode.instance;
      demand(
          tc.isNull() || "CM18_TC_BRANCH_EVIDENCE_V1".equals(text(tc.path("schemaVersion"))),
          "UNSUPPORTED_PROFILE");
      demand(
          mq.isNull() || "CM18_MQ_EVENT_EVIDENCE_V1".equals(text(mq.path("schemaVersion"))),
          "UNSUPPORTED_PROFILE");
      demand(
          base.isNull() || "CM18_PHASE_EVIDENCE_V1".equals(text(base.path("schemaVersion"))),
          "UNSUPPORTED_PROFILE");
      JsonNode subject = phase.path("subject");
      payNo = requiredText(subject.path("payNo"));
      orderNo = requiredText(subject.path("orderNo"));
      demand(
          payNo.matches("[A-Za-z0-9_-]{1,64}") && orderNo.matches("[A-Za-z0-9_-]{1,64}"),
          "INPUT_IDENTITY_INVALID");
      userId = integer(subject.path("userId"));
      demand(userId > 0, "INPUT_IDENTITY_INVALID");
      run = requiredText(phase.path("runId"));
      month = requiredText(subject.path("month"));
      demand(
          run.matches("cm16c_[0-9a-f]{32}")
              && month.matches("[0-9]{6}")
              && orderNo.startsWith(month),
          "INPUT_IDENTITY_INVALID");
      mode = requiredText(subject.path("debitMode"));
      amount = decimal(subject.path("amount"));
      demand(amount != null && amount.signum() > 0, "INPUT_AMOUNT_INVALID");
    }

    private ObjectNode evaluate() throws Exception {
      // 1. 验证四来源的真实归属、窗口、集合完整性与当前支付身份。
      rule(
          "SOURCE_WINDOWS_AND_BOUNDS",
          "LOCAL_OPERATOR/QA",
          "/sources",
          () -> {
            for (String service : List.of("pay", "user", "order", "stock")) {
              JsonNode source = phase.at("/sources/" + service);
              String status = text(source.path("status"));
              quality.set(
                  service,
                  object(
                      "status",
                      status == null ? "UNAVAILABLE" : status,
                      "role",
                      service.toUpperCase(java.util.Locale.ROOT)));
              if (!"OBSERVED".equals(status))
                missing(
                    "SOURCE_UNAVAILABLE_" + service.toUpperCase(java.util.Locale.ROOT),
                    service.toUpperCase(java.util.Locale.ROOT));
              same(
                  source.path("schema"),
                  run + "_" + service,
                  "SOURCE_SCHEMA",
                  service.toUpperCase(java.util.Locale.ROOT));
              window(source, service.toUpperCase(java.util.Locale.ROOT));
              if (source.path("tables").isObject()) {
                Iterator<String> names = source.path("tables").fieldNames();
                while (names.hasNext()) records(service, names.next(), false);
              } else missing("SOURCE_TABLES_MISSING", service.toUpperCase(java.util.Locale.ROOT));
            }
            JsonNode pay = phase.at("/sources/pay");
            if (!pay.path("sourceChanged").isBoolean()
                || pay.path("sourceChanged").booleanValue()
                || fingerprint(pay.path("anchorBeforeDigest")) == null
                || fingerprint(pay.path("anchorAfterDigest")) == null
                || !pay.path("anchorBeforeDigest").equals(pay.path("anchorAfterDigest"))) {
              missing("SOURCE_CHANGED", "PAY");
              quality.set("pay", object("status", "SOURCE_CHANGED"));
            }
          });
      rule(
          "ORIGINAL_SUBJECT_AND_MODE",
          "PAY",
          "/sources/pay/tables",
          () -> {
            payment = one("pay", "pay_record", true);
            execution = one("pay", "pay_tcc_execution", false);
            tuple(payment, "PAY", true);
            same(payment.path("debit_mode"), mode, "PERSISTED_MODE", "PAY");
            if (!Set.of("LEGACY", "TCC").contains(mode)) fail("PERSISTED_MODE_INVALID", "PAY");
            for (String table :
                List.of(
                    "pay_debit_intent",
                    "pay_tcc_execution",
                    "pay_tcc_receipt",
                    "pay_tcc_dispatch",
                    "pay_tcc_delivery",
                    "pay_tcc_cancel"))
              for (JsonNode row : records("pay", table, false)) {
                tuple(row, "PAY", true);
                if (row.has("debit_mode"))
                  same(row.path("debit_mode"), mode, "PERSISTED_MODE", "PAY");
              }
            for (String table :
                List.of(
                    "user_tcc_receive",
                    "balance_debit_attempt",
                    "balance_tcc_debit",
                    "balance_ledger"))
              for (JsonNode row : records("user", table, false)) tuple(row, "USER", true);
            if (!base.isNull()) baseIdentity();
          });
      // 2. 原账本守恒与账户投影分开，当前总余额不能反推本笔款项。
      rule("ORIGINAL_LEDGER_AND_ACCOUNT", "USER", "/sources/user/tables", this::ledger);
      if (mode.equals("LEGACY")) rule("LEGACY_ORIGINAL_PROTOCOL", "PAY", "/sources", this::legacy);
      else if (mode.equals("TCC"))
        rule("TCC_ORIGINAL_KEYS_BRANCHES_AND_FUNDS", "PAY/USER", "/sources", this::tccLocal);
      rule("ORDER_STOCK_ORIGINAL_VECTOR", "ORDER/STOCK", "/sources", this::orderStock);
      // 3. TC 与 MQ 仅关联归档中的直接事实，不驱动任何远端读取或资金动作。
      if (mode.equals("LEGACY")) {
        quality.set(
            "tc", object("status", "NOT_APPLICABLE", "reason", "LEGACY_HAS_NO_TC_RESOURCE"));
        observation("TC_NOT_APPLICABLE", "NOT_APPLICABLE", "TC_EVIDENCE", "/subject");
      } else if (!beginUnknown)
        rule("TC_ORIGINAL_REGISTRATION_AND_DIRECTION", "TC_EVIDENCE", "/", this::tcRelations);
      else quality.set("tc", object("status", "INCOMPLETE", "reason", "ORIGINAL_XID_UNPROVEN"));
      String mqProofPointer =
          !phase.at("/sources/order/tables/order_outbox").isMissingNode()
              ? "/sources/order/tables/order_outbox"
              : !phase.at("/sources/order").isMissingNode()
                  ? "/sources/order"
                  : !phase.path("sources").isMissingNode() ? "/sources" : "/";
      rule("MQ_TARGET_EVENT_CAUSALITY", "MQ_EVIDENCE", mqProofPointer, this::mqRelations);
      // 4. 先可靠冲突，再 BEGIN_UNKNOWN，再必要缺证/结算等待；受控样本不提升真实覆盖。
      if (mode.equals("TCC")
          && !beginUnknown
          && conflicts.isEmpty()
          && gaps.values().stream()
              .noneMatch(
                  owner ->
                      owner.contains("PAY")
                          || owner.contains("USER")
                          || owner.contains("TC_EVIDENCE")
                          || owner.contains("LOCAL_OPERATOR"))) {
        String state = text(execution.path("state"));
        if ("COMMITTED".equals(state)) money = "COMMITTED";
        else if ("ROLLEDBACK".equals(state)) money = "CANCELLED";
        else if ("RUNNING".equals(state)) money = "TRYING";
      }
      if (!"UNKNOWN".equals(money)) settlement();
      String classification =
          !conflicts.isEmpty()
              ? "PROTOCOL_CONFLICT"
              : beginUnknown
                  ? "BEGIN_UNKNOWN"
                  : !gaps.isEmpty()
                      ? "EVIDENCE_INCOMPLETE"
                      : mode.equals("LEGACY") && complete
                          ? "LEGACY_CONFIRMED"
                          : money.equals("TRYING")
                              ? "TCC_TRYING"
                              : complete && money.equals("COMMITTED")
                                  ? "TCC_COMMITTED"
                                  : complete && money.equals("CANCELLED")
                                      ? "TCC_CANCELLED"
                                      : "EVIDENCE_INCOMPLETE";
      if (!conflicts.isEmpty()) {
        money = "CONFLICT";
        complete = false;
      }
      ObjectNode result =
          object(
              "id",
              input.path("id").textValue(),
              "inputAcceptance",
              "ACCEPTED",
              "evidenceKind",
              input.path("evidenceKind").textValue(),
              "runId",
              run,
              "scenarioId",
              phase.path("scenarioId").asText(),
              "phase",
              phase.path("phase").asText(),
              "classification",
              classification,
              "moneyOutcome",
              money,
              "deliveryOutcome",
              outcome);
      result
          .put("settlementComplete", complete)
          .put("accountTotalCausalityProven", false)
          .put("automaticActionsAllowed", false)
          .put("cleanupAuthorized", false);
      result.set(
          "retention",
          retention(complete && conflicts.isEmpty() && gaps.isEmpty() && !beginUnknown));
      ObjectNode subject =
          object(
              "payNoSha256",
              hash(payNo),
              "orderNoSha256",
              hash(orderNo),
              "userIdSha256",
              hash(Long.toString(userId)),
              "amount",
              amount.toPlainString(),
              "mode",
              mode,
              "month",
              month);
      subject.put("skuIdSha256", hash(phase.at("/subject/skuId").asText()));
      result.set("subject", subject);
      // 只透传 selected phase 的精确作者声明；它不参与任何资金规则、真实性或覆盖计数。
      if ("COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION"
          .equals(text(phase.at("/verifiedAssertions/fixtureRole")))) {
        result.put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
        result.put("fixtureRoleDeclaration", "FIXTURE_AUTHOR_DECLARATION_NOT_FUNDS_FACT");
        result
            .putArray("fixtureRoleProofRefs")
            .add(
                object(
                    "sha256",
                    input.path("phaseSha256").textValue(),
                    "jsonPointer",
                    "/verifiedAssertions/fixtureRole"));
      }
      result.set("rules", rules);
      result.set("sourceQuality", quality);
      result.set("inheritedPhaseClassification", phase.path("closureClassification"));
      result.set("inheritedPhaseMissingEvidence", phase.path("missingEvidence"));
      result.set(
          "captureWindow",
          object(
              "startedAt",
              phase.path("captureStartedAt").asText(),
              "endedAt",
              phase.path("captureEndedAt").asText()));
      result.set("proofRefs", references("/"));
      ArrayNode blocking = result.putArray("blockingReasons");
      for (var issue : conflicts.entrySet())
        blocking.add(issue(issue.getKey(), issue.getValue(), "CONFLICT"));
      for (var issue : gaps.entrySet())
        blocking.add(issue(issue.getKey(), issue.getValue(), "MISSING"));
      if (beginUnknown)
        blocking.add(issue("BEGIN_ORIGINAL_XID_UNPROVEN", "PAY/TC_EVIDENCE", "MISSING"));
      result.set("missingEvidence", blocking.deepCopy());
      result
          .putArray("observabilityLimits")
          .add("CROSS_SOURCE_WINDOWS_NOT_ATOMIC")
          .add("ACCOUNT_TOTAL_CAUSALITY_NOT_PROVEN")
          .add("COMMIT_BRANCH_ACK_UNOBSERVED")
          .add("BROKER_FULL_ACK_HISTORY_NOT_PROVEN")
          .add("PAYLOAD_TO_WIRE_BODY_NOT_RECONSTRUCTIBLE_FROM_DIGEST");
      result
          .putArray("supplementalProofs")
          .add("ORIGINAL_PHASE_CLASSIFICATION_RETAINED")
          .add(
              mode.equals("LEGACY")
                  ? "TC_NOT_APPLICABLE_BY_ORIGINAL_LEGACY_ROWS"
                  : rulePassed("TC_ORIGINAL_REGISTRATION_AND_DIRECTION")
                          && rulePassed("TCC_ORIGINAL_KEYS_BRANCHES_AND_FUNDS")
                      ? "TC_NATIVE_AND_LOCAL_FACTS_JOINED_WITHOUT_INVENTING_ACK"
                      : "TC_PROOF_NOT_COMPLETED");
      return result;
    }

    private void ledger() throws Exception {
      account = one("user", "mall_user", true);
      exact(account.path("id"), userId, "ACCOUNT_USER", "USER");
      nonnegative(account.path("balance"), "ACCOUNT_BALANCE", "USER");
      nonnegative(account.path("frozen_balance"), "ACCOUNT_FROZEN", "USER");
      List<JsonNode> debits = new ArrayList<>(), refunds = new ArrayList<>();
      Set<String> keys = new HashSet<>();
      for (JsonNode row : records("user", "balance_ledger", false)) {
        same(row.path("state"), "APPLIED", "LEDGER_STATE", "USER");
        String type = text(row.path("operation_type"));
        if ("DEBIT".equals(type)) debits.add(row);
        else if ("REFUND".equals(type)) refunds.add(row);
        else fail("LEDGER_TYPE", "USER");
        String key = fingerprint(row.path("operation_key"));
        if (key == null) missing("LEDGER_KEY_MISSING", "USER");
        else if (!keys.add(key)) fail("DUPLICATE_LEDGER_KEY", "USER");
        BigDecimal before = nonnegative(row.path("balance_before"), "LEDGER_BEFORE", "USER"),
            after = nonnegative(row.path("balance_after"), "LEDGER_AFTER", "USER"),
            value = decimal(row.path("amount"));
        if (value == null) missing("LEDGER_AMOUNT_MISSING", "USER");
        else if (before != null && after != null) {
          BigDecimal delta = "DEBIT".equals(type) ? before.subtract(after) : after.subtract(before);
          if (delta.compareTo(value) != 0) fail("LEDGER_DELTA", "USER");
        }
      }
      if (debits.size() > 1 || refunds.size() > 1) fail("DUPLICATE_PAYMENT_LEDGER", "USER");
      debit = debits.isEmpty() ? NullNode.instance : debits.get(0);
      refund = refunds.isEmpty() ? NullNode.instance : refunds.get(0);
      if (!debit.isNull()) {
        fp(debit.path("operation_key"), hash(payNo), "DEBIT_ORIGINAL_KEY", "USER");
        if (!debit.path("original_operation_key").isNull()) fail("DEBIT_REFUND_ORIGIN", "USER");
      }
      if (!refund.isNull()) {
        fp(refund.path("operation_key"), hash("refund:" + payNo), "REFUND_ORIGINAL_KEY", "USER");
        fp(refund.path("original_operation_key"), hash(payNo), "REFUND_DEBIT_ORIGIN", "USER");
        if (debit.isNull()) fail("ORPHAN_REFUND", "USER");
        BigDecimal total = decimal(refund.path("amount"));
        if (total != null && total.compareTo(amount) > 0) fail("REFUND_EXCEEDS_DEBIT", "USER");
      }
    }

    private void legacy() throws Exception {
      JsonNode intent = one("pay", "pay_debit_intent", true),
          attempt = one("user", "balance_debit_attempt", true);
      same(intent.path("debit_mode"), "LEGACY", "LEGACY_INTENT_MODE", "PAY");
      if (!"SUCCESS".equals(text(intent.path("status"))))
        missing("UNSUPPORTED_LEGACY_SETTLEMENT_PROFILE", "PM_ARCH");
      same(attempt.path("mode"), "LEGACY", "LEGACY_ATTEMPT_MODE", "USER");
      if (!"APPLIED".equals(text(attempt.path("state"))))
        missing("UNSUPPORTED_LEGACY_SETTLEMENT_PROFILE", "PM_ARCH");
      fp(attempt.path("operation_key"), hash(payNo), "LEGACY_OPERATION_KEY", "USER");
      fp(attempt.path("ledger_key"), hash(payNo), "LEGACY_LEDGER_KEY", "USER");
      // 缺行已经由 one() 标为缺证；缺列不等于已观察 SQL NULL，另一列的明确冲突仍优先。
      if (!attempt.isNull())
        for (String field : List.of("xid", "branch_id")) {
          if (!attempt.has(field))
            missing("LEGACY_" + field.toUpperCase(java.util.Locale.ROOT) + "_UNOBSERVED", "USER");
          else if (!attempt.path(field).isNull()) fail("LEGACY_TCC_IDENTITY", "USER");
        }
      for (String table :
          List.of(
              "pay_tcc_execution",
              "pay_tcc_receipt",
              "pay_tcc_dispatch",
              "pay_tcc_delivery",
              "pay_tcc_cancel"))
        if (!records("pay", table, false).isEmpty()) fail("LEGACY_TCC_ROWS", "PAY");
      for (String table : List.of("user_tcc_receive", "balance_tcc_debit"))
        if (!records("user", table, false).isEmpty()) fail("LEGACY_TCC_ROWS", "USER");
      if (debit.isNull()) missing("ORIGINAL_DEBIT_MISSING", "USER");
      if (!refund.isNull()) missing("UNSUPPORTED_LEGACY_REFUND_PROFILE", "PM_ARCH");
      if (!"SUCCESS".equals(text(payment.path("status"))))
        missing("UNSUPPORTED_LEGACY_SETTLEMENT_PROFILE", "PM_ARCH");
      if (gaps.isEmpty() && conflicts.isEmpty()) money = "LEGACY_CONFIRMED";
    }

    private void tccLocal() throws Exception {
      if (execution.isNull()) {
        missing("EXECUTION_MISSING", "PAY");
        return;
      }
      xid = fingerprint(execution.path("xid"));
      beginUnknown =
          "BEGIN_UNKNOWN".equals(text(execution.path("state")))
              || execution.path("xid").isNull()
              || execution.path("xid").isMissingNode();
      if (beginUnknown) return;
      if (xid == null) {
        missing("ORIGINAL_XID_MISSING", "PAY");
        return;
      }
      JsonNode receipt = one("pay", "pay_tcc_receipt", true),
          dispatch = one("pay", "pay_tcc_dispatch", true),
          receive = one("user", "user_tcc_receive", true),
          attempt = one("user", "balance_debit_attempt", true),
          tccDebit = one("user", "balance_tcc_debit", true);
      payBranch = fingerprint(receipt.path("branch_id"));
      userBranch = fingerprint(dispatch.path("user_branch_id"));
      if (payBranch == null || userBranch == null) missing("ORIGINAL_BRANCH_MISSING", "PAY/USER");
      else if (payBranch.equals(userBranch)) fail("BRANCH_OWNERSHIP_CONFLICT", "PAY/USER");
      for (JsonNode row : List.of(execution, receipt, dispatch, receive, attempt, tccDebit)) {
        fp(row.path("xid"), xid, "ORIGINAL_XID", "PAY/USER");
        fp(row.path("operation_key"), hash("tcc-debit:" + payNo), "TCC_OPERATION_KEY", "PAY/USER");
        if (row != tccDebit) fp(row.path("ledger_key"), hash(payNo), "TCC_LEDGER_KEY", "PAY/USER");
      }
      for (JsonNode row : List.of(receive, attempt, tccDebit))
        fp(row.path("branch_id"), userBranch, "USER_BRANCH", "USER");
      same(attempt.path("mode"), "TCC", "TCC_ATTEMPT_MODE", "USER");
      if (!Set.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED")
          .contains(receive.path("state").asText())) missing("RECEIVE_STATE_UNSUPPORTED", "USER");
      delivery = one("pay", "pay_tcc_delivery", false);
      if (!delivery.isNull()) {
        fp(delivery.path("xid"), xid, "DELIVERY_XID", "PAY");
        fp(
            delivery.path("refund_operation_key"),
            hash("refund:" + payNo),
            "DELIVERY_REFUND_KEY",
            "PAY");
      }
      String state = text(execution.path("state"));
      boolean committed = "COMMITTED".equals(state),
          cancelled = "ROLLEDBACK".equals(state),
          trying = "RUNNING".equals(state);
      if (!committed && !cancelled && !trying) {
        missing("LOCAL_MONEY_SETTLEMENT_PENDING", "PAY");
        return;
      }
      same(
          execution.path("decision"),
          committed ? "COMMIT" : cancelled ? "ROLLBACK" : "NONE",
          "EXECUTION_DECISION",
          "PAY");
      String funds = committed ? "CONFIRMED" : cancelled ? "CANCELLED" : "TRYING";
      same(receipt.path("state"), funds, "RECEIPT_MONEY_STATE", "PAY");
      same(dispatch.path("state"), trying ? "TRY_ACCEPTED" : funds, "DISPATCH_MONEY_STATE", "PAY");
      same(attempt.path("state"), funds, "ATTEMPT_MONEY_STATE", "USER");
      same(tccDebit.path("state"), funds, "TCC_DEBIT_MONEY_STATE", "USER");
      fence(
          "pay",
          payBranch,
          PAY_RESOURCE,
          committed ? 2 : cancelled ? 3 : 1,
          committed || cancelled);
      fence(
          "user",
          userBranch,
          USER_RESOURCE,
          committed ? 2 : cancelled ? 3 : 1,
          committed || cancelled);
      BigDecimal before = nonnegative(tccDebit.path("balance_before"), "TCC_BEFORE", "USER"),
          after = nonnegative(tccDebit.path("balance_after_try"), "TCC_AFTER_TRY", "USER");
      if (before != null && after != null && before.subtract(after).compareTo(amount) != 0)
        fail("TCC_TRY_DELTA", "USER");
      if (committed && debit.isNull()) missing("ORIGINAL_DEBIT_MISSING", "USER");
      if ((cancelled || trying) && (!debit.isNull() || !refund.isNull()))
        fail("UNCOMMITTED_APPLIED_LEDGER", "USER");
      BigDecimal frozen = decimal(account.path("frozen_balance"));
      if (trying && (frozen == null || frozen.compareTo(amount) < 0))
        missing("ORIGINAL_FREEZE_NOT_PROVEN", "USER");
      if (cancelled && frozen != null && frozen.signum() != 0)
        missing("CANCELLED_FREEZE_OWNERSHIP_NOT_PROVEN", "USER");
    }

    private void fence(
        String service, String branch, String resource, int expected, boolean permanent)
        throws Exception {
      String owner = service.toUpperCase(java.util.Locale.ROOT);
      JsonNode source = phase.at("/sources/" + service + "/fence");
      if (!"OBSERVED".equals(text(source.path("status")))) {
        missing("FENCE_SOURCE_UNAVAILABLE", owner);
        return;
      }
      JsonNode table = source.at("/tables/tcc_fence_log");
      List<JsonNode> rows = tableRows(table, owner);
      if (rows.isEmpty() && permanent) {
        observation(
            "PERMANENT_LOCAL_TERMINAL_REPLACES_EXPIRED_FENCE_" + service,
            "SOURCE_LIMIT",
            owner,
            "/sources/" + service + "/fence");
        return;
      }
      if (rows.size() != 1) {
        if (rows.size() > 1) fail("FENCE_CARDINALITY", owner);
        else missing("FENCE_MISSING", owner);
        return;
      }
      JsonNode row = rows.get(0);
      fp(row.path("xid"), xid, "FENCE_XID", owner);
      fp(row.path("branch_id"), branch, "FENCE_BRANCH", owner);
      same(row.path("action_name"), resource, "FENCE_RESOURCE", owner);
      Long status = exactNumber(row.path("status"));
      if (status == null) missing("FENCE_STATUS_MISSING", owner);
      else if (status != expected && !(expected == 3 && status == 4))
        fail("FENCE_DIRECTION", owner);
    }

    private void orderStock() throws Exception {
      order = one("order", "mall_order_" + month, true);
      tuple(order, "ORDER", false);
      equalMoney(order.path("pay_amount"), amount, "ORDER_PAY_AMOUNT", "ORDER");
      List<JsonNode> items = records("order", "mall_order_item_" + month, true);
      TreeMap<Long, Long> vector = new TreeMap<>();
      BigDecimal lines = BigDecimal.ZERO;
      for (JsonNode item : items) {
        same(item.path("order_no"), orderNo, "ORDER_ITEM_ORDER", "ORDER");
        if (order.path("id").isMissingNode() || order.path("id").isNull())
          missing("ORDER_ITEM_PARENT_MISSING", "ORDER");
        else if (!item.path("order_id").equals(order.path("id")))
          fail("ORDER_ITEM_PARENT", "ORDER");
        Long sku = exactNumber(item.path("sku_id")), quantity = exactNumber(item.path("quantity"));
        if (sku == null
            || sku <= 0
            || quantity == null
            || quantity <= 0
            || quantity > Integer.MAX_VALUE) {
          missing("ORDER_ITEM_DOMAIN", "ORDER");
          continue;
        }
        if (vector.put(sku, quantity) != null) fail("DUPLICATE_ORDER_SKU", "ORDER");
        BigDecimal price = nonnegative(item.path("unit_price"), "ITEM_PRICE", "ORDER"),
            line = nonnegative(item.path("line_amount"), "ITEM_LINE", "ORDER");
        if (price != null && line != null) {
          if (price.multiply(BigDecimal.valueOf(quantity)).compareTo(line) != 0)
            fail("ORDER_LINE_AMOUNT", "ORDER");
          lines = lines.add(line);
        }
      }
      if (!items.isEmpty() && vector.size() == items.size())
        equalMoney(order.path("total_amount"), lines, "ORDER_TOTAL_AMOUNT", "ORDER");
      reservation = one("stock", "stock_reservation_request", true);
      same(reservation.path("order_no"), orderNo, "RESERVATION_ORDER", "STOCK");
      same(reservation.path("scene"), "NORMAL", "RESERVATION_SCENE", "STOCK");
      TreeMap<Long, Long> reserved = new TreeMap<>();
      JsonNode snapshot = reservation.path("items_snapshot");
      if (!snapshot.isArray() || snapshot.isEmpty() || snapshot.size() > ROW_LIMIT)
        missing("RESERVATION_ITEMS_MISSING", "STOCK");
      else
        for (JsonNode item : snapshot) {
          Long sku = exactNumber(item.path("skuId")), quantity = exactNumber(item.path("quantity"));
          if (sku == null || sku <= 0 || quantity == null || quantity <= 0)
            missing("RESERVATION_ITEM_DOMAIN", "STOCK");
          else if (reserved.put(sku, quantity) != null) fail("RESERVATION_DUPLICATE_SKU", "STOCK");
        }
      if (snapshot.isArray()
          && !snapshot.isEmpty()
          && reserved.size() == snapshot.size()
          && !vector.isEmpty())
        compareVector(vector, reserved, "RESERVATION_ORDER_VECTOR", "ORDER/STOCK");
      ArrayNode writer = JSON.createArrayNode();
      reserved.forEach(
          (sku, quantity) ->
              writer.add(JSON.createObjectNode().put("skuId", sku).put("quantity", quantity)));
      if (!reserved.isEmpty() && snapshot.isArray() && reserved.size() == snapshot.size())
        same(
            reservation.path("items_hash"),
            hash(JSON.writeValueAsBytes(writer)),
            "RESERVATION_ITEMS_HASH",
            "STOCK");
      String stockState = text(reservation.path("status"));
      String target =
          "CONFIRMED".equals(stockState)
              ? "CONFIRM"
              : "ROLLED_BACK".equals(stockState)
                  ? "ROLLBACK"
                  : "RESERVED".equals(stockState) ? null : "UNKNOWN";
      Map<String, TreeMap<Long, Long>> flows = new HashMap<>();
      Set<String> flowKeys = new HashSet<>();
      for (JsonNode flow : records("stock", "stock_flow", true)) {
        same(flow.path("order_no"), orderNo, "FLOW_ORDER", "STOCK");
        String type = text(flow.path("flow_type"));
        Long sku = exactNumber(flow.path("sku_id")), quantity = exactNumber(flow.path("quantity"));
        if (!Set.of("RESERVE", "CONFIRM", "ROLLBACK").contains(type == null ? "" : type)
            || sku == null
            || quantity == null
            || quantity <= 0) {
          missing("STOCK_FLOW_DOMAIN", "STOCK");
          continue;
        }
        String key = hash(orderNo + ":" + type + ":" + sku);
        fp(flow.path("idempotency_key"), key, "FLOW_ORIGINAL_KEY", "STOCK");
        if (!flowKeys.add(key)) fail("DUPLICATE_STOCK_FLOW", "STOCK");
        flows
            .computeIfAbsent(type, ignored -> new TreeMap<>())
            .merge(sku, quantity, Math::addExact);
      }
      if (completeTable("stock", "stock_flow") && !vector.isEmpty())
        compareVector(vector, flows.get("RESERVE"), "RESERVE_FLOW_VECTOR", "STOCK");
      if (target != null && !"UNKNOWN".equals(target)) {
        if (completeTable("stock", "stock_flow") && !vector.isEmpty())
          compareVector(vector, flows.get(target), "TERMINAL_FLOW_VECTOR", "STOCK");
        String opposite = target.equals("CONFIRM") ? "ROLLBACK" : "CONFIRM";
        if (flows.containsKey(opposite)) fail("OPPOSITE_STOCK_FLOW", "STOCK");
        JsonNode action = one("order", "order_stock_action", true);
        same(action.path("order_no"), orderNo, "ACTION_ORDER", "ORDER");
        same(action.path("action_type"), target, "ACTION_DIRECTION", "ORDER");
        same(action.path("status"), "SUCCEEDED", "ACTION_SETTLEMENT", "ORDER");
        fp(
            action.path("operation_key"),
            hash(orderNo + ":" + target),
            "ACTION_ORIGINAL_KEY",
            "ORDER");
      } else if ("UNKNOWN".equals(target)) missing("STOCK_SETTLEMENT_UNSUPPORTED", "STOCK");
      if (target == null && (flows.containsKey("CONFIRM") || flows.containsKey("ROLLBACK")))
        fail("RESERVED_WITH_TERMINAL_FLOWS", "STOCK");
      Set<Long> observedSkus = new HashSet<>();
      for (JsonNode row : records("stock", "stock_sku", true)) {
        Long total = exactNumber(row.path("total_quantity")),
            available = exactNumber(row.path("available_quantity")),
            frozen = exactNumber(row.path("reserved_quantity")),
            sold = exactNumber(row.path("sold_quantity"));
        if (total == null || available == null || frozen == null || sold == null)
          missing("STOCK_TOTAL_FIELDS_MISSING", "STOCK");
        else if (total < 0
            || available < 0
            || frozen < 0
            || sold < 0
            || BigDecimal.valueOf(available)
                    .add(BigDecimal.valueOf(frozen))
                    .add(BigDecimal.valueOf(sold))
                    .compareTo(BigDecimal.valueOf(total))
                != 0) fail("STOCK_PROJECTION_CONSERVATION", "STOCK");
        Long sku = exactNumber(row.path("sku_id"));
        if (sku == null || !observedSkus.add(sku) || !vector.isEmpty() && !vector.containsKey(sku))
          fail("STOCK_SKU_IDENTITY", "STOCK");
      }
      if (!observedSkus.equals(vector.keySet())) missing("STOCK_SKU_SET_INCOMPLETE", "STOCK");
    }

    /** 缺少向量元素属于缺证；已观察的同 SKU 数量相反才是可靠冲突。 */
    private void compareVector(
        Map<Long, Long> expected, Map<Long, Long> actual, String code, String owner) {
      if (actual == null || !actual.keySet().equals(expected.keySet()))
        missing(code + "_INCOMPLETE", owner);
      if (actual != null)
        for (Map.Entry<Long, Long> item : expected.entrySet())
          if (actual.containsKey(item.getKey())
              && !actual.get(item.getKey()).equals(item.getValue())) fail(code, owner);
    }

    private void settlement() throws Exception {
      String payState = text(payment.path("status")),
          orderState = text(order.path("status")),
          stockState = text(reservation.path("status"));
      boolean paid =
          Set.of("PAID", "COMPLETED").contains(orderState == null ? "" : orderState)
              && "CONFIRMED".equals(stockState);
      boolean cancelled = "CANCELLED".equals(orderState) && "ROLLED_BACK".equals(stockState);
      if (money.equals("TRYING")) {
        outcome = "PENDING";
        if (orderState == null || stockState == null)
          missing("TRYING_SETTLEMENT_MISSING", "ORDER/STOCK");
        else if (!"PENDING_PAYMENT".equals(orderState) || !"RESERVED".equals(stockState))
          fail("TRYING_SETTLEMENT_DIRECTION", "ORDER/STOCK");
        return;
      }
      if (money.equals("COMMITTED") && "REFUNDED".equals(payState)) {
        if (refund.isNull()) missing("FULL_REFUND_MISSING", "USER");
        else equalMoney(refund.path("amount"), amount, "FULL_REFUND_AMOUNT", "USER");
        if (delivery == null || delivery.isNull()) missing("DELIVERY_MISSING", "PAY");
        else same(delivery.path("state"), "REFUNDED", "DELIVERY_REFUND_STATE", "PAY");
        outcome = cancelled ? "REFUNDED" : "PENDING";
        complete = cancelled && gaps.isEmpty() && conflicts.isEmpty();
      } else if (money.equals("COMMITTED") || money.equals("LEGACY_CONFIRMED")) {
        outcome = paid && "SUCCESS".equals(payState) ? "DELIVERED" : "PENDING";
        if (money.equals("COMMITTED") && paid) {
          if (delivery == null || delivery.isNull()) missing("DELIVERY_MISSING", "PAY");
          else same(delivery.path("state"), "SUCCESS", "DELIVERY_SUCCESS_STATE", "PAY");
        }
        if (!refund.isNull() && !"REFUNDED".equals(payState))
          missing("REFUND_SETTLEMENT_PENDING", "PAY/USER");
        complete = outcome.equals("DELIVERED") && gaps.isEmpty() && conflicts.isEmpty();
      } else if (money.equals("CANCELLED")) {
        outcome = cancelled ? "CANCELLED" : "PENDING";
        if (!Set.of("FAILED", "CLOSED").contains(payState == null ? "" : payState))
          missing("PAY_CANCEL_SETTLEMENT_PENDING", "PAY");
        complete = cancelled && gaps.isEmpty() && conflicts.isEmpty();
      }
      if (!complete) missing("SETTLEMENT_PENDING", "PAY/ORDER/STOCK");
    }

    private void baseIdentity() throws Exception {
      same(base.path("runId"), run, "BASE_RUN_IDENTITY", "LOCAL_OPERATOR/QA");
      for (String field :
          List.of(
              "payNo",
              "orderNo",
              "userId",
              "amount",
              "debitMode",
              "executionId",
              "xidSha256",
              "payBranchSha256",
              "userBranchSha256",
              "operation_key_sha256",
              "ledger_key_sha256")) {
        JsonNode prior = base.at("/subject/" + field), selected = phase.at("/subject/" + field);
        if (!prior.isNull() && !prior.isMissingNode() && !prior.equals(selected))
          fail("BASE_IMMUTABLE_IDENTITY_CHANGED", "PAY/USER");
      }
      for (String service : List.of("pay", "user", "order", "stock")) {
        JsonNode beforeTables = base.at("/sources/" + service + "/tables"),
            selectedTables = phase.at("/sources/" + service + "/tables");
        Iterator<String> names = beforeTables.fieldNames();
        while (names.hasNext()) {
          String table = names.next();
          JsonNode beforeRows = beforeTables.path(table).path("rows"),
              afterRows = selectedTables.path(table).path("rows");
          if (beforeRows.isArray() && afterRows.isArray())
            for (JsonNode original : beforeRows) {
              JsonNode matched = NullNode.instance;
              for (JsonNode candidate : afterRows)
                if (naturalIdentity(table, original).equals(naturalIdentity(table, candidate))) {
                  matched = candidate;
                  break;
                }
              if (matched.isNull())
                missing("BASE_ORIGINAL_ROW_MISSING", service.toUpperCase(java.util.Locale.ROOT));
              else if (!original.path("id").isMissingNode()
                  && !original.path("id").equals(matched.path("id")))
                fail("BASE_PRIMARY_IDENTITY_CHANGED", service.toUpperCase(java.util.Locale.ROOT));
            }
        }
      }
    }

    private String naturalIdentity(String table, JsonNode row) {
      if (table.equals("stock_flow"))
        return row.path("order_no").asText()
            + ":"
            + row.path("flow_type").asText()
            + ":"
            + row.path("sku_id").asText();
      if (table.startsWith("mall_order_item_"))
        return row.path("order_no").asText() + ":" + row.path("sku_id").asText();
      for (String field : List.of("operation_key", "pay_no", "order_no", "sku_id", "id"))
        if (row.hasNonNull(field)) return field + ":" + row.path(field).asText();
      return "UNIDENTIFIED_ROW";
    }

    private List<JsonNode> records(String service, String table, boolean mandatory)
        throws Exception {
      List<JsonNode> records =
          tableRows(
              phase.at("/sources/" + service + "/tables/" + table),
              service.toUpperCase(java.util.Locale.ROOT));
      if (mandatory && records.isEmpty())
        missing(
            "MANDATORY_ROW_MISSING_" + table.toUpperCase(java.util.Locale.ROOT),
            service.toUpperCase(java.util.Locale.ROOT));
      return records;
    }

    private List<JsonNode> tableRows(JsonNode table, String owner) throws Exception {
      String status = text(table.path("status"));
      JsonNode rows = table.path("rows");
      if (!Set.of("OBSERVED", "EMPTY").contains(status == null ? "" : status) || !rows.isArray()) {
        missing("SOURCE_ROWS_UNAVAILABLE", owner);
        return List.of();
      }
      Long count = exactNumber(table.path("rowCount"));
      if (count == null
          || count > ROW_LIMIT
          || rows.size() > ROW_LIMIT
          || count != rows.size()
          || "EMPTY".equals(status) != rows.isEmpty()) {
        missing("BOUND_OR_ROWCOUNT_INCOMPLETE", owner);
        return List.of();
      }
      List<JsonNode> result = new ArrayList<>();
      rows.forEach(result::add);
      return result;
    }

    private JsonNode one(String service, String table, boolean mandatory) throws Exception {
      List<JsonNode> rows = records(service, table, mandatory);
      if (rows.size() > 1)
        fail(
            "UNIQUE_ROW_CARDINALITY_" + table.toUpperCase(java.util.Locale.ROOT),
            service.toUpperCase(java.util.Locale.ROOT));
      return rows.isEmpty() ? NullNode.instance : rows.get(0);
    }

    private void tuple(JsonNode row, String owner, boolean monetary) throws Exception {
      if (row.isNull()) return;
      if (monetary || row.has("pay_no"))
        same(row.path("pay_no"), payNo, "ORIGINAL_PAY_IDENTITY", owner);
      same(row.path("order_no"), orderNo, "ORIGINAL_ORDER_IDENTITY", owner);
      exact(row.path("user_id"), userId, "ORIGINAL_USER_IDENTITY", owner);
      if (monetary && !"REFUND".equals(text(row.path("operation_type"))))
        equalMoney(row.path("amount"), amount, "ORIGINAL_AMOUNT", owner);
      else if (monetary) {
        BigDecimal value = decimal(row.path("amount"));
        if (value == null) missing("REFUND_AMOUNT_MISSING", owner);
        else if (value.signum() <= 0 || value.compareTo(amount) > 0)
          fail("REFUND_AMOUNT_DOMAIN", owner);
      }
    }

    private void window(JsonNode source, String owner) {
      try {
        Instant start = Instant.parse(source.path("captureStartedAt").asText()),
            end = Instant.parse(source.path("captureEndedAt").asText());
        if (end.isBefore(start)) missing("SOURCE_WINDOW_INVALID", owner);
      } catch (Exception invalid) {
        missing("SOURCE_WINDOW_MISSING", owner);
      }
    }

    private void same(JsonNode actual, String expected, String code, String owner) {
      String value = text(actual);
      if (value == null || expected == null) missing(code + "_MISSING", owner);
      else if (!value.equals(expected)) fail(code, owner);
    }

    private void fp(JsonNode actual, String expected, String code, String owner) {
      String value = fingerprint(actual);
      if (value == null || expected == null) missing(code + "_MISSING", owner);
      else if (!value.equals(expected)) fail(code, owner);
    }

    private void exact(JsonNode actual, long expected, String code, String owner) {
      Long value = exactNumber(actual);
      if (value == null) missing(code + "_MISSING", owner);
      else if (value != expected) fail(code, owner);
    }

    private void equalMoney(JsonNode actual, BigDecimal expected, String code, String owner) {
      BigDecimal value = decimal(actual);
      if (value == null || expected == null) missing(code + "_MISSING", owner);
      else if (value.compareTo(expected) != 0) fail(code, owner);
    }

    private BigDecimal nonnegative(JsonNode value, String code, String owner) {
      BigDecimal amount = decimal(value);
      if (amount == null) missing(code + "_MISSING", owner);
      else if (amount.signum() < 0) fail(code, owner);
      return amount;
    }

    private void missing(String code, String owner) {
      gaps.putIfAbsent(code, owner);
    }

    private void fail(String code, String owner) {
      conflicts.putIfAbsent(code, owner);
    }

    private boolean rulePassed(String id) {
      for (JsonNode result : rules)
        if (id.equals(text(result.path("id"))) && "PASS".equals(text(result.path("status"))))
          return true;
      return false;
    }

    private void rule(String id, String owner, String pointer, Work work) throws Exception {
      int failures = conflicts.size(), absent = gaps.size();
      work.run();
      observation(
          id,
          conflicts.size() > failures ? "FAIL" : gaps.size() > absent ? "MISSING" : "PASS",
          owner,
          pointer);
    }

    private void observation(String id, String status, String owner, String pointer) {
      ObjectNode rule =
          object(
              "id",
              id,
              "status",
              status,
              "expectedSafe",
              "FROZEN_ORIGINAL_RELATION",
              "observedSafe",
              status,
              "owner",
              owner);
      rule.set("proofRefs", references(pointer));
      rules.add(rule);
    }

    private ArrayNode references(String pointer) {
      ArrayNode refs = JSON.createArrayNode();
      refs.add(
          object(
              "sha256",
              input.path("phaseSha256").asText(),
              "jsonPointer",
              pointer.equals("/") ? "" : pointer));
      for (String service : List.of("pay", "user", "order", "stock")) {
        JsonNode tables = phase.at("/sources/" + service + "/tables");
        Iterator<String> names = tables.fieldNames();
        while (names.hasNext()) {
          String table = names.next();
          if (tables.path(table).has("rows"))
            refs.add(
                object(
                    "sha256",
                    input.path("phaseSha256").asText(),
                    "jsonPointer",
                    "/sources/" + service + "/tables/" + table + "/rows"));
        }
        if (phase.at("/sources/" + service + "/fence/tables/tcc_fence_log/rows").isArray())
          refs.add(
              object(
                  "sha256",
                  input.path("phaseSha256").asText(),
                  "jsonPointer",
                  "/sources/" + service + "/fence/tables/tcc_fence_log/rows"));
      }
      if (input.hasNonNull("tcSha256"))
        for (String path : List.of("/events", "/sourceStartAnchor", "/sourceRead/endAnchor"))
          if (!tc.at(path).isMissingNode())
            refs.add(object("sha256", input.path("tcSha256").asText(), "jsonPointer", path));
      if (input.hasNonNull("mqSha256")) {
        Iterator<String> names = mq.path("observations").fieldNames();
        while (names.hasNext())
          refs.add(
              object(
                  "sha256",
                  input.path("mqSha256").asText(),
                  "jsonPointer",
                  "/observations/" + names.next()));
        if (mq.has("driverActions"))
          refs.add(
              object("sha256", input.path("mqSha256").asText(), "jsonPointer", "/driverActions"));
      }
      if (input.hasNonNull("basePhaseSha256"))
        refs.add(
            object("sha256", input.path("basePhaseSha256").asText(), "jsonPointer", "/subject"));
      return refs;
    }

    private boolean completeTable(String service, String table) {
      JsonNode value = phase.at("/sources/" + service + "/tables/" + table);
      Long count = exactNumber(value.path("rowCount"));
      return Set.of("OBSERVED", "EMPTY").contains(value.path("status").asText())
          && value.path("rows").isArray()
          && count != null
          && count == value.path("rows").size()
          && count <= ROW_LIMIT;
    }

    private ObjectNode issue(String code, String owner, String status) {
      ObjectNode issue =
          object(
              "code",
              code,
              "status",
              status,
              "owner",
              owner,
              "nextEvidenceSource",
              "ORIGINAL_PROTOCOL_IDENTITY_AND_PINNED_SOURCE_ARTIFACT",
              "retention",
              "NO_AUTOMATIC_EXPIRY",
              "forbiddenActions",
              "NO_BEGIN_TRY_NEW_BRANCH_REFUND_REPUBLISH_OR_CLEANUP");
      issue.put("automaticActionsAllowed", false);
      issue.set("proofRefs", references("/"));
      return issue;
    }

    private void tcRelations() throws Exception {
      if (tc.isNull()) {
        missing("TC_NATIVE_SOURCE_MISSING", "TC_EVIDENCE");
        quality.set("tc", object("status", "UNAVAILABLE"));
        return;
      }
      quality.set(
          "tc",
          object(
              "status",
              tc.path("status").asText("INCOMPLETE"),
              "directCommitAck",
              "ACK_UNOBSERVED"));
      same(tc.path("runId"), run, "TC_RUN", "TC_EVIDENCE");
      fp(tc.path("xidSha256"), xid, "TC_XID", "TC_EVIDENCE");
      if (!"OK".equals(text(tc.at("/sourceRead/status"))))
        missing("TC_SOURCE_UNAVAILABLE", "TC_EVIDENCE");
      if (!tc.at("/sourceRead/identityStable").isBoolean())
        missing("TC_IDENTITY_STABILITY_MISSING", "TC_EVIDENCE");
      else if (!tc.at("/sourceRead/identityStable").booleanValue())
        tcSourceChanged("TC_IDENTITY_UNSTABLE");
      if (!"DOCKER_DAEMON_SYSTEM_TIME".equals(text(tc.path("windowClockSource"))))
        missing("TC_CLOCK_SOURCE_UNPROVEN", "TC_EVIDENCE");
      JsonNode start = tc.path("sourceStartAnchor"), end = tc.at("/sourceRead/endAnchor");
      for (JsonNode anchor : List.of(start, end)) {
        if (!"OK".equals(text(anchor.path("status"))))
          missing("TC_ANCHOR_UNAVAILABLE", "TC_EVIDENCE");
        for (String field : List.of("daemonIdentitySha256", "tcIdentitySha256"))
          if (fingerprint(anchor.path(field)) == null)
            missing("TC_ANCHOR_IDENTITY_MISSING", "TC_EVIDENCE");
        Instant hostStart = instant(anchor.path("hostRequestStartedAt")),
            hostEnd = instant(anchor.path("hostRequestEndedAt"));
        Long elapsed = exactNumber(anchor.path("requestElapsedNanos"));
        if (instant(anchor.path("sourceTime")) == null
            || hostStart == null
            || hostEnd == null
            || hostEnd.isBefore(hostStart)
            || elapsed == null
            || elapsed < 0
            || elapsed > 10_000_000_000L) missing("TC_ANCHOR_TIME_INVALID", "TC_EVIDENCE");
      }
      for (String field : List.of("daemonIdentitySha256", "tcIdentitySha256"))
        if (fingerprint(start.path(field)) != null
            && fingerprint(end.path(field)) != null
            && !start.path(field).equals(end.path(field))) tcSourceChanged("TC_IDENTITY_CHANGED");
      String sourceIdentity = fingerprint(tc.at("/sourceRead/containerIdentitySha256"));
      if (sourceIdentity == null) missing("TC_CONTAINER_IDENTITY_MISSING", "TC_EVIDENCE");
      else if (fingerprint(start.path("tcIdentitySha256")) != null
          && !sourceIdentity.equals(fingerprint(start.path("tcIdentitySha256"))))
        tcSourceChanged("TC_CONTAINER_IDENTITY");
      JsonNode reads = tc.at("/sourceRead/readStatuses");
      if (!reads.isArray() || reads.size() != 4)
        missing("TC_SOURCE_READ_STATUS_MISSING", "TC_EVIDENCE");
      else
        for (JsonNode status : reads)
          if (!"OK".equals(text(status))) missing("TC_SOURCE_READ_INCOMPLETE", "TC_EVIDENCE");
      if (!start.path("sourceTime").equals(tc.path("windowStartedAt")))
        tcSourceChanged("TC_START_CLOCK");
      if (!end.path("sourceTime").equals(tc.path("windowEndedAt"))) tcSourceChanged("TC_END_CLOCK");
      JsonNode anchored = archive.selected(requiredHash(tc.at("/anchorArtifactManifest/sha256")));
      if (!anchored.equals(start)) tcSourceChanged("TC_ANCHOR_CONTENT_MISMATCH");
      Instant first = instant(tc.path("windowStartedAt")), last = instant(tc.path("windowEndedAt"));
      if (first == null
          || last == null
          || last.isBefore(first)
          || java.time.Duration.between(first, last).compareTo(java.time.Duration.ofMinutes(30))
              > 0) {
        missing("TC_SOURCE_WINDOW_INVALID", "TC_EVIDENCE");
        return;
      }
      JsonNode attempts = tc.path("attemptArtifacts");
      if (attempts.isArray()) {
        if (attempts.isEmpty()
            || attempts.size() > 4
            || exactNumber(tc.path("attemptCount")) == null
            || tc.path("attemptCount").longValue() != attempts.size())
          missing("TC_ATTEMPT_BOUND", "TC_EVIDENCE");
        Instant prior = first;
        for (JsonNode reference : attempts) {
          JsonNode attempt = archive.selected(requiredHash(reference.path("sha256")));
          Instant until = instant(attempt.path("windowEndedAt"));
          if (!attempt.path("windowStartedAt").equals(tc.path("windowStartedAt"))
              || until == null
              || until.isBefore(prior)
              || until.isAfter(last)) tcSourceChanged("TC_ATTEMPT_WINDOW");
          if (!attempt.path("sourceStartAnchor").equals(start))
            tcSourceChanged("TC_ATTEMPT_ANCHOR");
          if (until != null) prior = until;
        }
      } else missing("TC_FINAL_ATTEMPTS_MISSING", "TC_EVIDENCE");
      for (String participant : List.of("pay", "user")) {
        JsonNode observed = tc.path(participant);
        fp(
            observed.path("branchSha256"),
            participant.equals("pay") ? payBranch : userBranch,
            "TC_BRANCH",
            "TC_EVIDENCE");
        same(
            observed.path("resource"),
            participant.equals("pay") ? PAY_RESOURCE : USER_RESOURCE,
            "TC_RESOURCE",
            "TC_EVIDENCE");
      }
      Map<String, Integer> registrations = new HashMap<>(), rollbacks = new HashMap<>();
      int commits = 0, cancels = 0;
      JsonNode events = tc.path("events");
      if (!events.isArray() || events.size() > ROW_LIMIT) {
        missing("TC_EVENTS_INCOMPLETE", "TC_EVIDENCE");
        return;
      }
      for (JsonNode event : events) {
        if (fingerprint(event.path("lineSha256")) == null)
          missing("TC_NATIVE_LINE_DIGEST_MISSING", "TC_EVIDENCE");
        Instant observed = instant(event.path("observedAt"));
        if (observed == null || observed.isBefore(first) || observed.isAfter(last)) {
          missing("TC_EVENT_OUTSIDE_WINDOW", "TC_EVIDENCE");
          continue;
        }
        String type = text(event.path("type"));
        if ("REGISTER".equals(type)) {
          String resource = text(event.path("resource")),
              branch = fingerprint(event.path("branchSha256"));
          if (resource == null || branch == null)
            missing("TC_REGISTER_RELATION_MISSING", "TC_EVIDENCE");
          else if (PAY_RESOURCE.equals(resource) && java.util.Objects.equals(payBranch, branch)
              || USER_RESOURCE.equals(resource) && java.util.Objects.equals(userBranch, branch))
            registrations.merge(resource, 1, Integer::sum);
          else fail("TC_EXTRA_OR_WRONG_BRANCH", "TC_EVIDENCE");
        } else if ("PHASE_TWO_ROLLBACK".equals(type)) {
          String branch = fingerprint(event.path("branchSha256"));
          if (branch == null) missing("TC_ROLLBACK_BRANCH_MISSING", "TC_EVIDENCE");
          else if (java.util.Objects.equals(payBranch, branch)
              || java.util.Objects.equals(userBranch, branch))
            rollbacks.merge(branch, 1, Integer::sum);
          else fail("TC_EXTRA_OR_WRONG_BRANCH", "TC_EVIDENCE");
        } else if ("GLOBAL_COMMIT".equals(type)) commits++;
        else if ("GLOBAL_ROLLBACK".equals(type)) cancels++;
        else missing("TC_EVENT_UNSUPPORTED", "TC_EVIDENCE");
      }
      if (registrations.getOrDefault(PAY_RESOURCE, 0) != 1
          || registrations.getOrDefault(USER_RESOURCE, 0) != 1)
        missing("TC_ORIGINAL_DUAL_REGISTER_MISSING", "TC_EVIDENCE");
      String state = text(execution.path("state"));
      if ("COMMITTED".equals(state)) {
        if (cancels > 0 || !rollbacks.isEmpty()) fail("TC_OPPOSITE_MONEY_DIRECTION", "TC_EVIDENCE");
        if (commits != 1) missing("TC_GLOBAL_COMMIT_MISSING", "TC_EVIDENCE");
        if (!"SERVER_GLOBAL_COMMIT_OBSERVED".equals(text(tc.path("globalOutcome"))))
          missing("TC_GLOBAL_COMMIT_UNOBSERVED", "TC_EVIDENCE");
        observation("TC_DIRECT_COMMIT_ACK_NOT_OBSERVED", "SOURCE_LIMIT", "TC_EVIDENCE", "/");
      } else if ("ROLLEDBACK".equals(state)) {
        if (commits > 0) fail("TC_OPPOSITE_MONEY_DIRECTION", "TC_EVIDENCE");
        if (cancels != 1
            || rollbacks.getOrDefault(payBranch, 0) != 1
            || rollbacks.getOrDefault(userBranch, 0) != 1)
          missing("TC_NATIVE_ROLLBACK_INCOMPLETE", "TC_EVIDENCE");
        if (!"SERVER_GLOBAL_ROLLBACK_OBSERVED".equals(text(tc.path("globalOutcome"))))
          missing("TC_GLOBAL_ROLLBACK_UNOBSERVED", "TC_EVIDENCE");
      } else if ("RUNNING".equals(state)) {
        if (commits > 0 || cancels > 0 || !rollbacks.isEmpty())
          fail("TRYING_TC_TERMINAL_CONFLICT", "TC_EVIDENCE");
        JsonNode sdk = phase.at("/sources/tc");
        if (!"OBSERVED".equals(text(sdk.path("status"))))
          missing("TRYING_TC_ACTIVE_READ_MISSING", "TC_EVIDENCE");
        if (!"Begin".equals(text(sdk.path("observedGlobalStatus"))))
          missing("TRYING_TC_ACTIVE_STATUS_UNPROVEN", "TC_EVIDENCE");
        fp(sdk.path("xidSha256"), xid, "TRYING_TC_ACTIVE_XID", "TC_EVIDENCE");
      }
    }

    private void tcSourceChanged(String code) {
      missing("SOURCE_CHANGED_" + code, "TC_EVIDENCE");
      ((ObjectNode) quality.path("tc")).put("status", "SOURCE_CHANGED");
    }

    private void mqRelations() throws Exception {
      JsonNode orderSource = phase.at("/sources/order"),
          outboxTable = orderSource.at("/tables/order_outbox");
      String expectedSchema = run + "_order";
      // 缺证与真实空表分开；旧包可缺新归属字段，已有字段必须通过原归属门。
      if (!"OBSERVED".equals(text(orderSource.path("status")))
          || !expectedSchema.equals(text(orderSource.path("schema")))
          || orderSource.has("configuredSchema")
              && !expectedSchema.equals(text(orderSource.path("configuredSchema")))
          || orderSource.has("connectionOwnership")
              && !"VERIFIED".equals(text(orderSource.path("connectionOwnership")))
          || !completeTable("order", "order_outbox")
          || "EMPTY".equals(text(outboxTable.path("status")))
              != outboxTable.path("rows").isEmpty()) {
        missing("MQ_OUTBOX_SOURCE_UNPROVEN", "ORDER");
        quality.set(
            "mq",
            object(
                "status", "INCOMPLETE",
                "reason", "ORDER_OUTBOX_AND_TARGET_EVENT_PARTICIPATION_UNPROVEN"));
        return;
      }
      List<JsonNode> outbox = records("order", "order_outbox", false);
      if (outbox.isEmpty()) {
        String reason =
            mode.equals("LEGACY") ? "MQ_NOT_PARTICIPATING_IN_THIS_PATH" : "NO_TARGET_EVENT_YET";
        quality.set("mq", object("status", "NOT_APPLICABLE", "reason", reason));
        observation(reason, "NOT_APPLICABLE", "MQ_EVIDENCE", "/sources/order/tables/order_outbox");
        return;
      }
      if (outbox.size() != 1) {
        fail("MQ_OUTBOX_CARDINALITY", "ORDER");
        return;
      }
      JsonNode event = outbox.get(0);
      same(event.path("order_no"), orderNo, "OUTBOX_ORDER", "ORDER");
      if (!"ORDER_TIMEOUT".equals(text(event.path("event_type")))) {
        missing("MQ_EVENT_PROFILE_UNSUPPORTED", "PM_ARCH");
        return;
      }
      String eventHash = hash("order-timeout:" + orderNo);
      fp(event.path("event_id"), eventHash, "OUTBOX_ORIGINAL_EVENT", "ORDER");
      if (mq.isNull()) {
        missing("MQ_TARGET_EVIDENCE_MISSING", "MQ_EVIDENCE");
        quality.set("mq", object("status", "UNAVAILABLE"));
        return;
      }
      quality.set("mq", object("status", "OBSERVED", "role", "EXACT_TARGET_EVENT_ONLY"));
      same(mq.path("runId"), run, "MQ_RUN", "MQ_EVIDENCE");
      same(mq.path("scenarioId"), phase.path("scenarioId").asText(), "MQ_SCENARIO", "MQ_EVIDENCE");
      same(mq.at("/subject/payNo"), payNo, "MQ_PAY", "MQ_EVIDENCE");
      same(mq.at("/subject/orderNo"), orderNo, "MQ_ORDER", "MQ_EVIDENCE");
      fp(mq.at("/subject/eventIdSha256"), eventHash, "MQ_ORIGINAL_EVENT", "MQ_EVIDENCE");
      same(mq.path("phase"), "VERIFIED", "MQ_OBSERVATION_PHASE", "MQ_EVIDENCE");
      same(mq.path("virtualHost"), run, "MQ_VHOST", "MQ_EVIDENCE");
      same(mq.path("queue"), QUEUE, "MQ_QUEUE", "MQ_EVIDENCE");
      same(event.path("status"), "PUBLISHED", "OUTBOX_PUBLISH_STATE", "ORDER");
      if (!event.at("/payload/present").isBoolean()
          || !event.at("/payload/present").booleanValue()
          || fingerprint(event.at("/payload/sha256")) == null)
        missing("OUTBOX_PAYLOAD_DIGEST_MISSING", "ORDER");
      if (!base.isNull()) {
        JsonNode beforeRows = base.at("/sources/order/tables/order_outbox/rows");
        if (beforeRows.isArray()
            && beforeRows.size() == 1
            && !beforeRows.get(0).path("payload").equals(event.path("payload")))
          fail("OUTBOX_ORIGINAL_PAYLOAD_CHANGED", "ORDER");
      }
      JsonNode observations = mq.path("observations");
      String scenario = text(mq.path("scenarioId"));
      if ("MQ-R1".equals(scenario)) {
        JsonNode first = observations.path("returnedEvent"),
            retried = observations.path("originalRetry"),
            last = observations.path("finalOrder");
        trace(first, eventHash);
        trace(retried, eventHash);
        trace(last, eventHash);
        List<JsonNode> original = array(first.path("published"), "MQ_EVIDENCE"),
            published = array(last.path("published"), "MQ_EVIDENCE"),
            received = array(last.path("deliveries"), "MQ_EVIDENCE");
        if (original.size() != 1 || published.size() != 2 || received.size() != 1) {
          missing("MQ_R1_TRACE_CARDINALITY", "MQ_EVIDENCE");
          return;
        }
        JsonNode returned = original.get(0), retry = published.get(1);
        String body = requiredHash(returned.path("bodySha256"));
        publish(returned, eventHash, body);
        publish(retry, eventHash, body);
        proofBoolean(returned.path("returned"), true, "MQ_R1_MANDATORY_RETURN");
        proofInteger(returned.path("returnedReplyCode"), 312, "MQ_R1_RETURN_CODE");
        fp(returned.path("returnedBodySha256"), body, "MQ_RETURN_BODY", "MQ_EVIDENCE");
        same(returned.path("returnedExchange"), EXCHANGE, "MQ_RETURN_EXCHANGE", "MQ_EVIDENCE");
        same(returned.path("returnedRoutingKey"), "", "MQ_RETURN_ROUTING", "MQ_EVIDENCE");
        proofBoolean(retry.path("returned"), false, "MQ_R1_RETRY_RETURNED");
        if (!published.get(0).equals(returned)) fail("MQ_ORIGINAL_PUBLISH_CHANGED", "MQ_EVIDENCE");
        receive(received.get(0), eventHash, body, false, true);
        if (!first.path("processPid").equals(last.path("processPid"))
            || !retried.path("processPid").equals(last.path("processPid")))
          fail("MQ_R1_PUBLISHER_IDENTITY", "MQ_EVIDENCE");
        if (!retried.path("published").equals(last.path("published"))
            || !retried.path("deliveries").equals(last.path("deliveries")))
          missing("MQ_R1_RETRY_OBSERVATION_CHANGED", "MQ_EVIDENCE");
      } else if ("MQ-R2".equals(scenario)) {
        JsonNode old = observations.path("oldOrder"),
            fresh = observations.path("brokerRedelivery"),
            duplicate = observations.path("explicitDuplicate"),
            last = observations.path("finalOrder");
        trace(old, eventHash);
        trace(fresh, eventHash);
        trace(duplicate, eventHash);
        trace(last, eventHash);
        List<JsonNode> published = array(old.path("published"), "MQ_EVIDENCE"),
            oldReceipts = array(old.path("deliveries"), "MQ_EVIDENCE"),
            receipts = array(duplicate.path("deliveries"), "MQ_EVIDENCE"),
            freshReceipts = array(fresh.path("deliveries"), "MQ_EVIDENCE");
        if (published.size() != 1
            || oldReceipts.size() != 1
            || receipts.size() != 2
            || freshReceipts.size() != 1) {
          missing("MQ_R2_TRACE_CARDINALITY", "MQ_EVIDENCE");
          return;
        }
        String body = requiredHash(published.get(0).path("bodySha256"));
        publish(published.get(0), eventHash, body);
        proofBoolean(published.get(0).path("returned"), false, "MQ_R2_ORIGINAL_PUBLISH_RETURNED");
        receive(oldReceipts.get(0), eventHash, body, false, false);
        proofBoolean(old.path("blocked"), true, "MQ_R2_PRE_ACK_BARRIER");
        proofInteger(old.path("barrierCount"), 1, "MQ_R2_PRE_ACK_BARRIER_COUNT");
        proofBoolean(oldReceipts.get(0).path("blocked"), true, "MQ_R2_PRE_ACK_RECEIPT_BARRIER");
        if (old.path("processPid").equals(duplicate.path("processPid")))
          fail("MQ_R2_NEW_PROCESS_REQUIRED", "MQ_EVIDENCE");
        if (!array(fresh.path("published"), "MQ_EVIDENCE").isEmpty()
            || !array(duplicate.path("published"), "MQ_EVIDENCE").isEmpty()
            || !array(last.path("published"), "MQ_EVIDENCE").isEmpty())
          fail("MQ_R2_EXTRA_PUBLISH", "MQ_EVIDENCE");
        receive(freshReceipts.get(0), eventHash, body, true, true);
        if (!freshReceipts.get(0).equals(receipts.get(0))
            || !last.path("deliveries").equals(duplicate.path("deliveries")))
          missing("MQ_R2_OBSERVATION_CHAIN_CHANGED", "MQ_EVIDENCE");
        if (!fresh.path("processPid").equals(duplicate.path("processPid"))
            || !last.path("processPid").equals(duplicate.path("processPid")))
          fail("MQ_R2_REDELIVERY_PROCESS_IDENTITY", "MQ_EVIDENCE");
        receive(receipts.get(0), eventHash, body, true, true);
        receive(receipts.get(1), eventHash, body, false, true);
        JsonNode actions = mq.path("driverActions");
        boolean hardStop = false, explicitDuplicate = false;
        for (JsonNode action : array(actions, "MQ_EVIDENCE")) {
          String serializedType = action.path("kind").asText();
          if (serializedType.equals("HARD_STOP_AT_PRE_ACK_BARRIER"))
            hardStop |= action.path("pid").equals(old.path("processPid"));
          if (serializedType.equals("EXPLICIT_DUPLICATE_PUBLISH")) {
            explicitDuplicate = true;
            fp(action.path("bodySha256"), body, "MQ_DRIVER_DUPLICATE_BODY", "MQ_EVIDENCE");
            fp(action.path("eventIdSha256"), eventHash, "MQ_DRIVER_DUPLICATE_EVENT", "MQ_EVIDENCE");
            proofBoolean(action.path("confirmAck"), true, "MQ_DRIVER_DUPLICATE_CONFIRM_MISSING");
          }
        }
        if (!hardStop) missing("MQ_R2_HARD_STOP_PROOF", "MQ_EVIDENCE");
        if (!explicitDuplicate) missing("MQ_R2_EXPLICIT_DUPLICATE_PROOF", "MQ_EVIDENCE");
      } else missing("MQ_SCENARIO_UNSUPPORTED", "PM_ARCH");
      observation("BROKER_HISTORY_AND_PAYLOAD_WIRE_LIMIT", "SOURCE_LIMIT", "MQ_EVIDENCE", "/");
    }

    private void trace(JsonNode trace, String eventHash) throws Exception {
      if (!trace.isObject()) {
        missing("MQ_TRACE_MISSING", "MQ_EVIDENCE");
        return;
      }
      same(
          trace.path("schemaVersion"),
          "CM18_ORDER_TIMEOUT_PROCESS_TRACE_V1",
          "MQ_TRACE_SCHEMA",
          "MQ_EVIDENCE");
      same(trace.path("queue"), QUEUE, "MQ_TRACE_QUEUE", "MQ_EVIDENCE");
      same(trace.path("ackMode"), "AUTO", "MQ_AUTO_ACK", "MQ_EVIDENCE");
      same(
          trace.path("ackObservationScope"),
          "LOCAL_BASIC_ACK_RETURN_ONLY",
          "MQ_ACK_SCOPE",
          "MQ_EVIDENCE");
      fp(trace.path("targetEventIdSha256"), eventHash, "MQ_TRACE_EVENT", "MQ_EVIDENCE");
      fp(trace.path("targetOrderNoSha256"), hash(orderNo), "MQ_TRACE_ORDER", "MQ_EVIDENCE");
      proofBoolean(trace.path("ready"), true, "MQ_TRACE_NOT_READY");
      proofBoolean(trace.path("boundExceeded"), false, "MQ_TRACE_BOUND_EXCEEDED");
      proofInteger(trace.path("droppedEvents"), 0, "MQ_TRACE_DROPPED_EVENTS");
      proofInteger(trace.path("maxEvents"), ROW_LIMIT, "MQ_TRACE_BOUND_UNPROVEN");
      proofBoolean(trace.path("retryPreserved"), true, "MQ_RETRY_UNPROVEN");
      proofInteger(trace.path("retryMaxAttempts"), 3, "MQ_RETRY_ATTEMPTS_UNPROVEN");
      proofInteger(trace.path("prefetch"), 1, "MQ_PREFETCH_UNPROVEN");
      proofInteger(trace.path("concurrency"), 1, "MQ_CONCURRENCY_UNPROVEN");
      proofInteger(trace.path("adviceCount"), 2, "MQ_ADVICE_UNPROVEN");
      proofBoolean(trace.path("defaultRequeueRejected"), false, "MQ_REQUEUE_POLICY_UNPROVEN");
      proofBoolean(trace.path("publisherObserverInstalled"), true, "MQ_PUBLISH_OBSERVER_UNPROVEN");
      proofBoolean(trace.path("brokerAckConfirmed"), false, "MQ_ACK_SCOPE_UNPROVEN");
      boolean retryAdvice = false;
      for (JsonNode advice : array(trace.path("originalAdviceClasses"), "MQ_EVIDENCE"))
        retryAdvice |=
            "org.springframework.retry.interceptor.RetryOperationsInterceptor".equals(text(advice));
      if (!retryAdvice) missing("MQ_RETRY_ADVICE_MISSING", "MQ_EVIDENCE");
      if (trace.hasNonNull("failure")) missing("MQ_TRACE_FAILURE", "MQ_EVIDENCE");
      Long pid = exactNumber(trace.path("processPid"));
      if (pid == null || pid <= 0) missing("MQ_PROCESS_IDENTITY_MISSING", "MQ_EVIDENCE");
      for (JsonNode receipt : array(trace.path("deliveries"), "MQ_EVIDENCE")) {
        Long receivedPid = exactNumber(receipt.path("processPid"));
        if (receivedPid == null || receivedPid <= 0)
          missing("MQ_RECEIPT_PROCESS_MISSING", "MQ_EVIDENCE");
        else if (pid != null && !pid.equals(receivedPid))
          fail("MQ_RECEIPT_PROCESS_IDENTITY", "MQ_EVIDENCE");
      }
    }

    /** 缺少或错误类型的证明字段不能被 Jackson 默认值吞成成功。 */
    private void proofBoolean(JsonNode value, boolean expected, String code) {
      if (!value.isBoolean() || value.booleanValue() != expected) missing(code, "MQ_EVIDENCE");
    }

    private void proofInteger(JsonNode value, long expected, String code) {
      Long number = exactNumber(value);
      if (number == null || number != expected) missing(code, "MQ_EVIDENCE");
    }

    private void publish(JsonNode publish, String eventHash, String body) throws Exception {
      fp(publish.path("eventIdSha256"), eventHash, "MQ_PUBLISH_EVENT", "MQ_EVIDENCE");
      fp(publish.path("correlationSha256"), eventHash, "MQ_CORRELATION", "MQ_EVIDENCE");
      fp(publish.path("orderNoSha256"), hash(orderNo), "MQ_PUBLISH_ORDER", "MQ_EVIDENCE");
      fp(publish.path("bodySha256"), body, "MQ_PUBLISH_BODY", "MQ_EVIDENCE");
      same(publish.path("exchange"), EXCHANGE, "MQ_EXCHANGE", "MQ_EVIDENCE");
      same(publish.path("routingKey"), "", "MQ_ROUTING", "MQ_EVIDENCE");
      proofBoolean(publish.path("mandatory"), true, "MQ_MANDATORY_UNPROVEN");
      proofBoolean(publish.path("confirmObserved"), true, "MQ_PUBLISH_CONFIRM_MISSING");
      proofBoolean(publish.path("confirmAck"), true, "MQ_PUBLISH_CONFIRM_ACK_MISSING");
    }

    private void receive(
        JsonNode receive, String eventHash, String body, boolean redelivery, boolean ack)
        throws Exception {
      fp(receive.path("eventIdSha256"), eventHash, "MQ_RECEIPT_EVENT", "MQ_EVIDENCE");
      fp(receive.path("orderNoSha256"), hash(orderNo), "MQ_RECEIPT_ORDER", "MQ_EVIDENCE");
      fp(receive.path("bodySha256"), body, "MQ_RECEIPT_BODY", "MQ_EVIDENCE");
      same(receive.path("queue"), QUEUE, "MQ_RECEIPT_QUEUE", "MQ_EVIDENCE");
      same(receive.path("exchange"), EXCHANGE, "MQ_RECEIPT_EXCHANGE", "MQ_EVIDENCE");
      same(receive.path("routingKey"), "", "MQ_RECEIPT_ROUTING", "MQ_EVIDENCE");
      if (!receive.path("redelivered").isBoolean())
        missing("MQ_REDELIVERY_FLAG_MISSING", "MQ_EVIDENCE");
      else if (receive.path("redelivered").booleanValue() != redelivery)
        fail("MQ_REDELIVERY_DIRECTION", "MQ_EVIDENCE");
      proofBoolean(receive.path("transactionReturned"), true, "MQ_TRANSACTION_RETURN_MISSING");
      proofBoolean(
          receive.path("transactionActiveAfter"), false, "MQ_TRANSACTION_COMPLETION_MISSING");
      proofBoolean(
          receive.path("synchronizationActiveAfter"),
          false,
          "MQ_SYNCHRONIZATION_COMPLETION_MISSING");
      if (!receive.path("ackObserved").isBoolean()
          || receive.path("ackObserved").booleanValue() != ack)
        missing("MQ_LOCAL_ACK_OBSERVATION_MISSING", "MQ_EVIDENCE");
      Instant returned = instant(receive.path("transactionReturnedAt")),
          captured = instant(phase.path("captureEndedAt"));
      if (returned == null || captured == null || returned.isAfter(captured))
        missing("MQ_EVIDENCE_AFTER_SELECTED_PHASE", "MQ_EVIDENCE");
      if (ack) {
        proofBoolean(receive.path("ackAttemptObserved"), true, "MQ_ACK_ATTEMPT_MISSING");
        Instant acknowledged = instant(receive.path("ackObservedAt"));
        if (acknowledged == null
            || returned == null
            || acknowledged.isBefore(returned)
            || captured == null
            || acknowledged.isAfter(captured)) missing("MQ_ACK_TIME_MISSING", "MQ_EVIDENCE");
        Long transactionSequence = exactNumber(receive.path("transactionReturnSequence")),
            ackSequence = exactNumber(receive.path("ackSequence"));
        if (transactionSequence == null
            || ackSequence == null
            || transactionSequence < 0
            || ackSequence < 0
            || transactionSequence >= ackSequence)
          missing("MQ_ACK_SEQUENCE_MISSING", "MQ_EVIDENCE");
      }
    }

    private List<JsonNode> array(JsonNode value, String owner) {
      if (!value.isArray() || value.size() > ROW_LIMIT) {
        missing("OBSERVATION_ARRAY_INCOMPLETE", owner);
        return List.of();
      }
      List<JsonNode> result = new ArrayList<>();
      value.forEach(result::add);
      return result;
    }
  }

  /** 规则函数允许固定域异常传播到安全输入拒绝，业务缺证由上下文显式记录。 */
  @FunctionalInterface
  private interface Work {
    /** 执行一个固定只读关系规则；输入错误由调用方安全归档。 */
    void run() throws Exception;
  }

  private static Long exactNumber(JsonNode value) {
    return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
  }

  private static BigDecimal decimal(JsonNode value) {
    String text = text(value);
    if (text == null || !text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) return null;
    try {
      BigDecimal result = new BigDecimal(text);
      return result.precision() - result.scale() <= 16 && result.stripTrailingZeros().scale() <= 2
          ? result
          : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static Instant instant(JsonNode value) {
    try {
      return Instant.parse(requiredText(value));
    } catch (Exception invalid) {
      return null;
    }
  }
}
