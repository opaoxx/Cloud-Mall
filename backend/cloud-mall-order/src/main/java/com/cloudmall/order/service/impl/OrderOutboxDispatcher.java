package com.cloudmall.order.service.impl;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispatches committed order timeout outbox rows through the TTL and DLX chain. */
@Component
public class OrderOutboxDispatcher {
  /** 记录 outbox 投递和重试失败。 */
  private static final Logger log = LoggerFactory.getLogger(OrderOutboxDispatcher.class);

  /** 订单数据库时间和超时计算使用的业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 按状态和重试时间读取 outbox 的 Mapper。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 将已提交事件投递到 RabbitMQ 的模板。 */
  private final RabbitTemplate rabbitTemplate;

  /** 从数据库 JSON 载荷构造消息 envelope 的映射器。 */
  private final ObjectMapper objectMapper;

  /** 每轮扫描的最大 outbox 行数。 */
  private final int batchSize;

  /** 等待 broker publisher confirm 的毫秒数。 */
  private final long confirmTimeoutMillis;

  /** 投递失败后的再次尝试间隔。 */
  private final long retryDelaySeconds;

  /** Creates the committed outbox dispatcher. */
  public OrderOutboxDispatcher(
      OrderOutboxMapper orderOutboxMapper,
      RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper,
      org.springframework.core.env.Environment environment) {
    this.orderOutboxMapper = orderOutboxMapper;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.batchSize = environment.getProperty("cloudmall.order.outbox.batch-size", Integer.class, 50);
    this.confirmTimeoutMillis =
        environment.getProperty("cloudmall.order.outbox.confirm-timeout-ms", Long.class, 5000L);
    this.retryDelaySeconds =
        environment.getProperty("cloudmall.order.outbox.retry-delay-seconds", Long.class, 10L);
    this.rabbitTemplate.setMandatory(true);
  }

  /** Publishes pending timeout events after their local transaction has committed. */
  @Scheduled(fixedDelayString = "${cloudmall.order.outbox.dispatch-interval-ms:1000}")
  public void dispatchPending() {
    List<OrderOutboxPO> pendingRows = orderOutboxMapper.findPendingOutbox(batchSize);
    for (OrderOutboxPO pendingRow : pendingRows) {
      dispatchOne(pendingRow);
    }
  }

  /** 只处理计数可解释的当前快照；保留原eventId，只有确认且未被退回时才结束重试。 */
  private void dispatchOne(OrderOutboxPO outbox) {
    if (outbox.attempts == null || outbox.attempts < 0) {
      log.warn("订单超时outbox尝试次数快照非法，保留该项，eventId={}, attempts={}", outbox.eventId, outbox.attempts);
      return;
    }
    try {
      Map<String, Object> payload = parsePayload(outbox.payload);
      String expireAt = stringValue(payload.get("expireAt"));
      if (expireAt == null || expireAt.isBlank()) {
        throw new IllegalArgumentException("ORDER_TIMEOUT outbox 缺少 expireAt: " + outbox.eventId);
      }
      long ttlMillis = remainingTtlMillis(expireAt);
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("eventId", outbox.eventId);
      envelope.put("eventType", outbox.eventType);
      envelope.put("orderNo", outbox.orderNo);
      envelope.put("expireAt", expireAt);
      envelope.put("occurredAt", stringValue(payload.get("occurredAt")));
      envelope.put("traceId", stringValue(payload.get("traceId")));
      envelope.put("payload", payload);

      CorrelationData correlationData = new CorrelationData(outbox.eventId);
      rabbitTemplate.convertAndSend(
          OrderMessagingConfiguration.TIMEOUT_EXCHANGE,
          "",
          envelope,
          message -> withTtl(message, ttlMillis),
          correlationData);
      CorrelationData.Confirm confirm =
          correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      Message returnedMessage = correlationData.getReturnedMessage();
      if (confirm == null || !confirm.isAck() || returnedMessage != null) {
        String reason = confirm == null ? "未收到 publisher confirm" : confirm.getReason();
        if (returnedMessage != null) {
          reason = "消息被 RabbitMQ returned";
        }
        throw new IllegalStateException(reason);
      }
      orderOutboxMapper.markOutboxPublished(outbox.eventId, LocalDateTime.now(BUSINESS_ZONE));
    } catch (Exception exception) {
      LocalDateTime nextRetryAt = LocalDateTime.now(BUSINESS_ZONE).plusSeconds(retryDelaySeconds);
      String error = compactError(exception);
      orderOutboxMapper.recordOutboxFailure(outbox.eventId, error, nextRetryAt);
      log.warn("订单超时 outbox 发布失败，将重试，eventId={}, nextRetryAt={}", outbox.eventId, nextRetryAt, exception);
    }
  }

  /** 解析本地事务写入的 JSON，非法内容进入失败重试和告警路径。 */
  private Map<String, Object> parsePayload(String payload) {
    if (payload == null || payload.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception exception) {
      throw new MessageConversionException("订单超时 outbox payload 不是合法 JSON", exception);
    }
  }

  private static Message withTtl(Message message, long ttlMillis) {
    message.getMessageProperties().setExpiration(String.valueOf(Math.max(1L, ttlMillis)));
    return message;
  }

  private static long remainingTtlMillis(String value) {
    OffsetDateTime expiration = parseExpiration(value);
    return Math.max(1L, Duration.between(OffsetDateTime.now(ZoneOffset.ofHours(8)), expiration).toMillis());
  }

  private static OffsetDateTime parseExpiration(String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
      return LocalDateTime.parse(value).atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** 保留原异常消息或类名兜底，不做有损字节截断，持久化列宽由Mapper保护。 */
  private static String compactError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = exception.getClass().getSimpleName();
    }
    return message;
  }
}
