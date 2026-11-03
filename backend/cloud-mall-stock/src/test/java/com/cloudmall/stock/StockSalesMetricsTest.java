package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.StockSalesEventDTO;
import com.cloudmall.stock.domain.po.*;
import com.cloudmall.stock.mapper.*;
import com.cloudmall.stock.service.*;
import com.cloudmall.stock.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local original-entry tests; database rollback and real broker proof belong to the fixed ITs. */
class StockSalesMetricsTest {
  @Test
  void normalFirstConfirmUsesLockedSkuAndAtomicOutbox() throws Exception {
    StockSqlMapper stock = normalMapper("202610normal", 3);
    StockSalesOutboxMapper outbox = mock(StockSalesOutboxMapper.class);
    StockSalesOutboxService events = new StockSalesOutboxService(outbox);
    when(outbox.flowProof("202610normal", 71)).thenReturn(proof("202610normal", 3));
    StockServiceImpl service = stock(stock, events);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      service.confirm("202610normal");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    ArgumentCaptor<StockSalesEventDTO> event = ArgumentCaptor.forClass(StockSalesEventDTO.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(outbox).insert(event.capture(), anyString(), body.capture(), any());
    StockSalesEventDTO saved = event.getValue();
    assertEquals(41L, saved.productId);
    assertEquals(71L, saved.skuId);
    assertEquals(3, saved.quantity);
    assertEquals(8L, saved.sourceRevision);
    assertEquals("stock_flow:202610normal:CONFIRM:71", saved.sourceRef);
    assertEquals(sha("CM21:1:STOCK:CONFIRM:202610normal:71"), saved.eventId);
    assertNull(saved.requestId);
    assertEquals(
        saved.eventId, new ObjectMapper().readTree(body.getValue()).path("eventId").asText());
    var ordered = inOrder(stock, outbox);
    ordered.verify(stock).findStockForUpdate(71L);
    ordered.verify(stock).updateSettlementWithVersion(eq(71L), eq("CONFIRM"), eq(3), eq(7), any());
    ordered
        .verify(stock)
        .insertFlow(
            eq(71L),
            eq("202610normal"),
            eq("CONFIRM"),
            eq(3),
            eq("202610normal:CONFIRM:71"),
            any());
    ordered.verify(outbox).flowProof("202610normal", 71);
    ordered.verify(outbox).insert(any(), anyString(), anyString(), any());
    when(stock.findReservationRequestForUpdate("202610normal"))
        .thenReturn(Map.of("order_no", "202610normal", "status", "CONFIRMED"));
    service.confirm("202610normal");
    verify(outbox, times(1)).insert(any(), anyString(), anyString(), any());
    when(stock.findReservationRequestForUpdate("202610normal"))
        .thenReturn(Map.of("order_no", "202610NORMAL", "status", "CONFIRMED"));
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.confirm("202610normal")).getHttpStatus());
    for (String alias : List.of("202610NORMAL", "202610nórmal", "202610normal ")) {
      when(stock.findReservationRequestForUpdate(alias))
          .thenReturn(Map.of("order_no", "202610normal", "status", "CONFIRMED"));
      assertEquals(
          409, assertThrows(BizException.class, () -> service.confirm(alias)).getHttpStatus());
    }
    verify(outbox, times(1)).insert(any(), anyString(), anyString(), any());
    verify(stock, times(1))
        .updateSettlementWithVersion(eq(71L), eq("CONFIRM"), eq(3), eq(7), any());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @Test
  void seckillFirstCasAndReplayUseSameSaleIdentity() throws Exception {
    for (boolean blocked : List.of(false, true)) {
      String order = blocked ? "202610blocked" : "202610seckill";
      SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
      StockSqlMapper stock = mock(StockSqlMapper.class);
      StockSalesOutboxMapper outbox = mock(StockSalesOutboxMapper.class);
      StockSalesOutboxService events = new StockSalesOutboxService(outbox);
      SeckillQuotaSettlementPO
          before = settlement(order, blocked ? "PROJECTION_BLOCKED" : "OPEN", 17),
          after = settlement(order, "CONFIRMED", blocked ? 17 : 18);
      if (blocked) before.operationKey = order + ":CONFIRM";
      when(mapper.findSettlementForUpdate(order)).thenReturn(before, after, after);
      when(mapper.findMatchingAcceptanceId(after)).thenReturn(19L);
      if (blocked)
        when(mapper.transitionBlockedToConfirmed(order, order + ":CONFIRM", 17)).thenReturn(1);
      else when(mapper.transitionOpenToConfirmed(order, order + ":CONFIRM", 17)).thenReturn(1);
      when(stock.findStockForUpdate(71L)).thenReturn(Map.of("product_id", 41L));
      SeckillQuotaSettlementService service = new SeckillQuotaSettlementService(mapper, stock);
      ReflectionTestUtils.setField(service, "salesMetrics", events);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        service.settle(order, "CONFIRM");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      ArgumentCaptor<StockSalesEventDTO> event = ArgumentCaptor.forClass(StockSalesEventDTO.class);
      verify(outbox).insert(event.capture(), anyString(), anyString(), any());
      assertEquals(blocked ? 17L : 18L, event.getValue().sourceRevision);
      assertEquals(1, event.getValue().quantity);
      assertEquals("seckill_quota_settlement:" + order, event.getValue().sourceRef);
      assertEquals(sha("CM21:1:STOCK:CONFIRM:" + order + ":71"), event.getValue().eventId);
      service.settle(order, "CONFIRM");
      verify(outbox, times(1)).insert(any(), anyString(), anyString(), any());
      verify(stock, never())
          .updateSettlementWithVersion(any(), anyString(), anyInt(), anyInt(), any());
    }
  }

  @Test
  void rollbackUnknownAndOutboxFailureKeepOriginalSettlement() throws Exception {
    StockSqlMapper stock = normalMapper("202610failure", 2);
    StockSalesOutboxMapper mapper = mock(StockSalesOutboxMapper.class);
    StockSalesOutboxService events = new StockSalesOutboxService(mapper);
    StockServiceImpl service = stock(stock, events);
    when(mapper.flowProof("202610failure", 71)).thenReturn(proof("202610failure", 2));
    var databaseFailure = new org.springframework.dao.DataAccessResourceFailureException("fixed");
    doThrow(databaseFailure).when(mapper).insert(any(), anyString(), anyString(), any());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      BizException unavailable =
          assertThrows(BizException.class, () -> service.confirm("202610failure"));
      assertEquals("STOCK_SALES_EVENT_UNAVAILABLE", unavailable.getCode());
      assertSame(databaseFailure, unavailable.getCause());
      AssertionError fatal = new AssertionError("fixed");
      doThrow(fatal).when(mapper).insert(any(), anyString(), anyString(), any());
      assertSame(fatal, assertThrows(AssertionError.class, () -> service.confirm("202610failure")));
      clearInvocations(mapper);
      RootContext.bind("cm21-unit-xid");
      try {
        assertEquals(
            503,
            assertThrows(BizException.class, () -> service.confirm("202610failure"))
                .getHttpStatus());
        verifyNoInteractions(mapper);
      } finally {
        RootContext.unbind();
      }
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    clearInvocations(mapper);
    when(stock.findReservationRequestForUpdate("202610failure"))
        .thenReturn(Map.of("order_no", "202610failure", "status", "ROLLED_BACK"));
    assertThrows(BizException.class, () -> service.confirm("202610failure"));
    verifyNoInteractions(mapper);
    SeckillQuotaSettlementMapper settlements = mock(SeckillQuotaSettlementMapper.class);
    when(settlements.findSettlementForUpdate("202610release"))
        .thenReturn(settlement("202610release", "OPEN", 1));
    when(settlements.transitionToReleasePending("202610release", "202610release:ROLLBACK", 1))
        .thenReturn(1);
    SeckillQuotaSettlementService seckill = new SeckillQuotaSettlementService(settlements, stock);
    ReflectionTestUtils.setField(seckill, "salesMetrics", events);
    seckill.settle("202610release", "ROLLBACK");
    verifyNoInteractions(mapper);
    StockSalesOutboxService proxy = mock(StockSalesOutboxService.class);
    StockSalesOutboxPO row = new StockSalesOutboxPO();
    row.eventId = "a".repeat(64);
    row.payloadJson = "{}";
    when(proxy.claim(anyString())).thenReturn(List.of(row));
    when(proxy.finish(eq(row.eventId), anyString(), eq(false), eq("PUBLISH_UNCERTAIN")))
        .thenReturn(1);
    RabbitTemplate original = mock(RabbitTemplate.class);
    when(original.getConnectionFactory()).thenReturn(mock(CachingConnectionFactory.class));
    StockSalesOutboxDispatcher sender = new StockSalesOutboxDispatcher(proxy, original);
    RabbitTemplate transport = mock(RabbitTemplate.class);
    ReflectionTestUtils.setField(sender, "rabbit", transport);
    doThrow(new IllegalStateException("fixed"))
        .when(transport)
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    sender.dispatch();
    assertTrue(((Set<?>) ReflectionTestUtils.getField(sender, "owned")).isEmpty());
    verify(proxy).finish(eq(row.eventId), anyString(), eq(false), eq("PUBLISH_UNCERTAIN"));
    sender.close();
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
  }

  private static StockServiceImpl stock(StockSqlMapper mapper, StockSalesOutboxService events) {
    StockServiceImpl service =
        new StockServiceImpl(
            mock(StringRedisTemplate.class),
            mapper,
            mock(RabbitTemplate.class),
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "salesMetrics", events);
    return service;
  }

  private static StockSqlMapper normalMapper(String order, int quantity) {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findReservationRequestForUpdate(order))
        .thenReturn(Map.of("order_no", order, "status", "RESERVED"));
    when(mapper.findOpenReservations(order))
        .thenReturn(List.of(Map.of("sku_id", 71L, "quantity", quantity)));
    when(mapper.findStockForUpdate(71L))
        .thenReturn(
            Map.of(
                "product_id",
                41L,
                "available_quantity",
                8,
                "reserved_quantity",
                quantity,
                "sold_quantity",
                0,
                "version",
                7));
    when(mapper.updateSettlementWithVersion(eq(71L), eq("CONFIRM"), eq(quantity), eq(7), any()))
        .thenReturn(1);
    when(mapper.updateReservationRequestStatus(
            eq(order), eq("RESERVED"), eq("CONFIRMED"), isNull(), any()))
        .thenReturn(1);
    return mapper;
  }

  private static List<Map<String, Object>> proof(String order, int quantity) {
    return List.of(
        Map.of(
            "order_no",
            order,
            "sku_id",
            71L,
            "flow_type",
            "RESERVE",
            "quantity",
            quantity,
            "idempotency_key",
            order + ":RESERVE:71"),
        Map.of(
            "order_no",
            order,
            "sku_id",
            71L,
            "flow_type",
            "CONFIRM",
            "quantity",
            quantity,
            "idempotency_key",
            order + ":CONFIRM:71"));
  }

  private static SeckillQuotaSettlementPO settlement(String order, String status, long revision) {
    SeckillQuotaSettlementPO row = new SeckillQuotaSettlementPO();
    row.id = 19L;
    row.orderNo = order;
    row.activityId = 3L;
    row.skuId = 71L;
    row.userId = 9L;
    row.idempotencyKey = "cm21-original-acceptance";
    row.quantity = 1;
    row.status = status;
    row.revision = revision;
    if ("CONFIRMED".equals(status)) row.operationKey = order + ":CONFIRM";
    return row;
  }

  private static String sha(String input) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
  }
}
