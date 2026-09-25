package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一本地事务中完成秒杀受理状态和订单 outbox 写入。 */
@Service
public class SeckillAcceptanceService {
  private final StockSqlMapper stockSqlMapper;

  public SeckillAcceptanceService(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 只有 ACCEPTED 与 outbox 同时提交后，入口才允许返回 accepted。 */
  @Transactional
  public void accept(
      long userId,
      String idempotencyKey,
      String orderNo,
      String eventId,
      String payload,
      String requestHash,
      OffsetDateTime occurredAt) {
    if (stockSqlMapper.acceptSeckillAndInsertOutbox(
            userId, idempotencyKey, orderNo, eventId, payload, requestHash, occurredAt)
        != 1) {
      throw new IllegalStateException("秒杀受理记录状态已变化");
    }
  }
}
