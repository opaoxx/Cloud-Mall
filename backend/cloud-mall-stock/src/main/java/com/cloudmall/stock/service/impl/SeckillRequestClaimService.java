package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在事务内原子认领全局秒杀请求键，避免并发请求把唯一键异常暴露为 500。 */
@Service
public class SeckillRequestClaimService {
  private final StockSqlMapper stockSqlMapper;

  /** 创建秒杀请求认领服务。 */
  public SeckillRequestClaimService(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 插入首次请求或锁定已存在记录，调用方随后比较不可变 requestHash。 */
  @Transactional
  public Map<String, Object> claim(
      long activityId,
      long skuId,
      long userId,
      String orderNo,
      String idempotencyKey,
      String requestPayload,
      String requestHash,
      OffsetDateTime occurredAt) {
    // 1. 使用全局唯一键原子插入，重复请求不会抛唯一键异常。
    stockSqlMapper.insertSeckillPendingIgnore(
        activityId, skuId, userId, orderNo, idempotencyKey, requestPayload, requestHash, occurredAt);
    // 2. 在同一事务内锁定首次提交的事实行，保证返回稳定订单号和载荷。
    Map<String, Object> row = stockSqlMapper.findSeckillByUserKeyForUpdate(userId, idempotencyKey);
    if (row == null) throw new IllegalStateException("秒杀请求认领记录不存在");
    // 3. 返回数据库事实，由入口比较 hash 并决定重放或返回幂等结果。
    return row;
  }
}
