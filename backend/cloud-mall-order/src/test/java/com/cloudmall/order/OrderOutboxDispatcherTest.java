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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;

class OrderOutboxDispatcherTest {
  /** 中断测试必须回收本测试线程信号，不能污染后续测试或真实SQL夹具。 */
  @AfterEach
  void clearTestInterruption() {
    Thread.interrupted();
  }

  @Test
  void preInterruptedDispatchPerformsNoScanParsePublishOrWrite() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment());
    org.mockito.Mockito.clearInvocations(rabbit);
    Thread.currentThread().interrupt();
    dispatcher.dispatchPending();
    org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
    org.mockito.Mockito.verifyNoInteractions(mapper, json, rabbit);
  }

  @Test
  void checkpointSignalsStopFutureActionsWithoutUndoingAnExistingWrite() throws Exception {
    for (String phase : List.of("scan", "parse", "send", "confirm", "complete-return")) {
      try {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
        OrderOutboxPO first = candidate("first-checkpoint");
        OrderOutboxPO second = candidate("unprocessed-checkpoint");
        doAnswer(
                invocation -> {
                  if ("scan".equals(phase)) Thread.currentThread().interrupt();
                  return List.of(first, second);
                })
            .when(mapper)
            .findPendingOutbox(anyInt());
        if ("parse".equals(phase))
          doAnswer(
                  invocation -> {
                    Object result = invocation.callRealMethod();
                    Thread.currentThread().interrupt();
                    return result;
                  })
              .when(json)
              .readValue(
                  anyString(),
                  org.mockito.ArgumentMatchers.any(
                      com.fasterxml.jackson.core.type.TypeReference.class));
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  if ("confirm".equals(phase)) {
                    org.springframework.util.concurrent.SettableListenableFuture<
                            CorrelationData.Confirm>
                        future =
                            org.mockito.Mockito.mock(
                                org.springframework.util.concurrent.SettableListenableFuture.class);
                    doAnswer(
                            wait -> {
                              Thread.currentThread().interrupt();
                              return new CorrelationData.Confirm(true, null);
                            })
                        .when(future)
                        .get(
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class));
                    org.springframework.test.util.ReflectionTestUtils.setField(
                        correlation, "future", future);
                  } else correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  if ("send".equals(phase)) Thread.currentThread().interrupt();
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        if ("complete-return".equals(phase))
          doAnswer(
                  invocation -> {
                    Thread.currentThread().interrupt();
                    return 1;
                  })
              .when(mapper)
              .markOutboxPublished(anyString(), any(LocalDateTime.class));
        new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment()).dispatchPending();
        org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted(), phase);
        verify(mapper).findPendingOutbox(50);
        if ("complete-return".equals(phase))
          verify(mapper)
              .markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        if ("scan".equals(phase) || "parse".equals(phase))
          verify(rabbit, never())
              .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        else
          verify(rabbit)
              .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void explicitInterruptedCausesStopScanBrokerAndDatabasePaths() {
    for (String phase :
        List.of("scan", "broker", "completion-db", "failure-db", "failure-runtime")) {
      try {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        OrderOutboxPO first = candidate("first-cause");
        OrderOutboxPO second = candidate("unprocessed-cause");
        RuntimeException wrapped =
            phase.endsWith("runtime")
                ? new IllegalStateException(
                    "explicit cancellation", new InterruptedException("stop"))
                : new org.springframework.dao.DataAccessResourceFailureException(
                    "explicit cancellation", new InterruptedException("stop"));
        if ("scan".equals(phase)) doThrow(wrapped).when(mapper).findPendingOutbox(anyInt());
        else
          org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
              .thenReturn(List.of(first, second));
        doAnswer(
                invocation -> {
                  if ("broker".equals(phase))
                    throw new org.springframework.amqp.AmqpException(
                        "wrapped interruption", new InterruptedException("stop"));
                  if (phase.startsWith("failure"))
                    throw new IllegalStateException("ordinary broker failure");
                  CorrelationData correlation = invocation.getArgument(4);
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        if ("completion-db".equals(phase))
          doThrow(wrapped).when(mapper).markOutboxPublished(anyString(), any(LocalDateTime.class));
        if (phase.startsWith("failure"))
          doThrow(wrapped)
              .when(mapper)
              .recordOutboxFailure(anyString(), anyString(), any(LocalDateTime.class));
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
            .dispatchPending();
        org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted(), phase);
        verify(mapper).findPendingOutbox(50);
        if ("completion-db".equals(phase))
          verify(mapper)
              .markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
        if (phase.startsWith("failure"))
          verify(mapper)
              .recordOutboxFailure(
                  org.mockito.ArgumentMatchers.eq(first.eventId),
                  anyString(),
                  any(LocalDateTime.class));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        if ("scan".equals(phase))
          verify(rabbit, never())
              .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        else
          verify(rabbit)
              .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void ordinaryIoTimeoutsAndCauseCyclesRemainFailuresRatherThanCancellation() {
    IllegalStateException outer = new IllegalStateException("cycle outer");
    IllegalStateException inner = new IllegalStateException("cycle inner");
    outer.initCause(inner);
    inner.initCause(outer);
    for (Throwable cause :
        List.of(
            new java.net.SocketTimeoutException("read timeout"),
            new java.io.InterruptedIOException("ordinary IO error"),
            outer)) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(candidate("first-timeout"), candidate("second-timeout")));
      doThrow(new org.springframework.amqp.AmqpException("broker IO failure", cause))
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      org.junit.jupiter.api.Assertions.assertFalse(Thread.currentThread().isInterrupted());
      verify(mapper).findPendingOutbox(50);
      verify(mapper, times(2))
          .recordOutboxFailure(anyString(), anyString(), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit, times(2))
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void marksOutboxPublishedOnlyAfterConfirmedPublish() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.eventId = "event-1";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.status = "PENDING";
    outbox.orderNo = "202609event1";
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\",\"traceId\":\"trace-1\"}";
    outbox.attempts = 0;
    org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
    org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
        .thenReturn(1);
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
    ArgumentCaptor<CorrelationData> correlationCaptor =
        ArgumentCaptor.forClass(CorrelationData.class);
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
    outbox.status = "PENDING";
    outbox.orderNo = "202609retry1";
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\"}";
    outbox.attempts = 0;
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
        .recordOutboxFailure(
            org.mockito.ArgumentMatchers.eq("event-retry-1"),
            anyString(),
            any(LocalDateTime.class));
    verify(mapper, never()).markOutboxPublished(anyString(), any(LocalDateTime.class));
    ArgumentCaptor<CorrelationData> correlationCaptor =
        ArgumentCaptor.forClass(CorrelationData.class);
    verify(rabbitTemplate, times(2))
        .convertAndSend(anyString(), anyString(), any(), any(), correlationCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "event-retry-1", correlationCaptor.getAllValues().get(0).getId());
    org.junit.jupiter.api.Assertions.assertEquals(
        "event-retry-1", correlationCaptor.getAllValues().get(1).getId());
  }

  @Test
  void invalidAttemptSnapshotsNeverParseSendOrWriteFacts() {
    for (Integer attempts : java.util.Arrays.asList(null, -1, Integer.MIN_VALUE)) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
      OrderOutboxPO invalid = new OrderOutboxPO();
      invalid.eventId = "invalid-counter";
      invalid.attempts = attempts;
      invalid.payload = "not-json";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(invalid));
      OrderOutboxDispatcher dispatcher =
          new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment());
      org.mockito.Mockito.clearInvocations(rabbit);
      dispatcher.dispatchPending();
      verify(mapper).findPendingOutbox(50);
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      org.mockito.Mockito.verifyNoInteractions(json, rabbit);
      org.junit.jupiter.api.Assertions.assertEquals(attempts, invalid.attempts);
      org.junit.jupiter.api.Assertions.assertEquals("not-json", invalid.payload);
    }
  }

  @Test
  void invalidSnapshotsDoNotBlockAHealthyCandidateInTheSameBatch() {
    for (Integer attempts : java.util.Arrays.asList(null, -1, Integer.MIN_VALUE)) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO invalid = new OrderOutboxPO();
      invalid.eventId = "invalid-counter";
      invalid.attempts = attempts;
      invalid.payload = "not-json";
      OrderOutboxPO healthy = new OrderOutboxPO();
      healthy.eventId = "valid-maximum";
      healthy.eventType = "ORDER_TIMEOUT";
      healthy.status = "PENDING";
      healthy.orderNo = "202610-valid";
      healthy.attempts = Integer.MAX_VALUE;
      healthy.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\"}";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(invalid, healthy));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                org.junit.jupiter.api.Assertions.assertEquals(healthy.eventId, correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(healthy.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      org.junit.jupiter.api.Assertions.assertEquals(attempts, invalid.attempts);
    }
  }

  @Test
  void candidateDatabaseFailuresContinueWithoutRewritingOrResendingTheCandidate() {
    for (boolean completionFailure : List.of(false, true)) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO first = candidate("first-db");
      OrderOutboxPO second = candidate("second-db");
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(first, second));
      RuntimeException sqlFailure =
          new org.springframework.dao.DataAccessResourceFailureException("candidate SQL failure");
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                if (!completionFailure && first.eventId.equals(correlation.getId()))
                  throw new IllegalStateException("broker failure");
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      if (completionFailure)
        doThrow(sqlFailure)
            .when(mapper)
            .markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
      else
        doThrow(sqlFailure)
            .when(mapper)
            .recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(first.eventId),
                anyString(),
                any(LocalDateTime.class));
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () ->
              new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
                  .dispatchPending());
      verify(mapper).findPendingOutbox(50);
      if (completionFailure)
        verify(mapper)
            .markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
      else
        verify(mapper)
            .recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(first.eventId),
                anyString(),
                any(LocalDateTime.class));
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit, times(2))
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void scanErrorsAndNonDatabaseFailureWriterErrorsKeepTheirPropagation() {
    for (String phase : List.of("scan", "runtime", "fatal")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      Throwable original =
          "scan".equals(phase)
              ? new org.springframework.dao.DataAccessResourceFailureException("scan failed")
              : "runtime".equals(phase)
                  ? new IllegalStateException("failure writer failed")
                  : new AssertionError("fatal failure writer");
      OrderOutboxPO first = candidate("first-error");
      OrderOutboxPO second = candidate("unprocessed");
      if ("scan".equals(phase)) doThrow(original).when(mapper).findPendingOutbox(anyInt());
      else {
        org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
            .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker unavailable"))
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        doThrow(original)
            .when(mapper)
            .recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(first.eventId),
                anyString(),
                any(LocalDateTime.class));
      }
      OrderOutboxDispatcher dispatcher =
          new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment());
      org.mockito.Mockito.clearInvocations(rabbit);
      org.junit.jupiter.api.Assertions.assertSame(
          original,
          org.junit.jupiter.api.Assertions.assertThrows(
              original.getClass(), dispatcher::dispatchPending));
      verify(mapper).findPendingOutbox(50);
      if ("scan".equals(phase)) org.mockito.Mockito.verifyNoInteractions(rabbit);
      else {
        verify(mapper)
            .recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(first.eventId),
                anyString(),
                any(LocalDateTime.class));
        verify(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      }
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }
  }

  @Test
  void nonDatabaseCompletionErrorsStillRecordOneFailureAndContinue() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    OrderOutboxPO first = candidate("first-runtime");
    OrderOutboxPO second = candidate("second-runtime");
    org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(first, second));
    org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
        .thenReturn(1);
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    doThrow(new IllegalStateException("non-database completion error"))
        .when(mapper)
        .markOutboxPublished(
            org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
    new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
        .dispatchPending();
    verify(mapper).findPendingOutbox(50);
    verify(mapper)
        .markOutboxPublished(
            org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
    verify(mapper)
        .recordOutboxFailure(
            org.mockito.ArgumentMatchers.eq(first.eventId), anyString(), any(LocalDateTime.class));
    verify(mapper)
        .markOutboxPublished(
            org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
    org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    verify(rabbit, times(2))
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
  }

  @Test
  void dueSnapshotsUseTheExistingDlxWithoutExpirationAndKeepTheOriginalEnvelope() {
    for (String expiration :
        List.of(
            "2020-01-01T00:00:00Z",
            "2020-01-01T00:00:00+08:00",
            "2020-01-01T00:00:00",
            java.time.OffsetDateTime.now().toString(),
            java.time.OffsetDateTime.MIN.toString())) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO outbox = candidate("due-event");
      outbox.payload = "{\"expireAt\":\"" + expiration + "\",\"traceId\":\"original-trace\"}";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertEquals(
                    com.cloudmall.order.config.OrderMessagingConfiguration.DLX,
                    invocation.getArgument(0));
                Map<String, Object> envelope = invocation.getArgument(2);
                org.junit.jupiter.api.Assertions.assertEquals(
                    outbox.eventId, envelope.get("eventId"));
                org.junit.jupiter.api.Assertions.assertEquals(expiration, envelope.get("expireAt"));
                org.junit.jupiter.api.Assertions.assertEquals(
                    "original-trace", envelope.get("traceId"));
                org.springframework.amqp.core.Message message =
                    new org.springframework.amqp.core.Message(
                        new byte[0], new org.springframework.amqp.core.MessageProperties());
                message.getMessageProperties().setExpiration("999");
                org.springframework.amqp.core.MessagePostProcessor processor =
                    invocation.getArgument(3);
                org.junit.jupiter.api.Assertions.assertNull(
                    processor.postProcessMessage(message).getMessageProperties().getExpiration());
                CorrelationData correlation = invocation.getArgument(4);
                org.junit.jupiter.api.Assertions.assertEquals(outbox.eventId, correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(outbox.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void futureSnapshotsRetainTheOriginalTtlRouteAndTimeText() {
    for (String expiration :
        List.of("2099-01-01T00:00:00Z", "2099-01-01T00:00:00+08:00", "2099-01-01T00:00:00")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO outbox = candidate("future-event");
      outbox.payload = "{\"expireAt\":\"" + expiration + "\"}";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                org.junit.jupiter.api.Assertions.assertEquals(
                    com.cloudmall.order.config.OrderMessagingConfiguration.TIMEOUT_EXCHANGE,
                    invocation.getArgument(0));
                Map<String, Object> envelope = invocation.getArgument(2);
                org.junit.jupiter.api.Assertions.assertEquals(expiration, envelope.get("expireAt"));
                org.springframework.amqp.core.MessagePostProcessor processor =
                    invocation.getArgument(3);
                org.springframework.amqp.core.Message message =
                    new org.springframework.amqp.core.Message(
                        new byte[0], new org.springframework.amqp.core.MessageProperties());
                org.junit.jupiter.api.Assertions.assertTrue(
                    Long.parseLong(
                            processor
                                .postProcessMessage(message)
                                .getMessageProperties()
                                .getExpiration())
                        >= 1);
                CorrelationData correlation = invocation.getArgument(4);
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(outbox.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    }
  }

  @Test
  void futureDurationOverflowIsNotGuessedToBeAnExpiredMessage() {
    OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    OrderOutboxPO outbox = candidate("overflow-future");
    outbox.payload = "{\"expireAt\":\"" + java.time.OffsetDateTime.MAX + "\"}";
    org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment());
    org.mockito.Mockito.clearInvocations(rabbit);
    dispatcher.dispatchPending();
    verify(mapper).findPendingOutbox(50);
    verify(mapper)
        .recordOutboxFailure(
            org.mockito.ArgumentMatchers.eq(outbox.eventId), anyString(), any(LocalDateTime.class));
    org.mockito.Mockito.verifyNoMoreInteractions(mapper);
    org.mockito.Mockito.verifyNoInteractions(rabbit);
  }

  @Test
  void zeroProofRequiresOriginalOrderTypeAndAnInterpretableCounter() {
    for (String proof :
        List.of(
            "order-null",
            "order-other",
            "order-case",
            "order-trailing",
            "type-null",
            "type-other",
            "type-case",
            "type-accent",
            "type-trailing",
            "counter-null",
            "counter-negative",
            "counter-min",
            "counter-zero",
            "counter-one",
            "counter-max")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO first = candidate("first-bound-proof");
      OrderOutboxPO second = candidate("after-bound-proof");
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(first, second));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      org.mockito.Mockito.when(
              mapper.markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class)))
          .thenReturn(0);
      OrderOutboxPO fact = candidate(first.eventId);
      fact.status = "PUBLISHED";
      if (proof.startsWith("order-"))
        fact.orderNo =
            switch (proof) {
              case "order-null" -> null;
              case "order-other" -> "202610-different-order";
              case "order-case" -> first.orderNo.toUpperCase(java.util.Locale.ROOT);
              default -> first.orderNo + " ";
            };
      else if (proof.startsWith("type-"))
        fact.eventType =
            switch (proof) {
              case "type-null" -> null;
              case "type-other" -> "PAY_NOTIFY";
              case "type-case" -> "order_timeout";
              case "type-accent" -> "ORDER_TÍMEOUT";
              default -> "ORDER_TIMEOUT ";
            };
      else
        fact.attempts =
            switch (proof) {
              case "counter-null" -> null;
              case "counter-negative" -> -1;
              case "counter-min" -> Integer.MIN_VALUE;
              case "counter-one" -> 1;
              case "counter-max" -> Integer.MAX_VALUE;
              default -> 0;
            };
      org.mockito.Mockito.when(mapper.findPublicationFacts(first.eventId))
          .thenReturn(List.of(fact));
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      try {
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
            .dispatchPending();
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      long proofFailures =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getThrowableProxy() != null
                          && event
                              .getThrowableProxy()
                              .getClassName()
                              .endsWith("OptimisticLockingFailureException"))
              .count();
      boolean validCounter = List.of("counter-zero", "counter-one", "counter-max").contains(proof);
      org.junit.jupiter.api.Assertions.assertEquals(validCounter ? 0 : 1, proofFailures, proof);
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
      verify(mapper).findPublicationFacts(first.eventId);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit, times(2))
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void zeroCompletionOnlyAcceptsUniqueExactPublishedEvidence() {
    for (String proof : List.of("published", "pending", "other", "missing", "alias", "multiple")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO first = candidate("first-proof");
      OrderOutboxPO second = candidate("second-proof");
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(first, second));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      org.mockito.Mockito.when(
              mapper.markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class)))
          .thenReturn(0);
      OrderOutboxPO fact = new OrderOutboxPO();
      fact.eventType = first.eventType;
      fact.orderNo = first.orderNo;
      fact.attempts = first.attempts;
      fact.eventId =
          "alias".equals(proof) ? first.eventId.toUpperCase(java.util.Locale.ROOT) : first.eventId;
      fact.status =
          "pending".equals(proof) ? "PENDING" : "other".equals(proof) ? "OTHER" : "PUBLISHED";
      org.mockito.Mockito.when(mapper.findPublicationFacts(first.eventId))
          .thenReturn(
              "missing".equals(proof)
                  ? List.of()
                  : "multiple".equals(proof) ? List.of(fact, fact) : List.of(fact));
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      try {
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
            .dispatchPending();
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      long proofFailures =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getThrowableProxy() != null
                          && event
                              .getThrowableProxy()
                              .getClassName()
                              .endsWith("OptimisticLockingFailureException"))
              .count();
      org.junit.jupiter.api.Assertions.assertEquals(
          "published".equals(proof) ? 0 : 1, proofFailures, proof);
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
      verify(mapper).findPublicationFacts(first.eventId);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit, times(2))
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void nonzeroCompletionCountsDoNotReadProofAndDoNotRewriteFailures() {
    for (int affected : List.of(1, -1, 2)) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO first = candidate("first-count");
      OrderOutboxPO second = candidate("second-count");
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(first, second));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      org.mockito.Mockito.when(
              mapper.markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class)))
          .thenReturn(affected);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit, times(2))
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void proofReadErrorsAndInterruptionsDoNotProduceFailureWritesOrExtraSends() {
    for (String phase : List.of("query-error", "before-read", "after-read")) {
      try {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        OrderOutboxPO first = candidate("first-proof-read");
        OrderOutboxPO second = candidate("second-proof-read");
        org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
            .thenReturn(List.of(first, second));
        org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
            .thenReturn(1);
        doAnswer(
                invocation -> {
                  if ("before-read".equals(phase)) Thread.currentThread().interrupt();
                  return 0;
                })
            .when(mapper)
            .markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
        doAnswer(
                invocation -> {
                  if ("query-error".equals(phase))
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                        "proof query failed");
                  OrderOutboxPO fact = new OrderOutboxPO();
                  fact.eventId = first.eventId;
                  fact.status = "PUBLISHED";
                  fact.eventType = first.eventType;
                  fact.orderNo = first.orderNo;
                  fact.attempts = first.attempts;
                  Thread.currentThread().interrupt();
                  return List.of(fact);
                })
            .when(mapper)
            .findPublicationFacts(first.eventId);
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
            .dispatchPending();
        org.junit.jupiter.api.Assertions.assertEquals(
            !"query-error".equals(phase), Thread.currentThread().isInterrupted(), phase);
        verify(mapper).findPendingOutbox(50);
        verify(mapper)
            .markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(first.eventId), any(LocalDateTime.class));
        if (!"before-read".equals(phase)) verify(mapper).findPublicationFacts(first.eventId);
        if ("query-error".equals(phase))
          verify(mapper)
              .markOutboxPublished(
                  org.mockito.ArgumentMatchers.eq(second.eventId), any(LocalDateTime.class));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        verify(rabbit, times("query-error".equals(phase) ? 2 : 1))
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void presentPayloadIdentityMustBeAnExactStringBeforeTtlOrPublish() throws Exception {
    for (String field : List.of("eventId", "eventType", "orderNo")) {
      for (Object wrongValue :
          java.util.Arrays.asList(
              null, 42, false, List.of("wrong"), Map.of("key", "wrong"), "other")) {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        OrderOutboxPO invalid = candidate("42");
        invalid.orderNo = "42";
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("expireAt", "not-a-time");
        payload.put(field, wrongValue);
        invalid.payload = new ObjectMapper().writeValueAsString(payload);
        org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(invalid));
        OrderOutboxDispatcher dispatcher =
            new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment());
        org.mockito.Mockito.clearInvocations(rabbit);
        dispatcher.dispatchPending();
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(mapper).findPendingOutbox(50);
        verify(mapper)
            .recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(invalid.eventId),
                error.capture(),
                any(LocalDateTime.class));
        org.junit.jupiter.api.Assertions.assertTrue(error.getValue().contains("载荷身份不一致"), field);
        org.junit.jupiter.api.Assertions.assertTrue(error.getValue().contains(field), field);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        org.mockito.Mockito.verifyNoInteractions(rabbit);
      }
    }
  }

  @Test
  void matchingOrAbsentPayloadIdentityFieldsKeepLegacyCompatibilityAndOriginalValues()
      throws Exception {
    List<String> fields = List.of("eventId", "eventType", "orderNo");
    for (int presenceMask = 0; presenceMask < 8; presenceMask++) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO healthy = candidate(" 事件😀 ");
      healthy.orderNo = " 202610-original-order ";
      Map<String, String> expectedIdentity =
          Map.of(
              "eventId",
              healthy.eventId,
              "eventType",
              healthy.eventType,
              "orderNo",
              healthy.orderNo);
      Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("expireAt", "2099-01-01T00:00:00+08:00");
      payload.put("occurredAt", "2026-10-09T00:00:00+08:00");
      payload.put("traceId", "original-trace");
      for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
        if ((presenceMask & (1 << fieldIndex)) != 0) {
          String field = fields.get(fieldIndex);
          payload.put(field, expectedIdentity.get(field));
        }
      }
      healthy.payload = new ObjectMapper().writeValueAsString(payload);
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(healthy));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                Map<String, Object> envelope = invocation.getArgument(2);
                org.junit.jupiter.api.Assertions.assertEquals(healthy.eventId, correlation.getId());
                org.junit.jupiter.api.Assertions.assertEquals(
                    healthy.orderNo, envelope.get("orderNo"));
                org.junit.jupiter.api.Assertions.assertEquals(payload, envelope.get("payload"));
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(healthy.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void absentOrBlankIdentitySnapshotsNeverParsePublishOrWrite() {
    for (String field : List.of("eventId", "orderNo")) {
      for (String invalidIdentity : java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u2003")) {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
        OrderOutboxPO invalid = candidate("invalid-identity");
        if ("eventId".equals(field)) invalid.eventId = invalidIdentity;
        else invalid.orderNo = invalidIdentity;
        invalid.payload = "not-json";
        org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(invalid));
        OrderOutboxDispatcher dispatcher =
            new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment());
        org.mockito.Mockito.clearInvocations(rabbit);
        dispatcher.dispatchPending();
        verify(mapper).findPendingOutbox(50);
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        org.mockito.Mockito.verifyNoInteractions(json, rabbit);
        org.junit.jupiter.api.Assertions.assertEquals(
            invalidIdentity, "eventId".equals(field) ? invalid.eventId : invalid.orderNo);
        org.junit.jupiter.api.Assertions.assertEquals("not-json", invalid.payload);
      }
    }
  }

  @Test
  void invalidIdentitiesDoNotBlockFollowingEventsOrNormalizeTheirIdentity() {
    for (String field : List.of("eventId", "orderNo")) {
      for (String invalidIdentity : java.util.Arrays.asList(null, "", " ", "\t\r\n", "\u2003")) {
        OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
        OrderOutboxPO invalid = candidate("invalid-first-identity");
        if ("eventId".equals(field)) invalid.eventId = invalidIdentity;
        else invalid.orderNo = invalidIdentity;
        invalid.payload = "not-json";
        OrderOutboxPO healthy = candidate(" 事件😀 ");
        healthy.orderNo = " 202610-original-order ";
        healthy.attempts = Integer.MAX_VALUE;
        org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
            .thenReturn(List.of(invalid, healthy));
        org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
            .thenReturn(1);
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  Map<String, Object> envelope = invocation.getArgument(2);
                  org.junit.jupiter.api.Assertions.assertEquals(
                      healthy.eventId, correlation.getId());
                  org.junit.jupiter.api.Assertions.assertEquals(
                      healthy.eventId, envelope.get("eventId"));
                  org.junit.jupiter.api.Assertions.assertEquals(
                      healthy.orderNo, envelope.get("orderNo"));
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
            .dispatchPending();
        verify(mapper).findPendingOutbox(50);
        verify(mapper)
            .markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(healthy.eventId), any(LocalDateTime.class));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
        verify(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      }
    }
  }

  @Test
  void nonPendingSnapshotsNeverParsePublishOrWrite() {
    for (String status :
        java.util.Arrays.asList(null, "PUBLISHED", "OTHER", "pending", "PENDING ", "PÉNDING", "")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
      OrderOutboxPO invalid = candidate("invalid-status");
      invalid.status = status;
      invalid.payload = "not-json";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(invalid));
      OrderOutboxDispatcher dispatcher =
          new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment());
      org.mockito.Mockito.clearInvocations(rabbit);
      dispatcher.dispatchPending();
      verify(mapper).findPendingOutbox(50);
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      org.mockito.Mockito.verifyNoInteractions(json, rabbit);
      org.junit.jupiter.api.Assertions.assertEquals(status, invalid.status);
      org.junit.jupiter.api.Assertions.assertEquals("not-json", invalid.payload);
    }
  }

  @Test
  void anInvalidStatusSnapshotDoesNotBlockTheFollowingPendingEvent() {
    for (String status :
        java.util.Arrays.asList(null, "PUBLISHED", "OTHER", "pending", "PENDING ")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO invalid = candidate("invalid-status-first");
      invalid.status = status;
      invalid.payload = "not-json";
      OrderOutboxPO healthy = candidate("valid-status-second");
      healthy.attempts = Integer.MAX_VALUE;
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(invalid, healthy));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                org.junit.jupiter.api.Assertions.assertEquals(healthy.eventId, correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(healthy.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void nonTimeoutTypeSnapshotsNeverParsePublishOrWrite() {
    for (String type :
        java.util.Arrays.asList(
            null, "PAY_NOTIFY", "order_timeout", "ORDER_TIMEOUT ", "ORDER_TÍMEOUT", "")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      ObjectMapper json = org.mockito.Mockito.spy(new ObjectMapper());
      OrderOutboxPO invalid = candidate("invalid-type");
      invalid.eventType = type;
      invalid.payload = "not-json";
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(invalid));
      OrderOutboxDispatcher dispatcher =
          new OrderOutboxDispatcher(mapper, rabbit, json, new MockEnvironment());
      org.mockito.Mockito.clearInvocations(rabbit);
      dispatcher.dispatchPending();
      verify(mapper).findPendingOutbox(50);
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      org.mockito.Mockito.verifyNoInteractions(json, rabbit);
      org.junit.jupiter.api.Assertions.assertEquals(type, invalid.eventType);
      org.junit.jupiter.api.Assertions.assertEquals("not-json", invalid.payload);
    }
  }

  @Test
  void anInvalidTypeSnapshotDoesNotBlockTheFollowingTimeout() {
    for (String type :
        java.util.Arrays.asList(null, "PAY_NOTIFY", "order_timeout", "ORDER_TIMEOUT ")) {
      OrderOutboxMapper mapper = org.mockito.Mockito.mock(OrderOutboxMapper.class);
      RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
      OrderOutboxPO invalid = candidate("invalid-type-first");
      invalid.eventType = type;
      invalid.payload = "not-json";
      OrderOutboxPO healthy = candidate("valid-type-second");
      healthy.attempts = Integer.MAX_VALUE;
      org.mockito.Mockito.when(mapper.findPendingOutbox(anyInt()))
          .thenReturn(List.of(invalid, healthy));
      org.mockito.Mockito.when(mapper.markOutboxPublished(anyString(), any(LocalDateTime.class)))
          .thenReturn(1);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                org.junit.jupiter.api.Assertions.assertEquals(healthy.eventId, correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment())
          .dispatchPending();
      verify(mapper).findPendingOutbox(50);
      verify(mapper)
          .markOutboxPublished(
              org.mockito.ArgumentMatchers.eq(healthy.eventId), any(LocalDateTime.class));
      org.mockito.Mockito.verifyNoMoreInteractions(mapper);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  /** 当前批次合法事件快照，避免将未填计数的PO当作可发布事实。 */
  private static OrderOutboxPO candidate(String eventId) {
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.eventId = eventId;
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.status = "PENDING";
    outbox.orderNo = "202610" + eventId;
    outbox.attempts = 0;
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\"}";
    return outbox;
  }
}
