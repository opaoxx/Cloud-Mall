package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.ProductMetricControlPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.Channel;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seven bounded genuine-MySQL trajectories; no broker, ES, Redis or HTTP listener is started. */
@TestMethodOrder(OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class LateApplyGateMySqlIT {
  /**
   * One bounded seven-method execution may delegate no more than the reviewed apply attempt budget.
   */
  private static final AtomicLong APPLY_CALLS = new AtomicLong();

  /** Transport delivery count remains separate from genuine application attempt count. */
  private static final AtomicLong CONSUME_CALLS = new AtomicLong();

  /** No more than the reviewed table-free connection identity observations may be issued. */
  private static final AtomicLong IDENTITY_QUERIES = new AtomicLong();

  /** Additional actual public service invocations retain their independent finite budget. */
  private static final AtomicLong OTHER_SERVICE_CALLS = new AtomicLong();

  /**
   * Unsafe actor/source closure stops later fixed trajectories instead of extending an unknown run.
   */
  private static final AtomicBoolean SAFETY_STOP = new AtomicBoolean();

  /**
   * JUnit creates one test instance per method, sharing this wait-query cap across subscenarios.
   */
  private final AtomicLong methodWaitQueries = new AtomicLong();

  /**
   * Exact per-method attempt count prevents unexpected retries borrowing another trajectory's
   * quota.
   */
  private final AtomicLong methodApplyCalls = new AtomicLong();

  /** Exact per-method delivery count is separate from the retry count within each delivery. */
  private final AtomicLong methodConsumeCalls = new AtomicLong();

  /** Reviewed maximum actual application attempts for this fixed method. */
  private int methodApplyLimit;

  /** Reviewed maximum transport-stub deliveries for this fixed method. */
  private int methodConsumeLimit;

  /** Only the added snapshot method may consume its separately bounded identity allowance. */
  private boolean initialSnapshotMethod;

  /** Identity probes in the added method cannot borrow unused quota from the original six. */
  private final AtomicLong initialSnapshotIdentityQueries = new AtomicLong();

  @BeforeEach
  void setFixedMethodBudget(TestInfo test) {
    Assumptions.assumeFalse(SAFETY_STOP.get(), "previous actor/source safety gate unresolved");
    String method = test.getTestMethod().orElseThrow().getName();
    initialSnapshotMethod =
        method.equals("initialJoinedSnapshotRetainsRepeatableReadAndControlPriority");
    methodApplyLimit =
        switch (method) {
          case "differentProductsReachLateGateWithoutMissingLedgerGapCycle" -> 4;
          case "firstAggregateSerializesTwoEventsForOneProduct" -> 2;
          case "duplicateAndCrossProductSaleConflictsRollbackBeforeRetry" -> 6;
          case "staleLedgerSnapshotRechecksBeforePermanentFailure" -> 7;
          case "uniqueVersionAndProjectionAdvanceFailuresRollbackWholeApply" -> 5;
          case "lateApplyGateKeepsReadinessContentQuarantineAndCutsConsistent" -> 3;
          case "initialJoinedSnapshotRetainsRepeatableReadAndControlPriority" -> 5;
          default -> throw new IllegalStateException("UNREVIEWED_LATE_GATE_METHOD");
        };
    methodConsumeLimit =
        initialSnapshotMethod
            ? 0
            : methodApplyLimit == 6 ? 4 : methodApplyLimit == 7 ? 2 : methodApplyLimit == 5 ? 1 : 0;
  }

  @Test
  @Order(1)
  void differentProductsReachLateGateWithoutMissingLedgerGapCycle() throws Exception {
    for (boolean existing : List.of(false, true)) {
      try (Run run = new Run("different-products-" + existing)) {
        if (existing) {
          run.seedStat(41, 0, 0);
          run.seedStat(42, 0, 0);
        }
        CountDownLatch atGate = run.latch(2), release = run.latch(1);
        run.hook =
            new Hook() {
              /** Pause only the declared actor immediately before its actual mapper call. */
              @Override
              public void before(String actor, String method, Object[] arguments) throws Exception {
                if ((actor.equals("first") || actor.equals("second")) && gate(method, arguments)) {
                  atGate.countDown();
                  await(release);
                }
              }
            };
        Future<String> first = run.apply("first", event(41));
        Future<String> second = run.apply("second", event(42));
        try {
          await(atGate);
          assertEquals(0, run.database.count("product_metric_applied_event"));
          assertEquals(0, run.database.count("product_metric_projection_outbox"));
          assertEquals(existing ? 2 : 0, run.database.count("product_online_stat"));
          assertEquals(0, run.revision());
          run.database.audit(
              "uncommitted-independent-facts",
              Map.of("applied", 0, "projection", 0, "stat", existing ? 2 : 0, "revision", 0));
        } finally {
          release.countDown();
        }
        assertEquals("APPLIED", first.get(3, TimeUnit.SECONDS));
        assertEquals("APPLIED", second.get(3, TimeUnit.SECONDS));
        run.assertApplied(41, 1, 1);
        run.assertApplied(42, 1, 1);
        assertEquals(2, run.revision());
        assertEquals(2, run.database.count("product_metric_applied_event"));
        assertEquals(2, run.database.count("product_metric_projection_outbox"));
        run.success();
      }
    }
  }

  @Test
  @Order(2)
  void firstAggregateSerializesTwoEventsForOneProduct() throws Exception {
    try (Run run = new Run("first-aggregate")) {
      CountDownLatch firstAnchored = run.latch(1), release = run.latch(1);
      run.hook =
          new Hook() {
            /** Observe the declared completed SQL stage before releasing another actor. */
            @Override
            public void after(String actor, String method, Object[] arguments, Object result)
                throws Exception {
              if (actor.equals("first") && method.equals("ensureStat")) {
                firstAnchored.countDown();
                await(release);
              }
            }
          };
      Future<String> first = run.apply("first", event(41));
      await(firstAnchored);
      Future<String> second = run.apply("second", event(41));
      try {
        run.actualWait("second", "product_online_stat", "first");
      } finally {
        release.countDown();
      }
      assertEquals("APPLIED", first.get(3, TimeUnit.SECONDS));
      assertEquals("APPLIED", second.get(3, TimeUnit.SECONDS));
      run.assertApplied(41, 2, 2);
      assertEquals(1, run.database.count("product_online_stat"));
      assertEquals(
          List.of(1L, 2L),
          run.database.jdbc.queryForList(
              "SELECT metric_version FROM product_metric_applied_event ORDER BY metric_version",
              Long.class));
      assertEquals(2, run.revision());
      run.success();
    }
  }

  @Test
  @Order(3)
  void duplicateAndCrossProductSaleConflictsRollbackBeforeRetry() throws Exception {
    try (Run run = new Run("duplicate-retry")) {
      ProductMetricEventDTO event = event(41);
      CountDownLatch atGate = run.latch(1), release = run.latch(1);
      run.hook = pauseBeforeGate("winner", atGate, release);
      Future<Void> winner = run.consume("winner", event);
      await(atGate);
      Future<Void> loser = run.consume("loser", event);
      try {
        run.actualWait("loser", "product_online_stat", "winner");
      } finally {
        release.countDown();
      }
      assertNull(winner.get(3, TimeUnit.SECONDS));
      loser.get(3, TimeUnit.SECONDS);
      run.assertSqlFailure("loser", 1062, "PRIMARY");
      run.assertRetryThenCommit("loser");
      run.assertApplied(41, 1, 1);
      assertEquals(1, run.revision());
      assertEquals(2, run.acks.size());
      run.success();
    }
    try (Run run = new Run("cross-product-sale")) {
      ProductMetricEventDTO winnerEvent = sale(41);
      ProductMetricEventDTO loserEvent =
          ProductMetricEvents.parse(ProductMetricEvents.bytes(winnerEvent));
      loserEvent.productId = 42L;
      ProductMetricEvents.validate(loserEvent);
      CountDownLatch winnerMapped = run.latch(1), releaseWinner = run.latch(1);
      CountDownLatch loserPrepared = run.latch(1), releaseLoser = run.latch(1);
      AtomicBoolean loserZeroObserved = new AtomicBoolean();
      run.hook =
          new Hook() {
            /** Observe the declared completed SQL stage before releasing another actor. */
            @Override
            public void after(String actor, String method, Object[] arguments, Object result)
                throws Exception {
              if (actor.equals("winner") && method.equals("initialApplySnapshot")) {
                ProductMetricInitialApplySnapshotDTO snapshot =
                    (ProductMetricInitialApplySnapshotDTO) result;
                assertNotNull(snapshot);
                assertNotNull(snapshot.control);
                assertEquals(ProductMetricEvents.SERIES, snapshot.control.seriesId);
                assertTrue(snapshot.mappingPresent);
                assertNull(snapshot.appliedEventId);
                assertNull(snapshot.appliedFingerprint);
                winnerMapped.countDown();
                await(releaseWinner);
              }
            }

            /** Observe only the declared actor before its real SQL delegation. */
            @Override
            public void before(String actor, String method, Object[] arguments) throws Exception {
              if (actor.equals("loser") && method.equals("insertApplied")) {
                assertEquals(
                    0,
                    run.database.scalar(
                        "SELECT metric_version FROM product_online_stat WHERE product_id=42"));
                loserZeroObserved.set(true);
                loserPrepared.countDown();
                await(releaseLoser);
              }
            }
          };
      Future<Void> winner = run.consume("winner", winnerEvent);
      await(winnerMapped);
      run.database.audit("declared-sku-rebind-before", run.database.tableFacts("product_sku"));
      assertEquals(
          1,
          run.database.jdbc.update(
              "UPDATE product_sku SET product_id=42 WHERE id=71 AND product_id=41"));
      run.database.audit("declared-sku-rebind-after", run.database.tableFacts("product_sku"));
      Future<Void> loser = run.consume("loser", loserEvent);
      try {
        await(loserPrepared);
        assertTrue(loserZeroObserved.get());
        assertEquals(
            0, run.database.scalar("SELECT COUNT(*) FROM product_online_stat WHERE product_id=42"));
        releaseWinner.countDown();
        assertNull(winner.get(3, TimeUnit.SECONDS));
      } finally {
        releaseWinner.countDown();
        releaseLoser.countDown();
      }
      loser.get(3, TimeUnit.SECONDS);
      run.assertSqlFailure("loser", 1062, "PRIMARY");
      assertEquals(
          0, run.database.scalar("SELECT COUNT(*) FROM product_online_stat WHERE product_id=42"));
      assertEquals(1, run.database.count("product_metric_applied_event"));
      assertEquals(1, run.database.count("product_metric_projection_outbox"));
      assertEquals(
          2, run.database.scalar("SELECT sold_count FROM product_online_stat WHERE product_id=41"));
      assertEquals(
          20, run.database.scalar("SELECT hot_score FROM product_online_stat WHERE product_id=41"));
      assertEquals(1, run.database.count("product_metric_quarantine"));
      assertEquals(
          "EVENT_FINGERPRINT_CONFLICT",
          run.database.jdbc.queryForObject(
              "SELECT reason FROM product_metric_quarantine", String.class));
      assertEquals(2, run.acks.size());
      assertEquals(2, run.revision());
      assertTrue(run.hasCompletion("loser", TransactionSynchronization.STATUS_ROLLED_BACK));
      run.success();
    }
  }

  @Test
  @Order(4)
  void staleLedgerSnapshotRechecksBeforePermanentFailure() throws Exception {
    for (boolean versionBoundary : List.of(false, true)) {
      try (Run run = new Run("stale-max-" + versionBoundary)) {
        run.seedStat(
            41, versionBoundary ? 0 : Long.MAX_VALUE - 1, versionBoundary ? Long.MAX_VALUE - 1 : 0);
        ProductMetricEventDTO event = event(41);
        CountDownLatch atGate = run.latch(1), release = run.latch(1);
        run.hook = pauseBeforeGate("winner", atGate, release);
        Future<String> winner = run.apply("winner", event);
        await(atGate);
        Future<Void> loser = run.consume("loser", event);
        try {
          run.actualWait("loser", "product_online_stat", "winner");
        } finally {
          release.countDown();
        }
        assertEquals("APPLIED", winner.get(3, TimeUnit.SECONDS));
        loser.get(3, TimeUnit.SECONDS);
        assertTrue(run.saw("loser", "findAppliedIdentity", "true"));
        run.assertRetryThenCommit("loser");
        assertEquals(1, run.database.count("product_metric_applied_event"));
        assertEquals(1, run.database.count("product_metric_projection_outbox"));
        assertEquals(0, run.database.count("product_metric_quarantine"));
        assertEquals(1, run.revision());
        assertEquals(
            versionBoundary ? Long.MAX_VALUE : 1,
            run.database.scalar(
                "SELECT metric_version FROM product_online_stat WHERE product_id=41"));
        assertEquals(
            versionBoundary ? 1 : Long.MAX_VALUE,
            run.database.scalar("SELECT view_count FROM product_online_stat WHERE product_id=41"));
        assertEquals(1, run.acks.size());
        run.success();
      }
    }
    try (Run run = new Run("new-overflow")) {
      run.seedStat(41, Long.MAX_VALUE, 0);
      Map<String, Object> before = run.database.allFacts();
      ProductMetricEvents.InvalidEvent failure =
          assertThrows(
              ProductMetricEvents.InvalidEvent.class, () -> run.direct("overflow", event(41)));
      assertEquals("COUNTER_OVERFLOW", failure.reason());
      assertEquals(before, run.database.allFacts());
      run.success();
    }
  }

  @Test
  @Order(5)
  void uniqueVersionAndProjectionAdvanceFailuresRollbackWholeApply() throws Exception {
    try (Run run = new Run("unique-version")) {
      run.seedStat(41, 0, 0);
      ProductMetricEventDTO existing = event(41);
      run.mapper.insertApplied(existing, ProductMetricEvents.fingerprint(existing), 1, run.time());
      run.database.audit("declared-inconsistent-ledger-and-stat", run.database.allFacts());
      Map<String, Object> before = run.database.allFacts();
      run.consume("version-conflict", event(41)).get(3, TimeUnit.SECONDS);
      run.assertSqlFailure("version-conflict", 1062, "uk_metric_applied_version");
      assertEquals(3, run.sqlFailureCount("version-conflict", 1062));
      assertFalse(
          run.hasCompletion("version-conflict", TransactionSynchronization.STATUS_COMMITTED));
      assertEquals(1, run.acks.size());
      assertEquals(before, run.database.allFacts());
      run.success();
    }
    try (Run run = new Run("projection-failure")) {
      run.database.trigger(
          "cm21_late_gate_projection",
          "product_metric_projection_outbox",
          "CM21_LATE_GATE_PROJECTION");
      Map<String, Object> before = run.database.allFacts();
      DataAccessException failure =
          assertThrows(DataAccessException.class, () -> run.direct("projection-fault", event(41)));
      SQLException original = assertSql(failure, 1644, "CM21_LATE_GATE_PROJECTION");
      assertEquals("45000", original.getSQLState());
      assertEquals(before, run.database.allFacts());
      run.database.dropTrigger("cm21_late_gate_projection");
      run.success();
    }
    try (Run run = new Run("advance-failure")) {
      String trigger = "cm21_late_gate_advance";
      String ddl =
          "CREATE TRIGGER "
              + trigger
              + " BEFORE UPDATE ON product_metric_control FOR EACH ROW SIGNAL SQLSTATE '45000' SET"
              + " MYSQL_ERRNO=1644,MESSAGE_TEXT='CM21_LATE_GATE_ADVANCE'";
      run.database.audit("trigger-plan-" + trigger, Map.of("ddl", ddl, "trigger", trigger));
      run.database.jdbc.execute(ddl);
      assertTrue(run.database.triggers.add(trigger));
      Map<String, Object> before = run.database.allFacts();
      DataAccessException failure =
          assertThrows(DataAccessException.class, () -> run.direct("advance-fault", event(41)));
      SQLException original = assertSql(failure, 1644, "CM21_LATE_GATE_ADVANCE");
      assertEquals("45000", original.getSQLState());
      assertEquals(before, run.database.allFacts());
      run.database.dropTrigger(trigger);
      run.success();
    }
  }

  @Test
  @Order(6)
  void lateApplyGateKeepsReadinessContentQuarantineAndCutsConsistent() throws Exception {
    try (Run run = new Run("readiness-and-cut")) {
      run.seedReady();
      ProductMetricService.Cut before = run.service.cut();
      assertTrue(before.admissible());
      CountDownLatch applyLocked = run.latch(1), releaseApply = run.latch(1);
      run.hook =
          new Hook() {
            /** Retain the declared lock-owning stage until its peer observation completes. */
            @Override
            public void after(String actor, String method, Object[] arguments, Object result)
                throws Exception {
              if (actor.equals("apply-readiness") && gate(method, arguments)) {
                applyLocked.countDown();
                await(releaseApply);
              }
            }
          };
      Future<String> firstApply = run.apply("apply-readiness", event(41));
      await(applyLocked);
      assertEquals(0, run.database.count("product_metric_applied_event"));
      assertEquals(0, run.database.count("product_metric_projection_outbox"));
      assertEquals(before.revision(), run.revision());
      Future<Map<String, Object>> negative =
          run.submit(
              "negative-readiness",
              () ->
                  run.transaction()
                      .execute(
                          status -> {
                            boolean earlyPending = run.mapper.pending();
                            assertFalse(earlyPending);
                            run.mapper.control(true);
                            boolean stalePending = run.mapper.pending();
                            assertFalse(stalePending);
                            assertEquals(1, run.mapper.readiness("late-gate-index", true));
                            return Map.of(
                                "earlyPending",
                                earlyPending,
                                "pendingAfterGate",
                                stalePending,
                                "declaredWrongReadyCommitted",
                                true);
                          }));
      try {
        run.actualWait("negative-readiness", "product_metric_control", "apply-readiness");
      } finally {
        releaseApply.countDown();
      }
      assertEquals("APPLIED", firstApply.get(3, TimeUnit.SECONDS));
      run.database.audit("negative-stale-readiness-contract", negative.get(3, TimeUnit.SECONDS));
      assertTrue(run.service.cut().ready());
      assertTrue(run.service.cut().pending());
      assertFalse(run.service.cut().admissible());
      assertFalse(run.service.admit(before, List.of()));
      assertFalse(
          run.submit("readiness", () -> run.service.readiness("late-gate-index", true))
              .get(3, TimeUnit.SECONDS));
      assertFalse(run.service.cut().ready());
      run.assertOrder("readiness", "control", "pending");

      CountDownLatch applyPrepared = run.latch(1), letApplyGate = run.latch(1);
      CountDownLatch contentLocked = run.latch(1), releaseContent = run.latch(1);
      run.hook =
          new Hook() {
            /** Pause only the declared lane before the actual control lock request. */
            @Override
            public void before(String actor, String method, Object[] arguments) throws Exception {
              if (actor.equals("apply-content") && gate(method, arguments)) {
                applyPrepared.countDown();
                await(letApplyGate);
              }
            }

            /** Retain the declared lock-owning stage until its peer observation completes. */
            @Override
            public void after(String actor, String method, Object[] arguments, Object result)
                throws Exception {
              if (actor.equals("content") && gate(method, arguments)) {
                contentLocked.countDown();
                await(releaseContent);
              }
            }
          };
      long beforeContent = run.revision();
      Future<String> contentApply = run.apply("apply-content", event(41));
      await(applyPrepared);
      Future<Void> content =
          run.submit(
              "content",
              () -> {
                AuthContext.set(9L, "ADMIN");
                try {
                  ProductDTO update = new ProductDTO();
                  update.categoryId = 3L;
                  update.name = "Late gate content changed";
                  update.price = "12.00";
                  update.skus = List.of();
                  update.parameters = List.of();
                  run.context.getBean(ProductController.class).update(41L, update);
                } finally {
                  AuthContext.clear();
                }
                return null;
              });
      await(contentLocked);
      try {
        assertEquals(1, run.database.count("product_metric_applied_event"));
        assertEquals(beforeContent, run.revision());
        assertEquals(
            "Metric product 41",
            run.database.jdbc.queryForObject("SELECT name FROM product WHERE id=41", String.class));
        letApplyGate.countDown();
        run.actualWait("apply-content", "product_metric_control", "content");
      } finally {
        letApplyGate.countDown();
        releaseContent.countDown();
      }
      content.get(3, TimeUnit.SECONDS);
      assertEquals("APPLIED", contentApply.get(3, TimeUnit.SECONDS));
      assertEquals(beforeContent + 2, run.revision());
      assertEquals(
          "Late gate content changed",
          run.database.jdbc.queryForObject("SELECT name FROM product WHERE id=41", String.class));
      assertEquals(1, run.database.count("product_index_outbox"));

      CountDownLatch finalApplyLocked = run.latch(1), releaseFinalApply = run.latch(1);
      run.hook =
          new Hook() {
            /** Retain the declared lock-owning stage until its peer observation completes. */
            @Override
            public void after(String actor, String method, Object[] arguments, Object result)
                throws Exception {
              if (actor.equals("apply-quarantine") && gate(method, arguments)) {
                finalApplyLocked.countDown();
                await(releaseFinalApply);
              }
            }
          };
      long beforeQuarantine = run.revision();
      Future<String> quarantineApply = run.apply("apply-quarantine", event(43));
      await(finalApplyLocked);
      Future<Void> quarantine =
          run.submit(
              "quarantine",
              () -> {
                run.service.quarantine(
                    "late-gate-owned",
                    "declared fixture bytes".getBytes(StandardCharsets.UTF_8),
                    null,
                    "DECLARED_LATE_GATE_CHECK");
                return null;
              });
      try {
        assertEquals(2, run.database.count("product_metric_applied_event"));
        assertEquals(beforeQuarantine, run.revision());
        assertEquals(0, run.database.count("product_metric_quarantine"));
        run.actualWait("quarantine", "product_metric_control", "apply-quarantine");
      } finally {
        releaseFinalApply.countDown();
      }
      assertEquals("APPLIED", quarantineApply.get(3, TimeUnit.SECONDS));
      quarantine.get(3, TimeUnit.SECONDS);
      assertEquals(beforeQuarantine + 2, run.revision());
      assertEquals(3, run.database.count("product_metric_applied_event"));
      assertEquals(3, run.database.count("product_metric_projection_outbox"));
      assertEquals(1, run.database.count("product_metric_quarantine"));
      assertFalse(run.service.admit(before, List.of()));
      run.success();
    }
  }

  @Test
  @Order(7)
  void initialJoinedSnapshotRetainsRepeatableReadAndControlPriority() throws Exception {
    try (Run run = new Run("initial-joined-snapshot")) {
      ProductMetricEventDTO event = event(41);
      AtomicLong explicitJoins = new AtomicLong();
      TransactionTemplate snapshotTransaction = run.transaction();
      snapshotTransaction.setReadOnly(true);
      snapshotTransaction.execute(
          status -> {
            ProductMetricInitialApplySnapshotDTO first =
                run.mapper.initialApplySnapshot(event.eventId, event.productId, event.skuId);
            explicitJoins.incrementAndGet();
            assertNotNull(first);
            assertNotNull(first.control);
            assertEquals(1, first.control.singletonId);
            assertEquals(ProductMetricEvents.SERIES, first.control.seriesId);
            assertEquals(0L, first.control.revision);
            assertNotNull(first.control.startedAt);
            assertNull(first.control.indexUuid);
            assertEquals(0, first.control.indexReady);
            assertNotNull(first.control.updatedAt);
            assertNull(first.appliedEventId);
            assertNull(first.appliedFingerprint);
            assertTrue(first.mappingPresent);
            try {
              run.database.audit("initial-joined-first-control", initialJoinedSnapshotFacts(first));
              assertEquals("APPLIED", run.apply("joined-winner", event).get(1, TimeUnit.SECONDS));
            } catch (Exception failure) {
              throw new IllegalStateException("INITIAL_JOINED_SNAPSHOT_ACTOR_FAILED", failure);
            }
            ProductMetricInitialApplySnapshotDTO repeated =
                run.mapper.initialApplySnapshot(event.eventId, event.productId, event.skuId);
            explicitJoins.incrementAndGet();
            assertEquals(initialJoinedSnapshotFacts(first), initialJoinedSnapshotFacts(repeated));
            run.record(
                "joined-snapshot",
                "REPEATED_RR_READ",
                "initialApplySnapshot",
                initialJoinedSnapshotFacts(repeated));
            assertNull(repeated.appliedEventId);
            assertNull(repeated.appliedFingerprint);
            return null;
          });
      ProductMetricInitialApplySnapshotDTO fresh =
          run.transaction()
              .execute(
                  status -> {
                    explicitJoins.incrementAndGet();
                    return run.mapper.initialApplySnapshot(
                        event.eventId, event.productId, event.skuId);
                  });
      assertNotNull(fresh);
      assertEquals(1L, fresh.control.revision);
      assertEquals(event.eventId, fresh.appliedEventId);
      assertEquals(ProductMetricEvents.fingerprint(event), fresh.appliedFingerprint);
      assertTrue(fresh.mappingPresent);
      assertEquals(3L, explicitJoins.get());
      run.database.audit("initial-joined-fresh-control", initialJoinedSnapshotFacts(fresh));
      Map<String, Object> unchanged = run.initialJoinedBusinessFacts();
      assertEquals("DUPLICATE", run.direct("joined-duplicate", event));
      assertEquals(unchanged, run.initialJoinedBusinessFacts());
      ProductMetricControlPO restoredControl = fresh.control;
      Map<String, Object> controlBaseline = run.database.tableFacts("product_metric_control");
      AtomicLong fixtureChanges = new AtomicLong();
      for (String badControl : List.of("uppercase", "trailing-space", "missing")) {
        if (badControl.equals("missing")) {
          assertEquals(
              1,
              run.database.jdbc.update(
                  "DELETE FROM product_metric_control WHERE singleton_id=?",
                  restoredControl.singletonId));
        } else {
          String series =
              badControl.equals("uppercase")
                  ? ProductMetricEvents.SERIES.toUpperCase(java.util.Locale.ROOT)
                  : ProductMetricEvents.SERIES + " ";
          assertEquals(
              1,
              run.database.jdbc.update(
                  "UPDATE product_metric_control SET series_id=?,revision=? WHERE singleton_id=?",
                  series,
                  badControl.equals("uppercase") ? Long.MAX_VALUE : restoredControl.revision,
                  restoredControl.singletonId));
        }
        fixtureChanges.incrementAndGet();
        run.database.audit(
            "initial-joined-declared-" + badControl,
            run.database.tableFacts("product_metric_control"));
        IllegalStateException failure =
            assertThrows(
                IllegalStateException.class,
                () -> run.direct("joined-invalid-" + badControl, event));
        assertEquals("METRIC_CONTROL_INVALID", failure.getMessage());
        run.database.audit(
            "initial-joined-error-" + badControl,
            Map.of("type", failure.getClass().getName(), "message", failure.getMessage()));
        assertEquals(unchanged, run.initialJoinedBusinessFacts());
        if (badControl.equals("missing")) {
          assertEquals(
              1,
              run.database.jdbc.update(
                  "INSERT INTO product_metric_control(singleton_id,series_id,revision,started_at,index_uuid,index_ready,updated_at) VALUES(?,?,?,?,?,?,?)",
                  restoredControl.singletonId,
                  restoredControl.seriesId,
                  restoredControl.revision,
                  restoredControl.startedAt,
                  restoredControl.indexUuid,
                  restoredControl.indexReady,
                  restoredControl.updatedAt));
        } else {
          assertEquals(
              1,
              run.database.jdbc.update(
                  "UPDATE product_metric_control SET series_id=?,revision=?,started_at=?,index_uuid=?,index_ready=?,updated_at=? WHERE singleton_id=?",
                  restoredControl.seriesId,
                  restoredControl.revision,
                  restoredControl.startedAt,
                  restoredControl.indexUuid,
                  restoredControl.indexReady,
                  restoredControl.updatedAt,
                  restoredControl.singletonId));
        }
        fixtureChanges.incrementAndGet();
        assertEquals(controlBaseline, run.database.tableFacts("product_metric_control"));
        run.database.audit(
            "initial-joined-restored-" + badControl,
            run.database.tableFacts("product_metric_control"));
      }
      assertEquals(6L, fixtureChanges.get());
      assertEquals(5L, methodApplyCalls.get());
      assertEquals(0L, methodConsumeCalls.get());
      assertEquals(0L, methodWaitQueries.get());
      run.database.audit(
          "initial-joined-fixed-counts",
          Map.of(
              "apply",
              methodApplyCalls.get(),
              "consume",
              methodConsumeCalls.get(),
              "explicitJoins",
              explicitJoins.get(),
              "fixtureChanges",
              fixtureChanges.get(),
              "identityQueries",
              initialSnapshotIdentityQueries.get(),
              "waitQueries",
              methodWaitQueries.get()));
      run.success();
    }
  }

  /**
   * Preserve this added method's complete query result without requiring Java-time JSON modules.
   */
  private static Map<String, Object> initialJoinedSnapshotFacts(
      ProductMetricInitialApplySnapshotDTO snapshot) {
    Map<String, Object> facts = new LinkedHashMap<>();
    ProductMetricControlPO control = snapshot.control;
    facts.put("singletonId", control.singletonId);
    facts.put("seriesId", control.seriesId);
    facts.put("revision", control.revision);
    facts.put("startedAt", control.startedAt == null ? null : control.startedAt.toString());
    facts.put("indexUuid", control.indexUuid);
    facts.put("indexReady", control.indexReady);
    facts.put("updatedAt", control.updatedAt == null ? null : control.updatedAt.toString());
    facts.put("appliedEventId", snapshot.appliedEventId);
    facts.put("appliedFingerprint", snapshot.appliedFingerprint);
    facts.put("mappingPresent", snapshot.mappingPresent);
    return facts;
  }

  private static Hook pauseBeforeGate(
      String expectedActor, CountDownLatch entered, CountDownLatch release) {
    return new Hook() {
      /** Hold the named lane at its last gate without replacing the delegated SQL. */
      @Override
      public void before(String actor, String method, Object[] arguments) throws Exception {
        if (actor.equals(expectedActor) && gate(method, arguments)) {
          entered.countDown();
          await(release);
        }
      }
    };
  }

  private static boolean gate(String method, Object[] arguments) {
    return method.equals("control") && arguments.length == 1 && Boolean.TRUE.equals(arguments[0]);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(1, TimeUnit.SECONDS), "bounded SQL interleaving latch timed out");
  }

  private static ProductMetricEventDTO event(long productId) {
    return ProductMetricEvents.product(UUID.randomUUID().toString(), productId, false, null);
  }

  private static ProductMetricEventDTO sale(long productId) {
    ProductMetricEventDTO event = new ProductMetricEventDTO();
    event.payloadVersion = 1;
    event.seriesId = ProductMetricEvents.SERIES;
    event.eventType = "STOCK_SALE_CONFIRMED";
    event.producer = "STOCK";
    event.productId = productId;
    event.skuId = 71L;
    event.quantity = 2;
    event.orderNo = "202610late" + UUID.randomUUID().toString().replace("-", "");
    event.sourceRef = "stock_flow:" + event.orderNo + ":CONFIRM:71";
    event.sourceRevision = 1L;
    event.occurredAt = "2026-10-22T10:00:00.000+08:00";
    event.eventId = ProductMetricEvents.identity(event);
    ProductMetricEvents.validate(event);
    return event;
  }

  private static SQLException assertSql(Throwable failure, int code, String marker) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException) {
        SQLException sql = (SQLException) cause;
        if (sql.getErrorCode() == code && sql.getMessage().contains(marker)) return sql;
      }
    throw new AssertionError("Missing original SQL error " + code + " / " + marker, failure);
  }

  /**
   * Return the earliest next query time after a full 120 ms idle interval from SQL completion. This
   * pure eligibility clock neither extends the observation deadline nor proves a lock wait.
   *
   * @param queryEndedNanos actual client monotonic completion time of the successful SQL call
   * @return earliest permitted next query start; overflow propagates as an observation failure
   */
  private static long nextWaitObservationNanos(long queryEndedNanos) {
    return Math.addExact(queryEndedNanos, TimeUnit.MILLISECONDS.toNanos(120));
  }

  /** Query native waits without deleting unresolved thread or transaction associations. */
  private static String waitObservationSql() {
    return "SELECT w.ENGINE AS engine,w.REQUESTING_ENGINE_LOCK_ID AS requesting_engine_lock_id,"
        + " w.REQUESTING_ENGINE_TRANSACTION_ID AS requesting_engine_transaction_id,"
        + " w.REQUESTING_THREAD_ID AS requesting_thread_id,w.REQUESTING_EVENT_ID AS requesting_event_id,"
        + " w.BLOCKING_ENGINE_LOCK_ID AS blocking_engine_lock_id,"
        + " w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_engine_transaction_id,"
        + " w.BLOCKING_THREAD_ID AS blocking_thread_id,w.BLOCKING_EVENT_ID AS blocking_event_id,"
        + " requested.ENGINE_LOCK_ID AS requested_lock_id,"
        + " requested.ENGINE_TRANSACTION_ID AS requested_lock_engine_transaction_id,"
        + " requested.OBJECT_SCHEMA AS requested_schema,requested.OBJECT_NAME AS requested_table,"
        + " requested.INDEX_NAME AS requested_index,requested.LOCK_MODE AS requested_mode,"
        + " requested.LOCK_STATUS AS requested_status,blocked.ENGINE_LOCK_ID AS blocking_lock_id,"
        + " blocked.ENGINE_TRANSACTION_ID AS blocking_lock_engine_transaction_id,"
        + " blocked.OBJECT_SCHEMA AS blocking_schema,blocked.OBJECT_NAME AS blocking_table,"
        + " blocked.INDEX_NAME AS blocking_index,blocked.LOCK_MODE AS blocking_mode,"
        + " blocked.LOCK_STATUS AS blocking_status,"
        + " requesting.PROCESSLIST_ID AS ps_requesting_connection,"
        + " blocking.PROCESSLIST_ID AS ps_blocking_connection,"
        + " requesting_trx.TRX_ID AS trx_requesting_id,blocking_trx.TRX_ID AS trx_blocking_id,"
        + " requesting_trx.TRX_MYSQL_THREAD_ID AS trx_requesting_connection,"
        + " blocking_trx.TRX_MYSQL_THREAD_ID AS trx_blocking_connection"
        + " FROM performance_schema.data_lock_waits w"
        + " LEFT JOIN performance_schema.data_locks requested ON requested.ENGINE=w.ENGINE"
        + " AND requested.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"
        + " LEFT JOIN performance_schema.data_locks blocked ON blocked.ENGINE=w.ENGINE"
        + " AND blocked.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID"
        + " LEFT JOIN performance_schema.threads requesting ON requesting.THREAD_ID=w.REQUESTING_THREAD_ID"
        + " LEFT JOIN performance_schema.threads blocking ON blocking.THREAD_ID=w.BLOCKING_THREAD_ID"
        + " LEFT JOIN information_schema.innodb_trx requesting_trx"
        + " ON requesting_trx.TRX_ID=CAST(w.REQUESTING_ENGINE_TRANSACTION_ID AS CHAR)"
        + " LEFT JOIN information_schema.innodb_trx blocking_trx"
        + " ON blocking_trx.TRX_ID=CAST(w.BLOCKING_ENGINE_TRANSACTION_ID AS CHAR)"
        + " WHERE w.ENGINE='INNODB' LIMIT 16";
  }

  /** Qualify native owner chains; creation-event provenance never substitutes for ownership. */
  private static Map<String, Object> classifyWaitRow(
      Map<String, Object> row, String catalog, String table, Long requesting, Long blocking) {
    Map<String, Object> result = waitVerdict("UNKNOWN", "MISSING_NATIVE_ROW");
    if (row == null) return result;
    if (catalog == null || table == null) return waitVerdict("UNKNOWN", "EXPECTED_OBJECT_UNKNOWN");
    if (!"INNODB".equals(row.get("engine")))
      return waitVerdict(row.get("engine") == null ? "UNKNOWN" : "UNRELATED", "ENGINE_UNPROVEN");
    String[] columns = {
      "requested_schema",
      "requested_table",
      "requested_index",
      "blocking_schema",
      "blocking_table",
      "blocking_index"
    };
    String[] expected = {catalog, table, "PRIMARY", catalog, table, "PRIMARY"};
    for (int index = 0; index < columns.length; index++) {
      Object value = row.get(columns[index]);
      if (!expected[index].equals(value))
        return waitVerdict(
            value == null ? "UNKNOWN" : "UNRELATED", "OBJECT_" + columns[index] + "_UNPROVEN");
    }
    String[] sides = {"requesting", "blocking"};
    String[] locks = {"requested", "blocking"};
    for (int index = 0; index < sides.length; index++) {
      String side = sides[index], lock = locks[index];
      Object rawLock = row.get(side + "_engine_lock_id");
      String transaction = positiveDecimal(row.get(side + "_engine_transaction_id"));
      String lockTransaction = positiveDecimal(row.get(lock + "_lock_engine_transaction_id"));
      if (!(rawLock instanceof String)
          || ((String) rawLock).isBlank()
          || !rawLock.equals(row.get(lock + "_lock_id"))
          || transaction == null
          || !transaction.equals(lockTransaction))
        return waitVerdict("UNKNOWN", "ENGINE_LOCK_ASSOCIATION_UNPROVEN_" + side);
    }
    if (!"WAITING".equals(row.get("requested_status"))
        || !"GRANTED".equals(row.get("blocking_status")))
      return waitVerdict("UNKNOWN", "LOCK_WAIT_STATE_UNPROVEN");
    if (positiveDecimal(row.get("requesting_engine_transaction_id"))
        .equals(positiveDecimal(row.get("blocking_engine_transaction_id"))))
      return waitVerdict("UNKNOWN", "SAME_TRANSACTION_ENDPOINTS");
    Map<String, Object> requestIdentity = resolveWaitConnection(row, "requesting");
    Map<String, Object> blockIdentity = resolveWaitConnection(row, "blocking");
    result.put("requestingIdentity", requestIdentity);
    result.put("blockingIdentity", blockIdentity);
    if (!Boolean.TRUE.equals(requestIdentity.get("valid"))
        || !Boolean.TRUE.equals(blockIdentity.get("valid"))) {
      result.put("reason", "CONNECTION_ASSOCIATION_UNKNOWN");
      return result;
    }
    String expectedRequest = positiveDecimal(requesting), expectedBlock = positiveDecimal(blocking);
    if (expectedRequest == null || expectedBlock == null || expectedRequest.equals(expectedBlock)) {
      result.put("reason", "EXPECTED_ACTORS_UNKNOWN");
      return result;
    }
    if (!expectedRequest.equals(requestIdentity.get("connection"))
        || !expectedBlock.equals(blockIdentity.get("connection"))) {
      result.put("status", "UNRELATED");
      result.put("reason", "ACTOR_MISMATCH");
      return result;
    }
    result.put("status", "MATCH");
    result.put("reason", "EXACT_OWNED_WAIT_EDGE");
    return result;
  }

  /**
   * Resolve the owning transaction's session. P_S thread/event identifies the lock structure's
   * creator, which can differ when another session materializes an implicit lock for its owner.
   * Missing or contradictory InnoDB ownership cannot fall back to a creator connection.
   */
  private static Map<String, Object> resolveWaitConnection(Map<String, Object> row, String side) {
    Object rawTransaction = row.get("trx_" + side + "_connection");
    String transaction = positiveDecimal(rawTransaction);
    String engineTransaction = positiveDecimal(row.get(side + "_engine_transaction_id"));
    String lockPrefix = side.equals("requesting") ? "requested" : "blocking";
    String lockTransaction = positiveDecimal(row.get(lockPrefix + "_lock_engine_transaction_id"));
    String transactionId = positiveDecimal(row.get("trx_" + side + "_id"));
    boolean valid =
        transaction != null
            && engineTransaction != null
            && engineTransaction.equals(lockTransaction)
            && engineTransaction.equals(transactionId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("valid", valid);
    result.put("reason", valid ? "RESOLVED_OWNER" : "INNODB_TRANSACTION_ASSOCIATION_UNPROVEN");
    result.put("ownerSource", "INNODB_TRX");
    result.put("engineTransaction", row.get(side + "_engine_transaction_id"));
    result.put("lockTransaction", row.get(lockPrefix + "_lock_engine_transaction_id"));
    result.put("innodbTrxId", row.get("trx_" + side + "_id"));
    result.put("innodbTrxConnection", rawTransaction);
    result.put("connection", valid ? transaction : null);
    result.put("creatorThreadId", row.get(side + "_thread_id"));
    result.put("creatorEventId", row.get(side + "_event_id"));
    result.put("creatorConnection", row.get("ps_" + side + "_connection"));
    return result;
  }

  /** Keep metadata classification explicit even when no native identity is available. */
  private static Map<String, Object> waitVerdict(String status, String reason) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", status);
    result.put("reason", reason);
    return result;
  }

  /**
   * Canonical decimal strings retain unsigned precision without long or floating-point coercion.
   */
  private static String positiveDecimal(Object value) {
    if (value == null || !value.toString().matches("[0-9]+")) return null;
    java.math.BigInteger number = new java.math.BigInteger(value.toString());
    return number.signum() > 0 ? number.toString() : null;
  }

  /** Retain the wrapper plus original SQLSTATE/vendor/message, including nullable native fields. */
  private static Map<String, Object> waitFailureEvidence(Throwable failure) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", failure.getClass().getName());
    result.put("message", failure.getMessage());
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException) {
        SQLException sql = (SQLException) cause;
        result.put("sqlState", sql.getSQLState());
        result.put("vendorCode", sql.getErrorCode());
        result.put("sqlMessage", sql.getMessage());
        break;
      }
    return result;
  }

  /**
   * Test-only stage observers always delegate the actual mapper call without changing its result.
   */
  private interface Hook {
    /** Observe the exact point immediately before production SQL delegation. */
    default void before(String actor, String method, Object[] arguments) throws Exception {}

    /** Observe only after the actual production mapper has returned. */
    default void after(String actor, String method, Object[] arguments, Object result)
        throws Exception {}
  }

  /** One declared subscenario owns one catalog, real Spring graph and bounded worker group. */
  private final class Run implements AutoCloseable {
    /** Existing fixture performs exact local-source checks and failure preservation. */
    private final ProductOnlineMetricsMySqlIT.OwnedDatabase database;

    /** Original Product Spring configuration runs without a web listener or external middleware. */
    private final ConfigurableApplicationContext context;

    /** Genuine transactional application proxy, including physical commit and rollback. */
    private final ProductMetricService service;

    /** Observing class proxy delegates every operation to the original real mapper. */
    private final ProductMetricMapper mapper;

    /** Short actors are owned by this subscenario and joined before catalog cleanup. */
    private final ExecutorService workers = Executors.newFixedThreadPool(3);

    /** Per-thread actor name associates exact observations with their deterministic lane. */
    private final ThreadLocal<String> actor = ThreadLocal.withInitial(() -> "main");

    /**
     * Last actual bound connection prevents duplicate CONNECTION_ID queries inside one transaction.
     */
    private final ThreadLocal<Connection> observedConnection = new ThreadLocal<>();

    /** Actual MySQL connection IDs are used only for owned wait-view correlation. */
    private final Map<String, Long> connectionIds = new ConcurrentHashMap<>();

    /**
     * Notifications are published after observer hooks, immediately before actual SQL delegation.
     */
    private final Map<String, CountDownLatch> sqlDelegations = new ConcurrentHashMap<>();

    /** Ordered mapper observations, SQL failures and real transaction completion facts. */
    private final List<Map<String, Object>> observations =
        Collections.synchronizedList(new ArrayList<>());

    /** Mock transport ACKs are recorded only after genuine database transaction cleanup. */
    private final List<Map<String, Object>> acks = Collections.synchronizedList(new ArrayList<>());

    /** Every registered release is opened on failure so worker cleanup cannot strand a latch. */
    private final List<CountDownLatch> releases = new ArrayList<>();

    /**
     * A monotonic local sequence orders callbacks without pretending they are wall-clock timings.
     */
    private final AtomicLong sequence = new AtomicLong();

    /** Current finite observation behavior; no production method is replaced by a business stub. */
    private volatile Hook hook = new Hook() {};

    /** Exact success is required before the existing fixture may drop its own catalog. */
    private boolean passed;

    private Run(String label) throws Exception {
      String owner = System.getProperty("cloudmall.cm21.validation.owner");
      if (owner == null || !owner.matches("[0-9a-f]{32}"))
        throw new IllegalStateException("LATE_GATE_EXPLICIT_VALIDATION_OWNER_REQUIRED");
      ProductOnlineMetricsMySqlIT.OwnedDatabase acquiredDatabase = null;
      ConfigurableApplicationContext acquiredContext = null;
      try {
        acquiredDatabase =
            new ProductOnlineMetricsMySqlIT.OwnedDatabase("product", "late-gate-" + label);
        database = acquiredDatabase;
        database.seedProducts(label.equals("readiness-and-cut") ? 3 : 2);
        acquiredContext = startWithoutWeb(database);
        context = acquiredContext;
        service = context.getBean(ProductMetricService.class);
        assertTrue(AopUtils.isAopProxy(service));
        ProductMetricMapper original = context.getBean(ProductMetricMapper.class);
        ProxyFactory observer = new ProxyFactory(original);
        observer.setProxyTargetClass(true);
        observer.addAdvice((MethodInterceptor) this::observe);
        mapper = (ProductMetricMapper) observer.getProxy();
        ProductMetricService serviceTarget = AopTestUtils.getUltimateTargetObject(service);
        ReflectionTestUtils.setField((Object) serviceTarget, "mapper", mapper);
        ((Advised) service)
            .addAdvice(
                0,
                (MethodInterceptor)
                    invocation -> {
                      if (!invocation.getMethod().getName().equals("apply")) {
                        if (invocation.getMethod().getDeclaringClass() != Object.class)
                          assertTrue(
                              OTHER_SERVICE_CALLS.incrementAndGet() <= 40,
                              "public service call budget exceeded");
                        return invocation.proceed();
                      }
                      assertTrue(
                          methodApplyCalls.incrementAndGet() <= methodApplyLimit,
                          "method apply attempt budget exceeded");
                      assertTrue(
                          APPLY_CALLS.incrementAndGet() <= 32,
                          "reviewed apply attempt budget exceeded");
                      try {
                        Object result = invocation.proceed();
                        record(
                            actor.get(),
                            "SERVICE_RETURN",
                            "apply",
                            Map.of("result", String.valueOf(result)));
                        return result;
                      } catch (Throwable failure) {
                        record(
                            actor.get(),
                            "SERVICE_FAILURE",
                            "apply",
                            Map.of(
                                "type",
                                failure.getClass().getName(),
                                "message",
                                String.valueOf(failure.getMessage())));
                        throw failure;
                      }
                    });
        assertSame(database.dataSource, original.dataSource());
        assertSame(database.dataSource, context.getBean(javax.sql.DataSource.class));
        assertSame(
            database.dataSource,
            context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).getDataSource());
        assertSame(
            database.dataSource,
            ((org.springframework.jdbc.datasource.DataSourceTransactionManager)
                    context.getBean(PlatformTransactionManager.class))
                .getDataSource());
        assertSame(
            database.dataSource,
            context
                .getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                .getConfiguration()
                .getEnvironment()
                .getDataSource());
        assertEquals(
            "REPEATABLE-READ",
            database.jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        database.audit(
            "actual-wiring",
            Map.of(
                "webApplicationType",
                "NONE",
                "realServiceProxy",
                true,
                "sameDataSource",
                true,
                "transport",
                "MOCK_ONLY_NO_AMQP",
                "owner",
                owner));
      } catch (Exception | Error initializationFailure) {
        SAFETY_STOP.set(true);
        try {
          workers.shutdown();
          if (!workers.awaitTermination(5, TimeUnit.SECONDS))
            throw new IllegalStateException("LATE_GATE_INITIALIZATION_WORKERS_UNKNOWN");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          initializationFailure.addSuppressed(interrupted);
        } catch (RuntimeException | Error cleanupFailure) {
          if (cleanupFailure != initializationFailure)
            initializationFailure.addSuppressed(cleanupFailure);
        }
        if (acquiredContext != null) {
          try {
            acquiredContext.close();
          } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != initializationFailure)
              initializationFailure.addSuppressed(cleanupFailure);
          }
        }
        if (acquiredDatabase != null) {
          for (ConfigurableApplicationContext registered : acquiredDatabase.contexts) {
            if (registered == acquiredContext) continue;
            try {
              registered.close();
            } catch (RuntimeException | Error cleanupFailure) {
              if (cleanupFailure != initializationFailure)
                initializationFailure.addSuppressed(cleanupFailure);
            }
          }
          try {
            acquiredDatabase.close();
          } catch (Exception | Error cleanupFailure) {
            if (cleanupFailure != initializationFailure)
              initializationFailure.addSuppressed(cleanupFailure);
          }
        }
        throw initializationFailure;
      }
    }

    private CountDownLatch latch(int count) {
      CountDownLatch latch = new CountDownLatch(count);
      releases.add(latch);
      return latch;
    }

    private <T> Future<T> submit(String name, Callable<T> action) {
      return workers.submit(
          () -> {
            actor.set(name);
            try {
              return action.call();
            } finally {
              actor.remove();
              observedConnection.remove();
              AuthContext.clear();
            }
          });
    }

    private Future<String> apply(String name, ProductMetricEventDTO event) {
      return submit(name, () -> service.apply(event));
    }

    private String direct(String name, ProductMetricEventDTO event) {
      actor.set(name);
      try {
        return service.apply(event);
      } finally {
        actor.remove();
        observedConnection.remove();
      }
    }

    private Future<Void> consume(String name, ProductMetricEventDTO event) {
      assertTrue(
          methodConsumeCalls.incrementAndGet() <= methodConsumeLimit,
          "method consume budget exceeded");
      assertTrue(CONSUME_CALLS.incrementAndGet() <= 7, "consume delivery budget exceeded");
      return submit(
          name,
          () -> {
            ProductMetricOutboxDispatcher publisher = mock(ProductMetricOutboxDispatcher.class);
            when(publisher.transfer(anyString(), anyString(), any())).thenReturn(true);
            ProductMetricConsumer consumer =
                new ProductMetricConsumer(
                    service, publisher, mock(RabbitListenerEndpointRegistry.class));
            Channel channel = mock(Channel.class);
            doAnswer(
                    invocation -> {
                      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                      List<Map<String, Object>> committed =
                          database.jdbc.queryForList(
                              "SELECT event_id,fingerprint FROM product_metric_applied_event WHERE"
                                  + " event_id=?",
                              event.eventId);
                      boolean matching =
                          !committed.isEmpty()
                              && ProductMetricEvents.fingerprint(event)
                                  .equals(committed.get(0).get("fingerprint"));
                      if (name.equals("version-conflict")) assertTrue(committed.isEmpty());
                      else {
                        assertEquals(1, committed.size());
                        if (!matching)
                          assertEquals(
                              1L,
                              database.jdbc.queryForObject(
                                  "SELECT COUNT(*) FROM product_metric_quarantine WHERE event_id=?"
                                      + " AND reason='EVENT_FINGERPRINT_CONFLICT'",
                                  Long.class,
                                  event.eventId));
                      }
                      acks.add(
                          Map.of(
                              "actor",
                              name,
                              "mockTransport",
                              true,
                              "outsideTransaction",
                              true,
                              "independentCommittedLedgerRows",
                              committed.size(),
                              "fingerprintMatches",
                              matching));
                      return null;
                    })
                .when(channel)
                .basicAck(7L, false);
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryTag(7L);
            consumer.consume(new Message(ProductMetricEvents.bytes(event), properties), channel);
            verify(channel).basicAck(7L, false);
            verifyNoMoreInteractions(channel);
            if (name.equals("version-conflict")) {
              verify(publisher)
                  .transfer(
                      eq("cloudmall.product.metrics.dlx"),
                      eq("cloudmall.product.metrics.retry"),
                      any(Message.class));
              verifyNoMoreInteractions(publisher);
            } else if (name.equals("loser") && event.eventType.equals("STOCK_SALE_CONFIRMED")) {
              verify(publisher)
                  .transfer(
                      eq("cloudmall.product.metrics.dlx"),
                      eq("cloudmall.product.metrics.dlq"),
                      any(Message.class));
              verifyNoMoreInteractions(publisher);
            } else verifyNoInteractions(publisher);
            return null;
          });
    }

    private Object observe(MethodInvocation invocation) throws Throwable {
      String name = actor.get(), method = invocation.getMethod().getName();
      Object[] arguments = invocation.getArguments();
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        Connection bound = DataSourceUtils.getConnection(database.dataSource);
        try {
          if (observedConnection.get() != bound) {
            observedConnection.set(bound);
            assertTrue(
                IDENTITY_QUERIES.incrementAndGet() <= (initialSnapshotMethod ? 80 : 64),
                "connection identity budget exceeded");
            if (initialSnapshotMethod)
              assertTrue(
                  initialSnapshotIdentityQueries.incrementAndGet() <= 16,
                  "initial snapshot identity budget exceeded");
            long connectionId = database.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            connectionIds.put(name, connectionId);
            record(name, "BEGIN", method, Map.of("connectionId", connectionId));
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  /**
                   * Record the actual transaction manager completion status for this connection.
                   */
                  @Override
                  public void afterCompletion(int status) {
                    record(
                        name,
                        "COMPLETION",
                        method,
                        Map.of("connectionId", connectionId, "status", status));
                  }
                });
          }
        } finally {
          DataSourceUtils.releaseConnection(bound, database.dataSource);
        }
      }
      record(
          name,
          "BEFORE",
          method,
          Map.of(
              "lastArgument",
              lastArgument(arguments),
              "firstArgument",
              arguments.length == 0 ? "NONE" : String.valueOf(arguments[0])));
      hook.before(name, method, arguments);
      String requestedTable =
          method.equals("ensureStat")
              ? "product_online_stat"
              : gate(method, arguments) ? "product_metric_control" : null;
      if (requestedTable != null) {
        record(name, "SQL_DELEGATION", method, Map.of("table", requestedTable));
        sqlDelegations
            .computeIfAbsent(name + "|" + requestedTable, key -> new CountDownLatch(1))
            .countDown();
      }
      try {
        Object result = invocation.proceed();
        record(
            name,
            "AFTER",
            method,
            Map.of(
                "lastArgument",
                lastArgument(arguments),
                "result",
                result == null ? "VOID_OR_NULL" : String.valueOf(result)));
        hook.after(name, method, arguments, result);
        return result;
      } catch (Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
          if (cause instanceof SQLException) {
            SQLException sql = (SQLException) cause;
            record(
                name,
                "SQL_FAILURE",
                method,
                Map.of(
                    "code",
                    sql.getErrorCode(),
                    "sqlState",
                    String.valueOf(sql.getSQLState()),
                    "message",
                    sql.getMessage()));
          }
        throw failure;
      }
    }

    private String lastArgument(Object[] arguments) {
      return arguments.length == 0 ? "NONE" : String.valueOf(arguments[arguments.length - 1]);
    }

    private void record(String name, String phase, String method, Map<String, Object> extra) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sequence", sequence.incrementAndGet());
      row.put("actor", name);
      row.put("phase", phase);
      row.put("method", method);
      row.putAll(extra);
      observations.add(row);
    }

    /**
     * Read actual owned wait edges on an independent connection; elapsed time alone is no proof.
     */
    private void actualWait(String name, String expectedTable, String blockingActor)
        throws Exception {
      long started = System.nanoTime();
      long deadline = started + TimeUnit.MILLISECONDS.toNanos(900);
      CountDownLatch delegation =
          sqlDelegations.computeIfAbsent(name + "|" + expectedTable, key -> new CountDownLatch(1));
      List<Map<String, Object>> samples = new ArrayList<>();
      Map<String, Object> selected = null;
      Throwable problem = null;
      String reason = "NO_MATCHING_EDGE";
      boolean notified = false;
      boolean capped = false;
      long notifiedObserved = -1;
      int queries = 0;
      Long requesting = connectionIds.get(name), blocking = connectionIds.get(blockingActor);
      try {
        long remaining = deadline - System.nanoTime();
        notified = remaining > 0 && delegation.await(remaining, TimeUnit.NANOSECONDS);
        notifiedObserved = System.nanoTime();
        requesting = connectionIds.get(name);
        blocking = connectionIds.get(blockingActor);
        if (!notified) reason = "TARGET_SQL_NOT_REACHED";
        else if (requesting == null || blocking == null) reason = "ACTOR_CONNECTION_UNKNOWN";
        else {
          int slots = (int) Math.max(0, 12 - methodWaitQueries.get());
          long nextObservation = System.nanoTime();
          if (slots == 0) reason = "QUERY_QUOTA_EXHAUSTED";
          for (int slot = 0; slot < slots; slot++) {
            long scheduled = nextObservation;
            if (scheduled >= deadline) {
              reason = "OBSERVATION_DEADLINE";
              break;
            }
            while (System.nanoTime() < scheduled && System.nanoTime() < deadline) {
              if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("wait observer interrupted");
              long pause = Math.min(scheduled, deadline) - System.nanoTime();
              if (pause > 0) java.util.concurrent.locks.LockSupport.parkNanos(pause);
            }
            if (System.nanoTime() >= deadline) {
              reason = "OBSERVATION_DEADLINE";
              break;
            }
            if (methodWaitQueries.get() >= 12) {
              reason = "QUERY_QUOTA_EXHAUSTED";
              break;
            }
            methodWaitQueries.incrementAndGet();
            queries++;
            long queryStarted = System.nanoTime();
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("number", queries);
            sample.put("scheduledNanos", scheduled);
            sample.put("startedNanos", queryStarted);
            try {
              List<Map<String, Object>> rows = database.jdbc.queryForList(waitObservationSql());
              long ended = System.nanoTime();
              nextObservation = nextWaitObservationNanos(ended);
              sample.put("endedNanos", ended);
              sample.put("elapsedNanos", ended - queryStarted);
              sample.put("rawRows", rows);
              List<Map<String, Object>> assessments = new ArrayList<>();
              for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> assessment =
                    classifyWaitRow(
                        rows.get(index), database.database, expectedTable, requesting, blocking);
                assessment.put("rowIndex", index);
                assessments.add(assessment);
                if ("MATCH".equals(assessment.get("status"))) selected = assessment;
              }
              sample.put("assessments", assessments);
              capped = rows.size() >= 16;
              sample.put("capReached", capped);
              samples.add(sample);
              if (capped) {
                selected = null;
                reason = "RAW_CAP_UNKNOWN";
              } else if (ended > deadline) {
                selected = null;
                reason = "QUERY_AFTER_DEADLINE";
              } else if (selected != null) reason = "EXACT_OWNED_WAIT_EDGE";
              if (capped || ended > deadline || selected != null) break;
            } catch (Exception | Error failure) {
              long ended = System.nanoTime();
              sample.put("endedNanos", ended);
              sample.put("elapsedNanos", ended - queryStarted);
              sample.put("exception", waitFailureEvidence(failure));
              samples.add(sample);
              throw failure;
            }
          }
        }
      } catch (Exception | Error failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        problem = failure;
        reason = "OBSERVATION_EXCEPTION";
      }
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("startedNanos", started);
      evidence.put("deadlineNanos", deadline);
      evidence.put("samplingEndedNanos", System.nanoTime());
      evidence.put("delegationReached", notified);
      evidence.put("delegationObservedNanos", notifiedObserved);
      evidence.put(
          "delegationClockMeaning", "waiter observation; notifier instant not reconstructed");
      evidence.put("requestingActor", name);
      evidence.put("blockingActor", blockingActor);
      evidence.put("expectedRequestingConnection", requesting);
      evidence.put("expectedBlockingConnection", blocking);
      evidence.put("expectedCatalog", database.database);
      evidence.put("expectedTable", expectedTable);
      evidence.put("expectedIndex", "PRIMARY");
      evidence.put("queryCount", queries);
      evidence.put("metadataQueried", queries > 0);
      evidence.put("queriesUsedByMethod", methodWaitQueries.get());
      evidence.put("capReached", capped);
      evidence.put("samples", samples);
      evidence.put("selected", selected);
      evidence.put("samplingReason", reason);
      evidence.put("exception", problem == null ? null : waitFailureEvidence(problem));
      evidence.put("sql", waitObservationSql());
      evidence.put("auditStartedNanos", System.nanoTime());
      evidence.put("finalAuditClockInScenarioObservations", true);
      long auditEnded;
      try {
        int bytes = ProductOnlineMetricsMySqlIT.JSON.writeValueAsBytes(evidence).length;
        if (bytes > 2 * 1024 * 1024) {
          selected = null;
          reason = "RAW_EVIDENCE_SIZE_LIMIT_UNKNOWN";
          evidence = new LinkedHashMap<>();
          evidence.put("samplingReason", reason);
          evidence.put("fullRawEvidenceWritten", false);
          evidence.put("unwrittenBytes", bytes);
          evidence.put("queryCount", queries);
          evidence.put("startedNanos", started);
          evidence.put("deadlineNanos", deadline);
        }
        database.audit("actual-wait-" + name + "-" + sequence.incrementAndGet(), evidence);
        auditEnded = System.nanoTime();
        record(
            name,
            "WAIT_AUDIT_COMPLETED",
            "actualWait",
            Map.of(
                "endedNanos",
                auditEnded,
                "deadlineNanos",
                deadline,
                "withinWindow",
                auditEnded <= deadline,
                "reason",
                reason));
      } catch (Exception | Error auditFailure) {
        auditEnded = System.nanoTime();
        if (problem == null) problem = auditFailure;
        else if (problem != auditFailure) problem.addSuppressed(auditFailure);
        selected = null;
        record(
            name,
            "WAIT_AUDIT_FAILED",
            "actualWait",
            Map.of("endedNanos", auditEnded, "exception", waitFailureEvidence(auditFailure)));
      }
      if (problem instanceof Error) throw (Error) problem;
      if (problem != null) throw (Exception) problem;
      assertTrue(
          selected != null && auditEnded <= deadline,
          "No qualified database wait edge: "
              + reason
              + "; auditWithinWindow="
              + (auditEnded <= deadline));
    }

    private void assertApplied(long productId, long views, long version) {
      Map<String, Object> row =
          database.jdbc.queryForMap(
              "SELECT * FROM product_online_stat WHERE product_id=?", productId);
      assertEquals(views, ((Number) row.get("view_count")).longValue());
      assertEquals(version, ((Number) row.get("metric_version")).longValue());
      assertEquals(views, ((Number) row.get("hot_score")).longValue());
    }

    private void assertSqlFailure(String name, int code, String marker) {
      assertTrue(
          observations.stream()
              .anyMatch(
                  row ->
                      name.equals(row.get("actor"))
                          && "SQL_FAILURE".equals(row.get("phase"))
                          && Integer.valueOf(code).equals(row.get("code"))
                          && String.valueOf(row.get("message")).contains(marker)),
          "Original SQL failure not observed");
    }

    private long sqlFailureCount(String name, int code) {
      return observations.stream()
          .filter(
              row ->
                  name.equals(row.get("actor"))
                      && "SQL_FAILURE".equals(row.get("phase"))
                      && Integer.valueOf(code).equals(row.get("code")))
          .count();
    }

    private boolean hasCompletion(String name, int status) {
      return observations.stream()
          .anyMatch(
              row ->
                  name.equals(row.get("actor"))
                      && "COMPLETION".equals(row.get("phase"))
                      && Integer.valueOf(status).equals(row.get("status")));
    }

    private void assertRetryThenCommit(String name) {
      assertTrue(hasCompletion(name, TransactionSynchronization.STATUS_ROLLED_BACK));
      assertTrue(hasCompletion(name, TransactionSynchronization.STATUS_COMMITTED));
      List<Object> connections =
          observations.stream()
              .filter(row -> name.equals(row.get("actor")) && "BEGIN".equals(row.get("phase")))
              .map(row -> row.get("connectionId"))
              .toList();
      assertEquals(2, connections.size());
      assertNotEquals(connections.get(0), connections.get(1));
    }

    private boolean saw(String name, String method, String lastArgument) {
      return observations.stream()
          .anyMatch(
              row ->
                  name.equals(row.get("actor"))
                      && method.equals(row.get("method"))
                      && lastArgument.equals(row.get("lastArgument")));
    }

    private void assertOrder(String name, String first, String second) {
      long firstSequence =
          observations.stream()
              .filter(
                  row ->
                      name.equals(row.get("actor"))
                          && first.equals(row.get("method"))
                          && "BEFORE".equals(row.get("phase")))
              .mapToLong(row -> ((Number) row.get("sequence")).longValue())
              .min()
              .orElseThrow();
      long secondSequence =
          observations.stream()
              .filter(
                  row ->
                      name.equals(row.get("actor"))
                          && second.equals(row.get("method"))
                          && "BEFORE".equals(row.get("phase")))
              .mapToLong(row -> ((Number) row.get("sequence")).longValue())
              .min()
              .orElseThrow();
      assertTrue(firstSequence < secondSequence);
    }

    /**
     * Capture only the new method's immutable committed business facts, outside its RR snapshot.
     */
    private Map<String, Object> initialJoinedBusinessFacts() throws Exception {
      Map<String, Object> facts = new LinkedHashMap<>();
      for (String table :
          List.of(
              "product_metric_applied_event",
              "product_online_stat",
              "product_metric_projection_outbox")) facts.put(table, database.tableFacts(table));
      return facts;
    }

    private long revision() {
      return database.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1");
    }

    private LocalDateTime time() {
      return LocalDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
    }

    private void seedStat(long productId, long views, long version) throws Exception {
      database.jdbc.update(
          "INSERT INTO"
              + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at)"
              + " VALUES(?,?,0,0,?,?,?,?)",
          productId,
          views,
          views,
          version,
          time(),
          time());
      database.audit("declared-stat-seed-" + productId, database.tableFacts("product_online_stat"));
    }

    private void seedReady() {
      database.jdbc.update(
          "INSERT INTO"
              + " product_index_rebuild_job(job_id,target_index,index_generation,cursor_product_id,batch_size,max_events_per_minute,last_batch_count,status,created_at,updated_at)"
              + " VALUES('cloudmall-product-index','late-gate-index',1,43,50,6000,3,'COMPLETED',NOW(3),NOW(3))");
      assertTrue(service.readiness("late-gate-index", true));
    }

    private TransactionTemplate transaction() {
      TransactionTemplate transaction =
          new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      transaction.setTimeout(3);
      return transaction;
    }

    private void success() {
      passed = true;
    }

    /**
     * Attempt every independent close; only complete clean scenarios receive the old delete gate.
     */
    @Override
    public void close() throws Exception {
      hook = new Hook() {};
      for (CountDownLatch latch : releases) while (latch.getCount() > 0) latch.countDown();
      List<Throwable> failures = new ArrayList<>();
      workers.shutdown();
      try {
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
          SAFETY_STOP.set(true);
          workers.shutdownNow();
          throw new IllegalStateException("LATE_GATE_WORKERS_NOT_TERMINATED");
        }
      } catch (Throwable failure) {
        SAFETY_STOP.set(true);
        failures.add(failure);
      }
      try {
        context.close();
      } catch (Throwable failure) {
        SAFETY_STOP.set(true);
        failures.add(failure);
      }
      try {
        database.audit(
            "late-gate-observations",
            Map.of(
                "observations",
                observations,
                "mockTransportAcks",
                acks,
                "workerTerminated",
                workers.isTerminated(),
                "contextActive",
                context.isActive(),
                "assertionsPassed",
                passed));
      } catch (Throwable failure) {
        failures.add(failure);
      }
      if (passed && failures.isEmpty()) database.success();
      try {
        database.close();
      } catch (Throwable failure) {
        if (!"CM21_PRESERVED_ASSERTIONS_NOT_COMPLETE".equals(failure.getMessage()))
          SAFETY_STOP.set(true);
        failures.add(failure);
      }
      if (!failures.isEmpty()) {
        IllegalStateException failure = new IllegalStateException("LATE_GATE_SCENARIO_PRESERVED");
        failures.forEach(failure::addSuppressed);
        throw failure;
      }
    }
  }

  /** Reuse the original SQL application's real graph while creating no HTTP listener or port. */
  @SuppressWarnings("unchecked")
  private static ConfigurableApplicationContext startWithoutWeb(
      ProductOnlineMetricsMySqlIT.OwnedDatabase database) throws Exception {
    Field field = ProductOnlineMetricsMySqlIT.class.getDeclaredField("CURRENT");
    field.setAccessible(true);
    ThreadLocal<ProductOnlineMetricsMySqlIT.OwnedDatabase> current =
        (ThreadLocal<ProductOnlineMetricsMySqlIT.OwnedDatabase>) field.get(null);
    current.set(database);
    try {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(ProductOnlineMetricsMySqlIT.SqlApplication.class)
              .web(WebApplicationType.NONE)
              .profiles("cm21-product-mysql-it")
              .run(
                  "--spring.main.web-application-type=none",
                  "--spring.main.banner-mode=off",
                  "--logging.level.root=WARN",
                  "--spring.cloud.nacos.discovery.enabled=false",
                  "--spring.cloud.nacos.config.enabled=false",
                  "--seata.enabled=false",
                  "--seata.enable-auto-data-source-proxy=false",
                  "--cloudmall.product.metrics.enabled=true",
                  "--cloudmall.product.metrics.sort-mode=LEGACY",
                  "--cloudmall.product.search.enabled=false");
      database.contexts.add(context);
      assertEquals(0, database.ownedPorts.size());
      return context;
    } finally {
      current.remove();
    }
  }
}
