package com.cloudmall.product.util;

import static com.cloudmall.product.util.ProductDateTimes.now;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** 商品兼容事件信封和追踪标识。 */
public final class ProductEventEnvelope {
  /** 禁止实例化事件工具。 */
  private ProductEventEnvelope() {}

  /** 按原字段构造商品或分类的兼容事件信封。 */
  public static Map<String, Object> eventEnvelope(String type, long id) {
    return eventEnvelope(type, id, 0L);
  }

  /** 按原字段构造商品或分类的兼容事件信封。 */
  public static Map<String, Object> eventEnvelope(String type, long id, long productVersion) {
    String entityType = type.startsWith("CATEGORY") ? "CATEGORY" : "PRODUCT";
    String businessKeyName = "CATEGORY".equals(entityType) ? "categoryId" : "productId";
    OffsetDateTime occurredAt = now();
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> payload = Map.of(businessKeyName, id, "productVersion", productVersion);
    return Map.of(
        "eventId",
        eventId,
        "eventType",
        type,
        "occurredAt",
        occurredAt.toString(),
        "businessKey",
        Map.of("name", businessKeyName, "value", id),
        businessKeyName,
        id,
        "productVersion",
        productVersion,
        "traceId",
        traceId(eventId),
        "payload",
        payload);
  }

  /** 读取原MDC候选键，缺失时返回可追踪事件标识。 */
  public static String traceId(String eventId) {
    for (String key : List.of("traceId", "trace_id", "X-B3-TraceId")) {
      String value = MDC.get(key);
      if (value != null && !value.isBlank()) return value;
    }
    return "cloudmall-product-" + eventId;
  }
}
