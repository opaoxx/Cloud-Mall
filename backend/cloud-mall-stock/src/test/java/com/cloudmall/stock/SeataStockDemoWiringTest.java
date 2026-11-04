package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.config.SeataStockDemoConfiguration;
import com.cloudmall.stock.config.SeataStockXidBindingFilter;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.SeataStockDemoController;
import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.mapper.SeataStockDemoMapper;
import com.cloudmall.stock.service.SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.SeataStockDemoService;
import com.cloudmall.stock.util.StockDependencyGuard;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 验证演练改名后的真实Spring装配、HTTP协议、XID生命周期与业务熔断排除。 */
class SeataStockDemoWiringTest {
  /** 原内部端点，CM18路径是兼容协议。 */
  private static final String PATH = "/api/internal/cm18/seata/demo/branch";

  /** 只用于本地事务探针的XID，不伪装成TC注册证据。 */
  private static final String XID = "127.0.0.1:8091:stock-refactor";

  @AfterEach
  void clearXid() {
    RootContext.unbind();
  }

  @Test
  void defaultDisabledExplicitFalseAndProductionDoNotRegisterDemoBeans() {
    for (String[] settings :
        List.of(new String[] {}, new String[] {"cloudmall.seata.cm18.enabled=false"})) {
      try (var context = context(false, settings)) {
        assertAbsent(context);
      }
    }
    try (var context = context(true, "cloudmall.seata.cm18.enabled=true")) {
      assertAbsent(context);
    }
  }

  @Test
  void enabledBeansKeepNamesAndBranchCompletesBeforeControlledFailure() throws Exception {
    try (var context = context(false, "cloudmall.seata.cm18.enabled=true")) {
      assertInstanceOf(
          SeataStockDemoConfiguration.class, context.getBean("cm18SeataStockConfiguration"));
      assertSame(
          context.getBean(SeataStockDemoService.class),
          context.getBean("cm18SeataStockDemoService"));
      assertSame(
          context.getBean(SeataStockDemoOrchestrator.class),
          context.getBean("cm18SeataStockDemoOrchestrator"));
      assertTrue(AopUtils.isAopProxy(context.getBean(SeataStockDemoService.class)));
      assertFalse(AopUtils.isAopProxy(context.getBean(SeataStockDemoOrchestrator.class)));
      var transactions = context.getBean(TransactionProbe.class);
      var mapper = context.getBean(SeataStockDemoMapper.class);
      when(mapper.insert(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(StockDependencyGuard.mysqlActive());
                assertEquals(XID, RootContext.getXID());
                return 1;
              });
      MockMvc mvc = mvc(context);
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(XID.getBytes(StandardCharsets.UTF_8)),
                  0,
                  12);
      mvc.perform(request("NONE"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value("0"))
          .andExpect(jsonPath("$.data.txId").value("cm18-refactor"))
          .andExpect(jsonPath("$.data.xid").value(digest))
          .andExpect(jsonPath("$.data.status").value("WRITTEN"))
          .andExpect(jsonPath("$.data.length()").value(3));
      assertNull(RootContext.getXID());
      assertEquals(1, transactions.commits);

      mvc.perform(request("STOCK_AFTER_WRITE"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CM18_CONTROLLED_ROLLBACK"));
      assertEquals(2, transactions.commits);
      assertEquals(0, transactions.rollbacks);
      assertNull(RootContext.getXID());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      verify(mapper, times(2)).insert(any());
    }
  }

  @Test
  void localWriteFailureRollsBackWithoutEnteringOrdinaryStockGuard() {
    try (var context = context(false, "cloudmall.seata.cm18.enabled=true")) {
      var mapper = context.getBean(SeataStockDemoMapper.class);
      when(mapper.insert(any())).thenThrow(new IllegalStateException("controlled local failure"));
      SeataStockBranchDTO request = new SeataStockBranchDTO();
      request.setTxId("cm18-failed");
      assertThrows(
          IllegalStateException.class,
          () -> context.getBean(SeataStockDemoService.class).writeBranch(request, XID));
      var transactions = context.getBean(TransactionProbe.class);
      assertEquals(1, transactions.rollbacks);
      assertEquals(0, transactions.commits);
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  @Test
  void filterRejectsMissingHeadersAndControllerRejectsForeignTransactionIds() throws Exception {
    try (var context = context(false, "cloudmall.seata.cm18.enabled=true")) {
      MockMvc mvc = mvc(context);
      mvc.perform(
              post(PATH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"txId\":\"cm18-test\"}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(PATH)
                  .header("X-Internal-Service", "cloud-mall-cm18-test")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"txId\":\"cm18-test\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(request("NONE").content("{\"txId\":\"real-order\"}"))
          // 既有全局处理器将Controller的ResponseStatusException映射为500；改名保持现状。
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
      mvc.perform(request("UNKNOWN"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
      assertNull(RootContext.getXID());
      verifyNoInteractions(context.getBean(SeataStockDemoMapper.class));
    }
  }

  @Test
  void filterPreservesExistingContextAndClearsOnlyItsOwnBindingOnFailure() throws Exception {
    var filter = new SeataStockXidBindingFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
    request.addHeader("X-Internal-Service", "cloud-mall-cm18-test");
    request.addHeader("TX_XID", XID);
    assertThrows(
        IllegalStateException.class,
        () ->
            filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (incoming, outgoing) -> {
                  assertEquals(XID, RootContext.getXID());
                  throw new IllegalStateException("downstream failure");
                }));
    assertNull(RootContext.getXID());
    RootContext.bind(XID);
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (incoming, outgoing) -> assertEquals(XID, RootContext.getXID()));
    assertEquals(XID, RootContext.getXID());
    RootContext.unbind();
    RootContext.bind("different-xid");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (incoming, outgoing) -> fail("conflicting XID must reject"));
    assertEquals(400, response.getStatus());
    assertEquals("different-xid", RootContext.getXID());
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/stock/skus/7"),
        new MockHttpServletResponse(),
        (incoming, outgoing) -> assertEquals("different-xid", RootContext.getXID()));
    assertEquals("different-xid", RootContext.getXID());
  }

  @Test
  void invalidPauseWindowFailsDuringConfiguration() {
    for (String pause : List.of("-1", "30001")) {
      assertThrows(
          org.springframework.beans.BeansException.class,
          () -> {
            try (var ignored =
                context(
                    false,
                    "cloudmall.seata.cm18.enabled=true",
                    "cloudmall.seata.cm18.pause-after-stock-write-ms=" + pause)) {
              fail("invalid pause must fail startup");
            }
          });
    }
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      String failurePoint) {
    return post(PATH)
        .header("X-Internal-Service", "cloud-mall-cm18-test")
        .header("X-CM18-Failure-Point", failurePoint)
        .header("TX_XID", XID)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"txId\":\"cm18-refactor\"}");
  }

  private static MockMvc mvc(AnnotationConfigApplicationContext context) {
    return MockMvcBuilders.standaloneSetup(context.getBean("cm18SeataStockDemoController"))
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilters(context.getBean("cm18SeataXidBindingFilter", SeataStockXidBindingFilter.class))
        .build();
  }

  private static void assertAbsent(AnnotationConfigApplicationContext context) {
    assertTrue(context.getBeansOfType(SeataStockDemoService.class).isEmpty());
    assertTrue(context.getBeansOfType(SeataStockDemoOrchestrator.class).isEmpty());
    assertTrue(context.getBeansOfType(SeataStockDemoController.class).isEmpty());
    assertTrue(context.getBeansOfType(SeataStockXidBindingFilter.class).isEmpty());
    assertFalse(context.containsBean("cm18SeataStockConfiguration"));
    verifyNoInteractions(context.getBean(SeataStockDemoMapper.class));
  }

  private static AnnotationConfigApplicationContext context(
      boolean production, String... properties) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .setActiveProfiles(
            production
                ? new String[] {"stock-seata-refactor-test", "prod"}
                : new String[] {"stock-seata-refactor-test"});
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
    context.register(
        Transactions.class,
        StockDependencyAspect.class,
        SeataStockDemoConfiguration.class,
        SeataStockDemoController.class,
        SeataStockXidBindingFilter.class);
    context.registerBean(
        "cm18SeataStockDemoMapper",
        SeataStockDemoMapper.class,
        () -> mock(SeataStockDemoMapper.class));
    context.registerBean("transactionManager", TransactionProbe.class, TransactionProbe::new);
    try {
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
  }

  /** 专属Profile防止真实Stock Boot扫描时加载测试基础设施。 */
  @Configuration
  @Profile("stock-seata-refactor-test")
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  static class Transactions {}

  /** 真实事务advisor调用的探针，记录本地边界而非模拟TC决议。 */
  static class TransactionProbe extends AbstractPlatformTransactionManager {
    /** 完成的本地提交次数。 */
    private int commits;

    /** 完成的本地回滚次数。 */
    private int rollbacks;

    /** 为每次调用创建本地事务标识。 */
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    /** 验证本地事务开始时没有普通库存熔断上下文。 */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertFalse(StockDependencyGuard.mysqlActive());
    }

    /** 验证提交不进入普通库存资源并记录完成。 */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      assertFalse(StockDependencyGuard.mysqlActive());
      commits++;
    }

    /** 验证回滚不进入普通库存资源并记录完成。 */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      assertFalse(StockDependencyGuard.mysqlActive());
      rollbacks++;
    }
  }
}
