package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一本地事务中完成秒杀受理状态和订单 outbox 写入。 */
@Service
public class SeckillAcceptanceService {
  /** 受理 CAS 与可靠消息 outbox 的 SQL 入口。 */
  private final StockSqlMapper stockSqlMapper;

  /** 实际 Spring 环境注入的秒杀结算事实 Mapper；单参构造保留给既有单测。 */
  @Autowired private SeckillQuotaSettlementMapper settlementMapper;

  /** 创建受理事务服务，结算 Mapper 由 Spring 必选注入。 */
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
    // 1. 受理事实和 outbox 已在当前事务中写入后，用 INSERT SELECT 固定不可变维度。
    // 2. 不允许结算行缺失时向调用方返回 accepted，避免后续动作无法归属。
    if (settlementMapper == null) {
      throw new IllegalStateException("秒杀结算事实 Mapper 未注入");
    }
    int inserted = settlementMapper.insertOpenIfAbsent(orderNo, occurredAt.toLocalDateTime());
    if (inserted != 1 && settlementMapper.findSettlement(orderNo) == null) {
      throw new IllegalStateException("秒杀受理结算事实不存在");
    }
  }
}
