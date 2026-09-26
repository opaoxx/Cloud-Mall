package com.cloudmall.product.service;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.mapper.ProductIndexDeadLetterMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

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

  /** 持久化消费 ACK，避免 Rabbit ACK 与 ES 写入状态脱节。 */
  private final ProductIndexConsumptionService consumptionService;

  private final ProductIndexDeadLetterMapper deadLetterMapper;

  private final ProductIndexOutboxMapper outboxMapper;
  private final RabbitTemplate rabbitTemplate;

  /** 创建 ProductIndexConsumer 实例。 */
  public ProductIndexConsumer(
      ObjectMapper objectMapper, ProductIndexDocumentLoader loader, ProductIndexWriter writer) {
    this(objectMapper, loader, writer, null);
  }

  /** 创建带消费状态持久化的消费者。 */
  public ProductIndexConsumer(
      ObjectMapper objectMapper,
      ProductIndexDocumentLoader loader,
      ProductIndexWriter writer,
      ProductIndexConsumptionService consumptionService) {
    this(objectMapper, loader, writer, consumptionService, null);
  }

  public ProductIndexConsumer(
      ObjectMapper objectMapper,
      ProductIndexDocumentLoader loader,
      ProductIndexWriter writer,
      ProductIndexConsumptionService consumptionService,
      ProductIndexDeadLetterMapper deadLetterMapper) {
    this(objectMapper, loader, writer, consumptionService, deadLetterMapper, null);
  }

  public ProductIndexConsumer(
      ObjectMapper objectMapper,
      ProductIndexDocumentLoader loader,
      ProductIndexWriter writer,
      ProductIndexConsumptionService consumptionService,
      ProductIndexDeadLetterMapper deadLetterMapper,
      ProductIndexOutboxMapper outboxMapper) {
    this(objectMapper, loader, writer, consumptionService, deadLetterMapper, outboxMapper, null);
  }

  @Autowired
  public ProductIndexConsumer(
      ObjectMapper objectMapper,
      ProductIndexDocumentLoader loader,
      ProductIndexWriter writer,
      ProductIndexConsumptionService consumptionService,
      ProductIndexDeadLetterMapper deadLetterMapper,
      ProductIndexOutboxMapper outboxMapper,
      RabbitTemplate rabbitTemplate) {
    // 1. 接收并整理 ProductIndexConsumer 的业务请求。
    // 2. 执行 ProductIndexConsumer 的核心业务校验与状态处理。
    // 3. 返回 ProductIndexConsumer 的处理结果。
    this.objectMapper = objectMapper;
    this.loader = loader;
    this.writer = writer;
    this.consumptionService = consumptionService;
    this.deadLetterMapper = deadLetterMapper;
    this.outboxMapper = outboxMapper;
    this.rabbitTemplate = rabbitTemplate;
  }

  /** 持久化 DLQ 原字节后才确认消息，旧 Java 序列化只隔离不反序列化。 */
  @RabbitListener(
      queues = ProductMessagingConfiguration.PRODUCT_INDEX_DLQ,
      containerFactory = ProductMessagingConfiguration.PRODUCT_INDEX_DLQ_CONTAINER_FACTORY)
  public void consumeDeadLetter(
      Message message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
      throws Exception {
    byte[] body = message.getBody();
    try {
      if (body.length > 1024 * 1024)
        throw new IllegalArgumentException("商品索引 DLQ 消息超过 1MiB，证据不得截断");
      String sha256 = sha256(body);
      String contentType = message.getMessageProperties().getContentType();
      String messageId = message.getMessageProperties().getMessageId();
      if (messageId == null || messageId.isBlank()) messageId = sha256;
      if (deadLetterMapper == null) throw new IllegalStateException("DLQ 证据存储未初始化");
      deadLetterMapper.insert(messageId, contentType, body, sha256,
          isJson(body) ? "json-retry-pending" : "dead-letter", OffsetDateTime.now());
      channel.basicAck(deliveryTag, false);
    } catch (Exception storageFailure) {
      log.error("商品索引死信证据保存失败，延迟重入且不丢弃原字节", storageFailure);
      if (rabbitTemplate == null) throw storageFailure;
      CorrelationData retry = new CorrelationData(java.util.UUID.randomUUID().toString());
      rabbitTemplate.send(ProductMessagingConfiguration.PRODUCT_INDEX_DLX,
          ProductMessagingConfiguration.PRODUCT_INDEX_DLQ_RETRY, message, retry);
      if (!retry.getFuture().get(5, TimeUnit.SECONDS).isAck()
          || retry.getReturnedMessage() != null) throw storageFailure;
      channel.basicAck(deliveryTag, false);
    }
  }

  private static boolean isJson(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8).trim();
    return text.startsWith("{") && text.endsWith("}");
  }

  private static String sha256(byte[] body) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
    StringBuilder result = new StringBuilder(digest.length * 2);
    for (byte value : digest) result.append(String.format("%02x", value));
    return result.toString();
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
      if (consumptionService.isTerminal(eventId)) return;
      long productId = envelope.businessId;
      if (eventType.contains("DELETED")) {
        loader.load(productId).ifPresentOrElse(
            document -> {
              invokeUpsert(document, eventId, traceId);
              envelope.appliedVersion = document.version();
              record(envelope, "STALE", null);
            },
            () -> {
              invokeDelete(productId, eventId, traceId);
              record(envelope, "APPLIED", null);
            });
        return;
      }
      loader
          .load(productId)
          .ifPresentOrElse(
              document -> {
                invokeUpsert(document, eventId, traceId);
                envelope.appliedVersion = document.version();
                record(
                    envelope,
                    envelope.productVersion > 0 && document.version() > envelope.productVersion
                        ? "STALE"
                        : "APPLIED",
                    null);
              },
              () -> {
                invokeDelete(productId, eventId, traceId);
                record(envelope, "APPLIED", null);
              });
    } catch (Exception e) {
      record(envelope, "RETRY", e.getMessage());
      log.error("商品索引消费失败，eventId={}，traceId={}，eventType={}", eventId, traceId, eventType, e);
      throw e;
    } finally {
      MDC.remove("traceId");
    }
  }

  private void record(EventEnvelope envelope, String status, String error) {
    if (consumptionService == null) throw new IllegalStateException("商品索引消费状态服务未初始化");
    consumptionService.record(envelope.eventId, envelope.businessId, envelope.productVersion, envelope.appliedVersion, status, error);
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
    if (!eventType.startsWith("PRODUCT_")
        && !eventType.startsWith("CATEGORY_")
        && !eventType.equals("UPSERT")
        && !eventType.equals("HIDE")) {
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
    long productVersion = event.has("productVersion")
        ? requiredNonnegativeVersion(event, "productVersion") : -1L;
    return new EventEnvelope(eventId, eventType, traceId, rootId, productVersion);
  }

  private static long requiredNonnegativeVersion(JsonNode event, String field) {
    JsonNode value = event.get(field);
    if (value == null || !value.isIntegralNumber() || value.asLong() < 0)
      throw new IllegalArgumentException("商品事件版本无效");
    return value.asLong();
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

    private final long productVersion;

    private long appliedVersion = -1L;

    /** 执行 EventEnvelope 相关操作。 */
    private EventEnvelope(
        String eventId, String eventType, String traceId, long businessId, long productVersion) {
      // 1. 接收并整理 EventEnvelope 的业务请求。
      // 2. 执行 EventEnvelope 的核心业务校验与状态处理。
      // 3. 返回 EventEnvelope 的处理结果。
      this.eventId = eventId;
      this.eventType = eventType;
      this.traceId = traceId;
      this.businessId = businessId;
      this.productVersion = productVersion;
    }
  }

  static final class ProductIndexException extends RuntimeException {
    ProductIndexException(String operation, String eventId, String traceId, Exception cause) {
      super("商品索引 " + operation + " 失败，eventId=" + eventId + "，traceId=" + traceId, cause);
    }
  }
}
