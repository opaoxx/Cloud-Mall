package com.cloudmall.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.Map;

@Configuration
public class OrderMessagingConfiguration {
    public static final String TIMEOUT_EXCHANGE = "cloudmall.order.timeout.exchange";
    public static final String TIMEOUT_QUEUE = "cloudmall.order.timeout.ttl";
    public static final String DLX = "cloudmall.order.timeout.dlx.exchange";
    public static final String DLQ = "cloudmall.order.timeout.dlx";
    public static final String TIMEOUT_DLQ = DLQ;
    public static final String SECKILL_EXCHANGE = "cloudmall.seckill.order.exchange";
    public static final String SECKILL_QUEUE = "cloudmall.seckill.order.queue";
    public static final String SECKILL_DLX = "cloudmall.seckill.order.dlx.exchange";
    public static final String SECKILL_DLQ = "cloudmall.seckill.order.dlx";
    @Bean Jackson2JsonMessageConverter rabbitJsonMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean DirectExchange timeoutExchange() { return new DirectExchange(TIMEOUT_EXCHANGE); }
    @Bean DirectExchange timeoutDlx() { return new DirectExchange(DLX); }
    @Bean Queue timeoutTtlQueue() { return new Queue(TIMEOUT_QUEUE, true, false, false, Map.of("x-dead-letter-exchange", DLX)); }
    @Bean Queue timeoutDeadLetterQueue() { return new Queue(DLQ, true); }
    @Bean Binding timeoutBinding() { return BindingBuilder.bind(timeoutTtlQueue()).to(timeoutExchange()).with(""); }
    @Bean Binding deadLetterBinding() { return BindingBuilder.bind(timeoutDeadLetterQueue()).to(timeoutDlx()).with(""); }
    @Bean DirectExchange seckillExchange() { return new DirectExchange(SECKILL_EXCHANGE); }
    @Bean DirectExchange seckillDlx() { return new DirectExchange(SECKILL_DLX); }
    @Bean Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", SECKILL_DLX));
    }
    @Bean Queue seckillDeadLetterQueue() { return new Queue(SECKILL_DLQ, true); }
    @Bean Binding seckillBinding() { return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with(""); }
    @Bean Binding seckillDeadLetterBinding() {
        return BindingBuilder.bind(seckillDeadLetterQueue()).to(seckillDlx()).with("");
    }
}
