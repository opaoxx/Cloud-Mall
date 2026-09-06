package com.cloudmall.product.service;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Rabbit-to-ES seam. Replaying an event is safe because ES document id is productId. */
@Component
public class ProductIndexConsumer {
  /** 执行 getLogger 相关操作。 */
  private static final Logger log = LoggerFactory.getLogger(ProductIndexConsumer.class);

  /** 保存 objectMapper 的业务状态或配置。 */
  private final ObjectMapper objectMapper;

  /** 保存 loader 的业务状态或配置。 */
  private final ProductIndexDocumentLoader loader;

  /** 保存 writer 的业务状态或配置。 */
  private final ProductIndexWriter writer;

  /** 创建 ProductIndexConsumer 实例。 */
  public ProductIndexConsumer(
      ObjectMapper objectMapper, ProductIndexDocumentLoader loader, ProductIndexWriter writer) {
    // 1. 接收并整理 ProductIndexConsumer 的业务请求。
    // 2. 执行 ProductIndexConsumer 的核心业务校验与状态处理。
    // 3. 返回 ProductIndexConsumer 的处理结果。
    this.objectMapper = objectMapper;
    this.loader = loader;
    this.writer = writer;
  }

  @RabbitListener(
      queues = ProductMessagingConfiguration.PRODUCT_INDEX_QUEUE,
      containerFactory = ProductMessagingConfiguration.PRODUCT_INDEX_CONTAINER_FACTORY)
  /** 执行 consume 相关操作。 */
  public void consume(byte[] body) throws Exception {
    // 1. 接收并整理 consume 的业务请求。
    // 2. 执行 consume 的核心业务校验与状态处理。
    // 3. 返回 consume 的处理结果。
    JsonNode event = objectMapper.readTree(body);
    EventEnvelope envelope = validateEnvelope(event);
    String eventId = envelope.eventId;
    String traceId = envelope.traceId;
    String eventType = envelope.eventType;
    if (eventType.startsWith("CATEGORY_")) return;
    MDC.put("traceId", traceId);
    try {
      long productId = envelope.businessId;
      if (eventType.contains("DELETED")) {
        writer.delete(productId);
        return;
      }
      loader
          .load(productId)
          .ifPresentOrElse(
              document -> invokeUpsert(document, eventId, traceId),
              () -> invokeDelete(productId, eventId, traceId));
    } catch (Exception e) {
      log.error("商品索引消费失败，eventId={}，traceId={}，eventType={}", eventId, traceId, eventType, e);
      throw e;
    } finally {
      MDC.remove("traceId");
    }
  }

  /** 执行 invokeUpsert 相关操作。 */
  private void invokeUpsert(ProductIndexDocument document, String eventId, String traceId) {
    // 1. 接收并整理 invokeUpsert 的业务请求。
    // 2. 执行 invokeUpsert 的核心业务校验与状态处理。
    // 3. 返回 invokeUpsert 的处理结果。
    try {
      writer.upsert(document);
    } catch (Exception e) {
      throw new ProductIndexException("upsert", eventId, traceId, e);
    }
  }

  /** 执行 invokeDelete 相关操作。 */
  private void invokeDelete(long productId, String eventId, String traceId) {
    // 1. 接收并整理 invokeDelete 的业务请求。
    // 2. 执行 invokeDelete 的核心业务校验与状态处理。
    // 3. 返回 invokeDelete 的处理结果。
    try {
      writer.delete(productId);
    } catch (Exception e) {
      throw new ProductIndexException("delete", eventId, traceId, e);
    }
  }

  /** 执行 validateEnvelope 相关操作。 */
  private static EventEnvelope validateEnvelope(JsonNode event) {
    // 1. 接收并整理 validateEnvelope 的业务请求。
    // 2. 执行 validateEnvelope 的核心业务校验与状态处理。
    // 3. 返回 validateEnvelope 的处理结果。
    if (event == null || !event.isObject()) throw new IllegalArgumentException("商品事件 envelope 无效");
    String eventId = requiredText(event, "eventId");
    String eventType = requiredText(event, "eventType");
    String traceId = requiredText(event, "traceId");
    String occurredAt = requiredText(event, "occurredAt");
    try {
      OffsetDateTime.parse(occurredAt);
    } catch (Exception e) {
      throw new IllegalArgumentException("商品事件 occurredAt 格式无效", e);
    }
    if (!eventType.startsWith("PRODUCT_") && !eventType.startsWith("CATEGORY_")) {
      throw new IllegalArgumentException("商品事件 eventType 无效");
    }
    String expectedKey = eventType.startsWith("CATEGORY_") ? "categoryId" : "productId";
    JsonNode businessKey = event.get("businessKey");
    if (businessKey == null
        || !businessKey.isObject()
        || !expectedKey.equals(requiredText(businessKey, "name"))) {
      throw new IllegalArgumentException("商品事件 businessKey 无效");
    }
    long keyValue = requiredId(businessKey, "value");
    long rootId = requiredId(event, expectedKey);
    JsonNode payload = event.get("payload");
    if (payload == null || !payload.isObject())
      throw new IllegalArgumentException("商品事件 payload 无效");
    long payloadId = requiredId(payload, expectedKey);
    if (keyValue != rootId || rootId != payloadId) {
      throw new IllegalArgumentException("商品事件业务主键不一致");
    }
    return new EventEnvelope(eventId, eventType, traceId, rootId);
  }

  /** 执行 requiredText 相关操作。 */
  private static String requiredText(JsonNode event, String field) {
    // 1. 接收并整理 requiredText 的业务请求。
    // 2. 执行 requiredText 的核心业务校验与状态处理。
    // 3. 返回 requiredText 的处理结果。
    String value = event.path(field).asText(null);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("商品事件缺少 " + field);
    return value;
  }

  /** 执行 requiredId 相关操作。 */
  private static long requiredId(JsonNode event, String field) {
    // 1. 接收并整理 requiredId 的业务请求。
    // 2. 执行 requiredId 的核心业务校验与状态处理。
    // 3. 返回 requiredId 的处理结果。
    JsonNode value = event.get(field);
    if (value == null || !value.isIntegralNumber() || value.asLong() <= 0) {
      throw new IllegalArgumentException("商品事件缺少有效 " + field);
    }
    return value.asLong();
  }

  private static final class EventEnvelope {
    /** 保存 eventId 的业务状态或配置。 */
    private final String eventId;

    /** 保存 eventType 的业务状态或配置。 */
    private final String eventType;

    /** 保存 traceId 的业务状态或配置。 */
    private final String traceId;

    /** 保存 businessId 的业务状态或配置。 */
    private final long businessId;

    /** 执行 EventEnvelope 相关操作。 */
    private EventEnvelope(String eventId, String eventType, String traceId, long businessId) {
      // 1. 接收并整理 EventEnvelope 的业务请求。
      // 2. 执行 EventEnvelope 的核心业务校验与状态处理。
      // 3. 返回 EventEnvelope 的处理结果。
      this.eventId = eventId;
      this.eventType = eventType;
      this.traceId = traceId;
      this.businessId = businessId;
    }
  }

  static final class ProductIndexException extends RuntimeException {
    ProductIndexException(String operation, String eventId, String traceId, Exception cause) {
      super("商品索引 " + operation + " 失败，eventId=" + eventId + "，traceId=" + traceId, cause);
    }
  }
}
