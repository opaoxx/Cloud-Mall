package com.cloudmall.stock.service;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;

/** 秒杀认领与受理契约。 */
public interface SeckillReservationService {
  /** 使用HTTP层读取的明确身份受理秒杀，保留原幂等键与恢复语义。 */
  ApiResponse<?> seckill(SeckillReservationDTO request, long userId);
}
