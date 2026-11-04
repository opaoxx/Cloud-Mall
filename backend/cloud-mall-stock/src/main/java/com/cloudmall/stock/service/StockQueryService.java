package com.cloudmall.stock.service;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;

/** 库存数量及持久预扣状态查询契约。 */
public interface StockQueryService {
  /** 读取SKU可用量，缓存不可用时沿原路径回退数据库。 */
  ApiResponse<StockQuantityVO> get(Long skuId);

  /** 读取原订单的持久预扣状态，缺证保持UNKNOWN。 */
  ApiResponse<StockReservationVO> reservation(String orderNo);
}
