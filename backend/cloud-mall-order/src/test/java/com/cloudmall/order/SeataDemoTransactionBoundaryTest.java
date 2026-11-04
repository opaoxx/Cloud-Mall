package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.SeataOrderDemoBranchService;
import com.cloudmall.order.service.SeataTransactionDemoOrchestrator;
import com.cloudmall.order.service.impl.seata.SeataOrderDemoBranchServiceImpl;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoOrchestratorImpl;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoServiceImpl;
import io.seata.spring.annotation.GlobalTransactional;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 代理外确认顺序与原事务元数据；受控代理不是实际TC提交证据。 */
class SeataDemoTransactionBoundaryTest {
  @Test
  void originalGlobalAndLocalTransactionMetadataRemainOnImplementations() throws Exception {
    var global =
        SeataTransactionDemoOrchestratorImpl.class
            .getMethod("execute", SeataTransactionDemoDTO.class)
            .getAnnotation(GlobalTransactional.class);
    assertNotNull(global);
    assertEquals("cm18-seata-demo", global.name());
    assertArrayEquals(new Class<?>[] {Exception.class}, global.rollbackFor());
    var local =
        SeataOrderDemoBranchServiceImpl.class
            .getMethod("write", String.class, String.class)
            .getAnnotation(Transactional.class);
    assertNotNull(local);
    assertEquals(Propagation.REQUIRED, local.propagation());
    assertTrue(
        SeataOrderDemoBranchService.class.isAssignableFrom(SeataOrderDemoBranchServiceImpl.class));
    assertNull(SeataTransactionDemoServiceImpl.class.getAnnotation(Transactional.class));
    assertNull(
        SeataTransactionDemoServiceImpl.class
            .getMethod("execute", SeataTransactionDemoDTO.class)
            .getAnnotation(Transactional.class));
  }

  @Test
  void confirmationWaitsForProxyReturnAndIsSkippedIfCompletionFails() {
    var mapper = mock(SeataOrderDemoMapper.class);
    var calls = new ArrayList<String>();
    var request = new SeataTransactionDemoDTO();
    request.txId = "cm18-proxy";
    request.failurePoint = "NONE";
    SeataTransactionDemoOrchestrator target =
        input -> {
          calls.add("target-result");
          return new SeataTransactionDemoVO(input.txId, "hash", "COMMITTED");
        };
    var factory = new ProxyFactory(target);
    factory.addAdvice(
        (MethodInterceptor)
            invocation -> {
              Object result = invocation.proceed();
              calls.add("proxy-completed");
              return result;
            });
    when(mapper.markCommitted(request.txId))
        .thenAnswer(
            invocation -> {
              assertEquals(List.of("target-result", "proxy-completed"), calls);
              calls.add("confirmation");
              return 1;
            });
    var service =
        new SeataTransactionDemoServiceImpl(
            (SeataTransactionDemoOrchestrator) factory.getProxy(), mapper);
    assertEquals("COMMITTED", service.execute(request).status);
    assertEquals(List.of("target-result", "proxy-completed", "confirmation"), calls);

    clearInvocations(mapper);
    calls.clear();
    var failedFactory = new ProxyFactory(target);
    failedFactory.addAdvice(
        (MethodInterceptor)
            invocation -> {
              invocation.proceed();
              throw new SeataDemoUnknownTransactionException(
                  "controlled-proxy-completion-failure", null);
            });
    var failedService =
        new SeataTransactionDemoServiceImpl(
            (SeataTransactionDemoOrchestrator) failedFactory.getProxy(), mapper);
    assertThrows(SeataDemoUnknownTransactionException.class, () -> failedService.execute(request));
    assertEquals(List.of("target-result"), calls);
    verify(mapper, never()).markCommitted(any());
    verify(mapper, never()).selectById(any(java.io.Serializable.class));
  }
}
