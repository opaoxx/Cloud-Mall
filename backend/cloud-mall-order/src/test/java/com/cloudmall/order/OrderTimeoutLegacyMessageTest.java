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

/** 旧超时String消息的纯数字标识保持与消费入口兼容；SQL/RPC均为替身。 */
class OrderTimeoutLegacyMessageTest {
  @Test
  void rawNumericOrderNumbersReachTheOriginalCasWithoutNumericConversion() {
    List<Executable> checks = new ArrayList<>();
    for (String orderNo :
        List.of(
            "20261042",
            "2026109223372036854775807",
            "2026101234567890123456789012345678901234567890")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders);
      pendingOrder(orders, orderNo);
      Message message = message(" \r\n" + orderNo + "\t");
      checks.add(() -> assertDoesNotThrow(() -> service.consumeTimeout(message), orderNo));
      checks.add(() -> verify(orders).findOrder("mall_order_202610", orderNo));
      checks.add(
          () ->
              verify(orders)
                  .updateStatus(
                      eq("mall_order_202610"),
                      eq(orderNo),
                      eq("CANCELLED"),
                      eq("PENDING_PAYMENT"),
                      any()));
      checks.add(() -> verifyNoInteractions(stock));
    }
    assertAll(checks);
  }

  @Test
  void jsonStringsEnvelopesAndMalformedJsonLegacyTextKeepTheSameBusinessPath() throws Exception {
    String orderNo = "202610LEGACY1";
    for (String payload :
        List.of(
            new ObjectMapper().writeValueAsString(orderNo),
            " \t" + orderNo + "\n",
            "{\"eventType\":\"ORDER_TIMEOUT\",\"eventId\":\"timeout-1\",\"orderNo\":\""
                + orderNo
                + "\",\"expireAt\":\"2000-01-01T00:00:00+08:00\"}")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders);
      pendingOrder(orders, orderNo);
      service.consumeTimeout(message(payload));
      verify(orders).findOrder("mall_order_202610", orderNo);
      verify(orders)
          .updateStatus(
              eq("mall_order_202610"), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
      verifyNoInteractions(stock);
    }
  }

  @Test
  void noncanonicalNumericRootsAreRejectedBeforeOrderLookup() {
    for (String payload :
        List.of(
            "-20261042",
            "20261042.0",
            "20261042e0",
            "20261042 7",
            "20261042 {}",
            "true",
            "null",
            "[20261042]")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders);
      assertThrows(
          IllegalArgumentException.class, () -> service.consumeTimeout(message(payload)), payload);
      verifyNoInteractions(orders, stock);
    }
  }

  /** 通过实际Message入口及String业务方法，保持数据库CAS失败不回滚Stock的既有规则。 */
  private static OrderServiceImpl service(StockClient stock, OrderSqlMapper orders) {
    return new OrderServiceImpl(
        stock,
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        orders,
        new ObjectMapper());
  }

  /** 到期订单事实仅供替身返回；CAS默认0，不写库存动作或调用远端。 */
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

  /** 旧UTF8消息不依赖content-type猜测格式。 */
  private static Message message(String payload) {
    return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
