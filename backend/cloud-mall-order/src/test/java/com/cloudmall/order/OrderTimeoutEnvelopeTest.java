package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** timeout对象消息的原生必填文本门；实际消费方法，SQL/RPC替身，不经spy拦截。 */
class OrderTimeoutEnvelopeTest {
  @Test
  void missingBlankAndNontextRequiredFieldsAreRejectedBeforeBusinessAccess() throws Exception {
    List<Object> invalidValues =
        Arrays.asList(
            null, "", " \t\u2003", 20261042L, true, List.of("value"), Map.of("value", "text"));
    List<Executable> checks = new ArrayList<>();
    for (String fieldName : List.of("eventId", "orderNo", "expireAt")) {
      for (int variantIndex = 0; variantIndex <= invalidValues.size(); variantIndex++) {
        Map<String, Object> envelope = envelope();
        if (variantIndex == 0) envelope.remove(fieldName);
        else envelope.put(fieldName, invalidValues.get(variantIndex - 1));
        Message message = message(envelope);
        OrderSqlMapper orders = mock(OrderSqlMapper.class);
        StockClient stock = mock(StockClient.class);
        OrderTestService service = service(stock, orders);
        String label = fieldName + ":" + variantIndex;
        checks.add(
            () ->
                assertThrows(
                    IllegalArgumentException.class, () -> service.consumeTimeout(message), label));
        checks.add(() -> verifyNoInteractions(orders, stock));
      }
    }
    assertAll(checks);
  }

  @Test
  void validTextEnvelopesReachTheOriginalExactOrderAndCas() throws Exception {
    String orderNo = "20261042";
    for (String expiration : List.of("2000-01-01T00:00:00+08:00", "2000-01-01T00:00:00Z")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderTestService service = service(stock, orders);
      when(orders.findOrder("mall_order_202610", orderNo))
          .thenReturn(
              List.of(
                  Map.of(
                      "order_no",
                      orderNo,
                      "user_id",
                      8L,
                      "status",
                      "PENDING_PAYMENT",
                      "total_amount",
                      new BigDecimal("12.30"),
                      "pay_amount",
                      new BigDecimal("12.30"),
                      "address_snapshot",
                      "{}",
                      "expire_at",
                      LocalDateTime.of(2000, 1, 1, 0, 0))));
      Map<String, Object> envelope = envelope();
      envelope.put("expireAt", expiration);
      envelope.put("traceId", null);
      service.consumeTimeout(message(envelope));
      verify(orders).findOrder("mall_order_202610", orderNo);
      verify(orders)
          .updateStatus(
              eq("mall_order_202610"), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
      verifyNoInteractions(stock);
    }
  }

  @Test
  void eventTypeRemainsExactAndObjectOrderNumbersAreNeverTrimmed() throws Exception {
    for (Object eventType :
        Arrays.asList(
            null,
            "order_timeout",
            "ORDER_TIMEOUT ",
            "OTHER",
            123,
            true,
            List.of("ORDER_TIMEOUT"))) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderTestService service = service(stock, orders);
      Map<String, Object> envelope = envelope();
      envelope.put("eventType", eventType);
      Message message = message(envelope);
      assertThrows(IllegalArgumentException.class, () -> service.consumeTimeout(message));
      verifyNoInteractions(orders, stock);
    }
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    StockClient stock = mock(StockClient.class);
    OrderTestService service = service(stock, orders);
    Map<String, Object> envelope = envelope();
    envelope.put("orderNo", " 20261042 ");
    Message message = message(envelope);
    BizException rejected = assertThrows(BizException.class, () -> service.consumeTimeout(message));
    assertEquals(404, rejected.getHttpStatus());
    verifyNoInteractions(orders, stock);
  }

  /** eventId沿原不透明非空身份，不要求前缀；可选字段不参与本批资格。 */
  private static Map<String, Object> envelope() {
    return new LinkedHashMap<>(
        Map.of(
            "eventType", "ORDER_TIMEOUT",
            "eventId", "timeout-1",
            "orderNo", "20261042",
            "expireAt", "2000-01-01T00:00:00+08:00"));
  }

  /** 直接构造实际业务服务，不截获String入口或改变参数。 */
  private static OrderTestService service(StockClient stock, OrderSqlMapper orders) {
    return new OrderTestService(
        stock,
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        orders,
        new ObjectMapper());
  }

  /** 保留对象字段原始JSON类型，避免测试准备阶段提前做文本强转。 */
  private static Message message(Map<String, Object> envelope) throws Exception {
    String payload = new ObjectMapper().writeValueAsString(envelope);
    return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
