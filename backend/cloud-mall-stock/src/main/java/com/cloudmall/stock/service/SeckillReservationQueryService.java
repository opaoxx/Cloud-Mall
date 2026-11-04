package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.vo.SeckillReservationFactVO;

/** 订单死信恢复使用的内部秒杀受理事实查询契约。 */
public interface SeckillReservationQueryService {
  /** 沿原两次读取返回事实视图，不增加事务、校验或恢复写入。 */
  SeckillReservationFactVO find(String orderNo);
}
