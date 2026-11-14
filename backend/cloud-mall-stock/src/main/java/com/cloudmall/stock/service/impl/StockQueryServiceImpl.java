package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockQueryService;
import com.cloudmall.stock.util.StockDependencyGuard;
import java.util.Map;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 库存数量与订单预扣状态的只读入口。 */
@Service
public class StockQueryServiceImpl implements StockQueryService {
  /** 普通库存可用量的版本投影Key前缀。 */
  private static final String AVAILABLE_KEY_PREFIX = "stock:available:";

  /** 只读Redis数量投影。 */
  private final StringRedisTemplate redis;

  /** 投影缺失或不可用时读取持久库存数量。 */
  private final StockSqlMapper stockSqlMapper;

  /** 与写入重放共用的库存证明读取器。 */
  private final StockReservationReader reservationReader;

  /** 创建数量查询和持久状态查询入口。 */
  public StockQueryServiceImpl(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      StockReservationReader reservationReader) {
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
    this.reservationReader = reservationReader;
  }

  /** 在既有只读依赖保护内读取预扣事实，不新增数据库事务。 */
  @Override
  public StockReservationVO reservation(String orderNo) {
    return reservationReader.reservation(orderNo);
  }

  /** 读取SKU可用量，缓存故障时回退持久事实。 */
  @Override
  public StockQuantityVO get(Long skuId) {
    return new StockQuantityVO(skuId, quantity(skuId));
  }

  /** 优先读取Redis版本投影，失败或缺值时只读MySQL事实。 */
  private int quantity(Long skuId) {
    // 1. 仅信任原HASH投影；读取或解析失败时不把异常值作为数量返回。
    String stockKey = AVAILABLE_KEY_PREFIX + skuId;
    try {
      if (redis.type(stockKey) == DataType.HASH) {
        Object projected = redis.opsForHash().get(stockKey, "quantity");
        if (projected != null) return Integer.parseInt(String.valueOf(projected));
      }
    } catch (RuntimeException unavailableProjection) {
      // Redis 只是普通库存投影，读接口仍可回退到 MySQL 事实。
    }
    // 2. 缓存缺证时进入数据库读保护，双依赖故障不得伪造零库存。
    Map<String, Object> durableStock =
        StockDependencyGuard.call(
            StockDependencyGuard.MYSQL_READ, () -> stockSqlMapper.findStock(skuId));
    return durableStock == null ? 0 : ((Number) durableStock.get("available_quantity")).intValue();
  }
}
