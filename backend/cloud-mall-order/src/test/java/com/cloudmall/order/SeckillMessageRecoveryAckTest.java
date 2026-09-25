package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.service.impl.SeckillMessageRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SeckillMessageRecoveryAckTest {
  @Test
  void successfulPersistenceAcknowledgesTheDeadLetter() throws Exception {
    SeckillMessageRecoveryMapper mapper = mock(SeckillMessageRecoveryMapper.class);
    RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    Channel channel = mock(Channel.class);
    SeckillMessageRecovery recovery =
        new SeckillMessageRecovery(mapper, rabbitTemplate, new ObjectMapper(), mock(StockClient.class));
    Message message = message("{\"eventId\":\"event-1\",\"orderNo\":\"202609abc\","
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
        new SeckillMessageRecovery(mapper, rabbitTemplate, new ObjectMapper(), mock(StockClient.class));

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
