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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
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

  /**
   * 投递已提交的超时候选；保留明确中断信号并停止未来动作，单条数据库异常保原隔离。
   *
   * @throws DataAccessException 非中断扫描数据库失败，不能将扫描不可用伪装为单条跳过
   */
  @Scheduled(fixedDelayString = "${cloudmall.order.outbox.dispatch-interval-ms:1000}")
  public void dispatchPending() {
    if (Thread.currentThread().isInterrupted()) return;
    // 1. 扫描失败继续传播，不生成虚假候选或吞掉数据库不可用。
    List<OrderOutboxPO> pendingRows;
    try {
      pendingRows = orderOutboxMapper.findPendingOutbox(batchSize);
    } catch (RuntimeException scanFailure) {
      if (interrupted(scanFailure)) return;
      throw scanFailure;
    }
    if (Thread.currentThread().isInterrupted()) return;
    for (OrderOutboxPO pendingRow : pendingRows) {
      if (Thread.currentThread().isInterrupted()) return;
      // 2. 只隔离明确的单候选数据库异常，不重发或追加本候选写入。
      try {
        dispatchOne(pendingRow);
      } catch (DataAccessException databaseFailure) {
        if (interrupted(databaseFailure)) return;
        log.warn("订单超时outbox数据库写入失败，保留当前事实并继续后项，eventId={}", pendingRow.eventId, databaseFailure);
      } catch (RuntimeException candidateFailure) {
        if (interrupted(candidateFailure)) return;
        throw candidateFailure;
      }
    }
  }

  /** 只处理计数可解释的当前快照；保留原eventId，只有确认且未被退回时才结束重试。 */
  private void dispatchOne(OrderOutboxPO outbox) {
    if (Thread.currentThread().isInterrupted()) return;
    if (outbox.attempts == null || outbox.attempts < 0) {
      log.warn("订单超时outbox尝试次数快照非法，保留该项，eventId={}, attempts={}", outbox.eventId, outbox.attempts);
      return;
    }
    try {
      Map<String, Object> payload = parsePayload(outbox.payload);
      if (Thread.currentThread().isInterrupted()) return;
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
      if (Thread.currentThread().isInterrupted()) return;
      rabbitTemplate.convertAndSend(
          OrderMessagingConfiguration.TIMEOUT_EXCHANGE,
          "",
          envelope,
          message -> withTtl(message, ttlMillis),
          correlationData);
      if (Thread.currentThread().isInterrupted()) return;
      CorrelationData.Confirm confirm =
          correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      if (Thread.currentThread().isInterrupted()) return;
      Message returnedMessage = correlationData.getReturnedMessage();
      if (confirm == null || !confirm.isAck() || returnedMessage != null) {
        String reason = confirm == null ? "未收到 publisher confirm" : confirm.getReason();
        if (returnedMessage != null) {
          reason = "消息被 RabbitMQ returned";
        }
        throw new IllegalStateException(reason);
      }
      if (Thread.currentThread().isInterrupted()) return;
      int publishedRows =
          orderOutboxMapper.markOutboxPublished(outbox.eventId, LocalDateTime.now(BUSINESS_ZONE));
      if (Thread.currentThread().isInterrupted()) return;
      if (publishedRows == 1) return;
      if (publishedRows != 0)
        throw new OptimisticLockingFailureException("订单超时outbox完成更新行数不可信: " + outbox.eventId);
      // 0可能是同事件已由其他投递者完成；仅接受精确身份的唯一PUBLISHED事实。
      List<OrderOutboxPO> publicationFacts = orderOutboxMapper.findPublicationFacts(outbox.eventId);
      if (Thread.currentThread().isInterrupted()) return;
      if (publicationFacts == null
          || publicationFacts.size() != 1
          || publicationFacts.get(0) == null
          || outbox.eventId == null
          || !outbox.eventId.equals(publicationFacts.get(0).eventId)
          || !"PUBLISHED".equals(publicationFacts.get(0).status))
        throw new OptimisticLockingFailureException("订单超时outbox发布完成事实不可信: " + outbox.eventId);
    } catch (DataAccessException databaseFailure) {
      if (interrupted(databaseFailure)) return;
      // 完成写的数据库失败不等于发送失败，交由候选边界处理而非再次写failure。
      throw databaseFailure;
    } catch (Exception exception) {
      if (interrupted(exception)) return;
      LocalDateTime nextRetryAt = LocalDateTime.now(BUSINESS_ZONE).plusSeconds(retryDelaySeconds);
      String error = compactError(exception);
      if (Thread.currentThread().isInterrupted()) return;
      orderOutboxMapper.recordOutboxFailure(outbox.eventId, error, nextRetryAt);
      log.warn(
          "订单超时 outbox 发布失败，将重试，eventId={}, nextRetryAt={}",
          outbox.eventId,
          nextRetryAt,
          exception);
    }
  }

  /** 仅识别当前标志或cause中的明确InterruptedException，保信号且防cause环，不把普通I/O超时当取消。 */
  private static boolean interrupted(Throwable failure) {
    if (Thread.currentThread().isInterrupted()) return true;
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return true;
      }
    }
    return false;
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
