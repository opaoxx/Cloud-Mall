package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.OrderSeckillMessageListener;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderSeckillCreationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 使用实际AMQP注册器和原转换器验证薄监听器，不访问真实broker。 */
class OrderSeckillMessageListenerTest {
  @Test
  void forwardsOriginalMapAndNullAndDoesNotCatchServiceFailures() {
    var service = mock(OrderSeckillCreationService.class);
    var listener = new OrderSeckillMessageListener(service);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("userId", "0008");
    listener.consumeSeckill(event);
    listener.consumeSeckill(null);
    verify(service).createFromMessage(same(event));
    verify(service).createFromMessage(null);
    var failure = new IllegalStateException("original failure");
    doThrow(failure).when(service).createFromMessage(event);
    assertSame(
        failure, assertThrows(IllegalStateException.class, () -> listener.consumeSeckill(event)));
    var fatal = new AssertionError("original error");
    doThrow(fatal).when(service).createFromMessage(event);
    assertSame(fatal, assertThrows(AssertionError.class, () -> listener.consumeSeckill(event)));
  }

  @Test
  void registryHasExactlyOneSeckillEndpointAndRetainsOriginalMapConversion() throws Exception {
    var service = mock(OrderSeckillCreationService.class);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("order-seckill-listener-test");
      context.register(
          ListenerConfiguration.class,
          OrderMessagingConfiguration.class,
          OrderSeckillMessageListener.class,
          OrderController.class);
      context.registerBean(OrderSeckillCreationService.class, () -> service);
      context.registerBean(OrderSqlMapper.class, () -> mock(OrderSqlMapper.class));
      context.registerBean(OrderStockActionMapper.class, () -> mock(OrderStockActionMapper.class));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));
      context.registerBean(
          "rabbitListenerContainerFactory",
          SimpleRabbitListenerContainerFactory.class,
          () -> {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(context.getBean(ConnectionFactory.class));
            factory.setMessageConverter(context.getBean(Jackson2JsonMessageConverter.class));
            factory.setAutoStartup(false);
            return factory;
          });
      context.refresh();
      var containers =
          context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers();
      assertEquals(2, containers.size());
      var byQueue = new LinkedHashMap<String, AbstractMessageListenerContainer>();
      for (var container : containers) {
        var actual = (AbstractMessageListenerContainer) container;
        assertEquals(1, actual.getQueueNames().length);
        assertNull(byQueue.put(actual.getQueueNames()[0], actual), "duplicate queue listener");
      }
      assertEquals(
          Set.of(
              OrderMessagingConfiguration.SECKILL_QUEUE, OrderMessagingConfiguration.TIMEOUT_DLQ),
          byQueue.keySet());
      var converter = context.getBean(Jackson2JsonMessageConverter.class);
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("userId", "0008");
      event.put("skuId", 7.5);
      event.put("eventId", null);
      var message = converter.toMessage(event, new MessageProperties());
      assertEquals(
          "java.util.LinkedHashMap", message.getMessageProperties().getHeader("__TypeId__"));
      var adapter =
          (ChannelAwareMessageListener)
              byQueue.get(OrderMessagingConfiguration.SECKILL_QUEUE).getMessageListener();
      adapter.onMessage(message, mock(Channel.class));
      ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
      verify(service).createFromMessage(captured.capture());
      assertEquals(event, captured.getValue());
      assertFalse(byQueue.get(OrderMessagingConfiguration.SECKILL_QUEUE).isRunning());
    }
  }

  /** 仅本测试显式注册，正常服务扫描不启用。 */
  @Configuration
  @Profile("order-seckill-listener-test")
  @EnableRabbit
  static class ListenerConfiguration {}
}
