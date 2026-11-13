package com.cloudmall.order.service;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;

/** 秒杀请求的限流、地址快照和Stock受理编排；受理不代表订单已落库。 */
public interface OrderSeckillAdmissionService {
  /**
   * 按原幂等键提交一次受理请求，不新建事务或重试写调用。
   *
   * @param idempotencyKey 未归一化的原请求键
   * @param request 原活动、SKU和收货地址请求
   * @param userId Controller在参数业务校验前取得的已认证用户
   * @return Stock原响应信封，保留业务码、消息、数据及requestId
   */
  ApiResponse<?> submit(String idempotencyKey, SeckillOrderDTO request, long userId);
}
