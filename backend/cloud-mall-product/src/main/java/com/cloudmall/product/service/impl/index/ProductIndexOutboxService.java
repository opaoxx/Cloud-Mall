package com.cloudmall.product.service.impl.index;

import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 在商品事务中写入索引事件，并异步交给 dispatcher 投递。 */
@Service
public class ProductIndexOutboxService {
  /** Optional while disabled; enabled mode requires the real same-resource proxy. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private ProductMetricService metrics;

  /** The legacy constructor never touches the online control table. */
  @org.springframework.beans.factory.annotation.Value("${cloudmall.product.metrics.enabled:false}")
  private boolean metricsEnabled;

  /** 商品索引待投递事件的持久化入口。 */
  private final ProductIndexOutboxMapper mapper;

  /** 序列化原索引事件envelope的映射器。 */
  private final ObjectMapper objectMapper;

  /** 注入原outbox持久化与事件序列化依赖。 */
  public ProductIndexOutboxService(ProductIndexOutboxMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /** 写入严格绑定当前商品版本的 JSON envelope。 */
  public String append(String eventType, long productId, long productVersion, String traceId) {
    String eventId = UUID.randomUUID().toString();
    OffsetDateTime occurredAt = OffsetDateTime.now();
    String indexEventType = "PRODUCT_CHANGED".equals(eventType) ? "UPSERT" : eventType;
    Map<String, Object> envelope =
        Map.of(
            "eventId",
            eventId,
            "eventType",
            indexEventType,
            "occurredAt",
            occurredAt.toString(),
            "productId",
            productId,
            "productVersion",
            productVersion,
            "source",
            "LIVE",
            "traceId",
            traceId,
            "payloadVersion",
            1,
            "businessKey",
            Map.of("name", "productId", "value", productId),
            "payload",
            Map.of("productId", productId));
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
    if (metricsEnabled) {
      if (metrics == null) throw new IllegalStateException("METRIC_TRANSACTION_PROXY_REQUIRED");
      metrics.contentChanged();
    }
    return eventId;
  }
}
