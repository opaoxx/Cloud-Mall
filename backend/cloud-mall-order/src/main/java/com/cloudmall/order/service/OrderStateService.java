package com.cloudmall.order.service;

import com.cloudmall.order.domain.vo.OrderVO;
import java.util.function.LongSupplier;

/** 订单状态迁移业务契约，保留每个入口独立的事务及锁语义。 */
public interface OrderStateService {
  /** 取消原用户的待支付订单，原详情读取后再取身份，成功CAS才写回滚动作。 */
  OrderVO cancel(String orderNo, LongSupplier userIdSupplier);

  /** 幂等标记原用户订单已支付，成功CAS才写库存确认动作。 */
  OrderVO paid(String orderNo, LongSupplier userIdSupplier);

  /** 将原已支付订单确认为完成，不新增事务、共享锁或库存动作。 */
  OrderVO confirm(String orderNo, LongSupplier userIdSupplier);
}
