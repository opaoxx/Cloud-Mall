package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.service.impl.OrderOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;

class OrderOutboxDispatcherTest {
  @Test
  void marksOutboxPublishedOnlyAfterConfirmedPublish() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.eventId = "event-1";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = "202609event1";
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\",\"traceId\":\"trace-1\"}";
    org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
    doAnswer(
            invocation -> {
              CorrelationData correlationData = invocation.getArgument(4);
              correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));

    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(
            mapper,
            rabbitTemplate,
            new ObjectMapper(),
            new MockEnvironment().withProperty("cloudmall.order.outbox.confirm-timeout-ms", "100"));

    dispatcher.dispatchPending();

    verify(mapper).markOutboxPublished(anyString(), any(LocalDateTime.class));
    verify(mapper, never()).recordOutboxFailure(anyString(), anyString(), any(LocalDateTime.class));
    ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), any(), any(), correlationCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("event-1", correlationCaptor.getValue().getId());
  }

  @Test
  void brokerFailureKeepsTheSameEventPendingForRetry() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.eventId = "event-retry-1";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = "202609retry1";
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\"}";
    org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
    doThrow(new IllegalStateException("broker unavailable"))
        .when(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(
            mapper,
            rabbitTemplate,
            new ObjectMapper(),
            new MockEnvironment().withProperty("cloudmall.order.outbox.confirm-timeout-ms", "100"));

    dispatcher.dispatchPending();
    dispatcher.dispatchPending();

    verify(mapper, times(2))
        .recordOutboxFailure(org.mockito.ArgumentMatchers.eq("event-retry-1"), anyString(), any(LocalDateTime.class));
    verify(mapper, never()).markOutboxPublished(anyString(), any(LocalDateTime.class));
    ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate, times(2))
        .convertAndSend(anyString(), anyString(), any(), any(), correlationCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("event-retry-1", correlationCaptor.getAllValues().get(0).getId());
    org.junit.jupiter.api.Assertions.assertEquals("event-retry-1", correlationCaptor.getAllValues().get(1).getId());
  }
}
