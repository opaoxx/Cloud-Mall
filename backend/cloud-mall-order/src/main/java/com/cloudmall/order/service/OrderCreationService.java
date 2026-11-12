package com.cloudmall.order.service;

import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.vo.OrderVO;
import java.util.function.LongSupplier;

/** 普通订单创建与原幂等重放契约。 */
public interface OrderCreationService {
  /**
   * 按原顺序创建订单或返回已提交订单，结果未知时保留原意图供恢复。
   *
   * @param key 原用户范围内的幂等键
   * @param request 原购买与地址请求
   * @param userIdSupplier 在事务开始并取得共享监视器后读取用户身份
   * @return 原订单详情，不携带HTTP响应包装
   */
  OrderVO create(String key, CreateOrderDTO request, LongSupplier userIdSupplier);
}
