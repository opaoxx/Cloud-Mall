package com.cloudmall.stock.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以独立已提交事务将订单号绑定到不可变普通库存请求。 */
@Service
public class StockReservationRequestService {
  /** 预扣请求持久层。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建预扣请求认领服务。 */
  public StockReservationRequestService(StockSqlMapper stockSqlMapper) {
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 先提交 INIT 请求；相同订单号的不同 SKU 快照直接拒绝。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String claim(String orderNo, String scene, String itemsSnapshot, String itemsHash) {
    // 1. 数据库唯一订单号约束阻止不同实例并发认领同一请求。
    stockSqlMapper.insertReservationRequest(
        orderNo, scene, itemsSnapshot, itemsHash, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    // 2. 读取已提交的不可变请求，校验重试载荷完全一致。
    Map<String, Object> request = stockSqlMapper.findReservationRequest(orderNo);
    if (request == null) {
      throw new BizException(ErrorCodes.INTERNAL, "库存预扣请求无法持久化", 500);
    }
    if (!itemsHash.equals(String.valueOf(request.get("items_hash")))
        || !scene.equals(String.valueOf(request.get("scene")))) {
      throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
    }
    // 3. 调用方在库存事务中锁定本行，再决定继续或返回原结果。
    return String.valueOf(request.get("status"));
  }
}
