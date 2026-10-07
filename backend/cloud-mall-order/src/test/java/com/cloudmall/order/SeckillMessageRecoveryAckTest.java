package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.service.impl.SeckillMessageRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SeckillMessageRecoveryAckTest {
  @Test
  void interruptedDelayedPublishRestoresInterruptAfterNackEvenWhenNackFails() throws Exception {
    for (boolean nackFails : java.util.List.of(false, true)) {
      var mapper = mock(SeckillMessageRecoveryMapper.class);
      var rabbit = mock(RabbitTemplate.class);
      var channel = mock(Channel.class);
      doThrow(new IllegalStateException("database unavailable"))
          .when(mapper)
          .saveDlq(anyLong(), any(), any(), any(), any(), any());
      doAnswer(
              invocation -> {
                Thread.currentThread().interrupt();
                return null;
              })
          .when(rabbit)
          .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
      IOException nackFailure = new IOException("nack failed");
      if (nackFails) doThrow(nackFailure).when(channel).basicNack(0L, false, true);
      var recovery =
          new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), mock(StockClient.class));
      try {
        if (nackFails) {
          var failure =
              assertThrows(
                  IllegalStateException.class,
                  () -> recovery.consumeDeadLetter(message("not-json"), channel));
          assertTrue(failure.getCause() instanceof InterruptedException);
          assertArrayEquals(new Throwable[] {nackFailure}, failure.getCause().getSuppressed());
        } else recovery.consumeDeadLetter(message("not-json"), channel);
        assertTrue(
            Thread.currentThread().isInterrupted(), "listener must preserve shutdown signal");
      } finally {
        Thread.interrupted();
      }
      verify(channel, times(1)).basicNack(0L, false, true);
      verify(channel, never()).basicAck(anyLong(), anyBoolean());
      verify(rabbit, times(1))
          .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
  }

  @Test
  void acknowledgementFailureAfterPersistenceDoesNotRepublishOrNack() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    IOException acknowledgementFailure = new IOException("ack outcome unknown");
    doThrow(acknowledgementFailure).when(channel).basicAck(0L, false);
    var recovery =
        new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), mock(StockClient.class));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> recovery.consumeDeadLetter(message("not-json"), channel));
    assertSame(acknowledgementFailure, failure.getCause());
    verify(mapper, times(1)).saveDlq(anyLong(), any(), any(), any(), any(), any());
    verify(channel, times(1)).basicAck(0L, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(rabbit, never())
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
  }

  @Test
  void acknowledgementFailureAfterConfirmedRetryDoesNotNackOrSendAgain() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(mapper)
        .saveDlq(anyLong(), any(), any(), any(), any(), any());
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    IOException acknowledgementFailure = new IOException("ack outcome unknown");
    doThrow(acknowledgementFailure).when(channel).basicAck(0L, false);
    var recovery =
        new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), mock(StockClient.class));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> recovery.consumeDeadLetter(message("not-json"), channel));
    assertSame(acknowledgementFailure, failure.getCause());
    var sequence = inOrder(mapper, rabbit, channel);
    sequence.verify(mapper).saveDlq(anyLong(), any(), any(), any(), any(), any());
    sequence
        .verify(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    sequence.verify(channel).basicAck(0L, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(rabbit, times(1))
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
  }

  @Test
  void failedRetryAndFailedNackPropagateBothFailuresWithoutAck() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(mapper)
        .saveDlq(anyLong(), any(), any(), any(), any(), any());
    IllegalStateException publishFailure = new IllegalStateException("publish outcome unknown");
    IOException nackFailure = new IOException("nack outcome unknown");
    doThrow(publishFailure)
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    doThrow(nackFailure).when(channel).basicNack(0L, false, true);
    var recovery =
        new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), mock(StockClient.class));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> recovery.consumeDeadLetter(message("not-json"), channel));
    assertSame(publishFailure, failure.getCause());
    assertArrayEquals(new Throwable[] {nackFailure}, publishFailure.getSuppressed());
    verify(channel, times(1)).basicNack(0L, false, true);
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }

  @Test
  void successfulPersistenceAcknowledgesTheDeadLetter() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    SeckillMessageRecovery recovery =
        new SeckillMessageRecovery(
            mapper, rabbitTemplate, new ObjectMapper(), mock(StockClient.class));
    Message message =
        message(
            "{\"eventId\":\"event-1\",\"orderNo\":\"202609abc\","
                + "\"activityId\":1,\"skuId\":2,\"userId\":3,\"idempotencyKey\":\"key\","
                + "\"addressSnapshot\":{},\"requestHash\":\"bad\"}");

    recovery.consumeDeadLetter(message, channel);

    verify(channel).basicAck(0L, false);
    verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
  }

  @Test
  void persistenceAndDelayedPublishFailureOnlyRequestsRabbitRedelivery() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(mapper)
        .saveDlq(any(Long.class), any(), any(), any(), any(), any());
    doThrow(new IllegalStateException("broker unavailable"))
        .when(rabbitTemplate)
        .send(any(), any(), any(), any());
    Channel channel = mock(Channel.class);
    SeckillMessageRecovery recovery =
        new SeckillMessageRecovery(
            mapper, rabbitTemplate, new ObjectMapper(), mock(StockClient.class));

    recovery.consumeDeadLetter(message("not-json"), channel);

    verify(channel).basicNack(0L, false, true);
    verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
  }

  private static Message message(String payload) {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(0L);
    return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
  }
}
