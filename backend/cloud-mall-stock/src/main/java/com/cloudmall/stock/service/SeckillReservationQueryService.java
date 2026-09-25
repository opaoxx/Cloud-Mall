package com.cloudmall.stock.service;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 提供订单死信恢复使用的秒杀受理事实查询。 */
@Service
public class SeckillReservationQueryService {
  private final StockSqlMapper stockSqlMapper;

  /** 创建秒杀受理事实查询服务。 */
  public SeckillReservationQueryService(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 查询受理、outbox 与不可变请求字段。 */
  public Map<String, Object> find(String orderNo) {
    // 1. 读取库存受理台账，缺失时返回稳定 NOT_FOUND。
    Map<String, Object> reservation = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (reservation == null) return Map.of("orderNo", orderNo, "status", "NOT_FOUND");
    // 2. 读取同订单的 outbox，按库存事实推导可观测投递状态。
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderNo", orderNo);
    result.put("activityId", reservation.get("activity_id"));
    result.put("skuId", reservation.get("sku_id"));
    result.put("userId", reservation.get("user_id"));
    result.put("idempotencyKey", reservation.get("idempotency_key"));
    result.put("requestHash", reservation.get("request_hash"));
    result.put("reservationStatus", reservation.get("status"));
    Map<String, Object> outbox = stockSqlMapper.findSeckillOutboxByOrderNo(orderNo);
    String status = String.valueOf(reservation.get("status"));
    result.put("status", "REJECTED".equals(status) ? "REJECTED"
        : outbox == null ? "UNKNOWN" : String.valueOf(outbox.get("status")));
    if (outbox != null) result.put("eventId", outbox.get("event_id"));
    return result;
  }
}
