package com.cloudmall.stock.service.impl;

import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 恢复秒杀受理窗口并可靠投递已提交的订单 outbox。 */
@Component
public class SeckillOrderOutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(SeckillOrderOutboxDispatcher.class);
  private static final String EXCHANGE = "cloudmall.seckill.order.exchange";
  private static final long PENDING_MARKER_GRACE_SECONDS = 30L;

  private final StockSqlMapper stockSqlMapper;
  private final SeckillAcceptanceService acceptanceService;
  private final StringRedisTemplate redis;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final int batchSize;
  private final long confirmTimeoutMillis;

  /** marker宽限期使用瞬时时钟；测试可固定边界，不依赖JVM默认时区。 */
  private Clock markerClock = Clock.systemUTC();

  /** 单实例定时扫描的非持久主键游标；null表示初次扫描，进程重启后从头检查。 */
  private Long markerScanAfterId;

  public SeckillOrderOutboxDispatcher(
      StockSqlMapper stockSqlMapper,
      SeckillAcceptanceService acceptanceService,
      StringRedisTemplate redis,
      RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper,
      org.springframework.core.env.Environment environment) {
    this.stockSqlMapper = stockSqlMapper;
    this.acceptanceService = acceptanceService;
    this.redis = redis;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.batchSize = environment.getProperty("cloudmall.stock.seckill.outbox.batch-size", Integer.class, 50);
    this.confirmTimeoutMillis = environment.getProperty("cloudmall.stock.seckill.outbox.confirm-timeout-ms", Long.class, 5000L);
    this.rabbitTemplate.setMandatory(true);
  }

  /** 恢复 Lua 后进程退出的 PENDING 受理，再扫描可投递 outbox。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.seckill.outbox.dispatch-interval-ms:1000}")
  public void dispatch() {
    recoverPendingMarkers();
    String owner = UUID.randomUUID().toString();
    OffsetDateTime now = now();
    for (Map<String, Object> row : stockSqlMapper.findPendingSeckillOutbox(now, batchSize)) {
      long id = ((Number) row.get("id")).longValue();
      if (stockSqlMapper.claimSeckillOutbox(id, owner, now.plusSeconds(30), now) == 1) {
        dispatchOne(row, owner);
      }
    }
  }

  /** Lua 已经留下订单 marker 时补齐 ACCEPTED 和同一 eventId 的 outbox。 */
  private void recoverPendingMarkers() {
    for (Map<String, Object> row : nextMarkerCandidates()) {
      String orderNo = String.valueOf(row.get("order_no"));
      String marker = redis.opsForValue().get(
          "seckill:accepted:" + row.get("activity_id") + ":" + row.get("sku_id") + ":"
              + row.get("user_id") + ":" + row.get("idempotency_key"));
      if (!orderNo.equals(marker)) {
        if (isPastMarkerGrace(row.get("updated_at"))) {
          int transitioned = stockSqlMapper.markSeckillUnknown(
              ((Number) row.get("user_id")).longValue(),
              String.valueOf(row.get("idempotency_key")),
              "REDIS_MARKER_MISSING_AFTER_GRACE_PERIOD",
              now());
          if (transitioned == 1) {
            log.error("秒杀受理缺少 Redis marker，已转 UNKNOWN 保留人工/对账证据，orderNo={}", orderNo);
          } else {
            try {
              if (!alreadyCommitted(row, orderNo))
                log.warn("秒杀缺少 marker 且 UNKNOWN 转换未命中，保留原记录继续核验，orderNo={}", orderNo);
            } catch (RuntimeException verificationFailure) {
              log.warn("秒杀缺少 marker 后的持久事实核验失败，orderNo={}", orderNo,
                  verificationFailure);
            }
          }
        }
        continue;
      }
      try {
        acceptanceService.accept(
            ((Number) row.get("user_id")).longValue(),
            String.valueOf(row.get("idempotency_key")),
            orderNo,
            SeckillRequestFingerprint.eventId(orderNo),
            String.valueOf(row.get("request_payload")),
            String.valueOf(row.get("request_hash")),
            now());
      } catch (RuntimeException recoveryFailure) {
        StockDependencyGuard.noteMysqlFailure(recoveryFailure);
        try {
          if (alreadyCommitted(row, orderNo)) continue;
        } catch (RuntimeException verificationFailure) {
          recoveryFailure.addSuppressed(verificationFailure);
        }
        log.warn("秒杀 PENDING 受理恢复失败，将在下一轮重试，orderNo={}", orderNo, recoveryFailure);
      }
    }
  }

  /** 单轮最多回绕一次，不补满短批次；读成功后先推进游标，外部操作失败不霸占下一轮。 */
  private List<Map<String, Object>> nextMarkerCandidates() {
    List<Map<String, Object>> rows =
        markerScanAfterId == null
            ? stockSqlMapper.findPendingSeckillReservations(batchSize)
            : stockSqlMapper.findPendingSeckillReservationsAfter(markerScanAfterId, batchSize);
    if (rows.isEmpty() && markerScanAfterId != null) {
      rows = stockSqlMapper.findPendingSeckillReservations(batchSize);
    }
    if (!rows.isEmpty()) {
      markerScanAfterId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
    }
    return rows;
  }

  /** CAS 败方只在原订单号、用户幂等键、哈希和 outbox 事件全匹配时静默收敛。 */
  private boolean alreadyCommitted(Map<String, Object> scanned, String orderNo) {
    Map<String, Object> reservation = stockSqlMapper.findSeckillByOrderNo(orderNo);
    Map<String, Object> outbox = stockSqlMapper.findSeckillOutboxByOrderNo(orderNo);
    if (reservation == null || reservation.isEmpty() || outbox == null || outbox.isEmpty())
      return false;
    String status = String.valueOf(reservation.get("status"));
    String outboxStatus = String.valueOf(outbox.get("status"));
    return Set.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED").contains(status)
        && Set.of("PENDING", "PUBLISHED", "UNKNOWN", "DEAD").contains(outboxStatus)
        && orderNo.equals(String.valueOf(reservation.get("order_no")))
        && String.valueOf(scanned.get("user_id")).equals(String.valueOf(reservation.get("user_id")))
        && String.valueOf(scanned.get("idempotency_key"))
            .equals(String.valueOf(reservation.get("idempotency_key")))
        && String.valueOf(scanned.get("request_hash"))
            .equals(String.valueOf(reservation.get("request_hash")))
        && SeckillRequestFingerprint.eventId(orderNo).equals(String.valueOf(outbox.get("event_id")))
        && String.valueOf(scanned.get("request_hash"))
            .equals(String.valueOf(outbox.get("request_hash")));
  }

  /** 判断 PENDING 记录是否已超过 marker 恢复宽限期，避免旧记录永久占用扫描窗口。 */
  private boolean isPastMarkerGrace(Object updatedAtValue) {
    try {
      Instant updatedAt;
      if (updatedAtValue instanceof LocalDateTime localDateTime) {
        updatedAt = localDateTime.toInstant(ZoneOffset.ofHours(8));
      } else if (updatedAtValue instanceof java.sql.Timestamp timestamp) {
        // JDBC DATETIME保存上海本地字段，不能按Timestamp携带的JVM默认区解释。
        updatedAt = timestamp.toLocalDateTime().toInstant(ZoneOffset.ofHours(8));
      } else if (updatedAtValue instanceof OffsetDateTime offsetDateTime) {
        updatedAt = offsetDateTime.toInstant();
      } else {
        return false;
      }
      return updatedAt.plusSeconds(PENDING_MARKER_GRACE_SECONDS).isBefore(markerClock.instant());
    } catch (DateTimeException | ArithmeticException invalidTime) {
      // 无法解释的历史时间保留待核验，不猜测已超期。
      return false;
    }
  }

  /** 检查 publisher confirm 和 returned message，失败时保留 eventId 退避。 */
  private void dispatchOne(Map<String, Object> row, String owner) {
    String eventId = String.valueOf(row.get("event_id"));
    try {
      Map<String, Object> payload =
          objectMapper.readValue(
              String.valueOf(row.get("payload")),
              new TypeReference<LinkedHashMap<String, Object>>() {});
      Map<String, Object> envelope = new LinkedHashMap<>(payload);
      envelope.put("eventId", eventId);
      envelope.put("eventType", "SECKILL_ORDER_REQUEST");
      envelope.put("payloadVersion", 1);
      envelope.put("orderNo", row.get("order_no"));
      envelope.put("requestHash", row.get("request_hash"));
      // 创建时间来自首次受理事实；接受/投递/重试的调度时间不能替换它。
      envelope.put(
          "occurredAt",
          SeckillEventTimeResolver.resolve(
                  row,
                  payload,
                  stockSqlMapper.findSeckillByOrderNo(String.valueOf(row.get("order_no"))))
              .toString());
      CorrelationData correlationData = new CorrelationData(eventId);
      rabbitTemplate.convertAndSend(EXCHANGE, "", envelope, correlationData);
      CorrelationData.Confirm confirm =
          correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      Message returned = correlationData.getReturnedMessage();
      if (confirm == null || !confirm.isAck() || returned != null) {
        throw new IllegalStateException(
            returned == null ? "publisher confirm 未确认" : "消息被 RabbitMQ returned");
      }
      stockSqlMapper.markSeckillOutboxPublished(eventId, owner, now());
    } catch (Exception publishFailure) {
      stockSqlMapper.recordSeckillOutboxFailure(
          eventId, owner, compactError(publishFailure), now().plusSeconds(10), now());
      log.warn("秒杀订单 outbox 投递失败，将使用同一 eventId 重试，eventId={}", eventId, publishFailure);
    }
  }

  private static String compactError(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
    return message.substring(0, Math.min(message.length(), 512));
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }
}
