package com.cloudmall.product.service.impl.index;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.domain.po.ProductIndexOutbox;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 以稳定 eventId 投递商品索引 outbox，并在 confirm 未知时退避重试。 */
@Component
public class ProductIndexOutboxDispatcher {
  /** 认领和更新待投递事件的Mapper。 */
  private final ProductIndexOutboxMapper mapper;

  /** 提供publisher confirm及returned结果的消息客户端。 */
  private final RabbitTemplate rabbitTemplate;

  /** 将持久化原事件内容读取为投递载荷的映射器。 */
  private final ObjectMapper objectMapper;

  /** 当前派发实例领取租约的唯一标识。 */
  private final String owner = "product-index-" + UUID.randomUUID();

  /** 是否允许投递索引 outbox，缺省保持现有自动投递。 */
  @Value("${cloudmall.product.index.dispatch-enabled:true}")
  private boolean dispatchEnabled;

  /** 注入持久事件和投递依赖，并为当前 RabbitTemplate 设置原 JSON 转换器。 */
  public ProductIndexOutboxDispatcher(
      ProductIndexOutboxMapper mapper, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
  }

  /** 有界认领原事件，等待 publisher confirm/return 后记录投递或退避，消费回执另行核验。 */
  @Scheduled(fixedDelayString = "${cloudmall.product.index.outbox-dispatch-ms:1000}")
  public void dispatch() {
    // 1. 开关允许时按原批量和租期认领到期事件。
    if (!dispatchEnabled) return;
    OffsetDateTime now = OffsetDateTime.now();
    List<ProductIndexOutbox> events = mapper.claimDue(owner, 50, now, now.plusSeconds(30));
    for (ProductIndexOutbox event : events) {
      // 2. 使用原 eventId 和不可变信封投递，等待有界确认并检查 return。
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
          mapper.markRetry(
              event.id(), owner, "RabbitMQ returned message", nextAttempt(event.attempts()));
          continue;
        }
        // 3. 发布成功后保留消费回执截止时间，未知或失败则沿原策略退避。
        mapper.markPublished(
            event.id(), owner, now, now.plusSeconds(60), nextAttempt(event.attempts()));
      } catch (Exception exception) {
        mapper.markRetry(event.id(), owner, exception.getMessage(), nextAttempt(event.attempts()));
      }
    }
  }

  /** 按原重试次数计算有界退避时间。 */
  private OffsetDateTime nextAttempt(int attempts) {
    long seconds = Math.min(300, 1L << Math.min(8, attempts));
    return OffsetDateTime.now().plus(Duration.ofSeconds(seconds));
  }
}
