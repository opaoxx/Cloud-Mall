package com.cloudmall.product.service.impl.index;

import com.cloudmall.product.mapper.ProductIndexConsumptionMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品索引消费状态服务。 */
@Service
public class ProductIndexConsumptionService {
  /** 消费状态及原事件版本的持久化入口。 */
  private final ProductIndexConsumptionMapper mapper;

  /** 同步记录投递事件消费确认的Mapper。 */
  private final ProductIndexOutboxMapper outboxMapper;

  /** 注入消费结果与原outbox确认所需的Mapper。 */
  public ProductIndexConsumptionService(
      ProductIndexConsumptionMapper mapper, ProductIndexOutboxMapper outboxMapper) {
    this.mapper = mapper;
    this.outboxMapper = outboxMapper;
  }

  /** 在同一事务中记录消费结果，并确认已应用或已过期的事件。 */
  @Transactional
  public void record(
      String eventId,
      long productId,
      long eventVersion,
      long appliedVersion,
      String status,
      String error) {
    // 1. 保存原事件与实际应用版本的消费结果。
    mapper.record(
        eventId, productId, eventVersion, appliedVersion, status, error, OffsetDateTime.now());
    // 2. 仅已应用或已过期的终态确认对应outbox。
    if ("APPLIED".equals(status) || "STALE".equals(status)) {
      outboxMapper.markAcknowledged(eventId);
    }
  }

  /** 查询原事件是否已经形成APPLIED或STALE消费终态。 */
  public boolean isTerminal(String eventId) {
    String status = mapper.findStatus(eventId);
    return "APPLIED".equals(status) || "STALE".equals(status);
  }
}
