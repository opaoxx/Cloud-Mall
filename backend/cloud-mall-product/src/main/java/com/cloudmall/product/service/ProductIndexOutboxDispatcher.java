package com.cloudmall.product.service;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexOutbox;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** 以稳定 eventId 投递商品索引 outbox，并在 confirm 未知时退避重试。 */
@Component
public class ProductIndexOutboxDispatcher {
  private final ProductIndexOutboxMapper mapper;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final String owner = "product-index-" + UUID.randomUUID();
  /** 是否允许投递索引 outbox，缺省保持现有自动投递。 */
  @Value("${cloudmall.product.index.dispatch-enabled:true}")
  private boolean dispatchEnabled;

  public ProductIndexOutboxDispatcher(
      ProductIndexOutboxMapper mapper, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
  }

  @Scheduled(fixedDelayString = "${cloudmall.product.index.outbox-dispatch-ms:1000}")
  public void dispatch() {
    if (!dispatchEnabled) return;
    OffsetDateTime now = OffsetDateTime.now();
    List<ProductIndexOutbox> events = mapper.claimDue(owner, 50, now, now.plusSeconds(30));
    for (ProductIndexOutbox event : events) {
      CorrelationData correlationData = new CorrelationData(event.eventId());
      try {
        rabbitTemplate.convertAndSend(
            ProductMessagingConfiguration.PRODUCT_EXCHANGE,
            ProductMessagingConfiguration.PRODUCT_ROUTING_KEY,
            objectMapper.readTree(event.envelopeJson()),
            message -> {
              message.getMessageProperties().setContentType("application/json");
              message.getMessageProperties().setMessageId(event.eventId());
              return message;
            },
            correlationData);
        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
          mapper.markRetry(event.id(), owner, confirm.getReason(), nextAttempt(event.attempts()));
          continue;
        }
        if (correlationData.getReturnedMessage() != null) {
          mapper.markRetry(event.id(), owner, "RabbitMQ returned message", nextAttempt(event.attempts()));
          continue;
        }
        mapper.markPublished(event.id(), owner, now, now.plusSeconds(60), nextAttempt(event.attempts()));
      } catch (Exception exception) {
        mapper.markRetry(event.id(), owner, exception.getMessage(), nextAttempt(event.attempts()));
      }
    }
  }

  private OffsetDateTime nextAttempt(int attempts) {
    long seconds = Math.min(300, 1L << Math.min(8, attempts));
    return OffsetDateTime.now().plus(Duration.ofSeconds(seconds));
  }
}
