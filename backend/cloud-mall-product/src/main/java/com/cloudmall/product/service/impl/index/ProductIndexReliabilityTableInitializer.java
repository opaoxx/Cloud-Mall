package com.cloudmall.product.service.impl.index;

import com.cloudmall.product.mapper.ProductIndexConsumptionMapper;
import com.cloudmall.product.mapper.ProductIndexDeadLetterMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 为已有 MySQL volume 创建商品索引可靠性表。 */
@Component
public class ProductIndexReliabilityTableInitializer {
  /** 创建索引outbox表的Mapper。 */
  private final ProductIndexOutboxMapper outboxMapper;

  /** 创建索引消费状态表的Mapper。 */
  private final ProductIndexConsumptionMapper consumptionMapper;

  /** 创建死信证据表的Mapper。 */
  private final ProductIndexDeadLetterMapper deadLetterMapper;

  /** 注入原可靠性表初始化依赖。 */
  public ProductIndexReliabilityTableInitializer(
      ProductIndexOutboxMapper outboxMapper,
      ProductIndexConsumptionMapper consumptionMapper,
      ProductIndexDeadLetterMapper deadLetterMapper) {
    this.outboxMapper = outboxMapper;
    this.consumptionMapper = consumptionMapper;
    this.deadLetterMapper = deadLetterMapper;
  }

  /** 应用初始化时按原顺序幂等补齐可靠性表。 */
  @PostConstruct
  public void ensureTables() {
    outboxMapper.ensureTable();
    consumptionMapper.ensureTable();
    deadLetterMapper.ensureTable();
  }
}
