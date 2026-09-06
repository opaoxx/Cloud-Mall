package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.cloudmall.product.domain.dto.SkuDTO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.impl.ProductServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

class ProductContractTest {
  /** 执行 ObjectMapper 相关操作。 */
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void productParameterUsesNameAndValue() throws Exception {
    ProductParameterDTO parameter =
        mapper.readValue("{\"name\":\"颜色\",\"value\":\"黑色\"}", ProductParameterDTO.class);

    assertEquals("颜色", parameter.name);
    assertEquals("黑色", parameter.value);
    JsonNode json = mapper.readTree(mapper.writeValueAsString(parameter));
    assertTrue(json.has("name"));
    assertTrue(json.has("value"));
    assertFalse(json.has("paramName"));
    assertFalse(json.has("paramValue"));
  }

  @Test
  void skuSpecJsonIsAnObjectOfStrings() throws Exception {
    SkuDTO sku =
        mapper.readValue(
            "{\"skuCode\":\"BLACK-64\",\"specJson\":{\"颜色\":\"黑色\",\"容量\":\"64GB\"}}",
            SkuDTO.class);

    assertEquals(Map.of("颜色", "黑色", "容量", "64GB"), sku.specJson);
    JsonNode json = mapper.readTree(mapper.writeValueAsString(sku));
    assertTrue(json.get("specJson").isObject());
    assertEquals("黑色", json.get("specJson").get("颜色").asText());
    assertFalse(json.get("specJson").isTextual());
  }

  @Test
  void remainingStockUsesRedisValueAndMissingValueIsSafe() {
    OffsetDateTime now = OffsetDateTime.of(2026, 9, 4, 12, 0, 0, 0, ZoneOffset.ofHours(8));
    OffsetDateTime end = now.plusHours(1);

    assertEquals(7, ProductServiceImpl.resolveRemainingStock("7", "STARTED", end, now));
    assertEquals(0, ProductServiceImpl.resolveRemainingStock(null, "STARTED", end, now));
    assertEquals(
        0, ProductServiceImpl.resolveRemainingStock("100", "STARTED", now.minusSeconds(1), now));
    assertEquals(0, ProductServiceImpl.resolveRemainingStock("not-a-number", "STARTED", end, now));
  }

  @Test
  void productEventUsesTheFrozenEnvelopeAndMdcTraceId() {
    MDC.put("traceId", "trace-from-request");
    try {
      JsonNode event = mapper.valueToTree(ProductServiceImpl.eventEnvelope("PRODUCT_CHANGED", 42L));

      assertTrue(event.hasNonNull("eventId"));
      assertEquals("PRODUCT_CHANGED", event.get("eventType").asText());
      assertTrue(event.hasNonNull("occurredAt"));
      assertEquals("trace-from-request", event.get("traceId").asText());
      assertEquals(42L, event.get("productId").asLong());
      assertEquals("productId", event.get("businessKey").get("name").asText());
      assertEquals(42L, event.get("payload").get("productId").asLong());
    } finally {
      MDC.remove("traceId");
    }
  }

  @Test
  void productEventProvidesTraceableFallbackWhenMdcIsAbsent() {
    MDC.clear();

    JsonNode event = mapper.valueToTree(ProductServiceImpl.eventEnvelope("CATEGORY_CHANGED", 7L));

    assertTrue(event.get("traceId").asText().startsWith("cloudmall-product-"));
    assertEquals(7L, event.get("categoryId").asLong());
    assertEquals("categoryId", event.get("businessKey").get("name").asText());
    assertEquals(7L, event.get("payload").get("categoryId").asLong());
  }

  @Test
  void everyProductEventPublishingWriteEndpointIsTransactional() throws Exception {
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("create", ProductDTO.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("update", Long.class, ProductDTO.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("publish", Long.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("unpublish", Long.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("addCategory", CategoryDTO.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("updateCategory", Long.class, CategoryDTO.class)
            .isAnnotationPresent(Transactional.class));
    assertTrue(
        ProductServiceImpl.class
            .getDeclaredMethod("deleteCategory", Long.class)
            .isAnnotationPresent(Transactional.class));
  }

  @Test
  void eventPublishFailureIsPropagatedToTheTransactionalCaller() throws Exception {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
        .when(rabbit)
        .convertAndSend(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            (Object) org.mockito.ArgumentMatchers.any());
    ProductServiceImpl controller =
        new ProductServiceImpl(
            mock(ProductSqlMapper.class), rabbit, mock(StringRedisTemplate.class), mapper);
    Method event = ProductServiceImpl.class.getDeclaredMethod("event", String.class, long.class);
    event.setAccessible(true);

    InvocationTargetException thrown =
        assertThrows(
            InvocationTargetException.class,
            () -> event.invoke(controller, "PRODUCT_CHANGED", 42L));
    assertTrue(thrown.getCause() instanceof ProductServiceImpl.ProductEventPublishException);
  }
}
