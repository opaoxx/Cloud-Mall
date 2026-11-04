package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.product.domain.po.*;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.*;
import com.cloudmall.product.service.impl.ElasticsearchProductIndexWriter;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.Channel;
import com.zaxxer.hikari.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.type.classreading.*;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

/**
 * Owned real broker/ES/Redis and the standard Stock Boot artifact close the actual online pipeline.
 */
class ProductOnlineMetricsPipelineIT {
  /** Only a refresh currently being built may supply its exact owned Product resource. */
  private static final ThreadLocal<Pipeline> CURRENT = new ThreadLocal<>();

  /** Native evidence uses explicit nulls and stable field order. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void realStockEventsAndPublicReadsConvergeThroughOwnedBroker() throws Exception {
    try (Pipeline run = new Pipeline("convergence", 8)) {
      run.seedNormal("202610cm21normal", 3);
      run.seedSeckill("202610cm21seckill");
      run.start();
      run.confirm("202610cm21normal");
      run.confirm("202610cm21seckill");
      run.drain();
      Map<String, Object> confirmed = run.stockBusinessFacts();
      run.confirm("202610cm21normal");
      run.confirm("202610cm21seckill");
      run.drain();
      assertEquals(confirmed, run.stockBusinessFacts());
      run.audit(
          "stock-first-confirm-replay",
          Map.of(
              "before",
              confirmed,
              "after",
              run.stockBusinessFacts(),
              "outbox",
              run.stock.tableFacts("stock_sales_outbox")));
      run.detail(41);
      run.detail(41);
      run.search("latest", 1, 3);
      run.drain();
      assertEquals(2L, run.stock.count("stock_sales_outbox"));
      assertEquals(
          2L,
          run.product.scalar(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE"
                  + " event_type='STOCK_SALE_CONFIRMED'"));
      assertEquals(4L, run.product.scalar("SELECT SUM(sold_count) FROM product_online_stat"));
      assertEquals(2L, run.product.scalar("SELECT SUM(view_count) FROM product_online_stat"));
      assertEquals(3L, run.product.scalar("SELECT SUM(search_count) FROM product_online_stat"));
      assertEquals(45L, run.product.scalar("SELECT SUM(hot_score) FROM product_online_stat"));
      assertEquals(7L, run.product.count("product_metric_applied_event"));
      run.verifyAll();
      run.assertDefaultConverters();
      run.audit("converged", run.counts());
      run.success();
    }
  }

  /** Batch-only fault keeps the original single and dual-consumer test bodies unchanged. */
  @Test
  void batchReturnAndCommittedChannelCloseRedeliverAfterNewContext() throws Exception {
    assertTrue(
        ProductMetricPipelineBatchSupport.requested(
            System.getProperty("cloudmall.cm21.pipeline-batch", "false")));
    ProductMetricPipelineBatchSupport.admit(
        true,
        ProductMetricPipelineRedeliverySupport.ROLE,
        metricConsumerCount(),
        System.getProperty("cloudmall.cm21.phase-timing", "false"));
    try (Pipeline run = new Pipeline(ProductMetricPipelineRedeliverySupport.ROLE, 8)) {
      run.start();
      run.binding(false);
      JsonNode registered = run.detail(41);
      String firstId =
          ProductMetricEvents.product(registered.path("requestId").asText(), 41, false, null)
              .eventId;
      await(
          () ->
              run.product.scalar(
                      "SELECT COUNT(*) FROM product_metric_outbox WHERE status='UNKNOWN'")
                  == 1,
          30,
          "BATCH_MANDATORY_RETURN");
      String original =
          run.product.jdbc.queryForObject(
              "SELECT payload_json FROM product_metric_outbox WHERE event_id=?",
              String.class,
              firstId);
      assertEquals(0L, run.product.count("product_metric_applied_event"));
      run.audit(
          "batch-returned",
          Map.of(
              "eventId",
              firstId,
              "bodySha256",
              ProductMetricEvents.sha256(original.getBytes(StandardCharsets.UTF_8))));
      run.binding(true);
      run.drain();
      assertEquals(
          original,
          run.product.jdbc.queryForObject(
              "SELECT payload_json FROM product_metric_outbox WHERE event_id=?",
              String.class,
              firstId));
      run.batchRedelivery.arm();
      JsonNode second = run.detail(42);
      String secondId =
          ProductMetricEvents.product(second.path("requestId").asText(), 42, false, null).eventId;
      run.batchRedelivery.confirmTarget(secondId);
      await(() -> run.batchRedelivery.faultObserved(), 30, "BATCH_COMMITTED_PHYSICAL_CLOSE");
      assertEquals(
          1L,
          run.product.scalar(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id='"
                  + secondId
                  + "'"));
      run.stopExpectedBatchSafety();
      run.startProduct();
      await(() -> run.batchRedelivery.replayObserved(), 30, "BATCH_NEW_CONTEXT_REAL_REDELIVERY");
      run.drain();
      assertEquals(
          1L, run.product.scalar("SELECT view_count FROM product_online_stat WHERE product_id=42"));
      assertEquals(
          1L,
          run.product.scalar("SELECT metric_version FROM product_online_stat WHERE product_id=42"));
      assertEquals(
          1L,
          run.product.scalar(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id='"
                  + secondId
                  + "'"));
      assertEquals(
          original,
          run.product.jdbc.queryForObject(
              "SELECT payload_json FROM product_metric_outbox WHERE event_id=?",
              String.class,
              firstId));
      assertEquals(2L, run.product.count("product_metric_applied_event"));
      run.verifyAll();
      run.assertDefaultConverters();
      run.success();
    }
  }

  @Test
  void redeliveryReturnAndConsumerRestartKeepOneAppliedEvent() throws Exception {
    if (metricConsumerCount() == 2) {
      dualConsumerRedelivery();
      return;
    }
    try (Pipeline run = new Pipeline("redelivery", 8)) {
      run.start();
      run.binding(false);
      JsonNode registered = run.detail(41);
      String request = registered.path("requestId").asText();
      String event = ProductMetricEvents.product(request, 41, false, null).eventId;
      await(
          () ->
              run.product.scalar(
                      "SELECT COUNT(*) FROM product_metric_outbox WHERE status='UNKNOWN'")
                  == 1,
          30,
          "MANDATORY_RETURN");
      String raw =
          run.product.jdbc.queryForObject(
              "SELECT payload_json FROM product_metric_outbox WHERE event_id=?",
              String.class,
              event);
      assertEquals(0L, run.product.count("product_metric_applied_event"));
      run.audit(
          "returned",
          Map.of(
              "eventId",
              event,
              "storedBody",
              JSON.readTree(raw),
              "outbox",
              run.product.tableFacts("product_metric_outbox")));
      run.binding(true);
      run.drain();
      assertEquals(
          raw,
          run.product.jdbc.queryForObject(
              "SELECT payload_json FROM product_metric_outbox WHERE event_id=?",
              String.class,
              event));
      run.ackFault.set(true);
      JsonNode second = run.detail(42);
      String secondId =
          ProductMetricEvents.product(second.path("requestId").asText(), 42, false, null).eventId;
      await(() -> run.ackClosed.get(), 30, "ACK_CHANNEL_CLOSE");
      await(
          () ->
              run.product.scalar(
                      "SELECT COUNT(*) FROM product_metric_applied_event WHERE product_id=42")
                  == 1,
          10,
          "COMMITTED_BEFORE_ACK");
      run.audit(
          "committed-before-ack",
          Map.of(
              "eventId",
              secondId,
              "ledger",
              run.product.tableFacts("product_metric_applied_event"),
              "channelClosedBeforeOriginalAck",
              true));
      run.stopProduct();
      run.startProduct();
      await(() -> run.redelivered.get(), 30, "BROKER_REDELIVERY_AFTER_RESTART");
      run.drain();
      assertEquals(
          1L, run.product.scalar("SELECT view_count FROM product_online_stat WHERE product_id=42"));
      assertEquals(
          1L,
          run.product.scalar("SELECT metric_version FROM product_online_stat WHERE product_id=42"));
      assertEquals(
          1L,
          run.product.scalar(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id='"
                  + secondId
                  + "'"));
      run.verifyAll();
      run.audit(
          "redelivered",
          Map.of("eventId", secondId, "redelivered", run.redelivered.get(), "facts", run.counts()));
      run.success();
    }
  }

  /** Explicit isolated2 keeps all four original public identities and real recovery paths. */
  private void dualConsumerRedelivery() throws Exception {
    try (Pipeline run = new Pipeline("redelivery", 8)) {
      try {
        run.start();
        run.assertDefaultConverters();
        run.binding(false);
        JsonNode registered = run.detail(41);
        String event =
            ProductMetricEvents.product(registered.path("requestId").textValue(), 41, false, null)
                .eventId;
        await(
            () ->
                run.product.scalar(
                        "SELECT COUNT(*) FROM product_metric_outbox WHERE status='UNKNOWN'")
                    == 1,
            30,
            "MANDATORY_RETURN");
        Map<String, Object> immutable = run.originalMetric(event);
        assertEquals(0L, run.product.count("product_metric_applied_event"));
        run.audit(
            "returned",
            Map.of(
                "eventId",
                event,
                "source",
                immutable,
                "outbox",
                run.product.tableFacts("product_metric_outbox")));
        run.binding(true);
        run.drain();
        assertEquals(immutable, run.originalMetric(event));
        run.exerciseDualPause();
        run.exerciseDualAckPair();
        for (long id : List.of(41L, 44L, 42L, 43L)) {
          assertEquals(
              1L,
              run.product.scalar(
                  "SELECT COUNT(*) FROM product_metric_applied_event WHERE product_id=" + id));
          for (String field : List.of("view_count", "metric_version"))
            assertEquals(
                1L,
                run.product.scalar(
                    "SELECT " + field + " FROM product_online_stat WHERE product_id=" + id));
        }
        assertEquals(4L, run.product.count("product_metric_outbox"));
        assertEquals(4L, run.product.count("product_metric_applied_event"));
        assertEquals(4L, run.counts().get("view_count"));
        assertTrue(run.product.triggers.isEmpty());
        assertEquals(1, run.retryBindingFacts().size());
        run.verifyAll();
        run.audit("dual-four-events-converged", run.counts());
        run.success();
      } finally {
        run.pauseNackRelease.countDown();
        run.dualAckRelease.countDown();
        run.dualReplayRelease.countDown();
      }
    }
  }

  @Test
  void contentMetricBulkTombstoneAndOwnedEsRestartKeepSortedPages() throws Exception {
    try (Pipeline run = new Pipeline("dual-version", 8)) {
      run.start();
      run.detail(41);
      run.search("latest", 1, 3);
      run.drain();
      ProductIndexDocumentLoader loader = run.context.getBean(ProductIndexDocumentLoader.class);
      ElasticsearchProductIndexWriter writer =
          run.context.getBean(ElasticsearchProductIndexWriter.class);
      ProductIndexDocument old = loader.load(41).orElseThrow();
      run.concurrentContentAndMetric();
      run.drain();
      ProductIndexDocument current = loader.load(41).orElseThrow();
      assertTrue(current.version() > old.version());
      assertTrue(current.onlineStat().metricVersion > old.onlineStat().metricVersion);
      run.duplicateOriginalMetric();
      writer.upsertBatch(List.of(old));
      writer.upsertMetrics(List.of(old));
      writer.upsert(current);
      run.verifyOne(41);
      run.admin("POST", "/api/products/41/unpublish", Map.of());
      run.drain();
      assertFalse(run.document(41).path("visible").asBoolean());
      writer.upsert(old);
      assertFalse(run.document(41).path("visible").asBoolean());
      // This declared owned source deletion models the existing trusted deletion protocol; no new
      // API is added.
      ProductIndexDocument deletionSource = loader.load(42).orElseThrow();
      run.product.audit(
          "trusted-delete-source",
          Map.of(
              "productId",
              42,
              "version",
              deletionSource.version(),
              "fact",
              run.product.tableFacts("product")));
      new TransactionTemplate(
              run.context.getBean(org.springframework.transaction.PlatformTransactionManager.class))
          .execute(
              status -> {
                run.product.jdbc.update("DELETE FROM product_parameter WHERE product_id=42");
                run.product.jdbc.update("DELETE FROM product_sku WHERE product_id=42");
                run.product.jdbc.update("DELETE FROM product_hot_stat WHERE product_id=42");
                run.product.jdbc.update("DELETE FROM product WHERE id=42");
                run.context
                    .getBean(ProductIndexOutboxService.class)
                    .append("PRODUCT_DELETED", 42, deletionSource.version(), "cm21-owned-delete");
                return null;
              });
      run.drain();
      JsonNode tombstone = run.document(42);
      assertTrue(tombstone.path("tombstone").asBoolean());
      assertFalse(tombstone.path("visible").asBoolean());
      writer.upsert(deletionSource);
      assertTrue(run.document(42).path("tombstone").asBoolean());
      run.comparePages();
      run.restartOwnedEsOnce();
      run.drain();
      run.verifyAll();
      run.comparePages();
      String generation = writer.indexUuid();
      run.es("DELETE", "/" + run.index, null, 200);
      await(
          () -> !run.context.getBean(ProductMetricService.class).cut().ready(),
          15,
          "INDEX_LOSS_CLOSES_READY");
      run.drain();
      assertNotEquals(generation, writer.indexUuid());
      run.verifyAll();
      run.comparePages();
      var recoveredCut = run.context.getBean(ProductMetricService.class).cut();
      assertEquals(ProductMetricEvents.SERIES, recoveredCut.series());
      assertEquals(writer.indexUuid(), recoveredCut.indexUuid());
      assertTrue(recoveredCut.ready());
      assertFalse(recoveredCut.pending());
      assertTrue(recoveredCut.admissible());
      run.success();
    }
  }

  /**
   * Own only the fixture lifecycle; the root runner launches the actual external JMeter process.
   */
  public static void main(String[] arguments) throws Exception {
    // 1. Use the same real production graph and fixed seed shape after the standard package phase.
    if (arguments.length != 1 || !"load".equals(arguments[0]))
      throw new IllegalArgumentException("CM21_LOAD_ARGUMENT_REQUIRED");
    try (Pipeline run = new Pipeline("load", 300)) {
      try {
        run.seedNormal("202610cm21loadnormal", 3);
        run.seedSeckill("202610cm21loadseckill");
        run.start();
        run.confirm("202610cm21loadnormal");
        run.confirm("202610cm21loadseckill");
        run.drain();
        Path directory = Files.createDirectory(run.product.evidence.resolve("load"));
        List<Long> ids =
            run.product.jdbc.queryForList("SELECT id FROM product ORDER BY id", Long.class);
        assertEquals(300, ids.size());
        Path seedIds = directory.resolve("seed-ids.csv");
        Files.write(
            seedIds,
            ids.stream().map(String::valueOf).toList(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        loadWrite(
            directory,
            "ready.json",
            Map.of(
                "owner",
                run.owner,
                "productPort",
                run.productPort,
                "stockPort",
                run.stockPort,
                "productCatalog",
                run.product.database,
                "stockCatalog",
                run.stock.database,
                "index",
                run.index,
                "seedIds",
                seedIds.toAbsolutePath().toString(),
                "stageOrder",
                List.of(10, 25, 50),
                "measurementSeconds",
                60,
                "evidenceDir",
                directory.toAbsolutePath().toString()));
        System.out.println("CM21_LOAD_READY " + directory.toAbsolutePath());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        // 2. Warmup ends before its baseline; each formal JMeter process must exit before its
        // snapshot.
        for (int level : List.of(10, 25, 50)) {
          loadControl(directory, "baseline-" + level, deadline);
          long baselineDrain = loadDrain(run, directory, "baseline-" + level);
          Map<String, Object> baseline =
              loadSnapshot(run, directory, "baseline-" + level, deadline, baselineDrain);
          if (run.phaseTiming != null) run.phaseTiming.begin(level);
          loadWrite(directory, "baseline-" + level + ".done.json", baseline);
          loadControl(directory, "finish-" + level, deadline);
          if (run.phaseTiming != null) run.phaseTiming.end();
          long measuredDrain = loadDrain(run, directory, "finish-" + level);
          Map<String, Object> measured =
              loadSnapshot(run, directory, "finish-" + level, deadline, measuredDrain);
          // This first snapshot excludes the new exposure registrations caused by the comparison.
          loadComparePages(run, directory, level, deadline);
          if (run.phaseTiming != null) run.phaseTiming.export(directory, level);
          loadWrite(
              directory,
              "finish-" + level + ".done.json",
              Map.of(
                  "measurementSnapshot",
                  measured,
                  "afterComparisonCounts",
                  run.counts(),
                  "comparisonExposureRegistrationsSeparate",
                  true));
        }
        // 3. The independent raw-JTL auditor must accept all stages before the root requests
        // cleanup.
        JsonNode stop = loadControl(directory, "stop", deadline);
        if (!stop.path("allStageAuditsPassed").isBoolean()
            || !stop.path("allStageAuditsPassed").booleanValue())
          throw new IllegalStateException("CM21_INDEPENDENT_STAGE_AUDITS_REQUIRED");
        run.drain();
        run.success();
        loadWrite(
            directory,
            "stop.done.json",
            Map.of("assertionsComplete", true, "cleanupCompleted", false));
      } finally {
        if (run.phaseTiming != null) run.phaseTiming.preserve(run.resourceEvidence);
      }
    }
  }

  /** Validate the optional diagnostic before any completion claim or owned resource action. */
  static boolean applicationTimingRequested(
      String value,
      boolean single10,
      ProductMetricPipelineLoadSupport.Mode mode,
      int consumers,
      int seeds,
      String phaseTiming,
      String phaseAnchor) {
    if (!"true".equals(value) && !"false".equals(value))
      throw new IllegalArgumentException("CM21_APPLICATION_TIMING_BOOLEAN_REQUIRED");
    if ("false".equals(value)) return false;
    if (!single10
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
        || consumers != 1
        || seeds != 300
        || !"false".equals(phaseTiming)
        || !"false".equals(phaseAnchor))
      throw new IllegalArgumentException("CM21_APPLICATION_TIMING_SINGLE10_BATCH1_REQUIRED");
    return true;
  }

  /** Validate the independent default-off correlation flag before any owned resource action. */
  static boolean callbackServiceObservationRequested(
      String value,
      boolean single10,
      ProductMetricPipelineLoadSupport.Mode mode,
      int consumers,
      int seeds,
      boolean applicationTimingEnabled,
      String phaseTiming,
      String phaseAnchor) {
    if (!"true".equals(value) && !"false".equals(value))
      throw new IllegalArgumentException("CM21_CALLBACK_SERVICE_BOOLEAN_REQUIRED");
    if ("false".equals(value)) return false;
    if (!single10
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
        || consumers != 1
        || seeds != 300
        || !applicationTimingEnabled
        || !"false".equals(phaseTiming)
        || !"false".equals(phaseAnchor))
      throw new IllegalArgumentException("CM21_CALLBACK_SERVICE_SINGLE10_BATCH1_REQUIRED");
    return true;
  }

  /** Admit only literal opt-in on the reviewed fixture before inspecting external resources. */
  static boolean serviceTransactionObservationRequested(
      String value,
      boolean single10,
      ProductMetricPipelineLoadSupport.Mode mode,
      int consumers,
      int seeds,
      boolean applicationTimingEnabled,
      boolean callbackEnabled,
      String phaseTiming,
      String phaseAnchor) {
    if (!"true".equals(value) && !"false".equals(value))
      throw new IllegalArgumentException("CM21_SERVICE_TRANSACTION_BOOLEAN_REQUIRED");
    if ("false".equals(value)) return false;
    if (!single10
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
        || consumers != 1
        || seeds != 300
        || !applicationTimingEnabled
        || !callbackEnabled
        || !"false".equals(phaseTiming)
        || !"false".equals(phaseAnchor))
      throw new IllegalArgumentException("CM21_SERVICE_TRANSACTION_SINGLE10_BATCH1_REQUIRED");
    return true;
  }

  /** Publish one bounded create-only independent logical transaction sidecar. */
  static void writeServiceTransactionObservation(
      Path directory,
      ProductMetricApplicationTimingSupport.SnapshotKind kind,
      String owner,
      ProductMetricPipelineLoadSupport.Mode mode,
      Map<String, Object> observation)
      throws IOException {
    if (kind == null
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
        || owner == null
        || !owner.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("CM21_SERVICE_TRANSACTION_SIDECAR_IDENTITY_INVALID");
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)
        || !directory.toAbsolutePath().equals(directory.toRealPath()))
      throw new IllegalStateException("CM21_SERVICE_TRANSACTION_DIRECTORY_INVALID");
    String status = "UNAVAILABLE";
    if (observation != null) {
      if (!(observation.get("qualification") instanceof Map<?, ?> facts))
        throw new IllegalArgumentException("CM21_SERVICE_TRANSACTION_SNAPSHOT_INVALID");
      status =
          Boolean.TRUE.equals(facts.get("collectorHealthy"))
                  && Boolean.TRUE.equals(facts.get("closedQuiescent"))
                  && "COMPLETE".equals(facts.get("fullCoverage"))
              ? "AVAILABLE"
              : "PARTIAL";
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", 1);
    envelope.put("scope", "SERVICE_TRANSACTION_TEST_SIDECAR_V1");
    envelope.put("kind", kind.name());
    envelope.put("owner", owner);
    envelope.put("mode", mode.token());
    envelope.put("runIdentity", owner + "-" + mode.token());
    envelope.put("status", status);
    envelope.put("observation", observation);
    byte[] encoded = JSON.writeValueAsBytes(envelope);
    if (encoded.length > 1048576)
      throw new IllegalStateException("CM21_SERVICE_TRANSACTION_EXPORT_OVER_CAP");
    String file =
        switch (kind) {
          case BASELINE -> "baseline-10.service-transaction.json";
          case MEASUREMENT_ENDED -> "measurement-ended.service-transaction.json";
          case FINISH -> "finish-10.service-transaction.json";
          case COMPLETION -> "completion.service-transaction.json";
        };
    Files.write(directory.resolve(file), encoded, StandardOpenOption.CREATE_NEW);
  }

  /** Resolve new cold metadata safely; disabled observation performs no context lookups. */
  static void verifyServiceTransactionObservationRuntime(
      org.springframework.context.ApplicationContext context,
      ProductMetricServiceTransactionObservationSupport observation) {
    if (observation == null) return;
    try {
      observation.verifyRuntime(
          context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
          context.getBean(DataSource.class));
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(observation);
    }
  }

  /** Snapshot once and write once; failures affect only this independent diagnostic. */
  private static void exportServiceTransactionObservation(
      Pipeline run, Path directory, ProductMetricApplicationTimingSupport.SnapshotKind kind) {
    if (run.serviceTransactionObservation == null) return;
    Map<String, Object> snapshot = null;
    try {
      snapshot = run.serviceTransactionObservation.snapshot();
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(run.serviceTransactionObservation);
    }
    try {
      writeServiceTransactionObservation(
          directory, kind, run.owner, ProductMetricPipelineLoadSupport.Mode.BATCH1, snapshot);
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(run.serviceTransactionObservation);
    }
  }

  /** New diagnostic errors cannot alter old timing health or the original Throwable. */
  private static void recordServiceTransactionObservationFailure(
      ProductMetricServiceTransactionObservationSupport observation) {
    try {
      observation.recordObserverFailure();
    } catch (Throwable ignoredDiagnosticFailure) {
      // Failure reporting also remains outside the original business failure domain.
    }
  }

  /** Export legal pending evidence without converting it into an observer fault or primary. */
  static Throwable completeServiceTransactionObservationAfterClose(
      Path directory,
      ProductMetricServiceTransactionObservationSupport observation,
      String owner,
      ProductMetricPipelineLoadSupport.Mode mode,
      Throwable primary) {
    if (observation == null) return primary;
    try {
      observation.assertHealthyAfterClose();
    } catch (
        ProductMetricServiceTransactionObservationSupport.ObservationIncompleteException
            incomplete) {
      // A pending registration or unproven runtime is honest incomplete evidence.
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(observation);
    }
    Map<String, Object> snapshot = null;
    try {
      snapshot = observation.snapshot();
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(observation);
    }
    try {
      writeServiceTransactionObservation(
          directory,
          ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION,
          owner,
          mode,
          snapshot);
    } catch (Throwable diagnosticFailure) {
      recordServiceTransactionObservationFailure(observation);
    }
    return primary;
  }

  /**
   * Publish only an independent create-only test sidecar; this never grants runtime eligibility.
   */
  static void writeCallbackServiceObservation(
      Path directory,
      ProductMetricApplicationTimingSupport.SnapshotKind kind,
      String owner,
      ProductMetricPipelineLoadSupport.Mode mode,
      Map<String, Object> observation)
      throws IOException {
    if (kind == null
        || mode != ProductMetricPipelineLoadSupport.Mode.BATCH1
        || owner == null
        || !owner.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("CM21_CALLBACK_SERVICE_SIDECAR_IDENTITY_INVALID");
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)
        || !directory.toAbsolutePath().equals(directory.toRealPath()))
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_DIRECTORY_INVALID");
    String status = "UNAVAILABLE";
    if (observation != null) {
      Object qualification = observation.get("qualification");
      if (!(qualification instanceof Map<?, ?> facts))
        throw new IllegalArgumentException("CM21_CALLBACK_SERVICE_SNAPSHOT_INVALID");
      status =
          Boolean.TRUE.equals(facts.get("collectorHealthy"))
                  && "COMPLETE".equals(facts.get("partitionCoverage"))
              ? "AVAILABLE"
              : "PARTIAL";
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", 1);
    envelope.put("scope", "CALLBACK_SERVICE_TEST_SIDECAR_V1");
    envelope.put("kind", kind.name());
    envelope.put("owner", owner);
    envelope.put("mode", mode.token());
    envelope.put("runIdentity", owner + "-" + mode.token());
    envelope.put("status", status);
    envelope.put("observation", observation);
    byte[] encoded = JSON.writeValueAsBytes(envelope);
    if (encoded.length > 1048576)
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_EXPORT_OVER_CAP");
    String file =
        switch (kind) {
          case BASELINE -> "baseline-10.callback-service.json";
          case MEASUREMENT_ENDED -> "measurement-ended.callback-service.json";
          case FINISH -> "finish-10.callback-service.json";
          case COMPLETION -> "completion.callback-service.json";
        };
    Files.write(directory.resolve(file), encoded, StandardOpenOption.CREATE_NEW);
  }

  /** Export new cold facts independently; a failure never becomes an old business failure. */
  private static void exportCallbackServiceObservation(
      Pipeline run, Path directory, ProductMetricApplicationTimingSupport.SnapshotKind kind) {
    if (run.callbackServiceObservation == null) return;
    Map<String, Object> snapshot = null;
    try {
      snapshot = run.callbackServiceObservation.snapshot();
    } catch (Throwable diagnosticFailure) {
      recordCallbackServiceObservationFailure(run.callbackServiceObservation);
    }
    try {
      writeCallbackServiceObservation(
          directory, kind, run.owner, ProductMetricPipelineLoadSupport.Mode.BATCH1, snapshot);
    } catch (Throwable diagnosticFailure) {
      recordCallbackServiceObservationFailure(run.callbackServiceObservation);
    }
  }

  /** Mark only the independent new diagnostic, preserving all original exception state. */
  private static void recordCallbackServiceObservationFailure(
      ProductMetricCallbackServiceObservationSupport observation) {
    try {
      observation.recordObserverFailure();
    } catch (Throwable ignoredDiagnosticFailure) {
      // Even diagnostic failure reporting cannot replace the original business outcome.
    }
  }

  /**
   * Post-close health is new diagnostic evidence only; the existing primary is returned unchanged.
   */
  static Throwable completeCallbackServiceObservationAfterClose(
      Path directory,
      ProductMetricCallbackServiceObservationSupport observation,
      String owner,
      ProductMetricPipelineLoadSupport.Mode mode,
      Throwable primary) {
    if (observation == null) return primary;
    try {
      observation.assertHealthyAfterClose();
    } catch (Throwable diagnosticFailure) {
      recordCallbackServiceObservationFailure(observation);
    }
    Map<String, Object> snapshot = null;
    try {
      snapshot = observation.snapshot();
    } catch (Throwable diagnosticFailure) {
      recordCallbackServiceObservationFailure(observation);
    }
    try {
      writeCallbackServiceObservation(
          directory,
          ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION,
          owner,
          mode,
          snapshot);
    } catch (Throwable diagnosticFailure) {
      recordCallbackServiceObservationFailure(observation);
    }
    return primary;
  }

  /** Encode one cold, bounded diagnostic snapshot before publishing its fixed create-only file. */
  static void writeApplicationTiming(
      Path directory,
      ProductMetricApplicationTimingSupport.SnapshotKind kind,
      Map<String, Object> snapshot)
      throws IOException {
    if (snapshot == null) return;
    if (kind == null || !kind.name().equals(snapshot.get("snapshotKind")))
      throw new IllegalArgumentException("CM21_APPLICATION_TIMING_SNAPSHOT_KIND_INVALID");
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)
        || !directory.toAbsolutePath().equals(directory.toRealPath()))
      throw new IllegalStateException("CM21_APPLICATION_TIMING_DIRECTORY_INVALID");
    byte[] encoded = JSON.writeValueAsBytes(snapshot);
    if (encoded.length > 1048576)
      throw new IllegalStateException("CM21_APPLICATION_TIMING_EXPORT_OVER_CAP");
    String file =
        switch (kind) {
          case BASELINE -> "baseline-10.timing.json";
          case MEASUREMENT_ENDED -> "measurement-ended.timing.json";
          case FINISH -> "finish-10.timing.json";
          case COMPLETION -> "completion.timing.json";
        };
    Files.write(directory.resolve(file), encoded, StandardOpenOption.CREATE_NEW);
  }

  /** Require the selected cold-boundary health before exporting only the optional diagnostic. */
  private static void exportApplicationTiming(
      Pipeline run,
      Path directory,
      ProductMetricApplicationTimingSupport.SnapshotKind kind,
      boolean requireIdle)
      throws IOException {
    if (run.applicationTiming == null) return;
    run.applicationTiming.assertHealthy(requireIdle);
    writeApplicationTiming(directory, kind, run.loadSupport.timingSnapshot(kind));
  }

  /** Preserve the first failure while attempting only the post-close diagnostic health/export. */
  static Throwable completeApplicationTimingAfterClose(
      Path directory,
      ProductMetricPipelineLoadSupport observer,
      ProductMetricApplicationTimingSupport timing,
      boolean closeReturned,
      boolean closeFailed,
      Throwable primary) {
    if (timing == null) return primary;
    if (observer == null)
      return ProductMetricPipelineLoadSupport.combine(
          primary, new IllegalStateException("CM21_APPLICATION_TIMING_OBSERVER_MISSING"));
    try {
      timing.assertHealthy(closeReturned && !closeFailed);
    } catch (Exception | Error diagnosticFailure) {
      primary = ProductMetricPipelineLoadSupport.combine(primary, diagnosticFailure);
    }
    try {
      writeApplicationTiming(
          directory,
          ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION,
          observer.timingSnapshot(ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION));
    } catch (Exception | Error diagnosticPublicationFailure) {
      primary = ProductMetricPipelineLoadSupport.combine(primary, diagnosticPublicationFailure);
    }
    return primary;
  }

  /** Explicit one-stage entry; the legacy main above retains its original ladder and exit rules. */
  public static final class Single10 {
    /** Utility entry has no independent lifecycle owner. */
    private Single10() {}

    /** Run exactly one externally driven single1 or batch1 stage and publish only after close. */
    public static void main(String[] arguments) throws Exception {
      if (arguments.length != 1) throw new IllegalArgumentException("CM21_SINGLE10_MODE_REQUIRED");
      var mode = ProductMetricPipelineLoadSupport.Mode.parse(arguments[0]);
      var queryInfoAdmission =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE, 300, mode);
      var statementAdmission =
          ProductMetricStatementEnvelopeSupport.captureAdmission(queryInfoAdmission);
      boolean batch = queryInfoAdmission.batch();
      ProductMetricPipelineLoadSupport.admit(
          mode,
          batch,
          queryInfoAdmission.consumers(),
          300,
          queryInfoAdmission.phaseTiming(),
          queryInfoAdmission.phaseAnchor());
      boolean applicationTimingEnabled =
          applicationTimingRequested(
              statementAdmission.enabled()
                  ? Boolean.toString(statementAdmission.applicationTiming())
                  : System.getProperty("cloudmall.cm21.single10.application-timing", "false"),
              true,
              mode,
              queryInfoAdmission.consumers(),
              300,
              queryInfoAdmission.phaseTiming(),
              queryInfoAdmission.phaseAnchor());
      boolean callbackEnabled =
          callbackServiceObservationRequested(
              statementAdmission.enabled()
                  ? Boolean.toString(statementAdmission.callbackObservation())
                  : System.getProperty(
                      "cloudmall.cm21.single10.callback-service-observation", "false"),
              true,
              mode,
              queryInfoAdmission.consumers(),
              300,
              applicationTimingEnabled,
              queryInfoAdmission.phaseTiming(),
              queryInfoAdmission.phaseAnchor());
      serviceTransactionObservationRequested(
          statementAdmission.enabled()
              ? Boolean.toString(statementAdmission.transactionObservation())
              : System.getProperty(
                  "cloudmall.cm21.single10.service-transaction-observation", "false"),
          true,
          mode,
          queryInfoAdmission.consumers(),
          300,
          applicationTimingEnabled,
          callbackEnabled,
          queryInfoAdmission.phaseTiming(),
          queryInfoAdmission.phaseAnchor());
      String owner = required("owner");
      ProductMetricQueryInfoCacheSupport.actor("claim", queryInfoAdmission);
      var completion =
          ProductMetricPipelineLoadSupport.Completion.claim(
              Path.of(""),
              owner,
              mode,
              System.getProperty("cloudmall.cm21.single10.completion-file"));
      Pipeline run = null;
      Throwable primary = null;
      boolean workComplete = false, closeReturned = false, closeFailed = false;
      try {
        // 1. Keep original seeds, Stock offsets, page shape and owned production graph.
        run =
            new Pipeline(
                ProductMetricPipelineLoadSupport.ROLE,
                300,
                mode,
                queryInfoAdmission,
                statementAdmission);
        if (run.applicationTiming != null && !owner.equals(run.owner))
          throw new IllegalStateException("CM21_APPLICATION_TIMING_RUN_IDENTITY_MISMATCH");
        run.seedNormal("202610cm21loadnormal", 3);
        run.seedSeckill("202610cm21loadseckill");
        run.start();
        run.confirm("202610cm21loadnormal");
        run.confirm("202610cm21loadseckill");
        run.drain();
        Path directory = completion.directory();
        List<Long> ids =
            run.product.jdbc.queryForList("SELECT id FROM product ORDER BY id", Long.class);
        assertEquals(300, ids.size());
        Path seedIds = directory.resolve("seed-ids.csv");
        Files.write(
            seedIds,
            ids.stream().map(String::valueOf).toList(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("owner", owner);
        ready.put("mode", mode.token());
        ready.put("runIdentity", owner + "-" + mode.token());
        ready.put("runtimeIdentity", run.identity);
        ready.put("productPort", run.productPort);
        ready.put("stockPort", run.stockPort);
        ready.put("productCatalog", run.product.database);
        ready.put("stockCatalog", run.stock.database);
        ready.put("index", run.index);
        ready.put("seedIds", seedIds.toAbsolutePath().toString());
        ready.put("stageOrder", List.of(10));
        ready.put("measurementSeconds", 60);
        ready.put("evidenceDir", directory.toString());
        ready.put("runtimeFacts", run.loadSupport.runtimeFacts());
        run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.WARMUP);
        loadWrite(directory, "ready.json", ready);
        run.queryInfoSidecar(directory, "startup");
        System.out.println("CM21_SINGLE10_READY " + directory);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        var controls = new ProductMetricPipelineLoadSupport.Controls(owner, mode);
        // 2. Separate warmup, baseline, formal calls, natural drain and comparison exposure.
        single10Control(directory, "baseline-10", deadline, controls, run.applicationTiming);
        run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.BASELINE_DRAIN);
        long baselineDrain = loadDrain(run, directory, "baseline-10");
        Map<String, Object> baseline =
            loadSnapshot(run, directory, "baseline-10", deadline, baselineDrain);
        loadWrite(directory, "baseline-10.calls.json", run.loadSupport.snapshot());
        exportApplicationTiming(
            run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE, true);
        if (run.callbackServiceObservation != null)
          exportCallbackServiceObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE);
        if (run.serviceTransactionObservation != null)
          exportServiceTransactionObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE);
        run.statementEnvelopeSidecar(
            directory, ProductMetricStatementEnvelopeSupport.SnapshotKind.BASELINE);
        run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT);
        loadWrite(directory, "baseline-10.done.json", baseline);
        single10Control(directory, "finish-10", deadline, controls, run.applicationTiming);
        run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN);
        loadWrite(directory, "measurement-ended.calls.json", run.loadSupport.snapshot());
        run.queryInfoSidecar(directory, "measurement-ended");
        exportApplicationTiming(
            run,
            directory,
            ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED,
            false);
        if (run.callbackServiceObservation != null)
          exportCallbackServiceObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED);
        if (run.serviceTransactionObservation != null)
          exportServiceTransactionObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED);
        run.statementEnvelopeSidecar(
            directory, ProductMetricStatementEnvelopeSupport.SnapshotKind.MEASUREMENT_ENDED);
        long measuredDrain = loadDrain(run, directory, "finish-10");
        Map<String, Object> measured =
            loadSnapshot(run, directory, "finish-10", deadline, measuredDrain);
        run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.COMPARISON);
        single10ComparePages(run, directory, 10, deadline);
        exportApplicationTiming(
            run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.FINISH, true);
        if (run.callbackServiceObservation != null)
          exportCallbackServiceObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.FINISH);
        if (run.serviceTransactionObservation != null)
          exportServiceTransactionObservation(
              run, directory, ProductMetricApplicationTimingSupport.SnapshotKind.FINISH);
        run.statementEnvelopeSidecar(
            directory, ProductMetricStatementEnvelopeSupport.SnapshotKind.FINISH);
        loadWrite(
            directory,
            "finish-10.done.json",
            Map.of(
                "measurementSnapshot",
                measured,
                "afterComparisonCounts",
                run.counts(),
                "comparisonExposureRegistrationsSeparate",
                true,
                "callCounters",
                run.loadSupport.snapshot()));
        run.queryInfoSidecar(directory, "finish-10");
        single10Control(directory, "stop", deadline, controls, run.applicationTiming);
        run.drain();
        run.success();
        workComplete = true;
      } catch (Exception | Error originalFailure) {
        primary = originalFailure;
      } finally {
        // 3. Completion is published only after the original close returned or threw.
        if (run != null) {
          try {
            run.close();
            closeReturned = true;
          } catch (Exception | Error closeFailure) {
            closeFailed = true;
            primary = ProductMetricPipelineLoadSupport.combine(primary, closeFailure);
          }
        }
      }
      if (run != null)
        primary =
            completeApplicationTimingAfterClose(
                completion.directory(),
                run.loadSupport,
                run.applicationTiming,
                closeReturned,
                closeFailed,
                primary);
      if (run != null && run.callbackServiceObservation != null)
        primary =
            completeCallbackServiceObservationAfterClose(
                completion.directory(), run.callbackServiceObservation, run.owner, mode, primary);
      if (run != null && run.serviceTransactionObservation != null)
        primary =
            completeServiceTransactionObservationAfterClose(
                completion.directory(),
                run.serviceTransactionObservation,
                run.owner,
                mode,
                primary);
      if (primary == null && run != null) {
        try {
          run.loadSupport.assertHealthy(true);
        } catch (Exception | Error finalFailure) {
          primary = finalFailure;
        }
      }
      try {
        completion.publish(
            run == null ? null : run.identity,
            workComplete,
            closeReturned,
            closeFailed,
            run == null ? null : run.loadSupport,
            primary);
      } catch (Exception | Error publicationFailure) {
        primary = ProductMetricPipelineLoadSupport.combine(primary, publicationFailure);
      }
      if (run != null) run.queryInfoSidecar(completion.directory(), "completion");
      if (run != null)
        run.statementEnvelopeSidecar(
            completion.directory(), ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION);
      if (primary instanceof Exception exception) throw exception;
      if (primary instanceof Error fatal) throw fatal;
    }
  }

  /** Validate only this new owned run's ordered single10 commands and original process identity. */
  private static JsonNode single10Control(
      Path directory,
      String phase,
      long deadline,
      ProductMetricPipelineLoadSupport.Controls controls,
      ProductMetricApplicationTimingSupport applicationTiming)
      throws Exception {
    Path file = directory.resolve(phase + ".control.json");
    long phaseDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MINUTES.toNanos(6));
    while (true) {
      ProductMetricPipelineLoadSupport.Controls.verifyFiles(directory);
      for (String invalid : List.of("baseline-25", "finish-25", "baseline-50", "finish-50"))
        if (Files.exists(directory.resolve(invalid + ".control.json")))
          throw new IllegalStateException("CM21_SINGLE10_OTHER_LEVEL_FORBIDDEN");
      if (Files.exists(directory.resolve("abort.control.json"))) {
        JsonNode abort = JSON.readTree(Files.readAllBytes(directory.resolve("abort.control.json")));
        try {
          controls.accept("abort", abort);
        } catch (ProductMetricPipelineLoadSupport.Aborted accepted) {
          try {
            loadWrite(directory, "abort.accepted.json", abort);
          } catch (Exception | Error publicationFailure) {
            ProductMetricPipelineLoadSupport.combine(accepted, publicationFailure);
          }
          throw accepted;
        }
      }
      if (applicationTiming != null) applicationTiming.assertHealthy(false);
      loadDeadline(phaseDeadline);
      if (Files.exists(file)) break;
      Thread.sleep(250);
    }
    JsonNode control = JSON.readTree(Files.readAllBytes(file));
    controls.accept(phase, control);
    loadWrite(directory, phase + ".accepted.json", control);
    return control;
  }

  /** CREATE_NEW native files are never overwritten by a later stage or retry. */
  private static void loadWrite(Path directory, String name, Object value) throws Exception {
    Files.write(
        directory.resolve(name), JSON.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
  }

  /** Accept only a completed external process identity; this code never starts or kills JMeter. */
  private static JsonNode loadControl(Path directory, String phase, long deadline)
      throws Exception {
    Path file = directory.resolve(phase + ".control.json");
    long phaseDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MINUTES.toNanos(6));
    while (true) {
      if (Files.exists(directory.resolve("abort.control.json"))) {
        JsonNode abort = JSON.readTree(Files.readAllBytes(directory.resolve("abort.control.json")));
        loadWrite(directory, "abort.accepted.json", abort);
        throw new IllegalStateException("CM21_LOAD_ABORTED");
      }
      loadDeadline(phaseDeadline);
      if (Files.exists(file)) break;
      Thread.sleep(250);
    }
    JsonNode control = JSON.readTree(Files.readAllBytes(file));
    if (!control.isObject())
      throw new IllegalArgumentException("CM21_LOAD_CONTROL_OBJECT_REQUIRED");
    if (!"stop".equals(phase)) {
      if (!control.path("jmeterPid").canConvertToLong()
          || control.path("jmeterPid").asLong() <= 0
          || !control.path("jmeterStartedAt").isTextual()
          || !control.path("jmeterExitCode").isIntegralNumber()
          || control.path("jmeterExitCode").asInt() != 0)
        throw new IllegalArgumentException("CM21_JMETER_EXIT_IDENTITY_REQUIRED");
      Instant expectedStart = Instant.parse(control.path("jmeterStartedAt").textValue());
      Optional<ProcessHandle> current = ProcessHandle.of(control.path("jmeterPid").asLong());
      if (current.isPresent() && current.get().isAlive()) {
        Instant actualStart =
            current
                .get()
                .info()
                .startInstant()
                .orElseThrow(
                    () -> new IllegalStateException("CM21_JMETER_CURRENT_GENERATION_UNKNOWN"));
        if (actualStart.equals(expectedStart))
          throw new IllegalStateException("CM21_JMETER_STILL_RUNNING");
      }
    }
    loadWrite(directory, phase + ".accepted.json", control);
    return control;
  }

  /** Every stage wait and snapshot also shares the bounded main lifecycle budget. */
  private static void loadDeadline(long deadline) {
    if (System.nanoTime() >= deadline) throw new IllegalStateException("CM21_LOAD_CONTROL_TIMEOUT");
  }

  /**
   * Record the actual natural drain interval; a nominal await timeout is not elapsed-time proof.
   */
  private static long loadDrain(Pipeline run, Path directory, String phase) throws Exception {
    long started = System.nanoTime(), elapsed = 0;
    Instant observedAt = Instant.now();
    boolean complete = false;
    Throwable problem = null;
    try {
      run.drain();
      complete = true;
    } catch (Exception | Error failure) {
      problem = failure;
      throw failure;
    } finally {
      long ended = System.nanoTime();
      elapsed = TimeUnit.NANOSECONDS.toMillis(ended - started);
      try {
        loadWrite(
            directory,
            phase + ".drain.json",
            Map.of(
                "startedAt",
                observedAt.toString(),
                "startedNanos",
                started,
                "endedNanos",
                ended,
                "elapsedMillis",
                elapsed,
                "completed",
                complete,
                "within120Seconds",
                elapsed <= 120000));
      } catch (Exception auditFailure) {
        if (problem != null) problem.addSuppressed(auditFailure);
        else throw auditFailure;
      }
    }
    if (elapsed > 120000)
      throw new IllegalStateException("CM21_LOAD_NATURAL_DRAIN_EXCEEDS_120_SECONDS");
    return elapsed;
  }

  /** Capture actual committed rows and ES sources; the independent root auditor derives deltas. */
  private static Map<String, Object> loadSnapshot(
      Pipeline run, Path directory, String phase, long deadline, long drainMillis)
      throws Exception {
    ProductMetricService.Cut cut = run.context.getBean(ProductMetricService.class).cut();
    assertTrue(cut.admissible());
    Map<String, Long> counts = run.counts();
    Map<String, String> productFacts = new LinkedHashMap<>(), stockFacts = new LinkedHashMap<>();
    Set<String> productTables = new TreeSet<>(run.product.originalTables);
    productTables.addAll(
        List.of(
            "product_metric_control",
            "product_online_stat",
            "product_metric_applied_event",
            "product_metric_outbox",
            "product_metric_projection_outbox",
            "product_metric_quarantine"));
    Set<String> stockTables = new TreeSet<>(run.stock.originalTables);
    stockTables.add("stock_sales_outbox");
    for (String table : productTables) {
      loadDeadline(deadline);
      String name = phase + "-product-" + table + ".json";
      loadWrite(directory, name, run.product.tableFacts(table));
      productFacts.put(table, name);
    }
    for (String table : stockTables) {
      loadDeadline(deadline);
      String name = phase + "-stock-" + table + ".json";
      loadWrite(directory, name, run.stock.tableFacts(table));
      stockFacts.put(table, name);
    }
    List<JsonNode> documents = new ArrayList<>();
    for (long id :
        run.product.jdbc.queryForList("SELECT id FROM product ORDER BY id", Long.class)) {
      loadDeadline(deadline);
      documents.add(run.document(id));
    }
    String esFacts = phase + "-es-documents.json";
    loadWrite(directory, esFacts, documents);
    assertEquals(counts, run.counts());
    assertEquals(cut, run.context.getBean(ProductMetricService.class).cut());
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("counts", counts);
    snapshot.put("cut", cut);
    snapshot.put("naturalDrainElapsedMillis", drainMillis);
    snapshot.put("capturedAt", Instant.now().toString());
    snapshot.put("productTables", productFacts);
    snapshot.put("stockTables", stockFacts);
    snapshot.put("esDocuments", esFacts);
    snapshot.put("documentCount", documents.size());
    snapshot.put("beforeComparison", true);
    loadWrite(directory, phase + ".snapshot.json", snapshot);
    return snapshot;
  }

  /** Page20 comparison registers new exposure events only after the measured snapshot was saved. */
  private static void loadComparePages(Pipeline run, Path directory, int level, long deadline)
      throws Exception {
    MessageListenerContainer consumer =
        run.context
            .getBean(RabbitListenerEndpointRegistry.class)
            .getListenerContainer("productMetrics");
    consumer.stop();
    assertFalse(consumer.isRunning());
    List<Map<String, Object>> pages = new ArrayList<>();
    Map<String, Long> before = run.counts();
    try {
      ProductMetricService.Cut cut = run.context.getBean(ProductMetricService.class).cut();
      assertTrue(cut.admissible());
      ProductSqlMapper sql = run.context.getBean(ProductSqlMapper.class);
      for (String sort : List.of("sales", "hot"))
        for (int page : List.of(1, 2)) {
          loadDeadline(deadline);
          TransactionTemplate transaction =
              new TransactionTemplate(
                  run.context.getBean(
                      org.springframework.transaction.PlatformTransactionManager.class));
          transaction.setIsolationLevel(
              org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
          transaction.setReadOnly(true);
          transaction.setTimeout(3);
          int offset = (page - 1) * 20;
          Map<String, Object> expected =
              transaction.execute(
                  status ->
                      Map.of(
                          "total",
                          sql.countProducts("Metric", null, 1),
                          "ids",
                          sql.findProductsOnline("Metric", null, 1, offset, 20, sort).stream()
                              .map(row -> ((Number) row.get("id")).longValue())
                              .toList(),
                          "revision",
                          run.context.getBean(ProductMetricService.class).sqlRevision()));
          JsonNode response = run.search(sort, page, 20);
          List<Long> actual = new ArrayList<>();
          for (JsonNode item : response.path("data").path("items"))
            actual.add(item.path("id").asLong());
          assertEquals(expected.get("ids"), actual);
          assertEquals(
              ((Number) expected.get("total")).longValue(),
              response.path("data").path("total").asLong());
          assertEquals(cut.revision(), response.path("data").path("metricReadRevision").asLong());
          assertEquals(cut.revision(), expected.get("revision"));
          pages.add(Map.of("sort", sort, "page", page, "sql", expected, "http", response));
        }
      assertEquals(cut, run.context.getBean(ProductMetricService.class).cut());
    } finally {
      consumer.start();
    }
    long drainMillis = loadDrain(run, directory, "comparison-" + level);
    loadWrite(
        directory,
        "comparison-" + level + ".json",
        Map.of(
            "before",
            before,
            "after",
            run.counts(),
            "pages",
            pages,
            "naturalDrainElapsedMillis",
            drainMillis));
  }

  /** Compare the same four page20 results only after the new entry's actual stop qualification. */
  private static void single10ComparePages(Pipeline run, Path directory, int level, long deadline)
      throws Exception {
    MessageListenerContainer consumer =
        run.context
            .getBean(RabbitListenerEndpointRegistry.class)
            .getListenerContainer("productMetrics");
    consumer.stop();
    assertFalse(consumer.isRunning());
    ProductMetricPipelineContentEsSupport pauseSupport =
        run.batchMode ? new ProductMetricPipelineContentEsSupport(run::audit) : null;
    ProductMetricPipelineContentEsSupport.Pause permit =
        pauseSupport == null
            ? null
            : pauseSupport.pauseAfterStop(
                (SimpleMessageListenerContainer) consumer,
                run.context.getBean(ProductMetricConsumer.class),
                run.context.getBean(ProductMetricBatchChannelRegistry.class));
    if (permit == null)
      ProductMetricPipelineLoadSupport.requireSingleStopped(
          (SimpleMessageListenerContainer) consumer);
    Throwable pageFailure = null;
    List<Map<String, Object>> pages = new ArrayList<>();
    Map<String, Long> before = run.counts();
    try {
      ProductMetricService.Cut cut = run.context.getBean(ProductMetricService.class).cut();
      assertTrue(cut.admissible());
      ProductSqlMapper sql = run.context.getBean(ProductSqlMapper.class);
      for (String sort : List.of("sales", "hot"))
        for (int page : List.of(1, 2)) {
          loadDeadline(deadline);
          TransactionTemplate transaction =
              new TransactionTemplate(
                  run.context.getBean(
                      org.springframework.transaction.PlatformTransactionManager.class));
          transaction.setIsolationLevel(
              org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
          transaction.setReadOnly(true);
          transaction.setTimeout(3);
          int offset = (page - 1) * 20;
          Map<String, Object> expected =
              transaction.execute(
                  status ->
                      Map.of(
                          "total",
                          sql.countProducts("Metric", null, 1),
                          "ids",
                          sql.findProductsOnline("Metric", null, 1, offset, 20, sort).stream()
                              .map(row -> ((Number) row.get("id")).longValue())
                              .toList(),
                          "revision",
                          run.context.getBean(ProductMetricService.class).sqlRevision()));
          JsonNode response = run.search(sort, page, 20);
          List<Long> actual = new ArrayList<>();
          for (JsonNode item : response.path("data").path("items"))
            actual.add(item.path("id").asLong());
          assertEquals(expected.get("ids"), actual);
          assertEquals(
              ((Number) expected.get("total")).longValue(),
              response.path("data").path("total").asLong());
          assertEquals(cut.revision(), response.path("data").path("metricReadRevision").asLong());
          assertEquals(cut.revision(), expected.get("revision"));
          pages.add(Map.of("sort", sort, "page", page, "sql", expected, "http", response));
        }
      assertEquals(cut, run.context.getBean(ProductMetricService.class).cut());
    } catch (Exception | Error originalFailure) {
      pageFailure = originalFailure;
      throw originalFailure;
    } finally {
      if (permit == null)
        ProductMetricPipelineLoadSupport.resumeSingle(
            (SimpleMessageListenerContainer) consumer, pageFailure);
      else {
        permit.resume(pageFailure);
        if (pageFailure == null
            && (pauseSupport.failureCode() != null
                || !Boolean.TRUE.equals(permit.facts().get("resumeReturned"))))
          throw new IllegalStateException("CM21_SINGLE10_COMPARISON_RESUME_UNKNOWN");
      }
    }
    run.loadSupport.advance(ProductMetricPipelineLoadSupport.Stage.POST_COMPARISON_DRAIN);
    long drainMillis = loadDrain(run, directory, "comparison-" + level);
    loadWrite(
        directory,
        "comparison-" + level + ".json",
        Map.of(
            "before",
            before,
            "after",
            run.counts(),
            "pages",
            pages,
            "naturalDrainElapsedMillis",
            drainMillis));
  }

  /**
   * Each fixture owns its catalogs/vhost/index; root owns the three labelled middleware containers.
   */
  static final class Pipeline implements AutoCloseable {
    /** Resource-free tests observe real ctor-assigned snapshot fields before the first actor. */
    static final ThreadLocal<java.util.function.Consumer<Pipeline>> ADMISSION_OBSERVER =
        new ThreadLocal<>();

    /** One immutable context shared from actor-free entry capture through every product raw. */
    final ProductMetricQueryInfoCacheSupport.Admission queryInfoAdmission;

    /** Frozen optional qualification, captured before this fixture initializes any actor field. */
    final ProductMetricStatementEnvelopeSupport.Admission statementAdmission;

    /** Optional bounded dual-statement evidence, never allocated for the default-off branch. */
    ProductMetricStatementEnvelopeSupport statementEnvelope;

    /** Safe explicit run label binds every external resource check. */
    final String owner = admittedOwner();

    /** Reject unsupported anchor enablement before any fixture field can allocate resources. */
    private static String admittedOwner() {
      return required("owner");
    }

    /** Fresh UUID also names the only writable ES index and broker vhost. */
    final String identity = UUID.randomUUID().toString().replace("-", "");

    /** Each middleware observation is CREATE_NEW even before either catalog is constructed. */
    final Path resourceEvidence;

    /** Reviewed V2 physical index, never the shared legacy index. */
    final String index = "cm21_" + identity + "_product";

    /** Only this freshly created vhost may be changed or deleted. */
    final String vhost = "cm21_" + identity;

    /** Original full-facts/connection ownership helpers are reused within the same test module. */
    final ProductOnlineMetricsMySqlIT.OwnedDatabase product;

    final ProductOnlineMetricsMySqlIT.OwnedDatabase stock;

    /** One original public result register per observed request, saved before assertions. */
    final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());

    /** Real manual-ACK fault changes only this fixture's channel before its original ACK. */
    final AtomicBoolean ackFault = new AtomicBoolean(),
        ackClosed = new AtomicBoolean(),
        redelivered = new AtomicBoolean();

    /** The exact real delivery whose original ACK was deliberately interrupted. */
    volatile String faultEventId;

    /** Refresh generation distinguishes a broker retry in the newly created real context. */
    final AtomicInteger contextGeneration = new AtomicInteger();

    /** Only a post-commit interrupted ACK records this generation. */
    volatile int faultGeneration;

    /**
     * No SDK endpoint is shared; all three unbound loopback ports are checked immediately at
     * launch.
     */
    final int nacosBase;

    /** Exact declared source SKU identities differ for every fixture and survive retry. */
    final long normalSku =
        (UUID.randomUUID().getMostSignificantBits() & 0x3fffffffffffffffL) + 1000;

    final long seckillSku = normalSku + 1;

    /** Own real Product HTTP context; all business beans come from main sources. */
    ConfigurableApplicationContext context;

    /** Bounded real JDBC pool closes before the underlying counted raw-resource gate. */
    HikariDataSource pool;

    /** Allocated only after complete owned load/two-consumer admission. */
    PhaseTiming phaseTiming;

    /** Manual administration connection and its shared parent/publisher deferred-task pool. */
    OwnedProductMetricAdminConnection adminResources;

    RabbitAdmin admin;

    /** Only this ProcessBuilder-created Stock JVM may be stopped by the fixture. */
    Process stockProcess;

    Instant stockStarted;

    /** Ports are explicit loopback endpoints whose liveness is verified at close. */
    int productPort, stockPort;

    /** Standard shutdown is exposed only on this independently owned loopback management port. */
    int stockManagementPort;

    /** A single ES restart is permitted only in the third fixed role. */
    boolean esRestarted;

    /**
     * Cleanup requires complete business assertions, drain and independent resource close proof.
     */
    boolean passed;

    /** Exact source order identities also delimit the original cache keys eligible for cleanup. */
    final Set<String> seededOrders = new LinkedHashSet<>();

    /** Scenario is part of immutable native audit identity. */
    final String role;

    /** This test-only setting never changes the production factory's default concurrency. */
    final int metricConsumers;

    /** Explicit healthy-fixture batch selection; production never reads this test flag. */
    final boolean batchMode;

    /** Outer-proxy commit observations never replace listener, channel or transaction behavior. */
    ProductMetricPipelineBatchSupport.CommitObserver batchObserver;

    /** Only the new fault role owns a two-context observation coordinator. */
    ProductMetricPipelineRedeliverySupport batchRedelivery;

    /** Bounded restart searches and real same-context pause gates for the content/ES case. */
    ProductMetricPipelineContentEsSupport batchContentEs;

    /** Only the explicit single10 entry uses constant-space, SQL-free call observation. */
    ProductMetricPipelineLoadSupport loadSupport;

    /** One explicitly enabled test-only duration collector, never the legacy phase observer. */
    ProductMetricApplicationTimingSupport applicationTiming;

    /** Independently enabled fixed-space callback and observed-apply correlation. */
    ProductMetricCallbackServiceObservationSupport callbackServiceObservation;

    /** Independently enabled transaction markers retain the original manager and DataSource. */
    ProductMetricServiceTransactionObservationSupport serviceTransactionObservation;

    /** Current context's independent callback and commit observations. */
    ProductMetricPipelineRedeliverySupport.Generation batchRedeliveryGeneration;

    /** Previous observer instances remain available for the final per-generation evidence. */
    final Map<Integer, ProductMetricPipelineBatchSupport.CommitObserver> batchObservers =
        new LinkedHashMap<>();

    /** Initialized production listener identity is retained only for a no-replacement assertion. */
    Object batchOriginalListener;

    /** Only a complete constructor admission allows the customizer to select two workers. */
    boolean consumerIsolationAdmitted;

    /** Real current-generation container retained solely for fixture lifecycle observations. */
    volatile SimpleMessageListenerContainer metricContainer;

    /** The first failed44 retry transfer pauses before its real NACK until binding restoration. */
    final AtomicBoolean pauseNackObserved = new AtomicBoolean();

    /** Bounded observation barrier; the production NACK and stop remain original operations. */
    final CountDownLatch pauseNackEntered = new CountDownLatch(1),
        pauseNackRelease = new CountDownLatch(1);

    /** Actual returned/confirmed retry publication, never a mocked publisher result. */
    final CountDownLatch pauseReturned = new CountDownLatch(1),
        pauseConfirmReturned = new CountDownLatch(1);

    /**
     * Observed SQL failures/deliveries use unique evidence names even when a real race redelivers.
     */
    final AtomicInteger pauseSqlFailures = new AtomicInteger(),
        pauseDeliveries = new AtomicInteger(),
        pauseReturns = new AtomicInteger(),
        pauseConfirms = new AtomicInteger();

    /** Callback evidence failures fail the test rather than disappearing on broker threads. */
    final AtomicReference<Throwable> dualObservationFailure = new AtomicReference<>();

    /** Only42/43 deliveries in the armed phase stop at the two post-commit ACK barriers. */
    volatile boolean dualAckArmed;

    /** The exact first production pause completion, retained even if a later token is allocated. */
    volatile CountDownLatch pauseStopCompletion;

    /** First actual pause token/deadline are read without manufacturing lifecycle state. */
    volatile long pauseFirstStopToken, pauseFirstDeadline;

    /** Original generation whose two actual ACKs are interrupted after both commits. */
    volatile int dualFaultGeneration;

    /** Both genuine worker channels reach these barriers without holding a business transaction. */
    final CountDownLatch dualAckEntered = new CountDownLatch(2),
        dualAckRelease = new CountDownLatch(1),
        dualReplayEntered = new CountDownLatch(2),
        dualReplayRelease = new CountDownLatch(1);

    /** Exact original deliveries and replay facts, keyed by immutable event identity. */
    final Map<String, Channel> dualChannels = new ConcurrentHashMap<>(),
        dualReplayChannels = new ConcurrentHashMap<>();

    /** Original immutable wire bytes must survive the new-context broker redelivery. */
    final Map<String, byte[]> dualBodies = new ConcurrentHashMap<>();

    /** Native ACK-boundary facts avoid serializing actual Channel objects. */
    final Map<String, Map<String, Object>> dualAckFacts = new ConcurrentHashMap<>(),
        dualReplayFacts = new ConcurrentHashMap<>();

    /** Preserve legacy and finite callers by delegating without a selected single10 mode. */
    Pipeline(String role, int count) throws Exception {
      this(role, count, null);
    }

    /** Bind the explicit single10 mode before any owned database or middleware inspection. */
    Pipeline(String role, int count, ProductMetricPipelineLoadSupport.Mode selectedMode)
        throws Exception {
      this(
          role,
          count,
          selectedMode,
          ProductMetricQueryInfoCacheSupport.capture(role, count, selectedMode));
    }

    /** Validate a supplied snapshot before any instance initializer can create an actor. */
    Pipeline(
        String role,
        int count,
        ProductMetricPipelineLoadSupport.Mode selectedMode,
        ProductMetricQueryInfoCacheSupport.Admission admission)
        throws Exception {
      this(
          role,
          count,
          selectedMode,
          admission,
          ProductMetricStatementEnvelopeSupport.captureAdmission(admission));
    }

    /** Consume the entry's two immutable qualifications without recapturing mutable properties. */
    Pipeline(
        String role,
        int count,
        ProductMetricPipelineLoadSupport.Mode selectedMode,
        ProductMetricQueryInfoCacheSupport.Admission admission,
        ProductMetricStatementEnvelopeSupport.Admission statementAdmission)
        throws Exception {
      this(
          new EntryAdmission(
              ProductMetricQueryInfoCacheSupport.requireContext(
                  admission, role, count, selectedMode),
              ProductMetricStatementEnvelopeSupport.requireContext(
                  statementAdmission,
                  role,
                  count,
                  selectedMode,
                  admission.batch(),
                  admission.consumers(),
                  "true".equals(admission.phaseTiming()),
                  "true".equals(admission.phaseAnchor()))));
    }

    /** A private constructor cannot bypass either intrinsic or combined shape validation. */
    private record EntryAdmission(
        ProductMetricQueryInfoCacheSupport.Admission query,
        ProductMetricStatementEnvelopeSupport.Admission statement) {
      /** Validate again at the final pre-initializer boundary without reading system properties. */
      private EntryAdmission {
        ProductMetricQueryInfoCacheSupport.requireContext(
            query, query.role(), query.seeds(), query.mode());
        ProductMetricStatementEnvelopeSupport.requireContext(
            statement,
            query.role(),
            query.seeds(),
            query.mode(),
            query.batch(),
            query.consumers(),
            "true".equals(query.phaseTiming()),
            "true".equals(query.phaseAnchor()));
      }
    }

    /**
     * All original graph setup uses the same already validated snapshot, never reread shape flags.
     */
    private Pipeline(EntryAdmission entry) throws Exception {
      ProductMetricQueryInfoCacheSupport.Admission admission = entry.query();
      this.queryInfoAdmission = admission;
      this.statementAdmission = entry.statement();
      this.role = admission.role();
      int count = admission.seeds();
      ProductMetricPipelineLoadSupport.Mode selectedMode = admission.mode();
      metricConsumers = admission.consumers();
      batchMode = admission.batch();
      java.util.function.Consumer<Pipeline> observer = ADMISSION_OBSERVER.get();
      if (observer != null) observer.accept(this);
      String timing = admission.phaseTiming();
      if (!"true".equals(timing) && !"false".equals(timing))
        throw new IllegalArgumentException("CM21_PHASE_TIMING_BOOLEAN_REQUIRED");
      boolean applicationTimingEnabled =
          applicationTimingRequested(
              statementAdmission.enabled()
                  ? Boolean.toString(statementAdmission.applicationTiming())
                  : System.getProperty("cloudmall.cm21.single10.application-timing", "false"),
              ProductMetricPipelineLoadSupport.ROLE.equals(role),
              selectedMode,
              metricConsumers,
              count,
              timing,
              admission.phaseAnchor());
      boolean transactionEnabled =
          serviceTransactionObservationRequested(
              statementAdmission.enabled()
                  ? Boolean.toString(statementAdmission.transactionObservation())
                  : System.getProperty(
                      "cloudmall.cm21.single10.service-transaction-observation", "false"),
              ProductMetricPipelineLoadSupport.ROLE.equals(role),
              selectedMode,
              metricConsumers,
              count,
              applicationTimingEnabled,
              "true"
                  .equals(
                      statementAdmission.enabled()
                          ? Boolean.toString(statementAdmission.callbackObservation())
                          : System.getProperty(
                              "cloudmall.cm21.single10.callback-service-observation", "false")),
              timing,
              admission.phaseAnchor());
      if (ProductMetricPipelineLoadSupport.ROLE.equals(role)) {
        ProductMetricPipelineLoadSupport.admit(
            selectedMode, batchMode, metricConsumers, count, timing, admission.phaseAnchor());
        boolean callbackEnabled =
            callbackServiceObservationRequested(
                statementAdmission.enabled()
                    ? Boolean.toString(statementAdmission.callbackObservation())
                    : System.getProperty(
                        "cloudmall.cm21.single10.callback-service-observation", "false"),
                true,
                selectedMode,
                metricConsumers,
                count,
                applicationTimingEnabled,
                timing,
                admission.phaseAnchor());
        if (callbackEnabled) {
          java.util.function.LongSupplier sharedNanoClock = System::nanoTime;
          if (transactionEnabled)
            serviceTransactionObservation =
                new ProductMetricServiceTransactionObservationSupport(
                    sharedNanoClock, () -> loadSupport.currentStage());
          if (statementAdmission.enabled())
            statementEnvelope =
                new ProductMetricStatementEnvelopeSupport(
                    owner + "-" + selectedMode.token(),
                    selectedMode,
                    sharedNanoClock,
                    () -> loadSupport.currentStage());
          applicationTiming =
              new ProductMetricApplicationTimingSupport(
                  owner + "-" + selectedMode.token(),
                  selectedMode,
                  sharedNanoClock,
                  serviceTransactionObservation,
                  statementEnvelope);
          callbackServiceObservation =
              new ProductMetricCallbackServiceObservationSupport(
                  sharedNanoClock, () -> loadSupport.currentStage());
          loadSupport =
              new ProductMetricPipelineLoadSupport(
                  selectedMode,
                  applicationTiming,
                  callbackServiceObservation,
                  serviceTransactionObservation);
        } else {
          if (applicationTimingEnabled)
            applicationTiming =
                new ProductMetricApplicationTimingSupport(
                    owner + "-" + selectedMode.token(), selectedMode, System::nanoTime);
          loadSupport = new ProductMetricPipelineLoadSupport(selectedMode, applicationTiming);
        }
      } else {
        callbackServiceObservationRequested(
            statementAdmission.enabled()
                ? Boolean.toString(statementAdmission.callbackObservation())
                : System.getProperty(
                    "cloudmall.cm21.single10.callback-service-observation", "false"),
            false,
            selectedMode,
            metricConsumers,
            count,
            applicationTimingEnabled,
            timing,
            admission.phaseAnchor());
        if (selectedMode != null)
          throw new IllegalArgumentException("CM21_SINGLE10_LEGACY_MODE_FORBIDDEN");
        ProductMetricPipelineBatchSupport.admit(batchMode, role, metricConsumers, timing);
      }
      if ("true".equals(timing) && (!"load".equals(role) || metricConsumers != 2))
        throw new IllegalArgumentException("CM21_PHASE_TIMING_OWNED_LOAD_TWO_REQUIRED");
      if (!owner.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("CM21_OWNER_INVALID");
      ProductMetricQueryInfoCacheSupport.actor("mkdir", admission);
      resourceEvidence = Files.createDirectories(Path.of("target", "cm21-pipeline", identity));
      ProductMetricQueryInfoCacheSupport.actor("Nacos", admission);
      nacosBase = nacosPorts();
      ProductMetricQueryInfoCacheSupport.actor("HttpClient", admission);
      httpExecutor =
          Executors.newFixedThreadPool(
              4,
              task -> {
                Thread thread = new Thread(task, "cm21-http-" + identity);
                thread.setDaemon(true);
                return thread;
              });
      client =
          HttpClient.newBuilder()
              .executor(httpExecutor)
              .connectTimeout(Duration.ofSeconds(3))
              .build();
      ProductMetricQueryInfoCacheSupport.actor("inspect", admission);
      inspect("rabbit");
      inspect("es");
      inspect("redis");
      product =
          new ProductOnlineMetricsMySqlIT.OwnedDatabase("product", "pipeline-" + role, admission);
      stock = new ProductOnlineMetricsMySqlIT.OwnedDatabase("stock", "pipeline-" + role);
      product.seedProducts(count);
      product.jdbc.update("UPDATE product_sku SET id=? WHERE id=71", normalSku);
      product.jdbc.update("UPDATE product_sku SET id=? WHERE id=72", seckillSku);
      product.audit(
          "pipeline-plan",
          Map.of(
              "owner",
              owner,
              "role",
              role,
              "vhost",
              vhost,
              "index",
              index,
              "normalSku",
              normalSku,
              "seckillSku",
              seckillSku,
              "seedProducts",
              count,
              "catalogs",
              List.of(product.database, stock.database)));
      assertEquals(404, management("GET", "/api/vhosts/" + vhost, null).statusCode());
      assertEquals(201, management("PUT", "/api/vhosts/" + vhost, "{}").statusCode());
      HttpResponse<String> permissionPut =
          management(
              "PUT",
              "/api/permissions/" + vhost + "/root",
              JSON.writeValueAsString(Map.of("configure", ".*", "write", ".*", "read", ".*")));
      assertTrue(permissionPut.statusCode() == 201 || permissionPut.statusCode() == 204);
      HttpResponse<String> permissionGet =
          management("GET", "/api/permissions/" + vhost + "/root", null);
      audit(
          "vhost-permission-readback",
          Map.of(
              "putStatus",
              permissionPut.statusCode(),
              "getStatus",
              permissionGet.statusCode(),
              "getResponse",
              permissionGet.body()));
      assertEquals(200, permissionGet.statusCode());
      JsonNode granted = JSON.readTree(permissionGet.body());
      assertTrue(granted.isObject());
      for (String field : List.of("vhost", "user", "configure", "write", "read"))
        assertTrue(granted.path(field).isTextual(), field);
      assertEquals(vhost, granted.path("vhost").textValue());
      assertEquals("root", granted.path("user").textValue());
      for (String permission : List.of("configure", "write", "read"))
        assertEquals(".*", granted.path(permission).textValue(), permission);
      adminResources =
          new OwnedProductMetricAdminConnection("127.0.0.1", port("rabbit.port"), vhost);
      try {
        admin = new RabbitAdmin(adminResources.connectionFactory());
        consumerIsolationAdmitted = true;
        if (ProductMetricPipelineRedeliverySupport.ROLE.equals(role))
          batchRedelivery = new ProductMetricPipelineRedeliverySupport(this::audit);
        else if (batchMode && loadSupport == null)
          batchObserver =
              new ProductMetricPipelineBatchSupport.CommitObserver(
                  new ProductMetricPipelineBatchSupport.JdbcSnapshot(
                      product.dataSource, product.database),
                  this::audit);
        if (batchMode && ProductMetricPipelineContentEsSupport.ROLE.equals(role))
          batchContentEs = new ProductMetricPipelineContentEsSupport(this::audit);
        if ("true".equals(timing)) phaseTiming = new PhaseTiming();
      } catch (RuntimeException | Error failure) {
        try {
          adminResources.close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    /** Actual container settings and identities, including the unchanged default-one branch. */
    private Map<String, Object> metricContainerFacts() {
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("owner", owner);
      facts.put("vhost", vhost);
      facts.put("catalog", product.database);
      facts.put("brokerContainerId", System.getProperty("cloudmall.cm21.rabbit.container-id"));
      facts.put("contextGeneration", contextGeneration.get());
      facts.put("requestedConsumers", metricConsumers);
      for (String field :
          List.of(
              "concurrentConsumers", "maxConcurrentConsumers", "prefetchCount", "acknowledgeMode"))
        facts.put(
            field,
            org.springframework.test.util.ReflectionTestUtils.getField(metricContainer, field));
      facts.put("activeConsumers", metricContainer.getActiveConsumerCount());
      facts.put("running", metricContainer.isRunning());
      if (batchMode)
        facts.put(
            "batchRuntime",
            ProductMetricPipelineBatchSupport.containerFacts(
                context, metricContainer, batchOriginalListener));
      return facts;
    }

    /**
     * Observe private lifecycle state under its own gate; no deadline/state is changed in the IT.
     */
    private Map<String, Object> consumerLifecycleFacts() {
      ProductMetricConsumer consumer = context.getBean(ProductMetricConsumer.class);
      if (ProductMetricPipelineRedeliverySupport.ROLE.equals(role))
        consumer = ProductMetricPipelineRedeliverySupport.ultimateConsumer(consumer);
      Object gate =
          org.springframework.test.util.ReflectionTestUtils.getField(consumer, "stateGate");
      Map<String, Object> facts = new LinkedHashMap<>();
      synchronized (Objects.requireNonNull(gate)) {
        for (String field :
            List.of(
                "restartAfter",
                "pauseGeneration",
                "stopToken",
                "stopRequested",
                "starting",
                "closeRequested",
                "closed"))
          facts.put(
              field, org.springframework.test.util.ReflectionTestUtils.getField(consumer, field));
        CountDownLatch completion =
            (CountDownLatch)
                org.springframework.test.util.ReflectionTestUtils.getField(
                    consumer, "stopCompletion");
        facts.put("stopCompletionCount", completion == null ? null : completion.getCount());
        facts.put("observedNanos", System.nanoTime());
      }
      facts.put("activeConsumers", metricContainer.getActiveConsumerCount());
      facts.put("running", metricContainer.isRunning());
      return facts;
    }

    /**
     * Broker callback failures must remain visible even though the callback API cannot throw IO.
     */
    private void dualAudit(String name, Object fact) {
      try {
        audit(name, fact);
      } catch (Throwable failure) {
        dualObservationFailure.compareAndSet(null, failure);
      }
    }

    /**
     * Observe genuine proxy rollback and the original raw template's return/confirm correlation.
     */
    private void installDualObservers() {
      ProductMetricService metrics = context.getBean(ProductMetricService.class);
      if (!(metrics instanceof org.springframework.aop.framework.Advised advised))
        throw new IllegalStateException("CM21_ORIGINAL_TRANSACTION_PROXY_REQUIRED");
      advised.addAdvice(
          0,
          (org.aopalliance.intercept.MethodInterceptor)
              invocation -> {
                try {
                  return invocation.proceed();
                } catch (Throwable failure) {
                  if ("apply".equals(invocation.getMethod().getName())
                      && invocation.getArguments().length == 1
                      && invocation.getArguments()[0]
                          instanceof com.cloudmall.product.domain.dto.ProductMetricEventDTO event
                      && event.productId == 44L) {
                    List<Map<String, Object>> sql = new ArrayList<>();
                    for (Throwable cause = failure; cause != null; cause = cause.getCause())
                      if (cause instanceof java.sql.SQLException databaseFailure)
                        sql.add(
                            Map.of(
                                "state",
                                String.valueOf(databaseFailure.getSQLState()),
                                "code",
                                databaseFailure.getErrorCode()));
                    if (sql.stream()
                            .noneMatch(
                                value ->
                                    "45000".equals(value.get("state"))
                                        && Integer.valueOf(1644).equals(value.get("code")))
                        || TransactionSynchronizationManager.isActualTransactionActive()
                        || TransactionSynchronizationManager.isSynchronizationActive())
                      dualObservationFailure.compareAndSet(
                          null,
                          new IllegalStateException("CM21_LEDGER_SIGNAL_OR_ROLLBACK_NOT_OBSERVED"));
                    dualAudit(
                        "pause-sql-failure-" + pauseSqlFailures.incrementAndGet(),
                        Map.of(
                            "eventId",
                            event.eventId,
                            "sql",
                            sql,
                            "failureType",
                            failure.getClass().getName(),
                            "actualTx",
                            TransactionSynchronizationManager.isActualTransactionActive(),
                            "synchronization",
                            TransactionSynchronizationManager.isSynchronizationActive()));
                  }
                  throw failure;
                }
              });
      var sender = context.getBean(ProductMetricOutboxDispatcher.class);
      var rabbit =
          (org.springframework.amqp.rabbit.core.RabbitTemplate)
              org.springframework.test.util.ReflectionTestUtils.getField(sender, "rabbit");
      assertTrue(
          rabbit.getMessageConverter()
              instanceof org.springframework.amqp.support.converter.SimpleMessageConverter);
      assertNull(
          org.springframework.test.util.ReflectionTestUtils.getField(rabbit, "returnsCallback"));
      assertNull(
          org.springframework.test.util.ReflectionTestUtils.getField(rabbit, "confirmCallback"));
      rabbit.setReturnsCallback(
          returned -> {
            var event = ProductMetricEvents.parse(returned.getMessage().getBody());
            if (event.productId == 44L
                && "cloudmall.product.metrics.retry".equals(returned.getRoutingKey())) {
              if (returned.getReplyCode() != 312)
                dualObservationFailure.compareAndSet(
                    null, new IllegalStateException("CM21_REAL_NO_ROUTE_NOT_OBSERVED"));
              dualAudit(
                  "pause-real-return-" + pauseReturns.incrementAndGet(),
                  Map.of(
                      "eventId",
                      event.eventId,
                      "replyCode",
                      returned.getReplyCode(),
                      "replyText",
                      returned.getReplyText(),
                      "exchange",
                      returned.getExchange(),
                      "routingKey",
                      returned.getRoutingKey(),
                      "bodyBase64",
                      Base64.getEncoder().encodeToString(returned.getMessage().getBody()),
                      "headers",
                      returned.getMessage().getMessageProperties().getHeaders()));
              pauseReturned.countDown();
            }
          });
      rabbit.setConfirmCallback(
          (correlation, acknowledged, cause) -> {
            if (correlation != null && correlation.getReturned() != null) {
              var returned = correlation.getReturned();
              var event = ProductMetricEvents.parse(returned.getMessage().getBody());
              if (event.productId == 44L
                  && "cloudmall.product.metrics.retry".equals(returned.getRoutingKey())) {
                if (!acknowledged)
                  dualObservationFailure.compareAndSet(
                      null, new IllegalStateException("CM21_REAL_CONFIRM_NOT_ACKED"));
                dualAudit(
                    "pause-real-confirm-return-" + pauseConfirms.incrementAndGet(),
                    Map.of(
                        "eventId",
                        event.eventId,
                        "correlationId",
                        correlation.getId(),
                        "confirmAck",
                        acknowledged,
                        "returned",
                        true,
                        "bodySha256",
                        ProductMetricEvents.sha256(returned.getMessage().getBody())));
                pauseConfirmReturned.countDown();
              }
            }
          });
    }

    /** Pass through the genuine listener; only two reviewed post-transaction Channel seams wait. */
    private void observeDualDelivery(
        Message message, Channel channel, ChannelAwareMessageListener listener, int generation)
        throws Exception {
      var event = ProductMetricEvents.parse(message.getBody());
      if (event.productId == 44L) {
        byte[] previousBody = dualBodies.putIfAbsent(event.eventId, message.getBody().clone());
        if (previousBody != null) assertArrayEquals(previousBody, message.getBody());
        int delivery = pauseDeliveries.incrementAndGet();
        audit(
            "pause-delivery-" + delivery,
            Map.of(
                "eventId",
                event.eventId,
                "bodySha256",
                ProductMetricEvents.sha256(message.getBody()),
                "bodyBase64",
                Base64.getEncoder().encodeToString(message.getBody()),
                "generation",
                generation,
                "redelivered",
                Boolean.TRUE.equals(message.getMessageProperties().isRedelivered()),
                "channelNumber",
                channel.getChannelNumber(),
                "deliveryTag",
                message.getMessageProperties().getDeliveryTag()));
      }
      Channel observed =
          (Channel)
              java.lang.reflect.Proxy.newProxyInstance(
                  ProductOnlineMetricsPipelineIT.class.getClassLoader(),
                  new Class<?>[] {Channel.class},
                  (proxy, method, arguments) -> {
                    if ("basicNack".equals(method.getName())
                        && event.productId == 44L
                        && pauseNackObserved.compareAndSet(false, true)) {
                      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                      assertEquals(
                          0L,
                          product.jdbc.queryForObject(
                              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id=?",
                              Long.class,
                              event.eventId));
                      ProductMetricConsumer consumer = context.getBean(ProductMetricConsumer.class);
                      Object stateGate =
                          org.springframework.test.util.ReflectionTestUtils.getField(
                              consumer, "stateGate");
                      synchronized (Objects.requireNonNull(stateGate)) {
                        pauseStopCompletion =
                            (CountDownLatch)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                    consumer, "stopCompletion");
                        pauseFirstStopToken =
                            (long)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                    consumer, "stopToken");
                        pauseFirstDeadline =
                            (long)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                    consumer, "restartAfter");
                      }
                      audit(
                          "pause-before-real-nack",
                          Map.of(
                              "eventId",
                              event.eventId,
                              "bodyBase64",
                              Base64.getEncoder().encodeToString(message.getBody()),
                              "deliveryTag",
                              arguments[0],
                              "multiple",
                              arguments[1],
                              "requeue",
                              arguments[2],
                              "generation",
                              generation,
                              "lifecycle",
                              consumerLifecycleFacts()));
                      pauseNackEntered.countDown();
                      if (!pauseNackRelease.await(8, TimeUnit.SECONDS))
                        throw new IllegalStateException("CM21_PAUSE_BINDING_RESTORE_TIMEOUT");
                      try {
                        Object result = method.invoke(channel, arguments);
                        audit(
                            "pause-after-real-nack",
                            Map.of(
                                "eventId",
                                event.eventId,
                                "observedNanos",
                                System.nanoTime(),
                                "generation",
                                generation));
                        return result;
                      } catch (InvocationTargetException invocation) {
                        throw invocation.getCause();
                      }
                    }
                    if ("basicAck".equals(method.getName())
                        && dualAckArmed
                        && (event.productId == 42L || event.productId == 43L)) {
                      observeDualAck(message, channel, generation, event.eventId);
                      if (generation == dualFaultGeneration)
                        throw new IOException("CM21_TWO_OWNED_CHANNELS_CLOSED_BEFORE_ACK");
                    }
                    try {
                      return method.invoke(channel, arguments);
                    } catch (InvocationTargetException invocation) {
                      throw invocation.getCause();
                    }
                  });
      listener.onMessage(message, observed);
    }

    /** Both workers reach this point only after their own original transaction has completed. */
    private void observeDualAck(Message message, Channel channel, int generation, String eventId)
        throws Exception {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
      assertEquals(
          1L,
          product.jdbc.queryForObject(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id=?",
              Long.class,
              eventId));
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("eventId", eventId);
      facts.put("bodySha256", ProductMetricEvents.sha256(message.getBody()));
      facts.put("bodyBase64", Base64.getEncoder().encodeToString(message.getBody()));
      facts.put("generation", generation);
      facts.put("threadId", Thread.currentThread().getId());
      facts.put("threadName", Thread.currentThread().getName());
      facts.put("channelNumber", channel.getChannelNumber());
      facts.put("channelIdentity", System.identityHashCode(channel));
      facts.put("deliveryTag", message.getMessageProperties().getDeliveryTag());
      facts.put("actualTx", false);
      facts.put("synchronization", false);
      facts.put("independentLedgerCount", 1);
      facts.put("observedNanos", System.nanoTime());
      boolean replay = generation > dualFaultGeneration;
      if (replay) {
        assertTrue(Boolean.TRUE.equals(message.getMessageProperties().isRedelivered()));
        assertArrayEquals(dualBodies.get(eventId), message.getBody());
        assertNull(dualReplayChannels.putIfAbsent(eventId, channel));
        facts.put("redelivered", true);
        assertNull(dualReplayFacts.putIfAbsent(eventId, facts));
      } else {
        assertEquals(dualFaultGeneration, generation);
        assertNull(dualChannels.putIfAbsent(eventId, channel));
        dualBodies.put(eventId, message.getBody().clone());
        assertNull(dualAckFacts.putIfAbsent(eventId, facts));
      }
      audit("dual-ack-g" + generation + "-" + eventId + "-ch" + channel.getChannelNumber(), facts);
      CountDownLatch entered = replay ? dualReplayEntered : dualAckEntered;
      CountDownLatch release = replay ? dualReplayRelease : dualAckRelease;
      entered.countDown();
      if (!release.await(45, TimeUnit.SECONDS))
        throw new IllegalStateException("CM21_DUAL_ACK_BARRIER_TIMEOUT");
    }

    /** Read the one exact owned retry binding; main delivery and retry TTL/DLX stay unchanged. */
    private JsonNode retryBindingFacts() throws Exception {
      String path =
          "/api/bindings/"
              + vhost
              + "/e/cloudmall.product.metrics.dlx/q/cloudmall.product.metrics.retry";
      HttpResponse<String> response = management("GET", path, null);
      assertEquals(200, response.statusCode());
      JsonNode bindings = JSON.readTree(response.body());
      assertTrue(bindings.isArray());
      for (JsonNode binding : bindings) {
        assertEquals(vhost, binding.path("vhost").textValue());
        assertEquals("cloudmall.product.metrics.dlx", binding.path("source").textValue());
        assertEquals("cloudmall.product.metrics.retry", binding.path("destination").textValue());
        assertEquals("queue", binding.path("destination_type").textValue());
        assertEquals("cloudmall.product.metrics.retry", binding.path("routing_key").textValue());
        assertTrue(binding.path("arguments").isObject() && binding.path("arguments").isEmpty());
      }
      return bindings;
    }

    private void retryBinding(boolean present, String stage) throws Exception {
      JsonNode before = retryBindingFacts();
      assertEquals(present ? 0 : 1, before.size());
      Binding binding =
          new Binding(
              "cloudmall.product.metrics.retry",
              Binding.DestinationType.QUEUE,
              "cloudmall.product.metrics.dlx",
              "cloudmall.product.metrics.retry",
              null);
      if (present) admin.declareBinding(binding);
      else admin.removeBinding(binding);
      JsonNode after = retryBindingFacts();
      audit("retry-binding-" + stage, Map.of("vhost", vhost, "before", before, "after", after));
      assertEquals(present ? 1 : 0, after.size());
    }

    /** Declare only the exact owned44 conditional fault; the original fixture owns its removal. */
    private void declarePauseFault() throws Exception {
      String name = "cm21_pause_ledger";
      String ddl =
          "CREATE TRIGGER `"
              + product.database
              + "`.`"
              + name
              + "` BEFORE INSERT ON `"
              + product.database
              + "`.`product_metric_applied_event`"
              + " FOR EACH ROW BEGIN IF NEW.product_id=44 THEN SIGNAL SQLSTATE '45000'"
              + " SET MYSQL_ERRNO=1644,MESSAGE_TEXT='CM21_PAUSE_LEDGER_44'; END IF; END";
      audit(
          "trigger-plan-" + name,
          Map.of(
              "catalog",
              product.database,
              "name",
              name,
              "table",
              "product_metric_applied_event",
              "condition",
              "NEW.product_id=44",
              "ddl",
              ddl));
      product.jdbc.execute(ddl);
      assertTrue(product.triggers.add(name));
      audit(
          "trigger-created-" + name,
          product.jdbc.queryForList("SHOW CREATE TRIGGER `" + name + "`"));
    }

    /**
     * Persisted source identity/fingerprint/bytes, independent of the observed delivery objects.
     */
    private Map<String, Object> originalMetric(String eventId) {
      Map<String, Object> source =
          product.jdbc.queryForMap(
              "SELECT event_id,payload_json,fingerprint FROM product_metric_outbox WHERE"
                  + " event_id=?",
              eventId);
      String payload = String.valueOf(source.get("payload_json"));
      var parsed = ProductMetricEvents.parse(payload.getBytes(StandardCharsets.UTF_8));
      assertEquals(eventId, parsed.eventId);
      assertEquals(source.get("fingerprint"), ProductMetricEvents.fingerprint(parsed));
      return source;
    }

    /** One real failed retry transfer pauses both workers; the original scheduler alone resumes. */
    private void exerciseDualPause() throws Exception {
      drain();
      audit(
          "pause-before-fault",
          Map.of(
              "ledger",
              product.tableFacts("product_metric_applied_event"),
              "stat",
              product.tableFacts("product_online_stat"),
              "projection",
              product.tableFacts("product_metric_projection_outbox"),
              "source",
              product.tableFacts("product_metric_outbox"),
              "retryBinding",
              retryBindingFacts()));
      declarePauseFault();
      retryBinding(false, "remove-before-44");
      JsonNode request = detail(44);
      String eventId =
          ProductMetricEvents.product(request.path("requestId").textValue(), 44, false, null)
              .eventId;
      Map<String, Object> immutable = originalMetric(eventId);
      audit("pause-public-event", Map.of("request", request, "source", immutable));
      assertTrue(pauseNackEntered.await(30, TimeUnit.SECONDS));
      assertTrue(pauseReturned.await(5, TimeUnit.SECONDS));
      assertTrue(pauseConfirmReturned.await(5, TimeUnit.SECONDS));
      assertTrue(pauseSqlFailures.get() >= 3);
      assertNull(dualObservationFailure.get());
      assertEquals(
          0L,
          product.scalar("SELECT COUNT(*) FROM product_metric_applied_event WHERE product_id=44"));
      assertEquals(
          0L, product.scalar("SELECT COUNT(*) FROM product_online_stat WHERE product_id=44"));
      assertEquals(
          0L,
          product.scalar(
              "SELECT COUNT(*) FROM product_metric_projection_outbox WHERE product_id=44"));
      retryBinding(true, "restore-before-real-nack");
      pauseNackRelease.countDown();
      await(
          () -> {
            Map<String, Object> state = consumerLifecycleFacts();
            return pauseStopCompletion != null
                && pauseStopCompletion.getCount() == 0
                && Long.valueOf(0).equals(state.get("stopCompletionCount"))
                && !metricContainer.isRunning()
                && metricContainer.getActiveConsumerCount() == 0;
          },
          30,
          "TWO_CONSUMERS_REAL_PAUSE_STOP");
      Map<String, Object> stopped = consumerLifecycleFacts();
      long latestDeadline = (long) stopped.get("restartAfter");
      assertTrue(latestDeadline >= pauseFirstDeadline);
      audit(
          "pause-whole-listener-stopped",
          Map.of(
              "firstStopToken",
              pauseFirstStopToken,
              "firstCompletionCount",
              pauseStopCompletion.getCount(),
              "latestState",
              stopped,
              "allFrameworkThreadsJoinedProven",
              false));
      assertFalse(metricContainer.isRunning());
      product.dropTrigger("cm21_pause_ledger");
      audit(
          "pause-fault-restored",
          Map.of(
              "registeredTriggers",
              new TreeSet<>(product.triggers),
              "retryBinding",
              retryBindingFacts(),
              "observedNanos",
              System.nanoTime()));
      await(
          () ->
              metricContainer.isRunning()
                  && metricContainer.getActiveConsumerCount() == 2
                  && Long.valueOf(0).equals(consumerLifecycleFacts().get("restartAfter")),
          30,
          "NATURAL_TWO_CONSUMER_RECOVERY");
      assertTrue(System.nanoTime() >= latestDeadline);
      audit(
          "pause-natural-recovery",
          Map.of(
              "latestPauseDeadline",
              latestDeadline,
              "state",
              consumerLifecycleFacts(),
              "container",
              metricContainerFacts()));
      drain();
      assertEquals(immutable, originalMetric(eventId));
      for (String field : List.of("view_count", "metric_version"))
        assertEquals(
            1L,
            product.scalar("SELECT " + field + " FROM product_online_stat WHERE product_id=44"));
      assertEquals(
          1L,
          product.jdbc.queryForObject(
              "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id=?",
              Long.class,
              eventId));
      assertTrue(product.triggers.isEmpty());
      assertEquals(1, retryBindingFacts().size());
      assertNull(dualObservationFailure.get());
      audit(
          "pause-converged",
          Map.of(
              "eventId",
              eventId,
              "source",
              immutable,
              "observedSqlFailures",
              pauseSqlFailures.get(),
              "actualDeliveries",
              pauseDeliveries.get(),
              "facts",
              counts()));
    }

    /** The test thread stops once only after both real committed deliveries reach their ACKs. */
    private void exerciseDualAckPair() throws Exception {
      dualFaultGeneration = contextGeneration.get();
      dualAckArmed = true;
      Map<String, Map<String, Object>> immutable = new LinkedHashMap<>();
      for (long id : List.of(42L, 43L)) {
        JsonNode response = detail(id);
        String eventId =
            ProductMetricEvents.product(response.path("requestId").textValue(), id, false, null)
                .eventId;
        immutable.put(eventId, originalMetric(eventId));
      }
      assertTrue(dualAckEntered.await(30, TimeUnit.SECONDS));
      assertEquals(immutable.keySet(), dualChannels.keySet());
      List<Channel> originalChannels = new ArrayList<>(dualChannels.values());
      assertNotSame(originalChannels.get(0), originalChannels.get(1));
      assertEquals(
          2L, dualAckFacts.values().stream().map(fact -> fact.get("threadId")).distinct().count());
      for (var entry : immutable.entrySet())
        assertArrayEquals(
            String.valueOf(entry.getValue().get("payload_json")).getBytes(StandardCharsets.UTF_8),
            dualBodies.get(entry.getKey()));
      audit(
          "dual-two-committed-before-ack",
          Map.of(
              "events", dualAckFacts, "sources", immutable, "container", metricContainerFacts()));
      long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(15);
      CountDownLatch stopped = new CountDownLatch(1);
      List<Throwable> stopFailures = new ArrayList<>();
      try {
        metricContainer.stop(stopped::countDown);
      } catch (Throwable failure) {
        stopFailures.add(failure);
      }
      try {
        for (Channel channel : originalChannels) {
          try {
            channel.close();
          } catch (Throwable failure) {
            stopFailures.add(failure);
          }
        }
      } finally {
        dualAckRelease.countDown();
      }
      long remaining = deadline - System.nanoTime();
      boolean complete = remaining > 0 && stopped.await(remaining, TimeUnit.NANOSECONDS);
      audit(
          "dual-original-listener-stop",
          Map.of(
              "startedNanos",
              started,
              "finishedNanos",
              System.nanoTime(),
              "callbackArrived",
              complete,
              "activeConsumers",
              metricContainer.getActiveConsumerCount(),
              "running",
              metricContainer.isRunning(),
              "failureTypes",
              stopFailures.stream().map(failure -> failure.getClass().getName()).toList(),
              "allFrameworkThreadsJoinedProven",
              false));
      if (!stopFailures.isEmpty()) {
        var preserved = new IllegalStateException("CM21_DUAL_CHANNEL_STOP_FAILED");
        stopFailures.forEach(preserved::addSuppressed);
        throw preserved;
      }
      assertTrue(complete && System.nanoTime() <= deadline);
      assertFalse(metricContainer.isRunning());
      assertEquals(0, metricContainer.getActiveConsumerCount());
      for (Channel channel : originalChannels) assertFalse(channel.isOpen());
      stopProduct();
      startProduct();
      assertTrue(contextGeneration.get() > dualFaultGeneration);
      assertTrue(dualReplayEntered.await(30, TimeUnit.SECONDS));
      assertEquals(immutable.keySet(), dualReplayFacts.keySet());
      assertEquals(
          2L,
          dualReplayFacts.values().stream().map(fact -> fact.get("threadId")).distinct().count());
      List<Channel> replayChannels = new ArrayList<>(dualReplayChannels.values());
      assertNotSame(replayChannels.get(0), replayChannels.get(1));
      audit(
          "dual-new-generation-redeliveries",
          Map.of(
              "events",
              dualReplayFacts,
              "faultGeneration",
              dualFaultGeneration,
              "newGeneration",
              contextGeneration.get()));
      dualReplayRelease.countDown();
      drain();
      for (var entry : immutable.entrySet()) {
        assertEquals(entry.getValue(), originalMetric(entry.getKey()));
        assertEquals(
            1L,
            product.jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id=?",
                Long.class,
                entry.getKey()));
      }
      for (long id : List.of(42L, 43L))
        for (String field : List.of("view_count", "metric_version"))
          assertEquals(
              1L,
              product.scalar(
                  "SELECT " + field + " FROM product_online_stat WHERE product_id=" + id));
      assertNull(dualObservationFailure.get());
    }

    void seedNormal(String order, int quantity) {
      seededOrders.add(order);
      stock.jdbc.update(
          "INSERT INTO"
              + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
              + " VALUES(?,41,20,?,?,0,0,NOW(3))",
          normalSku,
          20 - quantity,
          quantity);
      stock.jdbc.update(
          "INSERT INTO"
              + " stock_reservation_request(id,order_no,scene,items_snapshot,items_hash,status,created_at,updated_at)"
              + " VALUES(?,?,'NORMAL',CAST(? AS JSON),?,'RESERVED',NOW(3),NOW(3))",
          normalSku,
          order,
          "[{\"skuId\":" + normalSku + ",\"quantity\":" + quantity + "}]",
          "a".repeat(64));
      stock.jdbc.update(
          "INSERT INTO stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
              + " VALUES(?,?,?,'RESERVE',?,?,NOW(3))",
          normalSku,
          normalSku,
          order,
          quantity,
          order + ":RESERVE:" + normalSku);
    }

    void seedSeckill(String order) {
      stock.jdbc.update(
          "INSERT INTO"
              + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
              + " VALUES(?,42,20,20,0,0,0,NOW(3))",
          seckillSku);
      stock.jdbc.update(
          "INSERT INTO"
              + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,request_hash,request_payload,status,created_at,updated_at)"
              + " VALUES(?,3,?,9,?,?,?,CAST(? AS JSON),'ACCEPTED',NOW(3),NOW(3))",
          seckillSku,
          seckillSku,
          order,
          "cm21-accepted-" + identity,
          "b".repeat(64),
          "{\"activityId\":3,\"skuId\":"
              + seckillSku
              + ",\"userId\":9,\"idempotencyKey\":\"cm21-accepted-"
              + identity
              + "\"}");
    }

    void start() throws Exception {
      startProduct();
      startStock();
      drain();
    }

    void startProduct() throws Exception {
      HikariConfig config = new HikariConfig();
      config.setDataSource(product.dataSource);
      config.setMaximumPoolSize(12);
      config.setMinimumIdle(2);
      config.setConnectionTimeout(3000);
      config.setValidationTimeout(1000);
      config.setInitializationFailTimeout(3000);
      config.setPoolName("cm21-product-" + identity);
      pool = new HikariDataSource(config);
      CURRENT.set(this);
      try {
        List<String> args = commonArguments(product.database);
        args.addAll(
            List.of(
                "--server.port=0",
                "--cloudmall.product.metrics.enabled=true",
                "--cloudmall.product.metrics.sort-mode=ONLINE",
                "--cloudmall.product.search.enabled=true",
                "--cloudmall.product.elasticsearch.index=" + index,
                "--cloudmall.product.elasticsearch.url=http://127.0.0.1:" + port("es.port"),
                "--cloudmall.product.index.dispatch-enabled=true",
                "--cloudmall.product.index.consumer-enabled=true",
                "--cloudmall.product.index.rebuild-enabled=true",
                "--cloudmall.product.index.rebuild-interval-ms=100",
                "--cloudmall.product.index.rebuild-audit-ms=1000",
                "--cloudmall.product.index.rebuild-max-events-per-minute=60000",
                "--cloudmall.product.index.outbox-dispatch-ms=100",
                "--cloudmall.product.metrics.dispatch-interval-ms=100",
                "--cloudmall.product.metrics.projection-interval-ms=100",
                "--spring.task.scheduling.pool.size=6"));
        if (batchMode) args.add("--cloudmall.product.metrics.batch.enabled=true");
        context =
            new SpringApplicationBuilder(PipelineApplication.class)
                .profiles("cm21-pipeline")
                .run(args.toArray(String[]::new));
        product.contexts.add(context);
        productPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        product.ownedPorts.add(productPort);
        if (nacosPort(productPort))
          throw new IllegalStateException("PRODUCT_PORT_COLLIDES_WITH_NACOS_TUPLE");
      } finally {
        CURRENT.remove();
      }
      audit(
          "product-start-" + product.contexts.size(),
          Map.of("port", productPort, "poolMaximum", 12, "catalog", product.database));
      if (phaseTiming != null)
        audit("phase-timing-startup", phaseTiming.startupFacts(context, pool));
      if (loadSupport != null) {
        Object selected =
            context
                .getBean(RabbitListenerEndpointRegistry.class)
                .getListenerContainer("productMetrics");
        if (!(selected instanceof SimpleMessageListenerContainer container))
          throw new IllegalStateException("CM21_SINGLE10_CONTAINER_REQUIRED");
        metricContainer = container;
        await(
            () -> container.isRunning() && container.getActiveConsumerCount() == 1,
            30,
            "ONE_ORIGINAL_LOAD_WORKER");
        audit("single10-runtime", loadSupport.runtimeFacts(context, container));
        if (applicationTiming != null)
          applicationTiming.verifyRuntime(context, context.getBean(ProductMetricService.class));
        if (serviceTransactionObservation != null)
          verifyServiceTransactionObservationRuntime(context, serviceTransactionObservation);
        if (statementEnvelope != null
            && !applicationTiming.verifyStatementEnvelopeRuntime(context, pool))
          throw new IllegalStateException("CM21_STATEMENT_ENVELOPE_RUNTIME_IDENTITY_REQUIRED");
      }
      if (batchMode) {
        Object selected =
            context
                .getBean(RabbitListenerEndpointRegistry.class)
                .getListenerContainer("productMetrics");
        if (!(selected instanceof SimpleMessageListenerContainer container))
          throw new IllegalStateException("CM21_BATCH_CONTAINER_REQUIRED");
        metricContainer = container;
        batchOriginalListener = container.getMessageListener();
        ProductMetricPipelineBatchSupport.containerFacts(context, container, batchOriginalListener);
        await(
            () -> metricContainer.isRunning() && metricContainer.getActiveConsumerCount() == 1,
            30,
            "ONE_ORIGINAL_BATCH_WORKER");
      }
      if (metricConsumers == 2)
        await(() -> metricContainer.getActiveConsumerCount() == 2, 30, "TWO_METRIC_WORKERS");
      audit("metrics-container-settings-" + contextGeneration.get(), metricContainerFacts());
      if (metricConsumers == 2 && "redelivery".equals(role)) installDualObservers();
    }

    /** Start only the standard Stock artifact with explicit owned endpoints and SDK directories. */
    void startStock() throws Exception {
      Path jar =
          Path.of("..", "cloud-mall-stock", "target", "cloud-mall-stock-0.1.0-SNAPSHOT.jar")
              .toAbsolutePath()
              .normalize();
      if (!Files.isRegularFile(jar))
        throw new IllegalStateException("STANDARD_STOCK_BOOT_JAR_REQUIRED");
      do {
        stockPort = freePort();
      } while (nacosPort(stockPort));
      do {
        stockManagementPort = freePort();
      } while (stockManagementPort == stockPort || nacosPort(stockManagementPort));
      Path sdkHome = Files.createDirectory(stock.evidence.resolve("stock-sdk-home"));
      Path sdkLogs = Files.createDirectory(stock.evidence.resolve("stock-sdk-logs"));
      Path sdkSnapshot = Files.createDirectory(stock.evidence.resolve("stock-sdk-snapshot"));
      Path sentinelLogs = Files.createDirectory(sdkLogs.resolve("sentinel"));
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(
                          System.getProperty("java.home"),
                          "bin",
                          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                      .toString(),
                  "-Xmx512m",
                  "-Dnacos.server.grpc.port.offset=1000",
                  "-Duser.home=" + sdkHome,
                  "-DJM.LOG.PATH=" + sdkLogs,
                  "-DJM.SNAPSHOT.PATH=" + sdkSnapshot,
                  "-Dcsp.sentinel.log.dir=" + sentinelLogs,
                  "-jar",
                  jar.toString()));
      command.addAll(commonArguments(stock.database));
      command.addAll(
          List.of(
              "--server.port=" + stockPort,
              "--management.server.address=127.0.0.1",
              "--management.server.port=" + stockManagementPort,
              "--management.endpoints.web.exposure.include=health,shutdown",
              "--management.endpoint.shutdown.enabled=true",
              "--server.shutdown=graceful",
              "--spring.lifecycle.timeout-per-shutdown-phase=5s",
              "--cloudmall.stock.sales-metrics.enabled=true",
              "--cloudmall.stock.sales-metrics.dispatch-interval-ms=100",
              "--cloudmall.stock.seckill.sentinel.nacos-retry-ms=86400000",
              "--cloudmall.stock.dependency-degrade.nacos-retry-ms=86400000",
              "--cloudmall.stock.seckill.sentinel.rule-group=CM21_" + identity,
              "--cloudmall.stock.dependency-degrade.rule-group=CM21_" + identity,
              "--spring.task.scheduling.pool.size=4"));
      Path log = stock.evidence.resolve("stock-boot.log");
      Files.createFile(log);
      Map<String, Object> plan = new LinkedHashMap<>();
      plan.put("jarSha256", ProductMetricEvents.sha256(Files.readAllBytes(jar)));
      plan.put("applicationPort", stockPort);
      plan.put("managementPort", stockManagementPort);
      plan.put("catalog", stock.database);
      plan.put("owner", owner);
      plan.put("nacosPorts", List.of(nacosBase, nacosBase + 1000, nacosBase + 1001));
      plan.put("nacosAddress", "127.0.0.1:" + nacosBase);
      plan.put("ruleGroup", "CM21_" + identity);
      plan.put("manualRuleRetryMillis", 86400000);
      plan.put("frameworkDiscoveryConfigSentinelSeata", false);
      plan.put(
          "sdkDirectories",
          List.of(
              sdkHome.toString(),
              sdkLogs.toString(),
              sdkSnapshot.toString(),
              sentinelLogs.toString()));
      plan.put(
          "shutdownEndpoint", "http://127.0.0.1:" + stockManagementPort + "/actuator/shutdown");
      plan.put("manualSdkMayRetry", true);
      plan.put("backgroundWorkersUseOwnedResources", true);
      audit("stock-launch", plan);
      assertNacosPortsFree(nacosBase);
      assertPortUnbound(stockPort);
      assertPortUnbound(stockManagementPort);
      stockProcess =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
              .start();
      stockStarted = stockProcess.info().startInstant().orElseThrow();
      stock.ownedPorts.add(stockPort);
      stock.ownedPorts.add(stockManagementPort);
      audit(
          "stock-process", Map.of("pid", stockProcess.pid(), "startedAt", stockStarted.toString()));
      await(
          () -> {
            if (!stockProcess.isAlive()) throw new IllegalStateException("OWNED_STOCK_EXITED");
            try {
              return http("GET", stockManagementPort, "/actuator/health", null, false).statusCode()
                  == 200;
            } catch (IOException notReady) {
              return false;
            }
          },
          120,
          "STOCK_MANAGEMENT_HEALTH");
    }

    private boolean nacosPort(int endpoint) {
      return endpoint == nacosBase || endpoint == nacosBase + 1000 || endpoint == nacosBase + 1001;
    }

    List<String> commonArguments(String catalog) throws Exception {
      return new ArrayList<>(
          List.of(
              "--server.address=127.0.0.1",
              "--spring.main.banner-mode=off",
              "--logging.level.root=WARN",
              "--spring.main.allow-bean-definition-overriding=false",
              "--spring.datasource.url="
                  + System.getProperty("cloudmall.cm21.mysql.base-url")
                  + catalog
                  + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000",
              "--spring.datasource.username=root",
              "--spring.datasource.password=root",
              "--spring.datasource.hikari.maximum-pool-size=12",
              "--spring.datasource.hikari.minimum-idle=2",
              "--spring.datasource.hikari.connection-timeout=3000",
              "--spring.datasource.hikari.connection-init-sql=SET time_zone='+08:00'",
              "--spring.redis.host=127.0.0.1",
              "--spring.redis.port=" + port("redis.port"),
              "--spring.redis.password=root",
              "--spring.rabbitmq.host=127.0.0.1",
              "--spring.rabbitmq.port=" + port("rabbit.port"),
              "--spring.rabbitmq.username=root",
              "--spring.rabbitmq.password=root",
              "--spring.rabbitmq.virtual-host=" + vhost,
              "--spring.rabbitmq.publisher-confirm-type=correlated",
              "--spring.rabbitmq.publisher-returns=true",
              "--spring.cloud.nacos.discovery.enabled=false",
              "--spring.cloud.nacos.config.enabled=false",
              "--spring.cloud.nacos.discovery.server-addr=127.0.0.1:" + nacosBase,
              "--spring.cloud.nacos.config.server-addr=127.0.0.1:" + nacosBase,
              "--spring.cloud.sentinel.enabled=false",
              "--spring.cloud.sentinel.eager=false",
              "--seata.enabled=false",
              "--seata.enable-auto-data-source-proxy=false",
              "--cloudmall.seata.cm18.enabled=false"));
    }

    /**
     * One shared bounded helper executor serves actual HTTP requests and is closed independently.
     */
    final ExecutorService httpExecutor;

    /**
     * JDK selector lifetime is framework-owned; this fixture proves its own executor and requests
     * complete.
     */
    final HttpClient client;

    /** Publish only optional cold evidence; observation faults never replace lifecycle failure. */
    void statementEnvelopeSidecar(
        Path directory, ProductMetricStatementEnvelopeSupport.SnapshotKind kind) {
      if (statementEnvelope != null)
        statementEnvelope.publish(directory, kind, owner, "batch1", owner + "-batch1", identity);
    }

    /**
     * Publish only separate finite files; failure remains in the sticky audit, not business errors.
     */
    void queryInfoSidecar(Path directory, String kind) {
      if (queryInfoAdmission.controlled())
        ProductMetricQueryInfoCacheSupport.publish(
            directory,
            kind,
            owner,
            identity,
            queryInfoAdmission,
            product.expectedServerUuid,
            product.dataSource.queryInfoAudit);
    }

    void audit(String name, Object body) throws Exception {
      product.audit(name, body);
    }

    HttpResponse<String> http(
        String method, int port, String path, Object body, boolean administrator) throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(5));
      if (administrator) request.header("X-User-Id", "9").header("X-User-Role", "ADMIN");
      if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
      else
        request
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> management(String method, String path, String body) throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port("rabbit.management-port") + path))
              .timeout(Duration.ofSeconds(5))
              .header(
                  "Authorization",
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString("root:root".getBytes(StandardCharsets.UTF_8)));
      request
          .header("Content-Type", "application/json")
          .method(
              method,
              body == null
                  ? HttpRequest.BodyPublishers.noBody()
                  : HttpRequest.BodyPublishers.ofString(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode es(String method, String path, Object body, int expected) throws Exception {
      HttpResponse<String> response = http(method, port("es.port"), path, body, false);
      assertEquals(expected, response.statusCode(), "owned ES operation");
      return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    JsonNode document(long id) throws Exception {
      return es("GET", "/" + index + "/_doc/" + id, null, 200).path("_source");
    }

    JsonNode detail(long id) throws Exception {
      HttpResponse<String> response = http("GET", productPort, "/api/products/" + id, null, false);
      assertEquals(200, response.statusCode());
      JsonNode result = JSON.readTree(response.body());
      registerObservation(result, "PRODUCT_VIEW_CONFIRMED", List.of(id));
      return result;
    }

    JsonNode search(String sort, int page, int size) throws Exception {
      HttpResponse<String> response =
          http(
              "GET",
              productPort,
              "/api/products?keyword=Metric&status=1&sort="
                  + sort
                  + "&page="
                  + page
                  + "&pageSize="
                  + size,
              null,
              false);
      assertEquals(200, response.statusCode());
      JsonNode result = JSON.readTree(response.body());
      List<Long> ids = new ArrayList<>();
      for (JsonNode item : result.path("data").path("items")) ids.add(item.path("id").asLong());
      registerObservation(result, "PRODUCT_SEARCH_EXPOSED", ids);
      return result;
    }

    void registerObservation(JsonNode response, String type, List<Long> ids) throws Exception {
      String request = response.path("requestId").asText();
      assertEquals(UUID.fromString(request).toString(), request);
      Map<String, Object> observed =
          Map.of(
              "requestId",
              request,
              "eventType",
              type,
              "returnedIds",
              List.copyOf(ids),
              "registeredDistinct",
              new HashSet<>(ids).size());
      requests.add(observed);
      audit("request-" + requests.size(), observed);
      for (long id : new LinkedHashSet<>(ids)) {
        String event =
            ProductMetricEvents.product(request, id, type.equals("PRODUCT_SEARCH_EXPOSED"), null)
                .eventId;
        assertEquals(
            1L,
            product.jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_metric_outbox WHERE event_id=?", Long.class, event));
      }
    }

    void admin(String method, String path, Object body) throws Exception {
      HttpResponse<String> response = http(method, productPort, path, body, true);
      assertEquals(200, response.statusCode());
      audit(
          "admin-" + UUID.randomUUID(),
          Map.of("method", method, "path", path, "response", JSON.readTree(response.body())));
    }

    void confirm(String order) throws Exception {
      HttpResponse<String> response =
          http(
              "POST",
              stockPort,
              "/api/stock/reservations/" + order + "/confirm",
              Map.of("operationKey", order + ":CONFIRM"),
              false);
      assertEquals(200, response.statusCode());
      assertTrue(JSON.readTree(response.body()).path("data").path("confirmed").asBoolean());
      audit(
          "stock-confirm-" + UUID.randomUUID(),
          Map.of("orderNo", order, "response", JSON.readTree(response.body())));
    }

    void binding(boolean present) {
      Binding binding =
          new Binding(
              "cloudmall.product.metrics.queue",
              Binding.DestinationType.QUEUE,
              "cloudmall.product.metrics.exchange",
              "product.metric.v1",
              null);
      if (present) admin.declareBinding(binding);
      else admin.removeBinding(binding);
    }

    void drain() throws Exception {
      await(
          () -> {
            if (applicationTiming != null) applicationTiming.assertHealthy(false);
            if (context == null
                || !context.isActive()
                || stockProcess != null && !stockProcess.isAlive()) return false;
            if (product.scalar(
                        "SELECT COUNT(*) FROM product_metric_outbox WHERE status<>'PUBLISHED'")
                    != 0
                || stock.scalar("SELECT COUNT(*) FROM stock_sales_outbox WHERE status<>'PUBLISHED'")
                    != 0) return false;
            ProductMetricService.Cut cut = context.getBean(ProductMetricService.class).cut();
            if (!cut.admissible()) return false;
            Properties queue = admin.getQueueProperties("cloudmall.product.metrics.queue");
            if (queue == null
                || ((Number) queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue() != 0)
              return false;
            JsonNode metrics =
                JSON.readTree(
                    management(
                            "GET",
                            "/api/queues/" + vhost + "/cloudmall.product.metrics.queue",
                            null)
                        .body());
            return metrics.path("messages").asLong() == 0
                && metrics.path("messages_unacknowledged").asLong() == 0;
          },
          120,
          "PIPELINE_DRAIN");
    }

    Map<String, Long> counts() {
      Map<String, Long> result = new LinkedHashMap<>();
      result.put("registered", product.count("product_metric_outbox"));
      result.put("applied", product.count("product_metric_applied_event"));
      for (String field :
          List.of("view_count", "search_count", "sold_count", "hot_score", "metric_version"))
        result.put(
            field,
            product.scalar("SELECT COALESCE(SUM(" + field + "),0) FROM product_online_stat"));
      result.put("stockEvents", stock.count("stock_sales_outbox"));
      return result;
    }

    void verifyOne(long id) throws Exception {
      ProductIndexDocument source =
          context.getBean(ProductIndexDocumentLoader.class).load(id).orElseThrow();
      JsonNode projection = document(id);
      assertEquals(source.id(), projection.path("id").asLong());
      assertEquals(
          source.updatedAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
          projection.path("updatedAt").asLong());
      assertEquals(ProductMetricEvents.SERIES, projection.path("onlineSeriesId").asText());
      assertEquals(source.version(), projection.path("productVersion").asLong());
      assertEquals(source.name(), projection.path("name").asText());
      assertEquals(0, source.price().compareTo(new BigDecimal(projection.path("price").asText())));
      assertEquals(source.status() == 1, projection.path("visible").asBoolean());
      assertEquals(
          ProductMetricEvents.contentFingerprint(source),
          projection.path("contentFingerprint").asText());
      ProductOnlineStatPO stat = source.onlineStat();
      assertEquals(stat.metricVersion.longValue(), projection.path("metricVersion").asLong());
      assertEquals(stat.viewCount.longValue(), projection.path("onlineViewCount").asLong());
      assertEquals(stat.searchCount.longValue(), projection.path("onlineSearchCount").asLong());
      assertEquals(stat.soldCount.longValue(), projection.path("onlineSoldCount").asLong());
      assertEquals(stat.hotScore.longValue(), projection.path("onlineHotScore").asLong());
      assertEquals(
          ProductMetricEvents.metricFingerprint(stat),
          projection.path("onlineFingerprint").asText());
    }

    void verifyAll() throws Exception {
      verifyLedgerGolden();
      assertEquals(
          0L,
          product.scalar(
              "SELECT COUNT(*) FROM product_online_stat WHERE"
                  + " hot_score<>view_count+search_count+10*sold_count"));
      assertEquals(
          product.count("product_metric_applied_event"),
          product.scalar("SELECT COALESCE(SUM(metric_version),0) FROM product_online_stat"));
      assertEquals(
          product.count("product_metric_applied_event"),
          product.count("product_metric_projection_outbox"));
      assertEquals(
          0L,
          product.scalar(
              "SELECT COUNT(*) FROM product_metric_projection_outbox WHERE status<>'DONE'"));
      assertEquals(0L, product.count("product_metric_quarantine"));
      for (long id : product.jdbc.queryForList("SELECT id FROM product ORDER BY id", Long.class))
        verifyOne(id);
      for (Map<String, Object> event :
          stock.jdbc.queryForList("SELECT event_id,payload_json FROM stock_sales_outbox")) {
        assertEquals(
            1L,
            product.jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_metric_applied_event WHERE event_id=?",
                Long.class,
                event.get("event_id")));
        JsonNode body = JSON.readTree(String.valueOf(event.get("payload_json")));
        assertEquals(
            ProductMetricEvents.fingerprint(
                ProductMetricEvents.parse(
                    String.valueOf(event.get("payload_json")).getBytes(StandardCharsets.UTF_8))),
            product.jdbc.queryForObject(
                "SELECT fingerprint FROM product_metric_applied_event WHERE event_id=?",
                String.class,
                event.get("event_id")));
        assertTrue(body.path("quantity").asInt() > 0);
      }
    }

    void assertDefaultConverters() {
      assertTrue(
          context
                  .getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class)
                  .getMessageConverter()
              instanceof org.springframework.amqp.support.converter.Jackson2JsonMessageConverter);
      assertEquals(12, pool.getMaximumPoolSize());
    }

    /**
     * A standard shutdown acceptance is followed by actual generation-bound process/listener exit.
     */
    void stopStock() throws Exception {
      if (stockProcess == null) return;
      long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(15);
      Map<String, Object> outcome = new LinkedHashMap<>();
      outcome.put("pid", stockProcess.pid());
      outcome.put("startedAt", String.valueOf(stockStarted));
      Exception failure = null;
      boolean graceful = false;
      try {
        if (!stockProcess.isAlive()
            || !stockProcess.info().startInstant().orElseThrow().equals(stockStarted))
          throw new IllegalStateException("OWNED_STOCK_GENERATION_NOT_RUNNING");
        HttpResponse<String> response =
            http("POST", stockManagementPort, "/actuator/shutdown", null, false);
        outcome.put("shutdownStatus", response.statusCode());
        outcome.put("shutdownResponse", response.body());
        if (response.statusCode() != 200)
          throw new IllegalStateException("STOCK_SHUTDOWN_NOT_ACCEPTED");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !stockProcess.waitFor(remaining, TimeUnit.NANOSECONDS))
          throw new IllegalStateException("STOCK_GRACEFUL_EXIT_TIMEOUT");
        while (System.nanoTime() < deadline
            && (listening(stockPort) || listening(stockManagementPort))) Thread.sleep(50);
        if (stockProcess.isAlive()
            || listening(stockPort)
            || listening(stockManagementPort)
            || System.nanoTime() > deadline)
          throw new IllegalStateException("STOCK_LISTENER_EXIT_NOT_PROVEN");
        graceful = true;
        outcome.put("exitCode", stockProcess.exitValue());
      } catch (Exception problem) {
        failure = problem;
      } finally {
        // Windows destroy is termination, not proof that Spring or SDK shutdown hooks completed.
        if (stockProcess.isAlive()) {
          try {
            if (!stockProcess.info().startInstant().orElseThrow().equals(stockStarted))
              throw new IllegalStateException("STOCK_FALLBACK_GENERATION_MISMATCH");
            stockProcess.destroy();
            if (!stockProcess.waitFor(3, TimeUnit.SECONDS)) {
              stockProcess.destroyForcibly();
              stockProcess.waitFor(3, TimeUnit.SECONDS);
            }
            outcome.put("fallbackTermination", true);
          } catch (Exception termination) {
            if (failure == null) failure = termination;
            else failure.addSuppressed(termination);
          }
        }
        outcome.put("gracefulExitProven", graceful);
        outcome.put("actualProcessAlive", stockProcess.isAlive());
        outcome.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        outcome.put("allFrameworkThreadsJoinedProven", false);
        outcome.put("sdkLog", stock.evidence.resolve("stock-boot.log").toString());
        try {
          audit("stock-stop", outcome);
        } catch (Exception auditFailure) {
          if (failure == null) failure = auditFailure;
          else failure.addSuppressed(auditFailure);
        }
      }
      if (!graceful && failure == null)
        failure = new IllegalStateException("STOCK_GRACEFUL_CLOSE_UNKNOWN_PRESERVE");
      if (failure != null) throw failure;
    }

    /** Inspect exact root-owned IDs and their real loopback published ports before any mutation. */
    JsonNode inspect(String kind) throws Exception {
      String id = required(kind + ".container-id");
      if (!id.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("CM21_FULL_CONTAINER_ID_REQUIRED");
      JsonNode observed = JSON.readTree(docker(List.of("inspect", id))).path(0);
      assertEquals(id, observed.path("Id").asText());
      assertEquals(
          owner, observed.path("Config").path("Labels").path("cloudmall.cm21.owner").asText());
      assertTrue(observed.path("State").path("Running").asBoolean());
      String expected =
          switch (kind) {
            case "rabbit" -> "rabbitmq:3.11-management";
            case "es" -> "docker.elastic.co/elasticsearch/elasticsearch:7.17.0";
            case "redis" -> "redis:7.0";
            default -> throw new IllegalArgumentException("CM21_RESOURCE_ROLE");
          };
      assertEquals(expected, observed.path("Config").path("Image").asText());
      int internal =
          switch (kind) {
            case "rabbit" -> 5672;
            case "es" -> 9200;
            default -> 6379;
          };
      requirePublishedPort(observed, internal, port(kind + ".port"));
      if ("rabbit".equals(kind))
        requirePublishedPort(observed, 15672, port("rabbit.management-port"));
      Files.write(
          resourceEvidence.resolve("inspect-" + kind + "-" + UUID.randomUUID() + ".json"),
          JSON.writeValueAsBytes(
              Map.of(
                  "id",
                  id,
                  "owner",
                  owner,
                  "image",
                  expected,
                  "running",
                  true,
                  "startedAt",
                  observed.path("State").path("StartedAt").asText(),
                  "hostPort",
                  port(kind + ".port"))),
          StandardOpenOption.CREATE_NEW);
      return observed;
    }

    private void requirePublishedPort(JsonNode observed, int internal, int external) {
      JsonNode bindings = observed.path("NetworkSettings").path("Ports").path(internal + "/tcp");
      assertEquals(1, bindings.size());
      assertEquals("127.0.0.1", bindings.path(0).path("HostIp").asText());
      assertEquals(String.valueOf(external), bindings.path(0).path("HostPort").asText());
    }

    /**
     * No shell composition: output remains in an owned file and a stalled CLI cannot be ignored.
     */
    String docker(List<String> arguments) throws Exception {
      List<String> command = new ArrayList<>();
      command.add("docker");
      command.addAll(arguments);
      Path output = Files.createTempFile(resourceEvidence, "docker-", ".txt");
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        if (!process.waitFor(60, TimeUnit.SECONDS))
          throw new IllegalStateException("CM21_DOCKER_TIMEOUT");
        if (process.exitValue() != 0) throw new IllegalStateException("CM21_DOCKER_FAILED");
        return Files.readString(output);
      } finally {
        if (process.isAlive()) {
          process.destroyForcibly();
          if (!process.waitFor(5, TimeUnit.SECONDS))
            throw new IllegalStateException("CM21_DOCKER_PROCESS_UNKNOWN");
        }
      }
    }

    /**
     * Two bounded HTTP requests start together; both still use the original transactional services.
     */
    void concurrentContentAndMetric() throws Exception {
      ExecutorService workers = Executors.newFixedThreadPool(2);
      CyclicBarrier start = new CyclicBarrier(2);
      try {
        Future<?> content =
            workers.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  admin(
                      "PUT",
                      "/api/products/41",
                      Map.of(
                          "categoryId",
                          3,
                          "name",
                          "Metric changed",
                          "price",
                          "14.00",
                          "published",
                          true,
                          "skus",
                          List.of(),
                          "parameters",
                          List.of()));
                  return null;
                });
        Future<?> metric =
            workers.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  detail(41);
                  return null;
                });
        content.get(20, TimeUnit.SECONDS);
        metric.get(20, TimeUnit.SECONDS);
      } finally {
        workers.shutdownNow();
        if (!workers.awaitTermination(15, TimeUnit.SECONDS))
          throw new IllegalStateException("CM21_HTTP_WORKERS_NOT_CLOSED");
      }
      audit(
          "content-metric-concurrent",
          Map.of("requestsCompleted", 2, "helperExecutorTerminated", true));
    }

    /** Re-publish the persisted original body through the real mandatory-confirmed sender. */
    void duplicateOriginalMetric() throws Exception {
      Map<String, Object> row =
          product.jdbc.queryForMap(
              "SELECT event_id,payload_json FROM product_metric_outbox ORDER BY event_id LIMIT 1");
      String id = String.valueOf(row.get("event_id")),
          raw = String.valueOf(row.get("payload_json"));
      Map<String, Long> before = counts();
      MessageProperties properties = new MessageProperties();
      properties.setMessageId(id);
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      assertTrue(
          context
              .getBean(ProductMetricOutboxDispatcher.class)
              .transfer(
                  "cloudmall.product.metrics.exchange",
                  "product.metric.v1",
                  new Message(raw.getBytes(StandardCharsets.UTF_8), properties)));
      await(
          () -> {
            JsonNode queue =
                JSON.readTree(
                    management(
                            "GET",
                            "/api/queues/" + vhost + "/cloudmall.product.metrics.queue",
                            null)
                        .body());
            return queue.path("messages").asLong() == 0
                && queue.path("messages_unacknowledged").asLong() == 0;
          },
          30,
          "DUPLICATE_ACK");
      drain();
      assertEquals(before, counts());
      audit(
          "immutable-duplicate",
          Map.of(
              "eventId",
              id,
              "bodySha256",
              ProductMetricEvents.sha256(raw.getBytes(StandardCharsets.UTF_8)),
              "before",
              before,
              "after",
              counts()));
    }

    /**
     * Pause only this fixture's metrics consumer so both SQL/ES pages observe the same APPLIED cut.
     */
    void comparePages() throws Exception {
      drain();
      MessageListenerContainer consumer =
          context
              .getBean(RabbitListenerEndpointRegistry.class)
              .getListenerContainer("productMetrics");
      consumer.stop();
      assertFalse(consumer.isRunning());
      ProductMetricPipelineContentEsSupport.Pause pause =
          batchContentEs == null
              ? null
              : batchContentEs.pauseAfterStop(
                  (SimpleMessageListenerContainer) consumer,
                  ProductMetricPipelineRedeliverySupport.ultimateConsumer(
                      context.getBean(ProductMetricConsumer.class)),
                  context.getBean(ProductMetricBatchChannelRegistry.class));
      Throwable comparisonFailure = null;
      List<Map<String, Object>> pages = new ArrayList<>();
      try {
        ProductMetricService.Cut cut = context.getBean(ProductMetricService.class).cut();
        assertTrue(cut.admissible());
        ProductSqlMapper sql = context.getBean(ProductSqlMapper.class);
        for (String sort : List.of("sales", "hot"))
          for (int page : List.of(1, 2)) {
            TransactionTemplate snapshot =
                new TransactionTemplate(
                    context.getBean(
                        org.springframework.transaction.PlatformTransactionManager.class));
            snapshot.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.setReadOnly(true);
            snapshot.setTimeout(3);
            final int selectedPage = page;
            Map<String, Object> physical =
                snapshot.execute(
                    status -> {
                      long total = sql.countProducts("Metric", null, 1);
                      List<Long> ids =
                          sql
                              .findProductsOnline(
                                  "Metric", null, 1, (selectedPage - 1) * 2, 2, sort)
                              .stream()
                              .map(row -> ((Number) row.get("id")).longValue())
                              .toList();
                      return Map.of(
                          "total",
                          total,
                          "ids",
                          ids,
                          "revision",
                          context.getBean(ProductMetricService.class).sqlRevision());
                    });
            JsonNode response = search(sort, page, 2);
            List<Long> actual = new ArrayList<>();
            for (JsonNode item : response.path("data").path("items"))
              actual.add(item.path("id").asLong());
            assertEquals(physical.get("ids"), actual);
            assertEquals(
                ((Number) physical.get("total")).longValue(),
                response.path("data").path("total").asLong());
            assertEquals(cut.revision(), response.path("data").path("metricReadRevision").asLong());
            assertEquals(cut.revision(), physical.get("revision"));
            pages.add(Map.of("sort", sort, "page", page, "sql", physical, "http", response));
          }
        assertEquals(cut, context.getBean(ProductMetricService.class).cut());
      } catch (Exception | Error originalFailure) {
        comparisonFailure = originalFailure;
        throw originalFailure;
      } finally {
        if (pause == null) consumer.start();
        else pause.resume(comparisonFailure);
      }
      drain();
      audit(
          "same-cut-pages-" + UUID.randomUUID(),
          Map.of(
              "pages",
              pages,
              "newExposureRegistrationsIncludedAfterResume",
              true,
              "counts",
              counts()));
    }

    /**
     * Exactly one restart targets only the labelled ES ID whose real mapped endpoint was admitted.
     */
    void restartOwnedEsOnce() throws Exception {
      if (esRestarted || !"dual-version".equals(role))
        throw new IllegalStateException("CM21_ES_RESTART_SCOPE");
      drain();
      JsonNode before = inspect("es");
      String uuid = context.getBean(ElasticsearchProductIndexWriter.class).indexUuid();
      Map<String, Long> baseline = counts();
      esRestarted = true;
      ExecutorService command = Executors.newSingleThreadExecutor();
      Throwable restartFailure = null;
      try {
        Future<String> restarted =
            command.submit(
                () -> docker(List.of("restart", "--time", "10", required("es.container-id"))));
        await(
            () -> {
              ProductMetricPipelineContentEsSupport.Attempt attempt =
                  batchContentEs == null ? null : batchContentEs.claimRestartSearch();
              Throwable searchFailure = null;
              try {
                HttpResponse<String> response =
                    http(
                        "GET",
                        productPort,
                        "/api/products?keyword=Metric&status=1&sort=hot&page=1&pageSize=2",
                        null,
                        false);
                if (response.statusCode() == 503) {
                  if (attempt != null) attempt.http503();
                  audit(
                      "es-unavailable-http",
                      Map.of(
                          "status",
                          503,
                          "body",
                          JSON.readTree(response.body()),
                          "noStore",
                          response.headers().firstValue("Cache-Control").orElse("")));
                  return true;
                }
                assertEquals(200, response.statusCode());
                JsonNode body = JSON.readTree(response.body());
                List<Long> ids = new ArrayList<>();
                for (JsonNode item : body.path("data").path("items"))
                  ids.add(item.path("id").asLong());
                registerObservation(body, "PRODUCT_SEARCH_EXPOSED", ids);
                if (attempt != null) {
                  List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(ids));
                  attempt.registered200(
                      body.path("requestId").asText(),
                      distinctIds,
                      distinctIds.stream()
                          .map(
                              id ->
                                  ProductMetricEvents.product(
                                          body.path("requestId").asText(), id, true, null)
                                      .eventId)
                          .toList());
                }
                return false;
              } catch (HttpTimeoutException unavailable) {
                if (attempt != null) attempt.timeout();
                return false;
              } catch (Exception | Error originalFailure) {
                searchFailure = originalFailure;
                if (attempt != null) attempt.unknown(originalFailure);
                throw originalFailure;
              } finally {
                if (attempt != null) attempt.finish(searchFailure);
              }
            },
            35,
            "ES_RESTART_HTTP_503");
        restarted.get(65, TimeUnit.SECONDS);
      } catch (Exception | Error originalFailure) {
        restartFailure = originalFailure;
        throw originalFailure;
      } finally {
        try {
          command.shutdownNow();
          if (!command.awaitTermination(5, TimeUnit.SECONDS))
            throw new IllegalStateException("CM21_ES_COMMAND_THREAD_UNKNOWN");
        } catch (Exception | Error cleanupFailure) {
          if (batchContentEs == null || restartFailure == null) throw cleanupFailure;
          if (cleanupFailure != restartFailure) restartFailure.addSuppressed(cleanupFailure);
        }
      }
      await(
          () -> {
            try {
              return http(
                          "GET",
                          port("es.port"),
                          "/_cluster/health?wait_for_status=yellow&timeout=1s",
                          null,
                          false)
                      .statusCode()
                  == 200;
            } catch (IOException starting) {
              return false;
            }
          },
          60,
          "ES_RESTART_READY");
      JsonNode after = inspect("es");
      assertNotEquals(
          before.path("State").path("StartedAt").asText(),
          after.path("State").path("StartedAt").asText());
      drain();
      assertEquals(uuid, context.getBean(ElasticsearchProductIndexWriter.class).indexUuid());
      audit(
          "es-restarted",
          Map.of(
              "id",
              required("es.container-id"),
              "indexUuid",
              uuid,
              "baseline",
              baseline,
              "after",
              counts(),
              "databaseReset",
              false));
    }

    /**
     * Close the real context and pool independently before another context can reuse the same
     * facts.
     */
    void stopProduct() throws Exception {
      List<Throwable> failures = new ArrayList<>();
      long schedulerStarted = System.nanoTime(),
          schedulerDeadline = schedulerStarted + TimeUnit.SECONDS.toNanos(15);
      ScheduledExecutorService originalScheduler = null;
      String schedulerBeanName = "NOT_OBSERVED";
      try {
        if (context != null && context.isActive()) {
          var schedulers =
              context.getBeansOfType(
                  org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
          if (schedulers.size() != 1)
            throw new IllegalStateException("CM21_UNIQUE_PRODUCT_SCHEDULER_REQUIRED");
          schedulerBeanName = schedulers.keySet().iterator().next();
          var scheduler = schedulers.get(schedulerBeanName);
          originalScheduler = scheduler.getScheduledExecutor();
          scheduler.setWaitForTasksToCompleteOnShutdown(true);
          scheduler.setAwaitTerminationSeconds(0);
          scheduler.shutdown();
          long remaining = schedulerDeadline - System.nanoTime();
          if (remaining <= 0
              || !originalScheduler.awaitTermination(remaining, TimeUnit.NANOSECONDS)
              || System.nanoTime() > schedulerDeadline)
            throw new IllegalStateException("CM21_PRODUCT_SCHEDULER_NORMAL_EXIT_TIMEOUT");
        }
      } catch (Throwable failure) {
        failures.add(failure);
      } finally {
        try {
          audit(
              "product-scheduler-stop-" + contextGeneration.get(),
              Map.of(
                  "startedNanos",
                  schedulerStarted,
                  "deadlineNanos",
                  schedulerDeadline,
                  "elapsedMillis",
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - schedulerStarted),
                  "schedulerBeanName",
                  schedulerBeanName,
                  "observed",
                  originalScheduler != null,
                  "shutdown",
                  originalScheduler != null && originalScheduler.isShutdown(),
                  "terminated",
                  originalScheduler != null && originalScheduler.isTerminated(),
                  "shutdownNowUsed",
                  false));
        } catch (Throwable auditFailure) {
          failures.add(auditFailure);
        }
      }
      ProductMetricConsumer closingConsumer = null;
      try {
        if (context != null && context.isActive())
          closingConsumer = context.getBean(ProductMetricConsumer.class);
        if (closingConsumer != null && ProductMetricPipelineRedeliverySupport.ROLE.equals(role))
          closingConsumer =
              ProductMetricPipelineRedeliverySupport.ultimateConsumer(closingConsumer);
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        if (context != null && context.isActive()) context.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      Map<String, Object> consumerClose = new LinkedHashMap<>();
      consumerClose.put("generation", contextGeneration.get());
      consumerClose.put("observed", false);
      try {
        if (closingConsumer == null)
          throw new IllegalStateException("CM21_PRODUCT_CONSUMER_CLOSE_NOT_OBSERVED");
        Object gate =
            Objects.requireNonNull(
                org.springframework.test.util.ReflectionTestUtils.getField(
                    closingConsumer, "stateGate"));
        boolean completed;
        long startedNanos;
        long elapsedNanos;
        Throwable closeFailure;
        synchronized (gate) {
          completed =
              Boolean.TRUE.equals(
                  org.springframework.test.util.ReflectionTestUtils.getField(
                      closingConsumer, "closeCompleted"));
          startedNanos =
              (long)
                  org.springframework.test.util.ReflectionTestUtils.getField(
                      closingConsumer, "closeStartedNanos");
          elapsedNanos =
              (long)
                  org.springframework.test.util.ReflectionTestUtils.getField(
                      closingConsumer, "closeElapsedNanos");
          closeFailure =
              (Throwable)
                  org.springframework.test.util.ReflectionTestUtils.getField(
                      closingConsumer, "closeFailure");
        }
        consumerClose.put("observed", true);
        consumerClose.put("completed", completed);
        consumerClose.put("startedNanos", startedNanos);
        consumerClose.put("elapsedNanos", elapsedNanos);
        consumerClose.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        consumerClose.put(
            "failureType", closeFailure == null ? "NONE" : closeFailure.getClass().getName());
        if (closeFailure != null) failures.add(closeFailure);
        if (!completed
            || startedNanos == 0
            || elapsedNanos < 0
            || elapsedNanos > TimeUnit.SECONDS.toNanos(15))
          failures.add(new IllegalStateException("CM21_PRODUCT_CONSUMER_CLOSE_UNKNOWN"));
      } catch (Throwable failure) {
        failures.add(failure);
        consumerClose.put("observationFailureType", failure.getClass().getName());
      } finally {
        try {
          audit("product-consumer-close-" + contextGeneration.get(), consumerClose);
        } catch (Throwable auditFailure) {
          failures.add(auditFailure);
        }
      }
      try {
        if (pool != null && !pool.isClosed()) pool.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (context != null && context.isActive())
        failures.add(new IllegalStateException("PRODUCT_CONTEXT_ACTIVE"));
      if (pool != null && !pool.isClosed())
        failures.add(new IllegalStateException("PRODUCT_POOL_OPEN"));
      if (product.dataSource.open.get() != 0)
        failures.add(new IllegalStateException("PRODUCT_RAW_CONNECTION_OPEN"));
      if (productPort != 0)
        try {
          await(() -> !listening(productPort), 10, "PRODUCT_HTTP_EXIT");
        } catch (Throwable failure) {
          failures.add(failure);
        }
      try {
        audit(
            "product-stop-" + contextGeneration.get(),
            Map.of(
                "contextActive",
                context != null && context.isActive(),
                "poolClosed",
                pool == null || pool.isClosed(),
                "rawConnections",
                product.dataSource.open.get(),
                "frameworkAllThreadsJoinedProven",
                false));
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (!failures.isEmpty()) {
        IllegalStateException error = new IllegalStateException("CM21_PRODUCT_CLOSE_UNKNOWN");
        failures.forEach(error::addSuppressed);
        throw error;
      }
    }

    /** Acknowledge only the exact expected safety exception after original physical close gates. */
    private void stopExpectedBatchSafety() throws Exception {
      int generation = contextGeneration.get();
      Throwable expected = null;
      try {
        stopProduct();
      } catch (IllegalStateException failure) {
        expected = failure;
      }
      Map<String, Object> scheduler =
          JSON.readValue(
              Files.readAllBytes(
                  product.evidence.resolve("product-scheduler-stop-" + generation + ".json")),
              Map.class);
      Map<String, Object> consumer =
          JSON.readValue(
              Files.readAllBytes(
                  product.evidence.resolve("product-consumer-close-" + generation + ".json")),
              Map.class);
      Map<String, Object> stopped =
          JSON.readValue(
              Files.readAllBytes(product.evidence.resolve("product-stop-" + generation + ".json")),
              Map.class);
      batchRedeliveryGeneration.expectedSafetyClosed(
          expected,
          Map.of(
              "schedulerTerminated",
                  Boolean.TRUE.equals(scheduler.get("observed"))
                      && Boolean.TRUE.equals(scheduler.get("terminated"))
                      && Boolean.FALSE.equals(scheduler.get("shutdownNowUsed")),
              "contextInactive", context != null && !context.isActive(),
              "poolClosed", pool != null && pool.isClosed(),
              "rawConnectionsClosed", product.dataSource.open.get() == 0,
              "httpClosed", productPort != 0 && !listening(productPort),
              "listenerStopped",
                  metricContainer != null
                      && !metricContainer.isRunning()
                      && metricContainer.getActiveConsumerCount() == 0,
              "productStopObserved",
                  Boolean.FALSE.equals(stopped.get("contextActive"))
                      && Boolean.TRUE.equals(stopped.get("poolClosed"))
                      && stopped.get("rawConnections") instanceof Number
                      && ((Number) stopped.get("rawConnections")).intValue() == 0,
              "consumerCloseObserved",
                  Boolean.TRUE.equals(consumer.get("observed"))
                      && Boolean.TRUE.equals(consumer.get("completed"))));
    }

    /**
     * Business completion alone cannot authorize cleanup until the independent close gates pass.
     */
    void success() {
      if (loadSupport != null) loadSupport.assertHealthy(false);
      if (batchMode && loadSupport == null) {
        Set<String> ledgerEvents =
            new LinkedHashSet<>(
                product.jdbc.queryForList(
                    "SELECT event_id FROM product_metric_applied_event", String.class));
        Set<String> outboxEvents =
            new LinkedHashSet<>(
                product.jdbc.queryForList(
                    "SELECT event_id FROM product_metric_outbox", String.class));
        outboxEvents.addAll(
            stock.jdbc.queryForList("SELECT event_id FROM stock_sales_outbox", String.class));
        ProductMetricPipelineBatchSupport.containerFacts(
            context, metricContainer, batchOriginalListener);
        try {
          audit(
              "batch-commit-observations",
              batchObserver.successfulFacts(ledgerEvents, outboxEvents));
          if (batchRedelivery != null) {
            Map<Integer, Object> observations = new LinkedHashMap<>();
            for (var entry : batchObservers.entrySet())
              observations.put(
                  entry.getKey(), entry.getValue().successfulFacts(ledgerEvents, outboxEvents));
            audit("batch-redelivery-generations", observations);
            audit("batch-redelivery-success", batchRedelivery.successfulFacts());
          }
          if (batchContentEs != null)
            audit("batch-content-es-success", batchContentEs.successfulFacts());
        } catch (Exception observationFailure) {
          throw new IllegalStateException("CM21_BATCH_FINAL_OBSERVATION_FAILED");
        }
      }
      passed = true;
    }

    /** Only immutable confirm business facts participate in the first-confirm replay comparison. */
    Map<String, Object> stockBusinessFacts() throws Exception {
      Map<String, Object> result = new LinkedHashMap<>();
      for (String table :
          List.of(
              "stock_sku",
              "stock_reservation_request",
              "stock_flow",
              "seckill_reservation",
              "seckill_quota_settlement")) result.put(table, stock.tableFacts(table));
      return result;
    }

    /**
     * Reuse the frozen helper's actual catalog/object/full-source gates before any resource
     * deletion.
     */
    private void databaseGate(ProductOnlineMetricsMySqlIT.OwnedDatabase database) throws Exception {
      Class<?> type = ProductOnlineMetricsMySqlIT.OwnedDatabase.class;
      Method connection = type.getDeclaredMethod("connection", String.class);
      connection.setAccessible(true);
      Method tables = type.getDeclaredMethod("tables", Connection.class);
      tables.setAccessible(true);
      Method facts = type.getDeclaredMethod("facts", Connection.class, List.class);
      facts.setAccessible(true);
      Method objects = type.getDeclaredMethod("assertObjects", Connection.class);
      objects.setAccessible(true);
      try (Connection original = (Connection) connection.invoke(database, database.sourceName)) {
        original.setReadOnly(true);
        if (!database.sourceBefore.equals(
            facts.invoke(null, original, tables.invoke(null, original))))
          throw new IllegalStateException("CM21_ORIGINAL_SOURCE_CHANGED");
      }
      try (Connection owned = database.dataSource.getConnection()) {
        objects.invoke(database, owned);
        try (Statement query = owned.createStatement();
            var rows =
                query.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID()"
                        + " AND DB IN ('"
                        + product.database
                        + "','"
                        + stock.database
                        + "')")) {
          rows.next();
          assertEquals(0L, rows.getLong(1), "owned business JDBC sessions closed");
        }
      }
      assertEquals(0, database.dataSource.open.get());
      database.audit(
          "pipeline-close-gate",
          Map.of(
              "catalog",
              database.database,
              "sourceAllDdlIndexesRowsChecksumUnchanged",
              true,
              "ownedFullFacts",
              database.allFacts()));
    }

    /**
     * Every close attempt is independent; any failed fact, worker or audit preserves both catalogs.
     */
    @Override
    public void close() throws Exception {
      List<Throwable> failures = new ArrayList<>();
      try {
        if (context != null || pool != null) stopProduct();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        stopStock();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        if (adminResources != null) adminResources.close();
      } catch (Throwable failure) {
        failures.add(failure);
      } finally {
        try {
          audit(
              "admin-connection-close",
              Map.of(
                  "observed",
                  adminResources != null,
                  "ownedExecutorShutdown",
                  adminResources != null && adminResources.executorShutdown(),
                  "ownedExecutorTerminated",
                  adminResources != null && adminResources.executorTerminated()));
        } catch (Throwable auditFailure) {
          failures.add(auditFailure);
        }
      }
      if (!passed) failures.add(new IllegalStateException("PIPELINE_ASSERTIONS_NOT_COMPLETE"));
      try {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertNull(AuthContext.userId());
        for (int endpoint : product.ownedPorts) assertFalse(listening(endpoint));
        for (int endpoint : stock.ownedPorts) assertFalse(listening(endpoint));
        if (stockProcess != null) assertFalse(stockProcess.isAlive());
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        databaseGate(product);
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        databaseGate(stock);
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (failures.isEmpty()) {
        boolean observeBrokerClose = "redelivery".equals(role);
        Map<String, Object> originalQueues =
            observeBrokerClose
                ? brokerCloseRecord("original-queues", "/api/queues/" + vhost)
                : null;
        Map<String, Object> originalConnections =
            observeBrokerClose
                ? brokerCloseRecord("original-connections", "/api/connections")
                : null;
        boolean brokerIdentityVerified = false;
        try {
          inspect("rabbit");
          inspect("es");
          inspect("redis");
          brokerIdentityVerified = true;
          HttpResponse<String> queues =
              observeBrokerClose
                  ? brokerCloseGet(originalQueues)
                  : management("GET", "/api/queues/" + vhost, null);
          assertEquals(200, queues.statusCode());
          JsonNode queueFacts = JSON.readTree(queues.body());
          for (JsonNode queue : queueFacts) {
            assertEquals(0L, queue.path("messages").asLong());
            assertEquals(0L, queue.path("messages_unacknowledged").asLong());
          }
          JsonNode connections =
              JSON.readTree(
                  (observeBrokerClose
                          ? brokerCloseGet(originalConnections)
                          : management("GET", "/api/connections", null))
                      .body());
          for (JsonNode connection : connections)
            assertNotEquals(vhost, connection.path("vhost").asText());
          audit(
              "owned-broker-final",
              Map.of("vhost", vhost, "queues", queueFacts, "connectionsForVhost", 0));
          cleanupOwnedRedis();
          assertEquals(204, management("DELETE", "/api/vhosts/" + vhost, null).statusCode());
          es("DELETE", "/" + index, null, 200);
        } catch (Throwable failure) {
          failures.add(failure);
          if (observeBrokerClose) {
            try {
              diagnoseBrokerClose(
                  failure, originalQueues, originalConnections, brokerIdentityVerified);
            } catch (Throwable diagnosticFailure) {
              failure.addSuppressed(diagnosticFailure);
            }
          }
        }
      }
      httpExecutor.shutdown();
      try {
        if (!httpExecutor.awaitTermination(15, TimeUnit.SECONDS))
          throw new IllegalStateException("HTTP_HELPER_NOT_TERMINATED");
      } catch (Throwable failure) {
        failures.add(failure);
        httpExecutor.shutdownNow();
      }
      try {
        audit(
            "pipeline-close",
            Map.of(
                "owner",
                owner,
                "passed",
                passed,
                "failureTypes",
                failures.stream().map(error -> error.getClass().getName()).toList(),
                "ownHttpExecutorTerminated",
                httpExecutor.isTerminated(),
                "allFrameworkThreadsJoinedProven",
                false));
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (failures.isEmpty()) {
        product.success();
        stock.success();
      }
      try {
        product.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      try {
        stock.close();
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (!failures.isEmpty()) {
        IllegalStateException preserved =
            new IllegalStateException("CM21_PIPELINE_FAILED_OR_UNKNOWN_PRESERVED");
        failures.forEach(preserved::addSuppressed);
        throw preserved;
      }
    }

    /** Bind every close read to this run without inventing a result before its original request. */
    private Map<String, Object> brokerCloseRecord(String stage, String path) {
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("stage", stage);
      record.put("phase", "AFTER_ORIGINAL_WORKER_AND_DATABASE_GATES");
      record.put("owner", owner);
      record.put("brokerContainerId", System.getProperty("cloudmall.cm21.rabbit.container-id"));
      record.put("brokerResourceEvidenceDir", resourceEvidence.toString());
      record.put("managementPort", System.getProperty("cloudmall.cm21.rabbit.management-port"));
      record.put("vhost", vhost);
      record.put("role", role);
      record.put("contextGeneration", contextGeneration.get());
      record.put("method", "GET");
      record.put("path", path);
      record.put("requestState", "NOT_REQUESTED");
      record.put("startedAtUtc", null);
      record.put("finishedAtUtc", null);
      record.put("startedNanos", null);
      record.put("finishedNanos", null);
      record.put("status", null);
      record.put("body", null);
      return record;
    }

    /** Persist the unchanged raw response before any caller parses it or asserts its status. */
    private HttpResponse<String> brokerCloseGet(Map<String, Object> record) throws Exception {
      record.put("startedAtUtc", Instant.now().toString());
      record.put("startedNanos", System.nanoTime());
      HttpResponse<String> response;
      try {
        response = management("GET", (String) record.get("path"), null);
      } catch (Exception | Error failure) {
        finishBrokerCloseRecord(record, null, failure);
        try {
          audit("broker-close-" + record.get("stage"), record);
        } catch (Throwable auditFailure) {
          failure.addSuppressed(auditFailure);
        }
        throw failure;
      }
      finishBrokerCloseRecord(record, response, null);
      audit("broker-close-" + record.get("stage"), record);
      return response;
    }

    /** Request errors keep absent status/body; an audit error never replaces an earlier error. */
    private void finishBrokerCloseRecord(
        Map<String, Object> record, HttpResponse<String> response, Throwable failure) {
      record.put("finishedNanos", System.nanoTime());
      record.put("finishedAtUtc", Instant.now().toString());
      record.put(
          "elapsedMillis",
          TimeUnit.NANOSECONDS.toMillis(
              (long) record.get("finishedNanos") - (long) record.get("startedNanos")));
      record.put("requestState", failure == null ? "RESPONSE_RECEIVED" : "REQUEST_FAILED");
      if (response != null) {
        record.put("status", response.statusCode());
        record.put("body", response.body());
        record.put(
            "bodySha256",
            response.body() == null
                ? null
                : ProductMetricEvents.sha256(response.body().getBytes(StandardCharsets.UTF_8)));
      }
      if (failure != null) record.put("requestFailureType", failure.getClass().getName());
    }

    /** Fixed sequential reads diagnose a retained failure; no observation can grant cleanup. */
    private void diagnoseBrokerClose(
        Throwable originalFailure,
        Map<String, Object> originalQueues,
        Map<String, Object> originalConnections,
        boolean brokerIdentityVerified)
        throws Exception {
      Map<String, Object> diagnosis = new LinkedHashMap<>();
      diagnosis.put("owner", owner);
      diagnosis.put("vhost", vhost);
      diagnosis.put("contextGeneration", contextGeneration.get());
      diagnosis.put("originalFailureType", originalFailure.getClass().getName());
      diagnosis.put("brokerIdentityVerified", brokerIdentityVerified);
      diagnosis.put("originalQueuesRequestState", originalQueues.get("requestState"));
      diagnosis.put("originalConnectionsRequestState", originalConnections.get("requestState"));
      Map<String, Object> originalQueueInterpretation =
          interpretBrokerClose(originalQueues, false, originalFailure);
      diagnosis.put("originalQueues", originalQueueInterpretation);
      boolean originalQueueInventoryKnown =
          "VALID".equals(originalQueueInterpretation.get("interpretation"));
      diagnosis.put("sameOriginalQueueInventoryRequired", true);
      diagnosis.put("originalQueueInventoryKnown", originalQueueInventoryKnown);
      Map<String, Object> connections =
          interpretBrokerClose(originalConnections, true, originalFailure);
      diagnosis.put("originalConnections", connections);
      List<Map<String, Object>> observations = new ArrayList<>();
      diagnosis.put("diagnosticReads", observations);
      diagnosis.put("causalConclusion", "UNKNOWN");
      diagnosis.put("originalFailureAndPreservationUnchanged", true);
      diagnosis.put(
          "limits",
          List.of(
              "Sequential samples are not an atomic broker snapshot or proof of an earlier run.",
              "TTL, per-message expiration, DLX movement and other clients are not excluded by"
                  + " these reads.",
              "A missing consumers field is unavailable; missing totals/connections are never"
                  + " zero."));
      if (brokerIdentityVerified) {
        String cachedPath = "/api/queues/" + vhost;
        String freshPath = cachedPath + "?disable_stats=true&enable_queue_totals=true";
        String[] stages = {"diagnostic-fresh-1", "diagnostic-cached-repeat", "diagnostic-fresh-2"};
        String[] paths = {freshPath, cachedPath, freshPath};
        for (int index = 0; index < stages.length; index++) {
          Map<String, Object> observation = brokerCloseRecord(stages[index], paths[index]);
          try {
            brokerCloseGet(observation);
          } catch (Throwable diagnosticFailure) {
            originalFailure.addSuppressed(diagnosticFailure);
          }
          observations.add(
              Map.of(
                  "stage",
                  stages[index],
                  "requestState",
                  observation.get("requestState"),
                  "interpretation",
                  originalQueueInventoryKnown
                      ? interpretBrokerClose(observation, false, originalFailure)
                      : Map.of(
                          "interpretation",
                          "UNKNOWN",
                          "reason",
                          "ORIGINAL_QUEUE_INVENTORY_UNKNOWN")));
        }
        if (!"VALID".equals(connections.get("interpretation"))) {
          Map<String, Object> observation =
              brokerCloseRecord("diagnostic-connections", "/api/connections");
          try {
            brokerCloseGet(observation);
          } catch (Throwable diagnosticFailure) {
            originalFailure.addSuppressed(diagnosticFailure);
          }
          observations.add(
              Map.of(
                  "stage",
                  "diagnostic-connections",
                  "requestState",
                  observation.get("requestState"),
                  "interpretation",
                  interpretBrokerClose(observation, true, originalFailure)));
        }
        diagnosis.put("diagnosticRequestCount", observations.size());
        diagnosis.put("perRequestTimeoutMillis", 5000);
        diagnosis.put("maximumClientRequestBudgetMillis", observations.size() * 5000);
      } else {
        diagnosis.put("diagnosticState", "NOT_REQUESTED_OWNER_IDENTITY_UNKNOWN");
        diagnosis.put("diagnosticRequestCount", 0);
      }
      audit("broker-close-failure-diagnosis", diagnosis);
    }

    /** Schema or collector gaps remain UNKNOWN, independently of the original numeric assertion. */
    private Map<String, Object> interpretBrokerClose(
        Map<String, Object> record, boolean connections, Throwable originalFailure) {
      if (!"RESPONSE_RECEIVED".equals(record.get("requestState")))
        return Map.of("interpretation", "UNKNOWN", "reason", record.get("requestState"));
      if (!Integer.valueOf(200).equals(record.get("status")))
        return Map.of("interpretation", "UNKNOWN", "reason", "HTTP_STATUS_NOT_200");
      try {
        JsonNode body =
            JSON.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree((String) record.get("body"));
        if (body == null || !body.isArray())
          return Map.of("interpretation", "UNKNOWN", "reason", "TOP_LEVEL_NOT_ARRAY");
        return connections ? interpretBrokerConnections(body) : interpretBrokerQueues(body);
      } catch (Throwable interpretationFailure) {
        originalFailure.addSuppressed(interpretationFailure);
        return Map.of(
            "interpretation",
            "UNKNOWN",
            "reason",
            "RESPONSE_INTERPRETATION_FAILED",
            "failureType",
            interpretationFailure.getClass().getName());
      }
    }

    /** The six declared content/metric queues must all be present exactly once for valid totals. */
    private Map<String, Object> interpretBrokerQueues(JsonNode body) {
      Set<String> expected =
          Set.of(
              "cloudmall.product.index.queue", "cloudmall.product.index.dlq",
              "cloudmall.product.index.dlq.retry", "cloudmall.product.metrics.queue",
              "cloudmall.product.metrics.dlq", "cloudmall.product.metrics.retry");
      Map<String, Object> queues = new TreeMap<>();
      boolean allZero = true;
      for (JsonNode entry : body) {
        JsonNode name = entry.get("name"), observedVhost = entry.get("vhost");
        if (!entry.isObject()
            || name == null
            || !name.isTextual()
            || observedVhost == null
            || !observedVhost.isTextual()
            || !vhost.equals(observedVhost.textValue())
            || !expected.contains(name.textValue())
            || queues.containsKey(name.textValue()))
          return Map.of(
              "interpretation", "UNKNOWN", "reason", "QUEUE_IDENTITY_OR_INVENTORY_INVALID");
        JsonNode state = entry.get("state");
        if (state != null && (!state.isTextual() || !"running".equals(state.textValue())))
          return Map.of("interpretation", "UNKNOWN", "reason", "QUEUE_DOWN_OR_STATE_INVALID");
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String field : List.of("messages", "messages_ready", "messages_unacknowledged")) {
          JsonNode value = entry.get(field);
          if (!brokerNonnegativeLong(value))
            return Map.of("interpretation", "UNKNOWN", "reason", "QUEUE_TOTAL_MISSING_OR_INVALID");
          totals.put(field, value.longValue());
          allZero &= value.longValue() == 0;
        }
        JsonNode consumers = entry.get("consumers");
        if (consumers == null) totals.put("consumers", "UNAVAILABLE");
        else if (!brokerNonnegativeLong(consumers))
          return Map.of("interpretation", "UNKNOWN", "reason", "QUEUE_CONSUMERS_INVALID");
        else totals.put("consumers", consumers.longValue());
        queues.put(name.textValue(), totals);
      }
      if (!queues.keySet().equals(expected))
        return Map.of(
            "interpretation",
            "UNKNOWN",
            "reason",
            "QUEUE_INVENTORY_INCOMPLETE",
            "observedQueueNames",
            new ArrayList<>(queues.keySet()));
      return Map.of(
          "interpretation",
          "VALID",
          "vhost",
          vhost,
          "queues",
          queues,
          "queueNames",
          new ArrayList<>(queues.keySet()),
          "allTotalsZero",
          allZero,
          "inventorySource",
          "ORIGINAL_DECLARED_CONTENT_AND_METRICS_TOPOLOGY");
    }

    /** No connection count exists unless the complete response exposes each entry's identity. */
    private Map<String, Object> interpretBrokerConnections(JsonNode body) {
      Set<String> names = new HashSet<>();
      List<String> matching = new ArrayList<>();
      for (JsonNode entry : body) {
        JsonNode name = entry.get("name"), observedVhost = entry.get("vhost");
        if (!entry.isObject()
            || name == null
            || !name.isTextual()
            || name.textValue().isEmpty()
            || observedVhost == null
            || !observedVhost.isTextual()
            || !names.add(name.textValue()))
          return Map.of(
              "interpretation", "UNKNOWN", "reason", "CONNECTION_IDENTITY_MISSING_OR_INVALID");
        if (vhost.equals(observedVhost.textValue())) matching.add(name.textValue());
      }
      return Map.of(
          "interpretation",
          "VALID",
          "matchingConnectionCount",
          matching.size(),
          "matchingConnectionNames",
          matching,
          "sequentialCachedObservation",
          true);
    }

    /** Reject missing, fractional, negative or overflowing totals instead of defaulting to zero. */
    private boolean brokerNonnegativeLong(JsonNode value) {
      return value != null
          && value.isIntegralNumber()
          && value.canConvertToLong()
          && value.longValue() >= 0;
    }

    /**
     * Delete only the original cache keys derived from this fixture's unique SKU/order identities.
     */
    private void cleanupOwnedRedis() throws Exception {
      inspect("redis");
      var standalone =
          new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
              "127.0.0.1", port("redis.port"));
      standalone.setPassword("root");
      var factory =
          new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
              standalone);
      factory.afterPropertiesSet();
      try {
        Set<String> allowed = new TreeSet<>();
        for (long sku : List.of(normalSku, seckillSku)) {
          allowed.add("stock:available:" + sku);
          allowed.add("stock:repair-blocked:" + sku);
        }
        for (String order : seededOrders) {
          allowed.add("stock:reservation:" + order);
          allowed.add("stock:reservation:" + order + ":lines");
        }
        var template = new org.springframework.data.redis.core.StringRedisTemplate(factory);
        Map<String, Object> facts = new TreeMap<>();
        for (String key : allowed) {
          try (var connection = factory.getConnection()) {
            byte[] dump = connection.dump(key.getBytes(StandardCharsets.UTF_8));
            facts.put(
                key,
                Map.of(
                    "exists",
                    dump != null,
                    "dumpBase64",
                    dump == null ? "" : Base64.getEncoder().encodeToString(dump)));
          }
        }
        audit("owned-redis-final", Map.of("id", required("redis.container-id"), "keys", facts));
        template.delete(allowed);
        for (String key : allowed) assertFalse(Boolean.TRUE.equals(template.hasKey(key)));
      } finally {
        factory.destroy();
      }
    }

    /**
     * Independent ledger aggregation checks every exact counter and immutable public event body.
     */
    private void verifyLedgerGolden() throws Exception {
      assertEquals(
          0L,
          product.scalar(
              "SELECT COUNT(*) FROM product_online_stat s LEFT JOIN (SELECT product_id,COUNT(*)"
                  + " n,SUM(CASE WHEN event_type='PRODUCT_VIEW_CONFIRMED' THEN quantity ELSE 0 END)"
                  + " v,SUM(CASE WHEN event_type='PRODUCT_SEARCH_EXPOSED' THEN quantity ELSE 0 END)"
                  + " q,SUM(CASE WHEN event_type='STOCK_SALE_CONFIRMED' THEN quantity ELSE 0 END)"
                  + " sold FROM product_metric_applied_event GROUP BY product_id) g ON"
                  + " g.product_id=s.product_id WHERE s.view_count<>COALESCE(g.v,0) OR"
                  + " s.search_count<>COALESCE(g.q,0) OR s.sold_count<>COALESCE(g.sold,0) OR"
                  + " s.metric_version<>COALESCE(g.n,0)"));
      assertEquals(
          0L,
          product.scalar(
              "SELECT COUNT(*) FROM product_hot_stat WHERE view_count<>900 OR search_count<>800 OR"
                  + " hot_score<>123.000000"));
      for (Map<String, Object> row :
          product.jdbc.queryForList(
              "SELECT event_id,payload_json,fingerprint FROM product_metric_outbox ORDER BY"
                  + " event_id")) {
        var event =
            ProductMetricEvents.parse(
                String.valueOf(row.get("payload_json")).getBytes(StandardCharsets.UTF_8));
        String identity =
            ProductMetricEvents.sha256(
                ("CM21:1:PRODUCT:"
                        + (event.eventType.equals("PRODUCT_VIEW_CONFIRMED") ? "VIEW:" : "SEARCH:")
                        + event.requestId
                        + ":"
                        + event.productId)
                    .getBytes(StandardCharsets.UTF_8));
        assertEquals(identity, row.get("event_id"));
        assertEquals(row.get("fingerprint"), ProductMetricEvents.fingerprint(event));
        assertEquals(
            row.get("fingerprint"),
            product.jdbc.queryForObject(
                "SELECT fingerprint FROM product_metric_applied_event WHERE event_id=?",
                String.class,
                identity));
      }
    }
  }

  /** Test-only bounded client observations; never performs business SQL or changes outcomes. */
  private static final class PhaseTiming {
    /** Default-off bridge; only an explicit test injection can provide fake observations. */
    private final Cm21PhaseAnchorBridge anchorBridge;

    /** Service and JDBC bounds are independent and never silently overwrite earlier records. */
    private final int callLimit = 32768, phaseLimit = 200000;

    /** Completed records contain only fixed categories and local timing/identity facts. */
    private final List<Map<String, Object>> calls = new ArrayList<>(), phases = new ArrayList<>();

    /** In-flight service identities permit explicit cross-window accounting. */
    private final Map<Long, Long> active = new HashMap<>();

    /** Context is restored rather than overwritten when original proxies invoke nested calls. */
    private final ThreadLocal<Frame> current = new ThreadLocal<>();

    /** Monotonic local identifiers do not pretend to be server connection identities. */
    private final AtomicLong callIds = new AtomicLong(), borrowIds = new AtomicLong();

    /** Observer failures never replace an original return value or throwable. */
    private final AtomicLong observerFailures = new AtomicLong();

    /** Buffer exhaustion makes this diagnostic incomplete without changing business work. */
    private volatile boolean overflow;

    /**
     * A successful boundary export prevents duplicate CREATE_NEW writes during abort preservation.
     */
    private boolean exported;

    /** Startup readback records the actual original proxy and unique resource identities. */
    private volatile boolean serviceAdviceInstalled;

    /** The sole observer bean and original pool remain available for identity-only verification. */
    private DataSource observedDataSource, originalDataSource;

    /** Window anchors are independent of the runner's actual first/last request timestamps. */
    private volatile long windowBegin, windowEnd;

    /** UTC is used only as an alignment anchor, never to calculate elapsed time. */
    private final String startupUtc = Instant.now().toString();

    /** Startup monotonic anchor shares the JVM clock with all recorded intervals. */
    private final long startupNanos = System.nanoTime();

    /** Window boundary snapshots retain in-flight identities without waiting for transactions. */
    private List<Long> activeAtBegin = List.of(), activeAtEnd = List.of();

    /** Explicit UTC boundary facts align this diagnostic with external observations. */
    private String beginUtc, endUtc;

    /** One immutable stack entry belongs to exactly one original proxy invocation. */
    private record Frame(long id, long parent, String method) {}

    /** Validate deterministic transparent helpers without acquiring any connection. */
    private PhaseTiming() {
      this(true);
    }

    /** Pure validation creates no middleware, pool, connection, or framework context. */
    private PhaseTiming(boolean validate) {
      this(Cm21PhaseAnchorBridge.disabled(), validate);
    }

    /** Explicit fake injection does not create a SQL-capable or live-enabled path. */
    private PhaseTiming(Cm21PhaseAnchorBridge anchorBridge, boolean validate) {
      this.anchorBridge = Objects.requireNonNull(anchorBridge);
      if (validate) selfCheck();
    }

    /** Pure helper validation is also callable by root reflectively before real startup. */
    private static void selfCheck() {
      IllegalStateException expected = new IllegalStateException("CM21_OBSERVER_SELF_CHECK");
      try {
        Method method = PhaseTiming.class.getDeclaredMethod("selfCheckFailure", Throwable.class);
        invokeOriginal(method, null, new Object[] {expected});
        throw new AssertionError("CM21_ORIGINAL_CAUSE_REQUIRED");
      } catch (Throwable actual) {
        if (actual != expected) throw new AssertionError("CM21_ORIGINAL_CAUSE_REQUIRED");
      }
      if (!"CONTROL_LOCK"
              .equals(
                  sqlCategory(
                      "SELECT * FROM product_metric_control WHERE singleton_id=1 FOR UPDATE"))
          || !"OTHER"
              .equals(
                  sqlCategory(
                      "SELECT * FROM product_metric_control WHERE singleton_id=2 FOR UPDATE")))
        throw new AssertionError("CM21_SQL_CATEGORY_REQUIRED");
      String identityReadSql =
          "SELECT event_id,fingerprint FROM product_metric_applied_event WHERE event_id=?";
      String statInsertSql =
          "INSERT INTO"
              + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at)"
              + " VALUES(?,0,0,0,0,0,?,?)";
      String statEnsureSql = statInsertSql + " ON DUPLICATE KEY UPDATE product_id=product_id";
      if (!"LEDGER_IDENTITY_READ".equals(sqlCategory(identityReadSql))
          || !"STAT_ENSURE".equals(sqlCategory(statEnsureSql))
          || !"STAT_INSERT".equals(sqlCategory(statInsertSql))
          || !"OTHER".equals(sqlCategory(identityReadSql + " FOR UPDATE"))
          || !"OTHER"
              .equals(
                  sqlCategory(identityReadSql.replace("WHERE event_id=?", "WHERE fingerprint=?")))
          || !"OTHER"
              .equals(
                  sqlCategory(
                      statEnsureSql.replace("product_id=product_id", "view_count=view_count+1")))
          || !"OTHER".equals(sqlCategory(statEnsureSql + " ")))
        throw new AssertionError("CM21_LATE_APPLY_SQL_CATEGORY_REQUIRED");
      String initialSnapshotSql =
          "SELECT"
              + " metric_control.singleton_id,metric_control.series_id,metric_control.revision,metric_control.started_at,metric_control.index_uuid,metric_control.index_ready,metric_control.updated_at,applied.event_id"
              + " AS applied_event_id,applied.fingerprint AS applied_fingerprint FROM"
              + " product_metric_control metric_control LEFT JOIN product_metric_applied_event"
              + " applied ON applied.event_id=? WHERE metric_control.singleton_id=1";
      if (!"INITIAL_APPLY_SNAPSHOT".equals(sqlCategory(initialSnapshotSql))
          || !"OTHER".equals(sqlCategory(initialSnapshotSql + " FOR UPDATE"))
          || !"OTHER".equals(sqlCategory(initialSnapshotSql + " FOR SHARE"))
          || !"OTHER"
              .equals(sqlCategory(initialSnapshotSql.replace("singleton_id=1", "singleton_id=2")))
          || !"OTHER"
              .equals(
                  sqlCategory(
                      initialSnapshotSql.replace("applied.event_id=?", "applied.fingerprint=?")))
          || !"OTHER"
              .equals(sqlCategory(initialSnapshotSql.replace("metric_control.updated_at,", "")))
          || !"OTHER".equals(sqlCategory(initialSnapshotSql.replace(" LEFT JOIN ", " INNER JOIN ")))
          || !"OTHER"
              .equals(
                  sqlCategory(
                      initialSnapshotSql.replace(
                          " WHERE ",
                          " LEFT JOIN product_online_stat stat ON stat.product_id=1 WHERE ")))
          || !"OTHER".equals(sqlCategory(initialSnapshotSql + " ")))
        throw new AssertionError("CM21_INITIAL_APPLY_SNAPSHOT_CATEGORY_REQUIRED");
      PhaseTiming observer = new PhaseTiming(false);
      List<Map<String, Object>> bounded = new ArrayList<>();
      observer.append(bounded, 1, Map.of("first", true));
      observer.append(bounded, 1, Map.of("second", true));
      if (!observer.overflow || bounded.size() != 1 || !bounded.get(0).containsKey("first"))
        throw new AssertionError("CM21_BOUNDED_RETENTION_REQUIRED");
      AtomicInteger delegated = new AtomicInteger();
      AtomicReference<Throwable> delegatedFailure = new AtomicReference<>();
      Object marker = new Object();
      org.aopalliance.intercept.MethodInvocation invocation =
          new org.aopalliance.intercept.MethodInvocation() {
            /** Supply a stable test method without exposing a public business seam. */
            @Override
            public Method getMethod() {
              try {
                return PhaseTiming.class.getDeclaredMethod("selfCheckFailure", Throwable.class);
              } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
              }
            }

            /** No business argument is needed to verify transparent delegation. */
            @Override
            public Object[] getArguments() {
              return new Object[0];
            }

            /** The original object must be returned exactly once. */
            @Override
            public Object proceed() throws Throwable {
              delegated.incrementAndGet();
              if (delegatedFailure.get() != null) throw delegatedFailure.get();
              return marker;
            }

            /** This synthetic invocation has no business target. */
            @Override
            public Object getThis() {
              return null;
            }

            /** Preserve the invocation's ordinary reflection identity. */
            @Override
            public AccessibleObject getStaticPart() {
              return getMethod();
            }
          };
      try {
        if (observer.serviceAdvice().invoke(invocation) != marker
            || delegated.get() != 1
            || observer.current.get() != null
            || !observer.active.isEmpty())
          throw new AssertionError("CM21_SINGLE_DELEGATE_AND_CONTEXT_REQUIRED");
        Frame enclosing = new Frame(900, 0, "SELF_CHECK");
        observer.current.set(enclosing);
        if (observer.serviceAdvice().invoke(invocation) != marker
            || delegated.get() != 2
            || observer.current.get() != enclosing
            || !observer.active.isEmpty())
          throw new AssertionError("CM21_NESTED_CONTEXT_RESTORE_REQUIRED");
        observer.current.remove();
        delegatedFailure.set(expected);
        try {
          observer.serviceAdvice().invoke(invocation);
          throw new AssertionError("CM21_SERVICE_ORIGINAL_THROWABLE_REQUIRED");
        } catch (Throwable actual) {
          if (actual != expected
              || delegated.get() != 3
              || observer.current.get() != null
              || !observer.active.isEmpty())
            throw new AssertionError("CM21_SERVICE_ORIGINAL_THROWABLE_AND_CLEANUP_REQUIRED");
        }
      } catch (Throwable failure) {
        throw new AssertionError("CM21_TRANSPARENT_SERVICE_REQUIRED", failure);
      }
    }

    /** A deterministic identity-preserving throwable target for the pure self check. */
    private static void selfCheckFailure(Throwable failure) throws Throwable {
      throw failure;
    }

    /** Reflection delegates exactly once and rethrows the original cause object. */
    private static Object invokeOriginal(Method method, Object target, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }

    /** Observe the real transaction proxy including its commit/rollback and cleanup. */
    private org.aopalliance.intercept.MethodInterceptor serviceAdvice() {
      return invocation -> {
        Frame previous = current.get();
        long id = callIds.incrementAndGet();
        Frame frame =
            new Frame(id, previous == null ? 0 : previous.id(), invocation.getMethod().getName());
        long started = System.nanoTime();
        try {
          current.set(frame);
          anchorBridge.serviceEntered(frame.id(), frame.parent(), frame.method());
          synchronized (this) {
            if (active.size() < callLimit) active.put(id, started);
            else overflow = true;
          }
        } catch (Throwable observation) {
          observerFailures.incrementAndGet();
        }
        Object result = null;
        Throwable problem = null;
        try {
          result = invocation.proceed();
          return result;
        } catch (Throwable failure) {
          problem = failure;
          throw failure;
        } finally {
          long finished = System.nanoTime();
          try {
            anchorBridge.serviceTerminal(frame.id(), problem);
            Map<String, Object> fact = base("SERVICE", frame, 0, 0, started, finished, problem);
            fact.put(
                "result",
                problem != null
                    ? "FAILED"
                    : "apply".equals(frame.method())
                            && ("APPLIED".equals(result) || "DUPLICATE".equals(result))
                        ? result
                        : "SUCCESS");
            if (result instanceof Collection<?> batch) fact.put("count", batch.size());
            if (result instanceof Map<?, ?> batch) fact.put("count", batch.size());
            if (result instanceof Number count) fact.put("count", count.longValue());
            if (invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof Map<?, ?> batch) {
              fact.put("batchCount", batch.size());
              if ("finishBatch".equals(frame.method())) {
                fact.put(
                    "confirmedCount", batch.values().stream().filter(Boolean.TRUE::equals).count());
                fact.put(
                    "uncertainCount",
                    batch.values().stream().filter(Boolean.FALSE::equals).count());
              }
              if ("finishProjectionBatch".equals(frame.method())
                  && invocation.getArguments().length == 3) {
                boolean acknowledged = Boolean.TRUE.equals(invocation.getArguments()[2]);
                fact.put("confirmedCount", acknowledged ? batch.size() : 0);
                fact.put("uncertainCount", acknowledged ? 0 : batch.size());
              }
            }
            synchronized (this) {
              active.remove(id);
              append(calls, callLimit, fact);
            }
          } catch (Throwable observation) {
            observerFailures.incrementAndGet();
          } finally {
            if (previous == null) current.remove();
            else current.set(previous);
          }
        }
      };
    }

    /** Construct a unique resource wrapper; Boot uses this same bean for JDBC and transactions. */
    private DataSource dataSource(DataSource original) {
      originalDataSource = original;
      observedDataSource =
          (DataSource)
              java.lang.reflect.Proxy.newProxyInstance(
                  DataSource.class.getClassLoader(),
                  new Class<?>[] {DataSource.class, Closeable.class},
                  (proxy, method, arguments) -> {
                    if ("getConnection".equals(method.getName())) {
                      long borrow = borrowIds.incrementAndGet(), started = System.nanoTime();
                      Connection connection = null;
                      Throwable problem = null;
                      try {
                        connection = (Connection) invokeOriginal(method, original, arguments);
                      } catch (Throwable failure) {
                        problem = failure;
                        throw failure;
                      } finally {
                        record(
                            "GET_CONNECTION",
                            current.get(),
                            borrow,
                            0,
                            started,
                            System.nanoTime(),
                            problem);
                      }
                      long server = 0;
                      long identityStarted = System.nanoTime();
                      try {
                        server = connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getId();
                      } catch (Throwable observation) {
                        observerFailures.incrementAndGet();
                      }
                      record(
                          "CONNECTION_ID",
                          current.get(),
                          borrow,
                          server,
                          identityStarted,
                          System.nanoTime(),
                          null);
                      return jdbcProxy(connection, Connection.class, borrow, server, "OTHER", null);
                    }
                    return wrapperCall(proxy, original, method, arguments);
                  });
      return observedDataSource;
    }

    /** Read actual bean identities and proxy facts without acquiring a JDBC connection. */
    private Map<String, Object> startupFacts(
        ConfigurableApplicationContext context, HikariDataSource pool) {
      DataSource bean = context.getBean(DataSource.class);
      Object manager =
          context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
      boolean mapperSame =
          context.getBean(com.cloudmall.product.mapper.ProductMetricMapper.class).dataSource()
              == bean;
      boolean managerSame =
          manager instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager local
              && local.getDataSource() == bean;
      boolean originalProxy =
          context.getBean(ProductMetricService.class)
              instanceof org.springframework.aop.framework.Advised;
      boolean same = bean == observedDataSource && originalDataSource == pool;
      if (!mapperSame || !managerSame || !originalProxy || !same || !serviceAdviceInstalled)
        observerFailures.incrementAndGet();
      return Map.of(
          "serviceAdvised",
          originalProxy,
          "serviceAdviceInstalled",
          serviceAdviceInstalled,
          "mapperSameDataSource",
          mapperSame,
          "transactionManagerSameDataSource",
          managerSame,
          "observedBeanAndOriginalPool",
          same,
          "extraSql",
          0,
          "dispatchRound",
          "UNKNOWN_PROXY_NOT_INSTALLED");
    }

    /** Preserve JDBC wrapper semantics while allowing supported interface unwrapping. */
    private static Object wrapperCall(
        Object proxy, Object original, Method method, Object[] arguments) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        if ("equals".equals(method.getName())) return proxy == arguments[0];
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("toString".equals(method.getName())) return "CM21ObservedJdbcWrapper";
      }
      if ("unwrap".equals(method.getName())
          && arguments != null
          && arguments[0] instanceof Class<?> type
          && type.isInstance(proxy)) return proxy;
      if ("isWrapperFor".equals(method.getName())
          && arguments != null
          && arguments[0] instanceof Class<?> type
          && type.isInstance(proxy)) return true;
      return invokeOriginal(method, original, arguments);
    }

    /**
     * Every original JDBC call is delegated once; returned JDBC objects retain observation links.
     */
    private Object jdbcProxy(
        Object original,
        Class<?> contract,
        long borrow,
        long server,
        String category,
        Object parent) {
      try {
        return java.lang.reflect.Proxy.newProxyInstance(
            contract.getClassLoader(),
            new Class<?>[] {contract},
            (proxy, method, arguments) -> {
              String name = method.getName();
              String actualCategory = category;
              if (("prepareStatement".equals(name)
                      || "prepareCall".equals(name)
                      || name.startsWith("execute"))
                  && arguments != null
                  && arguments.length > 0
                  && arguments[0] instanceof String sql) actualCategory = sqlCategory(sql);
              boolean observed =
                  name.startsWith("execute") || "commit".equals(name) || "rollback".equals(name);
              if (anchorBridge.enabledForFake()
                  && original instanceof Connection connection
                  && "commit".equals(name)) {
                Frame frame = current.get();
                String commitCategory = actualCategory;
                return anchorBridge.commit(
                    frame == null ? 0 : frame.id(),
                    borrow,
                    server,
                    connection,
                    () -> {
                      long delegateStarted = System.nanoTime();
                      Throwable delegateProblem = null;
                      try {
                        return wrapperCall(proxy, original, method, arguments);
                      } catch (Throwable failure) {
                        delegateProblem = failure;
                        throw failure;
                      } finally {
                        record(
                            "CONNECTION_commit",
                            current.get(),
                            borrow,
                            server,
                            delegateStarted,
                            System.nanoTime(),
                            delegateProblem,
                            commitCategory);
                      }
                    });
              }
              long started = observed ? System.nanoTime() : 0;
              Throwable problem = null;
              Object returned;
              try {
                returned = wrapperCall(proxy, original, method, arguments);
              } catch (Throwable failure) {
                problem = failure;
                throw failure;
              } finally {
                if (observed)
                  record(
                      original instanceof Connection
                          ? "CONNECTION_" + name
                          : original instanceof java.sql.ResultSet
                              ? "RESULT_" + name
                              : "SQL_" + name,
                      current.get(),
                      borrow,
                      server,
                      started,
                      System.nanoTime(),
                      problem,
                      actualCategory);
              }
              if ("getConnection".equals(name) && parent != null && original instanceof Statement)
                return parent;
              if ("getStatement".equals(name)
                  && parent != null
                  && original instanceof java.sql.ResultSet) return parent;
              if (returned instanceof java.sql.CallableStatement)
                return jdbcProxy(
                    returned,
                    java.sql.CallableStatement.class,
                    borrow,
                    server,
                    actualCategory,
                    proxy);
              if (returned instanceof java.sql.PreparedStatement)
                return jdbcProxy(
                    returned,
                    java.sql.PreparedStatement.class,
                    borrow,
                    server,
                    actualCategory,
                    proxy);
              if (returned instanceof Statement)
                return jdbcProxy(returned, Statement.class, borrow, server, actualCategory, proxy);
              if (returned instanceof java.sql.ResultSet)
                return jdbcProxy(
                    returned, java.sql.ResultSet.class, borrow, server, actualCategory, proxy);
              return returned;
            });
      } catch (Throwable observation) {
        observerFailures.incrementAndGet();
        return original;
      }
    }

    /** Record only allowlisted facts; observation errors are separate from original outcomes. */
    private void record(
        String kind,
        Frame frame,
        long borrow,
        long server,
        long start,
        long end,
        Throwable problem,
        String... category) {
      try {
        Map<String, Object> fact = base(kind, frame, borrow, server, start, end, problem);
        if (category.length > 0) fact.put("category", category[0]);
        synchronized (this) {
          append(phases, phaseLimit, fact);
        }
      } catch (Throwable observation) {
        observerFailures.incrementAndGet();
      }
    }

    /** Fixed metadata contains no SQL, parameter, message, URL, or exception text. */
    private static Map<String, Object> base(
        String kind,
        Frame frame,
        long borrow,
        long server,
        long start,
        long end,
        Throwable problem) {
      Map<String, Object> fact = new LinkedHashMap<>();
      fact.put("kind", kind);
      fact.put("call", frame == null ? 0 : frame.id());
      fact.put("parentCall", frame == null ? 0 : frame.parent());
      fact.put("caller", frame == null ? "UNATTRIBUTED" : frame.method());
      fact.put("thread", Thread.currentThread().getId());
      fact.put("borrow", borrow);
      fact.put("serverConnection", server);
      fact.put("startNanos", start);
      fact.put("endNanos", end);
      fact.put("outcome", problem == null ? "SUCCESS" : "FAILED");
      for (Throwable cause = problem; cause != null; cause = cause.getCause())
        if (cause instanceof java.sql.SQLException sql) {
          fact.put("sqlState", sql.getSQLState());
          fact.put("errorCode", sql.getErrorCode());
          break;
        }
      return fact;
    }

    /** The oldest evidence is retained when a diagnostic bound is reached. */
    private void append(List<Map<String, Object>> target, int limit, Map<String, Object> fact) {
      if (target.size() < limit) target.add(fact);
      else overflow = true;
    }

    /** Arm after the original baseline snapshot without waiting on any business operation. */
    private synchronized void begin(int level) {
      if (level != 10 || windowBegin != 0)
        throw new IllegalStateException("CM21_PHASE_SINGLE10_REQUIRED");
      activeAtBegin = new ArrayList<>(active.keySet());
      beginUtc = Instant.now().toString();
      windowBegin = System.nanoTime();
    }

    /** End before the original natural drain; subsequent completions remain explicitly retained. */
    private synchronized void end() {
      windowEnd = System.nanoTime();
      endUtc = Instant.now().toString();
      activeAtEnd = new ArrayList<>(active.keySet());
    }

    /** Classify whole intervals without clipping durations into apparently complete calls. */
    private String window(Map<String, Object> fact) {
      long start = (long) fact.get("startNanos"), end = (long) fact.get("endNanos");
      if (end < windowBegin) return "PRE_WINDOW";
      if (start < windowBegin) return end > windowEnd ? "CROSSES_BOTH" : "CROSSES_BEGIN";
      if (start > windowEnd) return "POST_WINDOW";
      return end > windowEnd ? "CROSSES_END" : "COMPLETE_WINDOW";
    }

    /**
     * Export once at the existing finish boundary; concurrent completions cannot mutate the copy.
     */
    private void export(Path directory, int level) {
      try {
        Map<String, Object> output = new LinkedHashMap<>();
        List<Map<String, Object>> callSnapshot, phaseSnapshot;
        synchronized (this) {
          output.put("schema", "cm21-phase-timing-v1");
          if (anchorBridge.enabledForFake()) output.put("anchor", anchorBridge.snapshot());
          output.put("startupUtc", startupUtc);
          output.put("startupNanos", startupNanos);
          output.put("beginUtc", beginUtc);
          output.put("endUtc", endUtc);
          output.put("windowBeginNanos", windowBegin);
          output.put("windowEndNanos", windowEnd);
          output.put("activeAtBegin", activeAtBegin);
          output.put("activeAtEnd", activeAtEnd);
          output.put("activeAtExport", new ArrayList<>(active.keySet()));
          Set<Long> boundaryCalls = new HashSet<>(activeAtBegin);
          boundaryCalls.addAll(activeAtEnd);
          for (Map.Entry<Long, Long> inFlight : active.entrySet())
            if (inFlight.getValue() >= windowBegin && inFlight.getValue() <= windowEnd)
              boundaryCalls.add(inFlight.getKey());
          boundaryCalls.retainAll(active.keySet());
          output.put("unresolvedWindowCallIds", new ArrayList<>(boundaryCalls));
          output.put("unresolvedWindowCallCount", boundaryCalls.size());
          output.put("overflow", overflow);
          output.put("observerFailures", observerFailures.get());
          output.put("callLimit", callLimit);
          output.put("phaseLimit", phaseLimit);
          output.put("dispatchRound", "UNKNOWN_PROXY_NOT_INSTALLED");
          output.put("serviceAdviceInstalled", serviceAdviceInstalled);
          callSnapshot = new ArrayList<>(calls);
          phaseSnapshot = new ArrayList<>(phases);
          boolean incomplete = overflow || observerFailures.get() != 0 || !boundaryCalls.isEmpty();
          output.put("measurementIncomplete", incomplete);
          output.put("complete", !incomplete);
        }
        List<Map<String, Object>> callCopy = new ArrayList<>(), phaseCopy = new ArrayList<>();
        for (Map<String, Object> fact : callSnapshot) {
          Map<String, Object> copy = new LinkedHashMap<>(fact);
          copy.put("window", window(fact));
          callCopy.add(copy);
        }
        for (Map<String, Object> fact : phaseSnapshot) {
          Map<String, Object> copy = new LinkedHashMap<>(fact);
          copy.put("window", window(fact));
          phaseCopy.add(copy);
        }
        output.put("calls", callCopy);
        output.put("phases", phaseCopy);
        output.put(
            "unmeasuredClientResidual",
            "PREPARE_RESULT_READING_CLEANUP_AND_OTHER_CLIENT_WORK_NOT_CPU");
        loadWrite(directory, "phase-timing-" + level + ".json", output);
        exported = true;
      } catch (Throwable observation) {
        observerFailures.incrementAndGet();
        System.err.println("CM21_PHASE_EXPORT_INCOMPLETE");
      }
    }

    /**
     * Preserve bounded observations when an original failure prevents the normal finish boundary.
     */
    private void preserve(Path directory) {
      if (exported) return;
      if (windowEnd == 0) {
        observerFailures.incrementAndGet();
        end();
      }
      export(directory, 10);
    }

    /** Exact prepared text whitelist; runtime SQL is classified in memory and never exported. */
    private static String sqlCategory(String sql) {
      if ("SELECT * FROM product_metric_control WHERE singleton_id=1 FOR UPDATE".equals(sql))
        return "CONTROL_LOCK";
      if ("SELECT * FROM product_metric_control WHERE singleton_id=1".equals(sql))
        return "CONTROL_READ";
      if ("SELECT * FROM product_metric_applied_event WHERE event_id=? FOR UPDATE".equals(sql))
        return "LEDGER_LOCK";
      if ("SELECT * FROM product_online_stat WHERE product_id=? FOR UPDATE".equals(sql))
        return "STAT_LOCK";
      if ("SELECT * FROM product_online_stat WHERE product_id=?".equals(sql)) return "STAT_READ";
      return exactSqlCategory(sql);
    }

    /**
     * Additional immutable source SQL categories are inserted below without runtime source reads.
     */
    private static String exactSqlCategory(String sql) {
      if ("SELECT metric_control.singleton_id,metric_control.series_id,metric_control.revision,metric_control.started_at,metric_control.index_uuid,metric_control.index_ready,metric_control.updated_at,applied.event_id AS applied_event_id,applied.fingerprint AS applied_fingerprint FROM product_metric_control metric_control LEFT JOIN product_metric_applied_event applied ON applied.event_id=? WHERE metric_control.singleton_id=1"
          .equals(sql)) return "INITIAL_APPLY_SNAPSHOT";
      if ("SELECT event_id,fingerprint FROM product_metric_applied_event WHERE event_id=?"
          .equals(sql)) return "LEDGER_IDENTITY_READ";
      if ("INSERT INTO product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at) VALUES(?,0,0,0,0,0,?,?) ON DUPLICATE KEY UPDATE product_id=product_id"
          .equals(sql)) return "STAT_ENSURE";
      if ("UPDATE product_metric_control SET revision=revision+1,updated_at=? WHERE singleton_id=1 AND revision<9223372036854775807"
          .equals(sql)) return "REVISION_UPDATE";
      if ("SELECT COUNT(*) FROM product WHERE id=?".equals(sql)) return "PRODUCT_MAPPING";
      if ("SELECT COUNT(*) FROM product_sku s JOIN product p ON p.id=s.product_id WHERE s.id=? AND p.id=?"
          .equals(sql)) return "PRODUCT_MAPPING";
      if ("INSERT INTO product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at) VALUES(?,0,0,0,0,0,?,?)"
          .equals(sql)) return "STAT_INSERT";
      if ("INSERT INTO product_metric_applied_event(event_id,fingerprint,event_type,product_id,sku_id,quantity,order_no,source_ref,source_revision,occurred_at,applied_at,metric_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
          .equals(sql)) return "LEDGER_INSERT";
      if ("UPDATE product_online_stat SET view_count=?,search_count=?,sold_count=?,hot_score=?,metric_version=?,last_event_id=?,updated_at=? WHERE product_id=?"
          .equals(sql)) return "STAT_UPDATE";
      if ("INSERT INTO product_metric_projection_outbox(product_id,metric_version,created_at,updated_at,next_retry_at) VALUES(?,?,?,?,?)"
          .equals(sql)) return "PROJECTION_INSERT";
      if ("SELECT * FROM product_metric_outbox WHERE status IN ('PENDING','UNKNOWN') AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_retry_at,event_id LIMIT 50 FOR UPDATE SKIP LOCKED"
          .equals(sql)) return "SOURCE_CLAIM";
      if ("UPDATE product_metric_outbox SET lease_owner=?,lease_until=?,updated_at=? WHERE event_id=? AND (lease_until IS NULL OR lease_until<=?)"
          .equals(sql)) return "SOURCE_LEASE";
      if ("UPDATE product_metric_outbox SET status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?"
          .equals(sql)) return "SOURCE_FINISH";
      if ("UPDATE product_metric_outbox SET status='UNKNOWN',attempts=LEAST(2147483647,CAST(attempts AS UNSIGNED)+1),next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),?),lease_owner=NULL,lease_until=NULL,last_error=?,updated_at=? WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?"
          .equals(sql)) return "SOURCE_FINISH";
      if ("SELECT product_id FROM product_metric_projection_outbox WHERE status IN ('PENDING','UNKNOWN') AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?) GROUP BY product_id ORDER BY MIN(next_retry_at),product_id LIMIT 50"
          .equals(sql)) return "PROJECTION_CLAIM";
      if ("SELECT * FROM product_metric_projection_outbox WHERE product_id=? AND status IN ('PENDING','UNKNOWN') AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY metric_version LIMIT 500 FOR UPDATE SKIP LOCKED"
          .equals(sql)) return "PROJECTION_CLAIM";
      if ("UPDATE product_metric_projection_outbox SET lease_owner=?,lease_until=?,updated_at=? WHERE product_id=? AND metric_version=? AND (lease_until IS NULL OR lease_until<=?)"
          .equals(sql)) return "PROJECTION_LEASE";
      if ("UPDATE product_metric_projection_outbox SET status='DONE',lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=? WHERE product_id=? AND metric_version<=? AND BINARY lease_owner=BINARY ? AND lease_until>?"
          .equals(sql)) return "PROJECTION_FINISH";
      if ("UPDATE product_metric_projection_outbox SET status='UNKNOWN',attempts=LEAST(2147483647,CAST(attempts AS UNSIGNED)+1),next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),?),lease_owner=NULL,lease_until=NULL,last_error='PROJECTION_UNCERTAIN',updated_at=? WHERE product_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?"
          .equals(sql)) return "PROJECTION_FINISH";
      return "OTHER";
    }
  }

  /** The frozen refresh object alone decides whether the optional factory exists. */
  static final class StatementEnvelopeCondition implements Condition {
    /** Read no mutable property and allocate no collector, connection or actor. */
    @Override
    public boolean matches(
        ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      Pipeline run = CURRENT.get();
      return run != null && run.statementAdmission.enabled();
    }
  }

  /**
   * Mirror Boot's sole factory only for an explicitly admitted refresh with no custom operations.
   */
  @Configuration(proxyBeanMethods = false)
  @Profile("cm21-pipeline")
  @Conditional(StatementEnvelopeCondition.class)
  @ConditionalOnMissingBean(JdbcOperations.class)
  @EnableConfigurationProperties(JdbcProperties.class)
  static class StatementEnvelopeConfiguration {
    /** Preserve Boot's name, Primary designation, datasource and template-setting order. */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource, JdbcProperties properties) {
      Pipeline run = CURRENT.get();
      if (run == null || run.statementEnvelope == null || !run.statementAdmission.enabled())
        throw new IllegalStateException("CM21_STATEMENT_ENVELOPE_REFRESH_REQUIRED");
      ProductMetricStatementEnvelopeSupport.requireContext(
          run.statementAdmission,
          run.role,
          run.queryInfoAdmission.seeds(),
          run.queryInfoAdmission.mode(),
          run.batchMode,
          run.metricConsumers,
          "true".equals(run.queryInfoAdmission.phaseTiming()),
          "true".equals(run.queryInfoAdmission.phaseAnchor()));
      return ProductMetricStatementEnvelopeSupport.createTemplate(
          dataSource, properties, run.statementEnvelope);
    }
  }

  /** Explicit production-only scan excludes every unrelated test configuration. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Profile("cm21-pipeline")
  @MapperScan(
      basePackages = "com.cloudmall.product.mapper",
      annotationClass = org.apache.ibatis.annotations.Mapper.class)
  @Import({
    StatementEnvelopeConfiguration.class,
    com.cloudmall.product.controller.ProductController.class,
    com.cloudmall.product.service.impl.ProductQueryServiceImpl.class,
    com.cloudmall.product.service.impl.ProductManagementServiceImpl.class,
    com.cloudmall.product.service.impl.CategoryServiceImpl.class,
    com.cloudmall.product.service.impl.SeckillActivityServiceImpl.class,
    com.cloudmall.product.service.impl.ProductCatalogReader.class,
    com.cloudmall.product.util.ProductValueCodec.class,
    ProductSqlMapper.class,
    com.cloudmall.product.mapper.ProductMetricMapper.class,
    com.cloudmall.product.mapper.ProductIndexOutboxMapper.class,
    com.cloudmall.product.mapper.ProductIndexConsumptionMapper.class,
    com.cloudmall.product.mapper.ProductIndexDeadLetterMapper.class,
    ProductMetricService.class,
    ProductMetricConsumer.class,
    ProductMetricOutboxDispatcher.class,
    ProductMetricProjectionDispatcher.class,
    ProductIndexDocumentLoader.class,
    ProductIndexOutboxService.class,
    ProductIndexOutboxDispatcher.class,
    ProductIndexConsumptionService.class,
    ProductIndexConsumer.class,
    ProductIndexRebuildJob.class,
    ProductKeywordSearch.class,
    ElasticsearchProductIndexWriter.class,
    com.cloudmall.product.config.ProductMessagingConfiguration.class,
    com.cloudmall.product.config.ProductMetricMessagingConfiguration.class,
    com.cloudmall.product.config.ProductIndexFeatureConfiguration.class,
    com.cloudmall.product.config.ElasticsearchConfiguration.class,
    com.cloudmall.product.config.ProductReadExceptionHandler.class,
    com.cloudmall.common.config.CommonWebConfiguration.class,
    com.cloudmall.common.web.GlobalExceptionHandler.class
  })
  static class PipelineApplication {
    /** Boot builds its original JDBC template and transaction manager around the owned pool. */
    @Bean
    public DataSource dataSource() {
      Pipeline run = CURRENT.get();
      if (run == null || run.pool == null)
        throw new IllegalStateException("CM21_REFRESH_RESOURCE_REQUIRED");
      return run.phaseTiming == null ? run.pool : run.phaseTiming.dataSource(run.pool);
    }

    /** Observe only the original manual metrics listener's real delivery and channel. */
    @Bean
    public static BeanPostProcessor observedMetricChannel() {
      Pipeline run = CURRENT.get();
      if (run == null) throw new IllegalStateException("CM21_REFRESH_OBSERVER_REQUIRED");
      int generation = run.contextGeneration.incrementAndGet();
      if (run.batchRedelivery != null) {
        run.batchRedeliveryGeneration = run.batchRedelivery.generation(generation);
        run.batchObserver =
            new ProductMetricPipelineBatchSupport.CommitObserver(
                new ProductMetricPipelineBatchSupport.JdbcSnapshot(
                    run.product.dataSource, run.product.database),
                (name, value) -> run.audit("batch-g" + generation + "-" + name, value),
                run.batchRedeliveryGeneration::afterCommitted);
        run.batchObservers.put(generation, run.batchObserver);
      }
      return new BeanPostProcessor() {
        /** The real consumer commits first; only its final ACK can be interrupted. */
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (run.applicationTiming != null
              && bean instanceof com.cloudmall.product.mapper.ProductMetricMapper)
            return run.applicationTiming.installMapper(bean);
          if (run.loadSupport != null && bean instanceof ProductMetricService) {
            try {
              run.loadSupport.install(bean);
            } catch (Exception invalidProxy) {
              throw new IllegalStateException(
                  "CM21_SINGLE10_ORIGINAL_TX_PROXY_REQUIRED", invalidProxy);
            }
          }
          if (run.batchRedelivery != null && bean instanceof ProductMetricConsumer)
            return run.batchRedeliveryGeneration.wrapConsumer(bean);
          if (run.phaseTiming != null && bean instanceof ProductMetricService) {
            if (!(bean instanceof org.springframework.aop.framework.Advised advised))
              throw new IllegalStateException("CM21_PHASE_ORIGINAL_TX_PROXY_REQUIRED");
            advised.addAdvice(0, run.phaseTiming.serviceAdvice());
            run.phaseTiming.serviceAdviceInstalled = true;
          }
          if (run.batchMode && run.loadSupport == null && bean instanceof ProductMetricService) {
            try {
              run.batchObserver.install(bean);
            } catch (Exception invalidProxy) {
              throw new IllegalStateException("CM21_BATCH_ORIGINAL_TX_PROXY_REQUIRED");
            }
          }
          if (run.loadSupport != null
              && bean instanceof SimpleRabbitListenerContainerFactory factory
              && "productMetricContainerFactory".equals(name)) {
            if (run.callbackServiceObservation != null)
              run.callbackServiceObservation.installFactory(factory);
            return bean;
          }
          if (ProductMetricPipelineBatchSupport.preserveFactory(run.batchMode, bean, name))
            return bean;
          if (bean instanceof SimpleRabbitListenerContainerFactory factory
              && "productMetricContainerFactory".equals(name)) {
            factory.setContainerCustomizer(
                container -> {
                  container.setShutdownTimeout(15000L);
                  Object original = container.getMessageListener();
                  if (!(original instanceof ChannelAwareMessageListener listener))
                    throw new IllegalStateException("CM21_ORIGINAL_CHANNEL_LISTENER_REQUIRED");
                  run.metricContainer = container;
                  if (run.metricConsumers == 2) {
                    if (!run.consumerIsolationAdmitted)
                      throw new IllegalStateException("CM21_TWO_CONSUMERS_NOT_OWNED");
                    container.setMaxConcurrentConsumers(2);
                    container.setConcurrentConsumers(2);
                  }
                  // Load uses the original production listener and channel without fault
                  // observation.
                  if ("load".equals(run.role)) return;
                  if (run.metricConsumers == 2 && "redelivery".equals(run.role)) {
                    container.setupMessageListener(
                        (ChannelAwareMessageListener)
                            (message, channel) ->
                                run.observeDualDelivery(message, channel, listener, generation));
                    return;
                  }
                  container.setupMessageListener(
                      (ChannelAwareMessageListener)
                          (message, channel) -> {
                            var event = ProductMetricEvents.parse(message.getBody());
                            if (Objects.equals(run.faultEventId, event.eventId)
                                && generation > run.faultGeneration
                                && Boolean.TRUE.equals(
                                    message.getMessageProperties().isRedelivered())) {
                              run.audit(
                                  "actual-redelivery",
                                  Map.of(
                                      "eventId",
                                      event.eventId,
                                      "bodySha256",
                                      ProductMetricEvents.sha256(message.getBody()),
                                      "deliveryTag",
                                      message.getMessageProperties().getDeliveryTag(),
                                      "faultGeneration",
                                      run.faultGeneration,
                                      "contextGeneration",
                                      generation,
                                      "redelivered",
                                      true));
                              run.redelivered.set(true);
                            }
                            Channel observed =
                                (Channel)
                                    java.lang.reflect.Proxy.newProxyInstance(
                                        ProductOnlineMetricsPipelineIT.class.getClassLoader(),
                                        new Class<?>[] {Channel.class},
                                        (proxy, method, arguments) -> {
                                          if ("basicAck".equals(method.getName())
                                              && run.ackFault.get()
                                              && "redelivery".equals(run.role)
                                              && event.productId == 42L
                                              && "PRODUCT_VIEW_CONFIRMED".equals(event.eventType)
                                              && run.ackFault.compareAndSet(true, false)) {
                                            if (TransactionSynchronizationManager
                                                    .isActualTransactionActive()
                                                || TransactionSynchronizationManager
                                                    .isSynchronizationActive())
                                              throw new IllegalStateException(
                                                  "CM21_ACK_STILL_TRANSACTIONAL");
                                            assertEquals(
                                                1L,
                                                run.product.jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                        + " product_metric_applied_event WHERE"
                                                        + " event_id=?",
                                                    Long.class,
                                                    event.eventId));
                                            run.faultEventId = event.eventId;
                                            run.faultGeneration = generation;
                                            run.audit(
                                                "ack-fault-boundary",
                                                Map.of(
                                                    "eventId",
                                                    event.eventId,
                                                    "bodySha256",
                                                    ProductMetricEvents.sha256(message.getBody()),
                                                    "deliveryTag",
                                                    message.getMessageProperties().getDeliveryTag(),
                                                    "contextGeneration",
                                                    generation,
                                                    "actualTx",
                                                    false,
                                                    "synchronization",
                                                    false,
                                                    "independentLedgerCount",
                                                    1));
                                            container.stop(() -> {});
                                            channel.close();
                                            run.ackClosed.set(true);
                                            throw new IOException(
                                                "CM21_OWNED_CHANNEL_CLOSED_BEFORE_ACK");
                                          }
                                          try {
                                            return method.invoke(channel, arguments);
                                          } catch (InvocationTargetException invocation) {
                                            throw invocation.getCause();
                                          }
                                        });
                            listener.onMessage(message, observed);
                          });
                });
          }
          return bean;
        }
      };
    }
  }

  /** Only exact explicit test values1/2 are accepted; production configuration never reads it. */
  private static int metricConsumerCount() {
    String value = System.getProperty("cloudmall.cm21.metric-consumers", "1");
    if (!"1".equals(value) && !"2".equals(value))
      throw new IllegalArgumentException("CM21_METRIC_CONSUMERS_INVALID");
    return Integer.parseInt(value);
  }

  /** Resolve only explicit fixture inputs; a missing value never selects shared defaults. */
  private static String required(String name) {
    String value = System.getProperty("cloudmall.cm21." + name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("CM21_EXPLICIT_PROPERTY_REQUIRED_" + name.replace('.', '_'));
    return value;
  }

  /** Validate an explicit owned middleware endpoint before any client can use it. */
  private static int port(String name) {
    int value = Integer.parseInt(required(name));
    if (value < 1024 || value > 65535) throw new IllegalArgumentException("CM21_PORT_INVALID");
    return value;
  }

  /** Ask the operating system for an available loopback application port. */
  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress("127.0.0.1", 0));
      return socket.getLocalPort();
    }
  }

  /** Fail before launch if an application/management port gained any wildcard listener. */
  private static void assertPortUnbound(int endpoint) throws IOException {
    for (String address : List.of("0.0.0.0", "::")) {
      try (ServerSocket probe = new ServerSocket()) {
        probe.setReuseAddress(false);
        probe.bind(new InetSocketAddress(InetAddress.getByName(address), endpoint));
      }
    }
  }

  /** Observe only an exact loopback fixture endpoint, never scan or contact shared services. */
  private static boolean listening(int endpoint) {
    if (endpoint == 0) return false;
    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress("127.0.0.1", endpoint), 100);
      return true;
    } catch (IOException absent) {
      return false;
    }
  }

  /** Reserve-check an unused Nacos HTTP/gRPC tuple, including both wildcard address families. */
  private static int nacosPorts() {
    for (int attempt = 0; attempt < 100; attempt++) {
      int base = ThreadLocalRandom.current().nextInt(35000, 50000);
      try {
        assertNacosPortsFree(base);
        return base;
      } catch (IOException occupied) {
        // Only an entirely unused tuple can be selected; no existing listener is contacted.
      }
    }
    throw new IllegalStateException("CM21_UNUSED_NACOS_TUPLE_REQUIRED");
  }

  /** Recheck just before launch; closing these probes does not claim SDK retries are disabled. */
  private static void assertNacosPortsFree(int base) throws IOException {
    if (base < 1024 || base + 1001 > 65535)
      throw new IllegalArgumentException("CM21_NACOS_TUPLE_INVALID");
    for (String address : List.of("0.0.0.0", "::")) {
      List<ServerSocket> probes = new ArrayList<>();
      try {
        for (int offset : List.of(0, 1000, 1001)) {
          ServerSocket probe = new ServerSocket();
          probes.add(probe);
          probe.setReuseAddress(false);
          probe.bind(new InetSocketAddress(InetAddress.getByName(address), base + offset));
        }
      } finally {
        IOException closeFailure = null;
        for (ServerSocket probe : probes) {
          try {
            probe.close();
          } catch (IOException failure) {
            if (closeFailure == null) closeFailure = failure;
            else closeFailure.addSuppressed(failure);
          }
        }
        if (closeFailure != null) throw closeFailure;
      }
    }
  }

  /** A real observation may fail with its original checked exception instead of being hidden. */
  @FunctionalInterface
  private interface Observation {
    boolean complete() throws Exception;
  }

  /** Poll a finite live condition without changing database due-times or production state. */
  private static void await(Observation observation, int seconds, String category)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (observation.complete()) return;
      Thread.sleep(100);
    }
    throw new IllegalStateException("CM21_TIMEOUT_" + category);
  }
}
