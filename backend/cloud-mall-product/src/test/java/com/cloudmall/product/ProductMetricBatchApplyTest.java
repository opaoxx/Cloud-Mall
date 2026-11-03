package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricApplySnapshotDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.ProductMetricControlPO;
import com.cloudmall.product.domain.po.ProductOnlineStatPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real Spring proxy transaction boundaries over mocked SQL; no database lock or durability claim.
 */
class ProductMetricBatchApplyTest {
  @Test
  void batchAnnotationIsOneNewRepeatableReadTransactionAndSingleContractStaysOriginal()
      throws Exception {
    Transactional batch =
        ProductMetricService.class
            .getMethod("applyBatch", List.class)
            .getAnnotation(Transactional.class);
    assertEquals(Propagation.REQUIRES_NEW, batch.propagation());
    assertEquals(Isolation.REPEATABLE_READ, batch.isolation());
    assertEquals(3, batch.timeout());
    Transactional single =
        ProductMetricService.class
            .getMethod("apply", ProductMetricEventDTO.class)
            .getAnnotation(Transactional.class);
    assertEquals(Propagation.REQUIRES_NEW, single.propagation());
    assertEquals(Isolation.DEFAULT, single.isolation());
    assertEquals(3, single.timeout());
  }

  @Test
  void invalidBatchIsRejectedBeforeAnyMapperOperation() {
    ProductMetricEventDTO event = event(41, false);
    List<List<ProductMetricEventDTO>> invalid = new ArrayList<>();
    invalid.add(null);
    invalid.add(List.of());
    invalid.add(Arrays.asList(event, null));
    invalid.add(List.of(event, event, event, event, event));
    ProductMetricEventDTO malformed = copy(event);
    malformed.productId = -1L;
    invalid.add(List.of(malformed));
    for (List<ProductMetricEventDTO> batch : invalid) {
      Fixture fixture = new Fixture();
      assertThrows(RuntimeException.class, () -> fixture.service.applyBatch(batch));
      verifyNoInteractions(fixture.mapper);
      fixture.assertTransactions(0, 1);
    }
  }

  @Test
  void distinctFourUseAscendingStatAnchorsInputDmlOrderAndOneCommit() {
    Fixture fixture = new Fixture();
    List<ProductMetricEventDTO> events =
        List.of(event(44, false), event(41, true), event(43, false), event(42, true));
    ProductMetricBatchApplyResultDTO result = fixture.service.applyBatch(events);
    assertEquals(4, result.appliedCount);
    assertEquals(
        List.of("APPLIED", "APPLIED", "APPLIED", "APPLIED"),
        result.events.stream().map(row -> row.outcome).toList());
    var order = inOrder(fixture.mapper);
    for (ProductMetricEventDTO event : events)
      order
          .verify(fixture.mapper)
          .initialApplySnapshot(event.eventId, event.productId, event.skuId);
    for (long productId : List.of(41L, 42L, 43L, 44L)) {
      order.verify(fixture.mapper).ensureStat(eq(productId), any(LocalDateTime.class));
      order.verify(fixture.mapper).stat(productId, true);
    }
    for (ProductMetricEventDTO event : events) {
      order
          .verify(fixture.mapper)
          .insertApplied(
              argThat(actual -> actual != event && actual.eventId.equals(event.eventId)),
              eq(ProductMetricEvents.fingerprint(event)),
              eq(1L),
              any(LocalDateTime.class));
      order
          .verify(fixture.mapper)
          .updateStat(any(ProductOnlineStatPO.class), eq(event.eventId), any(LocalDateTime.class));
    }
    order.verify(fixture.mapper).control(true);
    order.verify(fixture.mapper).advanceBy(4);
    order.verifyNoMoreInteractions();
    assertEquals(26, fixture.businessSqlUnits);
    fixture.assertTransactions(1, 0);
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.events.add(new ProductMetricBatchEventResultDTO(4, "id", "fp", "APPLIED")));
  }

  @Test
  void sameProductRetainsInputVersionsAndEveryDuplicateInputPosition() {
    Fixture fixture = new Fixture();
    fixture.stats.put(41L, stat(41L, 2, 3, 4, 7));
    ProductMetricEventDTO first = event(41, false);
    ProductMetricEventDTO second = event(41, true);
    ProductMetricBatchApplyResultDTO result =
        fixture.service.applyBatch(List.of(first, second, copy(first)));
    assertEquals(2, result.appliedCount);
    assertEquals(
        List.of("APPLIED", "APPLIED", "DUPLICATE"),
        result.events.stream().map(row -> row.outcome).toList());
    assertEquals(List.of(0, 1, 2), result.events.stream().map(row -> row.index).toList());
    assertEquals(first.eventId, result.events.get(2).eventId);
    assertEquals(List.of(8L, 9L), fixture.writes.stream().map(row -> row.version()).toList());
    assertEquals(
        List.of(first.eventId, second.eventId),
        fixture.writes.stream().map(row -> row.eventId()).toList());
    ProductOnlineStatPO updated = fixture.stats.get(41L);
    assertEquals(3L, updated.viewCount);
    assertEquals(4L, updated.searchCount);
    assertEquals(4L, updated.soldCount);
    assertEquals(47L, updated.hotScore);
    assertEquals(9L, updated.metricVersion);
    verify(fixture.mapper).ensureStat(eq(41L), any(LocalDateTime.class));
    verify(fixture.mapper).stat(41L, true);
    verify(fixture.mapper).advanceBy(2);
    fixture.assertTransactions(1, 0);
  }

  @Test
  void mixedViewSearchAndSaleKeepOriginalIntegerScoreAndPerEventVersions() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO sale = new ProductMetricEventDTO();
    sale.payloadVersion = 1;
    sale.seriesId = ProductMetricEvents.SERIES;
    sale.eventType = "STOCK_SALE_CONFIRMED";
    sale.producer = "STOCK";
    sale.productId = 41L;
    sale.skuId = 71L;
    sale.quantity = 2;
    sale.orderNo = "batch-sale";
    sale.sourceRevision = 5L;
    sale.sourceRef = "stock_flow:batch-sale:CONFIRM:71";
    sale.occurredAt = "2026-10-16T10:00:00.000+08:00";
    sale.eventId = ProductMetricEvents.identity(sale);
    ProductMetricBatchApplyResultDTO result =
        fixture.service.applyBatch(List.of(event(41, false), event(41, true), sale));
    ProductOnlineStatPO stat = fixture.stats.get(41L);
    assertEquals(3, result.appliedCount);
    assertEquals(1L, stat.viewCount);
    assertEquals(1L, stat.searchCount);
    assertEquals(2L, stat.soldCount);
    assertEquals(22L, stat.hotScore);
    assertEquals(List.of(1L, 2L, 3L), fixture.writes.stream().map(row -> row.version()).toList());
    verify(fixture.mapper).advanceBy(3);
    fixture.assertTransactions(1, 0);
  }

  @Test
  void initialControlPriorityPrecedesMappingAndLedgerConflictInBatch() {
    ProductMetricEventDTO event = event(41, false);
    ProductMetricControlPO uppercase = new ProductMetricControlPO();
    uppercase.seriesId = ProductMetricEvents.SERIES.toUpperCase(java.util.Locale.ROOT);
    ProductMetricControlPO trailing = new ProductMetricControlPO();
    trailing.seriesId = ProductMetricEvents.SERIES + " ";
    for (ProductMetricControlPO badControl : Arrays.asList(null, uppercase, trailing)) {
      Fixture fixture = new Fixture();
      when(fixture.mapper.initialApplySnapshot(event.eventId, 41L, null))
          .thenReturn(
              new ProductMetricInitialApplySnapshotDTO(
                  badControl, event.eventId, "other-fingerprint", false));
      assertEquals(
          "METRIC_CONTROL_INVALID",
          assertThrows(
                  IllegalStateException.class, () -> fixture.service.applyBatch(List.of(event)))
              .getMessage());
      verify(fixture.mapper).initialApplySnapshot(event.eventId, 41L, null);
      verifyNoMoreInteractions(fixture.mapper);
      fixture.assertTransactions(0, 1);
    }
  }

  @Test
  void purePersistentDuplicatesAtRevisionMaxStillTakeOneGateAndNoAdvance() {
    Fixture fixture = new Fixture();
    fixture.control.revision = Long.MAX_VALUE;
    ProductMetricEventDTO event = event(41, false);
    when(fixture.mapper.initialApplySnapshot(event.eventId, 41L, null))
        .thenReturn(
            new ProductMetricInitialApplySnapshotDTO(
                fixture.control, event.eventId, ProductMetricEvents.fingerprint(event), false));
    ProductMetricBatchApplyResultDTO result =
        fixture.service.applyBatch(List.of(event, copy(event)));
    assertEquals(0, result.appliedCount);
    assertTrue(result.events.stream().allMatch(row -> row.outcome.equals("DUPLICATE")));
    var order = inOrder(fixture.mapper);
    order.verify(fixture.mapper, times(2)).initialApplySnapshot(event.eventId, 41L, null);
    order.verify(fixture.mapper).control(true);
    order.verifyNoMoreInteractions();
    fixture.assertTransactions(1, 0);
  }

  @Test
  void persistentDuplicateDoesNotIncreaseMOrAcquireItsStat() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO duplicate = event(42, false), fresh = event(41, false);
    when(fixture.mapper.initialApplySnapshot(duplicate.eventId, 42L, null))
        .thenReturn(
            new ProductMetricInitialApplySnapshotDTO(
                fixture.control,
                duplicate.eventId,
                ProductMetricEvents.fingerprint(duplicate),
                false));
    ProductMetricBatchApplyResultDTO result = fixture.service.applyBatch(List.of(duplicate, fresh));
    assertEquals(1, result.appliedCount);
    verify(fixture.mapper, never()).ensureStat(eq(42L), any());
    verify(fixture.mapper, never()).stat(42L, true);
    verify(fixture.mapper).advanceBy(1);
    fixture.assertTransactions(1, 0);
  }

  @Test
  void canonicalCopySurvivesLaterCallerMutationWithoutRetainingOriginalDtos() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO first = event(41, false), second = event(42, false);
    String originalSecondId = second.eventId;
    when(fixture.mapper.initialApplySnapshot(first.eventId, 41L, null))
        .thenAnswer(
            invocation -> {
              fixture.businessSqlUnits++;
              second.productId = 999L;
              second.eventId = "caller-changed";
              first.traceId = "caller-changed";
              return new ProductMetricInitialApplySnapshotDTO(fixture.control, null, null, true);
            });
    ProductMetricBatchApplyResultDTO result = fixture.service.applyBatch(List.of(first, second));
    assertEquals(originalSecondId, result.events.get(1).eventId);
    verify(fixture.mapper).initialApplySnapshot(originalSecondId, 42L, null);
    assertTrue(
        fixture.writes.stream().noneMatch(row -> row.event() == first || row.event() == second));
    assertEquals(42L, fixture.writes.get(1).productId());
    fixture.assertTransactions(1, 0);
  }

  @Test
  void inputConflictAndPersistedConflictBothCheckCurrentControlBeforeThrowing() {
    for (boolean persistent : List.of(false, true)) {
      for (boolean badControl : List.of(false, true)) {
        Fixture fixture = new Fixture();
        ProductMetricEventDTO first = event(41, false), conflict = copy(first);
        conflict.occurredAt = "2026-10-24T12:34:56.789+08:00";
        if (persistent) {
          when(fixture.mapper.initialApplySnapshot(conflict.eventId, 41L, null))
              .thenReturn(
                  new ProductMetricInitialApplySnapshotDTO(
                      fixture.control,
                      first.eventId,
                      ProductMetricEvents.fingerprint(first),
                      false));
        }
        if (badControl) when(fixture.mapper.control(true)).thenReturn(null);
        List<ProductMetricEventDTO> input =
            persistent ? List.of(conflict) : List.of(first, conflict);
        RuntimeException problem =
            assertThrows(RuntimeException.class, () -> fixture.service.applyBatch(input));
        assertEquals(
            badControl ? "METRIC_CONTROL_INVALID" : "EVENT_FINGERPRINT_CONFLICT",
            problem.getMessage());
        var order = inOrder(fixture.mapper);
        order
            .verify(fixture.mapper, times(input.size()))
            .initialApplySnapshot(first.eventId, 41L, null);
        order.verify(fixture.mapper).control(true);
        order.verifyNoMoreInteractions();
        fixture.assertTransactions(0, 1);
      }
    }
  }

  @Test
  void missingMappingRechecksThreeCurrentIdentitiesAndBadControlAlwaysWins() {
    ProductMetricEventDTO event = event(41, false);
    for (ProductMetricApplySnapshotDTO current :
        Arrays.asList(
            null,
            new ProductMetricApplySnapshotDTO(
                event.eventId, ProductMetricEvents.fingerprint(event), null),
            new ProductMetricApplySnapshotDTO(event.eventId, "other-fingerprint", null))) {
      for (boolean badControl : List.of(false, true)) {
        Fixture fixture = new Fixture();
        when(fixture.mapper.initialApplySnapshot(event.eventId, 41L, null))
            .thenReturn(
                new ProductMetricInitialApplySnapshotDTO(fixture.control, null, null, false));
        when(fixture.mapper.findAppliedIdentity(event.eventId, true)).thenReturn(current);
        if (badControl) when(fixture.mapper.control(true)).thenReturn(null);
        RuntimeException problem =
            assertThrows(RuntimeException.class, () -> fixture.service.applyBatch(List.of(event)));
        String expected =
            badControl
                ? "METRIC_CONTROL_INVALID"
                : current == null
                    ? "PRODUCT_MAPPING_MISSING"
                    : ProductMetricEvents.fingerprint(event).equals(current.appliedFingerprint)
                        ? "METRIC_REPLAY_REQUIRES_NEW_SNAPSHOT"
                        : "EVENT_FINGERPRINT_CONFLICT";
        assertEquals(expected, problem.getMessage());
        if (!badControl
            && current != null
            && ProductMetricEvents.fingerprint(event).equals(current.appliedFingerprint))
          assertInstanceOf(ConcurrencyFailureException.class, problem);
        var order = inOrder(fixture.mapper);
        order.verify(fixture.mapper).initialApplySnapshot(event.eventId, 41L, null);
        order.verify(fixture.mapper).findAppliedIdentity(event.eventId, true);
        order.verify(fixture.mapper).control(true);
        order.verifyNoMoreInteractions();
        fixture.assertTransactions(0, 1);
      }
    }
  }

  @Test
  void laterPreflightFailurePreventsAllStatAndLedgerWrites() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO first = event(41, false), invalidMapping = event(42, false);
    when(fixture.mapper.initialApplySnapshot(invalidMapping.eventId, 42L, null))
        .thenReturn(new ProductMetricInitialApplySnapshotDTO(fixture.control, null, null, false));
    assertEquals(
        "PRODUCT_MAPPING_MISSING",
        assertThrows(
                ProductMetricEvents.InvalidEvent.class,
                () -> fixture.service.applyBatch(List.of(first, invalidMapping)))
            .reason());
    verify(fixture.mapper, never()).ensureStat(anyLong(), any());
    verify(fixture.mapper, never()).insertApplied(any(), anyString(), anyLong(), any());
    verify(fixture.mapper, never()).advanceBy(anyInt());
    fixture.assertTransactions(0, 1);
  }

  @Test
  void arithmeticAndVersionOverflowRecheckIdentityThenOnlyThrow() {
    ProductMetricEventDTO event = event(41, false);
    List<ProductOnlineStatPO> exhausted =
        List.of(
            stat(41, Long.MAX_VALUE, 0, 0, 0),
            stat(41, Long.MAX_VALUE - 1, 1, 0, 0),
            stat(41, 0, 0, 0, Long.MAX_VALUE));
    for (ProductOnlineStatPO stat : exhausted) {
      Fixture fixture = new Fixture();
      fixture.stats.put(41L, stat);
      assertEquals(
          "COUNTER_OVERFLOW",
          assertThrows(
                  ProductMetricEvents.InvalidEvent.class,
                  () -> fixture.service.applyBatch(List.of(event)))
              .reason());
      var order = inOrder(fixture.mapper);
      order.verify(fixture.mapper).initialApplySnapshot(event.eventId, 41L, null);
      order.verify(fixture.mapper).ensureStat(eq(41L), any());
      order.verify(fixture.mapper).stat(41L, true);
      order.verify(fixture.mapper).findAppliedIdentity(event.eventId, true);
      order.verify(fixture.mapper).control(true);
      order.verifyNoMoreInteractions();
      fixture.assertTransactions(0, 1);
    }
  }

  @Test
  void overflowAfterEarlierDmlStillUsesCurrentIdentityAndNeverWritesAfterFailureGate() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO first = event(41, false),
        overflowing = event(42, false),
        later = event(43, false);
    fixture.stats.put(42L, stat(42, 0, 0, 0, Long.MAX_VALUE));
    when(fixture.mapper.findAppliedIdentity(overflowing.eventId, true))
        .thenReturn(
            new ProductMetricApplySnapshotDTO(
                overflowing.eventId, ProductMetricEvents.fingerprint(overflowing), null));
    assertEquals(
        "METRIC_REPLAY_REQUIRES_NEW_SNAPSHOT",
        assertThrows(
                ConcurrencyFailureException.class,
                () -> fixture.service.applyBatch(List.of(first, overflowing, later)))
            .getMessage());
    assertEquals(
        List.of(first.eventId), fixture.writes.stream().map(row -> row.eventId()).toList());
    var order = inOrder(fixture.mapper);
    for (ProductMetricEventDTO event : List.of(first, overflowing, later))
      order
          .verify(fixture.mapper)
          .initialApplySnapshot(event.eventId, event.productId, event.skuId);
    for (long productId : List.of(41L, 42L, 43L)) {
      order.verify(fixture.mapper).ensureStat(eq(productId), any());
      order.verify(fixture.mapper).stat(productId, true);
    }
    order
        .verify(fixture.mapper)
        .insertApplied(any(), eq(ProductMetricEvents.fingerprint(first)), eq(1L), any());
    order.verify(fixture.mapper).updateStat(any(), eq(first.eventId), any());
    order.verify(fixture.mapper).findAppliedIdentity(overflowing.eventId, true);
    order.verify(fixture.mapper).control(true);
    order.verifyNoMoreInteractions();
    fixture.assertTransactions(0, 1);
  }

  @Test
  void commitFailureDoesNotExposeConstructedResultAsCompleted() {
    Fixture fixture = new Fixture();
    SQLException cause = new SQLException("commit outcome unknown", "08S01");
    org.springframework.transaction.TransactionSystemException original =
        new org.springframework.transaction.TransactionSystemException("commit fixture", cause);
    fixture.manager.commitFailure = original;
    assertSame(
        original,
        assertThrows(
            org.springframework.transaction.TransactionSystemException.class,
            () -> fixture.service.applyBatch(List.of(event(41, false)))));
    assertSame(cause, original.getCause());
    assertEquals(1, fixture.manager.begins);
    assertEquals(0, fixture.manager.commits);
    assertEquals(0, fixture.manager.rollbacks);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
  }

  @Test
  void revisionCapacityBoundaryAndZeroAffectedRowsRollBackTheWholeProxyCall() {
    List<ProductMetricEventDTO> events =
        List.of(event(41, false), event(42, false), event(43, false), event(44, false));
    Fixture exact = new Fixture();
    exact.control.revision = Long.MAX_VALUE - 4;
    assertEquals(4, exact.service.applyBatch(events).appliedCount);
    exact.assertTransactions(1, 0);
    verify(exact.mapper).advanceBy(4);
    Fixture insufficient = new Fixture();
    insufficient.control.revision = Long.MAX_VALUE - 3;
    assertEquals(
        "METRIC_CONTROL_EXHAUSTED",
        assertThrows(IllegalStateException.class, () -> insufficient.service.applyBatch(events))
            .getMessage());
    verify(insufficient.mapper, never()).advanceBy(anyInt());
    assertEquals(4, insufficient.writes.size());
    insufficient.assertTransactions(0, 1);
    Fixture lostUpdate = new Fixture();
    when(lostUpdate.mapper.advanceBy(4)).thenReturn(0);
    assertEquals(
        "METRIC_CONTROL_EXHAUSTED",
        assertThrows(IllegalStateException.class, () -> lostUpdate.service.applyBatch(events))
            .getMessage());
    lostUpdate.assertTransactions(0, 1);
  }

  @Test
  void sqlFailuresRetainExactExceptionCauseAndNeverContinuePastFailure() {
    ProductMetricEventDTO event = event(41, false);
    List<DataAccessException> failures =
        List.of(
            new DuplicateKeyException("PRIMARY", new SQLException("pk", "23000", 1062)),
            new DuplicateKeyException("unique-version", new SQLException("version", "23000", 1062)),
            new UncategorizedSQLException(
                "projection", "fixture", new SQLException("trigger", "45000", 1644)));
    for (DataAccessException original : failures) {
      Fixture fixture = new Fixture();
      if (original instanceof DuplicateKeyException)
        doThrow(original).when(fixture.mapper).insertApplied(any(), anyString(), anyLong(), any());
      else doThrow(original).when(fixture.mapper).updateStat(any(), anyString(), any());
      assertSame(
          original,
          assertThrows(
              DataAccessException.class, () -> fixture.service.applyBatch(List.of(event))));
      verify(fixture.mapper, never()).control(true);
      verify(fixture.mapper, never()).advanceBy(anyInt());
      fixture.assertTransactions(0, 1);
    }
    Fixture advanceFailure = new Fixture();
    DataAccessException original = failures.get(2);
    when(advanceFailure.mapper.advanceBy(1)).thenThrow(original);
    assertSame(
        original,
        assertThrows(
            DataAccessException.class, () -> advanceFailure.service.applyBatch(List.of(event))));
    advanceFailure.assertTransactions(0, 1);
  }

  @Test
  void nextProxyAttemptUsesNewTransactionAndFreshStatStateAfterFailure() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO event = event(41, false);
    when(fixture.mapper.stat(41L, true)).thenAnswer(invocation -> stat(41, 0, 0, 0, 0));
    doThrow(new DuplicateKeyException("first attempt"))
        .doNothing()
        .when(fixture.mapper)
        .insertApplied(any(), anyString(), eq(1L), any());
    assertThrows(DuplicateKeyException.class, () -> fixture.service.applyBatch(List.of(event)));
    assertEquals(1, fixture.service.applyBatch(List.of(event)).appliedCount);
    assertEquals(2, fixture.manager.begins);
    assertEquals(1, fixture.manager.commits);
    assertEquals(1, fixture.manager.rollbacks);
    assertNotSame(fixture.manager.transactions.get(0), fixture.manager.transactions.get(1));
    verify(fixture.mapper, times(2)).insertApplied(any(), anyString(), eq(1L), any());
  }

  @Test
  void singleApplyStillUsesEightSqlUnitsAndItsOriginalAdvance() {
    Fixture fixture = new Fixture();
    ProductMetricEventDTO event = event(41, false);
    assertEquals("APPLIED", fixture.service.apply(event));
    var order = inOrder(fixture.mapper);
    order.verify(fixture.mapper).initialApplySnapshot(event.eventId, 41L, null);
    order.verify(fixture.mapper).ensureStat(eq(41L), any());
    order.verify(fixture.mapper).stat(41L, true);
    order
        .verify(fixture.mapper)
        .insertApplied(eq(event), eq(ProductMetricEvents.fingerprint(event)), eq(1L), any());
    order.verify(fixture.mapper).updateStat(any(), eq(event.eventId), any());
    order.verify(fixture.mapper).control(true);
    order.verify(fixture.mapper).advance();
    order.verifyNoMoreInteractions();
    assertEquals(8, fixture.businessSqlUnits);
    fixture.assertTransactions(1, 0);
  }

  @Test
  void advanceByHasExactSqlBindingsAndRejectsOutsideOneThroughFour() {
    CapturedJdbc jdbc = new CapturedJdbc();
    ProductMetricMapper mapper = new ProductMetricMapper(jdbc);
    for (int count : List.of(-1, 0, 5, Integer.MAX_VALUE))
      assertThrows(IllegalArgumentException.class, () -> mapper.advanceBy(count));
    assertEquals(0, jdbc.updates);
    for (int count : List.of(1, 4)) {
      LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
      assertEquals(1, mapper.advanceBy(count));
      assertEquals(
          "UPDATE product_metric_control SET revision=revision+?,updated_at=?"
              + " WHERE singleton_id=1 AND revision<=?",
          jdbc.sql);
      assertEquals(count, jdbc.parameters[0]);
      assertInstanceOf(LocalDateTime.class, jdbc.parameters[1]);
      LocalDateTime bound = (LocalDateTime) jdbc.parameters[1];
      assertFalse(bound.isBefore(before));
      assertFalse(bound.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
      assertEquals(0, bound.getNano() % 1_000_000);
      assertEquals(Long.MAX_VALUE - count, jdbc.parameters[2]);
    }
  }

  /** Earlier projection failure wins over a reachable later version overflow in the real proxy. */
  @Test
  void earlyProjectionFailurePrecedesLaterVersionOverflowWithReachableOverflowControl() {
    // 1. Seed both faults behind successful preflight and both distinct ascending anchors.
    Fixture dualFailure = new Fixture();
    ProductMetricEventDTO first = event(41, false);
    ProductMetricEventDTO overflowing = event(42, false);
    ProductOnlineStatPO exhaustedStat = stat(42, 0, 0, 0, Long.MAX_VALUE);
    dualFailure.stats.put(42L, exhaustedStat);
    SQLException projectionCause = new SQLException("projection trigger", "45000", 1644);
    UncategorizedSQLException projectionFailure =
        new UncategorizedSQLException("projection", "fixture", projectionCause);
    doThrow(projectionFailure).when(dualFailure.mapper).updateStat(any(), eq(first.eventId), any());

    // 2. Preserve the original projection throwable and stop before computing the later event.
    DataAccessException observedFailure =
        assertThrows(
            DataAccessException.class,
            () -> dualFailure.service.applyBatch(List.of(first, overflowing)));
    assertSame(projectionFailure, observedFailure);
    assertSame(projectionCause, observedFailure.getCause());
    assertEquals("45000", projectionCause.getSQLState());
    assertEquals(1644, projectionCause.getErrorCode());
    assertEquals(Long.MAX_VALUE, exhaustedStat.metricVersion);
    var dualOrder = inOrder(dualFailure.mapper);
    dualOrder.verify(dualFailure.mapper).initialApplySnapshot(first.eventId, 41L, null);
    dualOrder.verify(dualFailure.mapper).initialApplySnapshot(overflowing.eventId, 42L, null);
    dualOrder.verify(dualFailure.mapper).ensureStat(eq(41L), any());
    dualOrder.verify(dualFailure.mapper).stat(41L, true);
    dualOrder.verify(dualFailure.mapper).ensureStat(eq(42L), any());
    dualOrder.verify(dualFailure.mapper).stat(42L, true);
    dualOrder
        .verify(dualFailure.mapper)
        .insertApplied(
            argThat(appliedEvent -> first.eventId.equals(appliedEvent.eventId)),
            eq(ProductMetricEvents.fingerprint(first)),
            eq(1L),
            any());
    dualOrder.verify(dualFailure.mapper).updateStat(any(), eq(first.eventId), any());
    dualOrder.verifyNoMoreInteractions();
    verify(dualFailure.mapper, never())
        .insertApplied(
            argThat(appliedEvent -> overflowing.eventId.equals(appliedEvent.eventId)),
            anyString(),
            anyLong(),
            any());
    verify(dualFailure.mapper, never()).updateStat(any(), eq(overflowing.eventId), any());
    verify(dualFailure.mapper, never()).findAppliedIdentity(anyString(), eq(true));
    verify(dualFailure.mapper, never()).control(true);
    verify(dualFailure.mapper, never()).advanceBy(anyInt());
    verify(dualFailure.mapper, never()).advance();
    dualFailure.assertTransactions(0, 1);

    // 3. An independent e2-only proxy reaches real arithmetic and its absent-identity failure gate.
    Fixture overflowControl = new Fixture();
    ProductMetricEventDTO controlEvent = copy(overflowing);
    overflowControl.stats.put(42L, stat(42, 0, 0, 0, Long.MAX_VALUE));
    when(overflowControl.mapper.findAppliedIdentity(controlEvent.eventId, true)).thenReturn(null);
    when(overflowControl.mapper.control(true)).thenReturn(overflowControl.control);
    assertEquals(
        "COUNTER_OVERFLOW",
        assertThrows(
                ProductMetricEvents.InvalidEvent.class,
                () -> overflowControl.service.applyBatch(List.of(controlEvent)))
            .reason());
    var controlOrder = inOrder(overflowControl.mapper);
    controlOrder
        .verify(overflowControl.mapper)
        .initialApplySnapshot(controlEvent.eventId, 42L, null);
    controlOrder.verify(overflowControl.mapper).ensureStat(eq(42L), any());
    controlOrder.verify(overflowControl.mapper).stat(42L, true);
    controlOrder.verify(overflowControl.mapper).findAppliedIdentity(controlEvent.eventId, true);
    controlOrder.verify(overflowControl.mapper).control(true);
    controlOrder.verifyNoMoreInteractions();
    verify(overflowControl.mapper, never()).insertApplied(any(), anyString(), anyLong(), any());
    verify(overflowControl.mapper, never()).updateStat(any(), anyString(), any());
    verify(overflowControl.mapper, never()).advanceBy(anyInt());
    verify(overflowControl.mapper, never()).advance();
    overflowControl.assertTransactions(0, 1);
  }

  /** Earlier projection failure wins over a reachable later plain-ledger conflict in the proxy. */
  @Test
  void earlyProjectionFailurePrecedesLaterLedgerConflictWithReachableConflictControl() {
    // 1. Configure independent projection and exact later-event ledger faults after healthy
    // anchors.
    Fixture dualFailure = new Fixture();
    ProductMetricEventDTO first = event(41, false);
    ProductMetricEventDTO conflicting = event(42, false);
    SQLException projectionCause = new SQLException("projection trigger", "45000", 1644);
    UncategorizedSQLException projectionFailure =
        new UncategorizedSQLException("projection", "fixture", projectionCause);
    SQLException ledgerCause = new SQLException("later ledger conflict", "23000", 1062);
    DuplicateKeyException ledgerFailure = new DuplicateKeyException("later PRIMARY", ledgerCause);
    doThrow(projectionFailure).when(dualFailure.mapper).updateStat(any(), eq(first.eventId), any());
    doThrow(ledgerFailure)
        .when(dualFailure.mapper)
        .insertApplied(
            argThat(appliedEvent -> conflicting.eventId.equals(appliedEvent.eventId)),
            eq(ProductMetricEvents.fingerprint(conflicting)),
            eq(1L),
            any());

    // 2. The earlier original exception escapes before any later ledger or final gate call.
    DataAccessException observedFailure =
        assertThrows(
            DataAccessException.class,
            () -> dualFailure.service.applyBatch(List.of(first, conflicting)));
    assertSame(projectionFailure, observedFailure);
    assertSame(projectionCause, observedFailure.getCause());
    assertEquals("45000", projectionCause.getSQLState());
    assertEquals(1644, projectionCause.getErrorCode());
    var dualOrder = inOrder(dualFailure.mapper);
    dualOrder.verify(dualFailure.mapper).initialApplySnapshot(first.eventId, 41L, null);
    dualOrder.verify(dualFailure.mapper).initialApplySnapshot(conflicting.eventId, 42L, null);
    dualOrder.verify(dualFailure.mapper).ensureStat(eq(41L), any());
    dualOrder.verify(dualFailure.mapper).stat(41L, true);
    dualOrder.verify(dualFailure.mapper).ensureStat(eq(42L), any());
    dualOrder.verify(dualFailure.mapper).stat(42L, true);
    dualOrder
        .verify(dualFailure.mapper)
        .insertApplied(
            argThat(appliedEvent -> first.eventId.equals(appliedEvent.eventId)),
            eq(ProductMetricEvents.fingerprint(first)),
            eq(1L),
            any());
    dualOrder.verify(dualFailure.mapper).updateStat(any(), eq(first.eventId), any());
    dualOrder.verifyNoMoreInteractions();
    verify(dualFailure.mapper, never())
        .insertApplied(
            argThat(appliedEvent -> conflicting.eventId.equals(appliedEvent.eventId)),
            anyString(),
            anyLong(),
            any());
    verify(dualFailure.mapper, never()).updateStat(any(), eq(conflicting.eventId), any());
    verify(dualFailure.mapper, never()).findAppliedIdentity(anyString(), eq(true));
    verify(dualFailure.mapper, never()).control(true);
    verify(dualFailure.mapper, never()).advanceBy(anyInt());
    verify(dualFailure.mapper, never()).advance();
    dualFailure.assertTransactions(0, 1);

    // 3. An independent healthy e2-only fixture proves the original 1062 fault is reachable.
    Fixture conflictControl = new Fixture();
    ProductMetricEventDTO controlEvent = copy(conflicting);
    conflictControl.stats.put(42L, stat(42, 0, 0, 0, 0));
    SQLException controlCause = new SQLException("later ledger conflict", "23000", 1062);
    DuplicateKeyException controlFailure = new DuplicateKeyException("later PRIMARY", controlCause);
    doThrow(controlFailure)
        .when(conflictControl.mapper)
        .insertApplied(
            argThat(appliedEvent -> controlEvent.eventId.equals(appliedEvent.eventId)),
            eq(ProductMetricEvents.fingerprint(controlEvent)),
            eq(1L),
            any());
    DuplicateKeyException observedControlFailure =
        assertThrows(
            DuplicateKeyException.class,
            () -> conflictControl.service.applyBatch(List.of(controlEvent)));
    assertSame(controlFailure, observedControlFailure);
    assertSame(controlCause, observedControlFailure.getCause());
    assertEquals("23000", controlCause.getSQLState());
    assertEquals(1062, controlCause.getErrorCode());
    var controlOrder = inOrder(conflictControl.mapper);
    controlOrder
        .verify(conflictControl.mapper)
        .initialApplySnapshot(controlEvent.eventId, 42L, null);
    controlOrder.verify(conflictControl.mapper).ensureStat(eq(42L), any());
    controlOrder.verify(conflictControl.mapper).stat(42L, true);
    controlOrder
        .verify(conflictControl.mapper)
        .insertApplied(
            argThat(appliedEvent -> controlEvent.eventId.equals(appliedEvent.eventId)),
            eq(ProductMetricEvents.fingerprint(controlEvent)),
            eq(1L),
            any());
    controlOrder.verifyNoMoreInteractions();
    verify(conflictControl.mapper, never()).updateStat(any(), anyString(), any());
    verify(conflictControl.mapper, never()).findAppliedIdentity(anyString(), eq(true));
    verify(conflictControl.mapper, never()).control(true);
    verify(conflictControl.mapper, never()).advanceBy(anyInt());
    verify(conflictControl.mapper, never()).advance();
    conflictControl.assertTransactions(0, 1);
  }

  private static ProductMetricEventDTO event(long productId, boolean search) {
    ProductMetricEventDTO event =
        ProductMetricEvents.product(UUID.randomUUID().toString(), productId, search, null);
    event.occurredAt = "2026-10-16T10:00:00.000+08:00";
    ProductMetricEvents.validate(event);
    return event;
  }

  private static ProductMetricEventDTO copy(ProductMetricEventDTO event) {
    return ProductMetricEvents.parse(ProductMetricEvents.bytes(event));
  }

  private static ProductOnlineStatPO stat(
      long productId, long views, long searches, long sales, long version) {
    ProductOnlineStatPO stat = new ProductOnlineStatPO();
    stat.productId = productId;
    stat.viewCount = views;
    stat.searchCount = searches;
    stat.soldCount = sales;
    stat.hotScore = 0L;
    stat.metricVersion = version;
    return stat;
  }

  /** Captured immutable call facts avoid reading a subsequently mutated aggregate reference. */
  private record Write(
      ProductMetricEventDTO event,
      String eventId,
      long productId,
      long version,
      LocalDateTime time) {}

  /** Mock SQL with an actual Spring interceptor and distinct transaction objects. */
  private static final class Fixture {
    /** Real mapper API; no JDBC operation is delegated by this fixture. */
    final ProductMetricMapper mapper = mock(ProductMetricMapper.class);

    /** Healthy complete control shape needed by both single and batch paths. */
    final ProductMetricControlPO control = new ProductMetricControlPO();

    /** Each explicitly seeded or first-created fake stat belongs only to this fixture. */
    final Map<Long, ProductOnlineStatPO> stats = new HashMap<>();

    /** Immutable observed insert arguments retain per-event input order and version. */
    final List<Write> writes = new ArrayList<>();

    /** Real Spring synchronization manager counts do not claim physical database commits. */
    final LocalTransactions manager = new LocalTransactions();

    /** The subject is a genuine class-based transaction proxy, never a mocked Service. */
    final ProductMetricService service;

    /** Weighted original mapper statements count updateStat as its two business SQL operations. */
    int businessSqlUnits;

    private Fixture() {
      control.seriesId = ProductMetricEvents.SERIES;
      control.revision = 0L;
      when(mapper.initialApplySnapshot(anyString(), anyLong(), nullable(Long.class)))
          .thenAnswer(
              invocation -> {
                businessSqlUnits++;
                return new ProductMetricInitialApplySnapshotDTO(control, null, null, true);
              });
      doAnswer(
              invocation -> {
                businessSqlUnits++;
                return null;
              })
          .when(mapper)
          .ensureStat(anyLong(), any());
      when(mapper.stat(anyLong(), eq(true)))
          .thenAnswer(
              invocation -> {
                businessSqlUnits++;
                long productId = invocation.getArgument(0);
                return stats.computeIfAbsent(productId, key -> stat(key, 0, 0, 0, 0));
              });
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                businessSqlUnits++;
                ProductMetricEventDTO event = invocation.getArgument(0);
                writes.add(
                    new Write(
                        event,
                        event.eventId,
                        event.productId,
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
                return null;
              })
          .when(mapper)
          .insertApplied(any(), anyString(), anyLong(), any());
      doAnswer(
              invocation -> {
                businessSqlUnits += 2;
                return null;
              })
          .when(mapper)
          .updateStat(any(), anyString(), any());
      when(mapper.control(true))
          .thenAnswer(
              invocation -> {
                businessSqlUnits++;
                return control;
              });
      when(mapper.advanceBy(anyInt()))
          .thenAnswer(
              invocation -> {
                businessSqlUnits++;
                return 1;
              });
      when(mapper.advance())
          .thenAnswer(
              invocation -> {
                businessSqlUnits++;
                return 1;
              });
      ProxyFactory factory = new ProxyFactory(new ProductMetricService(mapper));
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      service = (ProductMetricService) factory.getProxy();
    }

    private void assertTransactions(int committed, int rolledBack) {
      assertEquals(committed + rolledBack, manager.begins);
      assertEquals(committed, manager.commits);
      assertEquals(rolledBack, manager.rollbacks);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }
  }

  /** Spring controls the real interceptor lifecycle; this manager has no physical data source. */
  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    /** Actual calls to the transaction manager begin hook. */
    int begins;

    /** Actual successful commit hooks after Service execution. */
    int commits;

    /** Actual rollback hooks after original Service failures. */
    int rollbacks;

    /** Original simulated commit failure, with no assertion about real database completion. */
    RuntimeException commitFailure;

    /** Distinct transaction objects prove each retry enters a new transaction scope. */
    final List<Object> transactions = new ArrayList<>();

    /** Allocate one transaction identity without acquiring a database connection. */
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    /** Let Spring establish synchronization around the original method. */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
      transactions.add(transaction);
      assertEquals(3, definition.getTimeout());
    }

    /** Count a genuine interceptor commit invocation, not database durability. */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      if (commitFailure != null) throw commitFailure;
      commits++;
    }

    /** Count a genuine interceptor rollback invocation after the original failure. */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }

  /** Capture only the mapper's exact SQL/parameters, without running a statement. */
  private static final class CapturedJdbc extends JdbcTemplate {
    /** Exact number of delegated update calls. */
    int updates;

    /** Last actual SQL string emitted by advanceBy. */
    String sql;

    /** Exact bound values copied before another test call can replace them. */
    Object[] parameters;

    private CapturedJdbc() {
      super(mock(DataSource.class));
    }

    /** Capture the real production mapper arguments without creating a connection. */
    @Override
    public int update(String sql, Object... arguments) {
      updates++;
      this.sql = sql;
      parameters = arguments.clone();
      return 1;
    }
  }
}
