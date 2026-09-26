package com.cloudmall.product.domain.po;

import java.time.OffsetDateTime;

/** 商品索引 outbox 记录。 */
public record ProductIndexOutbox(
    long id,
    String eventId,
    String eventType,
    long productId,
    long productVersion,
    String source,
    String envelopeJson,
    String status,
    int attempts,
    OffsetDateTime nextRetryAt,
    OffsetDateTime lastPublishedAt,
    OffsetDateTime ackDeadlineAt) {}
