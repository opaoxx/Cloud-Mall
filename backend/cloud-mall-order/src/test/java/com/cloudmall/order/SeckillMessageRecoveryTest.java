package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.service.impl.SeckillMessageRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.rabbitmq.client.Channel;

/** 死信验真后才重放原事件，缺库存事实时保留消息。 */
class SeckillMessageRecoveryTest {
  private static final String ORDER_NO = "202609seckillrecovery1";
  private static final String EVENT_ID = "legacy-seckill:" + ORDER_NO;

  @Test
  void legacyDeadLetterIsPersistedBeforeAcknowledgement() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    Channel channel = mock(Channel.class);
    SeckillMessageRecovery recovery = recovery(mapper, mock(StockClient.class), mock(RabbitTemplate.class));
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(17L);
    Message legacy = new Message(
        ("{\"eventType\":\"SECKILL_ORDER_ACCEPTED\",\"orderNo\":\"" + ORDER_NO
            + "\",\"activityId\":11,\"skuId\":22,\"userId\":33,"
            + "\"idempotencyKey\":\"key-1\"}").getBytes(StandardCharsets.UTF_8),
        properties);

    recovery.consumeDeadLetter(legacy, channel);

    verify(mapper).saveDlq(anyLong(), eq(EVENT_ID), anyString(), anyString(),
        eq("QUARANTINED"), any(LocalDateTime.class));
    verify(channel).basicAck(17L, false);
  }

  @Test
  void missingStockAcceptanceKeepsDeadLetterQuarantined() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    StockClient stockClient = mock(StockClient.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    when(mapper.findDue(eq(50), any(LocalDateTime.class))).thenReturn(List.of(row()));
    when(mapper.claim(eq(1L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(1);
    when(stockClient.seckillReservationFact(ORDER_NO, "cloud-mall-order"))
        .thenReturn(ApiResponse.ok(Map.of("orderNo", ORDER_NO, "status", "NOT_FOUND")));

    recovery(mapper, stockClient, rabbit).replayDue();

    verify(mapper).markVerification(eq(1L), anyString(), eq("QUARANTINED"),
        anyString(), any(LocalDateTime.class));
    verify(rabbit, never()).send(anyString(), anyString(), any(Message.class),
        any(CorrelationData.class));
  }

  @Test
  void matchingStockFactReplaysTheOriginalEventOnly() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    StockClient stockClient = mock(StockClient.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    when(mapper.findDue(eq(50), any(LocalDateTime.class))).thenReturn(List.of(row()));
    when(mapper.claim(eq(1L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(1);
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("orderNo", ORDER_NO);
    fact.put("activityId", 11L);
    fact.put("skuId", 22L);
    fact.put("userId", 33L);
    fact.put("idempotencyKey", "key-1");
    fact.put("requestHash", fingerprint());
    fact.put("status", "PUBLISHED");
    when(stockClient.seckillReservationFact(ORDER_NO, "cloud-mall-order"))
        .thenReturn(ApiResponse.ok(fact));
    doAnswer(invocation -> {
      CorrelationData correlation = invocation.getArgument(3);
      correlation.getFuture().set(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).send(anyString(), anyString(), any(Message.class),
        any(CorrelationData.class));

    recovery(mapper, stockClient, rabbit).replayDue();

    ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
    ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbit).send(anyString(), anyString(), message.capture(), correlation.capture());
    assertEquals(EVENT_ID, correlation.getValue().getId());
    assertEquals(String.valueOf(row().get("payload")),
        new String(message.getValue().getBody(), StandardCharsets.UTF_8));
    verify(mapper).markReplayed(eq(1L), anyString(), eq(1), any(LocalDateTime.class), eq(null));
  }

  private static SeckillMessageRecovery recovery(
      SeckillMessageRecoveryMapper mapper, StockClient stockClient, RabbitTemplate rabbit) {
    return new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), stockClient);
  }

  private static Map<String, Object> row() throws Exception {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("eventId", EVENT_ID);
    event.put("eventType", "SECKILL_ORDER_ACCEPTED");
    event.put("orderNo", ORDER_NO);
    event.put("activityId", 11L);
    event.put("skuId", 22L);
    event.put("userId", 33L);
    event.put("idempotencyKey", "key-1");
    event.put("addressSnapshot", "{}");
    event.put("requestHash", fingerprint());
    return Map.of(
        "id", 1L,
        "event_id", EVENT_ID,
        "payload", new ObjectMapper().writeValueAsString(event),
        "request_hash", fingerprint(),
        "attempts", 0);
  }

  private static String fingerprint() {
    return SeckillRequestFingerprint.hash(11L, 22L, 33L, 1, "{}");
  }
}
