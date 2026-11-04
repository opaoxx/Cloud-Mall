package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;

/** 普通库存预扣与最终库存动作契约。 */
public interface StockReservationService {
  /** 按原订单与规范SKU快照原子预扣库存。 */
  StockReservationVO reserve(StockReservationDTO request);

  /** 按稳定操作键确认普通或秒杀预扣。 */
  StockActionResultVO confirm(String orderNo, StockActionDTO action);

  /** 按稳定操作键释放普通或秒杀预扣。 */
  StockActionResultVO rollback(String orderNo, StockActionDTO action);

  /** 兼容未携带动作载荷的内部确认调用。 */
  StockActionResultVO confirm(String orderNo);

  /** 兼容未携带动作载荷的内部回滚调用。 */
  StockActionResultVO rollback(String orderNo);
}
