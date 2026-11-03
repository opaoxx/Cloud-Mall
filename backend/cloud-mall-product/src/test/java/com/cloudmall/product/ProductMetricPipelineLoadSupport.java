package com.cloudmall.product;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.ProductMetricBatchListener;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.service.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.BatchMessagingMessageListenerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Constant-space service-call observation for the explicit single10 test entry, without SQL. */
final class ProductMetricPipelineLoadSupport implements MethodInterceptor {
  /** Exclusive new entry role; existing finite and legacy load admission remain independent. */
  static final String ROLE = "single10-load";

  /** Exact modes expose the intrinsic single/batch prefetch difference rather than hiding it. */
  enum Mode {
    SINGLE1,
    BATCH1;

    /** Parse only the explicit new command-line spelling. */
    static Mode parse(String text) {
      check(text != null, "MODE_INVALID");
      return switch (text) {
        case "single1" -> SINGLE1;
        case "batch1" -> BATCH1;
        default -> throw invalid("MODE_INVALID");
      };
    }

    /** Stable lowercase token used in control identities and the owned directory. */
    String token() {
      return this == SINGLE1 ? "single1" : "batch1";
    }
  }

  /** Ordered entry-time buckets; counters are never reset at a phase boundary. */
  enum Stage {
    SETUP,
    WARMUP,
    BASELINE_DRAIN,
    MEASUREMENT,
    FINISH_DRAIN,
    COMPARISON,
    POST_COMPARISON_DRAIN
  }

  /** Fixed native JSON codec, never invoked by the hot service advice. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Exact selected transport protocol. */
  private final Mode mode;

  /** Optional test-only duration collector; null preserves the original uninstrumented path. */
  private final ProductMetricApplicationTimingSupport applicationTiming;

  /** Optional callback correlation; null leaves every original observation path unchanged. */
  private final ProductMetricCallbackServiceObservationSupport callbackSupport;

  /** Optional lifecycle collector reusing the sole existing Service advice. */
  private final ProductMetricServiceTransactionObservationSupport transactionObservation;

  /** Stage, operation(apply/applyBatch), input size minus one, result(normal/threw). */
  private final long[][][][] calls = new long[Stage.values().length][2][4][2];

  /** Normal-return APPLIED positions, attributed to call entry stage. */
  private final long[] applied = new long[Stage.values().length];

  /** Normal-return DUPLICATE positions, attributed to call entry stage. */
  private final long[] duplicates = new long[Stage.values().length];

  /** Actual calls crossing a stage boundary; not cross-process timing evidence. */
  private final long[] crossed = new long[Stage.values().length];

  /** Current phase, guarded by this observer's short monitor. */
  private Stage stage = Stage.SETUP;

  /** In-flight observed calls, held through the original proxy return or throw. */
  private long active;

  /** First fixed observational error; original business failures do not set this. */
  private String failure;

  /** Original proxy identity, assigned once at installation. */
  private Object installedService;

  /** Actual initialized endpoint evidence, assigned once outside measurement. */
  private Map<String, Object> runtime;

  /** Allocate only fixed primitive counters; no reader, actor or event collection is installed. */
  ProductMetricPipelineLoadSupport(Mode mode) {
    this(mode, null);
  }

  /** Bind an explicitly admitted diagnostic collector without installing another Service advice. */
  ProductMetricPipelineLoadSupport(
      Mode mode, ProductMetricApplicationTimingSupport applicationTiming) {
    this(mode, applicationTiming, null);
  }

  /** Bind optional correlation without replacing the original application timing collector. */
  ProductMetricPipelineLoadSupport(
      Mode mode,
      ProductMetricApplicationTimingSupport applicationTiming,
      ProductMetricCallbackServiceObservationSupport callbackSupport) {
    this(mode, applicationTiming, callbackSupport, null);
  }

  /** Reuse the existing Service bracket for independently enabled transaction markers. */
  ProductMetricPipelineLoadSupport(
      Mode mode,
      ProductMetricApplicationTimingSupport applicationTiming,
      ProductMetricCallbackServiceObservationSupport callbackSupport,
      ProductMetricServiceTransactionObservationSupport transactionObservation) {
    this.transactionObservation = transactionObservation;
    this.mode = Objects.requireNonNull(mode);
    this.applicationTiming = applicationTiming;
    this.callbackSupport = callbackSupport;
  }

  /** Read the current entry phase without sampling a clock or mutating original counters. */
  synchronized Stage currentStage() {
    return stage;
  }

  /** Reject incompatible new-entry parameters before owned databases or middleware inspection. */
  static void admit(
      Mode mode, boolean batch, int consumers, int seeds, String timing, String anchor) {
    check(
        mode != null
            && (mode == Mode.BATCH1) == batch
            && consumers == 1
            && seeds == 300
            && "false".equals(timing)
            && "false".equals(anchor),
        "ADMISSION_INVALID");
  }

  /** Add one outer advice to the original transactional proxy, retaining every original advisor. */
  synchronized void install(Object service) throws Exception {
    require(installedService == null && service instanceof Advised, "INSTALL_INVALID");
    Advised advised = (Advised) service;
    Advisor[] original = advised.getAdvisors();
    for (String operation : List.of("apply", "applyBatch")) {
      Method method =
          ProductMetricService.class.getMethod(
              operation, "apply".equals(operation) ? ProductMetricEventDTO.class : List.class);
      int transactions = 0;
      for (Advisor advisor : original)
        if (advisor.getAdvice() instanceof TransactionInterceptor transaction) {
          var attribute =
              Objects.requireNonNull(transaction.getTransactionAttributeSource())
                  .getTransactionAttribute(method, advised.getTargetClass());
          require(
              attribute != null
                  && attribute.getPropagationBehavior()
                      == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                  && attribute.getTimeout() == 3
                  && attribute.getIsolationLevel()
                      == ("applyBatch".equals(operation)
                          ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                          : TransactionDefinition.ISOLATION_DEFAULT),
              "TRANSACTION_INVALID");
          transactions++;
        }
      require(transactions == 1, "TRANSACTION_COUNT_INVALID");
    }
    advised.addAdvice(0, this);
    Advisor[] after = advised.getAdvisors();
    require(after.length == original.length + 1 && after[0].getAdvice() == this, "ADVISOR_CHANGED");
    for (int index = 0; index < original.length; index++)
      require(after[index + 1] == original[index], "ADVISOR_CHANGED");
    installedService = service;
  }

  /** Observe a direct original Service call, not a broker acknowledgement or independent commit. */
  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    String operationName = invocation.getMethod().getName();
    if (!"apply".equals(operationName) && !"applyBatch".equals(operationName))
      return invocation.proceed();
    int operation = "apply".equals(operationName) ? 0 : 1;
    int size = 0;
    Object[] originalArguments = null;
    Stage entered;
    synchronized (this) {
      entered = stage;
      active = increment(active);
    }
    try {
      Object[] arguments = invocation.getArguments();
      originalArguments = arguments;
      if (arguments.length == 1 && operation == 0 && arguments[0] instanceof ProductMetricEventDTO)
        size = 1;
      if (arguments.length == 1 && operation == 1 && arguments[0] instanceof List<?> batch) {
        size = batch.size();
        if (size < 1
            || size > 4
            || batch.stream().anyMatch(value -> !(value instanceof ProductMetricEventDTO)))
          size = 0;
      }
      synchronized (this) {
        if (size == 0) fail("INPUT_UNKNOWN");
        if (mode == Mode.SINGLE1 && operation == 1) fail("UNEXPECTED_BATCH_ROUTE");
      }
    } catch (Throwable observationFailure) {
      synchronized (this) {
        fail("INPUT_OBSERVATION_FAILED");
      }
    }
    // 1. Original proxy invocation is never retried, unwrapped or replaced by observation.
    ProductMetricApplicationTimingSupport.CallToken timingToken = null;
    if (applicationTiming != null) {
      try {
        timingToken = applicationTiming.serviceEnter(entered, operationName, size);
      } catch (Throwable observationFailure) {
        recordTimingFailure();
      }
    }
    ProductMetricCallbackServiceObservationSupport.AttemptToken callbackToken =
        enterCallbackObservation(timingToken, entered, operationName, size);
    ProductMetricServiceTransactionObservationSupport.ServiceToken transactionToken =
        enterTransactionObservation(timingToken, entered, operationName, size);
    Object returned;
    try {
      returned = invocation.proceed();
    } catch (Throwable originalFailure) {
      exitTransactionObservation(transactionToken, timingToken, true);
      exitCallbackObservation(callbackToken, timingToken, true);
      exitTiming(timingToken, true);
      record(entered, operation, size, true, null, originalArguments);
      throw originalFailure;
    }
    // 2. Invalid evidence stays sticky without changing a normally returned business object.
    exitTransactionObservation(transactionToken, timingToken, false);
    exitCallbackObservation(callbackToken, timingToken, false);
    exitTiming(timingToken, false);
    record(entered, operation, size, false, returned, originalArguments);
    return returned;
  }

  /** Enter after existing entry hooks without replacing their original tokens. */
  private ProductMetricServiceTransactionObservationSupport.ServiceToken
      enterTransactionObservation(
          ProductMetricApplicationTimingSupport.CallToken originalTag,
          Stage entered,
          String operation,
          int size) {
    if (transactionObservation == null) return null;
    try {
      return transactionObservation.serviceEnter(
          applicationTiming, originalTag, entered, operation, size);
    } catch (Throwable diagnosticFailure) {
      recordTransactionObservationFailure();
      return null;
    }
  }

  /** Finish before existing exits while the same original application tag is still open. */
  private void exitTransactionObservation(
      ProductMetricServiceTransactionObservationSupport.ServiceToken token,
      ProductMetricApplicationTimingSupport.CallToken originalTag,
      boolean threw) {
    if (transactionObservation == null) return;
    try {
      transactionObservation.serviceExit(token, originalTag, threw, currentStage());
    } catch (Throwable diagnosticFailure) {
      recordTransactionObservationFailure();
    }
  }

  /** Even failure recording cannot replace the original business outcome. */
  private void recordTransactionObservationFailure() {
    try {
      transactionObservation.recordObserverFailure();
    } catch (Throwable ignoredDiagnosticFailure) {
      // New diagnostics never poison the existing observation failure domain.
    }
  }

  /** Start only the new optional bracket; no observation error enters the business try block. */
  private ProductMetricCallbackServiceObservationSupport.AttemptToken enterCallbackObservation(
      ProductMetricApplicationTimingSupport.CallToken originalToken,
      Stage entered,
      String operation,
      int size) {
    if (callbackSupport == null) return null;
    try {
      return callbackSupport.serviceEnter(originalToken, entered, operation, size);
    } catch (Throwable observationFailure) {
      recordCallbackObservationFailure();
      return null;
    }
  }

  /** Finish the new bracket before consuming the same original application timing token. */
  private void exitCallbackObservation(
      ProductMetricCallbackServiceObservationSupport.AttemptToken token,
      ProductMetricApplicationTimingSupport.CallToken originalToken,
      boolean threw) {
    if (callbackSupport == null) return;
    try {
      callbackSupport.serviceExit(token, originalToken, threw, currentStage());
    } catch (Throwable observationFailure) {
      recordCallbackObservationFailure();
    }
  }

  /** New diagnostics have a separate failure domain and never poison old observer counters. */
  private void recordCallbackObservationFailure() {
    try {
      callbackSupport.recordObserverFailure();
    } catch (Throwable ignoredDiagnosticFailure) {
      // Original return/Throwable remains authoritative even if diagnostic failure reporting fails.
    }
  }

  /**
   * Sample the actual exit stage only when enabled, preserving every original result or failure.
   */
  private void exitTiming(
      ProductMetricApplicationTimingSupport.CallToken timingToken, boolean threw) {
    if (applicationTiming == null) return;
    try {
      Stage observedExitStage;
      synchronized (this) {
        observedExitStage = stage;
      }
      applicationTiming.serviceExit(timingToken, threw, observedExitStage);
    } catch (Throwable observationFailure) {
      recordTimingFailure();
    }
  }

  /** Keep a diagnostic hook failure sticky without changing the original business Throwable. */
  private void recordTimingFailure() {
    try {
      applicationTiming.recordObserverFailure();
    } catch (Throwable ignoredObservationFailure) {
      synchronized (this) {
        fail("APPLICATION_TIMING_OBSERVER_FAILED");
      }
    }
  }

  /** Complete fixed counters with entry-stage attribution, preserving any original Throwable. */
  private synchronized void record(
      Stage entered, int operation, int size, boolean threw, Object returned, Object[] arguments) {
    try {
      if (size >= 1 && size <= 4)
        calls[entered.ordinal()][operation][size - 1][threw ? 1 : 0] =
            increment(calls[entered.ordinal()][operation][size - 1][threw ? 1 : 0]);
      if (stage != entered) crossed[entered.ordinal()] = increment(crossed[entered.ordinal()]);
      if (!threw && size > 0) {
        int newlyApplied = 0, duplicate = 0;
        if (operation == 0) {
          require(
              "APPLIED".equals(returned) || "DUPLICATE".equals(returned), "SINGLE_RESULT_UNKNOWN");
          newlyApplied = "APPLIED".equals(returned) ? 1 : 0;
          duplicate = 1 - newlyApplied;
        } else {
          require(returned instanceof ProductMetricBatchApplyResultDTO, "BATCH_RESULT_UNKNOWN");
          var result = (ProductMetricBatchApplyResultDTO) returned;
          require(result.events.size() == size, "BATCH_RESULT_SIZE_INVALID");
          List<?> inputs = (List<?>) arguments[0];
          for (int index = 0; index < size; index++) {
            var item = result.events.get(index);
            require(
                item != null
                    && item.index == index
                    && Objects.equals(
                        item.eventId, ((ProductMetricEventDTO) inputs.get(index)).eventId)
                    && ("APPLIED".equals(item.outcome) || "DUPLICATE".equals(item.outcome)),
                "BATCH_POSITION_INVALID");
            if ("APPLIED".equals(item.outcome)) newlyApplied++;
            else duplicate++;
          }
          require(result.appliedCount == newlyApplied, "BATCH_APPLIED_COUNT_INVALID");
        }
        applied[entered.ordinal()] = Math.addExact(applied[entered.ordinal()], newlyApplied);
        duplicates[entered.ordinal()] = Math.addExact(duplicates[entered.ordinal()], duplicate);
      }
    } catch (Throwable observationFailure) {
      fail("RETURN_OBSERVATION_FAILED");
    } finally {
      if (active > 0) active--;
      else fail("ACTIVE_COUNT_INVALID");
    }
  }

  /** Advance exactly one phase while retaining every prior counter. */
  synchronized void advance(Stage next) {
    require(next != null && next.ordinal() == stage.ordinal() + 1, "STAGE_ORDER_INVALID");
    stage = next;
  }

  /** Publish only fixed-sized primitive data; no per-event or exception object is retained. */
  synchronized Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("stage", stage.name());
    result.put("mode", mode.token());
    result.put("activeCalls", active);
    result.put("failureCode", failure);
    result.put("scope", "SERVICE_CALLS_NOT_COMMIT_OR_ACK");
    List<Map<String, Object>> stages = new java.util.ArrayList<>();
    for (Stage selected : Stage.values()) {
      List<Map<String, Object>> buckets = new java.util.ArrayList<>();
      for (int operation = 0; operation < 2; operation++)
        for (int size = 1; size <= 4; size++)
          buckets.add(
              Map.of(
                  "operation",
                  operation == 0 ? "apply" : "applyBatch",
                  "size",
                  size,
                  "normalReturns",
                  calls[selected.ordinal()][operation][size - 1][0],
                  "originalThrows",
                  calls[selected.ordinal()][operation][size - 1][1]));
      stages.add(
          Map.of(
              "stage",
              selected.name(),
              "calls",
              buckets,
              "normalAppliedPositions",
              applied[selected.ordinal()],
              "normalDuplicatePositions",
              duplicates[selected.ordinal()],
              "crossBoundaryCalls",
              crossed[selected.ordinal()]));
    }
    result.put("stages", stages);
    return result;
  }

  /** Copy the actual stage under the existing short lock before constructing a cold timing map. */
  Map<String, Object> timingSnapshot(ProductMetricApplicationTimingSupport.SnapshotKind kind) {
    if (applicationTiming == null) return null;
    Stage reportedStage;
    synchronized (this) {
      reportedStage = stage;
    }
    return applicationTiming.snapshot(kind, reportedStage);
  }

  /**
   * Require real initialized wiring and positive direct calls; this does not replace SQL/ES gates.
   */
  synchronized void assertHealthy(boolean afterClose) {
    require(
        failure == null && installedService != null && runtime != null, "OBSERVER_NOT_QUALIFIED");
    require(stage == Stage.POST_COMPARISON_DRAIN, "STAGE_INCOMPLETE");
    long normals = 0;
    for (Stage selected : Stage.values())
      for (int size = 0; size < 4; size++)
        normals =
            Math.addExact(normals, calls[selected.ordinal()][mode == Mode.BATCH1 ? 1 : 0][size][0]);
    require(normals > 0 && (!afterClose || active == 0), "CALL_EVIDENCE_MISSING");
    if (applicationTiming != null) applicationTiming.assertHealthy(afterClose);
  }

  /** Read actual production adapters and handler ownership once, before external warmup begins. */
  synchronized Map<String, Object> runtimeFacts(
      ApplicationContext context, SimpleMessageListenerContainer container) {
    require(
        runtime == null && context.getBean(ProductMetricService.class) == installedService,
        "RUNTIME_INSTALL_INVALID");
    Object factory = context.getBean("productMetricContainerFactory");
    require(
        factory instanceof SimpleRabbitListenerContainerFactory
            && ((factory instanceof ProductMetricBatchContainerFactory) == (mode == Mode.BATCH1)),
        "FACTORY_INVALID");
    require(
        context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainer("productMetrics")
            == container,
        "ENDPOINT_CHANGED");
    require(
        Integer.valueOf(1).equals(field(container, "concurrentConsumers"))
            && Integer.valueOf(1).equals(field(container, "maxConcurrentConsumers"))
            && Integer.valueOf(mode == Mode.BATCH1 ? 4 : 1)
                .equals(field(container, "prefetchCount"))
            && container.getAcknowledgeMode() == AcknowledgeMode.MANUAL,
        "RUNTIME_SETTINGS_INVALID");
    Object listener = container.getMessageListener();
    require(
        listener instanceof MessagingMessageListenerAdapter
            && ((listener instanceof BatchMessagingMessageListenerAdapter)
                == (mode == Mode.BATCH1)),
        "ADAPTER_INVALID");
    HandlerAdapter handler = (HandlerAdapter) field(listener, "handlerAdapter");
    InvocableHandlerMethod invocation =
        (InvocableHandlerMethod) field(handler, "invokerHandlerMethod");
    Object target =
        mode == Mode.BATCH1
            ? context.getBean(ProductMetricBatchListener.class)
            : context.getBean(ProductMetricSingleListener.class);
    require(
        invocation.getBean() == target
            && invocation
                .getMethod()
                .getName()
                .equals(mode == Mode.BATCH1 ? "onMessages" : "onMessage")
            && Arrays.equals(
                invocation.getMethod().getParameterTypes(),
                new Class<?>[] {mode == Mode.BATCH1 ? List.class : Message.class, Channel.class}),
        "HANDLER_INVALID");
    ProductMetricConsumer consumer = context.getBean(ProductMetricConsumer.class);
    require(
        field(target, "consumer") == consumer && field(consumer, "service") == installedService,
        "CONSUMER_CHANGED");
    if (mode == Mode.BATCH1) {
      ProductMetricPipelineBatchSupport.containerFacts(context, container, listener);
      Object registry = context.getBean(ProductMetricBatchChannelRegistry.class);
      require(
          field(factory, "registry") == registry
              && field(target, "registry") == registry
              && field(consumer, "batchRegistry") == registry,
          "REGISTRY_CHANGED");
    } else
      require(
          field(consumer, "batchRegistry") == null
              && context.getBeanNamesForType(ProductMetricBatchListener.class).length == 0,
          "SINGLE_REGISTRY_INVALID");
    runtime =
        Map.of(
            "mode",
            mode.token(),
            "containerIdentity",
            System.identityHashCode(container),
            "factoryIdentity",
            System.identityHashCode(factory),
            "listenerIdentity",
            System.identityHashCode(listener),
            "consumerIdentity",
            System.identityHashCode(consumer),
            "serviceIdentity",
            System.identityHashCode(installedService),
            "prefetch",
            mode == Mode.BATCH1 ? 4 : 1,
            "consumers",
            1,
            "rawChannelWrappedForObservation",
            false);
    return runtime;
  }

  /** Return fixed initialized facts or null if construction failed before runtime qualification. */
  synchronized Map<String, Object> runtimeFacts() {
    return runtime;
  }

  /** First failure is retained without arbitrary message text. */
  private void fail(String code) {
    if (failure == null) failure = code;
  }

  /** Checked arithmetic never wraps a successful-looking counter. */
  private long increment(long value) {
    try {
      return Math.addExact(value, 1);
    } catch (ArithmeticException overflow) {
      fail("COUNTER_OVERFLOW");
      return value;
    }
  }

  /** Observer setup and final gates can fail; per-call callers catch these internally. */
  private void require(boolean valid, String code) {
    if (!valid) {
      fail(code);
      throw invalid(code);
    }
  }

  /** Read only an exact existing field; never mutate a production owner. */
  private static Object field(Object target, String name) {
    return ReflectionTestUtils.getField(target, name);
  }

  /** Fixed external validation error. */
  private static IllegalStateException invalid(String code) {
    return new IllegalStateException("CM21_SINGLE10_" + code);
  }

  /** Validate non-observer input without creating an observer or any resource. */
  private static void check(boolean valid, String code) {
    if (!valid) throw invalid(code);
  }

  /** Retain the original failure and attach only a distinct secondary object. */
  static Throwable combine(Throwable primary, Throwable secondary) {
    if (primary == null) return secondary;
    if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
    return primary;
  }

  /**
   * Single-mode comparison has no batch lease evidence; require actual stopped framework workers.
   */
  static void requireSingleStopped(SimpleMessageListenerContainer container) {
    check(
        container != null && !container.isRunning() && container.getActiveConsumerCount() == 0,
        "SINGLE_STOP_UNKNOWN");
  }

  /**
   * Restore the exact single container once, preserving an existing page assertion or HTTP error.
   */
  static void resumeSingle(SimpleMessageListenerContainer container, Throwable primary) {
    try {
      requireSingleStopped(container);
      container.start();
      check(
          container.isRunning() && container.getActiveConsumerCount() == 1,
          "SINGLE_RESUME_UNKNOWN");
    } catch (RuntimeException | Error secondary) {
      if (primary == null) throw secondary;
      combine(primary, secondary);
    }
  }

  /** Exact new-entry control sequence, independent of the old ladder's abort behavior. */
  static final class Controls {
    /** Claimed scope owner, never taken from the incoming control. */
    private final String owner;

    /** Selected protocol identity. */
    private final Mode mode;

    /** Claimed owner-mode identity, independent of Pipeline's generated namespace identity. */
    private final String runIdentity;

    /** Next original external-process phase accepted once. */
    private int next;

    /** A validated cancel command is distinguishable from an arbitrary exception message. */
    private boolean aborted;

    /** Bind controls to one claimed completion directory. */
    Controls(String owner, Mode mode) {
      this.owner = owner;
      this.mode = mode;
      this.runIdentity = owner + "-" + mode.token();
    }

    /** Refuse undeclared control files rather than silently waiting past another load level. */
    static void verifyFiles(Path directory) throws IOException {
      try (var files = Files.newDirectoryStream(directory, "*.control.json")) {
        for (Path file : files)
          check(
              List.of(
                          "baseline-10.control.json",
                          "finish-10.control.json",
                          "stop.control.json",
                          "abort.control.json")
                      .contains(file.getFileName().toString())
                  && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                  && !Files.isSymbolicLink(file),
              "CONTROL_FILE_INVALID");
      }
    }

    /** Validate scope, stage and the original external JMeter exit generation before acceptance. */
    void accept(String phase, JsonNode control) {
      check(
          !aborted
              && next < 3
              && control != null
              && control.isObject()
              && owner.equals(control.path("owner").asText())
              && mode.token().equals(control.path("mode").asText())
              && runIdentity.equals(control.path("runIdentity").asText())
              && phase.equals(control.path("phase").asText())
              && control.path("level").isInt()
              && control.path("level").intValue() == 10,
          "CONTROL_IDENTITY_INVALID");
      if ("abort".equals(phase)) {
        aborted = true;
        throw new Aborted();
      }
      check(
          List.of("baseline-10", "finish-10", "stop").get(next).equals(phase),
          "CONTROL_ORDER_INVALID");
      if (!"stop".equals(phase)) {
        check(
            control.path("jmeterPid").isIntegralNumber()
                && control.path("jmeterPid").canConvertToLong()
                && control.path("jmeterPid").longValue() > 0
                && control.path("jmeterStartedAt").isTextual()
                && control.path("jmeterExitCode").isInt()
                && control.path("jmeterExitCode").intValue() == 0,
            "JMETER_EXIT_IDENTITY_INVALID");
        Instant started = Instant.parse(control.path("jmeterStartedAt").textValue());
        var current = ProcessHandle.of(control.path("jmeterPid").longValue());
        if (current.isPresent() && current.get().isAlive())
          check(
              !current
                  .get()
                  .info()
                  .startInstant()
                  .orElseThrow(() -> invalid("JMETER_GENERATION_UNKNOWN"))
                  .equals(started),
              "JMETER_STILL_RUNNING");
      } else
        check(
            control.path("allStageAuditsPassed").isBoolean()
                && control.path("allStageAuditsPassed").booleanValue(),
            "STAGE_AUDITS_REQUIRED");
      next++;
    }
  }

  /** A typed abort exists only after its exact owned control has been validated. */
  static final class Aborted extends IllegalStateException {
    /** Fixed serialization identity for this internal control signal. */
    private static final long serialVersionUID = 1L;

    /** Construct only within validated Controls.accept. */
    private Aborted() {
      super("CM21_SINGLE10_ABORTED");
    }
  }

  /** An atomically claimed directory grants only one fixed completion-file publication. */
  static final class Completion {
    /** Optional original directory file key; null means this provider has no such evidence. */
    private final Object directoryKey;

    /** Exact claimed canonical directory, verified again immediately before publication. */
    private final Path directory;

    /** Exact required direct child filename. */
    private final Path file;

    /** Stable expected owner. */
    private final String owner;

    /** Exact selected new entry mode. */
    private final Mode mode;

    /** Prevent a second publication even if the first native write fails. */
    private boolean published;

    /** Retain only the validated directory claim and identity. */
    private Completion(Path directory, Path file, String owner, Mode mode, Object directoryKey) {
      this.directory = directory;
      this.file = file;
      this.owner = owner;
      this.mode = mode;
      this.directoryKey = directoryKey;
    }

    /**
     * Claim exactly the standard module target path; an existing owner-mode directory is refused.
     */
    static Completion claim(Path moduleDirectory, String owner, Mode mode, String property)
        throws IOException {
      check(
          owner != null && owner.matches("[0-9a-f]{32}") && mode != null && property != null,
          "COMPLETION_IDENTITY_INVALID");
      Path module = moduleDirectory.toAbsolutePath().normalize();
      check(
          "cloud-mall-product".equals(module.getFileName().toString())
              && Files.isRegularFile(module.resolve("pom.xml")),
          "MODULE_DIRECTORY_INVALID");
      Path directory = module.resolve("target/cm21-single10").resolve(owner + "-" + mode.token());
      Path expected = directory.resolve("completion.json");
      Path requested = Path.of(property);
      check(
          requested.isAbsolute()
              && requested.equals(requested.normalize())
              && requested.equals(expected),
          "COMPLETION_PATH_INVALID");
      verifyDirectories(module);
      for (Path component : module.relativize(directory.getParent())) {
        module = module.resolve(component);
        if (!Files.exists(module, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(module);
        verifyDirectories(module);
      }
      verifyDirectories(directory.getParent());
      Files.createDirectory(directory);
      verifyDirectories(directory);
      check(!Files.exists(expected, LinkOption.NOFOLLOW_LINKS), "COMPLETION_EXISTS");
      Object key =
          Files.readAttributes(
                  directory,
                  java.nio.file.attribute.BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS)
              .fileKey();
      return new Completion(directory.toRealPath(), expected, owner, mode, key);
    }

    /** Return the sole newly claimed run directory for new-entry control and native files. */
    Path directory() {
      return directory;
    }

    /**
     * Publish after the close attempt, never before work or before the real close result exists.
     */
    void publish(
        String runtimeIdentity,
        boolean workComplete,
        boolean closeReturned,
        boolean closeFailed,
        ProductMetricPipelineLoadSupport observer,
        Throwable primary)
        throws IOException {
      check(!published, "COMPLETION_ALREADY_PUBLISHED");
      published = true;
      verifyDirectories(directory);
      check(
          directoryKey == null
              || directoryKey.equals(
                  Files.readAttributes(
                          directory,
                          java.nio.file.attribute.BasicFileAttributes.class,
                          LinkOption.NOFOLLOW_LINKS)
                      .fileKey()),
          "COMPLETION_DIRECTORY_CHANGED");
      check(
          file.getParent().equals(directory) && !Files.exists(file, LinkOption.NOFOLLOW_LINKS),
          "COMPLETION_DIRECTORY_CHANGED");
      boolean natural = primary == null && workComplete && closeReturned && !closeFailed;
      check(primary != null || natural, "COMPLETION_STATE_INVALID");
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("schemaVersion", 1);
      facts.put("owner", owner);
      facts.put("mode", mode.token());
      facts.put("runIdentity", owner + "-" + mode.token());
      facts.put("runtimeIdentity", runtimeIdentity);
      facts.put(
          "outcome",
          natural ? "NATURAL_COMPLETED" : primary instanceof Aborted ? "ABORTED" : "FAILED");
      facts.put("workAssertionsComplete", workComplete);
      facts.put("closeReturned", closeReturned);
      facts.put("closeFailed", closeFailed);
      facts.put("observerRuntimeFacts", observer == null ? null : observer.runtimeFacts());
      facts.put("observerCounters", observer == null ? null : observer.snapshot());
      facts.put("primaryExceptionType", primary == null ? null : primary.getClass().getName());
      facts.put(
          "secondaryExceptionTypes",
          primary == null
              ? List.of()
              : Arrays.stream(primary.getSuppressed())
                  .map(value -> value.getClass().getName())
                  .toList());
      facts.put("ownPublicationTimeProven", false);
      facts.put(
          "directoryFileKeyStatus",
          directoryKey == null ? "UNAVAILABLE" : "OBSERVED_AND_RECHECKED");
      Files.write(file, JSON.writeValueAsBytes(facts), StandardOpenOption.CREATE_NEW);
    }

    /** Reject symlinks and redirected canonical ancestors, including junction path deviations. */
    private static void verifyDirectories(Path directory) throws IOException {
      Path current = directory.getRoot();
      for (Path component : directory) {
        current = current.resolve(component);
        check(
            Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(current)
                && current.equals(current.toRealPath()),
            "DIRECTORY_REDIRECTED");
      }
    }
  }
}
