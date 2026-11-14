package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccCancelConfiguration;
import com.cloudmall.pay.config.PayTccCancelTableInitializer;
import com.cloudmall.pay.config.PayTccEntryConfiguration;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortService;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortStore;
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelScheduler;
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelService;
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelStore;
import com.cloudmall.pay.service.impl.cancellation.PayTccCommittedCancelService;
import com.cloudmall.pay.service.impl.cancellation.PayTccCommittedCancelStore;
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
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 实际扫描取消分组与原条件配置；SQL、事务管理器和Entry依赖为替身，不运行后台任务。 */
class PayTccCancellationWiringTest {
  @Test
  void scanPreservesConditionalBeansTransactionsAndOriginalSchedulerRegistration() {
    for (int scenario = 0; scenario < 3; scenario++) {
      boolean enabled = scenario > 0;
      long interval = scenario == 2 ? 7000L : 5000L;
      try (var fixture = new Fixture(enabled, interval, true)) {
        // 1. 永久取消表默认初始化，业务组件仍受原开关及配置依赖控制。
        fixture.context.refresh();
        verify(fixture.mockOf(PayTccCancelMapper.class)).ensureTable();
        verifyNoMoreInteractions(fixture.dependencies.values().toArray());
        clearInvocations(fixture.dependencies.values().toArray());
        Set<String> actual = new HashSet<>();
        for (String name : fixture.context.getBeanDefinitionNames()) {
          if (AopUtils.getTargetClass(fixture.context.getBean(name))
              .getPackageName()
              .equals("com.cloudmall.pay.service.impl.cancellation")) actual.add(name);
        }
        assertEquals(
            enabled
                ? Set.of(
                    "payTccCancelStore",
                    "payTccCommittedCancelStore",
                    "payTccAbortStore",
                    "payTccCancelService",
                    "payTccCommittedCancelService",
                    "payTccAbortService",
                    "payTccCancelScheduler")
                : Set.of("payTccCancelStore", "payTccCommittedCancelStore"),
            actual);
        assertEquals(enabled, fixture.context.containsBean("payTccCancelConfiguration"));

        // 2. 七个独立事务和四个暂停事务保持，两个Abort证明写阶段仍为RR。
        assertTransactions(
            fixture,
            PayTccCancelStore.class,
            Set.of("claimOrRead", "claimRecovery", "stage", "finish"),
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.ISOLATION_DEFAULT);
        assertTransactions(
            fixture,
            PayTccCommittedCancelStore.class,
            Set.of("request"),
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            TransactionDefinition.ISOLATION_DEFAULT);
        if (enabled) {
          assertTransactions(
              fixture,
              PayTccAbortStore.class,
              Set.of("preparedProof", "requestRollback"),
              TransactionDefinition.PROPAGATION_REQUIRES_NEW,
              TransactionDefinition.ISOLATION_REPEATABLE_READ);
          for (Class<?> type :
              List.of(
                  PayTccCancelService.class,
                  PayTccCommittedCancelService.class,
                  PayTccAbortService.class)) {
            assertTransactions(
                fixture,
                type,
                type == PayTccCancelService.class
                    ? Set.of("request", "reconcile")
                    : type == PayTccAbortService.class
                        ? Set.of("requestRollback")
                        : Set.of("request"),
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
                TransactionDefinition.ISOLATION_DEFAULT);
          }
        }

        // 3. 原5000/7000ms任务只登记；两个既有XID门先于业务依赖且保留caller。
        var tasks =
            fixture.context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        assertEquals(enabled ? 1 : 0, tasks.size());
        if (enabled) {
          var scheduler = fixture.context.getBean(PayTccCancelScheduler.class);
          assertFalse(AopUtils.isAopProxy(scheduler));
          var task = assertInstanceOf(FixedDelayTask.class, tasks.iterator().next().getTask());
          assertEquals(interval, task.getInterval());
          assertEquals(interval, task.getInitialDelay());
          var runnable = assertInstanceOf(ScheduledMethodRunnable.class, task.getRunnable());
          assertSame(scheduler, runnable.getTarget());
          assertEquals("recoverDue", runnable.getMethod().getName());
          String original = RootContext.unbind();
          RootContext.bind("cancellation-wiring-caller");
          try {
            var abort = fixture.context.getBean(PayTccAbortService.class);
            var committed = fixture.context.getBean(PayTccCommittedCancelService.class);
            var abortFailure = assertThrows(BizException.class, () -> abort.requestRollback(null));
            assertEquals("PAY_TCC_CONTEXT_INVALID", abortFailure.getCode());
            assertEquals(409, abortFailure.getHttpStatus());
            var committedFailure = assertThrows(BizException.class, () -> committed.request(null));
            assertEquals("PAY_TCC_DELIVERY_CONFLICT", committedFailure.getCode());
            assertEquals(409, committedFailure.getHttpStatus());
            assertEquals("cancellation-wiring-caller", RootContext.getXID());
          } finally {
            RootContext.unbind();
            if (original != null) RootContext.bind(original);
          }
        }
        verifyNoInteractions(fixture.dependencies.values().toArray());
      }
    }
  }

  @Test
  void enabledCancellationWithoutEntryStillFailsAtOriginalConfiguration() {
    try (var fixture = new Fixture(true, 5000L, false)) {
      var failure = assertThrows(BeanCreationException.class, fixture.context::refresh);
      assertTrue(failure.getMessage().contains("PayTccEntryConfiguration"));
      verifyNoInteractions(fixture.mockOf(OrderClient.class), fixture.mockOf(UserTccClient.class));
    }
  }

  /** 对实际扫描Bean核验代理及全部public实例方法，不把注解检查当作实库事务证明。 */
  private void assertTransactions(
      Fixture fixture, Class<?> type, Set<String> expected, int propagation, int isolation) {
    assertTrue(AopUtils.isAopProxy(fixture.context.getBean(type)));
    var attributes = new AnnotationTransactionAttributeSource();
    Set<String> actual = new HashSet<>();
    for (var method : type.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers()))
        continue;
      var attribute = attributes.getTransactionAttribute(method, type);
      if (type == PayTccCancelStore.class && method.getName().equals("read")) {
        assertNull(attribute);
      } else {
        assertNotNull(attribute, method.toString());
        assertEquals(propagation, attribute.getPropagationBehavior());
        assertEquals(isolation, attribute.getIsolationLevel());
        assertFalse(attribute.isReadOnly());
        actual.add(method.getName());
      }
    }
    assertEquals(expected, actual);
  }

  /** 专用Profile防止其它完整应用测试误扫描测试基础设施。 */
  @Configuration
  @Profile("pay-tcc-cancellation-wiring-test")
  @ComponentScan(basePackageClasses = PayTccCancelStore.class)
  @EnableTransactionManagement
  @EnableScheduling
  static class ScannedInfrastructure {}

  /** 只替换外部依赖，保留原取消配置、永久表初始化器和组件扫描。 */
  private static final class Fixture implements AutoCloseable {
    /** 待显式刷新的测试上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 需要确认无业务副作用的Mapper、Feign、Evidence和Entry替身。 */
    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private Fixture(boolean enabled, long interval, boolean entryAvailable) {
      context.getEnvironment().setActiveProfiles("pay-tcc-cancellation-wiring-test");
      Map<String, Object> flags = new HashMap<>();
      if (enabled) flags.put("cloudmall.pay.tcc-cancel.enabled", true);
      if (interval != 5000L) flags.put("cloudmall.pay.tcc-cancel.interval-ms", interval);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("cancellationFixture", flags));
      for (Class<?> type :
          List.of(
              PayTccCancelMapper.class,
              PayRecordMapper.class,
              PayTccExecutionMapper.class,
              PayTccDispatchMapper.class,
              PayTccReceiptMapper.class,
              PayDebitIntentMapper.class,
              PayTccDeliveryMapper.class,
              PayTccExecutionEvidence.class,
              UserTccClient.class,
              OrderClient.class)) register(type);
      if (entryAvailable) register(PayTccEntryConfiguration.class);
      var transactions = mock(PlatformTransactionManager.class);
      when(transactions.getTransaction(any()))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      context.getBeanFactory().registerSingleton("transactionManager", transactions);
      var scheduler = mock(TaskScheduler.class);
      when(scheduler.getClock()).thenReturn(Clock.systemDefaultZone());
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      context.register(
          ScannedInfrastructure.class,
          PayTccCancelConfiguration.class,
          PayTccCancelTableInitializer.class);
    }

    private void register(Class<?> type) {
      Object dependency = mock(type);
      dependencies.put(type, dependency);
      context.getBeanFactory().registerSingleton(type.getSimpleName(), dependency);
    }

    private <T> T mockOf(Class<T> type) {
      return type.cast(dependencies.get(type));
    }

    /** 关闭上下文及调度注册，不执行后台业务。 */
    @Override
    public void close() {
      context.close();
    }
  }
}
