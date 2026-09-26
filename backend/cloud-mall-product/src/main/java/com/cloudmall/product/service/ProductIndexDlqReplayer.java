package com.cloudmall.product.service;

import com.cloudmall.product.config.ProductMessagingConfiguration;
import com.cloudmall.product.mapper.ProductIndexDeadLetterMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 核对原事务事件后，以数据库租约和 publisher confirm 重放 JSON 死信。 */
@Component
public class ProductIndexDlqReplayer {
  private static final Logger log = LoggerFactory.getLogger(ProductIndexDlqReplayer.class);
  private final ProductIndexDeadLetterMapper mapper;
  private final ProductIndexOutboxMapper outboxMapper;
  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper objectMapper;
  private final String owner = "product-dlq-" + UUID.randomUUID();

  public ProductIndexDlqReplayer(ProductIndexDeadLetterMapper mapper,
      ProductIndexOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate,
      ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.outboxMapper = outboxMapper;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
  }

  /** 每轮只处理有界数量；不可核验的载荷保持隔离。 */
  @Scheduled(fixedDelayString = "${cloudmall.product.index.dlq-replay-ms:5000}")
  public void replay() {
    OffsetDateTime now = OffsetDateTime.now();
    for (ProductIndexDeadLetterMapper.DeadLetterPayload payload :
        mapper.claimRetryPayloads(owner, 20, now, now.plusSeconds(30))) {
      try {
        JsonNode event = objectMapper.readTree(payload.body());
        if (event == null || !event.isObject() || !event.path("productId").canConvertToLong()
            || event.path("productId").asLong() <= 0
            || !event.path("productVersion").canConvertToLong()
            || event.path("eventId").asText().isBlank()) {
          mapper.markUnverifiable(payload.quarantineId(), owner);
          continue;
        }
        String eventId = event.path("eventId").asText();
        String original = outboxMapper.findEnvelopeJson(eventId);
        if (original == null || !event.equals(objectMapper.readTree(original))) {
          mapper.markUnverifiable(payload.quarantineId(), owner);
          continue;
        }
        CorrelationData correlationData = new CorrelationData(eventId);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(eventId);
        rabbitTemplate.send(ProductMessagingConfiguration.PRODUCT_EXCHANGE,
            ProductMessagingConfiguration.PRODUCT_ROUTING_KEY,
            new Message(payload.body(), properties), correlationData);
        if (!correlationData.getFuture().get(5, TimeUnit.SECONDS).isAck()
            || correlationData.getReturnedMessage() != null) {
          retry(payload);
          continue;
        }
        if (mapper.markReplayed(payload.quarantineId(), owner) != 1)
          throw new IllegalStateException("商品索引死信重放状态未落库");
      } catch (Exception failure) {
        log.warn("商品索引死信重放失败，quarantineId={}", payload.quarantineId(), failure);
        retry(payload);
      }
    }
  }

  private void retry(ProductIndexDeadLetterMapper.DeadLetterPayload payload) {
    long backoffSeconds = Math.min(300, 1L << Math.min(8, payload.attempts()));
    mapper.markRetry(payload.quarantineId(), owner,
        OffsetDateTime.now().plusSeconds(backoffSeconds));
  }
}
