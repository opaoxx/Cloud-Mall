package com.cloudmall.stock.service.impl.seckill;

import com.cloudmall.stock.domain.vo.SeckillReservationFactVO;
import com.cloudmall.stock.domain.vo.SeckillReservationObservedVO;
import com.cloudmall.stock.domain.vo.SeckillReservationOutboxVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationQueryService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 只读投影原受理和outbox事实，不替调用方补证或执行恢复。 */
@Service("seckillReservationQueryService")
public class SeckillReservationQueryServiceImpl implements SeckillReservationQueryService {
  /** 读取原受理与outbox行的持久层。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建内部事实读取实现，沿用原Bean身份。 */
  public SeckillReservationQueryServiceImpl(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 保留原字段存在性、原JDBC值及受理/outbox的读取顺序。 */
  @Override
  public SeckillReservationFactVO find(String orderNo) {
    // 1. 缺失受理时只返回两个字段，不查询outbox。
    Map<String, Object> reservation = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (reservation == null) return SeckillReservationFactVO.notFound(orderNo);
    // 2. 在第二次查询前捕获原字段，保留显式null而不强转或补默认值。
    Object activityId = reservation.get("activity_id");
    Object skuId = reservation.get("sku_id");
    Object userId = reservation.get("user_id");
    Object idempotencyKey = reservation.get("idempotency_key");
    Object requestHash = reservation.get("request_hash");
    Object reservationStatus = reservation.get("status");
    Map<String, Object> outbox = stockSqlMapper.findSeckillOutboxByOrderNo(orderNo);
    // 3. 原实现此时再次读status；不把两次点读升级为快照或改写字符串"null"。
    String status = String.valueOf(reservation.get("status"));
    String externalStatus =
        "REJECTED".equals(status)
            ? "REJECTED"
            : outbox == null ? "UNKNOWN" : String.valueOf(outbox.get("status"));
    if (outbox != null) {
      return new SeckillReservationOutboxVO(
          orderNo,
          externalStatus,
          activityId,
          skuId,
          userId,
          idempotencyKey,
          requestHash,
          reservationStatus,
          outbox.get("event_id"));
    }
    return new SeckillReservationObservedVO(
        orderNo,
        externalStatus,
        activityId,
        skuId,
        userId,
        idempotencyKey,
        requestHash,
        reservationStatus);
  }
}
