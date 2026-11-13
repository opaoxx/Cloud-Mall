package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptRecoveryScheduler;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.service.impl.legacy.PayDebitIntentService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
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
import org.springframework.transaction.support.TransactionTemplate;

/** 实际扫描新职责子包，核验原Bean/事务及默认或覆盖的调度间隔，不执行任务。 */
class PayAuxiliaryWiringTest {
  @Test
  void scannedPackagesKeepFiveBeansTransactionBoundariesAndBothRecoverySchedules() {
    for (long interval : new long[] {5000L, 7000L}) {
      var records = mock(PayRecordMapper.class);
      var intents = mock(PayDebitIntentMapper.class);
      var logs = mock(PayCallbackLogMapper.class);
      var users = mock(UserClient.class);
      var orders = mock(OrderClient.class);
      var transactions = mock(PlatformTransactionManager.class);
      try (var context = new AnnotationConfigApplicationContext()) {
        context.getEnvironment().setActiveProfiles("pay-auxiliary-wiring-test");
        if (interval != 5000L)
          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(
                  new MapPropertySource(
                      "recoveryInterval", Map.of("cloudmall.pay.recovery-interval-ms", interval)));
        context.register(ScannedInfrastructure.class);
        var beans = context.getBeanFactory();
        beans.registerSingleton("records", records);
        beans.registerSingleton("intents", intents);
        beans.registerSingleton("logs", logs);
        beans.registerSingleton("users", users);
        beans.registerSingleton("orders", orders);
        beans.registerSingleton("transactionManager", transactions);
        beans.registerSingleton("objectMapper", new ObjectMapper());
        beans.registerSingleton("metrics", PayProtocolMetrics.noop());
        beans.registerSingleton("taskScheduler", mock(TaskScheduler.class));
        context.refresh();
        Map<Class<?>, String> names =
            Map.of(
                PayConsistencyService.class, "payConsistencyService",
                PayDebitIntentService.class, "payDebitIntentService",
                PayLegacyFailureService.class, "payLegacyFailureService",
                PayCallbackReceiptService.class, "payCallbackReceiptService",
                PayCallbackReceiptRecoveryScheduler.class, "payCallbackReceiptRecoveryScheduler");
        var transactionAttributes = new AnnotationTransactionAttributeSource();
        Set<String> transactionalMethods = new HashSet<>();
        for (var entry : names.entrySet()) {
          assertEquals(Set.of(entry.getValue()), context.getBeansOfType(entry.getKey()).keySet());
          boolean expectedProxy =
              Set.of(
                      PayDebitIntentService.class,
                      PayLegacyFailureService.class,
                      PayCallbackReceiptService.class)
                  .contains(entry.getKey());
          assertEquals(expectedProxy, AopUtils.isAopProxy(context.getBean(entry.getValue())));
          for (var method : entry.getKey().getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            var attribute = transactionAttributes.getTransactionAttribute(method, entry.getKey());
            if (expectedProxy) {
              assertNotNull(attribute);
              assertEquals(
                  TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                  attribute.getPropagationBehavior());
              assertFalse(attribute.isReadOnly());
              transactionalMethods.add(entry.getValue() + "." + method.getName());
            } else assertNull(attribute);
          }
        }
        assertEquals(
            Set.of(
                "payDebitIntentService.ensureIntent",
                "payLegacyFailureService.fail",
                "payCallbackReceiptService.claim",
                "payCallbackReceiptService.complete",
                "payCallbackReceiptService.recoverReceived"),
            transactionalMethods);
        var consistency = context.getBean(PayConsistencyService.class);
        for (String name :
            new String[] {"terminalTransactions", "stageTransactions", "retryTransactions"}) {
          var template = (TransactionTemplate) ReflectionTestUtils.getField(consistency, name);
          assertNotNull(template);
          assertSame(transactions, template.getTransactionManager());
          assertEquals(
              TransactionDefinition.PROPAGATION_REQUIRES_NEW, template.getPropagationBehavior());
        }
        Map<String, Long> schedules = new HashMap<>();
        for (var scheduled :
            context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()) {
          assertInstanceOf(FixedDelayTask.class, scheduled.getTask());
          var task = (FixedDelayTask) scheduled.getTask();
          var runnable = (ScheduledMethodRunnable) task.getRunnable();
          String beanName = names.get(runnable.getTarget().getClass());
          assertNotNull(beanName);
          assertSame(context.getBean(beanName), runnable.getTarget());
          assertEquals("recoverDue", runnable.getMethod().getName());
          assertNull(schedules.put(beanName, task.getInterval()));
        }
        assertEquals(
            Map.of(
                "payConsistencyService", interval, "payCallbackReceiptRecoveryScheduler", interval),
            schedules);
        verifyNoInteractions(records, intents, logs, users, orders, transactions);
      }
    }
  }

  /** 通过默认组件扫描发现真实迁移类，调度器仅记录注册而不执行Runnable。 */
  @Configuration
  @Profile("pay-auxiliary-wiring-test")
  @ComponentScan(
      basePackages = {
        "com.cloudmall.pay.service.impl.legacy",
        "com.cloudmall.pay.service.impl.callback"
      })
  @EnableTransactionManagement
  @EnableScheduling
  static class ScannedInfrastructure {}
}
