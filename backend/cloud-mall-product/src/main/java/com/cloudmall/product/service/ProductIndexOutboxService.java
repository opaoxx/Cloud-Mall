package com.cloudmall.product.service;

import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;

/** 在商品事务中写入索引事件，并异步交给 dispatcher 投递。 */
@Service
public class ProductIndexOutboxService {
  private final ProductIndexOutboxMapper mapper;
  private final ObjectMapper objectMapper;

  public ProductIndexOutboxService(ProductIndexOutboxMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /** 写入严格绑定当前商品版本的 JSON envelope。 */
  public String append(String eventType, long productId, long productVersion, String traceId) {
    String eventId = UUID.randomUUID().toString();
    OffsetDateTime occurredAt = OffsetDateTime.now();
    String indexEventType = "PRODUCT_CHANGED".equals(eventType) ? "UPSERT" : eventType;
    Map<String, Object> envelope = Map.of("eventId", eventId, "eventType", indexEventType, "occurredAt", occurredAt.toString(), "productId", productId, "productVersion", productVersion, "source", "LIVE", "traceId", traceId, "payloadVersion", 1, "businessKey", Map.of("name", "productId", "value", productId), "payload", Map.of("productId", productId));
    try {
      mapper.insert(
          Math.abs(UUID.randomUUID().getMostSignificantBits()),
          eventId,
          indexEventType,
          productId,
          productVersion,
          "LIVE",
          objectMapper.writeValueAsString(envelope),
          occurredAt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("商品索引事件序列化失败", exception);
    }
    return eventId;
  }
}
