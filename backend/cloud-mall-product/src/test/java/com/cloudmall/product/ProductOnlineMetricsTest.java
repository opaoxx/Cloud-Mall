package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.*;
import com.cloudmall.product.domain.vo.ProductMetricPageVO;
import com.cloudmall.product.mapper.*;
import com.cloudmall.product.service.*;
import com.cloudmall.product.service.impl.*;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.elasticsearch.action.bulk.*;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Seven local tests across Product/Stock complement, rather than replace, the genuine transport
 * ITs.
 */
class ProductOnlineMetricsTest {
  /** Fixed original product fact; all database/runtime dates are exercised independently in IT. */
  private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 10, 16, 10, 0);

  @Test
  void requestIdentityCountsOnlyFinalSuccessfulPublicResult() throws Exception {
    ProductSqlMapper mapper = mock(ProductSqlMapper.class);
    ProductMetricService metrics = mock(ProductMetricService.class);
    ProductController service = service(mapper, metrics, null);
    when(mapper.findProduct(41L)).thenReturn(List.of(fact(41, 7)));
    ApiResponse<?> first = service.product(41L), second = service.product(41L);
    assertNotEquals(first.requestId, second.requestId);
    assertEquals(UUID.fromString(first.requestId).toString(), first.requestId);
    verify(metrics).registerViews(first.requestId, 41L, null);
    verify(metrics).registerViews(second.requestId, 41L, null);
    service.hot(41L);
    verify(metrics).online(41L);
    verify(metrics, times(2)).registerViews(anyString(), eq(41L), isNull());
    assertThrows(BizException.class, () -> service.product(99L));
    verifyNoMoreInteractions(metrics);
    ProductKeywordSearch search = mock(ProductKeywordSearch.class);
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "searchEnabled", true);
    ReflectionTestUtils.setField(
        ProductTestServices.queries(service), "productKeywordSearch", search);
    ProductSearchHit hit =
        new ProductSearchHit(41, 3, "Metric product", new BigDecimal("12.00"), 1, 7, epoch());
    when(search.search("metric", null, 1, 0, 20, null))
        .thenReturn(new ProductSearchPage(List.of(hit), 1));
    when(mapper.findProductFacts(List.of(41L)))
        .thenReturn(List.of(fact(41, 8)), List.of(fact(41, 7)));
    ApiResponse<?> page = service.products(" metric ", null, 1, 1, 20, null);
    verify(search, times(2)).search("metric", null, 1, 0, 20, null);
    verify(metrics).registerSearchPage(page.requestId, List.of(41L), null);
    when(mapper.countProducts(" ", null, 1)).thenReturn(0L);
    service.products(" ", null, 1, 1, 20, null);
    verify(metrics, times(1)).registerSearchPage(anyString(), anyList(), isNull());
    CannotCreateTransactionException commit = new CannotCreateTransactionException("fixed failure");
    doThrow(commit).when(metrics).registerViews(anyString(), eq(41L), isNull());
    BizException unavailable = assertThrows(BizException.class, () -> service.product(41L));
    assertEquals("PRODUCT_METRICS_UNAVAILABLE", unavailable.getCode());
    assertEquals(503, unavailable.getHttpStatus());
    assertSame(commit, unavailable.getCause());
    AssertionError fatal = new AssertionError("fatal");
    doThrow(fatal).when(metrics).registerViews(anyString(), eq(41L), isNull());
    assertSame(fatal, assertThrows(AssertionError.class, () -> service.product(41L)));
    ProductMetricMapper registration = mock(ProductMetricMapper.class);
    ProductMetricService realRegistration = new ProductMetricService(registration);
    realRegistration.registerSearchPage(first.requestId, List.of(41L, 41L, 42L), null);
    verify(registration, times(2)).register(any(), anyString(), any(byte[].class), any());
  }

  @Test
  void strictEnvelopeAndDuplicateFingerprintPreserveCounters() throws Exception {
    String request = UUID.randomUUID().toString();
    ProductMetricEventDTO event = ProductMetricEvents.product(request, 41, true, null);
    byte[] raw = ProductMetricEvents.bytes(event);
    assertEquals(event.eventId, ProductMetricEvents.parse(raw).eventId);
    assertThrows(
        ProductMetricEvents.InvalidEvent.class,
        () ->
            ProductMetricEvents.parse(
                (new String(raw, StandardCharsets.UTF_8) + " {}")
                    .getBytes(StandardCharsets.UTF_8)));
    for (String mutation :
        List.of(
            new String(raw, StandardCharsets.UTF_8)
                .replace("\"payloadVersion\":1", "\"payloadVersion\":1,\"payloadVersion\":1"),
            new String(raw, StandardCharsets.UTF_8).replace("\"quantity\":1", "\"quantity\":1.0"),
            new String(raw, StandardCharsets.UTF_8)
                .replace("\"quantity\":1", "\"quantity\":2147483648"),
            new String(raw, StandardCharsets.UTF_8)
                .replace("\"traceId\":null", "\"unknown\":null")))
      assertThrows(
          ProductMetricEvents.InvalidEvent.class,
          () -> ProductMetricEvents.parse(mutation.getBytes(StandardCharsets.UTF_8)));
    ProductMetricMapper mapper = mock(ProductMetricMapper.class);
    ProductMetricControlPO control = new ProductMetricControlPO();
    control.seriesId = ProductMetricEvents.SERIES;
    control.revision = 0L;
    ProductOnlineStatPO stat = stat(41, 0, 0, 0, 0);
    AtomicReference<ProductMetricAppliedEventPO> applied = new AtomicReference<>();
    when(mapper.control(false)).thenReturn(control);
    when(mapper.control(true)).thenReturn(control);
    when(mapper.stat(41L, true)).thenReturn(stat);
    when(mapper.advance()).thenReturn(1);
    when(mapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
        .thenAnswer(
            invocation -> {
              ProductMetricAppliedEventPO previous = applied.get();
              return new ProductMetricInitialApplySnapshotDTO(
                  control,
                  previous == null ? null : event.eventId,
                  previous == null ? null : previous.fingerprint,
                  true);
            });
    doAnswer(
            invocation -> {
              ProductMetricAppliedEventPO row = new ProductMetricAppliedEventPO();
              row.fingerprint = invocation.getArgument(1);
              applied.set(row);
              return null;
            })
        .when(mapper)
        .insertApplied(eq(event), anyString(), eq(1L), any());
    ProductMetricService real = new ProductMetricService(mapper);
    assertEquals("APPLIED", real.apply(event));
    assertEquals(1L, stat.searchCount);
    assertEquals(1L, stat.hotScore);
    assertEquals(1L, stat.metricVersion);
    event.traceId = "different trace";
    assertEquals("DUPLICATE", real.apply(event));
    verify(mapper, times(1)).advance();
    verify(mapper, times(1)).updateStat(eq(stat), eq(event.eventId), any());
    verify(mapper, never()).applied(anyString());
    verify(mapper, times(1)).stat(41L, true);
    verify(mapper, times(1)).ensureStat(eq(41L), any());
    verify(mapper, never()).applySnapshot(anyString(), anyLong());
    event.occurredAt =
        java.time.OffsetDateTime.parse(event.occurredAt)
            .plusSeconds(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"));
    assertThrows(ProductMetricEvents.InvalidEvent.class, () -> real.apply(event));
    assertEquals(1L, stat.metricVersion);
    control.startedAt = UPDATED;
    when(mapper.control(false)).thenReturn(control);
    assertEquals("ONLINE_EVENT", real.online(41).sourceType());
    assertEquals(0L, real.online(41).soldCount());
    ProductMetricEventDTO sale = sale("202610metrics1", 2);
    sale.sourceRef = "seckill_quota_settlement:" + sale.orderNo;
    assertThrows(ProductMetricEvents.InvalidEvent.class, () -> ProductMetricEvents.validate(sale));
    ProductMetricConsumer consumer =
        new ProductMetricConsumer(
            mock(ProductMetricService.class),
            mock(ProductMetricOutboxDispatcher.class),
            mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class));
    assertNotNull(consumer);
    ProductMetricService delivery = mock(ProductMetricService.class);
    ProductMetricOutboxDispatcher publisher = mock(ProductMetricOutboxDispatcher.class);
    var registry =
        mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
    var channel = mock(com.rabbitmq.client.Channel.class);
    var properties = new org.springframework.amqp.core.MessageProperties();
    properties.setDeliveryTag(7);
    properties.setMessageId("bad-message");
    byte[] bad = "{}".getBytes(StandardCharsets.UTF_8);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixed"))
        .when(delivery)
        .quarantine(anyString(), eq(bad), isNull(), anyString());
    new ProductMetricConsumer(delivery, publisher, registry)
        .consume(new org.springframework.amqp.core.Message(bad, properties), channel);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).basicNack(7, false, true);
    verifyNoInteractions(publisher);
    var originalTemplate = mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
    when(originalTemplate.getConnectionFactory())
        .thenReturn(
            mock(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class));
    ProductMetricOutboxDispatcher actualSender =
        new ProductMetricOutboxDispatcher(delivery, originalTemplate);
    var network = mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
    ReflectionTestUtils.setField(actualSender, "rabbit", network);
    doAnswer(
            invocation -> {
              org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                  invocation.getArgument(3);
              correlation
                  .getFuture()
                  .set(
                      new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(
                          true, null));
              return null;
            })
        .when(network)
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    properties.setHeader("cm21-original-header", "retained");
    properties.setContentEncoding("UTF-8");
    properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT);
    var originalMessage = new org.springframework.amqp.core.Message(raw, properties);
    assertTrue(actualSender.transfer("owned-exchange", "owned-routing", originalMessage));
    ArgumentCaptor<org.springframework.amqp.core.Message> sent =
        ArgumentCaptor.forClass(org.springframework.amqp.core.Message.class);
    verify(network)
        .send(
            eq("owned-exchange"),
            eq("owned-routing"),
            sent.capture(),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    assertArrayEquals(raw, sent.getValue().getBody());
    assertEquals(
        "retained",
        sent.getValue().getMessageProperties().getHeaders().get("cm21-original-header"));
    assertEquals("UTF-8", sent.getValue().getMessageProperties().getContentEncoding());
    assertEquals(
        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,
        sent.getValue().getMessageProperties().getDeliveryMode());
    assertEquals(
        org.springframework.amqp.core.MessageDeliveryMode.NON_PERSISTENT,
        properties.getDeliveryMode());
    properties.setMessageId(null);
    assertTrue(
        actualSender.transfer(
            "owned-exchange",
            "owned-routing",
            new org.springframework.amqp.core.Message(bad, properties)));
    ArgumentCaptor<org.springframework.amqp.rabbit.connection.CorrelationData> correlation =
        ArgumentCaptor.forClass(org.springframework.amqp.rabbit.connection.CorrelationData.class);
    verify(network, times(2))
        .send(eq("owned-exchange"), eq("owned-routing"), sent.capture(), correlation.capture());
    assertNull(
        sent.getAllValues()
            .get(sent.getAllValues().size() - 1)
            .getMessageProperties()
            .getMessageId());
    assertNotNull(
        UUID.fromString(
            correlation.getAllValues().get(correlation.getAllValues().size() - 1).getId()));
    actualSender.close();

    // Exercise the existing private row reader without changing its visibility or JDBC boundary.
    var rowReader =
        ProductMetricMapper.class.getDeclaredMethod("read", java.sql.ResultSet.class, Class.class);
    rowReader.setAccessible(true);
    LocalDateTime millisecondDate = LocalDateTime.of(2026, 10, 17, 16, 2, 3, 456000000);
    java.sql.ResultSet directDates = mock(java.sql.ResultSet.class);
    when(directDates.getObject("created_at")).thenReturn(millisecondDate);
    ProductOnlineStatPO direct =
        (ProductOnlineStatPO) rowReader.invoke(null, directDates, ProductOnlineStatPO.class);
    assertSame(millisecondDate, direct.createdAt);
    assertEquals(456000000, direct.createdAt.getNano());
    verify(directDates, never()).getString(anyString());

    java.sql.ResultSet fallbackDates = mock(java.sql.ResultSet.class);
    String storedDate = "2026-10-17 16:02:03.456";
    when(fallbackDates.getObject("created_at")).thenReturn(java.sql.Timestamp.valueOf(storedDate));
    when(fallbackDates.getString("created_at")).thenReturn(storedDate);
    ProductOnlineStatPO fallback =
        (ProductOnlineStatPO) rowReader.invoke(null, fallbackDates, ProductOnlineStatPO.class);
    assertEquals(millisecondDate, fallback.createdAt);
    verify(fallbackDates).getString("created_at");

    java.sql.ResultSet nullDates = mock(java.sql.ResultSet.class);
    ProductOnlineStatPO absent =
        (ProductOnlineStatPO) rowReader.invoke(null, nullDates, ProductOnlineStatPO.class);
    assertNull(absent.createdAt);
    verify(nullDates).getObject("created_at");
    verify(nullDates, never()).getString(anyString());

    java.sql.ResultSet failedDates = mock(java.sql.ResultSet.class);
    java.sql.SQLException originalReadFailure =
        new java.sql.SQLException("owned date read failure");
    when(failedDates.getObject("created_at")).thenThrow(originalReadFailure);
    var reflectedReadFailure =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> rowReader.invoke(null, failedDates, ProductOnlineStatPO.class));
    assertSame(originalReadFailure, reflectedReadFailure.getCause());
    verify(failedDates, never()).getString(anyString());
    verifyLateApplyContract();
    verifyConsumerLifecycleInterleavings();
  }

  /** Check application branches independently of JDBC mapping or real transaction/lock claims. */
  private static void verifyLateApplyContract() throws Exception {
    var transaction =
        ProductMetricService.class
            .getMethod("apply", ProductMetricEventDTO.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertNotNull(transaction);
    assertEquals(
        org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
        transaction.propagation());
    assertEquals(3, transaction.timeout());
    assertEquals(
        org.springframework.transaction.annotation.Isolation.DEFAULT, transaction.isolation());

    ProductMetricEventDTO event =
        ProductMetricEvents.product(UUID.randomUUID().toString(), 41L, false, null);
    ProductMetricControlPO control = new ProductMetricControlPO();
    control.seriesId = ProductMetricEvents.SERIES;
    ProductMetricMapper invalidEnvelopeMapper = mock(ProductMetricMapper.class);
    assertThrows(
        ProductMetricEvents.InvalidEvent.class,
        () -> new ProductMetricService(invalidEnvelopeMapper).apply(null));
    verifyNoInteractions(invalidEnvelopeMapper);
    ProductMetricControlPO wrongSeries = new ProductMetricControlPO();
    wrongSeries.seriesId = "unrelated-series";
    ProductMetricControlPO uppercaseSeries = new ProductMetricControlPO();
    uppercaseSeries.seriesId = ProductMetricEvents.SERIES.toUpperCase(Locale.ROOT);
    ProductMetricControlPO trailingSeries = new ProductMetricControlPO();
    trailingSeries.seriesId = ProductMetricEvents.SERIES + " ";
    for (ProductMetricControlPO badControl :
        Arrays.asList(null, wrongSeries, uppercaseSeries, trailingSeries)) {
      ProductMetricMapper invalidMapper = mock(ProductMetricMapper.class);
      when(invalidMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
          .thenReturn(
              new ProductMetricInitialApplySnapshotDTO(
                  badControl, event.eventId, "conflicting-fingerprint", false));
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> new ProductMetricService(invalidMapper).apply(event));
      assertEquals("METRIC_CONTROL_INVALID", failure.getMessage());
      verify(invalidMapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
      verifyNoMoreInteractions(invalidMapper);
    }

    ProductMetricMapper absentControlMapper = mock(ProductMetricMapper.class);
    assertEquals(
        "METRIC_CONTROL_INVALID",
        assertThrows(
                IllegalStateException.class,
                () -> new ProductMetricService(absentControlMapper).apply(event))
            .getMessage());
    verify(absentControlMapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
    verifyNoMoreInteractions(absentControlMapper);

    ProductMetricMapper existingMapper = mock(ProductMetricMapper.class);
    ProductOnlineStatPO existing = stat(41L, 2, 3, 4, 7);
    when(existingMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
        .thenReturn(new ProductMetricInitialApplySnapshotDTO(control, null, null, true));
    when(existingMapper.control(true)).thenReturn(control);
    when(existingMapper.stat(41L, true)).thenReturn(existing);
    when(existingMapper.advance()).thenReturn(1);
    assertEquals("APPLIED", new ProductMetricService(existingMapper).apply(event));
    assertEquals(3L, existing.viewCount);
    assertEquals(3L, existing.searchCount);
    assertEquals(4L, existing.soldCount);
    assertEquals(46L, existing.hotScore);
    assertEquals(8L, existing.metricVersion);
    var existingOrder = inOrder(existingMapper);
    existingOrder
        .verify(existingMapper)
        .initialApplySnapshot(event.eventId, event.productId, event.skuId);
    existingOrder.verify(existingMapper).ensureStat(eq(41L), any());
    existingOrder.verify(existingMapper).stat(41L, true);
    existingOrder
        .verify(existingMapper)
        .insertApplied(eq(event), eq(ProductMetricEvents.fingerprint(event)), eq(8L), any());
    existingOrder.verify(existingMapper).updateStat(eq(existing), eq(event.eventId), any());
    existingOrder.verify(existingMapper).control(true);
    existingOrder.verify(existingMapper).advance();
    existingOrder.verifyNoMoreInteractions();

    for (ProductOnlineStatPO unrelatedStat :
        Arrays.asList(null, stat(41L, Long.MAX_VALUE, 0, 0, Long.MAX_VALUE))) {
      ProductMetricMapper duplicateMapper = mock(ProductMetricMapper.class);

      when(duplicateMapper.control(true)).thenReturn(control);
      when(duplicateMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
          .thenReturn(
              new ProductMetricInitialApplySnapshotDTO(
                  control, event.eventId, ProductMetricEvents.fingerprint(event), false));
      assertEquals("DUPLICATE", new ProductMetricService(duplicateMapper).apply(event));
      verify(duplicateMapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
      verify(duplicateMapper).control(true);
      verifyNoMoreInteractions(duplicateMapper);
    }

    ProductMetricEventDTO originalSale = sale("202610snapshot-conflict", 2);
    ProductMetricEventDTO differentProduct =
        ProductMetricEvents.parse(ProductMetricEvents.bytes(originalSale));
    differentProduct.productId = originalSale.productId + 1;
    ProductMetricEvents.validate(differentProduct);
    assertEquals(originalSale.eventId, differentProduct.eventId);
    ProductMetricMapper conflictMapper = mock(ProductMetricMapper.class);

    when(conflictMapper.control(true)).thenReturn(control);
    when(conflictMapper.initialApplySnapshot(
            differentProduct.eventId, differentProduct.productId, differentProduct.skuId))
        .thenReturn(
            new ProductMetricInitialApplySnapshotDTO(
                control,
                originalSale.eventId,
                ProductMetricEvents.fingerprint(originalSale),
                false));
    ProductMetricEvents.InvalidEvent conflict =
        assertThrows(
            ProductMetricEvents.InvalidEvent.class,
            () -> new ProductMetricService(conflictMapper).apply(differentProduct));
    assertEquals("EVENT_FINGERPRINT_CONFLICT", conflict.reason());
    verify(conflictMapper)
        .initialApplySnapshot(
            differentProduct.eventId, differentProduct.productId, differentProduct.skuId);
    verify(conflictMapper).control(true);
    verifyNoMoreInteractions(conflictMapper);

    ProductMetricMapper firstMapper = mock(ProductMetricMapper.class);
    ProductOnlineStatPO first = stat(41L, 0, 0, 0, 0);
    when(firstMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
        .thenReturn(new ProductMetricInitialApplySnapshotDTO(control, null, null, true));
    when(firstMapper.control(true)).thenReturn(control);

    when(firstMapper.stat(41L, true)).thenReturn(first);
    when(firstMapper.advance()).thenReturn(1);
    assertEquals("APPLIED", new ProductMetricService(firstMapper).apply(event));
    var firstOrder = inOrder(firstMapper);
    firstOrder
        .verify(firstMapper)
        .initialApplySnapshot(event.eventId, event.productId, event.skuId);
    firstOrder.verify(firstMapper).ensureStat(eq(41L), any());
    firstOrder.verify(firstMapper).stat(41L, true);
    firstOrder
        .verify(firstMapper)
        .insertApplied(eq(event), eq(ProductMetricEvents.fingerprint(event)), eq(1L), any());
    firstOrder.verify(firstMapper).updateStat(eq(first), eq(event.eventId), any());
    firstOrder.verify(firstMapper).control(true);
    firstOrder.verify(firstMapper).advance();
    firstOrder.verifyNoMoreInteractions();

    ProductMetricMapper missingMapper = mock(ProductMetricMapper.class);
    when(missingMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
        .thenReturn(new ProductMetricInitialApplySnapshotDTO(control, null, null, false));
    when(missingMapper.control(true)).thenReturn(control);

    ProductMetricEvents.InvalidEvent missing =
        assertThrows(
            ProductMetricEvents.InvalidEvent.class,
            () -> new ProductMetricService(missingMapper).apply(event));
    assertEquals("PRODUCT_MAPPING_MISSING", missing.reason());
    verify(missingMapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
    verify(missingMapper).control(true);
    verify(missingMapper).findAppliedIdentity(event.eventId, true);
    verifyNoMoreInteractions(missingMapper);

    for (ProductOnlineStatPO exhausted :
        List.of(stat(41L, Long.MAX_VALUE, 0, 0, 0), stat(41L, 0, 0, 0, Long.MAX_VALUE))) {
      ProductMetricMapper overflowMapper = mock(ProductMetricMapper.class);
      when(overflowMapper.initialApplySnapshot(event.eventId, event.productId, event.skuId))
          .thenReturn(new ProductMetricInitialApplySnapshotDTO(control, null, null, true));
      when(overflowMapper.control(true)).thenReturn(control);
      when(overflowMapper.stat(41L, true)).thenReturn(exhausted);
      ProductMetricEvents.InvalidEvent overflow =
          assertThrows(
              ProductMetricEvents.InvalidEvent.class,
              () -> new ProductMetricService(overflowMapper).apply(event));
      assertEquals("COUNTER_OVERFLOW", overflow.reason());
      verify(overflowMapper).initialApplySnapshot(event.eventId, event.productId, event.skuId);
      verify(overflowMapper).control(true);
      verify(overflowMapper).findAppliedIdentity(event.eventId, true);
      verify(overflowMapper).ensureStat(eq(41L), any());
      verify(overflowMapper).stat(41L, true);
      verifyNoMoreInteractions(overflowMapper);
    }
  }

  @Test
  void independentVersionsKeepContentMetricsAndTombstones() throws Exception {
    RestHighLevelClient client = mock(RestHighLevelClient.class);
    LocalWriter writer = new LocalWriter(client);
    ProductOnlineStatPO stat = stat(41, 3, 5, 2, 11);
    ProductIndexDocument document =
        new ProductIndexDocument(
            41,
            3,
            "Metric product",
            null,
            null,
            new BigDecimal("12.00"),
            1,
            7,
            UPDATED,
            900,
            800,
            new BigDecimal("123.000000"),
            "LEGACY_IMPORT",
            "source",
            stat);
    writer.upsert(document);
    ArgumentCaptor<UpdateRequest> updates = ArgumentCaptor.forClass(UpdateRequest.class);
    verify(client).update(updates.capture(), eq(RequestOptions.DEFAULT));
    UpdateRequest combined = updates.getValue();
    assertEquals(3, combined.retryOnConflict());
    assertEquals(
        org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL,
        combined.getRefreshPolicy());
    assertTrue(combined.scriptedUpsert());
    Map<?, ?> content = (Map<?, ?>) combined.script().getParams().get("content"),
        metric = (Map<?, ?>) combined.script().getParams().get("metric");
    assertEquals(7L, content.get("productVersion"));
    assertEquals(11L, metric.get("metricVersion"));
    assertEquals(28L, metric.get("onlineHotScore"));
    assertFalse(content.containsKey("onlineSoldCount"));
    assertFalse(metric.containsKey("name"));
    assertFalse(metric.containsKey("visible"));
    assertFalse(metric.containsKey("tombstone"));
    String code = combined.script().getIdOrCode();
    for (String guard :
        List.of(
            "CONTENT_VERSION_CONFLICT",
            "METRIC_VERSION_CONFLICT",
            "CONTENT_DELETE_VERSION_UNPROVEN",
            "priority>oldPriority",
            "incoming.metricVersion>current")) assertTrue(code.contains(guard), guard);
    BulkResponse bulk = mock(BulkResponse.class);
    when(bulk.getItems()).thenReturn(new BulkItemResponse[] {mock(BulkItemResponse.class)});
    when(client.bulk(any(BulkRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(bulk);
    writer.upsertMetrics(List.of(document));
    ArgumentCaptor<BulkRequest> batches = ArgumentCaptor.forClass(BulkRequest.class);
    verify(client).bulk(batches.capture(), eq(RequestOptions.DEFAULT));
    UpdateRequest metricOnly = (UpdateRequest) batches.getValue().requests().get(0);
    assertNull(metricOnly.script().getParams().get("content"));
    assertEquals(metric, metricOnly.script().getParams().get("metric"));
    assertNull(batches.getValue().validate());
    assertEquals(
        org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL,
        batches.getValue().getRefreshPolicy());
    for (var item : batches.getValue().requests())
      assertEquals(
          org.elasticsearch.action.support.WriteRequest.RefreshPolicy.NONE,
          ((UpdateRequest) item).getRefreshPolicy());
    writer.upsertBatch(List.of(document));
    ArgumentCaptor<BulkRequest> contentBatches = ArgumentCaptor.forClass(BulkRequest.class);
    verify(client, times(2)).bulk(contentBatches.capture(), eq(RequestOptions.DEFAULT));
    BulkRequest contentBulk = contentBatches.getValue();
    assertNull(contentBulk.validate());
    assertEquals(
        org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL,
        contentBulk.getRefreshPolicy());
    for (var item : contentBulk.requests())
      assertEquals(
          org.elasticsearch.action.support.WriteRequest.RefreshPolicy.NONE,
          ((UpdateRequest) item).getRefreshPolicy());
    UpdateRequest contentItem = (UpdateRequest) contentBulk.requests().get(0);
    assertEquals(content, contentItem.script().getParams().get("content"));
    assertEquals(metric, contentItem.script().getParams().get("metric"));
    writer.delete(41, 7);
    verify(client, times(2)).update(updates.capture(), eq(RequestOptions.DEFAULT));
    Map<?, ?> tombstone =
        (Map<?, ?>)
            updates
                .getAllValues()
                .get(updates.getAllValues().size() - 1)
                .script()
                .getParams()
                .get("content");
    assertEquals(true, tombstone.get("tombstone"));
    assertEquals(false, tombstone.get("visible"));
    assertEquals(
        org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL,
        updates.getValue().getRefreshPolicy());
    assertThrows(IllegalStateException.class, () -> writer.delete(41));
    String hash = ProductMetricEvents.metricFingerprint(stat);
    stat.updatedAt = UPDATED.plusHours(1);
    assertEquals(hash, ProductMetricEvents.metricFingerprint(stat));
    stat.soldCount = 3L;
    assertNotEquals(hash, ProductMetricEvents.metricFingerprint(stat));
    assertNull(
        new ProductIndexDocument(41, 3, "legacy", null, null, BigDecimal.ONE, 1, 0).onlineStat());
    ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
    ProductIndexWriter contentWriter = mock(ProductIndexWriter.class);
    ProductIndexConsumptionService receipts = mock(ProductIndexConsumptionService.class);
    ProductIndexConsumer consumer =
        new ProductIndexConsumer(new ObjectMapper(), loader, contentWriter, receipts);
    ReflectionTestUtils.setField(consumer, "metricsEnabled", true);
    when(loader.load(41)).thenReturn(Optional.empty());
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", "PRODUCT_DELETED");
    envelope.put("traceId", "cm21-unit");
    envelope.put("occurredAt", "2026-10-16T10:00:00.000+08:00");
    envelope.put("productId", 41L);
    envelope.put("productVersion", 7L);
    envelope.put("payloadVersion", 1);
    envelope.put("source", "LIVE");
    envelope.put("businessKey", Map.of("name", "productId", "value", 41L));
    envelope.put("payload", Map.of("productId", 41L));
    consumer.consume(new ObjectMapper().writeValueAsBytes(envelope));
    verify(contentWriter).delete(41, 7);
    verify(receipts).record(eventId, 41, 7, 7, "APPLIED", null);
    for (Object untrusted : List.of(2, 1.0, "1")) {
      envelope.put("payloadVersion", untrusted);
      assertThrows(
          IllegalArgumentException.class,
          () -> consumer.consume(new ObjectMapper().writeValueAsBytes(envelope)));
    }
    envelope.put("payloadVersion", 1);
    envelope.put("productVersion", new java.math.BigInteger("18446744073709551623"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.consume(new ObjectMapper().writeValueAsBytes(envelope)));
    envelope.put("productVersion", 8L);
    when(loader.load(41)).thenReturn(Optional.of(document));
    clearInvocations(contentWriter, receipts);
    assertThrows(
        IllegalStateException.class,
        () -> consumer.consume(new ObjectMapper().writeValueAsBytes(envelope)));
    verify(contentWriter, never()).upsert(any());
    verify(receipts).record(eq(eventId), eq(41L), eq(8L), eq(-1L), eq("RETRY"), anyString());
    // The ES-only axis is canonical in single writes, Bulk parameters, scripts and seed documents.
    assertEquals(ProductMetricEvents.SERIES, metric.get("onlineSeriesId"));
    assertFalse(metric.containsKey("metricSeries"));
    assertEquals(
        ProductMetricEvents.SERIES, combined.upsertRequest().sourceAsMap().get("onlineSeriesId"));
    assertFalse(combined.upsertRequest().sourceAsMap().containsKey("metricSeries"));
    assertTrue(code.contains("incoming.onlineSeriesId!='cm21-online-v1'"));
    assertFalse(code.contains("incoming.metricSeries"));
    assertEquals(
        ProductMetricEvents.SERIES,
        ((Map<?, ?>) metricOnly.script().getParams().get("metric")).get("onlineSeriesId"));
    LinkedHashMap<String, Object> canonicalMetric = new LinkedHashMap<>();
    canonicalMetric.put("onlineSeriesId", ProductMetricEvents.SERIES);
    canonicalMetric.put("id", 41L);
    canonicalMetric.put("onlineViewCount", 3L);
    canonicalMetric.put("onlineSearchCount", 5L);
    canonicalMetric.put("onlineSoldCount", 2L);
    canonicalMetric.put("onlineHotScore", 28L);
    canonicalMetric.put("metricVersion", 11L);
    String canonicalHash =
        ProductMetricEvents.sha256(new ObjectMapper().writeValueAsBytes(canonicalMetric));
    assertEquals(canonicalHash, metric.get("onlineFingerprint"));
    assertEquals(canonicalHash, ProductMetricEvents.metricFingerprint(stat(41, 3, 5, 2, 11)));
  }

  @Test
  void onlineSortAdmissionUsesStableProjectionCut() throws Exception {
    ProductSqlMapper mapper = mock(ProductSqlMapper.class);
    ProductMetricService metrics = mock(ProductMetricService.class);
    ProductKeywordSearch search = mock(ProductKeywordSearch.class);
    ProductController service = service(mapper, metrics, search);
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "metricSortMode", "ONLINE");
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "searchEnabled", true);
    javax.sql.DataSource resource = mock(javax.sql.DataSource.class);
    java.sql.Connection physical = mock(java.sql.Connection.class);
    when(resource.getConnection()).thenReturn(physical);
    when(physical.getAutoCommit()).thenReturn(true);
    ReflectionTestUtils.setField(
        ProductTestServices.queries(service),
        "transactionManager",
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(resource));
    AtomicReference<Object> postSnapshot = new AtomicReference<>();
    var cut = new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "uuid", true, false);
    when(metrics.cut()).thenReturn(cut);
    when(search.indexUuid(anyLong())).thenReturn("uuid");
    ProductSearchHit hit =
        new ProductSearchHit(
            41,
            3,
            "Metric product",
            new BigDecimal("12.00"),
            1,
            7,
            epoch(),
            2L,
            ProductMetricEvents.SERIES,
            1L,
            1L,
            2L,
            22L,
            "content",
            "metric");
    when(search.search(eq("metric"), isNull(), eq(1), eq(0), eq(20), eq("sales"), anyLong()))
        .thenReturn(new ProductSearchPage(List.of(hit), 1));
    when(mapper.findProductFacts(List.of(41L)))
        .thenAnswer(
            invocation -> {
              Object holder =
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .getResource(resource);
              assertNotNull(holder);
              postSnapshot.set(holder);
              assertTrue(
                  ((org.springframework.jdbc.datasource.ConnectionHolder) holder).hasTimeout());
              assertTrue(
                  ((org.springframework.jdbc.datasource.ConnectionHolder) holder)
                          .getTimeToLiveInSeconds()
                      <= 3);
              return List.of(fact(41, 7));
            });
    when(metrics.admit(cut, List.of(hit)))
        .thenAnswer(
            invocation -> {
              assertSame(
                  postSnapshot.get(),
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .getResource(resource));
              assertTrue(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              return true;
            });
    ApiResponse<?> response = service.products("metric", null, 1, 1, 20, "sales");
    assertEquals(9L, ((ProductMetricPageVO<?>) response.data).metricReadRevision);
    verify(metrics).registerSearchPage(response.requestId, List.of(41L), null);
    clearInvocations(metrics, search);
    doReturn(false).when(metrics).admit(cut, List.of(hit));
    assertEquals(
        "PRODUCT_SEARCH_RETRYABLE",
        assertThrows(BizException.class, () -> service.products("metric", null, 1, 1, 20, "sales"))
            .getCode());
    verify(search, times(2))
        .search(eq("metric"), isNull(), eq(1), eq(0), eq(20), eq("sales"), anyLong());
    verify(metrics, never()).registerSearchPage(anyString(), anyList(), any());
    when(metrics.cut())
        .thenReturn(
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 10, "uuid", false, true));
    clearInvocations(search);
    assertThrows(BizException.class, () -> service.products("metric", null, 1, 1, 20, "hot"));
    verifyNoInteractions(search);
    var annotation =
        ProductMetricService.class
            .getMethod("admit", ProductMetricService.Cut.class, List.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertEquals(
        org.springframework.transaction.annotation.Propagation.REQUIRED, annotation.propagation());
    var environment =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("cloudmall.product.metrics.enabled", "true")
            .withProperty("cloudmall.product.metrics.sort-mode", "ONLINE");
    assertDoesNotThrow(
        () ->
            new com.cloudmall.product.config.ProductIndexFeatureConfiguration(environment)
                .logEffectiveConfiguration());
    environment
        .withProperty("cloudmall.product.search.enabled", "true")
        .withProperty(
            "cloudmall.product.elasticsearch.index",
            "cm21_00000000000000000000000000000000_product")
        .withProperty("cloudmall.product.index.rebuild-enabled", "true");
    assertDoesNotThrow(
        () ->
            new com.cloudmall.product.config.ProductIndexFeatureConfiguration(environment)
                .logEffectiveConfiguration());
    for (String required : List.of("dispatch", "consumer", "rebuild")) {
      environment.withProperty("cloudmall.product.index." + required + "-enabled", "false");
      assertThrows(
          IllegalStateException.class,
          () ->
              new com.cloudmall.product.config.ProductIndexFeatureConfiguration(environment)
                  .logEffectiveConfiguration());
      environment.withProperty("cloudmall.product.index." + required + "-enabled", "true");
    }
    // Both transient pre-cut failures consume the existing two attempts before any ES request.
    clearInvocations(metrics, search);
    when(metrics.cut())
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("unit-cut-one"),
            new org.springframework.transaction.CannotCreateTransactionException("unit-cut-two"));
    BizException cutUnavailable =
        assertThrows(BizException.class, () -> service.products("metric", null, 1, 1, 20, "sales"));
    assertEquals("PRODUCT_SEARCH_RETRYABLE", cutUnavailable.getCode());
    assertEquals(503, cutUnavailable.getHttpStatus());
    verify(metrics, times(2)).cut();
    verifyNoInteractions(search);
    verify(metrics, never()).registerSearchPage(anyString(), anyList(), any());
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "metricSortMode", "online");
    assertThrows(
        IllegalStateException.class,
        ProductTestServices.queries(service)::validateMetricConfiguration);
    verifyCompletedAuditPruning();
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "metricSortMode", "ONLINE");
    doReturn(cut).when(metrics).cut();
    // A concurrent readiness/revision change still fails the original final RR admission.
    clearInvocations(metrics, search);
    assertEquals(
        "PRODUCT_SEARCH_RETRYABLE",
        assertThrows(BizException.class, () -> service.products("metric", null, 1, 1, 20, "sales"))
            .getCode());
    verify(metrics, times(2)).admit(cut, List.of(hit));
    verify(metrics, never()).registerSearchPage(anyString(), anyList(), any());
    // A physical generation change between the two ES metadata reads also rejects both attempts.
    when(search.indexUuid(anyLong())).thenReturn("uuid", "new-uuid", "uuid", "new-uuid");
    clearInvocations(metrics, search);
    assertThrows(BizException.class, () -> service.products("metric", null, 1, 1, 20, "sales"));
    verify(search, times(4)).indexUuid(anyLong());
    verify(metrics, never()).admit(any(), anyList());
    verify(metrics, never()).registerSearchPage(anyString(), anyList(), any());
  }

  /** Exercise each conservative skip gate and the unchanged failure/closing paths without races. */
  private static void verifyCompletedAuditPruning() throws Exception {
    var rebuild = mock(ProductIndexRebuildMapper.class);
    var loader = mock(ProductIndexDocumentLoader.class);
    var writer = mock(ElasticsearchProductIndexWriter.class);
    var metrics = mock(ProductMetricService.class);
    var job = new ProductIndexRebuildJobPO();
    job.status = "COMPLETED";
    var subject =
        new ProductIndexRebuildJob(
            rebuild, loader, writer, new org.springframework.mock.env.MockEnvironment());
    ReflectionTestUtils.setField(subject, "metricsEnabled", true);
    ReflectionTestUtils.setField(subject, "metrics", metrics);
    var pending = new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "uuid", false, true);
    // Each case reverses one gate, retaining all other eligible facts where possible.
    List<ProductMetricService.Cut> cuts =
        List.of(
            pending,
            new ProductMetricService.Cut("invalid-series", 9, "uuid", false, true),
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, null, false, true),
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "", false, true),
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "old-uuid", false, true),
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "uuid", true, true),
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "uuid", false, false));
    for (int scenario = 0; scenario < cuts.size() + 8; scenario++) {
      reset(rebuild, loader, writer, metrics);
      when(rebuild.findJob(anyString())).thenReturn(job);
      when(writer.indexExists()).thenReturn(true);
      when(writer.indexUuid()).thenReturn("uuid");
      when(loader.countSources()).thenReturn(3L);
      when(writer.countDocuments()).thenReturn(3L);
      var observed = scenario < cuts.size() ? cuts.get(scenario) : pending;
      when(metrics.cut()).thenReturn(observed);
      if (scenario == 2) when(writer.indexUuid()).thenReturn(null);
      if (scenario == 3) when(writer.indexUuid()).thenReturn("");
      if (scenario == 7) when(writer.indexExists()).thenReturn(false);
      if (scenario == 8) when(writer.countDocuments()).thenReturn(2L);
      if (scenario == 9) when(writer.countDocuments()).thenReturn(4L);
      RuntimeException failure = new IllegalStateException("audit-failure-" + scenario);
      if (scenario == 10) when(writer.indexExists()).thenThrow(failure);
      if (scenario == 11) when(writer.indexUuid()).thenThrow(failure);
      if (scenario == 12) when(writer.countDocuments()).thenThrow(failure);
      if (scenario == 13) when(metrics.cut()).thenThrow(failure).thenReturn(pending);
      if (scenario == 14) when(loader.countSources()).thenThrow(failure);
      subject.auditCompletedIndex();
      if (scenario == 0) {
        verify(metrics, never()).readiness(any(), anyBoolean());
      } else if (scenario >= 10) {
        verify(metrics).readiness("uuid", false);
      } else {
        boolean requested = scenario != 4 && scenario != 7 && scenario != 8 && scenario != 9;
        verify(metrics)
            .readiness(
                scenario == 7 || scenario == 2 ? null : scenario == 3 ? "" : "uuid", requested);
      }
      if (scenario == 4 || scenario == 7 || scenario == 8)
        verify(rebuild).restartCompleted(anyString(), any());
      else verify(rebuild, never()).restartCompleted(anyString(), any());
    }
    // The original requireControl validates illegal series, including its closing attempt.
    reset(metrics, writer, loader, rebuild);
    when(rebuild.findJob(anyString())).thenReturn(job);
    when(writer.indexExists()).thenReturn(true);
    when(writer.indexUuid()).thenReturn("uuid");
    when(loader.countSources()).thenReturn(3L);
    when(writer.countDocuments()).thenReturn(3L);
    var controlMapper = mock(ProductMetricMapper.class);
    var invalid = new ProductMetricControlPO();
    invalid.seriesId = "invalid-series";
    invalid.revision = 9L;
    invalid.indexUuid = "uuid";
    invalid.indexReady = 0;
    when(controlMapper.control(false)).thenReturn(invalid);
    when(controlMapper.control(true)).thenReturn(invalid);
    var realMetrics = spy(new ProductMetricService(controlMapper));
    ReflectionTestUtils.setField(subject, "metrics", realMetrics);
    subject.auditCompletedIndex();
    verify(realMetrics).readiness("uuid", true);
    verify(realMetrics).readiness("uuid", false);
    verify(controlMapper, times(2)).control(true);
    verify(controlMapper, never()).readiness(any(), anyBoolean());
    // Cut-after-drain can defer this audit; the next settled cut must execute recovery.
    ReflectionTestUtils.setField(subject, "metrics", metrics);
    var live = new AtomicReference<>(pending);
    doAnswer(
            invocation -> {
              var captured = live.get();
              live.set(
                  new ProductMetricService.Cut(
                      ProductMetricEvents.SERIES, 9, "uuid", false, false));
              return captured;
            })
        .when(metrics)
        .cut();
    subject.auditCompletedIndex();
    verify(metrics, never()).readiness(any(), anyBoolean());
    doAnswer(
            invocation -> {
              live.set(
                  new ProductMetricService.Cut(
                      ProductMetricEvents.SERIES, 10, "uuid", true, false));
              return true;
            })
        .when(metrics)
        .readiness("uuid", true);
    subject.auditCompletedIndex();
    assertTrue(live.get().admissible());
    verify(metrics).readiness("uuid", true);
    // Another audit can publish readiness or change UUID after our captured cut; skip writes
    // neither.
    for (var concurrent :
        List.of(
            new ProductMetricService.Cut(ProductMetricEvents.SERIES, 11, "uuid", true, true),
            new ProductMetricService.Cut(
                ProductMetricEvents.SERIES, 12, "new-uuid", false, true))) {
      clearInvocations(metrics);
      doAnswer(
              invocation -> {
                live.set(concurrent);
                return pending;
              })
          .when(metrics)
          .cut();
      subject.auditCompletedIndex();
      assertSame(concurrent, live.get());
      assertFalse(live.get().admissible());
      verify(metrics, never()).readiness(any(), anyBoolean());
      var currentControl = new ProductMetricControlPO();
      currentControl.seriesId = concurrent.series();
      currentControl.revision = concurrent.revision();
      currentControl.indexUuid = concurrent.indexUuid();
      currentControl.indexReady = concurrent.ready() ? 1 : 0;
      when(controlMapper.control(false)).thenReturn(currentControl);
      assertFalse(
          new ProductMetricService(controlMapper)
              .admit(
                  new ProductMetricService.Cut(ProductMetricEvents.SERIES, 9, "uuid", true, false),
                  List.of()));
    }
    ReflectionTestUtils.setField(subject, "metricsEnabled", false);
    clearInvocations(metrics);
    subject.auditCompletedIndex();
    verifyNoInteractions(metrics);
  }

  /** Force the callback/start/pause/close order with bounded latches, never timing guesses. */
  private static void verifyConsumerLifecycleInterleavings() throws Exception {
    var delivery = mock(ProductMetricService.class);
    var publisher = mock(ProductMetricOutboxDispatcher.class);
    var registry =
        mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
    var container =
        mock(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
    when(registry.getListenerContainer("productMetrics")).thenReturn(container);
    var subject = new ProductMetricConsumer(delivery, publisher, registry);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("owned-quarantine"))
        .when(delivery)
        .quarantine(anyString(), any(byte[].class), isNull(), anyString());
    List<Runnable> callbacks = Collections.synchronizedList(new ArrayList<>());
    var completeOnCleanup = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Runnable callback = invocation.getArgument(0);
              callbacks.add(callback);
              if (completeOnCleanup.get()) callback.run();
              return null;
            })
        .when(container)
        .stop(any(Runnable.class));
    var firstChannel = mock(com.rabbitmq.client.Channel.class);
    var secondChannel = mock(com.rabbitmq.client.Channel.class);
    var firstProperties = new org.springframework.amqp.core.MessageProperties();
    firstProperties.setDeliveryTag(17);
    var secondProperties = new org.springframework.amqp.core.MessageProperties();
    secondProperties.setDeliveryTag(23);
    var first = new org.springframework.amqp.core.Message(new byte[] {'{', '}'}, firstProperties);
    var second = new org.springframework.amqp.core.Message(new byte[] {'{', '}'}, secondProperties);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    CountDownLatch releaseFirstStart = new CountDownLatch(1);
    CountDownLatch releaseClosingStart = new CountDownLatch(1);
    try {
      subject.consume(first, firstChannel);
      long firstDeadline = (long) consumerState(subject, "restartAfter");
      subject.consume(second, secondChannel);
      assertTrue((long) consumerState(subject, "restartAfter") >= firstDeadline);
      assertEquals(1, callbacks.size());
      expireConsumerPause(subject);
      subject.recoverListener();
      verify(container, never()).start();
      callbacks.get(0).run();
      subject.recoverListener();
      verify(container).start();
      subject.consume(first, firstChannel);
      assertEquals(2, callbacks.size());
      expireConsumerPause(subject);
      callbacks.get(0).run();
      assertEquals(1L, ((CountDownLatch) consumerState(subject, "stopCompletion")).getCount());
      subject.recoverListener();
      verify(container).start();
      callbacks.get(1).run();
      CountDownLatch firstStartEntered = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                firstStartEntered.countDown();
                assertTrue(releaseFirstStart.await(5, TimeUnit.SECONDS));
                return null;
              })
          .when(container)
          .start();
      Future<?> restarting = workers.submit(subject::recoverListener);
      assertTrue(firstStartEntered.await(5, TimeUnit.SECONDS));
      subject.consume(second, secondChannel);
      long newerDeadline = (long) consumerState(subject, "restartAfter");
      assertTrue(newerDeadline > System.nanoTime());
      releaseFirstStart.countDown();
      restarting.get(5, TimeUnit.SECONDS);
      assertEquals(newerDeadline, (long) consumerState(subject, "restartAfter"));
      assertEquals(3, callbacks.size());
      callbacks.get(1).run();
      assertEquals(1L, ((CountDownLatch) consumerState(subject, "stopCompletion")).getCount());
      callbacks.get(2).run();
      expireConsumerPause(subject);
      CountDownLatch closingStartEntered = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                closingStartEntered.countDown();
                assertTrue(releaseClosingStart.await(5, TimeUnit.SECONDS));
                return null;
              })
          .when(container)
          .start();
      Future<?> startingAtClose = workers.submit(subject::recoverListener);
      assertTrue(closingStartEntered.await(5, TimeUnit.SECONDS));
      Future<?> closing = workers.submit(subject::close);
      awaitConsumerFlag(subject, "closeRequested");
      assertEquals(false, consumerState(subject, "closed"));
      subject.consume(first, firstChannel); // Does not wait for the blocked operation/close.
      assertFalse(closing.isDone());
      releaseClosingStart.countDown();
      startingAtClose.get(5, TimeUnit.SECONDS);
      awaitConsumerFlag(subject, "closed");
      assertEquals(4, callbacks.size());
      assertFalse(closing.isDone());
      subject.consume(
          second, secondChannel); // Terminal close neither restarts nor duplicates stop.
      assertEquals(4, callbacks.size());
      callbacks.get(3).run();
      closing.get(5, TimeUnit.SECONDS);
      expireConsumerPause(subject);
      subject.recoverListener();
      verify(container, times(3)).start();
      verify(firstChannel, atLeastOnce()).basicNack(17, false, true);
      verify(secondChannel, atLeastOnce()).basicNack(23, false, true);
      verify(firstChannel, never()).basicAck(anyLong(), anyBoolean());
      verify(secondChannel, never()).basicAck(anyLong(), anyBoolean());
      var failedContainer =
          mock(org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class);
      var failedRegistry =
          mock(org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry.class);
      when(failedRegistry.getListenerContainer("productMetrics")).thenReturn(failedContainer);
      var sharedFailure = new IllegalStateException("shared-nack-and-stop");
      var failedChannel = mock(com.rabbitmq.client.Channel.class);
      doThrow(sharedFailure).when(failedChannel).basicNack(17, false, true);
      doThrow(sharedFailure).when(failedContainer).stop(any(Runnable.class));
      var failedConsumer = new ProductMetricConsumer(delivery, publisher, failedRegistry);
      assertSame(
          sharedFailure,
          assertThrows(
              IllegalStateException.class, () -> failedConsumer.consume(first, failedChannel)));
      assertEquals(0, sharedFailure.getSuppressed().length);
      var destroyedConsumer = new ProductMetricConsumer(delivery, publisher, failedRegistry);
      var destroyProcessor =
          new org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor();
      destroyProcessor.setDestroyAnnotationType(javax.annotation.PreDestroy.class);
      destroyProcessor.postProcessBeforeInitialization(destroyedConsumer, "ownedMetricConsumer");
      assertDoesNotThrow(
          () ->
              destroyProcessor.postProcessBeforeDestruction(
                  destroyedConsumer, "ownedMetricConsumer"));
      assertSame(sharedFailure, consumerState(destroyedConsumer, "closeFailure"));
      assertEquals(true, consumerState(destroyedConsumer, "closeCompleted"));
      assertTrue((long) consumerState(destroyedConsumer, "closeStartedNanos") != 0);
      assertTrue((long) consumerState(destroyedConsumer, "closeElapsedNanos") >= 0);

    } finally {
      completeOnCleanup.set(true);
      releaseFirstStart.countDown();
      releaseClosingStart.countDown();
      for (Runnable callback : List.copyOf(callbacks)) callback.run();
      workers.shutdown();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /** Synthetic deadlines are changed only while holding the same private state gate. */
  private static void expireConsumerPause(ProductMetricConsumer consumer) {
    synchronized (Objects.requireNonNull(ReflectionTestUtils.getField(consumer, "stateGate"))) {
      ReflectionTestUtils.setField(consumer, "restartAfter", System.nanoTime() - 1);
    }
  }

  private static Object consumerState(ProductMetricConsumer consumer, String field) {
    synchronized (Objects.requireNonNull(ReflectionTestUtils.getField(consumer, "stateGate"))) {
      return ReflectionTestUtils.getField(consumer, field);
    }
  }

  private static void awaitConsumerFlag(ProductMetricConsumer consumer, String field) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Boolean.TRUE.equals(consumerState(consumer, field)) && System.nanoTime() < deadline)
      Thread.yield();
    assertEquals(true, consumerState(consumer, field));
  }

  private static ProductController service(
      ProductSqlMapper mapper, ProductMetricService metrics, ProductKeywordSearch search) {
    ProductController service =
        ProductTestServices.controller(
            mapper,
            mock(StringRedisTemplate.class),
            new ObjectMapper(),
            mock(ProductIndexOutboxService.class));
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "metricsEnabled", true);
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "metrics", metrics);
    ReflectionTestUtils.setField(
        ProductTestServices.queries(service), "productKeywordSearch", search);
    return service;
  }

  private static Map<String, Object> fact(long id, long version) {
    Map<String, Object> row = new HashMap<>();
    row.put("id", id);
    row.put("category_id", 3L);
    row.put("name", "Metric product");
    row.put("price", new BigDecimal("12.00"));
    row.put("status", 1);
    row.put("version", version);
    row.put("updated_at", UPDATED);
    return row;
  }

  private static long epoch() {
    return UPDATED.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
  }

  private static ProductOnlineStatPO stat(
      long id, long views, long searches, long sold, long version) {
    ProductOnlineStatPO stat = new ProductOnlineStatPO();
    stat.productId = id;
    stat.viewCount = views;
    stat.searchCount = searches;
    stat.soldCount = sold;
    stat.hotScore = views + searches + 10 * sold;
    stat.metricVersion = version;
    return stat;
  }

  private static ProductMetricEventDTO sale(String order, int quantity) {
    ProductMetricEventDTO event = new ProductMetricEventDTO();
    event.payloadVersion = 1;
    event.seriesId = ProductMetricEvents.SERIES;
    event.eventType = "STOCK_SALE_CONFIRMED";
    event.producer = "STOCK";
    event.productId = 41L;
    event.skuId = 71L;
    event.quantity = quantity;
    event.orderNo = order;
    event.sourceRevision = 5L;
    event.sourceRef = "stock_flow:" + order + ":CONFIRM:71";
    event.occurredAt = "2026-10-16T10:00:00.000+08:00";
    event.eventId = ProductMetricEvents.identity(event);
    return event;
  }

  /** Local request-capture seam only; live ITs use the original ensureIndex and real ES. */
  private static final class LocalWriter extends ElasticsearchProductIndexWriter {
    private LocalWriter(RestHighLevelClient client) {
      super(client, new ObjectMapper(), "cm21_00000000000000000000000000000000_product");
      ReflectionTestUtils.setField(this, "metricsEnabled", true);
    }

    @Override
    public void ensureIndex() {}
  }
}
