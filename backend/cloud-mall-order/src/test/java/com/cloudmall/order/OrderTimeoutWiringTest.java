package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.config.OrderTimeoutMessageListener;
import com.cloudmall.order.controller.OrderCreationController;
import com.cloudmall.order.controller.OrderDetailQueryController;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.controller.OrderSeckillAdmissionController;
import com.cloudmall.order.controller.OrderStateController;
import com.cloudmall.order.service.OrderTimeoutService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** 监听适配保留原消息身份/异常，HTTP角色已不再继承业务实现。 */
class OrderTimeoutWiringTest {
  @Test
  void listenerDelegatesOriginalMessageAndNullWithoutWrappingFailures() {
    var service = mock(OrderTimeoutService.class);
    var listener = new OrderTimeoutMessageListener(service);
    byte[] payload = {(byte) 0xC3, (byte) 0x28};
    var message = new Message(payload, new MessageProperties());
    listener.consumeTimeout(message);
    listener.consumeTimeout(null);
    verify(service).closeFromMessage(same(message));
    verify(service).closeFromMessage(null);
    assertArrayEquals(payload, message.getBody());
    var failure = new IllegalStateException("commit result unknown");
    doThrow(failure).when(service).closeFromMessage(message);
    assertSame(
        failure, assertThrows(IllegalStateException.class, () -> listener.consumeTimeout(message)));
    var fatal = new AssertionError("original error");
    doThrow(fatal).when(service).closeFromMessage(message);
    assertSame(fatal, assertThrows(AssertionError.class, () -> listener.consumeTimeout(message)));
  }

  @Test
  void productionHttpControllersAndTestFacadeHaveNoMixedServiceSuperclass() {
    for (Class<?> type :
        List.of(
            OrderCreationController.class,
            OrderQueryController.class,
            OrderDetailQueryController.class,
            OrderStateController.class,
            OrderSeckillAdmissionController.class,
            OrderTestService.class)) {
      assertEquals(Object.class, type.getSuperclass(), type.getName());
    }
  }
}
