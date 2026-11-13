package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.service.impl.evidence.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 实际扫描迁移后的取证包，防止Bean、代理或默认入口门随地址整理而丢失。 */
class PayTccEvidenceWiringTest {
  /** 原Bean名及类型清单；两个Reader之外均为无事务入口。 */
  private static final Map<String, Class<?>> SERVICES =
      Map.ofEntries(
          Map.entry("payTccReconciliationService", PayTccReconciliationService.class),
          Map.entry("payTccReconciliationReadService", PayTccReconciliationReadService.class),
          Map.entry("payTccDeliveryEvidenceService", PayTccDeliveryEvidenceService.class),
          Map.entry("payTccDeliveryEvidenceReadService", PayTccDeliveryEvidenceReadService.class),
          Map.entry("payTccOrderEvidenceService", PayTccOrderEvidenceService.class),
          Map.entry("payTccStockEvidenceService", PayTccStockEvidenceService.class),
          Map.entry("payTccUserEvidenceService", PayTccUserEvidenceService.class),
          Map.entry("payTccUserAccountEvidenceService", PayTccUserAccountEvidenceService.class),
          Map.entry(
              "payTccUserFrozenOwnershipEvidenceService",
              PayTccUserFrozenOwnershipEvidenceService.class),
          Map.entry(
              "payTccUserLinkedRefundEvidenceService", PayTccUserLinkedRefundEvidenceService.class),
          Map.entry(
              "payTccUserPaymentLedgerEvidenceService",
              PayTccUserPaymentLedgerEvidenceService.class),
          Map.entry(
              "payTccUserTerminalFrozenOwnershipEvidenceService",
              PayTccUserTerminalFrozenOwnershipEvidenceService.class));

  @Test
  void actualScanRetainsBeanNamesAndReadOnlyProxiesWithGatesBeforeAllResources() throws Exception {
    // 1. 不配置开关时所有入口关闭；全部显式开启时仍须先通过原凭证门。
    for (boolean enabled : new boolean[] {false, true}) {
      List<Object> dependencies = new ArrayList<>();
      try (var context = new AnnotationConfigApplicationContext()) {
        context.getEnvironment().setActiveProfiles("pay-tcc-evidence-wiring-test");
        if (enabled) {
          Map<String, Object> flags = new HashMap<>();
          for (String suffix :
              List.of(
                  "reconciliation",
                  "delivery-evidence",
                  "order-evidence",
                  "stock-evidence",
                  "user-evidence",
                  "user-account-evidence",
                  "user-frozen-ownership-evidence",
                  "user-linked-refund-evidence",
                  "user-payment-ledger-evidence",
                  "user-terminal-frozen-ownership-evidence")) {
            flags.put("cloudmall.pay.tcc-" + suffix + ".enabled", true);
          }
          context
              .getEnvironment()
              .getPropertySources()
              .addFirst(new MapPropertySource("evidenceFlags", flags));
        }
        Set<Class<?>> resourceTypes = new HashSet<>();
        for (Class<?> type : SERVICES.values()) {
          for (Class<?> parameter : type.getConstructors()[0].getParameterTypes()) {
            if (!parameter.isPrimitive()
                && parameter != String.class
                && !SERVICES.containsValue(parameter)) resourceTypes.add(parameter);
          }
        }
        resourceTypes.add(PlatformTransactionManager.class);
        for (Class<?> type : resourceTypes) {
          Object dependency = mock(type);
          dependencies.add(dependency);
          context
              .getBeanFactory()
              .registerSingleton(
                  type == PlatformTransactionManager.class
                      ? "transactionManager"
                      : type.getSimpleName(),
                  dependency);
        }
        context.register(ScannedInfrastructure.class);
        context.refresh();

        // 2. 默认@Service名保持，只有两个Reader获得三个REQUIRED/RR/readOnly方法。
        var attributes = new AnnotationTransactionAttributeSource();
        Set<String> transactionalMethods = new HashSet<>();
        Set<String> actualServices = new HashSet<>();
        for (var entry : SERVICES.entrySet()) {
          assertEquals(Set.of(entry.getKey()), context.getBeansOfType(entry.getValue()).keySet());
          Object bean = context.getBean(entry.getKey());
          boolean reader =
              entry.getValue() == PayTccReconciliationReadService.class
                  || entry.getValue() == PayTccDeliveryEvidenceReadService.class;
          assertEquals(reader, AopUtils.isAopProxy(bean));
          for (var method : entry.getValue().getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            var attribute = attributes.getTransactionAttribute(method, entry.getValue());
            if (reader) {
              assertNotNull(attribute);
              assertTrue(attribute.isReadOnly());
              assertEquals(
                  TransactionDefinition.PROPAGATION_REQUIRED, attribute.getPropagationBehavior());
              assertEquals(
                  TransactionDefinition.ISOLATION_REPEATABLE_READ, attribute.getIsolationLevel());
              transactionalMethods.add(entry.getKey() + "." + method.getName());
            } else assertNull(attribute);
          }
          // 3. 实际入口拒绝请求时，不应创建事务、调用数据库或发送Feign请求。
          if (!reader) {
            var inspect =
                entry.getValue().getMethod("inspect", String.class, String.class, String.class);
            var failure =
                assertThrows(
                    InvocationTargetException.class,
                    () -> inspect.invoke(bean, "pay1", "cm18-reconciliation", "wrong-token"));
            var business = assertInstanceOf(BizException.class, failure.getCause());
            assertEquals(enabled ? 403 : 503, business.getHttpStatus());
          }
        }
        for (String name : context.getBeanDefinitionNames()) {
          Class<?> type = AopUtils.getTargetClass(context.getBean(name));
          if (type.getPackageName().equals("com.cloudmall.pay.service.impl.evidence"))
            actualServices.add(name);
        }
        assertEquals(SERVICES.keySet(), actualServices);
        assertEquals(
            Set.of(
                "payTccReconciliationReadService.inspect",
                "payTccReconciliationReadService.capture",
                "payTccDeliveryEvidenceReadService.inspect"),
            transactionalMethods);
        verifyNoInteractions(dependencies.toArray());
      }
    }
  }

  /** 仅扫描真实取证包；使用受限测试Profile，避免混入其它Spring测试上下文。 */
  @Configuration
  @Profile("pay-tcc-evidence-wiring-test")
  @ComponentScan(basePackageClasses = PayTccReconciliationService.class)
  @EnableTransactionManagement
  static class ScannedInfrastructure {}
}
