package com.cloudmall.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Test-only immutable admission and bounded physical-connection evidence; no production defaults.
 */
final class ProductMetricQueryInfoCacheSupport {
  /** Sole optional test flag, strictly parsed at the actor-free entry boundary. */
  static final String FLAG = "cloudmall.cm21.single10.client-queryinfo-cache";

  /** Original URL bytes shared by legacy and the audited false control. */
  static final String ORIGINAL_PARAMETERS =
      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000";

  /** Only the reviewed true arm may append these four literal properties. */
  static final String TRUE_SUFFIX =
      "&cachePrepStmts=true&useServerPrepStmts=false&prepStmtCacheSize=25&prepStmtCacheSqlLimit=256";

  /** Resource-free tests may stop at real actor boundaries; normal runs have no hook. */
  static final ThreadLocal<ActorHook> ACTOR_HOOK = new ThreadLocal<>();

  /** Tests can mutate properties after capture to prove actual entry snapshot propagation. */
  static final ThreadLocal<Consumer<Admission>> CAPTURE_HOOK = new ThreadLocal<>();

  /** Failure injection at proxy preparation; no change to normal proxy behavior. */
  static final ThreadLocal<Runnable> PROXY_HOOK = new ThreadLocal<>();

  /** Failure injection in audit preparation, never a substitute audit implementation. */
  static final ThreadLocal<Consumer<String>> AUDIT_HOOK = new ThreadLocal<>();

  /** Fixed JSON codec for bounded cold-path evidence only. */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .configure(
              com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /** Separate legacy absence of instrumentation from the symmetric two audited arms. */
  enum Policy {
    LEGACY_DISABLED,
    CONTROL_FALSE,
    CANDIDATE_TRUE
  }

  /**
   * Immutable cache-qualification shape, captured once before any claim or Pipeline initializer.
   */
  record Admission(
      Policy policy,
      String request,
      boolean single10,
      String role,
      ProductMetricPipelineLoadSupport.Mode mode,
      boolean batch,
      int consumers,
      int seeds,
      String phaseTiming,
      String phaseAnchor) {
    /** No caller can forge a policy inconsistent with the immutable request and qualification. */
    Admission {
      if (policy
          != qualifiedPolicy(
              request, single10, role, mode, batch, consumers, seeds, phaseTiming, phaseAnchor))
        throw invalid("POLICY_MISMATCH");
    }

    /** Whether every product-owned physical acquisition must be independently audited. */
    boolean controlled() {
      return policy != Policy.LEGACY_DISABLED;
    }
  }

  /** Actual JDBC leaf opener; tests inject only this leaf rather than mocking strategy behavior. */
  @FunctionalInterface
  interface RawOpener {
    /** Acquire one raw connection using the exact strategy-produced URL. */
    Connection open(String url, String username, String password) throws SQLException;
  }

  /** Observation immediately before a real actor, with the same immutable entry context. */
  @FunctionalInterface
  interface ActorHook {
    /** Observe or fail before the named operation is executed. */
    void before(String kind, Admission admission);
  }

  /** Utility-only helper, never a new application service. */
  private ProductMetricQueryInfoCacheSupport() {}

  /** Old independent DB/Stock constructors deliberately do not consult any system property. */
  static Admission legacy() {
    return new Admission(
        Policy.LEGACY_DISABLED, null, false, "legacy", null, false, 1, 0, "false", "false");
  }

  /** Capture the exact role/mode/batch/consumer/seed/phase qualification before actors. */
  static Admission capture(String role, int seeds, ProductMetricPipelineLoadSupport.Mode mode) {
    String consumers = System.getProperty("cloudmall.cm21.metric-consumers", "1");
    if (!"1".equals(consumers) && !"2".equals(consumers)) throw invalid("CONSUMERS");
    Admission admission =
        admit(
            System.getProperty(FLAG),
            ProductMetricPipelineLoadSupport.ROLE.equals(role),
            role,
            mode,
            bool(System.getProperty("cloudmall.cm21.pipeline-batch", "false"), "BATCH"),
            Integer.parseInt(consumers),
            seeds,
            System.getProperty("cloudmall.cm21.phase-timing", "false"),
            System.getProperty("cloudmall.cm21.phase-anchor", "false"));
    Consumer<Admission> hook = CAPTURE_HOOK.get();
    if (hook != null) hook.accept(admission);
    return admission;
  }

  /** Build one validated immutable qualification; true has exactly one supported shape. */
  static Admission admit(
      String request,
      boolean single10,
      String role,
      ProductMetricPipelineLoadSupport.Mode mode,
      boolean batch,
      int consumers,
      int seeds,
      String phaseTiming,
      String phaseAnchor) {
    return new Admission(
        qualifiedPolicy(
            request, single10, role, mode, batch, consumers, seeds, phaseTiming, phaseAnchor),
        request,
        single10,
        role,
        mode,
        batch,
        consumers,
        seeds,
        phaseTiming,
        phaseAnchor);
  }

  /**
   * Derive the sole policy for a complete immutable shape, without consulting system properties.
   */
  private static Policy qualifiedPolicy(
      String request,
      boolean single10,
      String role,
      ProductMetricPipelineLoadSupport.Mode mode,
      boolean batch,
      int consumers,
      int seeds,
      String phaseTiming,
      String phaseAnchor) {
    boolean requested = request != null && bool(request, "BOOLEAN");
    bool(phaseTiming, "PHASE_TIMING");
    bool(phaseAnchor, "PHASE_ANCHOR");
    if (consumers != 1 && consumers != 2) throw invalid("CONSUMERS");
    if (single10 != ProductMetricPipelineLoadSupport.ROLE.equals(role)) throw invalid("ROLE");
    if (single10)
      ProductMetricPipelineLoadSupport.admit(
          mode, batch, consumers, seeds, phaseTiming, phaseAnchor);
    else if (mode != null) throw invalid("LEGACY_MODE");
    if (requested
        && (!single10
            || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
            || !batch
            || consumers != 1
            || seeds != 300
            || !"false".equals(phaseTiming)
            || !"false".equals(phaseAnchor))) throw invalid("SINGLE10_BATCH1_REQUIRED");
    if (!"false".equals(phaseAnchor)) throw invalid("PHASE_ANCHOR_DISABLED_REQUIRED");
    return single10
        ? requested ? Policy.CANDIDATE_TRUE : Policy.CONTROL_FALSE
        : Policy.LEGACY_DISABLED;
  }

  /** Verify a supplied typed context before an explicit DB/DS constructor can create an actor. */
  static Admission requireIntrinsic(Admission admission) {
    Objects.requireNonNull(admission, "admission");
    return requireContext(admission, admission.role(), admission.seeds(), admission.mode());
  }

  /**
   * Reject malformed direct ctor contexts without rereading properties or choosing a new policy.
   */
  static Admission requireContext(
      Admission admission, String role, int seeds, ProductMetricPipelineLoadSupport.Mode mode) {
    Objects.requireNonNull(admission, "admission");
    if (!Objects.equals(admission.role(), role)
        || admission.seeds() != seeds
        || admission.mode() != mode) throw invalid("CONTEXT_MISMATCH");
    Admission verified =
        admit(
            admission.request(),
            admission.single10(),
            role,
            mode,
            admission.batch(),
            admission.consumers(),
            seeds,
            admission.phaseTiming(),
            admission.phaseAnchor());
    if (!verified.equals(admission)) throw invalid("POLICY_MISMATCH");
    return admission;
  }

  /** Hooks observe actual entry/constructor operations and are absent during normal execution. */
  static void actor(String kind, Admission admission) {
    ActorHook hook = ACTOR_HOOK.get();
    if (hook != null) hook.before(kind, admission);
  }

  /** Preserve old bytes for false/legacy; reject duplicate/conflicting cache parameters. */
  static String url(String base, String catalog, Admission admission) {
    if (base == null || !base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))
      throw invalid("LOOPBACK_BASE");
    if (catalog == null || !catalog.matches("[a-zA-Z0-9_]+")) throw invalid("CATALOG");
    String original = base + catalog + ORIGINAL_PARAMETERS;
    return original + (admission.policy() == Policy.CANDIDATE_TRUE ? TRUE_SUFFIX : "");
  }

  /** Independently read the source/admin UUID anchor or check one subsequent controlled raw. */
  static String serverUuid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(3);
      try (var result = statement.executeQuery("SELECT @@server_uuid")) {
        if (!result.next()) throw new SQLException("CM21_QUERYINFO_UUID_MISSING");
        String uuid = result.getString(1);
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
          throw new SQLException("CM21_QUERYINFO_UUID_INVALID");
        if (result.next()) throw new SQLException("CM21_QUERYINFO_UUID_MULTIPLE");
        return uuid;
      }
    }
  }

  /** Validate an independently supplied trusted UUID before any physical acquisition. */
  static String expectedUuid(String uuid) {
    if (uuid == null || !uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
      throw invalid("EXPECTED_UUID");
    return uuid;
  }

  /**
   * Controlled product acquisition: original catalog/SET checks, effective flags, then proxy
   * delivery.
   */
  static Connection open(
      String base,
      String catalog,
      Admission admission,
      String expectedUuid,
      AtomicInteger open,
      Audit audit,
      RawOpener opener)
      throws SQLException {
    if (!admission.controlled()) throw invalid("CONTROLLED_REQUIRED");
    expectedUuid(expectedUuid);
    String connectionUrl = url(base, catalog, admission);
    audit.begin();
    Connection raw;
    try {
      raw = opener.open(connectionUrl, "root", "root");
    } catch (SQLException | RuntimeException | Error failure) {
      audit.safely(
          "ACQUISITION_FAILED",
          Map.of("acquisitionFailed", 1L, "inFlight", -1L),
          Map.of(),
          failure);
      throw failure;
    }
    long connectionOrdinal = 0;
    boolean reserved = false;
    try {
      connectionOrdinal = audit.created();
      // 1. Preserve the original catalog and SET+08 order before new metadata validation.
      if (!catalog.equals(raw.getCatalog())) throw new SQLException("OWNED_CATALOG_MISMATCH");
      try (Statement statement = raw.createStatement();
          var result = statement.executeQuery("SELECT DATABASE()")) {
        if (!result.next() || !catalog.equals(result.getString(1)))
          throw new SQLException("OWNED_CATALOG_MISMATCH");
      }
      try (Statement statement = raw.createStatement()) {
        statement.execute("SET time_zone='+08:00'");
      }
      Map<String, Object> facts = effective(raw, admission, catalog, expectedUuid);
      audit.transition("VALIDATED", Map.of("validated", 1L), facts);
      // 2. Prepare the proxy before the counted ownership handoff, including failure injection.
      Runnable hook = PROXY_HOOK.get();
      if (hook != null) hook.run();
      long ordinal = connectionOrdinal;
      Object[] closeState = {false};
      Connection proxy =
          (Connection)
              Proxy.newProxyInstance(
                  ProductMetricQueryInfoCacheSupport.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (ignored, method, arguments) -> {
                    if (method.getName().equals("close")) {
                      synchronized (closeState) {
                        if (!((Boolean) closeState[0])) {
                          try {
                            raw.close();
                          } catch (SQLException | RuntimeException | Error closeFailure) {
                            audit.safely(
                                "DELIVERED_CLOSE_FAILED",
                                Map.of("closeFailedAttempts", 1L),
                                Map.of("connectionOrdinal", ordinal),
                                closeFailure);
                            throw closeFailure;
                          }
                          closeState[0] = true;
                          open.decrementAndGet();
                          audit.completedClose(true, ordinal);
                        }
                      }
                      return null;
                    }
                    try {
                      return method.invoke(raw, arguments);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      // 3. Reserve original open only for a prepared deliverable; undo if audited handoff fails.
      open.updateAndGet(Math::incrementExact);
      reserved = true;
      audit.transition(
          "DELIVERED",
          Map.of("delivered", 1L, "inFlight", -1L),
          Map.of("connectionOrdinal", connectionOrdinal));
      return proxy;
    } catch (SQLException | RuntimeException | Error failure) {
      if (reserved) open.decrementAndGet();
      audit.safely(
          "REJECTED",
          Map.of("rejected", 1L, "inFlight", -1L),
          Map.of("connectionOrdinal", connectionOrdinal),
          failure);
      boolean rawClosed = false;
      try {
        raw.close();
        rawClosed = true;
        audit.completedClose(false, connectionOrdinal);
      } catch (SQLException | RuntimeException | Error closeFailure) {
        if (closeFailure != failure) failure.addSuppressed(closeFailure);
        // An audit failure after successful physical close is not a physical close attempt failure.
        if (!rawClosed)
          audit.safely(
              "REJECTED_CLOSE_FAILED",
              Map.of("closeFailedAttempts", 1L),
              Map.of("connectionOrdinal", connectionOrdinal),
              failure);
      }
      throw failure;
    }
  }

  /** Read actual driver properties and independent server identity once per controlled raw. */
  private static Map<String, Object> effective(
      Connection raw, Admission admission, String catalog, String expectedUuid)
      throws SQLException {
    JdbcConnection driver = raw.unwrap(JdbcConnection.class);
    boolean cache =
        driver.getPropertySet().getBooleanProperty(PropertyKey.cachePrepStmts).getValue();
    boolean server =
        driver.getPropertySet().getBooleanProperty(PropertyKey.useServerPrepStmts).getValue();
    int size = driver.getPropertySet().getIntegerProperty(PropertyKey.prepStmtCacheSize).getValue();
    int limit =
        driver.getPropertySet().getIntegerProperty(PropertyKey.prepStmtCacheSqlLimit).getValue();
    if (cache != (admission.policy() == Policy.CANDIDATE_TRUE)
        || server
        || size != 25
        || limit != 256) throw new SQLException("CM21_QUERYINFO_EFFECTIVE_MISMATCH");
    String actualUuid = serverUuid(raw);
    if (!expectedUuid.equals(actualUuid)) throw new SQLException("CM21_QUERYINFO_UUID_MISMATCH");
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("cachePrepStmts", cache);
    facts.put("useServerPrepStmts", server);
    facts.put("prepStmtCacheSize", size);
    facts.put("prepStmtCacheSqlLimit", limit);
    facts.put("catalog", catalog);
    facts.put("serverUuid", actualUuid);
    facts.put("driverClass", driver.getClass().getName());
    facts.put("driverVersion", bounded(raw.getMetaData().getDriverVersion()));
    facts.put("connectionId", driver.getId());
    facts.put("rawDriverReferenceSame", raw == driver);
    facts.put("identityHashAuxiliary", System.identityHashCode(driver));
    try {
      var implementationSource = driver.getClass().getProtectionDomain().getCodeSource();
      facts.put(
          "actualImplementationCodeSource",
          implementationSource == null
              ? "ABSENT"
              : bounded(implementationSource.getLocation().toString()));
      if (implementationSource != null
          && "file".equals(implementationSource.getLocation().getProtocol())) {
        Path implementationPath = Path.of(implementationSource.getLocation().toURI());
        facts.put(
            "actualImplementationSourceKind",
            Files.isRegularFile(implementationPath)
                ? "REGULAR_FILE"
                : "DIRECTORY_OR_GENERATED_LEAF");
        facts.put(
            "actualImplementationSourceSha256",
            Files.isRegularFile(implementationPath)
                ? HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(implementationPath)))
                : "NOT_A_REGULAR_FILE");
      } else {
        facts.put("actualImplementationSourceKind", "ABSENT_OR_NON_FILE");
        facts.put("actualImplementationSourceSha256", "UNAVAILABLE");
      }
      Path jar =
          Path.of(JdbcConnection.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      facts.put("connectorApiClass", JdbcConnection.class.getName());
      facts.put("connectorApiJar", bounded(jar.toString()));
      facts.put(
          "connectorApiJarSha256",
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
    } catch (Exception failure) {
      throw new SQLException("CM21_QUERYINFO_DRIVER_SOURCE", failure);
    }
    return facts;
  }

  /**
   * Publish an independent bounded file; observation failures never replace a business throwable.
   */
  static boolean publish(
      Path directory,
      String kind,
      String owner,
      String runtimeIdentity,
      Admission admission,
      String expectedUuid,
      Audit audit) {
    if (!admission.controlled()) return true;
    try {
      if (!List.of("startup", "measurement-ended", "finish-10", "completion").contains(kind))
        throw invalid("SIDECAR_KIND");
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("schemaVersion", 1);
      record.put("scope", "TEST_ONLY_QUERYINFO_CACHE");
      record.put("kind", kind);
      record.put("owner", owner);
      record.put("mode", admission.mode().token());
      record.put("runIdentity", owner + "-" + admission.mode().token());
      record.put("runtimeIdentity", runtimeIdentity);
      record.put("policy", admission.policy().name());
      record.put("expectedUUID", expectedUuid);
      record.put(
          "effectiveProperties",
          Map.of(
              "cachePrepStmts",
              admission.policy() == Policy.CANDIDATE_TRUE,
              "useServerPrepStmts",
              false,
              "prepStmtCacheSize",
              25,
              "prepStmtCacheSqlLimit",
              256));
      Map<String, Object> snapshot =
          "completion".equals(kind) ? audit.terminalSnapshot() : audit.snapshot();
      record.put("audit", snapshot);
      byte[] bytes = JSON.writeValueAsBytes(record);
      if (bytes.length > 131_072) throw invalid("SIDECAR_BOUND");
      Files.write(
          directory.resolve(kind + ".queryinfo-cache.json"), bytes, StandardOpenOption.CREATE_NEW);
      return true;
    } catch (Exception | Error failure) {
      audit.fail("SIDECAR_PUBLICATION");
      return false;
    }
  }

  /**
   * Constant-space checked counters and a sequence-ordered rolling digest, without raw references.
   */
  static final class Audit {
    /** Finite mandatory counter vocabulary, independent of the pool's maximum size. */
    private final Map<String, Long> counters = new LinkedHashMap<>();

    /** At most sixteen scalar event samples; truncation never stops validation/counting. */
    private final List<Map<String, Object>> samples = new ArrayList<>();

    /** Strict event ordering for the digest, checked against long overflow. */
    private long sequence;

    /** Digest of the preceding digest, event sequence, event name and canonical scalar facts. */
    private byte[] digest = new byte[32];

    /** First finite audit failure code is sticky and cannot be washed back to OK. */
    private String failureCode;

    /** Initialize the fixed vocabulary, never a physical-connection collection. */
    Audit() {
      for (String name :
          List.of(
              "created",
              "acquisitionFailed",
              "validated",
              "delivered",
              "rejected",
              "deliveredClosed",
              "rejectedClosed",
              "closeFailedAttempts",
              "inFlight")) counters.put(name, 0L);
    }

    /** Begin one acquisition before the raw factory can be called. */
    synchronized void begin() throws SQLException {
      transition("ACQUIRE", Map.of("inFlight", 1L), Map.of());
    }

    /** Count only a successful raw return and allocate its scalar ordinal. */
    synchronized long created() throws SQLException {
      try {
        transition("CREATED", Map.of("created", 1L), Map.of());
      } catch (SQLException failure) {
        counters.putAll(changed(Map.of("created", 1L)));
        throw failure;
      }
      return counters.get("created");
    }

    /** Atomically prepare ordered evidence and checked counters before publishing a transition. */
    synchronized void transition(String event, Map<String, Long> changes, Map<String, ?> facts)
        throws SQLException {
      try {
        long nextSequence = Math.incrementExact(sequence);
        Map<String, Long> next = changed(changes);
        Consumer<String> hook = AUDIT_HOOK.get();
        if (hook != null) hook.accept(event);
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sequence", nextSequence);
        sample.put("event", event);
        sample.put("facts", Map.copyOf(facts));
        sample.put("threadId", Thread.currentThread().getId());
        String threadName = Thread.currentThread().getName();
        sample.put("threadName", threadName.substring(0, Math.min(128, threadName.length())));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(digest);
        sha.update(JSON.writeValueAsBytes(sample));
        byte[] nextDigest = sha.digest();
        counters.putAll(next);
        sequence = nextSequence;
        digest = nextDigest;
        if (samples.size() < 16) samples.add(java.util.Collections.unmodifiableMap(sample));
      } catch (Exception | Error failure) {
        fail(failure instanceof ArithmeticException ? "COUNTER_OVERFLOW" : "AUDIT_PREPARATION");
        throw new SQLException("CM21_QUERYINFO_" + failureCode, failure);
      }
    }

    /** Compute all counter deltas before any counter becomes visible. */
    private Map<String, Long> changed(Map<String, Long> changes) {
      Map<String, Long> next = new LinkedHashMap<>();
      for (var change : changes.entrySet()) {
        long value =
            Math.addExact(Objects.requireNonNull(counters.get(change.getKey())), change.getValue());
        if (value < 0) throw new ArithmeticException("negative audit counter");
        next.put(change.getKey(), value);
      }
      return next;
    }

    /**
     * Preserve a primary throwable while retaining best-effort honest counters if digesting fails.
     */
    synchronized void safely(
        String event, Map<String, Long> changes, Map<String, ?> facts, Throwable primary) {
      try {
        transition(event, changes, facts);
      } catch (SQLException auditFailure) {
        primary.addSuppressed(auditFailure);
        try {
          counters.putAll(changed(changes));
        } catch (RuntimeException overflow) {
          fail("COUNTER_OVERFLOW");
          primary.addSuppressed(overflow);
        }
      }
      fail(
          event.contains("CLOSE_FAILED")
              ? "PHYSICAL_CLOSE"
              : event.equals("ACQUISITION_FAILED") ? "ACQUISITION" : "RAW_REJECTED");
    }

    /**
     * Successful physical close is counted even when its evidence preparation subsequently fails.
     */
    synchronized void completedClose(boolean delivered, long ordinal) throws SQLException {
      Map<String, Long> changes = Map.of(delivered ? "deliveredClosed" : "rejectedClosed", 1L);
      try {
        transition(
            delivered ? "DELIVERED_CLOSED" : "REJECTED_CLOSED",
            changes,
            Map.of("connectionOrdinal", ordinal));
      } catch (SQLException failure) {
        counters.putAll(changed(changes));
        throw failure;
      }
    }

    /** First finite failure is sticky; no exception/cause text can grow the evidence schema. */
    synchronized void fail(String code) {
      if (failureCode == null) failureCode = code;
    }

    /** Copy only finite scalar counters, bounded samples and the current digest under one lock. */
    synchronized Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.putAll(counters);
      long closed = Math.addExact(counters.get("deliveredClosed"), counters.get("rejectedClosed"));
      long open = Math.subtractExact(counters.get("delivered"), counters.get("deliveredClosed"));
      long unreleased =
          Math.addExact(
              open, Math.subtractExact(counters.get("rejected"), counters.get("rejectedClosed")));
      result.put("closed", closed);
      result.put("open", open);
      result.put("unreleased", unreleased);
      result.put("sequence", sequence);
      result.put("orderedSha256", HexFormat.of().formatHex(digest));
      result.put("sampleLimit", 16);
      result.put("samples", List.copyOf(samples));
      result.put("sampleTruncated", sequence > 16);
      result.put("status", failureCode == null ? "OK" : "FAILED");
      result.put("failureCode", failureCode);
      return result;
    }

    /** Only a quiescent, fully released normal terminal audit can qualify a completion sidecar. */
    synchronized void requireQuiescent() throws SQLException {
      Map<String, Object> snapshot = snapshot();
      if (failureCode != null
          || counters.get("inFlight") != 0
          || counters.get("created")
              != Math.addExact(counters.get("delivered"), counters.get("rejected"))
          || counters.get("delivered") > counters.get("validated")
          || counters.get("validated") > counters.get("created")
          || !Long.valueOf(0).equals(snapshot.get("unreleased")))
        throw new SQLException("CM21_QUERYINFO_TERMINAL_NOT_QUALIFIED");
    }

    /** Validate and copy the same terminal state while holding one audit lock throughout. */
    synchronized Map<String, Object> terminalSnapshot() throws SQLException {
      requireQuiescent();
      return snapshot();
    }
  }

  /** Accept only exact booleans rather than permissive Boolean.parseBoolean. */
  private static boolean bool(String value, String code) {
    if (!"true".equals(value) && !"false".equals(value)) throw invalid(code);
    return "true".equals(value);
  }

  /** Refuse unbounded metadata strings rather than allowing evidence size to grow with input. */
  private static String bounded(String value) throws SQLException {
    if (value == null || value.length() > 1_024)
      throw new SQLException("CM21_QUERYINFO_METADATA_BOUND");
    return value;
  }

  /** Keep admission failures finite and reviewable without including untrusted property text. */
  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException("CM21_QUERYINFO_" + code);
  }
}
