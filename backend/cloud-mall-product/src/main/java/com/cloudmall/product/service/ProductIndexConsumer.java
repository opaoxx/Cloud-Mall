package com.cloudmall.product.service;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** Rabbit-to-ES seam. Replaying an event is safe because ES document id is productId. */
@Component
public class ProductIndexConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProductIndexConsumer.class);
    private final ObjectMapper objectMapper;
    private final ProductIndexDocumentLoader loader;
    private final ProductIndexWriter writer;

    public ProductIndexConsumer(ObjectMapper objectMapper, ProductIndexDocumentLoader loader, ProductIndexWriter writer) {
        this.objectMapper = objectMapper;
        this.loader = loader;
        this.writer = writer;
    }

    @RabbitListener(queues = ProductMessagingConfiguration.PRODUCT_INDEX_QUEUE,
            containerFactory = ProductMessagingConfiguration.PRODUCT_INDEX_CONTAINER_FACTORY)
    public void consume(byte[] body) throws Exception {
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
            loader.load(productId).ifPresentOrElse(document -> invokeUpsert(document, eventId, traceId),
                    () -> invokeDelete(productId, eventId, traceId));
        } catch (Exception e) {
            log.error("商品索引消费失败，eventId={}，traceId={}，eventType={}", eventId, traceId, eventType, e);
            throw e;
        } finally {
            MDC.remove("traceId");
        }
    }

    private void invokeUpsert(ProductIndexDocument document, String eventId, String traceId) {
        try {
            writer.upsert(document);
        } catch (Exception e) {
            throw new ProductIndexException("upsert", eventId, traceId, e);
        }
    }

    private void invokeDelete(long productId, String eventId, String traceId) {
        try {
            writer.delete(productId);
        } catch (Exception e) {
            throw new ProductIndexException("delete", eventId, traceId, e);
        }
    }

    private static EventEnvelope validateEnvelope(JsonNode event) {
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
        if (businessKey == null || !businessKey.isObject()
                || !expectedKey.equals(requiredText(businessKey, "name"))) {
            throw new IllegalArgumentException("商品事件 businessKey 无效");
        }
        long keyValue = requiredId(businessKey, "value");
        long rootId = requiredId(event, expectedKey);
        JsonNode payload = event.get("payload");
        if (payload == null || !payload.isObject()) throw new IllegalArgumentException("商品事件 payload 无效");
        long payloadId = requiredId(payload, expectedKey);
        if (keyValue != rootId || rootId != payloadId) {
            throw new IllegalArgumentException("商品事件业务主键不一致");
        }
        return new EventEnvelope(eventId, eventType, traceId, rootId);
    }

    private static String requiredText(JsonNode event, String field) {
        String value = event.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("商品事件缺少 " + field);
        return value;
    }

    private static long requiredId(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() <= 0) {
            throw new IllegalArgumentException("商品事件缺少有效 " + field);
        }
        return value.asLong();
    }

    private static final class EventEnvelope {
        private final String eventId;
        private final String eventType;
        private final String traceId;
        private final long businessId;

        private EventEnvelope(String eventId, String eventType, String traceId, long businessId) {
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
