package com.cloudmall.product.service;

import com.cloudmall.product.mapper.ProductIndexConsumptionMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品索引消费状态服务。 */
@Service
public class ProductIndexConsumptionService {
  private final ProductIndexConsumptionMapper mapper;
  private final ProductIndexOutboxMapper outboxMapper;

  public ProductIndexConsumptionService(ProductIndexConsumptionMapper mapper, ProductIndexOutboxMapper outboxMapper) {
    this.mapper = mapper;
    this.outboxMapper = outboxMapper;
  }

  @Transactional
  public void record(String eventId, long productId, long eventVersion, long appliedVersion, String status, String error) {
    mapper.record(eventId, productId, eventVersion, appliedVersion, status, error, OffsetDateTime.now());
    if ("APPLIED".equals(status) || "STALE".equals(status)) {
      outboxMapper.markAcknowledged(eventId);
    }
  }

  public boolean isTerminal(String eventId) {
    String status = mapper.findStatus(eventId);
    return "APPLIED".equals(status) || "STALE".equals(status);
  }
}
