package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 首次秒杀只接受原SKU成功商品事实；直接替身编排不冒称本地claim事务回滚。 */
class SeckillProductIdentityTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void untrustedProductResponsesCannotAuthorizeOrdersOutboxOrCommittedResolution() {
    for (String fault :
        List.of(
            "wrong-sku", "missing-sku", "bad-code", "null-code", "null-envelope", "null-data")) {
      Fixture fixture = new Fixture();
      Long responseSku = 7L;
      if ("wrong-sku".equals(fault)) responseSku = 8L;
      else if ("missing-sku".equals(fault)) responseSku = null;
      ApiResponse<ProductClient.SkuView> response =
          ApiResponse.ok(product(responseSku, BigDecimal.TEN));
      if ("bad-code".equals(fault)) response.code = "BROKEN";
      else if ("null-code".equals(fault)) response.code = null;
      else if ("null-envelope".equals(fault)) response = null;
      else if ("null-data".equals(fault)) response = ApiResponse.ok(null);
      doReturn(response).when(fixture.products).sku(7L);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertTrue(failure.getMessage().contains("RabbitMQ"));
      fixture.verifyNoOrderOrCompletionWrites();
    }
  }

  @Test
  void matchingProductKeepsOriginalPriceSkuQuantityAndCreationTime() {
    Fixture fixture = new Fixture();
    BigDecimal originalPrice = new BigDecimal("12.3000");
    doReturn(ApiResponse.ok(product(7L, originalPrice))).when(fixture.products).sku(7L);
    fixture.service.consumeSeckill(fixture.event);
    var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
    var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
    verify(fixture.orders).insertOrder(eq("mall_order_202612"), order.capture());
    verify(fixture.orders).insertOrderItem(eq("mall_order_item_202612"), detail.capture());
    assertSame(originalPrice, order.getValue().totalAmount);
    assertSame(originalPrice, order.getValue().payAmount);
    assertSame(originalPrice, detail.getValue().unitPrice);
    assertSame(originalPrice, detail.getValue().lineAmount);
    assertEquals(7L, detail.getValue().skuId);
    assertEquals(1, detail.getValue().quantity);
    assertEquals(
        java.time.LocalDateTime.parse("2026-12-31T23:59:59.999"), order.getValue().createdAt);
    verify(fixture.products).sku(7L);
    verify(fixture.dedup).markCommitted(eq(8L), eq(fixture.key), any());
    verify(fixture.recovery)
        .markResolved(
            eq(fixture.eventId),
            eq(fixture.orderNo),
            eq(8L),
            eq(fixture.key),
            eq(fixture.hash),
            any());
    verifyNoInteractions(fixture.stock, fixture.cart);
  }

  @Test
  void committedReplayAndInvalidTimeKeepTheirProductQueryPriority() {
    Fixture committed = new Fixture();
    doReturn(0)
        .when(committed.dedup)
        .claim(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    doReturn(
            Map.of(
                "request_hash",
                committed.hash,
                "order_no",
                committed.orderNo,
                "status",
                "COMMITTED"))
        .when(committed.dedup)
        .find(8L, committed.key);
    committed.event.remove("occurredAt");
    committed.service.consumeSeckill(committed.event);
    verifyNoInteractions(
        committed.products, committed.orders, committed.outbox, committed.stock, committed.cart);
    verify(committed.recovery)
        .markResolved(
            eq(committed.eventId),
            eq(committed.orderNo),
            eq(8L),
            eq(committed.key),
            eq(committed.hash),
            any());
    Fixture invalidTime = new Fixture();
    invalidTime.event.remove("occurredAt");
    assertThrows(
        IllegalStateException.class, () -> invalidTime.service.consumeSeckill(invalidTime.event));
    verifyNoInteractions(
        invalidTime.products,
        invalidTime.orders,
        invalidTime.outbox,
        invalidTime.recovery,
        invalidTime.stock,
        invalidTime.cart);
    verify(invalidTime.dedup, never()).markCommitted(anyLong(), anyString(), any());
  }

  @Test
  void dependencyExceptionsKeepTheirOriginalCauseAndFatalErrorsRemainUnwrapped() {
    Fixture runtime = new Fixture();
    IllegalStateException original = new IllegalStateException("Product unavailable");
    doThrow(original).when(runtime.products).sku(7L);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> runtime.service.consumeSeckill(runtime.event));
    assertSame(original, failure.getCause());
    runtime.verifyNoOrderOrCompletionWrites();
    Fixture fatal = new Fixture();
    AssertionError originalError = new AssertionError("fatal Product failure");
    doThrow(originalError).when(fatal.products).sku(7L);
    assertSame(
        originalError,
        assertThrows(AssertionError.class, () -> fatal.service.consumeSeckill(fatal.event)));
    fatal.verifyNoOrderOrCompletionWrites();
  }

  @Test
  void nullAndNegativePricesFailBeforeOrdersOrCompletionWithControlledCauses() {
    for (String fault : List.of("null", "negative")) {
      Fixture fixture = new Fixture();
      BigDecimal price = "null".equals(fault) ? null : new BigDecimal("-12.30");
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("价格"));
      fixture.verifyNoOrderOrCompletionWrites();
    }
    Fixture identityFirst = new Fixture();
    doReturn(ApiResponse.ok(product(8L, null))).when(identityFirst.products).sku(7L);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> identityFirst.service.consumeSeckill(identityFirst.event));
    assertTrue(failure.getCause().getMessage().contains("SKU"));
    identityFirst.verifyNoOrderOrCompletionWrites();
  }

  @Test
  void zeroAndPositivePricesKeepTheirOriginalObjectAndScaleForSeckillWrites() {
    for (String representation : List.of("0.00", "-0.0000", "1E+1", "12.3000")) {
      Fixture fixture = new Fixture();
      BigDecimal price = new BigDecimal(representation);
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      fixture.service.consumeSeckill(fixture.event);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      assertSame(price, order.getValue().totalAmount);
      assertSame(price, order.getValue().payAmount);
      assertSame(price, detail.getValue().unitPrice);
      assertSame(price, detail.getValue().lineAmount);
      assertEquals(1, detail.getValue().quantity);
      verify(fixture.dedup).markCommitted(eq(8L), eq(fixture.key), any());
      verify(fixture.recovery)
          .markResolved(
              eq(fixture.eventId),
              eq(fixture.orderNo),
              eq(8L),
              eq(fixture.key),
              eq(fixture.hash),
              any());
      verifyNoInteractions(fixture.stock, fixture.cart);
    }
  }

  @Test
  void nonCentPricesCannotCreateOrCompleteSeckillFacts() {
    List<BigDecimal> prices = new java.util.ArrayList<>();
    for (String representation : List.of("12.345", "0.001", "1E-3"))
      prices.add(new BigDecimal(representation));
    prices.add(new BigDecimal(java.math.BigInteger.ONE, Integer.MAX_VALUE));
    for (BigDecimal price : prices) {
      Fixture fixture = new Fixture();
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("精度"));
      fixture.verifyNoOrderOrCompletionWrites();
    }
  }

  @Test
  void exactCentPricesAndExtremeScaleValidationNeverReplaceTheOriginalPrice() {
    List<BigDecimal> prices = new java.util.ArrayList<>();
    for (String representation : List.of("12.3400", "0.0100", "1E+1", "0.0000"))
      prices.add(new BigDecimal(representation));
    // 极端表示只验证Service与mock赋值，不能将其当作真实SQL或可支付金额承诺。
    prices.add(new BigDecimal(java.math.BigInteger.ZERO, Integer.MAX_VALUE));

    for (BigDecimal price : prices) {
      Fixture fixture = new Fixture();
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      fixture.service.consumeSeckill(fixture.event);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      assertSame(price, detail.getValue().unitPrice);
      assertSame(price, detail.getValue().lineAmount);
      assertSame(price, order.getValue().totalAmount);
      assertSame(price, order.getValue().payAmount);
      assertEquals(1, detail.getValue().quantity);
    }
  }

  @Test
  void oversizedAndExtremeIntegerPricesCannotWriteOrCompleteSeckillFacts() {
    for (BigDecimal price :
        List.of(
            new BigDecimal("10000000000000000.00"),
            new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE))) {
      Fixture fixture = new Fixture();
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("容量"));
      fixture.verifyNoOrderOrCompletionWrites();
    }
  }

  @Test
  void maximumAndMathematicallyZeroExtremeScaleKeepTheirOriginalObjects() {
    for (BigDecimal price :
        List.of(
            new BigDecimal("9999999999999999.99"),
            new BigDecimal("9999999999999999.9900"),
            new BigDecimal(java.math.BigInteger.ZERO, Integer.MAX_VALUE))) {
      Fixture fixture = new Fixture();
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      fixture.service.consumeSeckill(fixture.event);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      assertSame(price, order.getValue().totalAmount);
      assertSame(price, order.getValue().payAmount);
      assertSame(price, detail.getValue().unitPrice);
      assertSame(price, detail.getValue().lineAmount);
      assertEquals(1, detail.getValue().quantity);
    }
  }

  @Test
  void invalidRequiredMetadataCannotCompleteTheClaimOrWriteOrders() {
    for (String fault : List.of("null-parent", "null-name", "wide", "wide-emoji")) {
      Fixture fixture = new Fixture();
      Long parentId = "null-parent".equals(fault) ? null : Long.valueOf(9L);
      String name =
          "null-name".equals(fault)
              ? null
              : "wide".equals(fault)
                  ? "名".repeat(256)
                  : "wide-emoji".equals(fault) ? "😀".repeat(256) : "商品";
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, parentId, name, "SKU", BigDecimal.TEN, Map.of())))
          .when(fixture.products)
          .sku(7L);
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
      assertTrue(failure.getCause().getMessage().contains("必填信息"));
      fixture.verifyNoOrderOrCompletionWrites();
    }
    Fixture earlier = new Fixture();
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, null, null, "SKU", new BigDecimal("-1"), null)))
        .when(earlier.products)
        .sku(7L);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> earlier.service.consumeSeckill(earlier.event));
    assertTrue(failure.getCause().getMessage().contains("价格"));
    earlier.verifyNoOrderOrCompletionWrites();
  }

  @Test
  void legalMetadataPreservesOriginalNameParentAndNullableSnapshotOnCompletion() {
    for (String name : List.of("", " ", "尾空格 ", "名".repeat(255), "😀".repeat(255))) {
      Fixture fixture = new Fixture();
      BigDecimal price = new BigDecimal("12.3000");
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, Long.MAX_VALUE, name, "SKU", price, null)))
          .when(fixture.products)
          .sku(7L);
      fixture.service.consumeSeckill(fixture.event);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      assertEquals(Long.MAX_VALUE, detail.getValue().productId);
      assertSame(name, detail.getValue().productNameSnapshot);
      assertSame(price, detail.getValue().unitPrice);
      assertEquals("null", detail.getValue().skuSnapshot);
      assertEquals(1, detail.getValue().quantity);
      verify(fixture.dedup).markCommitted(eq(8L), eq(fixture.key), any());
      verify(fixture.recovery)
          .markResolved(
              eq(fixture.eventId),
              eq(fixture.orderNo),
              eq(8L),
              eq(fixture.key),
              eq(fixture.hash),
              any());
      verifyNoInteractions(fixture.stock, fixture.cart);
    }
  }

  @Test
  void lossyNumbersAndNonIdentityTypesOnlyRecordOriginalConflictEvidence() throws Exception {
    for (String field : List.of("userId", "activityId", "skuId")) {
      long alias = "userId".equals(field) ? 8L : "activityId".equals(field) ? 11L : 7L;
      for (Object invalid :
          List.of(
              new BigDecimal(alias + ".5"),
              java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.valueOf(alias)),
              alias + 0.5d,
              alias + 0.5f,
              Double.NaN,
              Double.POSITIVE_INFINITY,
              Double.NEGATIVE_INFINITY,
              new BigDecimal("9223372036854775808"),
              new BigDecimal("-9223372036854775809"),
              new BigDecimal("1E+1000"),
              "9223372036854775808",
              "7.5",
              true,
              Map.of(),
              List.of())) {
        Fixture fixture = new Fixture();
        fixture.event.put(field, invalid);
        fixture.service.consumeSeckill(fixture.event);
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.dedup)
            .recordConflictEvent(
                anyLong(),
                eq("userId".equals(field) ? 0L : 8L),
                eq(fixture.key),
                eq(fixture.hash),
                eq(fixture.orderNo),
                eq(fixture.eventId),
                anyString(),
                payload.capture(),
                any());
        ObjectMapper json = new ObjectMapper();
        assertEquals(
            json.readTree(json.writeValueAsString(fixture.event)),
            json.readTree(payload.getValue()));
        verifyNoMoreInteractions(fixture.dedup);
        verifyNoInteractions(
            fixture.products,
            fixture.orders,
            fixture.outbox,
            fixture.recovery,
            fixture.stock,
            fixture.cart);
      }
    }
  }

  @Test
  void exactIntegralRepresentationsRetainOriginalBusinessIdentity() {
    for (String field : List.of("userId", "activityId", "skuId")) {
      long identity = "userId".equals(field) ? 8L : "activityId".equals(field) ? 11L : 7L;
      for (Object representation :
          List.of(
              (byte) identity,
              (short) identity,
              (int) identity,
              identity,
              java.math.BigInteger.valueOf(identity),
              new BigDecimal(identity + ".000"),
              (float) identity,
              (double) identity,
              "+000" + identity)) {
        Fixture fixture = new Fixture();
        fixture.event.put(field, representation);
        fixture.service.consumeSeckill(fixture.event);
        verify(fixture.dedup)
            .claim(
                anyLong(),
                eq(8L),
                eq(fixture.key),
                eq(fixture.hash),
                eq(fixture.orderNo),
                eq(fixture.eventId),
                any());
        verify(fixture.products).sku(7L);
        var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
        verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
        assertEquals(7L, detail.getValue().skuId);
        verify(fixture.dedup).markCommitted(eq(8L), eq(fixture.key), any());
        verify(fixture.dedup, never())
            .recordConflictEvent(
                anyLong(),
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any());
        verifyNoInteractions(fixture.stock, fixture.cart);
      }
    }
  }

  @Test
  void nonPositiveExactValuesKeepTheExistingFingerprintFailureChannel() {
    for (String field : List.of("userId", "activityId", "skuId")) {
      for (Object representation : List.of(0L, -1L, new BigDecimal("-0.000"), "0", "-1")) {
        Fixture fixture = new Fixture();
        fixture.event.put(field, representation);
        IllegalStateException failure =
            assertThrows(
                IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        verifyNoInteractions(
            fixture.dedup,
            fixture.products,
            fixture.orders,
            fixture.outbox,
            fixture.recovery,
            fixture.stock,
            fixture.cart);
      }
    }
  }

  @Test
  void conflictEvidenceWriteFailureKeepsTheRabbitRetryChannel() {
    Fixture fixture = new Fixture();
    fixture.event.put("skuId", new BigDecimal("7.5"));
    RuntimeException unavailable =
        new org.springframework.dao.DataAccessResourceFailureException(
            "raw evidence write unavailable");
    doThrow(unavailable)
        .when(fixture.dedup)
        .recordConflictEvent(
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any());
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> fixture.service.consumeSeckill(fixture.event));
    assertSame(unavailable, failure.getCause());
    verify(fixture.dedup)
        .recordConflictEvent(
            anyLong(),
            eq(8L),
            eq(fixture.key),
            eq(fixture.hash),
            eq(fixture.orderNo),
            eq(fixture.eventId),
            anyString(),
            anyString(),
            any());
    verifyNoMoreInteractions(fixture.dedup);
    verifyNoInteractions(
        fixture.products,
        fixture.orders,
        fixture.outbox,
        fixture.recovery,
        fixture.stock,
        fixture.cart);
  }

  /** 原始Product记录，不改变业务金额表示。 */
  private static ProductClient.SkuView product(Long skuId, BigDecimal price) {
    return new ProductClient.SkuView(skuId, 9L, "商品", "SKU", price, Map.of());
  }

  /** 无真实事务的实际消费Service和独立依赖替身。 */
  private static class Fixture {
    /** 原订单号与消息创建月份一致。 */
    final String orderNo = "202612-product-identity";

    /** 原用户绑定的幂等键。 */
    final String key = "product-identity";

    /** 原事件号。 */
    final String eventId = SeckillRequestFingerprint.eventId(orderNo);

    /** 原请求维度指纹。 */
    final String hash = SeckillRequestFingerprint.hash(11, 7, 8, 1, "{}");

    /** 可变消息用于已有重放与时间异常边界。 */
    final Map<String, Object> event = new LinkedHashMap<>();

    /** 首次认领和完成状态入口替身。 */
    final SeckillOrderDedupMapper dedup = mock(SeckillOrderDedupMapper.class);

    /** 原持久恢复状态入口替身。 */
    final SeckillMessageRecoveryMapper recovery = mock(SeckillMessageRecoveryMapper.class);

    /** 原商品RPC替身。 */
    final ProductClient products = mock(ProductClient.class);

    /** 秒杀消费不应新调用库存。 */
    final StockClient stock = mock(StockClient.class);

    /** 秒杀消费不应读取或清理Cart。 */
    final CartClient cart = mock(CartClient.class);

    /** 原订单/明细持久入口替身。 */
    final OrderSqlMapper orders = mock(OrderSqlMapper.class);

    /** 原超时outbox持久入口替身。 */
    final OrderOutboxMapper outbox = mock(OrderOutboxMapper.class);

    /** 实际被测消费编排。 */
    final OrderServiceImpl service =
        new OrderServiceImpl(
            stock, products, mock(UserClient.class), cart, orders, new ObjectMapper());

    private Fixture() {
      event.putAll(
          Map.of(
              "orderNo",
              orderNo,
              "eventId",
              eventId,
              "eventType",
              "SECKILL_ORDER_REQUEST",
              "activityId",
              11L,
              "skuId",
              7L,
              "userId",
              8L,
              "idempotencyKey",
              key,
              "addressSnapshot",
              "{}",
              "requestHash",
              hash,
              "occurredAt",
              "2026-12-31T23:59:59.999+08:00"));
      when(dedup.claim(
              anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
          .thenReturn(1);
      when(dedup.find(8L, key))
          .thenReturn(Map.of("request_hash", hash, "order_no", orderNo, "status", "PROCESSING"));
      when(products.sku(7L)).thenReturn(ApiResponse.ok(product(7L, BigDecimal.TEN)));
      ReflectionTestUtils.setField(service, "seckillOrderDedupMapper", dedup);
      ReflectionTestUtils.setField(service, "seckillMessageRecoveryMapper", recovery);
      ReflectionTestUtils.setField(service, "orderOutboxMapper", outbox);
    }

    /** 首次claim与Product查询允许存在，不得有后续状态或订单写；不声称撤回mock claim。 */
    private void verifyNoOrderOrCompletionWrites() {
      verify(dedup).claim(anyLong(), eq(8L), eq(key), eq(hash), eq(orderNo), eq(eventId), any());
      verify(dedup).find(8L, key);
      verifyNoMoreInteractions(dedup);
      verify(products).sku(7L);
      verifyNoMoreInteractions(products);
      verifyNoInteractions(orders, outbox, recovery, stock, cart);
    }
  }
}
