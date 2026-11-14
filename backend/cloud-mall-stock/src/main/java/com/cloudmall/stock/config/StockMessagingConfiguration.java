package com.cloudmall.stock.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为库存服务的 Rabbit 消息提供 JSON 转换器。 */
@Configuration
public class StockMessagingConfiguration {
  /** 创建使用默认构造参数的 Rabbit JSON 消息转换器。 */
  @Bean
  Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}
