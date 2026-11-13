package com.cloudmall.order.config;

import com.cloudmall.order.service.OrderTimeoutService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 原超时DLX队列接入适配器，保留Message原始字节和容器确认/重试策略。 */
@Component
public class OrderTimeoutMessageListener {
  /** 在解析前开启事务、提交后返回的超时业务代理。 */
  private final OrderTimeoutService timeoutService;

  /** 注入超时消费业务契约，不持有Mapper或自行解析消息。 */
  public OrderTimeoutMessageListener(OrderTimeoutService timeoutService) {
    this.timeoutService = timeoutService;
  }

  /** 服务事务完成后才返回容器；异常不捕获，继续原重试和拒绝流程。 */
  @RabbitListener(queues = OrderMessagingConfiguration.TIMEOUT_DLQ)
  public void consumeTimeout(Message message) {
    timeoutService.closeFromMessage(message);
  }
}
