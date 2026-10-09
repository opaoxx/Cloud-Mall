package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** ASCII JSON转义之后的必填文本Unicode边界；实际Message/String业务链，SQL/RPC替身。 */
class OrderTimeoutUnicodeTextTest {
  @Test
  void unpairedSurrogatesInEventIdentityAndDeadlineCannotEnterBusiness() {
    assertUnpairedFieldsAreRejected(List.of("eventId", "expireAt"));
  }

  @Test
  void unpairedOrderNumbersAreFormatErrorsBeforeTheOrderLookup() {
    assertUnpairedFieldsAreRejected(List.of("orderNo"));
  }

  @Test
  void validPairsUnicodeAndExistingMessageFormatsKeepTheOriginalBusinessPath() {
    for (String payload :
        List.of(
            envelope("eventId", "escaped-\\uD83D\\uDED2"),
            envelope("eventId", "\\uD800\\uDC00\\uDBFF\\uDFFF"),
            envelope("eventId", "中文🛒"),
            envelope("eventId", "真实替代字符\uFFFD"),
            envelope("expireAt", "期限\\uD83D\\uDED2"),
            "\"20261042\"",
            " 20261042 ",
            " 202610LEGACY1 ")) {
      String orderNo = payload.contains("LEGACY1") ? "202610LEGACY1" : "20261042";
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders);
      pendingOrder(orders, orderNo);
      byte[] original = payload.getBytes(StandardCharsets.UTF_8);
      Message message = new Message(original.clone(), new MessageProperties());
      service.consumeTimeout(message);
      assertArrayEquals(original, message.getBody());
      verify(orders).findOrder("mall_order_202610", orderNo);
      verify(orders)
          .updateStatus(
              eq("mall_order_202610"), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
      verifyNoInteractions(stock);
    }
  }

  /** 原bytes全为合法ASCII，损坏发生在JSON解码后；不借UTF8替换或spy截获业务。 */
  private static void assertUnpairedFieldsAreRejected(List<String> fields) {
    List<Executable> checks = new ArrayList<>();
    for (String field : fields) {
      for (String escaped :
          List.of(
              "\\uD800",
              "x\\uDBFF",
              "\\uDC00x",
              "\\uD800\\uD800",
              "\\uDC00\\uD800",
              "\\uD800x\\uDC00",
              "\\uD83D\\uDED2\\uD800",
              "\\uDC00\\uD83D\\uDED2")) {
        String payload = envelope(field, escaped);
        assertTrue(payload.chars().allMatch(codeUnit -> codeUnit <= 127));
        OrderSqlMapper orders = mock(OrderSqlMapper.class);
        StockClient stock = mock(StockClient.class);
        OrderServiceImpl service = service(stock, orders);
        pendingOrder(orders, "20261042");
        byte[] original = payload.getBytes(StandardCharsets.UTF_8);
        Message message = new Message(original.clone(), new MessageProperties());
        String label = field + ":" + escaped;
        checks.add(
            () ->
                assertThrows(
                    IllegalArgumentException.class, () -> service.consumeTimeout(message), label));
        checks.add(() -> verifyNoInteractions(orders, stock));
        checks.add(() -> assertArrayEquals(original, message.getBody(), label));
      }
    }
    assertAll(checks);
  }

  /** 直接准备JSON转义，不能先将孤立UTF16字符经UTF8编码替换为问号。 */
  private static String envelope(String field, String escapedText) {
    String eventId = "eventId".equals(field) ? escapedText : "timeout-1";
    String orderNo = "orderNo".equals(field) ? "202610" + escapedText : "20261042";
    String expireAt = "expireAt".equals(field) ? escapedText : "2000-01-01T00:00:00+08:00";
    return "{\"eventType\":\"ORDER_TIMEOUT\",\"eventId\":\""
        + eventId
        + "\",\"orderNo\":\""
        + orderNo
        + "\",\"expireAt\":\""
        + expireAt
        + "\"}";
  }

  /** 已过期DB事实允许原CAS路径，默认CAS=0不会触发动作写或真实库存调用。 */
  private static void pendingOrder(OrderSqlMapper orders, String orderNo) {
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
  }

  /** 实际Service处理入口，不mock String消费方法；外部依赖均为替身。 */
  private static OrderServiceImpl service(StockClient stock, OrderSqlMapper orders) {
    return new OrderServiceImpl(
        stock,
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        orders,
        new ObjectMapper());
  }
}
