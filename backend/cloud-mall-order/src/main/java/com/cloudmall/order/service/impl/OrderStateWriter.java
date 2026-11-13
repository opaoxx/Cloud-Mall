package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.ts;

import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.util.OrderTableRouting;

/** 状态Service与超时消费者共享的原CAS写入，沿调用方事务执行。 */
final class OrderStateWriter {
  /** 禁止实例化无状态的共享条件更新规则。 */
  private OrderStateWriter() {}

  /** 保留原旧状态条件、时间采样以及恰好一行才成功的语义。 */
  static boolean transition(OrderSqlMapper orderSqlMapper, OrderVO order, String targetStatus) {
    return orderSqlMapper.updateStatus(
            OrderTableRouting.tableFromOrderNo(order.orderNo),
            order.orderNo,
            targetStatus,
            order.status,
            ts(now()))
        == 1;
  }
}
