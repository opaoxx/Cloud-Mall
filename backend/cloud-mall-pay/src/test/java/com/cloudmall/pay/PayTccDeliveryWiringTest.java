package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.pay.config.PayTccDeliveryConfiguration;
import com.cloudmall.pay.config.PayTccExecutionTableInitializer;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryScheduler;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryServiceImpl;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import io.seata.core.context.RootContext;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实际扫描交付子包及原配置，调度只注册、不执行；真实SQL由既有实库矩阵验证。 */
class PayTccDeliveryWiringTest {
  @Test
  void scanKeepsOriginalBeansTransactionsAndOneScheduleWithoutExecutingBusiness() {
    for (int scenario = 0; scenario < 3; scenario++) {
      boolean enabled = scenario > 0;
      long interval = scenario == 2 ? 7000L : 5000L;
      try (var fixture = new Fixture(enabled, interval, true)) {
        // 1. 通过原条件/初始化器装配，保留默认关闭及原Bean名。
        fixture.context.refresh();
        if (enabled) {
          verify(fixture.mockOf(PayTccExecutionMapper.class)).ensureTable();
          verify(fixture.mockOf(PayTccExecutionRecoveryMapper.class)).ensureSchema();
          verify(fixture.mockOf(PayTccDeliveryMapper.class)).ensureTable();
        }
        verifyNoMoreInteractions(fixture.dependencies.values().toArray());
        clearInvocations(fixture.dependencies.values().toArray());
        Set<String> actual = new HashSet<>();
        for (String name : fixture.context.getBeanDefinitionNames()) {
          if (AopUtils.getTargetClass(fixture.context.getBean(name))
              .getPackageName()
              .equals("com.cloudmall.pay.service.impl.delivery")) actual.add(name);
        }
        assertEquals(
            enabled
                ? Set.of(
                    "payTccDeliveryStore", "payTccDeliveryServiceImpl", "payTccDeliveryScheduler")
                : Set.of("payTccDeliveryStore"),
            actual);
        assertEquals(enabled, fixture.context.containsBean("payTccDeliveryConfiguration"));
        assertTrue(AopUtils.isAopProxy(fixture.context.getBean(PayTccDeliveryStore.class)));

        // 2. 四独立写事务和一项RR证明保持；read不加事务。
        var attributes = new AnnotationTransactionAttributeSource();
        Set<String> writes = new HashSet<>();
        for (var method : PayTccDeliveryStore.class.getDeclaredMethods()) {
          if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()))
            continue;
          var attribute = attributes.getTransactionAttribute(method, PayTccDeliveryStore.class);
          if (method.getName().equals("read")) {
            assertNull(attribute);
          } else {
            assertNotNull(attribute);
            if (method.getName().equals("proof")) {
              assertTrue(attribute.isReadOnly());
              assertEquals(
                  TransactionDefinition.PROPAGATION_REQUIRED, attribute.getPropagationBehavior());
              assertEquals(
                  TransactionDefinition.ISOLATION_REPEATABLE_READ, attribute.getIsolationLevel());
            } else {
              assertFalse(attribute.isReadOnly());
              assertEquals(
                  TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                  attribute.getPropagationBehavior());
              writes.add(method.getName());
            }
          }
        }
        assertEquals(Set.of("ensure", "claim", "stage", "finish"), writes);
        assertNotNull(
            attributes.getTransactionAttribute(
                PayTccDeliveryStore.class.getMethod("proof", PayTccDeliveryPO.class),
                PayTccDeliveryStore.class));

        // 3. 原调度键解析默认/覆盖间隔；入口拒绝caller XID且不触及业务依赖。
        var tasks =
            fixture.context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        assertEquals(enabled ? 1 : 0, tasks.size());
        if (enabled) {
          var scheduler = fixture.context.getBean(PayTccDeliveryScheduler.class);
          var service = fixture.context.getBean(PayTccDeliveryServiceImpl.class);
          assertFalse(AopUtils.isAopProxy(scheduler));
          assertFalse(AopUtils.isAopProxy(service));
          var task = assertInstanceOf(FixedDelayTask.class, tasks.iterator().next().getTask());
          assertEquals(interval, task.getInterval());
          assertEquals(interval, task.getInitialDelay());
          var runnable = assertInstanceOf(ScheduledMethodRunnable.class, task.getRunnable());
          assertSame(scheduler, runnable.getTarget());
          assertEquals("recoverDue", runnable.getMethod().getName());
          String original = RootContext.unbind();
          RootContext.bind("delivery-wiring-caller");
          try {
            assertThrows(IllegalStateException.class, () -> service.deliver("pay-1"));
            assertEquals("delivery-wiring-caller", RootContext.getXID());
          } finally {
            RootContext.unbind();
            if (original != null) RootContext.bind(original);
          }
        }
        verifyNoInteractions(fixture.dependencies.values().toArray());
      } catch (NoSuchMethodException missingContract) {
        throw new AssertionError(missingContract);
      }
    }
  }

  @Test
  void enabledDeliveryWithoutCoordinatorStillFailsAtOriginalConfiguration() {
    try (var fixture = new Fixture(true, 5000L, false)) {
      var failure = assertThrows(BeanCreationException.class, fixture.context::refresh);
      assertTrue(failure.getMessage().contains("PayTccCoordinatorService"));
      verifyNoInteractions(
          fixture.mockOf(UserTccClient.class),
          fixture.mockOf(UserClient.class),
          fixture.mockOf(OrderClient.class));
    }
  }

  /** 专用Profile防止扫描配置被其它应用测试发现后启用。 */
  @Configuration
  @Profile("pay-tcc-delivery-wiring-test")
  @ComponentScan(basePackageClasses = PayTccDeliveryStore.class)
  @EnableTransactionManagement
  @EnableScheduling
  static class ScannedInfrastructure {}

  /** 生产配置和组件使用的受控依赖，不加载官方TC工厂。 */
  private static final class Fixture implements AutoCloseable {
    /** 待测试显式刷新的上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 业务/SQL/事务替身，调度替身单独管理其正常注册交互。 */
    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private Fixture(boolean enabled, long interval, boolean coordinatorAvailable) {
      context.getEnvironment().setActiveProfiles("pay-tcc-delivery-wiring-test");
      Map<String, Object> flags = new HashMap<>();
      if (enabled) {
        flags.put("cloudmall.pay.tcc-delivery.enabled", true);
        flags.put("cloudmall.pay.tcc-coordinator.enabled", true);
      }
      if (interval != 5000L) flags.put("cloudmall.pay.tcc-delivery.interval-ms", interval);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("deliveryFixture", flags));
      for (Class<?> type :
          List.of(
              PayTccDeliveryMapper.class,
              PayRecordMapper.class,
              PayTccExecutionMapper.class,
              PayTccExecutionRecoveryMapper.class,
              PayTccDispatchMapper.class,
              PayTccExecutionEvidence.class,
              UserTccClient.class,
              UserClient.class,
              OrderClient.class,
              PlatformTransactionManager.class)) register(type);
      if (coordinatorAvailable) register(PayTccCoordinatorService.class);
      context.getBeanFactory().registerSingleton("metrics", PayProtocolMetrics.noop());
      var scheduler = mock(TaskScheduler.class);
      when(scheduler.getClock()).thenReturn(Clock.systemDefaultZone());
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      context.register(
          ScannedInfrastructure.class,
          PayTccDeliveryConfiguration.class,
          PayTccExecutionTableInitializer.class);
    }

    private void register(Class<?> type) {
      Object dependency = mock(type);
      dependencies.put(type, dependency);
      context
          .getBeanFactory()
          .registerSingleton(
              type == PlatformTransactionManager.class
                  ? "transactionManager"
                  : type.getSimpleName(),
              dependency);
    }

    private <T> T mockOf(Class<T> type) {
      return type.cast(dependencies.get(type));
    }

    /** 关闭上下文及原调度注册，不运行后台业务任务。 */
    @Override
    public void close() {
      context.close();
    }
  }
}
