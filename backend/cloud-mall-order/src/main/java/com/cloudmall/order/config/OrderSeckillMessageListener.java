package com.cloudmall.order.config;

import com.cloudmall.order.service.OrderSeckillCreationService;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 秒杀队列的消息接入适配器，事务提交与失败传播由业务服务代理完成。 */
@Component
public class OrderSeckillMessageListener {
  /** 在原事务内完成幂等认领、订单落库和恢复状态的业务代理。 */
  private final OrderSeckillCreationService creationService;

  /** 注入秒杀落单业务契约，不直接持有Mapper或远端客户端。 */
  public OrderSeckillMessageListener(OrderSeckillCreationService creationService) {
    this.creationService = creationService;
  }

  /** 保持原Map转换与队列绑定，服务正常提交后返回，异常沿原容器重试策略传播。 */
  @RabbitListener(queues = OrderMessagingConfiguration.SECKILL_QUEUE)
  public void consumeSeckill(Map<String, Object> event) {
    creationService.createFromMessage(event);
  }
}
