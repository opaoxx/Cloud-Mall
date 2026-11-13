package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.service.impl.outbox.OrderOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

/** Outbox配置通过真实派发seam验证，不读取或镜像构造器私有字段。 */
class OrderOutboxConfigurationTest {
  @Test
  void nonpositiveSettingsFailBeforeChangingTheRabbitTemplateOrDoingWork() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String property :
        List.of(
            "cloudmall.order.outbox.batch-size",
            "cloudmall.order.outbox.confirm-timeout-ms",
            "cloudmall.order.outbox.retry-delay-seconds")) {
      String minimum =
          property.endsWith("batch-size")
              ? String.valueOf(Integer.MIN_VALUE)
              : String.valueOf(Long.MIN_VALUE);
      for (String value : List.of("0", "-1", minimum)) {
        checks.add(
            () -> {
              OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
              RabbitTemplate rabbit = mock(RabbitTemplate.class);
              MockEnvironment environment = new MockEnvironment().withProperty(property, value);
              IllegalArgumentException failure =
                  assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          new OrderOutboxDispatcher(
                              mapper, rabbit, new ObjectMapper(), environment));
              assertTrue(failure.getMessage().contains(property));
              assertTrue(failure.getMessage().contains(value));
              verifyNoInteractions(mapper, rabbit);
            });
      }
    }
    assertAll(checks);
  }

  @Test
  void absentSettingsKeepTheOriginalScanConfirmationAndRetryDefaults() throws Exception {
    verifyDispatchSettings(new MockEnvironment(), 50, 5000L, 10L);
  }

  @Test
  void representableLongRetryIntervalIsNotArbitrarilyCapped() throws Exception {
    verifyDispatchSettings(
        new MockEnvironment()
            .withProperty("cloudmall.order.outbox.retry-delay-seconds", "3000000000"),
        50,
        5000L,
        3000000000L);
  }

  @Test
  void positiveCustomAndMinimumSettingsAreHonoredByTheDispatchFlow() throws Exception {
    verifyDispatchSettings(
        new MockEnvironment()
            .withProperty("cloudmall.order.outbox.batch-size", "3")
            .withProperty("cloudmall.order.outbox.confirm-timeout-ms", "23")
            .withProperty("cloudmall.order.outbox.retry-delay-seconds", "7"),
        3,
        23L,
        7L);
    verifyDispatchSettings(
        new MockEnvironment()
            .withProperty("cloudmall.order.outbox.batch-size", "1")
            .withProperty("cloudmall.order.outbox.confirm-timeout-ms", "1")
            .withProperty("cloudmall.order.outbox.retry-delay-seconds", "1"),
        1,
        1L,
        1L);
  }

  /** 注入确认NACK，只替换broker等待；验证实际scan参数、等待参数和传入退避时间的时间边界。 */
  private static void verifyDispatchSettings(
      MockEnvironment environment, int batchSize, long timeoutMillis, long retrySeconds)
      throws Exception {
    OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.eventId = "configuration-event";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = "202610-configuration";
    outbox.status = "PENDING";
    outbox.attempts = 0;
    outbox.payload = "{\"expireAt\":\"2099-01-01T00:00:00+08:00\"}";
    when(mapper.findPendingOutbox(anyInt())).thenReturn(List.of(outbox));
    when(mapper.recordOutboxFailure(anyString(), anyString(), any(LocalDateTime.class)))
        .thenReturn(1);
    @SuppressWarnings("unchecked")
    SettableListenableFuture<CorrelationData.Confirm> confirmation =
        mock(SettableListenableFuture.class);
    when(confirmation.get(timeoutMillis, TimeUnit.MILLISECONDS))
        .thenReturn(new CorrelationData.Confirm(false, "controlled-nack"));
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              ReflectionTestUtils.setField(correlation, "future", confirmation);
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), environment);
    LocalDateTime before = LocalDateTime.now(ZoneOffset.ofHours(8));
    dispatcher.dispatchPending();
    LocalDateTime after = LocalDateTime.now(ZoneOffset.ofHours(8));
    ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(mapper).findPendingOutbox(batchSize);
    verify(mapper)
        .recordOutboxFailure(eq(outbox.eventId), eq("controlled-nack"), retryAt.capture());
    assertFalse(retryAt.getValue().isBefore(before.plusSeconds(retrySeconds)));
    assertFalse(retryAt.getValue().isAfter(after.plusSeconds(retrySeconds)));
    verify(confirmation).get(timeoutMillis, TimeUnit.MILLISECONDS);
    verifyNoMoreInteractions(mapper, confirmation);
    verify(rabbit).setMandatory(true);
    verify(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    verifyNoMoreInteractions(rabbit);
  }
}
