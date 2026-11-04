package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricApplySnapshotDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.ProductMetricControlPO;
import com.cloudmall.product.domain.po.ProductOnlineStatPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.service.impl.metrics.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.Channel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local contracts complement the separately bounded genuine MySQL interleavings. */
class LateApplyGateTest {
  @Test
  void identityReadsAndAggregateAnchorKeepExactSqlBoundaries() throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getString("event_id")).thenReturn("immutable-id");
    when(result.getString("fingerprint")).thenReturn("immutable-fingerprint");
    CapturedJdbcTemplate jdbc = spy(new CapturedJdbcTemplate(result));
    ProductMetricMapper mapper = new ProductMetricMapper(jdbc);
    assertEquals("immutable-id", mapper.findAppliedIdentity("immutable-id", false).appliedEventId);
    assertEquals(
        "SELECT event_id,fingerprint FROM product_metric_applied_event WHERE event_id=?", jdbc.sql);
    assertArrayEquals(new Object[] {"immutable-id"}, jdbc.arguments);
    assertEquals(
        "immutable-fingerprint",
        mapper.findAppliedIdentity("immutable-id", true).appliedFingerprint);
    assertTrue(jdbc.sql.endsWith(" FOR UPDATE"));
    jdbc.rowPresent = false;
    assertNull(mapper.findAppliedIdentity("absent-id", false));
    LocalDateTime time = LocalDateTime.of(2026, 10, 22, 10, 20, 30, 123000000);
    mapper.ensureStat(41L, time);
    assertTrue(jdbc.sql.endsWith("ON DUPLICATE KEY UPDATE product_id=product_id"));
    assertTrue(jdbc.sql.contains("VALUES(?,0,0,0,0,0,?,?)"));
    assertFalse(jdbc.sql.contains("INSERT IGNORE"));
    assertFalse(jdbc.sql.contains("updated_at="));
    assertArrayEquals(new Object[] {41L, time, time}, jdbc.arguments);
    jdbc.rowPresent = true;
    LocalDateTime started = time.minusDays(1);
    when(result.getObject("singleton_id")).thenReturn(1L);
    when(result.getObject("series_id")).thenReturn(ProductMetricEvents.SERIES);
    when(result.getObject("revision")).thenReturn(9);
    when(result.getObject("started_at")).thenReturn(started);
    when(result.getObject("index_uuid")).thenReturn(null);
    when(result.getObject("index_ready")).thenReturn(1L);
    when(result.getObject("updated_at")).thenReturn("2026-10-22 10:20:30.123");
    when(result.getString("updated_at")).thenReturn("2026-10-22 10:20:30.123");
    when(result.getString("applied_event_id")).thenReturn("immutable-id");
    when(result.getString("applied_fingerprint")).thenReturn("immutable-fingerprint");
    when(result.getBoolean("mapping_present")).thenReturn(true);
    when(result.wasNull()).thenReturn(false);
    ProductMetricInitialApplySnapshotDTO initial =
        mapper.initialApplySnapshot("immutable-id", 41L, null);
    assertEquals(
        "SELECT metric_control.singleton_id,metric_control.series_id,metric_control.revision,"
            + "metric_control.started_at,metric_control.index_uuid,metric_control.index_ready,"
            + "metric_control.updated_at,applied.event_id AS applied_event_id,"
            + "applied.fingerprint AS applied_fingerprint,"
            + "EXISTS(SELECT 1 FROM product WHERE id=?) AS mapping_present"
            + " FROM product_metric_control metric_control"
            + " LEFT JOIN product_metric_applied_event applied ON applied.event_id=?"
            + " WHERE metric_control.singleton_id=1",
        jdbc.sql);
    assertArrayEquals(new Object[] {41L, "immutable-id"}, jdbc.arguments);
    verify(jdbc, times(1)).query(eq(jdbc.sql), any(RowMapper.class), eq(41L), eq("immutable-id"));
    assertFalse(jdbc.sql.contains("FOR UPDATE"));
    assertFalse(jdbc.sql.contains("FOR SHARE"));
    assertFalse(jdbc.sql.contains("product_online_stat"));
    assertEquals(1, initial.control.singletonId);
    assertEquals(ProductMetricEvents.SERIES, initial.control.seriesId);
    assertEquals(9L, initial.control.revision);
    assertEquals(started, initial.control.startedAt);
    assertNull(initial.control.indexUuid);
    assertEquals(1, initial.control.indexReady);
    assertEquals(time, initial.control.updatedAt);
    assertEquals("immutable-id", initial.appliedEventId);
    assertEquals("immutable-fingerprint", initial.appliedFingerprint);
    assertTrue(initial.mappingPresent);
    for (boolean mappingPresent : List.of(false, true)) {
      when(result.getBoolean("mapping_present")).thenReturn(mappingPresent);
      ProductMetricInitialApplySnapshotDTO saleSnapshot =
          mapper.initialApplySnapshot("immutable-id", 41L, 71L);
      assertEquals(mappingPresent, saleSnapshot.mappingPresent);
      assertEquals(
          "SELECT metric_control.singleton_id,metric_control.series_id,metric_control.revision,"
              + "metric_control.started_at,metric_control.index_uuid,metric_control.index_ready,metric_control.updated_at,applied.event_id"
              + " AS applied_event_id,applied.fingerprint AS applied_fingerprint,EXISTS(SELECT 1"
              + " FROM product_sku s JOIN product p ON p.id=s.product_id WHERE s.id=? AND p.id=?)"
              + " AS mapping_present FROM product_metric_control metric_control LEFT JOIN"
              + " product_metric_applied_event applied ON applied.event_id=? WHERE"
              + " metric_control.singleton_id=1",
          jdbc.sql);
      assertArrayEquals(new Object[] {71L, 41L, "immutable-id"}, jdbc.arguments);
    }
    verify(jdbc, times(2))
        .query(eq(jdbc.sql), any(RowMapper.class), eq(71L), eq(41L), eq("immutable-id"));
    when(result.getString("applied_event_id")).thenReturn(null);
    when(result.getString("applied_fingerprint")).thenReturn(null);
    when(result.getBoolean("mapping_present")).thenReturn(false);
    initial = mapper.initialApplySnapshot("absent-id", 41L, null);
    assertNotNull(initial.control);
    assertNull(initial.appliedEventId);
    assertNull(initial.appliedFingerprint);
    assertFalse(initial.mappingPresent);
    for (Long skuId : java.util.Arrays.asList(null, 71L)) {
      when(result.wasNull()).thenReturn(true);
      IllegalStateException invalidProjection =
          assertThrows(
              IllegalStateException.class,
              () -> mapper.initialApplySnapshot("null-mapping", 41L, skuId));
      assertInstanceOf(SQLException.class, invalidProjection.getCause());
      assertEquals("METRIC_MAPPING_PROJECTION_NULL", invalidProjection.getCause().getMessage());
    }
    when(result.wasNull()).thenReturn(false);
    jdbc.rowPresent = false;
    assertNull(mapper.initialApplySnapshot("absent-control", 41L, null));
    ProductMetricEventDTO event = event();
    mapper.insertApplied(event, ProductMetricEvents.fingerprint(event), 1L, time);
    assertTrue(jdbc.sql.startsWith("INSERT INTO product_metric_applied_event("));
    assertFalse(jdbc.sql.contains("ON DUPLICATE"));
    assertEquals(12, jdbc.arguments.length);
    assertEquals(event.eventId, jdbc.arguments[0]);
    assertEquals(1L, jdbc.arguments[11]);
  }

  @Test
  void stalePermanentFailureReadsCurrentIdentityAndRollsBackForFreshRetry() {
    ProductMetricEventDTO event = event();
    for (boolean mappingPresent : List.of(false, true)) {
      ProductMetricMapper mapper = validMapper(mappingPresent);
      when(mapper.stat(41L, true)).thenReturn(stat(Long.MAX_VALUE));
      when(mapper.findAppliedIdentity(event.eventId, true))
          .thenReturn(
              new ProductMetricApplySnapshotDTO(
                  event.eventId, ProductMetricEvents.fingerprint(event), null));
      ConcurrencyFailureException retry =
          assertThrows(
              ConcurrencyFailureException.class,
              () -> new ProductMetricService(mapper).apply(event));
      assertEquals("METRIC_REPLAY_REQUIRES_NEW_SNAPSHOT", retry.getMessage());
      var order = inOrder(mapper);
      order.verify(mapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
      if (mappingPresent) {
        order.verify(mapper).ensureStat(eq(41L), any());
        order.verify(mapper).stat(41L, true);
      }
      order.verify(mapper).findAppliedIdentity(event.eventId, true);
      order.verify(mapper).control(true);
      order.verifyNoMoreInteractions();
    }
  }

  @Test
  void currentControlAndFingerprintPrecedePermanentFailure() {
    ProductMetricEventDTO event = event();
    ProductMetricMapper conflictMapper = validMapper(false);
    when(conflictMapper.findAppliedIdentity(event.eventId, true))
        .thenReturn(new ProductMetricApplySnapshotDTO(event.eventId, "other-fingerprint", null));
    ProductMetricEvents.InvalidEvent conflict =
        assertThrows(
            ProductMetricEvents.InvalidEvent.class,
            () -> new ProductMetricService(conflictMapper).apply(event));
    assertEquals("EVENT_FINGERPRINT_CONFLICT", conflict.reason());
    verify(conflictMapper, never()).ensureStat(anyLong(), any());
    verify(conflictMapper, never()).insertApplied(any(), anyString(), anyLong(), any());
    ProductMetricMapper changedControl = validMapper(false);
    when(changedControl.control(true)).thenReturn(null);
    IllegalStateException invalid =
        assertThrows(
            IllegalStateException.class,
            () -> new ProductMetricService(changedControl).apply(event));
    assertEquals("METRIC_CONTROL_INVALID", invalid.getMessage());
    verify(changedControl).findAppliedIdentity(event.eventId, true);
    verify(changedControl, never()).advance();
    for (ProductMetricApplySnapshotDTO currentIdentity :
        java.util.Arrays.asList(
            null,
            new ProductMetricApplySnapshotDTO(
                event.eventId, ProductMetricEvents.fingerprint(event), null),
            new ProductMetricApplySnapshotDTO(event.eventId, "other-fingerprint", null))) {
      ProductMetricMapper invalidCurrentControl = validMapper(false);
      when(invalidCurrentControl.findAppliedIdentity(event.eventId, true))
          .thenReturn(currentIdentity);
      when(invalidCurrentControl.control(true)).thenReturn(null);
      IllegalStateException controlFailure =
          assertThrows(
              IllegalStateException.class,
              () -> new ProductMetricService(invalidCurrentControl).apply(event));
      assertEquals("METRIC_CONTROL_INVALID", controlFailure.getMessage());
      var order = inOrder(invalidCurrentControl);
      order
          .verify(invalidCurrentControl)
          .initialApplySnapshot(event.eventId, event.productId, event.skuId);
      order.verify(invalidCurrentControl).findAppliedIdentity(event.eventId, true);
      order.verify(invalidCurrentControl).control(true);
      order.verifyNoMoreInteractions();
    }
  }

  @Test
  void consumerRetriesRealTransactionProxyThreeTimesAndAcknowledgesAfterCommit() throws Exception {
    ProductMetricEventDTO event = event();
    ProductMetricMapper mapper = validMapper(true);
    when(mapper.stat(41L, true)).thenAnswer(invocation -> stat(0));
    // Parsing creates an equivalent envelope, so match its stable identity instead of object
    // identity.
    doThrow(new DuplicateKeyException("controlled first unique conflict"))
        .doThrow(new DuplicateKeyException("controlled second unique conflict"))
        .doNothing()
        .when(mapper)
        .insertApplied(
            argThat(candidate -> candidate != null && event.eventId.equals(candidate.eventId)),
            anyString(),
            eq(1L),
            any());
    LocalTransactionManager manager = new LocalTransactionManager();
    ProxyFactory factory = new ProxyFactory(new ProductMetricService(mapper));
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    ProductMetricService proxy = (ProductMetricService) factory.getProxy();
    ProductMetricOutboxDispatcher publisher = mock(ProductMetricOutboxDispatcher.class);
    ProductMetricConsumer consumer =
        new ProductMetricConsumer(proxy, publisher, mock(RabbitListenerEndpointRegistry.class));
    Channel channel = mock(Channel.class);
    doAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
              assertEquals(3, manager.begins);
              assertEquals(2, manager.rollbacks);
              assertEquals(1, manager.commits);
              return null;
            })
        .when(channel)
        .basicAck(7L, false);
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(7L);
    consumer.consume(new Message(ProductMetricEvents.bytes(event), properties), channel);
    verify(channel).basicAck(7L, false);
    verifyNoMoreInteractions(channel);
    verifyNoInteractions(publisher);
    verify(mapper, times(3))
        .insertApplied(any(ProductMetricEventDTO.class), anyString(), eq(1L), any());
    assertEquals(3, manager.transactionIdentities.size());
    assertEquals(3L, manager.transactionIdentities.stream().distinct().count());
  }

  private static ProductMetricMapper validMapper(boolean mappingPresent) {
    ProductMetricMapper mapper = mock(ProductMetricMapper.class);
    ProductMetricControlPO control = new ProductMetricControlPO();
    control.seriesId = ProductMetricEvents.SERIES;
    when(mapper.initialApplySnapshot(anyString(), anyLong(), nullable(Long.class)))
        .thenReturn(new ProductMetricInitialApplySnapshotDTO(control, null, null, mappingPresent));
    when(mapper.control(true)).thenReturn(control);
    when(mapper.advance()).thenReturn(1);
    return mapper;
  }

  private static ProductMetricEventDTO event() {
    return ProductMetricEvents.product(UUID.randomUUID().toString(), 41L, false, null);
  }

  private static ProductOnlineStatPO stat(long views) {
    ProductOnlineStatPO stat = new ProductOnlineStatPO();
    stat.productId = 41L;
    stat.viewCount = views;
    stat.searchCount = 0L;
    stat.soldCount = 0L;
    stat.hotScore = views;
    stat.metricVersion = 0L;
    return stat;
  }

  /** Capture genuine mapper statements without obtaining a database connection. */
  private static final class CapturedJdbcTemplate extends JdbcTemplate {
    /** Controlled identity row for the production RowMapper. */
    private final ResultSet result;

    /** Whether the local result contains its optional immutable ledger row. */
    private boolean rowPresent = true;

    /** Last delegated statement and exact bindings, retained independently of row getters. */
    private String sql;

    /** Last statement's original parameter order. */
    private Object[] arguments;

    private CapturedJdbcTemplate(ResultSet result) {
      super(mock(DataSource.class));
      this.result = result;
    }

    /** Evaluate only the production row mapping; this test does not execute SQL. */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments.clone();
      if (!rowPresent) return List.of();
      try {
        return List.of(mapper.mapRow(result, 0));
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    /** Capture inserts without manufacturing database uniqueness or transaction evidence. */
    @Override
    public int update(String sql, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments.clone();
      return 1;
    }
  }

  /**
   * Real Spring transaction synchronization with no physical resource, for the local retry seam.
   */
  private static final class LocalTransactionManager extends AbstractPlatformTransactionManager {
    /** Number of actual proxy transaction begins. */
    private int begins;

    /** Number of successful transaction manager commits. */
    private int commits;

    /** Number of proxy rollbacks after the controlled local insert failures. */
    private int rollbacks;

    /** Distinct transaction objects prove retries enter separate Spring transaction scopes. */
    private final List<Object> transactionIdentities = new ArrayList<>();

    /** Allocate a fresh local transaction object for each REQUIRES_NEW invocation. */
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    /** Record begin while Spring manages its real thread-bound synchronization scope. */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
      transactionIdentities.add(transaction);
    }

    /** Record a completed successful proxy invocation. */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    /** Record a complete failed proxy invocation, without claiming database persistence. */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
