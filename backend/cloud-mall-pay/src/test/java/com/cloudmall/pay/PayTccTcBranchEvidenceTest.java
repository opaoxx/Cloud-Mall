package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 仅验证受控原生文本的信任边界；不是 live TC 运行证据。 */
class PayTccTcBranchEvidenceTest {
  /** 受控身份与窗口不连接任何事务协调器。 */
  private static final String XID = "seata-server:8091:1001";

  /** Docker UTC 窗口须先于受控原注册。 */
  private static final Instant START = Instant.parse("2026-10-15T18:00:00Z");

  /** 窗口末尾之后的文本不能作为本次事实。 */
  private static final Instant END = START.plusSeconds(60);

  @Test
  void commitProvesRegistrationAndGlobalDirectionWithoutInventingBranchAck() throws Exception {
    Map<String, Object> facts = parse(registrations() + global(true));
    assertTrue((Boolean) branch(facts, "pay").get("registrationObserved"));
    assertTrue((Boolean) branch(facts, "user").get("registrationObserved"));
    assertEquals("SERVER_GLOBAL_COMMIT_OBSERVED", facts.get("globalOutcome"));
    assertEquals(true, facts.get("directionProvenByTc"));
    assertEquals(false, facts.get("branchDetailsObserved"));
    assertEquals("ACK_UNOBSERVED", branch(facts, "pay").get("phaseTwo"));
    assertEquals("ACK_UNOBSERVED", branch(facts, "user").get("commitAck"));
    String json = new ObjectMapper().writeValueAsString(facts);
    assertFalse(json.contains(XID));
    assertFalse(json.contains("branchId"));
    assertTrue(json.contains("lineSha256"));
    assertTrue(json.contains("TC_COMMIT_BRANCH_ACK_NOT_LOGGED"));
  }

  @Test
  void rollbackRequiresBothOriginalBranchesAndNativeGlobalOutcome() {
    Map<String, Object> facts =
        parse(registrations() + rollback(2001) + rollback(2002) + global(false));
    assertEquals("SERVER_GLOBAL_ROLLBACK_OBSERVED", facts.get("globalOutcome"));
    assertEquals(true, facts.get("branchDetailsObserved"));
    assertEquals("SERVER_PHASE_TWO_ROLLBACK_OBSERVED", branch(facts, "pay").get("phaseTwo"));
    assertEquals("SERVER_PHASE_TWO_ROLLBACK_OBSERVED", branch(facts, "user").get("phaseTwo"));
    assertEquals(
        false,
        parse(registrations() + rollback(2001) + global(false)).get("branchDetailsObserved"));
  }

  @Test
  void prefixesWrongLoggerLevelTimeAndTrailingDataCannotProveFacts() {
    for (String invalid :
        new String[] {
          registrations().replace(XID, XID + "9"),
          registrations().replace("AbstractCore", "FakeAbstractCore"),
          registrations().replace("INFO", "WARN"),
          registrations().replace("18:00:01Z", "17:59:59Z"),
          registrations().replace("lockKeys = null", "lockKeys = null extra")
        }) {
      Map<String, Object> facts = parse(invalid);
      assertEquals(false, branch(facts, "pay").get("registrationObserved"));
      assertEquals(false, branch(facts, "user").get("registrationObserved"));
      assertEquals(false, facts.get("directionProvenByTc"));
    }
    assertEquals(
        "PROTOCOL_CONFLICT", parse(registrations().replace("2001", "20019")).get("status"));
    assertEquals(
        "PROTOCOL_CONFLICT",
        parse(registrations().replace("cloudmallPayDebitReceipt", "wrongResource")).get("status"));
  }

  @Test
  void contradictoryDirectionsAndExtraBranchCannotBeSilentlyFiltered() {
    for (String conflict :
        new String[] {
          registrations() + global(true) + global(false),
          registrations() + global(true) + rollback(2001),
          registrations() + rollback(2003)
        }) {
      Map<String, Object> facts = parse(conflict);
      assertEquals("PROTOCOL_CONFLICT", facts.get("status"));
      assertEquals(false, facts.get("directionProvenByTc"));
      assertEquals(false, facts.get("branchDetailsObserved"));
    }
  }

  @Test
  void byteLineAndUtf8BoundsDiscardAllPartialSuccess() {
    byte[] bytes = new byte[4 * 1024 * 1024 + 1];
    byte[] valid = (registrations() + global(true)).getBytes(StandardCharsets.UTF_8);
    System.arraycopy(valid, 0, bytes, 0, valid.length);
    assertUnavailable(
        PayTccTcBranchEvidence.parse(bytes, XID, 2001, 2002, START, END), "BOUND_EXCEEDED");
    assertUnavailable(parse(registrations() + "unrelated\n".repeat(4998)), "BOUND_EXCEEDED");
    byte[] malformed = new byte[valid.length + 1];
    System.arraycopy(valid, 0, malformed, 0, valid.length);
    malformed[valid.length] = (byte) 0xc3;
    assertUnavailable(
        PayTccTcBranchEvidence.parse(malformed, XID, 2001, 2002, START, END), "PARSE_INCOMPLETE");
  }

  @Test
  void onlySuccessfullyReadIncompleteFactsCanRetryAndMissingCommitAckDoesNotTriggerRetry() {
    Map<String, Object> incomplete = new LinkedHashMap<>(parse(registrations()));
    incomplete.put("sourceRead", Map.of("status", "OK"));
    assertTrue(PayTccTcBranchEvidence.shouldRetry(incomplete));
    incomplete.put("sourceRead", Map.of("status", "SOURCE_UNAVAILABLE"));
    assertFalse(PayTccTcBranchEvidence.shouldRetry(incomplete));
    for (Map<String, Object> terminal :
        List.of(
            parse(registrations() + global(true)),
            parse(registrations() + global(true) + global(false)),
            parse("unrelated\n".repeat(5000)),
            PayTccTcBranchEvidence.parse(new byte[] {(byte) 0xc3}, XID, 2001, 2002, START, END))) {
      Map<String, Object> bundle = new LinkedHashMap<>(terminal);
      bundle.put("sourceRead", Map.of("status", "OK"));
      assertFalse(PayTccTcBranchEvidence.shouldRetry(bundle));
    }
  }

  @Test
  void hostClockAheadOrBehindNeverClipsEventsInsideTheSourceWindow() {
    byte[] logs = (registrations() + global(true)).getBytes(StandardCharsets.UTF_8);
    for (long hostOffset : new long[] {-60, 60}) {
      var sourceStart = window(START, START.plusSeconds(hostOffset));
      var sourceEnd = window(END, END.plusSeconds(hostOffset));
      Map<String, Object> facts =
          PayTccTcBranchEvidence.parseWindow(logs, XID, 2001, 2002, sourceStart, sourceEnd, null);
      assertEquals("SERVER_GLOBAL_COMMIT_OBSERVED", facts.get("globalOutcome"));
      assertEquals(true, branch(facts, "pay").get("registrationObserved"));
      assertEquals(true, branch(facts, "user").get("registrationObserved"));
      assertEquals("ACK_UNOBSERVED", branch(facts, "pay").get("commitAck"));
    }
  }

  @Test
  void unavailableRegressedOrChangedSourceCannotFallBackToAValidHostWindow() {
    byte[] logs = (registrations() + global(true)).getBytes(StandardCharsets.UTF_8);
    var sourceStart = window(START, START);
    var unavailable =
        new PayTccTcBranchEvidence.Window(
            START.toString(), END.toString(), null, "daemon", "SOURCE_UNAVAILABLE", "tc", 1L);
    assertUnavailable(
        PayTccTcBranchEvidence.parseWindow(logs, XID, 2001, 2002, sourceStart, unavailable, null),
        "SOURCE_UNAVAILABLE");
    assertUnavailable(
        PayTccTcBranchEvidence.parseWindow(
            logs, XID, 2001, 2002, sourceStart, window(START.minusSeconds(1), END), null),
        "CLOCK_REGRESSION");
    assertUnavailable(
        PayTccTcBranchEvidence.parseWindow(
            logs, XID, 2001, 2002, sourceStart, window(END, END), END.plusSeconds(1).toString()),
        "CLOCK_REGRESSION");
    var changed =
        new PayTccTcBranchEvidence.Window(
            START.toString(), END.toString(), END.toString(), "different-daemon", "OK", "tc", 1L);
    assertUnavailable(
        PayTccTcBranchEvidence.parseWindow(logs, XID, 2001, 2002, sourceStart, changed, null),
        "SOURCE_CHANGED");
  }

  private static PayTccTcBranchEvidence.Window window(Instant source, Instant host) {
    return new PayTccTcBranchEvidence.Window(
        host.toString(),
        host.plusMillis(1).toString(),
        source.toString(),
        "daemon",
        "OK",
        "tc",
        1L);
  }

  private static void assertUnavailable(Map<String, Object> facts, String status) {
    assertEquals(status, facts.get("status"));
    assertEquals(false, facts.get("directionProvenByTc"));
    assertEquals(false, facts.get("branchDetailsObserved"));
    assertEquals(false, branch(facts, "pay").get("registrationObserved"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> branch(Map<String, Object> facts, String name) {
    return (Map<String, Object>) facts.get(name);
  }

  private static Map<String, Object> parse(String text) {
    return PayTccTcBranchEvidence.parse(
        text.getBytes(StandardCharsets.UTF_8), XID, 2001, 2002, START, END);
  }

  private static String registrations() {
    return line(
            "i.seata.server.coordinator.AbstractCore",
            "Register branch successfully, xid = "
                + XID
                + ", branchId = 2001, resourceId = cloudmallPayDebitReceipt ,lockKeys = null")
        + line(
            "io.seata.server.coordinator.AbstractCore",
            "Register branch successfully, xid = "
                + XID
                + ", branchId = 2002, resourceId = cloudmallUserBalanceDebit ,lockKeys = null");
  }

  private static String rollback(long branch) {
    return line(
        "io.seata.server.coordinator.DefaultCore",
        "Rollback branch transaction successfully, xid = " + XID + " branchId = " + branch);
  }

  private static String global(boolean commit) {
    return line(
        "io.seata.server.coordinator.DefaultCore",
        (commit
                ? "Committing global transaction is successfully done"
                : "Rollback global transaction successfully")
            + ", xid = "
            + XID
            + ".");
  }

  private static String line(String logger, String message) {
    return "2026-10-15T18:00:01Z 02:00:01.000  INFO --- [server-worker] "
        + logger
        + " : "
        + message
        + "\n";
  }
}
