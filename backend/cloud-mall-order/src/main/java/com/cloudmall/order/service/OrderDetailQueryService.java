package com.cloudmall.order.service;

import com.cloudmall.order.domain.vo.OrderPaymentStateVO;
import com.cloudmall.order.domain.vo.OrderVO;
import java.util.function.LongSupplier;

/** 单订单详情与支付状态观察，不扩大原接口的事务快照保证。 */
public interface OrderDetailQueryService {
  /**
   * 读取完整详情后核对归属。
   *
   * @param orderNo 原订单号
   * @param userIdSupplier 在原鉴权位置获取用户身份，可能抛出未认证异常
   * @return 原订单详情与明细快照
   */
  OrderVO get(String orderNo, LongSupplier userIdSupplier);

  /**
   * 读取订单、核对身份，再观察库存动作证明和必要的存量库存事实。
   *
   * @param orderNo 原订单号
   * @param userIdSupplier 显式请求头或延迟上下文身份，读取位置与原实现一致
   * @return 五字段支付状态，不含payNo或新增推断事实
   */
  OrderPaymentStateVO paymentState(String orderNo, LongSupplier userIdSupplier);
}
