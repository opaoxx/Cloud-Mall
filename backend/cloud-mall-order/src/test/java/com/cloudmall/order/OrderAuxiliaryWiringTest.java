package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.service.impl.outbox.OrderOutboxDispatcher;
import com.cloudmall.order.service.impl.reservation.OrderReservationIntentService;
import com.cloudmall.order.service.impl.reservation.OrderReservationRecovery;
import com.cloudmall.order.service.impl.seckill.SeckillMessageRecovery;
import com.cloudmall.order.service.impl.stockaction.OrderStockActionRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实际扫描四个职责子包并核验原Bean/事务/调度/监听注册；不执行后台任务或连接broker。 */
class OrderAuxiliaryWiringTest {
  @Test
  void packageScanRetainsFiveBeanNamesFourSchedulesTransactionAndManualDlq() {
    var stock = mock(StockClient.class);
    var orders = mock(OrderSqlMapper.class);
    var intents = mock(OrderReservationIntentMapper.class);
    var actions = mock(OrderStockActionMapper.class);
    var outbox = mock(OrderOutboxMapper.class);
    var recovery = mock(SeckillMessageRecoveryMapper.class);
    var rabbit = mock(RabbitTemplate.class);
    var transactions = mock(PlatformTransactionManager.class);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("order-auxiliary-wiring-test");
      context.register(ScannedInfrastructure.class, OrderMessagingConfiguration.class);
      context.registerBean(StockClient.class, () -> stock);
      context.registerBean(OrderSqlMapper.class, () -> orders);
      context.registerBean(OrderReservationIntentMapper.class, () -> intents);
      context.registerBean(OrderStockActionMapper.class, () -> actions);
      context.registerBean(OrderOutboxMapper.class, () -> outbox);
      context.registerBean(SeckillMessageRecoveryMapper.class, () -> recovery);
      context.registerBean(RabbitTemplate.class, () -> rabbit);
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));
      context.registerBean(
          "transactionManager", PlatformTransactionManager.class, () -> transactions);
      context.registerBean("taskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class));
      context.refresh();
      Map<Class<?>, String> names =
          Map.of(
              OrderReservationIntentService.class, "orderReservationIntentService",
              OrderReservationRecovery.class, "orderReservationRecovery",
              OrderStockActionRecovery.class, "orderStockActionRecovery",
              OrderOutboxDispatcher.class, "orderOutboxDispatcher",
              SeckillMessageRecovery.class, "seckillMessageRecovery");
      for (var entry : names.entrySet()) {
        assertEquals(Set.of(entry.getValue()), context.getBeansOfType(entry.getKey()).keySet());
        assertEquals(
            entry.getKey() == OrderReservationIntentService.class,
            AopUtils.isAopProxy(context.getBean(entry.getValue())));
      }
      var attributes = new AnnotationTransactionAttributeSource();
      var transactionalMethods = new java.util.HashSet<String>();
      for (var method : OrderReservationIntentService.class.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())) continue;
        var attribute =
            attributes.getTransactionAttribute(method, OrderReservationIntentService.class);
        if (method.getName().equals("findByUserAndKey")) assertNull(attribute);
        else {
          assertNotNull(attribute);
          assertEquals(
              TransactionDefinition.PROPAGATION_REQUIRES_NEW, attribute.getPropagationBehavior());
          transactionalMethods.add(method.getName());
        }
      }
      assertEquals(
          Set.of(
              "createIntent",
              "markReserved",
              "markReleased",
              "recordFailure",
              "markReserveUnknown"),
          transactionalMethods);
      Map<String, Long> scheduledIntervals = new HashMap<>();
      for (var scheduled :
          context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()) {
        assertTrue(scheduled.getTask() instanceof FixedDelayTask);
        var task = (FixedDelayTask) scheduled.getTask();
        var runnable = (ScheduledMethodRunnable) task.getRunnable();
        assertSame(
            context.getBean(names.get(runnable.getTarget().getClass())), runnable.getTarget());
        assertNull(scheduledIntervals.put(runnable.getMethod().getName(), task.getInterval()));
      }
      assertEquals(
          Map.of(
              "dispatchPending",
              1000L,
              "recoverStaleReservations",
              30000L,
              "recoverDue",
              5000L,
              "replayDue",
              5000L),
          scheduledIntervals);
      var containers =
          context.getBean(RabbitListenerEndpointRegistry.class).getListenerContainers();
      assertEquals(1, containers.size());
      var container = (AbstractMessageListenerContainer) containers.iterator().next();
      assertArrayEquals(
          new String[] {OrderMessagingConfiguration.SECKILL_DLQ}, container.getQueueNames());
      assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
      assertFalse(container.isRunning());
      verify(rabbit, times(2)).setMandatory(true);
      verifyNoInteractions(stock, orders, intents, actions, outbox, recovery, transactions);
    }
  }

  /** 通过生产子包的默认扫描发现组件；调度器为不执行Runnable的替身。 */
  @Configuration
  @Profile("order-auxiliary-wiring-test")
  @ComponentScan(
      basePackages = {
        "com.cloudmall.order.service.impl.reservation",
        "com.cloudmall.order.service.impl.stockaction",
        "com.cloudmall.order.service.impl.outbox",
        "com.cloudmall.order.service.impl.seckill"
      })
  @EnableTransactionManagement
  @EnableScheduling
  @EnableRabbit
  static class ScannedInfrastructure {
    /** 只阻止测试上下文连接broker，不改变原MANUAL/队列注册配置。 */
    @Bean
    static BeanPostProcessor stopTestContainers() {
      return new BeanPostProcessor() {
        /** 原factory装配完成后关闭本测试的自动启动。 */
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof SimpleRabbitListenerContainerFactory factory)
            factory.setAutoStartup(false);
          return bean;
        }
      };
    }
  }
}
