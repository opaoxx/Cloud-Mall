package com.cloudmall.order.service;

import org.springframework.amqp.core.Message;

/** 超时消息解析与依据数据库期限执行关单的业务契约。 */
public interface OrderTimeoutService {
  /** 在原事务内读取消息体并解析，保留原字节、异常及legacy兼容边界。 */
  void closeFromMessage(Message message);

  /** 依据原订单详情和数据库期限关单；仅CAS成功时同事务记录库存回滚动作。 */
  void closeExpiredOrder(String orderNo);
}
