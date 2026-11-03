package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic capability/clock cases, distinct from the real Spring lifecycle integration suite. */
class ProductMetricServiceTransactionObservationSupportTest {
  /** Original shared entry stage for controlled calls. */
  private static final ProductMetricPipelineLoadSupport.Stage MEASUREMENT =
      ProductMetricPipelineLoadSupport.Stage.MEASUREMENT;

  /** Fixed original owner-mode identity, not a runtime UUID. */
  private static final String IDENTITY = "0123456789abcdef0123456789abcdef-batch1";

  /** Remove only synthetic thread state owned by this fixture after each test. */
  @AfterEach
  void clearSyntheticTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void constructionAndColdSnapshotNeverReadSuppliersAndHaveClosedShape() {
    AtomicInteger reads = new AtomicInteger();
    var collector =
        new ProductMetricServiceTransactionObservationSupport(
            () -> {
              reads.incrementAndGet();
              throw new AssertionError();
            },
            () -> {
              reads.incrementAndGet();
              throw new AssertionError();
            });
    Map<String, Object> snapshot = collector.snapshot();
    assertEquals(9, snapshot.size());
    assertEquals(13, map(snapshot.get("runtimeIdentityFacts")).size());
    assertEquals(7, map(snapshot.get("collectorState")).size());
    assertEquals(26, map(map(snapshot.get("collectorState")).get("counters")).size());
    assertEquals(7, map(snapshot.get("qualification")).size());
    assertEquals(112, list(snapshot.get("cells")).size());
    for (Object row : list(snapshot.get("cells"))) {
      assertEquals(14, map(row).size());
      assertEquals(5, map(map(row).get("segments")).size());
      assertEquals(64, list(map(row).get("markerMaskBuckets")).size());
    }
    assertEquals(0, reads.get());
    assertThrows(
        ProductMetricServiceTransactionObservationSupport.ObservationIncompleteException.class,
        collector::assertHealthyAfterClose);
    assertEquals("NONE", state(collector).get("failureCode"));
  }

  @Test
  void coldProofKeepsOriginalManagerDatasourceAndAcceptsLocalSubclassWithoutConnection() {
    DataSource dataSource = mock(DataSource.class);
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource) {};
    var collector = collector(new AtomicLong()::getAndIncrement);
    assertTrue(collector.verifyRuntime(manager, dataSource));
    assertTrue(collector.verifyRuntime(manager, dataSource));
    Map<String, Object> facts = map(collector.snapshot().get("runtimeIdentityFacts"));
    assertEquals(System.identityHashCode(manager), facts.get("managerIdentityHashCode"));
    assertEquals(manager.getClass().getName(), facts.get("managerClassName"));
    assertEquals(false, facts.get("managerWrapped"));
    verifyNoInteractions(dataSource);
    assertFalse(collector.verifyRuntime(new DataSourceTransactionManager(dataSource), dataSource));
    assertNotEquals("NONE", state(collector).get("failureCode"));
  }

  @Test
  void unsupportedOrDifferentDatasourceDoesNotPretendRuntimeProof() {
    var collector = collector(() -> 0);
    assertFalse(
        collector.verifyRuntime(mock(PlatformTransactionManager.class), mock(DataSource.class)));
    assertThrows(
        ProductMetricServiceTransactionObservationSupport.ObservationIncompleteException.class,
        collector::assertHealthyAfterClose);
    var mismatch = collector(() -> 0);
    assertFalse(
        mismatch.verifyRuntime(
            new DataSourceTransactionManager(mock(DataSource.class)), mock(DataSource.class)));
    assertEquals(
        false, map(mismatch.snapshot().get("runtimeIdentityFacts")).get("runtimeVerified"));
    java.util.concurrent.atomic.AtomicBoolean metadataFails =
        new java.util.concurrent.atomic.AtomicBoolean();
    DataSource dataSource = mock(DataSource.class);
    DataSourceTransactionManager throwingManager =
        new DataSourceTransactionManager(dataSource) {
          /** Simulate a cold metadata failure without ever requesting a connection. */
          @Override
          public DataSource getDataSource() {
            if (metadataFails.get()) throw new AssertionError("cold metadata");
            return super.getDataSource();
          }
        };
    metadataFails.set(true);
    var guarded = collector(() -> 0);
    assertFalse(guarded.verifyRuntime(throwingManager, dataSource));
    assertEquals(false, map(guarded.snapshot().get("runtimeIdentityFacts")).get("runtimeVerified"));
    assertNotEquals("NONE", state(guarded).get("failureCode"));
  }

  @Test
  void sixZeroMarkersProduceOneFullCohortAndFiveZeroEdgesForNormalOrThrew() {
    for (boolean threw : List.of(false, true)) {
      Fixture fixture = new Fixture(() -> -100L);
      fixture.enter();
      TransactionSynchronization synchronization = fixture.mapper();
      synchronization.beforeCommit(false);
      synchronization.afterCommit();
      synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
      fixture.exit(threw);
      Map<String, Object> row = usedRow(fixture.collector);
      assertEquals(threw ? "THREW" : "NORMAL", row.get("outcome"));
      assertEquals(1L, row.get("fullLogicalCalls"));
      assertEquals(0L, row.get("partialLogicalCalls"));
      assertEquals(0L, map(row.get("fullWholeStats")).get("sumNs"));
      for (Object stats : map(row.get("segments")).values()) {
        assertEquals(1L, map(stats).get("timedCalls"));
        assertEquals(0L, map(stats).get("sumNs"));
      }
      assertEquals("COMPLETE", qualification(fixture.collector).get("fullCoverage"));
      fixture.collector.assertHealthyAfterClose();
      assertTrue(fixture.timing.matchesCurrentCallToken(fixture.oldTag));
      fixture.closeOld();
    }
  }

  @Test
  void logicalEdgesShareTheFullWholeDenominatorAndSumExactlyOnce() {
    AtomicLong clock = new AtomicLong(-20);
    Fixture fixture = new Fixture(() -> clock.getAndAdd(10));
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    synchronization.beforeCommit(false);
    synchronization.afterCommit();
    synchronization.afterCompletion(0);
    fixture.exit(false);
    Map<String, Object> row = usedRow(fixture.collector);
    assertEquals(50L, map(row.get("wholeStats")).get("sumNs"));
    long segmentSum = 0;
    for (Object stats : map(row.get("segments")).values()) {
      segmentSum += (Long) map(stats).get("sumNs");
      assertEquals(1L, map(stats).get("timedCalls"));
    }
    assertEquals(map(row.get("fullWholeStats")).get("sumNs"), segmentSum);
    fixture.closeOld();
  }

  @Test
  void rollbackAndMissingCommitMarkersAreHealthyPartialNotBusinessSuccess() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    fixture.mapper().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    fixture.exit(true);
    Map<String, Object> row = usedRow(fixture.collector);
    assertEquals(1L, row.get("partialLogicalCalls"));
    assertEquals(0L, row.get("fullLogicalCalls"));
    assertEquals(1L, map(row.get("completionStatusCounts")).get("ROLLED_BACK"));
    assertEquals(0L, map(map(row.get("segments")).get("B_A")).get("timedCalls"));
    fixture.collector.assertHealthyAfterClose();
    fixture.closeOld();
  }

  @Test
  void noMapperHasOnlySAndEAndNoFabricatedMissingMarkerSamples() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    fixture.exit(false);
    Map<String, Object> row = usedRow(fixture.collector);
    assertEquals(1L, list(row.get("markerMaskBuckets")).get(33));
    assertEquals(1L, map(row.get("completionStatusCounts")).get("NOT_OBSERVED"));
    assertEquals(0L, counter(fixture.collector, "registrationAttempts"));
    assertEquals("UNAVAILABLE", qualification(fixture.collector).get("fullCoverage"));
    fixture.collector.assertHealthyAfterClose();
    fixture.closeOld();
  }

  @Test
  void repeatedRecognizedMapperCallsRegisterOnlyOneSynchronization() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    fixture.collector.mapperEnter(fixture.timing, "ADVANCE_BY");
    fixture.collector.mapperEnter(fixture.timing, "LOCK_STAT");
    assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    assertEquals(1L, counter(fixture.collector, "registrationAttempts"));
    synchronization.afterCompletion(0);
    fixture.exit(false);
    fixture.closeOld();
  }

  @Test
  void outerTransactionOrSynchronizationAtSIsSkippedWithoutNewClock() {
    for (boolean actualTransaction : List.of(false, true)) {
      AtomicInteger reads = new AtomicInteger();
      Fixture fixture = new Fixture(() -> reads.incrementAndGet());
      if (actualTransaction) TransactionSynchronizationManager.setActualTransactionActive(true);
      else TransactionSynchronizationManager.initSynchronization();
      fixture.enter();
      fixture.exit(false);
      assertEquals(0, reads.get());
      assertEquals(1L, counter(fixture.collector, "skippedOuterTransaction"));
      assertEquals(0L, state(fixture.collector).get("activeServices"));
      fixture.closeOld();
      clearSyntheticTransactionState();
    }
  }

  @Test
  void mapperReadOnlyOrMissingActualTransactionSkipsRegistrationWithoutInventingM() {
    for (int gate = 0; gate < 3; gate++) {
      Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
      fixture.enter();
      if (gate != 0) TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(gate != 1);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(gate == 2);
      fixture.collector.mapperEnter(fixture.timing, "ADVANCE");
      fixture.exit(false);
      assertEquals(0L, counter(fixture.collector, "registrationAttempts"));
      assertEquals(0L, map(usedRow(fixture.collector).get("markerSeenCounts")).get("M"));
      fixture.closeOld();
      clearSyntheticTransactionState();
    }
  }

  @Test
  void serviceExitWithoutCLeavesOnePendingAndLaterCallsOnlySkipUntilLateC() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization oldSynchronization = fixture.mapper();
    fixture.exit(false);
    fixture.closeOld();
    clearSyntheticTransactionState();
    Map<String, Object> beforeLate = usedRow(fixture.collector);
    assertEquals(1L, state(fixture.collector).get("pendingRegistrations"));
    assertEquals(0L, state(fixture.collector).get("activeServices"));
    assertThrows(
        ProductMetricServiceTransactionObservationSupport.ObservationIncompleteException.class,
        fixture.collector::assertHealthyAfterClose);
    for (int attempt = 0; attempt < 4; attempt++) {
      fixture.enter();
      fixture.exit(false);
      fixture.closeOld();
    }
    assertEquals(4L, counter(fixture.collector, "skippedPendingRegistration"));
    oldSynchronization.afterCompletion(0);
    assertEquals(beforeLate, usedRow(fixture.collector));
    assertEquals(1L, counter(fixture.collector, "lateCompletions"));
    assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
    fixture.collector.assertHealthyAfterClose();
  }

  @Test
  void duplicateOldCDoesNotReleaseANewReservationOrTouchItsActiveFrame() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization oldSynchronization = fixture.mapper();
    oldSynchronization.afterCompletion(0);
    fixture.exit(false);
    fixture.closeOld();
    clearSyntheticTransactionState();
    fixture.enter();
    TransactionSynchronization nextSynchronization = fixture.mapper();
    oldSynchronization.afterCompletion(0);
    assertEquals(1L, state(fixture.collector).get("pendingRegistrations"));
    assertEquals(1L, state(fixture.collector).get("activeServices"));
    assertEquals(1L, counter(fixture.collector, "duplicateCallbacks"));
    nextSynchronization.afterCompletion(0);
    fixture.exit(false);
    fixture.closeOld();
    assertEquals(2L, counter(fixture.collector, "reservationsReleased"));
  }

  @Test
  void cBeforeEReleasesReservationButDoesNotAdmitAConcurrentServiceFrame() throws Exception {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    fixture.mapper().afterCompletion(0);
    Thread contender =
        new Thread(
            () -> {
              var timing = timing();
              var oldTag = timing.serviceEnter(MEASUREMENT, "applyBatch", 1);
              var token =
                  fixture.collector.serviceEnter(timing, oldTag, MEASUREMENT, "applyBatch", 1);
              fixture.collector.serviceExit(token, oldTag, false, MEASUREMENT);
              timing.serviceExit(oldTag, false, MEASUREMENT);
            });
    contender.start();
    contender.join(5000);
    assertFalse(contender.isAlive());
    assertEquals(1L, state(fixture.collector).get("activeServices"));
    assertEquals(1L, counter(fixture.collector, "skippedConcurrentService"));
    assertEquals(1L, counter(fixture.collector, "framesEntered"));
    assertEquals(0L, counter(fixture.collector, "framesCompleted"));
    assertTrue(
        list(fixture.collector.snapshot().get("cells")).stream()
            .allMatch(row -> map(map(row).get("wholeStats")).get("observations").equals(0L)));
    fixture.exit(false);
    assertEquals(1L, counter(fixture.collector, "framesCompleted"));
    assertEquals(0L, state(fixture.collector).get("activeServices"));
    assertEquals(1L, map(usedRow(fixture.collector).get("wholeStats")).get("observations"));
    fixture.closeOld();
  }

  @Test
  void completionClockFailureStillReleasesCapacityAndDoesNotClaimCValid() {
    AtomicInteger reads = new AtomicInteger();
    Fixture fixture =
        new Fixture(
            () -> {
              if (reads.incrementAndGet() == 3) throw new AssertionError("C");
              return 0;
            });
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    synchronization.afterCompletion(0);
    fixture.exit(false);
    assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
    assertEquals(1L, counter(fixture.collector, "clockFailures"));
    assertEquals(0L, map(usedRow(fixture.collector).get("markerSeenCounts")).get("C"));
    fixture.closeOld();
  }

  @Test
  void completionCounterOverflowStillReleasesItsOwnReservation() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    fixture.exit(false);
    fixture.closeOld();
    long[] counters = (long[]) ReflectionTestUtils.getField(fixture.collector, "counters");
    counters[16] = Long.MAX_VALUE;
    assertDoesNotThrow(() -> synchronization.afterCompletion(0));
    assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
    assertNotEquals("NONE", state(fixture.collector).get("failureCode"));
  }

  @Test
  void failedMBeforeSubmissionReleasesReservationEvenWhenItsCounterOverflows() {
    for (int fullCounter : new int[] {15, 22}) {
      AtomicInteger reads = new AtomicInteger();
      Fixture fixture =
          new Fixture(
              () -> {
                if (reads.incrementAndGet() == 2) throw new AssertionError("M");
                return 0;
              });
      fixture.enter();
      long[] counters = (long[]) ReflectionTestUtils.getField(fixture.collector, "counters");
      counters[fullCounter] = Long.MAX_VALUE;
      TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertDoesNotThrow(() -> fixture.collector.mapperEnter(fixture.timing, "ADVANCE"));
      assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
      assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
      fixture.exit(false);
      fixture.closeOld();
    }
  }

  @Test
  void registerThrowableKeepsOneUncertainReservationWithoutRetry() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try (MockedStatic<TransactionSynchronizationManager> manager =
        mockStatic(TransactionSynchronizationManager.class, CALLS_REAL_METHODS)) {
      manager
          .when(
              () ->
                  TransactionSynchronizationManager.registerSynchronization(
                      any(TransactionSynchronization.class)))
          .thenThrow(new AssertionError("registration uncertain"));
      assertDoesNotThrow(() -> fixture.collector.mapperEnter(fixture.timing, "ADVANCE"));
      fixture.collector.mapperEnter(fixture.timing, "ADVANCE");
      manager.verify(
          () ->
              TransactionSynchronizationManager.registerSynchronization(
                  any(TransactionSynchronization.class)),
          times(1));
    }
    assertEquals("UNCERTAIN", state(fixture.collector).get("pendingRegistrationState"));
    fixture.exit(false);
    fixture.closeOld();
    assertEquals(1L, state(fixture.collector).get("pendingRegistrations"));
  }

  @Test
  void confirmedBookkeepingOverflowNeverReclassifiesSuccessfulRegistrationAsUncertain() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    long[] counters = (long[]) ReflectionTestUtils.getField(fixture.collector, "counters");
    counters[12] = Long.MAX_VALUE;
    TransactionSynchronization synchronization = fixture.mapper();
    assertEquals("CONFIRMED", state(fixture.collector).get("pendingRegistrationState"));
    assertEquals("COUNT_OVERFLOW", state(fixture.collector).get("failureCode"));
    assertEquals(0L, counter(fixture.collector, "registrationsUncertain"));
    fixture.collector.mapperEnter(fixture.timing, "ADVANCE_BY");
    assertEquals(1L, counter(fixture.collector, "registrationAttempts"));
    assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    synchronization.beforeCommit(false);
    synchronization.afterCommit();
    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
    assertEquals(1L, counter(fixture.collector, "reservationsReleased"));
    fixture.exit(false);
    Map<String, Object> row = usedRow(fixture.collector);
    assertEquals(1L, map(row.get("registrationResultCounts")).get("CONFIRMED"));
    assertEquals(0L, map(row.get("registrationResultCounts")).get("UNCERTAIN"));
    assertEquals(0L, row.get("fullLogicalCalls"));
    assertEquals(1L, row.get("partialLogicalCalls"));
    assertEquals(0L, state(fixture.collector).get("activeServices"));
    fixture.closeOld();
  }

  @Test
  void nestedSuppressionNeverRegistersForOuterAndItsExitRestoresDepth() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    var nested =
        fixture.collector.serviceEnter(
            fixture.timing, fixture.oldTag, MEASUREMENT, "applyBatch", 1);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    fixture.collector.mapperEnter(fixture.timing, "ADVANCE");
    assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    fixture.collector.serviceExit(nested, fixture.oldTag, true, MEASUREMENT);
    assertEquals(0, state(fixture.collector).get("suppressionDepth"));
    fixture.collector.mapperEnter(fixture.timing, "ADVANCE");
    TransactionSynchronization synchronization =
        TransactionSynchronizationManager.getSynchronizations().get(0);
    synchronization.beforeCommit(false);
    synchronization.afterCommit();
    synchronization.afterCompletion(0);
    fixture.exit(false);
    assertEquals(0L, usedRow(fixture.collector).get("fullLogicalCalls"));
    assertEquals("NONE", state(fixture.collector).get("failureCode"));
    fixture.closeOld();
  }

  @Test
  void wrongAndDoubleServiceTokensCannotClearOriginalAssociation() throws Exception {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    var foreign = new Fixture(() -> 0);
    foreign.enter();
    fixture.collector.serviceExit(foreign.token, foreign.oldTag, false, MEASUREMENT);
    Thread wrongThread =
        new Thread(
            () -> fixture.collector.serviceExit(fixture.token, fixture.oldTag, false, MEASUREMENT));
    wrongThread.start();
    wrongThread.join(5000);
    assertFalse(wrongThread.isAlive());
    assertEquals(1L, state(fixture.collector).get("activeServices"));
    fixture.exit(false);
    fixture.exit(false);
    assertEquals(0L, state(fixture.collector).get("activeServices"));
    foreign.exit(false);
    foreign.closeOld();
    fixture.closeOld();
  }

  @Test
  void wrongThreadCReleasesOnlyItsCapacityAndDoesNotProduceFullCoverage() throws Exception {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    Thread callback = new Thread(() -> synchronization.afterCompletion(0));
    callback.start();
    callback.join(5000);
    assertFalse(callback.isAlive());
    assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
    assertEquals(1L, counter(fixture.collector, "wrongThreadCallbacks"));
    fixture.exit(false);
    assertEquals(0L, usedRow(fixture.collector).get("fullLogicalCalls"));
    fixture.closeOld();
  }

  @Test
  void failedCallbackClockStillDetectsDuplicateWithoutASecondClockRead() {
    AtomicInteger reads = new AtomicInteger();
    Fixture fixture =
        new Fixture(
            () -> {
              if (reads.incrementAndGet() >= 3) throw new AssertionError();
              return 0;
            });
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    synchronization.beforeCommit(false);
    int prior = reads.get();
    synchronization.beforeCommit(false);
    assertEquals(prior, reads.get());
    assertEquals(1L, counter(fixture.collector, "duplicateCallbacks"));
    synchronization.afterCompletion(0);
    fixture.exit(false);
    fixture.closeOld();
  }

  @Test
  void stageCrossingKeepsWholeButExcludesAllFullEdges() {
    Fixture fixture = new Fixture(new AtomicLong()::getAndIncrement);
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    synchronization.beforeCommit(false);
    synchronization.afterCommit();
    synchronization.afterCompletion(0);
    fixture.collector.serviceExit(
        fixture.token, fixture.oldTag, false, ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN);
    assertEquals(1L, usedRow(fixture.collector).get("crossStageCalls"));
    assertEquals(0L, usedRow(fixture.collector).get("fullLogicalCalls"));
    fixture.collector.assertHealthyAfterClose();
    fixture.closeOld();
  }

  @Test
  void backwardAndCheckedSubtractionOverflowFailWithoutFabricatingAHealthyCell() {
    for (long[] timestamps :
        List.of(new long[] {10, 9}, new long[] {Long.MIN_VALUE, Long.MAX_VALUE})) {
      AtomicInteger reads = new AtomicInteger();
      Fixture fixture = new Fixture(() -> timestamps[Math.min(reads.getAndIncrement(), 1)]);
      fixture.enter();
      fixture.exit(false);
      assertEquals("CLOCK_INVALID", state(fixture.collector).get("failureCode"));
      assertEquals(1L, counter(fixture.collector, "clockFailures"));
      assertEquals(0L, counter(fixture.collector, "counterOverflow"));
      assertEquals(0L, state(fixture.collector).get("activeServices"));
      assertTrue(
          list(fixture.collector.snapshot().get("cells")).stream()
              .allMatch(row -> map(map(row).get("wholeStats")).get("observations").equals(0L)));
      fixture.closeOld();
    }
  }

  @Test
  void aggregateOverflowLeavesWholeMaskAndClassificationUncommitted() {
    Fixture fixture = new Fixture(() -> 0);
    fixture.enter();
    long[][][] stats = (long[][][]) ReflectionTestUtils.getField(fixture.collector, "statistics");
    int coordinate = ((MEASUREMENT.ordinal() * 2 + 1) * 4) * 2;
    stats[coordinate][0][0] = Long.MAX_VALUE;
    fixture.exit(false);
    Map<String, Object> row =
        list(fixture.collector.snapshot().get("cells")).stream()
            .map(ProductMetricServiceTransactionObservationSupportTest::map)
            .filter(
                candidate ->
                    candidate.get("entryStage").equals(MEASUREMENT.name())
                        && candidate.get("operation").equals("applyBatch")
                        && candidate.get("inputSize").equals(1)
                        && candidate.get("outcome").equals("NORMAL"))
            .findFirst()
            .orElseThrow();
    assertEquals(0L, row.get("partialLogicalCalls"));
    assertEquals(
        0L, list(row.get("markerMaskBuckets")).stream().mapToLong(value -> (Long) value).sum());
    assertEquals(0L, state(fixture.collector).get("activeServices"));
    fixture.closeOld();
  }

  @Test
  void snapshotDoesNotExposeArraysOrReadClockAndFailureCannotBeReset() {
    Fixture fixture = new Fixture(() -> 0);
    fixture.enter();
    fixture.exit(false);
    fixture.closeOld();
    Map<String, Object> snapshot = fixture.collector.snapshot();
    list(map(list(snapshot.get("cells")).get(0)).get("markerMaskBuckets")).set(0, -99L);
    assertEquals(
        0L,
        list(map(list(fixture.collector.snapshot().get("cells")).get(0)).get("markerMaskBuckets"))
            .get(0));
    fixture.collector.recordObserverFailure();
    String failure = (String) state(fixture.collector).get("failureCode");
    fixture.collector.recordObserverFailure();
    assertEquals(failure, state(fixture.collector).get("failureCode"));
    assertThrows(IllegalStateException.class, fixture.collector::assertHealthyAfterClose);
  }

  @Test
  void explicitObserverFailureNeverEscapesWhenItsOwnCounterIsFull() {
    var collector = collector(() -> 0);
    long[] counters = (long[]) ReflectionTestUtils.getField(collector, "counters");
    counters[25] = Long.MAX_VALUE;
    assertDoesNotThrow(collector::recordObserverFailure);
    assertNotEquals("NONE", state(collector).get("failureCode"));
  }

  @Test
  void primitiveArrayPayloadFitsBudgetAndNoBusinessPayloadIsRetained() {
    var collector = collector(() -> 0);
    long bytes = retainedPrimitiveArrayBytes(collector);
    assertEquals(184888L, bytes);
    assertTrue(bytes + 1024 < 262144);
    assertNull(ReflectionTestUtils.getField(collector, "active"));
    assertNull(ReflectionTestUtils.getField(collector, "pending"));
    for (java.lang.reflect.Field field : collector.getClass().getDeclaredFields()) {
      assertFalse(field.getType().getName().contains("Connection"));
      assertFalse(field.getType().getName().contains("ProductMetricEvent"));
    }
  }

  @Test
  void histogramUpperBoundsAreInclusiveAndOverflowUsesOnlyLastBucket() {
    long[] durations = {
      0L,
      100000L,
      500000L,
      1000000L,
      2000000L,
      5000000L,
      10000000L,
      20000000L,
      50000000L,
      100000000L,
      200000000L,
      500000000L,
      1000000000L,
      3000000000L,
      3000000001L
    };
    for (int durationIndex = 0; durationIndex < durations.length; durationIndex++) {
      AtomicInteger reads = new AtomicInteger();
      long duration = durations[durationIndex];
      Fixture fixture = new Fixture(() -> reads.getAndIncrement() == 0 ? 0 : duration);
      fixture.enter();
      fixture.exit(false);
      List<Object> buckets = list(map(usedRow(fixture.collector).get("wholeStats")).get("buckets"));
      int expectedBucket = durationIndex == 0 ? 0 : durationIndex - 1;
      for (int bucket = 0; bucket < 14; bucket++)
        assertEquals(bucket == expectedBucket ? 1L : 0L, buckets.get(bucket));
      assertEquals(duration, map(usedRow(fixture.collector).get("wholeStats")).get("sumNs"));
      fixture.closeOld();
    }
  }

  @Test
  void defaultSynchronizationCallbacksDoNothingAndOrderDoesNotReadClock() {
    AtomicInteger reads = new AtomicInteger();
    Fixture fixture = new Fixture(() -> reads.incrementAndGet());
    fixture.enter();
    TransactionSynchronization synchronization = fixture.mapper();
    int before = reads.get();
    assertEquals(Integer.MAX_VALUE, synchronization.getOrder());
    synchronization.suspend();
    synchronization.resume();
    synchronization.flush();
    synchronization.beforeCompletion();
    assertEquals(before, reads.get());
    synchronization.afterCompletion(0);
    fixture.exit(false);
    fixture.closeOld();
  }

  @Test
  void serviceClockFaultsStillClearTheirOwnShortLivedTagAndThreadLocal() {
    for (int failingRead = 1; failingRead <= 2; failingRead++) {
      AtomicInteger reads = new AtomicInteger();
      int selectedRead = failingRead;
      Fixture fixture =
          new Fixture(
              () -> {
                if (reads.incrementAndGet() == selectedRead) throw new AssertionError("clock");
                return 0;
              });
      fixture.enter();
      fixture.exit(false);
      assertEquals(0L, state(fixture.collector).get("activeServices"));
      assertEquals(0L, state(fixture.collector).get("framesOutstanding"));
      assertNull(ReflectionTestUtils.getField(fixture.token, "oldTag"));
      assertNull(ReflectionTestUtils.getField(fixture.token, "timing"));
      assertNull(ReflectionTestUtils.getField(fixture.token, "frame"));
      ThreadLocal<?> current =
          (ThreadLocal<?>) ReflectionTestUtils.getField(fixture.collector, "current");
      assertNull(current.get());
      assertEquals(1L, counter(fixture.collector, "clockFailures"));
      fixture.closeOld();
    }
  }

  @Test
  void runtimeSkipAndInvalidCoordinatesDoNotReadNewClockOrAllocateActiveFrames() {
    AtomicInteger reads = new AtomicInteger();
    var collector = collector(() -> reads.incrementAndGet());
    var timing = timing();
    var oldTag = timing.serviceEnter(MEASUREMENT, "applyBatch", 1);
    var skipped = collector.serviceEnter(timing, oldTag, MEASUREMENT, "applyBatch", 1);
    collector.serviceExit(skipped, oldTag, false, MEASUREMENT);
    var invalid = collector.serviceEnter(timing, oldTag, MEASUREMENT, "unknown", 1);
    collector.serviceExit(invalid, oldTag, false, MEASUREMENT);
    assertEquals(0, reads.get());
    assertEquals(1L, counter(collector, "skippedRuntime"));
    assertEquals(1L, counter(collector, "unclassifiedServices"));
    assertEquals(0L, state(collector).get("activeServices"));
    timing.serviceExit(oldTag, false, MEASUREMENT);
  }

  @Test
  void stageSupplierThrowableAtEveryMarkerIsIsolatedAndCStillFreesCapacity() {
    for (int markerRead = 1; markerRead <= 6; markerRead++) {
      AtomicInteger reads = new AtomicInteger();
      int selectedRead = markerRead;
      Fixture fixture =
          new Fixture(
              () -> 0,
              () -> {
                if (reads.incrementAndGet() == selectedRead) throw new AssertionError("stage");
                return MEASUREMENT;
              });
      fixture.enter();
      TransactionSynchronization synchronization;
      if (markerRead == 2) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        fixture.collector.mapperEnter(fixture.timing, "ADVANCE");
        synchronization = null;
      } else synchronization = fixture.mapper();
      if (markerRead == 2) {
        assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
      } else {
        synchronization.beforeCommit(false);
        synchronization.afterCommit();
        synchronization.afterCompletion(0);
      }
      fixture.exit(false);
      assertEquals(0L, state(fixture.collector).get("pendingRegistrations"));
      assertEquals(0L, state(fixture.collector).get("activeServices"));
      assertNotEquals("NONE", state(fixture.collector).get("failureCode"));
      fixture.closeOld();
    }
  }

  @Test
  void skippedFreshThreadNeverReadsOrInitializesTheNewThreadLocal() throws Exception {
    for (boolean verified : List.of(false, true)) {
      AtomicInteger reads = new AtomicInteger();
      AtomicInteger initializations = new AtomicInteger();
      var collector =
          collector(
              () -> {
                throw new AssertionError("skip clock");
              });
      ThreadLocal<Object> tracking =
          new ThreadLocal<Object>() {
            /** Detect every lookup, including an initially null entry. */
            @Override
            public Object get() {
              reads.incrementAndGet();
              return super.get();
            }

            /** Detect Java's otherwise hidden first-get allocation path. */
            @Override
            protected Object initialValue() {
              initializations.incrementAndGet();
              return null;
            }
          };
      ReflectionTestUtils.setField(collector, "current", tracking);
      if (verified) {
        DataSource dataSource = mock(DataSource.class);
        collector.verifyRuntime(new DataSourceTransactionManager(dataSource), dataSource);
      }
      java.util.concurrent.atomic.AtomicReference<Throwable> failure =
          new java.util.concurrent.atomic.AtomicReference<>();
      Thread fresh =
          new Thread(
              () -> {
                try {
                  var timing = timing();
                  var oldTag = timing.serviceEnter(MEASUREMENT, "applyBatch", 1);
                  if (verified) TransactionSynchronizationManager.setActualTransactionActive(true);
                  var token = collector.serviceEnter(timing, oldTag, MEASUREMENT, "applyBatch", 1);
                  collector.mapperEnter(timing, "ADVANCE");
                  collector.serviceExit(token, oldTag, false, MEASUREMENT);
                  timing.serviceExit(oldTag, false, MEASUREMENT);
                } catch (Throwable unexpected) {
                  failure.set(unexpected);
                } finally {
                  TransactionSynchronizationManager.setActualTransactionActive(false);
                }
              });
      fresh.start();
      fresh.join(5000);
      assertFalse(fresh.isAlive());
      assertNull(failure.get());
      assertEquals(0, reads.get());
      assertEquals(0, initializations.get());
    }
  }

  /** Local fixture never calls a manager transaction method or a datasource connection. */
  private static final class Fixture {
    /** New enabled collector sharing only the controlled clock. */
    private final ProductMetricServiceTransactionObservationSupport collector;

    /** Original timing helper owns and alone consumes the opaque token. */
    private final ProductMetricApplicationTimingSupport timing = timing();

    /** Current original invocation identity. */
    private ProductMetricApplicationTimingSupport.CallToken oldTag;

    /** Current new diagnostic capability. */
    private ProductMetricServiceTransactionObservationSupport.ServiceToken token;

    /** Verify only cold original DSTM/DS identity, never a transaction. */
    private Fixture(LongSupplier clock) {
      this(clock, () -> MEASUREMENT);
    }

    /** Supply a controlled stage failure without changing original timing metadata. */
    private Fixture(LongSupplier clock, Supplier<ProductMetricPipelineLoadSupport.Stage> stage) {
      collector = new ProductMetricServiceTransactionObservationSupport(clock, stage);
      DataSource dataSource = mock(DataSource.class);
      assertTrue(collector.verifyRuntime(new DataSourceTransactionManager(dataSource), dataSource));
    }

    /** Begin an original synthetic timing call before the new S hook. */
    private void enter() {
      oldTag = timing.serviceEnter(MEASUREMENT, "applyBatch", 1);
      token = collector.serviceEnter(timing, oldTag, MEASUREMENT, "applyBatch", 1);
    }

    /** Create only controlled Spring thread metadata, then obtain the one registered observer. */
    private TransactionSynchronization mapper() {
      if (!TransactionSynchronizationManager.isSynchronizationActive())
        TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      collector.mapperEnter(timing, "ADVANCE");
      return TransactionSynchronizationManager.getSynchronizations().get(0);
    }

    /** Record the new exit before its original token is consumed. */
    private void exit(boolean threw) {
      collector.serviceExit(token, oldTag, threw, MEASUREMENT);
    }

    /** Only the original timer consumes its original token. */
    private void closeOld() {
      timing.serviceExit(oldTag, false, MEASUREMENT);
      if (TransactionSynchronizationManager.isSynchronizationActive())
        TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
  }

  /**
   * Reflect only owned primitive arrays, avoiding arbitrary external objects and identity cycles.
   */
  private static long retainedPrimitiveArrayBytes(Object collector) {
    long bytes = 0;
    try {
      for (java.lang.reflect.Field field : collector.getClass().getDeclaredFields()) {
        if (field.getType().isArray()) {
          field.setAccessible(true);
          bytes += arrayBytes(field.get(collector));
        }
      }
      return bytes;
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  /** Count nested array primitive payloads; label/reference arrays contribute no primitive leaf. */
  private static long arrayBytes(Object array) {
    if (array instanceof long[]) return ((long[]) array).length * Long.BYTES;
    if (array instanceof Object[]) {
      long bytes = 0;
      for (Object child : (Object[]) array)
        if (child != null && child.getClass().isArray()) bytes += arrayBytes(child);
      return bytes;
    }
    return 0;
  }

  /** Keep the original timing clock independent so new clock tests have exact reads. */
  private static ProductMetricApplicationTimingSupport timing() {
    return new ProductMetricApplicationTimingSupport(
        IDENTITY, ProductMetricPipelineLoadSupport.Mode.BATCH1, () -> 0);
  }

  /** Construct a collector with a stable original entry stage. */
  private static ProductMetricServiceTransactionObservationSupport collector(LongSupplier clock) {
    return new ProductMetricServiceTransactionObservationSupport(clock, () -> MEASUREMENT);
  }

  /** Typed cold snapshot state lookup. */
  private static Map<String, Object> state(
      ProductMetricServiceTransactionObservationSupport collector) {
    return map(collector.snapshot().get("collectorState"));
  }

  /** Typed cold qualification lookup. */
  private static Map<String, Object> qualification(
      ProductMetricServiceTransactionObservationSupport collector) {
    return map(collector.snapshot().get("qualification"));
  }

  /** Return a named fixed counter. */
  private static long counter(
      ProductMetricServiceTransactionObservationSupport collector, String name) {
    return (Long) map(state(collector).get("counters")).get(name);
  }

  /** Find the sole E-frozen cohort in a one-call fixture. */
  private static Map<String, Object> usedRow(
      ProductMetricServiceTransactionObservationSupport collector) {
    return list(collector.snapshot().get("cells")).stream()
        .map(ProductMetricServiceTransactionObservationSupportTest::map)
        .filter(row -> !map(row.get("wholeStats")).get("observations").equals(0L))
        .findFirst()
        .orElseThrow();
  }

  /** Cast only the fixed collector's documented map structure. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  /** Cast only the fixed collector's documented list structure. */
  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }
}
