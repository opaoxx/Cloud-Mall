package com.cloudmall.product.service;

import com.cloudmall.product.mapper.ProductIndexConsumptionMapper;
import com.cloudmall.product.mapper.ProductIndexDeadLetterMapper;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 为已有 MySQL volume 创建商品索引可靠性表。 */
@Component
public class ProductIndexReliabilityTableInitializer {
  private final ProductIndexOutboxMapper outboxMapper;
  private final ProductIndexConsumptionMapper consumptionMapper;
  private final ProductIndexDeadLetterMapper deadLetterMapper;

  public ProductIndexReliabilityTableInitializer(
      ProductIndexOutboxMapper outboxMapper,
      ProductIndexConsumptionMapper consumptionMapper,
      ProductIndexDeadLetterMapper deadLetterMapper) {
    this.outboxMapper = outboxMapper;
    this.consumptionMapper = consumptionMapper;
    this.deadLetterMapper = deadLetterMapper;
  }

  @PostConstruct
  public void ensureTables() {
    outboxMapper.ensureTable();
    consumptionMapper.ensureTable();
    deadLetterMapper.ensureTable();
  }
}
