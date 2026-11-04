package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.SeckillReservationQueryController;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationQueryService;
import com.cloudmall.stock.service.impl.seckill.SeckillReservationQueryServiceImpl;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 内部事实视图保留原字段存在性、值类型和读取顺序，不增加证明或恢复动作。 */
class SeckillReservationFactTest {
  /** 使用实际Jackson默认规则检查完整字段树。 */
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void missingFactHasOnlyTwoFieldsAndDoesNotQueryOutbox() {
    var stocks = emptyMapper();
    var service = new SeckillReservationQueryServiceImpl(stocks);
    assertEquals(
        json.valueToTree(Map.of("orderNo", "order-1", "status", "NOT_FOUND")),
        json.valueToTree(service.find("order-1")));
    verify(stocks).findSeckillByOrderNo("order-1");
    verifyNoMoreInteractions(stocks);
    assertThrows(NullPointerException.class, () -> service.find(null));
    verify(stocks, never()).findSeckillOutboxByOrderNo(any());
  }

  @Test
  void observedFactKeepsRawNumbersNullsAndDoesNotInventEventId() {
    var stocks = emptyMapper();
    var source = new HashMap<String, Object>();
    source.put("activity_id", new BigDecimal("1.25"));
    source.put("sku_id", new BigInteger("9223372036854775808"));
    source.put("user_id", "unconverted-user");
    source.put("status", "ACCEPTED");
    when(stocks.findSeckillByOrderNo("order-1")).thenReturn(source);
    JsonNode result =
        json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find("order-1"));
    assertEquals(json.valueToTree(expected("order-1", source, null)), result);
    assertEquals(8, result.size());
    assertTrue(result.get("idempotencyKey").isNull());
    assertFalse(result.has("eventId"));
    assertEquals(new BigDecimal("1.25"), result.get("activityId").decimalValue());
    assertEquals(new BigInteger("9223372036854775808"), result.get("skuId").bigIntegerValue());
    verify(stocks).findSeckillByOrderNo("order-1");
    verify(stocks).findSeckillOutboxByOrderNo("order-1");
    verifyNoMoreInteractions(stocks);
  }

  @Test
  void outboxPresenceControlsEventFieldAndRejectedKeepsPriority() {
    for (String sourceStatus : new String[] {"ACCEPTED", "REJECTED", null})
      for (String outboxStatus : new String[] {"PENDING", "PUBLISHED", null}) {
        var stocks = emptyMapper();
        var source = new HashMap<String, Object>();
        source.put("status", sourceStatus);
        var outbox = new HashMap<String, Object>();
        outbox.put("status", outboxStatus);
        when(stocks.findSeckillByOrderNo("order-1")).thenReturn(source);
        when(stocks.findSeckillOutboxByOrderNo("order-1")).thenReturn(outbox);
        JsonNode result =
            json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find("order-1"));
        assertEquals(json.valueToTree(expected("order-1", source, outbox)), result);
        assertEquals(9, result.size());
        assertTrue(result.has("eventId"));
        assertTrue(result.get("eventId").isNull());
        outbox.put("event_id", "original-event");
        assertEquals(
            "original-event",
            json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find("order-1"))
                .get("eventId")
                .asText());
      }
  }

  @Test
  void fieldsAreCapturedBeforeOutboxButStatusIsReadAgainAfterwards() {
    var stocks = emptyMapper();
    var source = new HashMap<String, Object>();
    source.put("activity_id", 10L);
    source.put("status", "ACCEPTED");
    when(stocks.findSeckillByOrderNo("order-1")).thenReturn(source);
    when(stocks.findSeckillOutboxByOrderNo("order-1"))
        .thenAnswer(
            invocation -> {
              source.put("activity_id", 99L);
              source.put("status", "REJECTED");
              return Map.of("status", "PUBLISHED", "event_id", "event-1");
            });
    JsonNode result =
        json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find("order-1"));
    assertEquals(10L, result.get("activityId").asLong());
    assertEquals("ACCEPTED", result.get("reservationStatus").asText());
    assertEquals("REJECTED", result.get("status").asText());
    var ordered = inOrder(stocks);
    ordered.verify(stocks).findSeckillByOrderNo("order-1");
    ordered.verify(stocks).findSeckillOutboxByOrderNo("order-1");
    ordered.verifyNoMoreInteractions();
  }

  @Test
  void nonNullEmptyRowIsObservedAndRetainsNullQueryOrder() {
    var stocks = emptyMapper();
    when(stocks.findSeckillByOrderNo(null)).thenReturn(Map.of());
    JsonNode result = json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find(null));
    assertEquals(8, result.size());
    assertTrue(result.get("orderNo").isNull());
    assertEquals("UNKNOWN", result.get("status").asText());
    when(stocks.findSeckillOutboxByOrderNo(null)).thenReturn(Map.of());
    result = json.valueToTree(new SeckillReservationQueryServiceImpl(stocks).find(null));
    assertEquals(9, result.size());
    assertEquals("null", result.get("status").asText());
    assertTrue(result.get("eventId").isNull());
  }

  @Test
  void httpHeaderAndNewReadPointcutKeepOriginalErrorBoundaries() throws Exception {
    var stocks = emptyMapper();
    var factory = new AspectJProxyFactory(new SeckillReservationQueryServiceImpl(stocks));
    factory.setProxyTargetClass(true);
    factory.addAspect(new StockDependencyAspect());
    SeckillReservationQueryService service = factory.getProxy();
    var mvc =
        MockMvcBuilders.standaloneSetup(new SeckillReservationQueryController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String path = "/api/internal/stock/seckill/reservations/order-1";
    for (String caller : List.of("", "cloud-mall-pay", "Cloud-Mall-Order"))
      mvc.perform(get(path).header("X-Internal-Service", caller)).andExpect(status().isForbidden());
    verifyNoInteractions(stocks);
    when(stocks.findSeckillByOrderNo("order-1"))
        .thenAnswer(
            invocation -> {
              assertTrue(StockDependencyGuard.mysqlActive());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            });
    mvc.perform(get(path).header("X-Internal-Service", "cloud-mall-order"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("NOT_FOUND"));
    doThrow(new DataAccessResourceFailureException("isolated read failure"))
        .when(stocks)
        .findSeckillByOrderNo("order-1");
    mvc.perform(get(path).header("X-Internal-Service", "cloud-mall-order"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STOCK_SERVICE_UNAVAILABLE"));
    assertFalse(StockDependencyGuard.mysqlActive());
  }

  private static StockSqlMapper emptyMapper() {
    var mapper = mock(StockSqlMapper.class);
    when(mapper.findSeckillByOrderNo(any())).thenReturn(null);
    when(mapper.findSeckillOutboxByOrderNo(any())).thenReturn(null);
    return mapper;
  }

  /** 独立列举冻结协议的字段集合，不复用VO工厂作为期望。 */
  private static Map<String, Object> expected(
      String orderNo, Map<String, Object> source, Map<String, Object> outbox) {
    var result = new HashMap<String, Object>();
    result.put("orderNo", orderNo);
    result.put("activityId", source.get("activity_id"));
    result.put("skuId", source.get("sku_id"));
    result.put("userId", source.get("user_id"));
    result.put("idempotencyKey", source.get("idempotency_key"));
    result.put("requestHash", source.get("request_hash"));
    result.put("reservationStatus", source.get("status"));
    result.put(
        "status",
        "REJECTED".equals(String.valueOf(source.get("status")))
            ? "REJECTED"
            : outbox == null ? "UNKNOWN" : String.valueOf(outbox.get("status")));
    if (outbox != null) result.put("eventId", outbox.get("event_id"));
    return result;
  }
}
