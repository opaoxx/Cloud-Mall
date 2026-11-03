package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 人工规则样本只证明分类器行为；不冒充真实 DB、TC、Rabbit 或七态真实覆盖。 */
class PayTccOfflineClassificationTest {
  /** 现有 Jackson 的独立测试输入构造器。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** TC 原 writer 按 Map key 排序，数组保留原事件顺序。 */
  private static final ObjectMapper TC_JSON =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  /** 与阶段 helper 同一原引用契约的固定人工身份，原值不得出现在输出中。 */
  private static final String PAY = "synthetic-pay",
      ORDER = "202610-synthetic-order",
      RUN = "cm16c_" + "a".repeat(32),
      START = "2026-10-16T08:00:00+08:00",
      END = "2026-10-16T08:00:10+08:00";

  /** 所有输入和输出均为 JUnit 自有临时文件。 */
  @TempDir Path temporary;

  @Test
  void sevenStatesAndMoneyVersusSettlementRemainSeparate() throws Exception {
    assertResult(fixture("LEGACY"), "LEGACY_CONFIRMED", "LEGACY_CONFIRMED", "DELIVERED", true);
    assertResult(fixture("COMMITTED"), "TCC_COMMITTED", "COMMITTED", "DELIVERED", true);
    assertResult(fixture("RUNNING"), "TCC_TRYING", "TRYING", "PENDING", false);
    assertResult(fixture("ROLLEDBACK"), "TCC_CANCELLED", "CANCELLED", "CANCELLED", true);
    assertResult(fixture("BEGIN_UNKNOWN"), "BEGIN_UNKNOWN", "UNKNOWN", "UNKNOWN", false);
    Fixture missing = fixture("COMMITTED");
    source(missing.phase, "user").put("status", "UNAVAILABLE");
    assertResult(missing, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    Fixture conflict = fixture("COMMITTED");
    row(conflict.phase, "user", "balance_debit_attempt").put("user_id", 8);
    source(conflict.phase, "stock").put("status", "UNAVAILABLE");
    assertResult(conflict, "PROTOCOL_CONFLICT", "CONFLICT", "UNKNOWN", false);
    Fixture pending = fixture("COMMITTED");
    pending(pending.phase);
    assertResult(pending, "EVIDENCE_INCOMPLETE", "COMMITTED", "PENDING", false);
  }

  @Test
  void originalCommitRefundUsesDifferentDomainKeysAndDoesNotBecomeRollback() throws Exception {
    Fixture fixture = fixture("COMMITTED");
    refunded(fixture.phase);
    fixture.mq = r1();
    JsonNode result = result(fixture);
    assertEquals("TCC_COMMITTED", result.path("classification").asText(), result.toPrettyString());
    assertEquals("COMMITTED", result.path("moneyOutcome").asText());
    assertEquals("REFUNDED", result.path("deliveryOutcome").asText());
    assertFalse(result.path("accountTotalCausalityProven").booleanValue());
    assertEquals("EVIDENCE_INCOMPLETE", result.path("inheritedPhaseClassification").asText());
    assertTrue(result.toString().contains("COMMIT_BRANCH_ACK_UNOBSERVED"));
    Fixture missingMq = fixture("COMMITTED");
    refunded(missingMq.phase);
    assertResult(missingMq, "EVIDENCE_INCOMPLETE", "COMMITTED", "REFUNDED", false);
  }

  @Test
  void sourceCollectionAndFieldAbsenceNeverTurnIntoPositiveOrInventedConflicts() throws Exception {
    Fixture absentPay = fixture("COMMITTED");
    row(absentPay.phase, "pay", "pay_tcc_receipt").remove("pay_no");
    assertEquals("EVIDENCE_INCOMPLETE", result(absentPay).path("classification").asText());
    Fixture absentSku = fixture("COMMITTED");
    table(absentSku.phase, "stock", "stock_sku", List.of());
    JsonNode sku = result(absentSku);
    assertEquals("EVIDENCE_INCOMPLETE", sku.path("classification").asText());
    assertEquals("COMMITTED", sku.path("moneyOutcome").asText());
    Fixture unavailable = fixture("COMMITTED");
    source(unavailable.phase, "order").put("status", "UNAVAILABLE");
    table(unavailable.phase, "order", "mall_order_202610", List.of());
    table(unavailable.phase, "order", "mall_order_item_202610", List.of());
    assertEquals("EVIDENCE_INCOMPLETE", result(unavailable).path("classification").asText());
    Fixture unavailableOrder = fixture("LEGACY");
    source(unavailableOrder.phase, "order").put("status", "UNAVAILABLE");
    ((ObjectNode) source(unavailableOrder.phase, "order").path("tables")).removeAll();
    Fixture absentOutbox = fixture("LEGACY");
    ((ObjectNode) source(absentOutbox.phase, "order").path("tables")).remove("order_outbox");
    Fixture incompleteOutbox = fixture("LEGACY");
    ((ObjectNode) source(incompleteOutbox.phase, "order").at("/tables/order_outbox"))
        .put("status", "INCOMPLETE");
    Fixture boundedOutbox = fixture("LEGACY");
    ((ObjectNode) source(boundedOutbox.phase, "order").at("/tables/order_outbox"))
        .put("rowCount", 101);
    Fixture inconsistentEmptyOutbox = fixture("LEGACY");
    ObjectNode inconsistentTable =
        (ObjectNode) source(inconsistentEmptyOutbox.phase, "order").at("/tables/order_outbox");
    inconsistentTable.put("rowCount", 1);
    ((ArrayNode) inconsistentTable.path("rows")).add(JSON.createObjectNode());
    Fixture unverifiedOrder = fixture("LEGACY");
    source(unverifiedOrder.phase, "order").put("connectionOwnership", "UNAVAILABLE_NOT_VERIFIED");
    for (Fixture outboxMissing :
        List.of(
            unavailableOrder,
            absentOutbox,
            incompleteOutbox,
            boundedOutbox,
            inconsistentEmptyOutbox,
            unverifiedOrder)) {
      JsonNode missingMqSource = result(outboxMissing);
      assertEquals("EVIDENCE_INCOMPLETE", missingMqSource.path("classification").textValue());
      boolean dedicatedGap = false, mqRuleObserved = false;
      for (JsonNode reason : missingMqSource.path("blockingReasons"))
        if ("MQ_OUTBOX_SOURCE_UNPROVEN".equals(reason.path("code").textValue())) {
          assertEquals("ORDER", reason.path("owner").textValue());
          assertEquals("MISSING", reason.path("status").textValue());
          dedicatedGap = true;
        }
      assertTrue(dedicatedGap, "MQ_OUTBOX_SOURCE_UNPROVEN_MUST_BE_REPORTED");
      assertEquals("INCOMPLETE", missingMqSource.at("/sourceQuality/mq/status").textValue());
      for (JsonNode rule : missingMqSource.path("rules")) {
        assertNotEquals("MQ_NOT_PARTICIPATING_IN_THIS_PATH", rule.path("id").textValue());
        assertNotEquals("NO_TARGET_EVENT_YET", rule.path("id").textValue());
        if ("MQ_TARGET_EVENT_CAUSALITY".equals(rule.path("id").textValue())) {
          assertEquals("MISSING", rule.path("status").textValue());
          mqRuleObserved = true;
        }
        for (JsonNode proof : rule.path("proofRefs")) {
          JsonNode document = outboxMissing.archived.get(proof.path("sha256").textValue());
          assertNotNull(document);
          assertFalse(
              document.at(proof.path("jsonPointer").textValue()).isMissingNode(), proof.toString());
        }
      }
      assertTrue(mqRuleObserved);
    }
    Fixture observedEmptyOutbox = fixture("LEGACY");
    assertFalse(source(observedEmptyOutbox.phase, "order").has("connectionOwnership"));
    JsonNode noTargetEvent = result(observedEmptyOutbox);
    assertEquals("LEGACY_CONFIRMED", noTargetEvent.path("classification").textValue());
    assertEquals("NOT_APPLICABLE", noTargetEvent.at("/sourceQuality/mq/status").textValue());
    boolean emptyOutboxRulePassed = false;
    for (JsonNode rule : noTargetEvent.path("rules"))
      if ("MQ_TARGET_EVENT_CAUSALITY".equals(rule.path("id").textValue())) {
        assertEquals("PASS", rule.path("status").textValue());
        emptyOutboxRulePassed = true;
      }
    assertTrue(emptyOutboxRulePassed);
    Fixture sentinel = fixture("COMMITTED");
    ((ObjectNode) source(sentinel.phase, "user").path("tables").path("balance_ledger"))
        .put("rowCount", 101)
        .put("status", "INCOMPLETE");
    assertEquals("EVIDENCE_INCOMPLETE", result(sentinel).path("classification").asText());
    Fixture missingFlow = fixture("COMMITTED");
    table(missingFlow.phase, "stock", "stock_flow", List.of(flow("RESERVE")));
    JsonNode missingTerminal = result(missingFlow);
    assertEquals("EVIDENCE_INCOMPLETE", missingTerminal.path("classification").asText());
    assertEquals("COMMITTED", missingTerminal.path("moneyOutcome").asText());
    Fixture anchor = fixture("COMMITTED");
    source(anchor.phase, "pay").remove(List.of("anchorBeforeDigest", "anchorAfterDigest"));
    assertEquals("EVIDENCE_INCOMPLETE", result(anchor).path("classification").asText());
    Fixture nativeLine = fixture("COMMITTED");
    ((ObjectNode) nativeLine.tc.path("events").get(0)).remove("lineSha256");
    assertEquals("EVIDENCE_INCOMPLETE", result(nativeLine).path("classification").asText());
    Fixture unavailableTc = fixture("COMMITTED");
    ((ObjectNode) unavailableTc.tc.path("sourceRead")).put("status", "SOURCE_UNAVAILABLE");
    unavailableTc.tc.put("globalOutcome", "UNOBSERVED");
    ((ArrayNode) unavailableTc.tc.path("events")).removeAll();
    assertResult(unavailableTc, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    Fixture missingFence = fixture("COMMITTED");
    ((ObjectNode) source(missingFence.phase, "pay").at("/fence/tables/tcc_fence_log/rows/0"))
        .remove("status");
    assertResult(missingFence, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    Fixture missingTcBranch = fixture("COMMITTED");
    ((ObjectNode) missingTcBranch.tc.path("events").get(0)).remove("branchSha256");
    assertResult(missingTcBranch, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    Fixture changedTc = fixture("COMMITTED");
    ((ObjectNode) changedTc.tc.at("/sourceRead/endAnchor"))
        .put("daemonIdentitySha256", hash("different-daemon"));
    assertResult(changedTc, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    Fixture trace = fixture("COMMITTED");
    refunded(trace.phase);
    trace.mq = r1();
    ((ObjectNode) trace.mq.at("/observations/finalOrder/deliveries/0"))
        .remove("synchronizationActiveAfter");
    JsonNode incompleteTrace = result(trace);
    assertEquals("EVIDENCE_INCOMPLETE", incompleteTrace.path("classification").asText());
    assertEquals("COMMITTED", incompleteTrace.path("moneyOutcome").asText());

    Fixture unavailableLegacy = fixture("LEGACY");
    source(unavailableLegacy.phase, "user").put("status", "UNAVAILABLE");
    ((ObjectNode) source(unavailableLegacy.phase, "user").path("tables")).removeAll();
    unavailableLegacy
        .phase
        .putObject("verifiedAssertions")
        .put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION")
        .put("notForReport", "DO_NOT_COPY_ASSERTION_OBJECT");
    JsonNode legacyMissing = result(unavailableLegacy);
    assertEquals(
        "EVIDENCE_INCOMPLETE",
        legacyMissing.path("classification").textValue(),
        legacyMissing.toPrettyString());
    assertEquals("UNKNOWN", legacyMissing.path("moneyOutcome").textValue());
    assertEquals("UNKNOWN", legacyMissing.path("deliveryOutcome").textValue());
    assertFalse(legacyMissing.toString().contains("LEGACY_TCC_IDENTITY"));
    assertEquals(
        "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION", legacyMissing.path("fixtureRole").textValue());
    assertEquals(
        "FIXTURE_AUTHOR_DECLARATION_NOT_FUNDS_FACT",
        legacyMissing.path("fixtureRoleDeclaration").textValue());
    assertFalse(legacyMissing.toString().contains("DO_NOT_COPY_ASSERTION_OBJECT"));
    assertTrue(legacyMissing.path("fixtureRoleProofRefs").isArray());
    assertFalse(legacyMissing.path("fixtureRoleProofRefs").isEmpty());
    for (JsonNode proof : legacyMissing.path("fixtureRoleProofRefs")) {
      assertEquals(unavailableLegacy.phaseHash, proof.path("sha256").textValue());
      assertEquals("/verifiedAssertions/fixtureRole", proof.path("jsonPointer").textValue());
      assertEquals(
          "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION",
          unavailableLegacy
              .archived
              .get(unavailableLegacy.phaseHash)
              .at(proof.path("jsonPointer").textValue())
              .textValue());
    }
    Fixture noAttempt = fixture("LEGACY");
    table(noAttempt.phase, "user", "balance_debit_attempt", List.of());
    assertResult(noAttempt, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
    for (String field : List.of("xid", "branch_id")) {
      Fixture missingIdentity = fixture("LEGACY");
      row(missingIdentity.phase, "user", "balance_debit_attempt").remove(field);
      assertResult(missingIdentity, "EVIDENCE_INCOMPLETE", "UNKNOWN", "UNKNOWN", false);
      Fixture explicitTccIdentity = fixture("LEGACY");
      ObjectNode attempt = row(explicitTccIdentity.phase, "user", "balance_debit_attempt");
      attempt.remove(field.equals("xid") ? "branch_id" : "xid");
      attempt.put(field, fp("known-tcc-identity"));
      assertEquals(
          "PROTOCOL_CONFLICT", result(explicitTccIdentity).path("classification").textValue());
    }
    Fixture explicitNull = fixture("LEGACY");
    assertResult(explicitNull, "LEGACY_CONFIRMED", "LEGACY_CONFIRMED", "DELIVERED", true);
    Fixture baseOnlyRole = fixture("LEGACY");
    baseOnlyRole.base = fixture("LEGACY").phase;
    baseOnlyRole
        .base
        .putObject("verifiedAssertions")
        .put("fixtureRole", "COLLECTOR_ONLY_NO_BUSINESS_TRANSACTION");
    assertFalse(result(baseOnlyRole).has("fixtureRole"));
    Fixture unknownRole = fixture("LEGACY");
    unknownRole.phase.putObject("verifiedAssertions").put("fixtureRole", "UNRECOGNIZED_ROLE");
    assertFalse(result(unknownRole).has("fixtureRole"));
  }

  @Test
  void reliableKeyBranchStockAndMqContradictionsHavePriority() throws Exception {
    Fixture key = fixture("COMMITTED");
    row(key.phase, "user", "balance_ledger").put("operation_key", fp("wrong-original-key"));
    assertEquals("PROTOCOL_CONFLICT", result(key).path("classification").asText());
    Fixture branch = fixture("COMMITTED");
    row(branch.phase, "user", "user_tcc_receive").put("branch_id", fp("wrong-branch"));
    assertEquals("PROTOCOL_CONFLICT", result(branch).path("classification").asText());
    Fixture duplicate = fixture("COMMITTED");
    JsonNode sku = row(duplicate.phase, "stock", "stock_sku");
    table(duplicate.phase, "stock", "stock_sku", List.of(sku, sku.deepCopy()));
    assertEquals("PROTOCOL_CONFLICT", result(duplicate).path("classification").asText());
    Fixture trying = fixture("RUNNING");
    JsonNode reserve = row(trying.phase, "stock", "stock_flow");
    ObjectNode terminal = reserve.deepCopy();
    terminal.put("flow_type", "CONFIRM").put("idempotency_key", fp(ORDER + ":CONFIRM:22"));
    table(trying.phase, "stock", "stock_flow", List.of(reserve, terminal));
    assertEquals("PROTOCOL_CONFLICT", result(trying).path("classification").asText());
    Fixture wire = fixture("COMMITTED");
    refunded(wire.phase);
    wire.mq = r1();
    ((ObjectNode) wire.mq.at("/observations/finalOrder/deliveries/0"))
        .put("bodySha256", hash("wrong-wire"));
    assertEquals("PROTOCOL_CONFLICT", result(wire).path("classification").asText());
  }

  @Test
  void unsupportedLegalLegacyRefundIsIncompleteAndAppendCannotReplaceOriginalDebitId()
      throws Exception {
    Fixture legacy = fixture("LEGACY");
    refunded(legacy.phase);
    row(legacy.phase, "pay", "pay_debit_intent").put("status", "REFUNDED");
    table(legacy.phase, "order", "order_outbox", List.of());
    assertEquals("EVIDENCE_INCOMPLETE", result(legacy).path("classification").asText());
    Fixture base = fixture("COMMITTED");
    Fixture next = fixture("COMMITTED");
    next.base = base.phase.deepCopy();
    refunded(next.phase);
    next.mq = r1();
    ArrayNode rows = (ArrayNode) source(next.phase, "user").at("/tables/balance_ledger/rows");
    ((ObjectNode) rows.get(0)).put("id", 999);
    assertEquals("PROTOCOL_CONFLICT", result(next).path("classification").asText());
  }

  @Test
  void safeOutputStableDigestAndExactReferencesDoNotUpgradeSyntheticCoverage() throws Exception {
    Fixture fixture = fixture("LEGACY");
    row(fixture.phase, "user", "mall_user").put("balance", "80.00");
    Path input = writeInput(fixture);
    byte[] original = Files.readAllBytes(input);
    Path first = PayTccOfflineClassification.generate(input, temporary.resolve("reports"));
    Path second = PayTccOfflineClassification.generate(input, temporary.resolve("reports"));
    JsonNode one = JSON.readTree(Files.readAllBytes(first)),
        two = JSON.readTree(Files.readAllBytes(second));
    assertNotEquals(first, second);
    assertArrayEquals(original, Files.readAllBytes(input));
    assertEquals(one.path("semanticDigest"), two.path("semanticDigest"));
    assertEquals(0, one.at("/coverage/realArchivedPhaseSamples").intValue());
    assertEquals(1, one.at("/coverage/syntheticRuleSamples").intValue());
    assertEquals(7, one.at("/coverage/liveNotCovered").size());
    String output = one.toString();
    assertFalse(output.contains(PAY));
    assertFalse(output.contains(ORDER));
    assertFalse(output.contains("raw-synthetic-xid"));
    assertFalse(output.contains("root:root"));
    assertFalse(one.path("wholeCm18Complete").booleanValue());
    assertFalse(one.path("m1Complete").booleanValue());
    assertEquals("SUCCESS_MINIMUM_30_DAYS", one.at("/samples/0/retention/policy").textValue());
    assertEquals(30, one.at("/samples/0/retention/minimumDays").intValue());
    assertFalse(one.at("/samples/0/retention/automaticDeletionAllowed").booleanValue());
    for (JsonNode rule : one.at("/samples/0/rules"))
      for (JsonNode proof : rule.path("proofRefs")) {
        JsonNode document = fixture.archived.get(proof.path("sha256").asText());
        assertNotNull(document);
        assertFalse(
            document.at(proof.path("jsonPointer").asText()).isMissingNode(), proof.toString());
      }
  }

  @Test
  void integrityUnknownSchemaAndCanonicalCorruptionRejectWithoutProtocolConclusions()
      throws Exception {
    Fixture damaged = fixture("LEGACY");
    Path input = writeInput(damaged);
    Files.writeString(damaged.root.resolve(damaged.phaseHash), "{}");
    assertRejected(input, "INTEGRITY_MISMATCH");
    Fixture digest = fixture("LEGACY");
    digest.badDigest = true;
    assertRejected(writeInput(digest), "SEMANTIC_DIGEST_MISMATCH");
    Fixture unsupported = fixture("LEGACY");
    unsupported.phase.put("schemaVersion", "UNSUPPORTED");
    assertRejected(writeInput(unsupported), "UNSUPPORTED_PROFILE");
    Fixture missing = fixture("LEGACY");
    Path missingInput = writeInput(missing);
    Files.delete(missing.root.resolve(missing.phaseHash));
    assertRejected(missingInput, "INPUT_MISSING");
  }

  @Test
  void boundedParserDuplicateKeysUtf8TraversalAndArchiveCountAreSafeRejections() throws Exception {
    for (byte[] malformed :
        List.of(
            "{\"samples\":[],\"samples\":[]}".getBytes(StandardCharsets.UTF_8),
            new byte[] {(byte) 0xff},
            ("[".repeat(65) + "0" + "]".repeat(65)).getBytes(StandardCharsets.UTF_8),
            "{} {}".getBytes(StandardCharsets.UTF_8))) {
      Path input = temporary.resolve(UUID.randomUUID() + ".json");
      Files.write(input, malformed);
      JsonNode report =
          JSON.readTree(
              Files.readAllBytes(
                  PayTccOfflineClassification.generate(input, temporary.resolve("rejects"))));
      assertTrue(report.has("inputRejection"));
      assertFalse(report.path("reportGenerationComplete").booleanValue());
    }
    Fixture traversal = fixture("LEGACY");
    Path input = writeInput(traversal);
    ArrayNode index = (ArrayNode) JSON.readTree(Files.readAllBytes(traversal.index));
    ((ObjectNode) index.get(0)).put("archivedRelativePath", "../outside");
    Files.write(traversal.index, JSON.writeValueAsBytes(index));
    updateIndexHash(input, traversal.index);
    assertRejected(input, "PATH_OUTSIDE_ARCHIVE");
    Fixture large = fixture("LEGACY");
    Path largeInput = writeInput(large);
    ArrayNode many = JSON.createArrayNode();
    for (int item = 0; item < 513; item++)
      many.add(
          JSON.createObjectNode()
              .put("original", "logical/" + item)
              .put("archivedRelativePath", "unread-" + item)
              .put("bytes", 0)
              .put("sha256", hash("entry-" + item)));
    Files.write(large.index, JSON.writeValueAsBytes(many));
    updateIndexHash(largeInput, large.index);
    assertRejected(largeInput, "BOUND_EXCEEDED");
  }

  private void assertRejected(Path input, String reason) throws Exception {
    JsonNode report =
        JSON.readTree(
            Files.readAllBytes(
                PayTccOfflineClassification.generate(input, temporary.resolve("rejected"))));
    assertTrue(report.toString().contains(reason), report.toPrettyString());
    for (JsonNode sample : report.path("samples")) {
      assertEquals("EVIDENCE_INCOMPLETE", sample.path("classification").asText());
      assertEquals("UNKNOWN", sample.path("moneyOutcome").asText());
      assertEquals("REJECTED", sample.path("inputAcceptance").asText());
      assertEquals("NO_AUTOMATIC_EXPIRY", sample.at("/retention/policy").textValue());
      assertFalse(sample.at("/retention/automaticDeletionAllowed").booleanValue());
    }
  }

  private void assertResult(
      Fixture fixture, String classification, String money, String delivery, boolean complete)
      throws Exception {
    JsonNode result = result(fixture);
    assertEquals(classification, result.path("classification").asText(), result.toPrettyString());
    assertEquals(money, result.path("moneyOutcome").asText(), result.toPrettyString());
    assertEquals(delivery, result.path("deliveryOutcome").asText(), result.toPrettyString());
    assertEquals(complete, result.path("settlementComplete").booleanValue());
    assertEquals(
        complete ? "SUCCESS_MINIMUM_30_DAYS" : "NO_AUTOMATIC_EXPIRY",
        result.at("/retention/policy").textValue());
  }

  private JsonNode result(Fixture fixture) throws Exception {
    Path input = writeInput(fixture);
    return JSON.readTree(
            Files.readAllBytes(
                PayTccOfflineClassification.generate(input, temporary.resolve("reports"))))
        .at("/samples/0");
  }

  private void updateIndexHash(Path input, Path index) throws Exception {
    ObjectNode document = (ObjectNode) JSON.readTree(Files.readAllBytes(input));
    ((ObjectNode) document.path("archiveManifest")).put("sha256", hash(Files.readAllBytes(index)));
    Files.write(input, JSON.writeValueAsBytes(document));
  }

  /** 单测文件归档，original 是虚构逻辑标识，无法退回读取真实机器路径。 */
  private static final class Fixture {
    /** 人工阶段/TC/MQ/base 文档与本轮写入位置。 */
    private ObjectNode phase, tc, mq, base;

    /** 此用例临时归档根目录及其固定索引。 */
    private Path root, index;

    /** 当前选中阶段的内容 SHA。 */
    private String phaseHash;

    /** 构造只用于验证 canonical 损坏拒绝的坏摘要。 */
    private boolean badDigest;

    /** 按内容寻址的独立文档，供 proof pointer 外部验证。 */
    private final Map<String, JsonNode> archived = new LinkedHashMap<>();
  }

  private Path writeInput(Fixture fixture) throws Exception {
    fixture.root = temporary.resolve(UUID.randomUUID().toString());
    Files.createDirectories(fixture.root);
    fixture.index = fixture.root.resolve("index.json");
    ArrayNode manifest = JSON.createArrayNode();
    ObjectNode fixtureDocument = JSON.createObjectNode().put("group", RUN);
    fixtureDocument.putArray("databases").add(RUN + "_pay");
    String fixtureHash = store(fixture, manifest, "fixture.json", fixtureDocument);
    fixture.phase.set(
        "artifactManifest",
        JSON.createArrayNode().add(reference(fixture, "fixture.json", fixtureHash)));
    if (fixture.base != null) sealPhase(fixture.base);
    sealPhase(fixture.phase);
    if (fixture.badDigest) fixture.phase.put("factsDigest", "0".repeat(64));
    fixture.phaseHash = store(fixture, manifest, "phase.json", fixture.phase);
    ObjectNode sample =
        JSON.createObjectNode()
            .put("id", "RULE_SAMPLE")
            .put("evidenceKind", "SYNTHETIC_RULE_ONLY")
            .put("phaseSha256", fixture.phaseHash);
    if (fixture.tc != null) {
      ObjectNode anchor = (ObjectNode) fixture.tc.path("sourceStartAnchor");
      String anchorHash = store(fixture, manifest, "anchor.json", anchor);
      fixture.tc.set("anchorArtifactManifest", reference(fixture, "anchor.json", anchorHash));
      sealTc(fixture.tc);
      String attemptHash = store(fixture, manifest, "attempt.json", fixture.tc);
      ObjectNode finalized = fixture.tc.deepCopy();
      finalized
          .put("attemptCount", 1)
          .set(
              "attemptArtifacts",
              JSON.createArrayNode().add(reference(fixture, "attempt.json", attemptHash)));
      String tcHash = store(fixture, manifest, "tc-final.json", finalized);
      sample.put("tcSha256", tcHash);
    }
    if (fixture.mq != null) sample.put("mqSha256", store(fixture, manifest, "mq.json", fixture.mq));
    if (fixture.base != null)
      sample.put("basePhaseSha256", store(fixture, manifest, "base.json", fixture.base));
    Files.write(fixture.index, JSON.writeValueAsBytes(manifest));
    ObjectNode input =
        JSON.createObjectNode().put("schemaVersion", "CM18_OFFLINE_CLASSIFICATION_INPUT_V1");
    input.set(
        "archiveManifest",
        JSON.createObjectNode()
            .put("path", fixture.index.toString())
            .put("sha256", hash(Files.readAllBytes(fixture.index))));
    input.set("samples", JSON.createArrayNode().add(sample));
    Path file = fixture.root.resolve("input.json");
    Files.write(file, JSON.writeValueAsBytes(input));
    return file;
  }

  private String store(Fixture fixture, ArrayNode manifest, String logicalName, JsonNode value)
      throws Exception {
    byte[] bytes = JSON.writeValueAsBytes(value);
    String sha = hash(bytes);
    Path path = fixture.root.resolve(sha);
    if (!Files.exists(path)) Files.write(path, bytes);
    fixture.archived.put(sha, value.deepCopy());
    manifest.add(
        JSON.createObjectNode()
            .put("original", "logical/source/" + logicalName)
            .put("archivedRelativePath", sha)
            .put("bytes", bytes.length)
            .put("sha256", sha));
    return sha;
  }

  private ObjectNode reference(Fixture fixture, String name, String sha) throws Exception {
    return JSON.createObjectNode()
        .put("relativePath", name)
        .put("sha256", sha)
        .put("bytes", JSON.writeValueAsBytes(fixture.archived.get(sha)).length);
  }

  private void sealPhase(ObjectNode phase) throws Exception {
    phase.put("factsDigest", hash(JSON.writeValueAsBytes(phaseWriterFacts(phase.path("sources")))));
  }

  /** 人工输入的原 writer：独立构造冻结格式，不初始化任何 live collector 或复用被测 reader。 */
  private JsonNode phaseWriterFacts(JsonNode value) {
    if (value.isObject()) {
      ObjectNode result = JSON.createObjectNode();
      List<String> fields = new ArrayList<>();
      value.fieldNames().forEachRemaining(fields::add);
      java.util.Collections.sort(fields);
      for (String field : fields)
        if (!Set.of(
                "captureStartedAt",
                "captureEndedAt",
                "observedAt",
                "tc",
                "mq",
                "anchorAfter",
                "anchorBeforeDigest",
                "anchorAfterDigest",
                "sourceChanged")
            .contains(field)) result.set(field, phaseWriterFacts(value.path(field)));
      return result;
    }
    if (value.isArray()) {
      List<JsonNode> entries = new ArrayList<>();
      value.forEach(item -> entries.add(phaseWriterFacts(item)));
      entries.sort(java.util.Comparator.comparing(JsonNode::toString));
      ArrayNode result = JSON.createArrayNode();
      entries.forEach(result::add);
      return result;
    }
    return value;
  }

  private void sealTc(ObjectNode tc) throws Exception {
    List<String> fields =
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
    Map<String, Object> facts = new LinkedHashMap<>();
    for (String field : fields) facts.put(field, JSON.convertValue(tc.path(field), Object.class));
    tc.set("factsDigestFields", JSON.valueToTree(fields));
    tc.put("factsDigest", hash(TC_JSON.writeValueAsBytes(facts)));
  }

  private Fixture fixture(String state) throws Exception {
    Fixture fixture = new Fixture();
    boolean legacy = state.equals("LEGACY"),
        committed = state.equals("COMMITTED"),
        cancelled = state.equals("ROLLEDBACK"),
        trying = !legacy && !committed && !cancelled;
    String mode = legacy ? "LEGACY" : "TCC",
        funds = committed ? "CONFIRMED" : cancelled ? "CANCELLED" : "TRYING";
    ObjectNode phase = JSON.createObjectNode();
    fixture.phase = phase;
    phase
        .put("schemaVersion", "CM18_PHASE_EVIDENCE_V1")
        .put("scope", "ISOLATED_TEST_PHASE_ONLY")
        .put("automaticActionsAllowed", false)
        .put("runId", RUN)
        .put("scenarioId", "MQ-R1")
        .put("phase", "SYNTHETIC_RULE_ONLY")
        .put("captureStartedAt", START)
        .put("captureEndedAt", END)
        .put("closureClassification", "EVIDENCE_INCOMPLETE");
    phase
        .putArray("missingEvidence")
        .add("TC_BRANCH_NOT_OBSERVED:TC")
        .add("MQ_EVENT_CAUSALITY_UNPROVEN:MQ_CONSUMER");
    ObjectNode subject =
        JSON.createObjectNode()
            .put("payNo", PAY)
            .put("orderNo", ORDER)
            .put("userId", 7)
            .put("skuId", 22)
            .put("month", "202610")
            .put("amount", "10.00")
            .put("debitMode", mode);
    phase.set("subject", subject);
    ObjectNode sources = phase.putObject("sources");
    for (String service : List.of("pay", "user", "order", "stock")) {
      ObjectNode source =
          JSON.createObjectNode()
              .put("status", "OBSERVED")
              .put("schema", RUN + "_" + service)
              .put("captureStartedAt", START)
              .put("captureEndedAt", END);
      source.putObject("tables");
      sources.set(service, source);
    }
    source(phase, "pay")
        .put("sourceChanged", false)
        .put("anchorBeforeDigest", hash("stable"))
        .put("anchorAfterDigest", hash("stable"));
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_receipt",
            "pay_tcc_dispatch",
            "pay_tcc_delivery",
            "pay_tcc_cancel",
            "pay_callback_log")) table(phase, "pay", table, List.of());
    for (String table :
        List.of(
            "balance_debit_attempt",
            "balance_tcc_debit",
            "user_tcc_receive",
            "balance_ledger",
            "mall_user")) table(phase, "user", table, List.of());
    ObjectNode payment =
        tuple()
            .put("debit_mode", mode)
            .put("status", cancelled ? "FAILED" : trying ? "PENDING" : "SUCCESS");
    table(phase, "pay", "pay_record", List.of(payment));
    table(
        phase,
        "user",
        "mall_user",
        List.of(
            JSON.createObjectNode()
                .put("id", 7)
                .put("balance", cancelled ? "100.00" : "90.00")
                .put("frozen_balance", trying ? "10.00" : "0.00")
                .put("status", 1)));
    if (!trying && !cancelled) table(phase, "user", "balance_ledger", List.of(debit()));
    if (legacy) {
      table(
          phase,
          "pay",
          "pay_debit_intent",
          List.of(tuple().put("debit_mode", "LEGACY").put("status", "SUCCESS")));
      table(
          phase,
          "user",
          "balance_debit_attempt",
          List.of(
              tuple()
                  .put("mode", "LEGACY")
                  .put("state", "APPLIED")
                  .put("operation_key", fp(PAY))
                  .put("ledger_key", fp(PAY))
                  .set("xid", NullNode.instance)));
      row(phase, "user", "balance_debit_attempt").putNull("branch_id");
    } else {
      ObjectNode execution =
          tccRow()
              .put("state", state)
              .put("decision", committed ? "COMMIT" : cancelled ? "ROLLBACK" : "NONE");
      if (state.equals("BEGIN_UNKNOWN")) execution.putNull("xid");
      table(phase, "pay", "pay_tcc_execution", List.of(execution));
      table(
          phase,
          "pay",
          "pay_tcc_receipt",
          List.of(tccRow().put("state", funds).put("branch_id", fp("101"))));
      table(
          phase,
          "pay",
          "pay_tcc_dispatch",
          List.of(
              tccRow()
                  .put("state", trying ? "TRY_ACCEPTED" : funds)
                  .put("user_branch_id", fp("202"))));
      table(
          phase,
          "user",
          "user_tcc_receive",
          List.of(tccRow().put("state", "CLAIMED").put("branch_id", fp("202"))));
      table(
          phase,
          "user",
          "balance_debit_attempt",
          List.of(tccRow().put("state", funds).put("mode", "TCC").put("branch_id", fp("202"))));
      ObjectNode tcc =
          tccRow()
              .put("state", funds)
              .put("branch_id", fp("202"))
              .put("balance_before", "100.00")
              .put("balance_after_try", "90.00");
      tcc.remove("ledger_key");
      table(phase, "user", "balance_tcc_debit", List.of(tcc));
      if (committed)
        table(
            phase,
            "pay",
            "pay_tcc_delivery",
            List.of(
                tuple()
                    .put("state", "SUCCESS")
                    .put("xid", fp("raw-synthetic-xid"))
                    .put("refund_operation_key", fp("refund:" + PAY))));
      for (String service : List.of("pay", "user")) {
        ObjectNode fence = JSON.createObjectNode().put("status", "OBSERVED");
        ObjectNode tables = fence.putObject("tables");
        tables.set(
            "tcc_fence_log",
            tableValue(
                List.of(
                    JSON.createObjectNode()
                        .put("xid", fp("raw-synthetic-xid"))
                        .put("branch_id", fp(service.equals("pay") ? "101" : "202"))
                        .put(
                            "action_name",
                            service.equals("pay")
                                ? "cloudmallPayDebitReceipt"
                                : "cloudmallUserBalanceDebit")
                        .put("status", committed ? 2 : cancelled ? 3 : 1))));
        source(phase, service).set("fence", fence);
      }
      fixture.tc = nativeTc(state);
      sources.set(
          "tc",
          JSON.createObjectNode()
              .put("status", "OBSERVED")
              .put("observedGlobalStatus", trying ? "Begin" : "Finished")
              .put("xidSha256", hash("raw-synthetic-xid")));
    }
    ObjectNode order =
        tuple()
            .put("status", cancelled ? "CANCELLED" : trying ? "PENDING_PAYMENT" : "PAID")
            .put("pay_amount", "10.00")
            .put("total_amount", "10.00");
    order.remove("pay_no");
    order.put("id", 11);
    table(phase, "order", "mall_order_202610", List.of(order));
    table(
        phase,
        "order",
        "mall_order_item_202610",
        List.of(
            JSON.createObjectNode()
                .put("id", 12)
                .put("order_id", 11)
                .put("order_no", ORDER)
                .put("sku_id", 22)
                .put("quantity", 1)
                .put("unit_price", "10.00")
                .put("line_amount", "10.00")));
    table(phase, "order", "order_outbox", List.of());
    table(
        phase,
        "order",
        "order_stock_action",
        trying ? List.of() : List.of(action(cancelled ? "ROLLBACK" : "CONFIRM")));
    stock(phase, cancelled ? "ROLLBACK" : trying ? null : "CONFIRM");
    return fixture;
  }

  private ObjectNode tuple() {
    return JSON.createObjectNode()
        .put("id", 1)
        .put("pay_no", PAY)
        .put("order_no", ORDER)
        .put("user_id", 7)
        .put("amount", "10.00");
  }

  private ObjectNode tccRow() throws Exception {
    return tuple()
        .put("xid", fp("raw-synthetic-xid"))
        .put("operation_key", fp("tcc-debit:" + PAY))
        .put("ledger_key", fp(PAY));
  }

  private ObjectNode debit() throws Exception {
    return tuple()
        .put("operation_type", "DEBIT")
        .put("state", "APPLIED")
        .put("operation_key", fp(PAY))
        .put("balance_before", "100.00")
        .put("balance_after", "90.00")
        .putNull("original_operation_key");
  }

  private ObjectNode action(String direction) throws Exception {
    return JSON.createObjectNode()
        .put("id", 41)
        .put("order_no", ORDER)
        .put("action_type", direction)
        .put("status", "SUCCEEDED")
        .put("operation_key", fp(ORDER + ":" + direction));
  }

  private void stock(ObjectNode phase, String direction) throws Exception {
    ArrayNode items =
        JSON.createArrayNode().add(JSON.createObjectNode().put("skuId", 22).put("quantity", 1));
    ObjectNode request =
        JSON.createObjectNode()
            .put("id", 31)
            .put("order_no", ORDER)
            .put("scene", "NORMAL")
            .put(
                "status",
                direction == null
                    ? "RESERVED"
                    : direction.equals("CONFIRM") ? "CONFIRMED" : "ROLLED_BACK")
            .put("items_hash", hash(JSON.writeValueAsBytes(items)));
    request.set("items_snapshot", items);
    table(phase, "stock", "stock_reservation_request", List.of(request));
    List<JsonNode> flows = new ArrayList<>();
    flows.add(flow("RESERVE"));
    if (direction != null) flows.add(flow(direction));
    table(phase, "stock", "stock_flow", flows);
    table(
        phase,
        "stock",
        "stock_sku",
        List.of(
            JSON.createObjectNode()
                .put("sku_id", 22)
                .put("total_quantity", 10)
                .put("available_quantity", "ROLLBACK".equals(direction) ? 10 : 9)
                .put("reserved_quantity", direction == null ? 1 : 0)
                .put("sold_quantity", "CONFIRM".equals(direction) ? 1 : 0)));
  }

  private ObjectNode flow(String direction) throws Exception {
    return JSON.createObjectNode()
        .put("id", direction.equals("RESERVE") ? 32 : 33)
        .put("order_no", ORDER)
        .put("sku_id", 22)
        .put("quantity", 1)
        .put("flow_type", direction)
        .put("idempotency_key", fp(ORDER + ":" + direction + ":22"));
  }

  private void pending(ObjectNode phase) throws Exception {
    row(phase, "pay", "pay_record").put("status", "PENDING");
    row(phase, "pay", "pay_tcc_delivery").put("state", "READY");
    row(phase, "order", "mall_order_202610").put("status", "PENDING_PAYMENT");
    table(phase, "order", "order_stock_action", List.of());
    stock(phase, null);
  }

  private void refunded(ObjectNode phase) throws Exception {
    row(phase, "pay", "pay_record").put("status", "REFUNDED");
    if (!source(phase, "pay").at("/tables/pay_tcc_delivery/rows").isEmpty())
      row(phase, "pay", "pay_tcc_delivery").put("state", "REFUNDED");
    ObjectNode refund =
        tuple()
            .put("id", 2)
            .put("state", "APPLIED")
            .put("operation_type", "REFUND")
            .put("operation_key", fp("refund:" + PAY))
            .put("original_operation_key", fp(PAY))
            .put("balance_before", "90.00")
            .put("balance_after", "100.00");
    table(phase, "user", "balance_ledger", List.of(debit(), refund));
    row(phase, "user", "mall_user").put("balance", "100.00");
    row(phase, "order", "mall_order_202610").put("status", "CANCELLED");
    table(phase, "order", "order_stock_action", List.of(action("ROLLBACK")));
    stock(phase, "ROLLBACK");
    ObjectNode outbox =
        JSON.createObjectNode()
            .put("id", 51)
            .put("order_no", ORDER)
            .put("event_type", "ORDER_TIMEOUT")
            .put("event_id", fp("order-timeout:" + ORDER))
            .put("status", "PUBLISHED");
    outbox.set(
        "payload",
        JSON.createObjectNode().put("present", true).put("sha256", hash("database-json-not-wire")));
    table(phase, "order", "order_outbox", List.of(outbox));
  }

  private static ObjectNode source(ObjectNode phase, String service) {
    return (ObjectNode) phase.at("/sources/" + service);
  }

  private static ObjectNode row(ObjectNode phase, String service, String table) {
    return (ObjectNode) phase.at("/sources/" + service + "/tables/" + table + "/rows/0");
  }

  private static void table(
      ObjectNode phase, String service, String table, List<? extends JsonNode> rows) {
    ((ObjectNode) source(phase, service).path("tables")).set(table, tableValue(rows));
  }

  private static ObjectNode tableValue(List<? extends JsonNode> rows) {
    ObjectNode value =
        JSON.createObjectNode()
            .put("status", rows.isEmpty() ? "EMPTY" : "OBSERVED")
            .put("rowCount", rows.size());
    ArrayNode array = value.putArray("rows");
    rows.forEach(array::add);
    return value;
  }

  private ObjectNode nativeTc(String state) throws Exception {
    boolean commit = state.equals("COMMITTED"), cancel = state.equals("ROLLEDBACK");
    ObjectNode result =
        JSON.createObjectNode()
            .put("schemaVersion", "CM18_TC_BRANCH_EVIDENCE_V1")
            .put("scope", "ISOLATED_TEST_TC_SERVER_OBSERVATION")
            .put("source", "SEATA_1_5_2_SERVER_NATIVE_DOCKER_LOG")
            .put("runId", RUN)
            .put("windowClockSource", "DOCKER_DAEMON_SYSTEM_TIME")
            .put("windowStartedAt", START)
            .put("windowEndedAt", END)
            .put("xidSha256", hash("raw-synthetic-xid"))
            .put(
                "globalOutcome",
                commit
                    ? "SERVER_GLOBAL_COMMIT_OBSERVED"
                    : cancel ? "SERVER_GLOBAL_ROLLBACK_OBSERVED" : "UNOBSERVED")
            .put("status", commit || cancel ? "OBSERVED" : "INCOMPLETE")
            .put("directionProvenByTc", commit || cancel)
            .put("branchDetailsObserved", cancel);
    ObjectNode start =
        JSON.createObjectNode()
            .put("status", "OK")
            .put("sourceTime", START)
            .put("hostRequestStartedAt", START)
            .put("hostRequestEndedAt", START)
            .put("requestElapsedNanos", 1)
            .put("daemonIdentitySha256", hash("daemon"))
            .put("tcIdentitySha256", hash("tc"));
    result.set("sourceStartAnchor", start);
    ObjectNode end = start.deepCopy().put("sourceTime", END);
    ObjectNode sourceRead =
        JSON.createObjectNode()
            .put("status", "OK")
            .put("identityStable", true)
            .put("containerIdentitySha256", hash("tc"));
    sourceRead.set("endAnchor", end);
    sourceRead.putArray("readStatuses").add("OK").add("OK").add("OK").add("OK");
    result.set("sourceRead", sourceRead);
    ArrayNode events = result.putArray("events");
    for (String participant : List.of("pay", "user")) {
      String branch = hash(participant.equals("pay") ? "101" : "202"),
          resource =
              participant.equals("pay") ? "cloudmallPayDebitReceipt" : "cloudmallUserBalanceDebit";
      result.set(
          participant,
          JSON.createObjectNode().put("branchSha256", branch).put("resource", resource));
      events.add(
          JSON.createObjectNode()
              .put("type", "REGISTER")
              .put("observedAt", START)
              .put("branchSha256", branch)
              .put("resource", resource));
      if (cancel)
        events.add(
            JSON.createObjectNode()
                .put("type", "PHASE_TWO_ROLLBACK")
                .put("observedAt", START)
                .put("branchSha256", branch));
    }
    if (commit || cancel)
      events.add(
          JSON.createObjectNode()
              .put("type", commit ? "GLOBAL_COMMIT" : "GLOBAL_ROLLBACK")
              .put("observedAt", END));
    for (JsonNode event : events) ((ObjectNode) event).put("lineSha256", hash(event.toString()));
    result.putArray("missingEvidence");
    return result;
  }

  private ObjectNode r1() throws Exception {
    String event = hash("order-timeout:" + ORDER), body = hash("wire-body");
    ObjectNode original = publish(event, body, true), retry = publish(event, body, false);
    ObjectNode first = trace(event);
    ((ArrayNode) first.path("published")).add(original);
    ObjectNode retried = trace(event);
    ((ArrayNode) retried.path("published")).add(original.deepCopy()).add(retry);
    ((ArrayNode) retried.path("deliveries")).add(receipt(event, body));
    ObjectNode report =
        JSON.createObjectNode()
            .put("schemaVersion", "CM18_MQ_EVENT_EVIDENCE_V1")
            .put("scope", "ISOLATED_TEST_EVENT_CAUSALITY")
            .put("runId", RUN)
            .put("scenarioId", "MQ-R1")
            .put("phase", "VERIFIED")
            .put("virtualHost", RUN)
            .put("queue", "cloudmall.order.timeout.dlx")
            .put("eventCausalityProven", false)
            .put("brokerAckHistoryProven", false);
    report.set(
        "subject",
        JSON.createObjectNode()
            .put("payNo", PAY)
            .put("orderNo", ORDER)
            .put("eventIdSha256", event));
    report.set("observations", JSON.createObjectNode().set("returnedEvent", first));
    ((ObjectNode) report.path("observations")).set("originalRetry", retried);
    ((ObjectNode) report.path("observations")).set("finalOrder", retried.deepCopy());
    report.putArray("artifactManifest");
    return report;
  }

  private ObjectNode trace(String event) throws Exception {
    ObjectNode trace =
        JSON.createObjectNode()
            .put("schemaVersion", "CM18_ORDER_TIMEOUT_PROCESS_TRACE_V1")
            .put("queue", "cloudmall.order.timeout.dlx")
            .put("ackMode", "AUTO")
            .put("ackObservationScope", "LOCAL_BASIC_ACK_RETURN_ONLY")
            .put("targetEventIdSha256", event)
            .put("targetOrderNoSha256", hash(ORDER))
            .put("ready", true)
            .put("boundExceeded", false)
            .put("droppedEvents", 0)
            .put("retryPreserved", true)
            .put("retryMaxAttempts", 3)
            .put("prefetch", 1)
            .put("concurrency", 1)
            .put("processPid", 123)
            .put("brokerAckConfirmed", false)
            .put("adviceCount", 2)
            .put("defaultRequeueRejected", false)
            .put("publisherObserverInstalled", true)
            .put("maxEvents", 100);
    trace
        .putArray("originalAdviceClasses")
        .add("org.springframework.retry.interceptor.RetryOperationsInterceptor");
    trace.putArray("published");
    trace.putArray("deliveries");
    return trace;
  }

  private ObjectNode publish(String event, String body, boolean returned) throws Exception {
    ObjectNode value =
        JSON.createObjectNode()
            .put("eventIdSha256", event)
            .put("correlationSha256", event)
            .put("orderNoSha256", hash(ORDER))
            .put("bodySha256", body)
            .put("exchange", "cloudmall.order.timeout.dlx.exchange")
            .put("routingKey", "")
            .put("mandatory", true)
            .put("confirmObserved", true)
            .put("confirmAck", true)
            .put("returned", returned);
    if (returned)
      value
          .put("returnedReplyCode", 312)
          .put("returnedBodySha256", body)
          .put("returnedExchange", "cloudmall.order.timeout.dlx.exchange")
          .put("returnedRoutingKey", "");
    return value;
  }

  private ObjectNode receipt(String event, String body) throws Exception {
    return JSON.createObjectNode()
        .put("eventIdSha256", event)
        .put("orderNoSha256", hash(ORDER))
        .put("bodySha256", body)
        .put("queue", "cloudmall.order.timeout.dlx")
        .put("exchange", "cloudmall.order.timeout.dlx.exchange")
        .put("routingKey", "")
        .put("redelivered", false)
        .put("transactionReturned", true)
        .put("transactionActiveAfter", false)
        .put("synchronizationActiveAfter", false)
        .put("processPid", 123)
        .put("ackAttemptObserved", true)
        .put("ackObservedAt", START)
        .put("ackObserved", true)
        .put("transactionReturnedAt", START)
        .put("transactionReturnSequence", 2)
        .put("ackSequence", 3);
  }

  private static String fp(String value) throws Exception {
    return "sha256:" + hash(value);
  }

  private static String hash(String value) throws Exception {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
