package com.cloudmall.stock.service.impl.seckill;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在事务内原子认领全局秒杀请求键，避免并发请求把唯一键异常暴露为 500。 */
@Service
public class SeckillRequestClaimService {
  /** 秒杀请求持久化和锁定查询入口。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建秒杀请求认领服务。 */
  public SeckillRequestClaimService(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /**
   * 插入首次请求或锁定已存在记录，精确核对幂等键后供调用方比较不可变 requestHash。
   *
   * @param activityId 秒杀活动主键
   * @param skuId SKU主键
   * @param userId 请求所属用户
   * @param orderNo 首次请求的候选订单号
   * @param idempotencyKey 原始幂等键，不做大小写或空白归一化
   * @param requestPayload 原请求载荷
   * @param requestHash 业务指纹
   * @param occurredAt 首次采样的认领时间
   * @return 与用户和精确幂等键匹配的持久事实
   * @throws BizException 数据库排序规则命中不同幂等键时返回原409冲突
   */
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
        activityId,
        skuId,
        userId,
        orderNo,
        idempotencyKey,
        requestPayload,
        requestHash,
        occurredAt);
    // 2. 在同一事务内锁定首次提交的事实行，保证返回稳定订单号和载荷。
    Map<String, Object> row = stockSqlMapper.findSeckillByUserKeyForUpdate(userId, idempotencyKey);
    if (row == null) throw new IllegalStateException("秒杀请求认领记录不存在");
    // 3. 数据库排序规则命中候选不等于键相同，别名不能借用原订单进入Redis受理。
    if (!Objects.equals(idempotencyKey, row.get("idempotency_key"))) {
      throw new BizException("SECKILL_IDEMPOTENCY_CONFLICT", "秒杀幂等键与已有记录不完全一致", 409);
    }
    // 4. 返回数据库事实，由入口比较 hash 并决定重放或返回幂等结果。
    return row;
  }
}
