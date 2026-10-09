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

/** timeout树读取的重复键拒绝；原始JSON保留重复项，业务方法真实，SQL/RPC替身。 */
class OrderTimeoutDuplicateKeysTest {
  @Test
  void duplicateRootFieldsCannotOverwriteEarlierIdentityValues() {
    List<String> payloads = new ArrayList<>();
    for (var field :
        Map.of(
                "eventType",
                "ORDER_TIMEOUT",
                "eventId",
                "timeout-1",
                "orderNo",
                "20261042",
                "expireAt",
                "2000-01-01T00:00:00+08:00")
            .entrySet()) {
      String property = "\"" + field.getKey() + "\":\"" + field.getValue() + "\"";
      payloads.add(append(envelope(), property));
      payloads.add(
          append(envelope().replace(property, "\"" + field.getKey() + "\":null"), property));
    }
    assertDuplicatePayloadsAreRejected(payloads);
  }

  @Test
  void duplicateOptionalAndNestedObjectKeysAreRejectedAtEveryTreeDepth() {
    assertDuplicatePayloadsAreRejected(
        List.of(
            append(envelope(), "\"traceId\":\"first\",\"traceId\":\"second\""),
            append(envelope(), "\"payload\":{\"key\":1,\"key\":2}"),
            append(envelope(), "\"payload\":{\"child\":{\"key\":1,\"key\":2}}"),
            append(envelope(), "\"payload\":[{\"key\":1,\"key\":2}]"),
            append(envelope(), "\"extension\":{\"key\":1,\"key\":2}")));
  }

  @Test
  void uniqueKeysAndKeysRepeatedInDifferentObjectsKeepExistingFormatsAndGlobalFlags() {
    for (String payload :
        List.of(
            append(envelope(), "\"payload\":{\"first\":{\"key\":1},\"second\":{\"key\":2}}")
                + " \n",
            append(envelope(), "\"payload\":[{\"key\":1},{\"key\":2}]"),
            "\"20261042\"",
            " 20261042 ",
            " 202610LEGACY1 ")) {
      String orderNo = payload.contains("LEGACY1") ? "202610LEGACY1" : "20261042";
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      ObjectMapper mapper = new ObjectMapper();
      assertGlobalFlagsAreUnchanged(mapper);
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
      assertGlobalFlagsAreUnchanged(mapper);
      verify(orders).findOrder("mall_order_202610", orderNo);
      verify(orders)
          .updateStatus(
              eq("mall_order_202610"), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
      verifyNoInteractions(stock);
    }
  }

  /** 同值与不同值重复都必须由解析错误拒绝，不能仅看最终业务字段。 */
  private static void assertDuplicatePayloadsAreRejected(List<String> payloads) {
    List<Executable> checks = new ArrayList<>();
    for (String payload : payloads) {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      StockClient stock = mock(StockClient.class);
      ObjectMapper mapper = new ObjectMapper();
      OrderServiceImpl service = service(stock, orders, mapper);
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
      checks.add(() -> assertGlobalFlagsAreUnchanged(mapper));
    }
    assertAll(checks);
  }

  /** 不允许修改共享ObjectMapper影响订单其他JSON读写。 */
  private static void assertGlobalFlagsAreUnchanged(ObjectMapper mapper) {
    assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY));
    assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
  }

  /** 明确构造单根合法框架，字符串拼接保留重复键。 */
  private static String envelope() {
    return "{\"eventType\":\"ORDER_TIMEOUT\",\"eventId\":\"timeout-1\",\"orderNo\":\"20261042\",\"expireAt\":\"2000-01-01T00:00:00+08:00\"}";
  }

  /** 仅去掉末尾右花括号追加测试字段，不解析成Map而丢失重复证据。 */
  private static String append(String envelope, String properties) {
    return envelope.substring(0, envelope.length() - 1) + "," + properties + "}";
  }

  /** 实际Message/String业务方法，CAS默认0不会触及库存动作。 */
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

  /** 保留UTF8原文中的同名字段及原顺序。 */
  private static Message message(String payload) {
    return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }
}
