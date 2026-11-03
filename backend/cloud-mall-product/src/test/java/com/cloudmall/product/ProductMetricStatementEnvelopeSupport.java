package com.cloudmall.product;

import com.cloudmall.product.mapper.ProductMetricMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only bounded same-Mapper-call client envelopes, never server SQL or commit timing. */
final class ProductMetricStatementEnvelopeSupport {
  /** Sole new strict default-off observation flag. */
  static final String FLAG = "cloudmall.cm21.single10.statement-envelope-observation";

  /** Exact first original SQL; no normalization or prefix classification is permitted. */
  static final String STAT_UPDATE_SQL =
      "UPDATE product_online_stat SET"
          + " view_count=?,search_count=?,sold_count=?,hot_score=?,metric_version=?,last_event_id=?,updated_at=?"
          + " WHERE product_id=?";

  /** Exact second original SQL, whose returned row count is not a business gate. */
  static final String PROJECTION_INSERT_SQL =
      "INSERT INTO"
          + " product_metric_projection_outbox(product_id,metric_version,created_at,updated_at,next_retry_at)"
          + " VALUES(?,?,?,?,?)";

  /** Original histogram boundaries, plus one separate overflow bucket. */
  private static final long[] BOUNDS = {
    100_000L,
    500_000L,
    1_000_000L,
    2_000_000L,
    5_000_000L,
    10_000_000L,
    20_000_000L,
    50_000_000L,
    100_000_000L,
    200_000_000L,
    500_000_000L,
    1_000_000_000L,
    3_000_000_000L
  };

  /** Fixed marker names; raw timestamps are never exported. */
  private static final List<String> MARKERS = List.of("M0", "U0", "U1", "P0", "P1", "M1");

  /** Fixed normal-full-pair segments. */
  private static final List<String> SEGMENTS =
      List.of("PRE", "STAT_CALL", "GAP", "PROJECTION_CALL", "POST");

  /** All counters have fixed positions and checked arithmetic. */
  private static final List<String> COUNTERS =
      List.of(
          "activeFrames",
          "admittedFrames",
          "completedFrames",
          "normalFullPairs",
          "completeThrownAttempts",
          "partialFrames",
          "crossStageFrames",
          "skippedNoFrame",
          "skippedNested",
          "skippedCapacity",
          "skippedAfterFailure",
          "unexpectedSqlOrTemplate",
          "invalidSequence",
          "invalidTokenOrThread",
          "clockFailures",
          "counterOverflow",
          "observerFailures");

  /** Bounded cold JSON encoder, never used on a business invocation. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Independently published cold boundaries. */
  enum SnapshotKind {
    BASELINE,
    MEASUREMENT_ENDED,
    FINISH,
    COMPLETION
  }

  /** Finite sticky error vocabulary, independent from all existing collectors. */
  private enum Failure {
    NONE,
    CLOCK_INVALID,
    COUNTER_OVERFLOW,
    TOKEN_INVALID,
    UNEXPECTED_TEMPLATE_OR_SQL,
    REENTRY_OR_SEQUENCE,
    OBSERVER_FAILED,
    SIDECAR_PUBLICATION
  }

  /** Finite hot state machine, never inferred from SQL arguments. */
  private enum Phase {
    RESERVED,
    EXPECT_U,
    U_RUNNING,
    WAIT_P,
    P_RUNNING,
    EXPECT_M1,
    KNOWN_EARLY_STOP,
    THREW_ATTEMPT,
    CLOSED
  }

  /** Immutable actor-free qualification with prerequisite observer decisions. */
  record Admission(
      boolean enabled,
      String request,
      String role,
      int seeds,
      ProductMetricPipelineLoadSupport.Mode mode,
      boolean batch,
      int consumers,
      boolean phaseTiming,
      boolean phaseAnchor,
      boolean applicationTiming,
      boolean callbackObservation,
      boolean transactionObservation) {
    /** Reject forged enabled values and unsupported shapes. */
    Admission {
      boolean requested = strictRequest(request);
      if (enabled != requested) throw invalid("ADMISSION_MISMATCH");
      if (enabled
          && (!ProductMetricPipelineLoadSupport.ROLE.equals(role)
              || seeds != 300
              || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
              || !batch
              || consumers != 1
              || phaseTiming
              || phaseAnchor
              || !applicationTiming
              || !callbackObservation
              || !transactionObservation)) throw invalid("SINGLE10_BATCH1_OBSERVERS_REQUIRED");
    }
  }

  /** Parse this flag first; disabled entry never reads prerequisite properties. */
  static Admission captureAdmission(
      String role, int seeds, ProductMetricPipelineLoadSupport.Mode mode) {
    String request = System.getProperty(FLAG);
    if (!strictRequest(request)) return disabled(request, role, seeds, mode);
    return new Admission(
        true,
        request,
        role,
        seeds,
        mode,
        property("cloudmall.cm21.pipeline-batch"),
        Integer.parseInt(System.getProperty("cloudmall.cm21.metric-consumers", "1")),
        property("cloudmall.cm21.phase-timing"),
        property("cloudmall.cm21.phase-anchor"),
        property("cloudmall.cm21.single10.application-timing"),
        property("cloudmall.cm21.single10.callback-service-observation"),
        property("cloudmall.cm21.single10.service-transaction-observation"));
  }

  /** Reuse captured cache shape rather than re-reading mutable shape properties. */
  static Admission captureAdmission(ProductMetricQueryInfoCacheSupport.Admission shape) {
    Objects.requireNonNull(shape);
    String request = System.getProperty(FLAG);
    if (!strictRequest(request))
      return disabled(request, shape.role(), shape.seeds(), shape.mode());
    return new Admission(
        true,
        request,
        shape.role(),
        shape.seeds(),
        shape.mode(),
        shape.batch(),
        shape.consumers(),
        strictBoolean(shape.phaseTiming()),
        strictBoolean(shape.phaseAnchor()),
        property("cloudmall.cm21.single10.application-timing"),
        property("cloudmall.cm21.single10.callback-service-observation"),
        property("cloudmall.cm21.single10.service-transaction-observation"));
  }

  /** Check frozen qualification against the actual controlled construction context. */
  static Admission requireContext(
      Admission admission,
      String role,
      int seeds,
      ProductMetricPipelineLoadSupport.Mode mode,
      boolean batch,
      int consumers,
      boolean phaseTiming,
      boolean phaseAnchor) {
    Objects.requireNonNull(admission);
    if (admission.enabled()
        && (!Objects.equals(admission.role(), role)
            || admission.seeds() != seeds
            || admission.mode() != mode
            || admission.batch() != batch
            || admission.consumers() != consumers
            || admission.phaseTiming() != phaseTiming
            || admission.phaseAnchor() != phaseAnchor)) throw invalid("CONTEXT_MISMATCH");
    return admission;
  }

  /** Disabled immutable data creates no collector, clock, TLS, Bean or actor. */
  private static Admission disabled(
      String request, String role, int seeds, ProductMetricPipelineLoadSupport.Mode mode) {
    return new Admission(
        false, request, role, seeds, mode, false, 0, false, false, false, false, false);
  }

  /** Only missing, exact false and exact true are legal. */
  private static boolean strictRequest(String request) {
    return request != null && strictBoolean(request);
  }

  /** Strict boolean parsing rejects whitespace and case variants. */
  private static boolean strictBoolean(String value) {
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw invalid("BOOLEAN_INVALID");
  }

  /** Prerequisite reads occur only on enabled capture. */
  private static boolean property(String name) {
    return strictBoolean(System.getProperty(name, "false"));
  }

  /** Stable admission exceptions contain no business payload. */
  private static IllegalStateException invalid(String reason) {
    return new IllegalStateException("CM21_STATEMENT_ENVELOPE_" + reason);
  }

  /** Opaque same-owner scope capability, never a business payload. */
  static final class ScopeToken {
    /** Creating collector. */
    private final ProductMetricStatementEnvelopeSupport owner;

    /** Creating thread ID, not a retained Thread object. */
    private final long threadId;

    /** One bounded primitive-only frame. */
    private final Frame frame;

    /** Nested scopes cannot own outer cleanup. */
    private final boolean nested;

    /** At most one close. */
    private boolean closed;

    /** Bind immutable capability identity. */
    private ScopeToken(ProductMetricStatementEnvelopeSupport owner, Frame frame, boolean nested) {
      this.owner = owner;
      threadId = Thread.currentThread().getId();
      this.frame = frame;
      this.nested = nested;
    }
  }

  /** Opaque statement capability never retains SQL or arguments. */
  static final class StatementToken {
    /** Creating collector. */
    private final ProductMetricStatementEnvelopeSupport owner;

    /** One admitted original Mapper frame. */
    private final Frame frame;

    /** Creating thread ID. */
    private final long threadId;

    /** Fixed role, STAT=0 or PROJECTION=1. */
    private final int role;

    /** Duplicate exit detection. */
    private boolean ended;

    /** Bind only role and existing frame. */
    private StatementToken(ProductMetricStatementEnvelopeSupport owner, Frame frame, int role) {
      this.owner = owner;
      this.frame = frame;
      this.role = role;
      threadId = Thread.currentThread().getId();
    }
  }

  /** At most one live frame globally and on its creating thread; no business references. */
  private static final class Frame {
    /** Entry coordinate before Mapper outcome is known. */
    private final int coordinate;

    /** Original Service entry stage. */
    private final ProductMetricPipelineLoadSupport.Stage entryStage;

    /** Monotonic call sequence. */
    private final long sequence;

    /** Original creating thread ID. */
    private final long threadId = Thread.currentThread().getId();

    /** Six arbitrary-origin clock readings, never exported. */
    private final long[] markers = new long[6];

    /** Presence of actual readings. */
    private final boolean[] markerPresent = new boolean[6];

    /** Completed assignable role attempts only. */
    private final boolean[] roleEnded = new boolean[2];

    /** Local role outcomes. */
    private final boolean[] roleThrew = new boolean[2];

    /** Returned row counts, retained only through finalization. */
    private final int[] rows = new int[2];

    /** Current fixed sequence state. */
    private Phase phase = Phase.RESERVED;

    /** Missing or invalid observation prohibits a full pair. */
    private volatile boolean invalid;

    /** Known cross-stage marker subset. */
    private boolean crossed;

    /** Suppression includes untimed nested Mapper entries. */
    private int nestedDepth;

    /** Outcome is known only after original Mapper ends. */
    private boolean outcomeKnown;

    /** Actual original Mapper outcome. */
    private boolean mapperThrew;

    /** Terminal commit or failed cleanup already happened. */
    private boolean finalized;

    /** Freeze primitive semantic coordinates before markers. */
    private Frame(
        ProductMetricPipelineLoadSupport.Stage stage,
        int operationIndex,
        int inputSize,
        long sequence) {
      entryStage = stage;
      coordinate = (stage.ordinal() * 2 + operationIndex) * 4 + inputSize - 1;
      this.sequence = sequence;
    }
  }

  /** Original owner-mode identity. */
  private final String runIdentity;

  /** Elapsed clock; constructor never reads it. */
  private final LongSupplier clock;

  /** Fixed cold-bound actual original stage supplier. */
  private final Supplier<ProductMetricPipelineLoadSupport.Stage> stageSupplier;

  /** Sole current frame, removed by the creating owner's finally. */
  private final ThreadLocal<Frame> currentFrame = new ThreadLocal<>();

  /** Fixed finalized outcome cells. */
  private final Cell[] cells = new Cell[112];

  /** Checked aggregate counters, shared with snapshot under a short monitor. */
  private long[] counts = new long[COUNTERS.size()];

  /** First observational error only. */
  private Failure failureCode = Failure.NONE;

  /** Sole original injected template permitted in a frame. */
  private JdbcTemplate template;

  /** Cold identity facts remain unproved until explicitly checked. */
  private Map<String, Object> runtimeFacts = unverifiedRuntime();

  /** Optional observational failure leaf, never a replacement observer implementation. */
  private Consumer<String> observerHook;

  /** Allocate enabled bounded state without IO or clock reads. */
  ProductMetricStatementEnvelopeSupport(
      String runIdentity,
      ProductMetricPipelineLoadSupport.Mode mode,
      LongSupplier clock,
      Supplier<ProductMetricPipelineLoadSupport.Stage> stageSupplier) {
    if (runIdentity == null
        || !runIdentity.matches("[0-9a-f]{32}-batch1")
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1)
      throw invalid("IDENTITY_OR_MODE_INVALID");
    this.runIdentity = runIdentity;
    this.clock = Objects.requireNonNull(clock);
    this.stageSupplier = Objects.requireNonNull(stageSupplier);
    for (int index = 0; index < cells.length; index++) cells[index] = new Cell();
  }

  /** Install a controlled observer-only fault leaf on this enabled helper. */
  void setObserverHook(Consumer<String> hook) {
    observerHook = hook;
  }

  /** Report wiring metadata failures only to this new collector. */
  void recordObserverFailure() {
    Frame frame = currentFrame.get();
    if (frame != null) frame.invalid = true;
    failure(Failure.OBSERVER_FAILED, 16);
  }

  /** Register every original Mapper entry, including untimed nested entries. */
  ScopeToken openMapperScope(
      boolean target,
      ProductMetricPipelineLoadSupport.Stage stage,
      int operationIndex,
      int inputSize) {
    Frame frame = null;
    boolean reserved = false;
    try {
      Frame existing = currentFrame.get();
      if (existing != null) {
        existing.invalid = true;
        existing.nestedDepth = Math.addExact(existing.nestedDepth, 1);
        increment(8);
        failure(Failure.REENTRY_OR_SEQUENCE, 12);
        return new ScopeToken(this, existing, true);
      }
      if (!target) return null;
      if (stage == null
          || operationIndex < 0
          || operationIndex > 1
          || inputSize < 1
          || inputSize > 4) {
        increment(7);
        return null;
      }
      synchronized (this) {
        if (failureCode != Failure.NONE) {
          incrementLocked(10);
          return null;
        }
        if (counts[0] != 0) {
          incrementLocked(9);
          return null;
        }
        long admitted = Math.addExact(counts[1], 1);
        frame = new Frame(stage, operationIndex, inputSize, admitted);
        counts[1] = admitted;
        counts[0] = 1;
        reserved = true;
      }
      currentFrame.set(frame);
      return new ScopeToken(this, frame, false);
    } catch (Throwable fault) {
      if (frame != null) frame.invalid = true;
      observerFailure(frame, fault);
      if (reserved) {
        synchronized (this) {
          counts[0] = 0;
        }
        currentFrame.remove();
      }
      return null;
    }
  }

  /** Own M0 sits immediately before original proceed, outside the collector monitor. */
  void beforeMapperProceed(ScopeToken token) {
    if (token == null || token.nested) return;
    try {
      if (!valid(token)) return;
      if (token.frame.phase != Phase.RESERVED || token.frame.markerPresent[0]) {
        token.frame.invalid = true;
        failure(Failure.REENTRY_OR_SEQUENCE, 12);
        return;
      }
      mark(token.frame, 0);
      token.frame.phase = Phase.EXPECT_U;
    } catch (Throwable fault) {
      observerFailure(token.frame, fault);
    }
  }

  /** Own M1 follows original return/throw, then assigns its actual finalized outcome. */
  void afterMapperProceed(ScopeToken token, boolean threw) {
    if (token == null || token.nested) return;
    Frame frame = token.frame;
    try {
      if (!valid(token)) return;
      if (frame.outcomeKnown) {
        frame.invalid = true;
        failure(Failure.REENTRY_OR_SEQUENCE, 12);
        return;
      }
      frame.outcomeKnown = true;
      frame.mapperThrew = threw;
      mark(frame, 5);
      if (frame.phase != Phase.EXPECT_M1
          && frame.phase != Phase.KNOWN_EARLY_STOP
          && frame.phase != Phase.THREW_ATTEMPT) {
        frame.invalid = true;
        failure(Failure.REENTRY_OR_SEQUENCE, 12);
      }
    } catch (Throwable fault) {
      observerFailure(frame, fault);
    }
  }

  /** Release only this creating owner's TLS and global slot despite observation faults. */
  void closeMapperScope(ScopeToken token) {
    if (token == null) return;
    Frame frame = token.frame;
    try {
      if (!valid(token)) return;
      token.closed = true;
      if (token.nested) {
        frame.nestedDepth--;
        return;
      }
      try {
        hook("closeMapperScope");
      } catch (Throwable fault) {
        observerFailure(frame, fault);
      }
      if (!frame.outcomeKnown) {
        frame.invalid = true;
        failure(Failure.OBSERVER_FAILED, 16);
      }
    } catch (Throwable fault) {
      observerFailure(frame, fault);
    } finally {
      if (token.owner == this
          && token.threadId == Thread.currentThread().getId()
          && !token.nested
          && currentFrame.get() == frame) {
        currentFrame.remove();
        if (frame.outcomeKnown) commit(frame);
        else
          synchronized (this) {
            counts[0] = 0;
            frame.finalized = true;
            frame.phase = Phase.CLOSED;
          }
      }
    }
  }

  /** Associate only exact target SQL on the unique template in an admitted Mapper frame. */
  StatementToken beforeTemplateCall(JdbcTemplate caller, String sql) {
    Frame frame = null;
    try {
      int role = STAT_UPDATE_SQL.equals(sql) ? 0 : PROJECTION_INSERT_SQL.equals(sql) ? 1 : -1;
      frame = currentFrame.get();
      if (frame == null) {
        if (role >= 0) increment(7);
        return null;
      }
      if (frame.nestedDepth != 0) {
        frame.invalid = true;
        return null;
      }
      if (role < 0 || caller == null || template == null || caller != template) {
        frame.invalid = true;
        failure(Failure.UNEXPECTED_TEMPLATE_OR_SQL, 11);
        return null;
      }
      Phase expected = role == 0 ? Phase.EXPECT_U : Phase.WAIT_P;
      if (frame.phase != expected) {
        frame.invalid = true;
        failure(Failure.REENTRY_OR_SEQUENCE, 12);
        return null;
      }
      hook("beforeTemplateCall");
      frame.phase = role == 0 ? Phase.U_RUNNING : Phase.P_RUNNING;
      StatementToken token = new StatementToken(this, frame, role);
      mark(frame, role == 0 ? 1 : 3);
      return token;
    } catch (Throwable fault) {
      observerFailure(frame, fault);
      return null;
    }
  }

  /** Observe a completed original super call without changing its result or exception. */
  void afterTemplateCall(StatementToken token, boolean threw, int returnedRows) {
    if (token == null) return;
    Frame frame = token.frame;
    try {
      if (token.owner != this
          || token.threadId != Thread.currentThread().getId()
          || currentFrame.get() != frame
          || token.ended) {
        frame.invalid = true;
        failure(Failure.TOKEN_INVALID, 13);
        return;
      }
      token.ended = true;
      hook("afterTemplateCall");
      frame.roleEnded[token.role] = true;
      frame.roleThrew[token.role] = threw;
      frame.rows[token.role] = returnedRows;
      mark(frame, token.role == 0 ? 2 : 4);
      frame.phase =
          token.role == 0
              ? (threw || returnedRows != 1 ? Phase.KNOWN_EARLY_STOP : Phase.WAIT_P)
              : (threw ? Phase.THREW_ATTEMPT : Phase.EXPECT_M1);
    } catch (Throwable fault) {
      observerFailure(frame, fault);
    }
  }

  /** Preserve natural JDBC exception propagation and override only the exact varargs method. */
  static final class ObservedJdbcTemplate extends JdbcTemplate {
    /** One existing enabled collector. */
    private final ProductMetricStatementEnvelopeSupport observation;

    /** Bind the same datasource without opening a connection or translator. */
    ObservedJdbcTemplate(DataSource dataSource, ProductMetricStatementEnvelopeSupport observation) {
      super(dataSource);
      this.observation = Objects.requireNonNull(observation);
      observation.bindTemplate(this);
    }

    /** Delegate the same String and Object[] reference to original Spring exactly once. */
    @Override
    public int update(String sql, Object... args) {
      StatementToken token = observation.beforeTemplateCall(this, sql);
      boolean threw = true;
      int returnedRows = 0;
      try {
        returnedRows = super.update(sql, args);
        threw = false;
        return returnedRows;
      } finally {
        observation.afterTemplateCall(token, threw, returnedRows);
      }
    }
  }

  /** Mirror Boot 2.7.18 ordering and original Java narrowing semantics exactly. */
  static JdbcTemplate createTemplate(
      DataSource dataSource,
      JdbcProperties properties,
      ProductMetricStatementEnvelopeSupport observation) {
    JdbcTemplate result = new ObservedJdbcTemplate(dataSource, observation);
    JdbcProperties.Template configuration = properties.getTemplate();
    result.setFetchSize(configuration.getFetchSize());
    result.setMaxRows(configuration.getMaxRows());
    if (configuration.getQueryTimeout() != null)
      result.setQueryTimeout((int) configuration.getQueryTimeout().getSeconds());
    return result;
  }

  /** A second observed template cannot silently join this collector. */
  private synchronized void bindTemplate(JdbcTemplate candidate) {
    if (template == null) template = candidate;
    else if (template != candidate) failure(Failure.UNEXPECTED_TEMPLATE_OR_SQL, 11);
  }

  /** Prove cold reference identities and logger switches without translator or connection IO. */
  boolean verifyRuntime(ApplicationContext context, Object mapperTarget, DataSource original) {
    try {
      JdbcTemplate actual = context.getBean("jdbcTemplate", JdbcTemplate.class);
      Object injected = ReflectionTestUtils.getField(mapperTarget, "jdbc");
      DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
      JdbcTemplate baseline = new JdbcTemplate(original);
      Map<String, Object> vector = loggerVector(actual);
      boolean loggerSame = vector.equals(loggerVector(baseline));
      boolean mapperSame = injected == actual;
      boolean mapperData =
          mapperTarget instanceof ProductMetricMapper
              && ((ProductMetricMapper) mapperTarget).dataSource() == original;
      boolean templateData = actual.getDataSource() == original;
      boolean managerData = manager.getDataSource() == original;
      boolean originalSame = context.getBean(DataSource.class) == original;
      boolean unique =
          context.getBeansOfType(JdbcTemplate.class).size() == 1
              && context.getBeansOfType(DataSource.class).size() == 1
              && context.getBeansOfType(DataSourceTransactionManager.class).size() == 1;
      boolean verified =
          actual == template
              && actual.getClass() == ObservedJdbcTemplate.class
              && mapperSame
              && mapperData
              && templateData
              && managerData
              && originalSame
              && unique
              && loggerSame
              && actual.isIgnoreWarnings() == baseline.isIgnoreWarnings();
      Map<String, Object> facts = unverifiedRuntime();
      facts.put("runtimeVerified", verified);
      facts.put("templateClassName", actual.getClass().getName());
      facts.put("templateDataSourceSame", templateData);
      facts.put("mapperTemplateSame", mapperSame);
      facts.put("mapperDataSourceSame", mapperData);
      facts.put("transactionManagerDataSourceSame", managerData);
      facts.put("baseDataSourceUnchanged", originalSame);
      facts.put("fetchSize", actual.getFetchSize());
      facts.put("maxRows", actual.getMaxRows());
      facts.put("queryTimeout", actual.getQueryTimeout());
      facts.put("ignoreWarnings", actual.isIgnoreWarnings());
      facts.put("loggerEnablementSame", loggerSame);
      facts.put("loggerEnablement", vector);
      synchronized (this) {
        runtimeFacts = facts;
      }
      if (!verified) failure(Failure.OBSERVER_FAILED, 16);
      return verified;
    } catch (Throwable fault) {
      failure(Failure.OBSERVER_FAILED, 16);
      return false;
    }
  }

  /** Read logger switches without changing logger settings or touching JDBC. */
  private static Map<String, Object> loggerVector(JdbcTemplate jdbc) {
    Log logger = (Log) ReflectionTestUtils.getField(jdbc, "logger");
    return ordered(
        "trace",
        logger.isTraceEnabled(),
        "debug",
        logger.isDebugEnabled(),
        "info",
        logger.isInfoEnabled(),
        "warn",
        logger.isWarnEnabled(),
        "error",
        logger.isErrorEnabled(),
        "fatal",
        logger.isFatalEnabled());
  }

  /** Unproved fixed shape; false means absence of proof. */
  private static Map<String, Object> unverifiedRuntime() {
    return ordered(
        "runtimeVerified",
        false,
        "templateClassName",
        null,
        "templateBeanName",
        "jdbcTemplate",
        "templateDataSourceSame",
        false,
        "mapperTemplateSame",
        false,
        "mapperDataSourceSame",
        false,
        "transactionManagerDataSourceSame",
        false,
        "baseDataSourceUnchanged",
        false,
        "additionalDataSourceCreated",
        false,
        "transactionManagerAdded",
        false,
        "jdbcTemplateProxyAdded",
        false,
        "overriddenMethod",
        "update(String,Object[])",
        "sqlRoleSha256",
        ordered(
            "STAT_UPDATE",
            "653f7fbc406d2e3cb2243cc5d012de5b3335f649008f36d8024e387c0e4e5ab5",
            "PROJECTION_INSERT",
            "c5f485d5604c3411e49280df5ec5dccee8f9bb5ad3cf6024f9e1c2089a30679d"),
        "fetchSize",
        null,
        "maxRows",
        null,
        "queryTimeout",
        null,
        "ignoreWarnings",
        true,
        "loggerEnablementSame",
        false,
        "loggerEnablement",
        ordered(
            "trace", false, "debug", false, "info", false, "warn", false, "error", false, "fatal",
            false));
  }

  /** Export bounded aggregates without a clock read, connection or raw timestamp. */
  Map<String, Object> snapshot(SnapshotKind kind) {
    try {
      hook("snapshot");
    } catch (Throwable fault) {
      failure(Failure.OBSERVER_FAILED, 16);
    }
    synchronized (this) {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("failureCode", failureCode.name());
      for (int index = 0; index < counts.length; index++)
        state.put(COUNTERS.get(index), counts[index]);
      List<Object> exported = new ArrayList<>(112);
      for (int stage = 0; stage < 7; stage++)
        for (int operation = 0; operation < 2; operation++)
          for (int size = 1; size <= 4; size++)
            for (int outcome = 0; outcome < 2; outcome++) {
              Cell cell = cells[(((stage * 2 + operation) * 4 + size - 1) * 2) + outcome];
              Map<String, Object> row =
                  ordered(
                      "entryStage",
                      ProductMetricPipelineLoadSupport.Stage.values()[stage].name(),
                      "operation",
                      operation == 0 ? "apply" : "applyBatch",
                      "inputSize",
                      size,
                      "mapperOutcome",
                      outcome == 0 ? "NORMAL" : "THREW",
                      "completedFrames",
                      cell.completed,
                      "normalFullPairs",
                      cell.normal,
                      "completeThrownAttempts",
                      cell.thrown,
                      "partialFrames",
                      cell.partial,
                      "crossStageFrames",
                      cell.crossed,
                      "wholeMapperStats",
                      cell.whole.export(),
                      "normalFullWholeStats",
                      cell.full.export());
              Map<String, Object> segments = new LinkedHashMap<>();
              for (int index = 0; index < 5; index++)
                segments.put(SEGMENTS.get(index), cell.segments[index].export());
              row.put("segments", segments);
              row.put(
                  "statementRoles",
                  ordered(
                      "STAT_UPDATE",
                      cell.roles[0].export(),
                      "PROJECTION_INSERT",
                      cell.roles[1].export()));
              exported.add(row);
            }
      boolean complete =
          failureCode == Failure.NONE
              && counts[0] == 0
              && counts[2] == counts[3]
              && counts[4] == 0
              && counts[5] == 0
              && counts[7] == 0
              && counts[8] == 0
              && counts[9] == 0
              && counts[10] == 0;
      boolean closed =
          (kind == SnapshotKind.FINISH || kind == SnapshotKind.COMPLETION)
              && counts[0] == 0
              && currentFrame.get() == null;
      return ordered(
          "markerNames",
          MARKERS,
          "segmentNames",
          SEGMENTS,
          "histogramUpperBoundsNs",
          BOUNDS.clone(),
          "runtimeFacts",
          exportRuntimeFacts(),
          "collectorState",
          state,
          "cells",
          exported,
          "qualification",
          ordered(
              "collectorHealthy",
              failureCode == Failure.NONE,
              "pairedFullCoverage",
              complete ? "COMPLETE" : "PARTIAL",
              "closedQuiescent",
              closed,
              "runtimeQualification",
              false,
              "serverSqlMeasured",
              false,
              "commitMeasured",
              false,
              "physicalAckMeasured",
              false,
              "observerOverheadUnknown",
              true));
    }
  }

  /** Copy both nested fact maps so a cold snapshot caller cannot mutate later evidence. */
  private Map<String, Object> exportRuntimeFacts() {
    Map<String, Object> facts = new LinkedHashMap<>(runtimeFacts);
    facts.put(
        "loggerEnablement", new LinkedHashMap<>((Map<?, ?>) runtimeFacts.get("loggerEnablement")));
    facts.put("sqlRoleSha256", new LinkedHashMap<>((Map<?, ?>) runtimeFacts.get("sqlRoleSha256")));
    return facts;
  }

  /** Publish an independent CREATE_NEW sidecar; failure cannot mask original cleanup exceptions. */
  void publish(
      Path directory,
      SnapshotKind kind,
      String owner,
      String mode,
      String expectedRunIdentity,
      String runtimeIdentity) {
    try {
      hook("publish");
      if (!runIdentity.equals(expectedRunIdentity)) throw invalid("PUBLISH_IDENTITY");
      String filename =
          switch (kind) {
            case BASELINE -> "baseline-10.statement-envelope.json";
            case MEASUREMENT_ENDED -> "measurement-ended.statement-envelope.json";
            case FINISH -> "finish-10.statement-envelope.json";
            case COMPLETION -> "completion.statement-envelope.json";
          };
      Map<String, Object> envelope =
          ordered(
              "schemaVersion",
              1,
              "scope",
              "TEST_ONLY_STATEMENT_ENVELOPE_V1",
              "kind",
              kind.name(),
              "owner",
              owner,
              "mode",
              mode,
              "runIdentity",
              runIdentity,
              "runtimeIdentity",
              runtimeIdentity,
              "enabled",
              true,
              "observation",
              snapshot(kind));
      Files.write(
          directory.resolve(filename),
          JSON.writeValueAsBytes(envelope),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
    } catch (Throwable fault) {
      failure(Failure.SIDECAR_PUBLICATION, 16);
    }
  }

  /** Foreign thread ends cannot clear the original thread's TLS. */
  private boolean valid(ScopeToken token) {
    if (token.owner != this
        || token.threadId != Thread.currentThread().getId()
        || token.closed
        || currentFrame.get() != token.frame) {
      token.frame.invalid = true;
      failure(Failure.TOKEN_INVALID, 13);
      return false;
    }
    return true;
  }

  /** Clock and actual stage supplier calls remain outside the commit monitor. */
  private void mark(Frame frame, int index) {
    try {
      hook(
          index == 0
              ? "beforeMapperProceed"
              : index == 5 ? "afterMapperProceed" : "marker" + MARKERS.get(index));
    } catch (Throwable fault) {
      observerFailure(frame, fault);
      return;
    }
    try {
      frame.markers[index] = clock.getAsLong();
      frame.markerPresent[index] = true;
    } catch (Throwable fault) {
      frame.invalid = true;
      failure(Failure.CLOCK_INVALID, 14);
    }
    try {
      ProductMetricPipelineLoadSupport.Stage actual = stageSupplier.get();
      if (actual == null) frame.invalid = true;
      else if (actual != frame.entryStage) {
        frame.invalid = true;
        frame.crossed = true;
      }
    } catch (Throwable fault) {
      frame.invalid = true;
      failure(Failure.OBSERVER_FAILED, 16);
    }
  }

  /** Stage the whole cell and aggregate counts before one atomic commit and active release. */
  private void commit(Frame frame) {
    if (frame.finalized) return;
    try {
      hook("commit");
      boolean all = true;
      for (boolean present : frame.markerPresent) all &= present;
      long[] durations = new long[5];
      boolean sequential = all;
      long segmentSum = 0;
      if (all)
        for (int index = 0; index < 5; index++) {
          durations[index] = frame.markers[index + 1] - frame.markers[index];
          if (durations[index] < 0) sequential = false;
          try {
            segmentSum = Math.addExact(segmentSum, durations[index]);
          } catch (ArithmeticException fault) {
            sequential = false;
          }
        }
      long whole = frame.markers[5] - frame.markers[0];
      if (all && segmentSum != whole) sequential = false;
      boolean wholeValid = frame.markerPresent[0] && frame.markerPresent[5] && whole >= 0;
      if ((all && !sequential) || (frame.markerPresent[0] && frame.markerPresent[5] && whole < 0)) {
        frame.invalid = true;
        failure(Failure.CLOCK_INVALID, 14);
      }
      boolean pair =
          !frame.invalid
              && all
              && sequential
              && frame.roleEnded[0]
              && !frame.roleThrew[0]
              && frame.rows[0] == 1
              && frame.roleEnded[1];
      synchronized (this) {
        // Observer failures can occur on a cold-path thread or after this owner's TLS removal.
        // Freeze the eligibility decision at the same atomic point as the complete cell commit.
        boolean eligible = pair && !frame.invalid && failureCode == Failure.NONE;
        boolean normal = eligible && !frame.mapperThrew && !frame.roleThrew[1];
        boolean thrown = eligible && frame.mapperThrew && frame.roleThrew[1];
        int index = frame.coordinate * 2 + (frame.mapperThrew ? 1 : 0);
        Cell staged = new Cell(cells[index]);
        long[] totals = counts.clone();
        staged.completed = Math.addExact(staged.completed, 1);
        totals[2] = Math.addExact(totals[2], 1);
        int category = normal ? 3 : thrown ? 4 : 5;
        totals[category] = Math.addExact(totals[category], 1);
        if (normal) staged.normal = Math.addExact(staged.normal, 1);
        else if (thrown) staged.thrown = Math.addExact(staged.thrown, 1);
        else staged.partial = Math.addExact(staged.partial, 1);
        if (frame.crossed) {
          staged.crossed = Math.addExact(staged.crossed, 1);
          totals[6] = Math.addExact(totals[6], 1);
        }
        staged.whole.add(wholeValid, whole);
        if (normal) {
          staged.full.add(true, whole);
          for (int segment = 0; segment < 5; segment++)
            staged.segments[segment].add(true, durations[segment]);
        }
        for (int role = 0; role < 2; role++)
          if (frame.roleEnded[role]) {
            int first = role == 0 ? 1 : 3;
            long duration = frame.markers[first + 1] - frame.markers[first];
            boolean validDuration =
                frame.markerPresent[first] && frame.markerPresent[first + 1] && duration >= 0;
            staged.roles[role].add(
                frame.roleThrew[role], frame.rows[role], validDuration, duration);
          }
        totals[0] = 0;
        cells[index] = staged;
        counts = totals;
        frame.finalized = true;
        frame.phase = Phase.CLOSED;
      }
    } catch (Throwable fault) {
      observerFailure(frame, fault);
    } finally {
      if (!frame.finalized)
        synchronized (this) {
          counts[0] = 0;
          frame.finalized = true;
          frame.phase = Phase.CLOSED;
        }
    }
  }

  /** Short checked diagnostic mutation never surrounds business or clock calls. */
  private void increment(int index) {
    synchronized (this) {
      incrementLocked(index);
    }
  }

  /** Overflow makes the observer unavailable rather than saturated green. */
  private void incrementLocked(int index) {
    try {
      counts[index] = Math.addExact(counts[index], 1);
    } catch (ArithmeticException fault) {
      if (failureCode == Failure.NONE) failureCode = Failure.COUNTER_OVERFLOW;
      if (index != 15)
        try {
          counts[15] = Math.addExact(counts[15], 1);
        } catch (ArithmeticException ignored) {
          /* Failure already recorded; never saturate a value. */
        }
    }
  }

  /** Sticky first fixed failure and bounded diagnostic count. */
  private synchronized void failure(Failure code, int index) {
    if (failureCode == Failure.NONE) failureCode = code;
    incrementLocked(index);
  }

  /** Keep observational Throwables in this collector's finite failure domain. */
  private void observerFailure(Frame frame, Throwable fault) {
    if (frame != null) frame.invalid = true;
    failure(
        fault instanceof ArithmeticException ? Failure.COUNTER_OVERFLOW : Failure.OBSERVER_FAILED,
        fault instanceof ArithmeticException ? 15 : 16);
  }

  /** Actual observer-only fault leaf. */
  private void hook(String point) {
    Consumer<String> hook = observerHook;
    if (hook != null) hook.accept(point);
  }

  /** Fixed ordered map assembly for exact cold schemas. */
  private static Map<String, Object> ordered(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2)
      result.put((String) pairs[index], pairs[index + 1]);
    return result;
  }

  /** Fixed histogram sufficient statistics, mutated only in an unpublished cell copy. */
  private static final class Stats {
    /** Known completed attempts, including unknown durations. */
    private long observations;

    /** Attempts with a valid nonnegative duration. */
    private long timedCalls;

    /** Checked integer duration sum. */
    private long sum;

    /** Maximum timed sample, zero if none. */
    private long maximum;

    /** Thirteen inclusive buckets and one overflow bucket. */
    private final long[] buckets = new long[14];

    /** Construct empty statistics. */
    private Stats() {}

    /** Copy before visible aggregate changes. */
    private Stats(Stats previous) {
      observations = previous.observations;
      timedCalls = previous.timedCalls;
      sum = previous.sum;
      maximum = previous.maximum;
      System.arraycopy(previous.buckets, 0, buckets, 0, 14);
    }

    /** Unknown time stays unknown, never zero-filled. */
    private void add(boolean timed, long duration) {
      observations = Math.addExact(observations, 1);
      if (!timed) return;
      timedCalls = Math.addExact(timedCalls, 1);
      sum = Math.addExact(sum, duration);
      maximum = Math.max(maximum, duration);
      int bucket = 0;
      while (bucket < 13 && duration > BOUNDS[bucket]) bucket++;
      buckets[bucket] = Math.addExact(buckets[bucket], 1);
    }

    /** Cold fixed shape. */
    private Map<String, Object> export() {
      return ordered(
          "observations",
          observations,
          "timedCalls",
          timedCalls,
          "sumNs",
          sum,
          "maxNs",
          maximum,
          "buckets",
          buckets.clone());
    }
  }

  /** Only completed assignable attempts, with no live outcome guess. */
  private static final class Role {
    /** Completed returns. */
    private long returned;

    /** Completed throws. */
    private long threw;

    /** ZERO/ONE/OTHER returned row categories. */
    private final long[] rows = new long[3];

    /** Independent completed-attempt durations. */
    private Stats duration = new Stats();

    /** Construct empty role statistics. */
    private Role() {}

    /** Copy all bounded fields before update. */
    private Role(Role previous) {
      returned = previous.returned;
      threw = previous.threw;
      System.arraycopy(previous.rows, 0, rows, 0, 3);
      duration = new Stats(previous.duration);
    }

    /** Assign one actually completed attempt to known Mapper outcome. */
    private void add(boolean failed, int count, boolean timed, long elapsed) {
      if (failed) threw = Math.addExact(threw, 1);
      else {
        returned = Math.addExact(returned, 1);
        int category = count == 0 ? 0 : count == 1 ? 1 : 2;
        rows[category] = Math.addExact(rows[category], 1);
      }
      Math.addExact(returned, threw);
      duration.add(timed, elapsed);
    }

    /** A finalized role's inFlight is always zero. */
    private Map<String, Object> export() {
      return ordered(
          "entered",
          Math.addExact(returned, threw),
          "returned",
          returned,
          "threw",
          threw,
          "inFlight",
          0L,
          "returnedRows",
          ordered("ZERO", rows[0], "ONE", rows[1], "OTHER", rows[2]),
          "durationStats",
          duration.export());
    }
  }

  /** One finalized stage/operation/size/outcome cell. */
  private static final class Cell {
    /** All completed frames. */
    private long completed;

    /** NORMAL_ONLY full pairs. */
    private long normal;

    /** Six-marker complete projection-throw attempts. */
    private long thrown;

    /** Other incomplete or invalid frames. */
    private long partial;

    /** Overlapping known cross-stage subset. */
    private long crossed;

    /** Own Mapper envelope, distinct from existing timing. */
    private Stats whole = new Stats();

    /** Normal full pair envelope in exactly the segment cohort. */
    private Stats full = new Stats();

    /** Five normal full segments. */
    private final Stats[] segments = new Stats[5];

    /** Two completed role aggregates. */
    private final Role[] roles = {new Role(), new Role()};

    /** Construct fixed empty aggregates. */
    private Cell() {
      for (int index = 0; index < 5; index++) segments[index] = new Stats();
    }

    /** Stage a full bounded copy before atomic replacement. */
    private Cell(Cell previous) {
      completed = previous.completed;
      normal = previous.normal;
      thrown = previous.thrown;
      partial = previous.partial;
      crossed = previous.crossed;
      whole = new Stats(previous.whole);
      full = new Stats(previous.full);
      for (int index = 0; index < 5; index++) segments[index] = new Stats(previous.segments[index]);
      roles[0] = new Role(previous.roles[0]);
      roles[1] = new Role(previous.roles[1]);
    }
  }
}
