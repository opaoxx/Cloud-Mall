package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** UTF8字节到timeout JSON的严格边界；原字节不改，业务方法真实，SQL/RPC替身。 */
class OrderTimeoutUtf8Test {
  @Test
  void malformedUtf8CannotBecomeReplacementCharactersInEventIdentity() {
    List<byte[]> invalidSequences =
        List.of(
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xbf},
            new byte[] {(byte) 0xc2},
            new byte[] {(byte) 0xc2, 0x78},
            new byte[] {(byte) 0xc0, (byte) 0xaf},
            new byte[] {(byte) 0xe0, (byte) 0x80, (byte) 0xaf},
            new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
            new byte[] {(byte) 0xf5, (byte) 0x80, (byte) 0x80, (byte) 0x80},
            new byte[] {(byte) 0xe2, (byte) 0x82},
            new byte[] {(byte) 0xf0, (byte) 0x9f, (byte) 0x98},
            new byte[] {(byte) 0xe2, 0x28, (byte) 0xa1});
    List<byte[]> payloads = new ArrayList<>();
    for (byte[] sequence : invalidSequences) {
      payloads.add(
          join(
              "{\"eventType\":\"ORDER_TIMEOUT\",\"eventId\":\"time",
              sequence,
              "out\",\"orderNo\":\"20261042\",\"expireAt\":\"2000-01-01T00:00:00+08:00\"}"));
    }
    assertMalformedBodiesAreRejected(payloads);
  }

  @Test
  void malformedUtf8InOptionalFieldsCannotBeSilentlyAccepted() {
    byte[] invalid = {(byte) 0x80};
    String prefix = envelope("timeout-1");
    prefix = prefix.substring(0, prefix.length() - 1);
    assertMalformedBodiesAreRejected(
        List.of(
            join(prefix + ",\"traceId\":\"", invalid, "\"}"),
            join(prefix + ",\"payload\":{\"note\":\"", invalid, "\"}}"),
            join(prefix + ",\"extension\":\"", invalid, "\"}")));
  }

  @Test
  void validMultibyteUtf8AndExistingFormatsRemainUnchanged() {
    for (String payload :
        List.of(
            envelope("超时🛒"),
            envelope("合法替代字符\uFFFD"),
            "\"20261042\"",
            " 20261042 ",
            " 202610LEGACY1 ")) {
      String orderNo = payload.contains("LEGACY1") ? "202610LEGACY1" : "20261042";
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

  /** 不经String准备损坏JSON，严格检查原编码cause与无业务访问。 */
  private static void assertMalformedBodiesAreRejected(List<byte[]> payloads) {
    List<Executable> checks = new ArrayList<>();
    for (byte[] payload : payloads) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      OrderTestService service = service(stock, orders);
      Message message = new Message(payload.clone(), new MessageProperties());
      checks.add(
          () -> {
            IllegalArgumentException rejected =
                assertThrows(IllegalArgumentException.class, () -> service.consumeTimeout(message));
            assertInstanceOf(CharacterCodingException.class, rejected.getCause());
          });
      checks.add(() -> verifyNoInteractions(orders, stock));
      checks.add(() -> assertArrayEquals(payload, message.getBody()));
    }
    assertAll(checks);
  }

  /** 原框架为合法单根JSON，eventId可包含合法UTF8字符，不加前缀规则。 */
  private static String envelope(String eventId) {
    return "{\"eventType\":\"ORDER_TIMEOUT\",\"eventId\":\""
        + eventId
        + "\",\"orderNo\":\"20261042\",\"expireAt\":\"2000-01-01T00:00:00+08:00\"}";
  }

  /** 两端为有效UTF8，直接在中间拼接指定原始非法序列。 */
  private static byte[] join(String prefix, byte[] middle, String suffix) {
    byte[] before = prefix.getBytes(StandardCharsets.UTF_8);
    byte[] after = suffix.getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[before.length + middle.length + after.length];
    System.arraycopy(before, 0, body, 0, before.length);
    System.arraycopy(middle, 0, body, before.length, middle.length);
    System.arraycopy(after, 0, body, before.length + middle.length, after.length);
    return body;
  }

  /** 实际Message及String业务方法，CAS默认0不访问库存动作。 */
  private static OrderTestService service(StockClient stock, OrderSqlMapper orders) {
    return new OrderTestService(
        stock,
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        orders,
        new ObjectMapper());
  }
}
