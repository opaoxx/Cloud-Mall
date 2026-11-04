package com.cloudmall.stock;

import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.SeckillReservationServiceImpl;
import com.cloudmall.stock.service.impl.StockQueryServiceImpl;
import com.cloudmall.stock.service.impl.StockReservationReader;
import com.cloudmall.stock.service.impl.StockReservationServiceImpl;
import com.cloudmall.stock.service.impl.StockReservationSnapshotCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 仅为既有直构造算法测试组装实际职责，不代替Spring代理/MVC/实库边界测试。 */
final class StockTestServices {
  private StockTestServices() {}

  static StockReservationReader reader(StockSqlMapper stocks, ObjectMapper json) {
    return reader(stocks, json, null);
  }

  static StockReservationReader reader(
      StockSqlMapper stocks, ObjectMapper json, SeckillQuotaSettlementService settlement) {
    return new StockReservationReader(
        stocks, json, settlement, new StockReservationSnapshotCodec(json));
  }

  static StockQueryServiceImpl query(
      StringRedisTemplate redis, StockSqlMapper stocks, ObjectMapper json) {
    return new StockQueryServiceImpl(redis, stocks, reader(stocks, json));
  }

  static StockQueryServiceImpl query(
      StringRedisTemplate redis,
      StockSqlMapper stocks,
      ObjectMapper json,
      SeckillQuotaSettlementService settlement) {
    return new StockQueryServiceImpl(redis, stocks, reader(stocks, json, settlement));
  }

  static StockReservationServiceImpl normal(
      StringRedisTemplate redis, StockSqlMapper stocks, ObjectMapper json) {
    return normal(redis, stocks, json, null);
  }

  static StockReservationServiceImpl normal(
      StringRedisTemplate redis,
      StockSqlMapper stocks,
      ObjectMapper json,
      SeckillQuotaSettlementService settlement) {
    return new StockReservationServiceImpl(
        redis,
        stocks,
        null,
        settlement,
        reader(stocks, json, settlement),
        new StockReservationSnapshotCodec(json),
        null,
        false);
  }

  static SeckillReservationServiceImpl seckillService(
      StringRedisTemplate redis, StockSqlMapper stocks, ObjectMapper json) {
    return new SeckillReservationServiceImpl(
        redis,
        stocks,
        json,
        new SeckillAcceptanceService(stocks),
        new SeckillRequestClaimService(stocks));
  }
}
