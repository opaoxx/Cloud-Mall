package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccCancelTableInitializer;
import com.cloudmall.pay.config.PayTccExecutionTableInitializer;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.execution.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.execution.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.execution.PayTccExecutionStore;
import com.cloudmall.pay.service.impl.execution.PayTccRecoveryScheduler;
import com.cloudmall.pay.service.impl.projection.PayTccRollbackProjectionService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实际子包扫描及原初始化器验证；调度只注册，TC与数据库均为受控替身。 */
class PayTccExecutionWiringTest {
  @Test
  void actualScanKeepsConditionalBeansIndependentTransactionsAndOneBoundedSchedule() {
    // 1. 默认关闭、仅coordinator、同时启用及自定义间隔分别创建独立上下文。
    for (int scenario = 0; scenario < 4; scenario++) {
      boolean coordinatorEnabled = scenario > 0;
      boolean recoveryEnabled = scenario > 1;
      long interval = scenario == 3 ? 7000L : 5000L;
      try (var fixture = new Fixture(coordinatorEnabled, recoveryEnabled, interval)) {
        fixture.context.refresh();
        verify(fixture.mockOf(PayTccCancelMapper.class)).ensureTable();
        if (coordinatorEnabled) {
          verify(fixture.mockOf(PayTccExecutionMapper.class)).ensureTable();
          verify(fixture.mockOf(PayTccExecutionRecoveryMapper.class)).ensureSchema();
        }
        verifyNoMoreInteractions(fixture.dependencies.values().toArray());
        clearInvocations(fixture.dependencies.values().toArray());
        Set<String> expected =
            new HashSet<>(Set.of("payTccExecutionStore", "payTccExecutionRecoveryStore"));
        if (coordinatorEnabled) expected.add("payTccCoordinatorServiceImpl");
        if (recoveryEnabled) expected.add("payTccRecoveryScheduler");
        Set<String> actual = new HashSet<>();
        for (String name : fixture.context.getBeanDefinitionNames()) {
          if (AopUtils.getTargetClass(fixture.context.getBean(name))
              .getPackageName()
              .equals("com.cloudmall.pay.service.impl.execution")) actual.add(name);
        }
        assertEquals(expected, actual);

        // 2. 原十三个短事务仍在两个Store代理上，读取及协调/扫描不增加事务。
        var attributes = new AnnotationTransactionAttributeSource();
        Set<String> transactionMethods = new HashSet<>();
        for (Class<?> type :
            List.of(PayTccExecutionStore.class, PayTccExecutionRecoveryStore.class)) {
          assertTrue(AopUtils.isAopProxy(fixture.context.getBean(type)));
          for (var method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) continue;
            var attribute = attributes.getTransactionAttribute(method, type);
            if (attribute == null) continue;
            assertEquals(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW, attribute.getPropagationBehavior());
            assertFalse(attribute.isReadOnly());
            transactionMethods.add(type.getSimpleName() + "." + method.getName());
          }
        }
        assertEquals(
            Set.of(
                "PayTccExecutionStore.claim",
                "PayTccExecutionStore.claimOwned",
                "PayTccExecutionStore.bindXid",
                "PayTccExecutionStore.bindOwnedXid",
                "PayTccExecutionStore.decide",
                "PayTccExecutionStore.decideOwned",
                "PayTccExecutionStore.observe",
                "PayTccExecutionStore.observeSnapshot",
                "PayTccExecutionStore.observeDecidedSnapshot",
                "PayTccExecutionStore.verifyDecisionSnapshot",
                "PayTccExecutionRecoveryStore.claimRecovery",
                "PayTccExecutionRecoveryStore.claimRollbackProjection",
                "PayTccExecutionRecoveryStore.finishRecovery"),
            transactionMethods);

        // 3. 只注册原扫描任务，两个间隔均解析原键；替身TaskScheduler不执行Runnable。
        var scheduled =
            fixture.context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        assertEquals(recoveryEnabled ? 1 : 0, scheduled.size());
        if (recoveryEnabled) {
          var scheduler = fixture.context.getBean(PayTccRecoveryScheduler.class);
          assertFalse(AopUtils.isAopProxy(scheduler));
          var task = assertInstanceOf(FixedDelayTask.class, scheduled.iterator().next().getTask());
          assertEquals(interval, task.getInterval());
          assertEquals(interval, task.getInitialDelay());
          var runnable = assertInstanceOf(ScheduledMethodRunnable.class, task.getRunnable());
          assertSame(scheduler, runnable.getTarget());
          assertEquals("recoverDue", runnable.getMethod().getName());
          assertEquals(20, ReflectionTestUtils.getField(scheduler, "batchSize"));
          assertEquals(60, ReflectionTestUtils.getField(scheduler, "leaseSeconds"));
          assertEquals(10_000_000_000L, ReflectionTestUtils.getField(scheduler, "budgetNanos"));
        }
        if (coordinatorEnabled) {
          var coordinator = fixture.context.getBean(PayTccCoordinatorServiceImpl.class);
          assertFalse(AopUtils.isAopProxy(coordinator));
          String originalXid = RootContext.unbind();
          RootContext.bind("execution-wiring-caller");
          try {
            assertEquals(
                "PAY_TCC_CONTEXT_INVALID",
                assertThrows(BizException.class, () -> coordinator.execute(null)).getCode());
            assertEquals(
                "PAY_TCC_CONTEXT_INVALID",
                assertThrows(BizException.class, () -> coordinator.recover("pay-1")).getCode());
            if (recoveryEnabled)
              assertThrows(
                  IllegalStateException.class,
                  () -> fixture.context.getBean(PayTccRecoveryScheduler.class).recoverDue());
            assertEquals("execution-wiring-caller", RootContext.getXID());
          } finally {
            RootContext.unbind();
            if (originalXid != null) RootContext.bind(originalXid);
          }
        }
        verifyNoInteractions(fixture.dependencies.values().toArray());
      }
    }
  }

  @Test
  void recoveryWithoutCoordinatorStillFailsAtOriginalInitializerDependency() {
    try (var fixture = new Fixture(false, true, 5000L)) {
      var failure = assertThrows(BeanCreationException.class, fixture.context::refresh);
      assertTrue(failure.getMessage().contains("payTccExecutionTableInitializer"));
      verifyNoInteractions(
          fixture.mockOf(PayTccTransactionClient.class),
          fixture.mockOf(PayTccBridgeService.class),
          fixture.mockOf(PayTccReceiptService.class),
          fixture.mockOf(PayTccRollbackProjectionService.class));
    }
  }

  /** 专用Profile防止本测试的扫描与调度配置混入其它Spring应用测试。 */
  @Configuration
  @Profile("pay-tcc-execution-wiring-test")
  @ComponentScan(basePackageClasses = PayTccExecutionStore.class)
  @EnableTransactionManagement
  @EnableScheduling
  static class ScannedInfrastructure {}

  /** 实际生产类型与原初始化器的装配夹具，不加载官方TC客户端工厂。 */
  private static final class Fixture implements AutoCloseable {
    /** 待测试显式refresh的Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 业务依赖与事务管理器替身，便于前置拒绝的零交互验证。 */
    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private Fixture(boolean coordinatorEnabled, boolean recoveryEnabled, long interval) {
      context.getEnvironment().setActiveProfiles("pay-tcc-execution-wiring-test");
      Map<String, Object> flags = new HashMap<>();
      if (coordinatorEnabled) flags.put("cloudmall.pay.tcc-coordinator.enabled", true);
      if (recoveryEnabled) flags.put("cloudmall.pay.tcc-recovery.enabled", true);
      if (interval != 5000L) flags.put("cloudmall.pay.tcc-recovery.interval-ms", interval);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("executionFixture", flags));
      for (Class<?> type :
          List.of(
              PayRecordMapper.class,
              PayTccCancelMapper.class,
              PayTccDispatchMapper.class,
              PayTccReceiptMapper.class,
              PayTccExecutionMapper.class,
              PayTccExecutionRecoveryMapper.class,
              PayTccBridgeService.class,
              PayTccReceiptService.class,
              PayTccTransactionClient.class,
              PayTccExecutionEvidence.class,
              PayTccRollbackProjectionService.class,
              PlatformTransactionManager.class)) {
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
      context.getBeanFactory().registerSingleton("metrics", PayProtocolMetrics.noop());
      var taskScheduler = mock(TaskScheduler.class);
      when(taskScheduler.getClock()).thenReturn(Clock.systemDefaultZone());
      context.getBeanFactory().registerSingleton("taskScheduler", taskScheduler);
      context.register(
          ScannedInfrastructure.class,
          PayTccCancelTableInitializer.class,
          PayTccExecutionTableInitializer.class);
    }

    private <T> T mockOf(Class<T> type) {
      return type.cast(dependencies.get(type));
    }

    /** 关闭调度注册和上下文，不启动或保留后台业务任务。 */
    @Override
    public void close() {
      context.close();
    }
  }
}
