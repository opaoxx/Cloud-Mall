package com.cloudmall.order.service;

import java.util.Map;

/** 秒杀受理消息的本地落单、消费幂等和冲突证据业务契约。 */
public interface OrderSeckillCreationService {
  /**
   * 在原本地事务内核验事件并创建订单，已提交的同身份消息沿原幂等路径返回。
   *
   * @param event 原转换器产生的消息Map，不改变字段类型或重建消息身份
   * @throws IllegalStateException 业务/持久化失败保留cause，交由监听容器重试或死信
   */
  void createFromMessage(Map<String, Object> event);
}
