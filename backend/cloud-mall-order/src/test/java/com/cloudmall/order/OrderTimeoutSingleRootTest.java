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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
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

/** 超时消息JSON根的完整性与空输入分类；实际消费方法，SQL/RPC替身。 */
class OrderTimeoutSingleRootTest {
  @Test
  void additionalJsonRootsCannotBeSilentlyIgnoredBeforeBusinessAccess() throws Exception {
    String envelope = envelope("20261042");
    List<Executable> checks = new ArrayList<>();
    for (String payload :
        List.of(
            envelope + " {}",
            envelope + " []",
            envelope + " null",
            envelope + " 7",
            envelope + " \"tail\"",
            envelope + " true",
            "\"20261042\" {}",
            "\"20261042\" \"20261043\"")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders, new ObjectMapper());
      checks.add(
          () -> {
            IllegalArgumentException rejected =
                assertThrows(
                    IllegalArgumentException.class,
                    () -> service.consumeTimeout(message(payload)),
                    payload);
            assertInstanceOf(MismatchedInputException.class, rejected.getCause());
          });
      checks.add(() -> verifyNoInteractions(orders, stock));
    }
    assertAll(checks);
  }

  @Test
  void allExistingMessageFormatsKeepTheirCasAndDoNotMutateTheSharedMapper() throws Exception {
    for (String payload :
        List.of(
            envelope("20261042") + " \t\r\n",
            "\"20261042\" \n",
            " \t20261042\r\n",
            " \t202610LEGACY1\r\n")) {
      String orderNo = payload.contains("LEGACY1") ? "202610LEGACY1" : "20261042";
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      ObjectMapper mapper = new ObjectMapper();
      assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
      OrderServiceImpl service = service(stock, orders, mapper);
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
      service.consumeTimeout(message(payload));
      assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
      verify(orders).findOrder("mall_order_202610", orderNo);
      verify(orders)
          .updateStatus(
              eq("mall_order_202610"), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
      verifyNoInteractions(stock);
    }
  }

  @Test
  void emptyAndUnsupportedRootsUseFormatErrorsBeforeAnyOrderLookup() {
    List<Executable> checks = new ArrayList<>();
    for (String payload : List.of("", " ", "\t\r\n", "null", "true", "[]")) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderServiceImpl service = service(stock, orders, new ObjectMapper());
      checks.add(
          () ->
              assertThrows(
                  IllegalArgumentException.class,
                  () -> service.consumeTimeout(message(payload)),
                  payload));
      checks.add(() -> verifyNoInteractions(orders, stock));
    }
    assertAll(checks);
  }

  /** 标准文本envelope只生成单根JSON，不在测试准备阶段剔除尾随内容。 */
  private static String envelope(String orderNo) throws Exception {
    return new ObjectMapper()
        .writeValueAsString(
            Map.of(
                "eventType",
                "ORDER_TIMEOUT",
                "eventId",
                "timeout-1",
                "orderNo",
                orderNo,
                "expireAt",
                "2000-01-01T00:00:00+08:00"));
  }

  /** 保持真实String消费方法，默认CAS0不访问库存动作或Stock。 */
  private static OrderServiceImpl service(
      StockClient stock, OrderSqlMapper orders, ObjectMapper mapper) {
    return new OrderServiceImpl(
        stock,
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        orders,
        mapper);
  }

  /** 以实际UTF8正文触发解析，content type不参与格式猜测。 */
  private static Message message(String payload) {
    return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
