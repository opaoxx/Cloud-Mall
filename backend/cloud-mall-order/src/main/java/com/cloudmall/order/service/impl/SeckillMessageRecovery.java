package com.cloudmall.order.service.impl;

import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.util.OrderCreationTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 持久化并自动重放秒杀死信，确保原事件号和载荷不变。 */
@Component
public class SeckillMessageRecovery {
  private static final Logger LOGGER = LoggerFactory.getLogger(SeckillMessageRecovery.class);
  private final SeckillMessageRecoveryMapper recoveryMapper;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final StockClient stockClient;
  /** 恢复操作的单次新鲜时钟；测试可模拟批次停顿，不注册新的配置 Bean。 */
  private Clock recoveryClock = Clock.system(ZoneOffset.ofHours(8));

  public SeckillMessageRecovery(
      SeckillMessageRecoveryMapper recoveryMapper,
      RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper,
      StockClient stockClient) {
    this.recoveryMapper = recoveryMapper;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.stockClient = stockClient;
    this.rabbitTemplate.setMandatory(true);
  }

  /** 死信先落库，方法正常返回后 RabbitMQ 才确认消息。 */
  @RabbitListener(
      queues = OrderMessagingConfiguration.SECKILL_DLQ,
      containerFactory = "seckillDlqContainerFactory")
  public void consumeDeadLetter(Message message, Channel channel) {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    try {
      persistDeadLetter(message);
      channel.basicAck(deliveryTag, false);
    } catch (Exception persistenceFailure) {
      try {
        republishForDelayedRetry(message, persistenceFailure);
        channel.basicAck(deliveryTag, false);
      } catch (Exception retryFailure) {
        try {
          channel.basicNack(deliveryTag, false, true);
        } catch (Exception nackFailure) {
          retryFailure.addSuppressed(nackFailure);
          throw new IllegalStateException("秒杀死信无法落库且 nack 失败", retryFailure);
        }
        LOGGER.warn("秒杀死信落库失败，延迟重试投递失败，已请求 RabbitMQ 重新投递", retryFailure);
      }
    }
  }

  private void persistDeadLetter(Message message) {
    String raw = new String(message.getBody(), StandardCharsets.UTF_8);
    Map<String, Object> event;
    String eventId;
    String requestHash;
    String status = "REPLAY_PENDING";
    try {
      event = objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
      eventId = stableEventId(event, raw);
      boolean legacyPayload = "SECKILL_ORDER_ACCEPTED".equals(event.get("eventType"))
          && event.get("addressSnapshot") == null;
      boolean coreFieldsValid = hasCoreFields(event);
      normalizeLegacy(event, eventId);
      String calculatedHash = requestHash(event, raw);
      String suppliedHash = event.get("requestHash") == null
          ? calculatedHash : String.valueOf(event.get("requestHash"));
      requestHash = suppliedHash;
      event.put("eventId", eventId);
      event.put("requestHash", suppliedHash);
      raw = objectMapper.writeValueAsString(event);
      // 旧 DLQ 没有库存受理事实可供订单侧核验，先隔离保留证据，避免幽灵订单。
      if (!coreFieldsValid || !calculatedHash.equalsIgnoreCase(suppliedHash)) status = "CONFLICT";
      else if (legacyPayload) status = "QUARANTINED";
    } catch (Exception exception) {
      eventId = "legacy-seckill:hash:" + sha256(raw);
      requestHash = sha256(raw);
      status = "CONFLICT";
      try {
        raw = objectMapper.writeValueAsString(Map.of("rawPayload", raw));
      } catch (Exception serializationException) {
        throw new IllegalStateException("无法保存秒杀死信原始载荷", serializationException);
      }
    }
    recoveryMapper.saveDlq(id(), eventId, requestHash, raw, status, LocalDateTime.now());
  }

  private void republishForDelayedRetry(Message message, Exception failure) throws Exception {
    MessageProperties properties = message.getMessageProperties();
    properties.setHeader("x-cloudmall-dlq-retry", true);
    CorrelationData correlation = new CorrelationData(
        "dlq-retry:" + UUID.randomUUID());
    rabbitTemplate.send(
        OrderMessagingConfiguration.SECKILL_DLQ_RETRY_EXCHANGE, "", message, correlation);
    CorrelationData.Confirm confirm = correlation.getFuture().get(5000, TimeUnit.MILLISECONDS);
    if (confirm == null || !confirm.isAck() || correlation.getReturnedMessage() != null) {
      throw new IllegalStateException("死信延迟重试消息未收到有效 publisher confirm", failure);
    }
  }

  /** 只重放到达时间的记录；失败继续退避，超过阈值仍不停止。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.seckill-recovery-interval-ms:5000}")
  public void replayDue() {
    // 1. 扫描时刻只用于发现候选；不能作为整批记录的租约起点。
    LocalDateTime scanNow = recoveryNow();
    List<Map<String, Object>> rows = recoveryMapper.findDue(50, scanNow);
    for (Map<String, Object> candidate : rows) {
      long id = ((Number) candidate.get("id")).longValue();
      String owner = UUID.randomUUID().toString();
      // 2. 每条记录独立采样，并在外部 Stock 查询前核验当前归属。
      LocalDateTime claimNow = recoveryNow();
      if (recoveryMapper.claim(id, owner, claimNow.plusSeconds(30), claimNow) != 1) continue;
      Map<String, Object> row = claimedRecord(id, owner);
      if (row == null) continue;
      Integer attempts = nextAttempt(row.get("attempts"));
      if (attempts == null) {
        LOGGER.warn("秒杀恢复计数非法，保留原事实并跳过，id={}", id);
        continue;
      }
      if (!verifyStockFact(row, id, owner)) continue;
      try {
        String eventId = String.valueOf(row.get("event_id"));
        CorrelationData correlation = new CorrelationData(eventId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(eventId);
        Message outbound =
            new Message(
                String.valueOf(row.get("payload")).getBytes(StandardCharsets.UTF_8), properties);
        // 3. 慢 RPC 后再检查；发送开始后的竞态由原幂等和17J完成写CAS约束。
        if (!ownsCurrentLease(id, owner)) continue;
        rabbitTemplate.send(
            OrderMessagingConfiguration.SECKILL_EXCHANGE, "", outbound, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(5000, TimeUnit.MILLISECONDS);
        if (confirm == null || !confirm.isAck() || correlation.getReturnedMessage() != null)
          throw new IllegalStateException("秒杀事件未收到有效 publisher confirm");
        recoveryMapper.markReplayed(
            id,
            owner,
            attempts,
            recoveryNow().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8))),
            null);
      } catch (Exception exception) {
        recoveryMapper.markReplayed(
            id,
            owner,
            attempts,
            recoveryNow().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8))),
            exception.toString());
      }
    }
  }

  /** 按上海偏移读取本次操作的毫秒时间，避免依赖 JVM 默认时区。 */
  private LocalDateTime recoveryNow() {
    return LocalDateTime.ofInstant(recoveryClock.instant(), ZoneOffset.ofHours(8))
        .truncatedTo(ChronoUnit.MILLIS);
  }

  /** 只使用当前认领后的快照；读取失败不回退到扫描旧值。 */
  private Map<String, Object> claimedRecord(long id, String owner) {
    try {
      return recoveryMapper.findClaimed(id, owner);
    } catch (DataAccessException unavailableSnapshot) {
      LOGGER.warn("秒杀恢复认领快照读取失败，本次跳过，id={}", id, unavailableSnapshot);
      return null;
    }
  }

  /** 验证数据库整数范围后用 long 递增，最大值饱和；异常事实不能猜测归零。 */
  private static Integer nextAttempt(Object storedAttempts) {
    if (!(storedAttempts instanceof Number number)) return null;
    try {
      long current = new BigDecimal(number.toString()).longValueExact();
      if (current < 0 || current > Integer.MAX_VALUE) return null;
      return (int) Math.min(Integer.MAX_VALUE, current + 1L);
    } catch (ArithmeticException | NumberFormatException invalidCount) {
      return null;
    }
  }

  /** 归属无法读取时不猜测有效性，也不尝试清理可能已归他人的租约。 */
  private boolean ownsCurrentLease(long id, String owner) {
    try {
      return recoveryMapper.hasActiveLease(id, owner);
    } catch (DataAccessException unavailableLease) {
      LOGGER.warn("秒杀恢复租约核验失败，本次跳过，id={}", id, unavailableLease);
      return false;
    }
  }

  /** 只有库存受理台账确认存在时才允许重放事件。 */
  private boolean verifyStockFact(Map<String, Object> row, long id, String owner) {
    try {
      Map<String, Object> event =
          objectMapper.readValue(
              String.valueOf(row.get("payload")),
              new TypeReference<LinkedHashMap<String, Object>>() {});
      String orderNo = String.valueOf(event.getOrDefault("orderNo", ""));
      if (orderNo.isBlank()) {
        recoveryMapper.markVerification(
            id, owner, "CONFLICT", "缺少 orderNo，无法核对库存受理", recoveryNow().plusMinutes(5));
        return false;
      }
      // 时间证据不足时保留原载荷和事件号，避免在 Stock 查询与 Rabbit 重发之间空转。
      try {
        OrderCreationTime.forSeckill(orderNo, event.get("occurredAt"));
      } catch (IllegalArgumentException unverifiedTime) {
        recoveryMapper.markVerification(
            id, owner, "QUARANTINED", unverifiedTime.getMessage(), recoveryNow().plusMinutes(5));
        return false;
      }
      var response =
          OrderStockCircuitBreaker.call(
              OrderStockCircuitBreaker.STATUS,
              () -> stockClient.seckillReservationFact(orderNo, "cloud-mall-order"));
      if (response == null || !"0".equals(response.code) || response.data == null) {
        recoveryMapper.markVerification(
            id, owner, "VERIFYING", "库存受理查询响应无效", recoveryNow().plusSeconds(30));
        return false;
      }
      String status = String.valueOf(response.data.get("status"));
      if ("NOT_FOUND".equals(status) || "REJECTED".equals(status)) {
        // 没有库存事实的存量死信必须保留并持续核验，不能因字段缺失误判为冲突或创建幽灵订单。
        recoveryMapper.markVerification(
            id, owner, "QUARANTINED", "库存受理状态=" + status, recoveryNow().plusMinutes(5));
        return false;
      }
      if (!hasCoreFields(event) || !matchesEvent(event, response.data)) {
        recoveryMapper.markVerification(
            id, owner, "CONFLICT", "库存受理事实与原事件不一致", recoveryNow().plusMinutes(5));
        return false;
      }
      if ("ACCEPTED".equals(status) || "PUBLISHED".equals(status) || "ORDER_CREATED".equals(status))
        return true;
      recoveryMapper.markVerification(
          id, owner, "VERIFYING", "库存受理状态未知=" + status, recoveryNow().plusSeconds(30));
    } catch (Exception exception) {
      recoveryMapper.markVerification(
          id, owner, "VERIFYING", exception.toString(), recoveryNow().plusSeconds(30));
    }
    return false;
  }

  private boolean matchesEvent(Map<String, Object> event, Map<String, Object> fact) {
    if (fact == null
        || !hasCoreFields(event)
        || !hasCoreFields(fact)
        || !same(event, fact, "orderNo")
        || !same(event, fact, "activityId")
        || !same(event, fact, "skuId")
        || !same(event, fact, "userId")
        || !same(event, fact, "idempotencyKey")) return false;
    String expectedHash = requestHash(event, "");
    return expectedHash.equalsIgnoreCase(String.valueOf(event.get("requestHash")))
        && expectedHash.equalsIgnoreCase(String.valueOf(fact.get("requestHash")));
  }

  private static boolean same(Map<String, Object> event, Map<String, Object> fact, String field) {
    return String.valueOf(event.get(field)).equals(String.valueOf(fact.get(field)));
  }

  private static boolean hasCoreFields(Map<String, Object> event) {
    return event.get("orderNo") != null && event.get("activityId") != null
        && event.get("skuId") != null && event.get("userId") != null
        && event.get("idempotencyKey") != null
        && !String.valueOf(event.get("orderNo")).isBlank()
        && !String.valueOf(event.get("idempotencyKey")).isBlank();
  }

  private void normalizeLegacy(Map<String, Object> event, String eventId) {
    event.putIfAbsent("eventType", "SECKILL_ORDER_REQUEST");
    event.putIfAbsent("payloadVersion", 0);
    event.putIfAbsent("addressSnapshot", "{}");
    event.put("eventId", eventId);
  }

  private String requestHash(Map<String, Object> event, String raw) {
    try {
      if (event.get("activityId") != null && event.get("skuId") != null && event.get("userId") != null)
        return SeckillRequestFingerprint.hash(
            Long.parseLong(String.valueOf(event.get("activityId"))),
            Long.parseLong(String.valueOf(event.get("skuId"))),
            Long.parseLong(String.valueOf(event.get("userId"))),
            1,
            String.valueOf(event.getOrDefault("addressSnapshot", "{}")));
    } catch (RuntimeException ignored) {
      // malformed or legacy payload is retained as conflict evidence below
    }
    return sha256(raw);
  }

  private static String stableEventId(Map<String, Object> event, String raw) {
    if (event.get("eventId") != null && !String.valueOf(event.get("eventId")).isBlank())
      return String.valueOf(event.get("eventId"));
    if (event.get("orderNo") != null && !String.valueOf(event.get("orderNo")).isBlank())
      return "legacy-seckill:" + event.get("orderNo");
    if (event.get("userId") != null && event.get("idempotencyKey") != null)
      return "legacy-seckill:key:" + event.get("userId") + ":" + event.get("idempotencyKey");
    return "legacy-seckill:hash:" + sha256(raw);
  }

  private static long id() { return Math.abs(UUID.randomUUID().getMostSignificantBits()); }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
