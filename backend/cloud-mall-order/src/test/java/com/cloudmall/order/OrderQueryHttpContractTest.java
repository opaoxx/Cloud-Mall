package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.OrderQueryService;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实MVC路由/金额格式，以及实际Spring事务拦截器下的认证和提交失败顺序。 */
class OrderQueryHttpContractTest {
  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @Test
  void originalRouteDefaultsAndExplicitUserAreBoundWithoutDuplicateInheritedMapping()
      throws Exception {
    var service = mock(OrderQueryService.class);
    when(service.list(7L, null, 1, 20, null, null))
        .thenReturn(new PageResult<>(List.of(), 1, 20, 0));
    AuthContext.set(7L, "USER");
    mvc(service)
        .perform(get("/api/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.pageSize").value(20));
    verify(service).list(7L, null, 1, 20, null, null);
    when(service.list(7L, "PAID", 2, 3, "start", "end"))
        .thenReturn(new PageResult<>(List.of(), 2, 3, 0));
    mvc(service)
        .perform(
            get("/api/orders")
                .param("status", "PAID")
                .param("page", "2")
                .param("pageSize", "3")
                .param("startTime", "start")
                .param("endTime", "end"))
        .andExpect(status().isOk());
    verify(service).list(7L, "PAID", 2, 3, "start", "end");
    assertFalse(
        java.util.Arrays.stream(OrderController.class.getMethods())
            .anyMatch(method -> method.getName().equals("list")));
  }

  @Test
  void missingIdentityIsCheckedAfterTransactionBeginAndBeforeWindowParsing() throws Exception {
    var mapper = mock(OrderSqlMapper.class);
    var transactions = new RecordingTransactions();
    mvc(proxied(mapper, transactions))
        .perform(get("/api/orders").param("startTime", "not-an-instant"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("请先登录"));
    assertEquals(1, transactions.begins);
    assertEquals(1, transactions.rollbacks);
    assertEquals(0, transactions.commits);
    verifyNoInteractions(mapper);
  }

  @Test
  void summaryPreservesMoneyStringsNullTimeAddressTextAndEmptyDetails() throws Exception {
    var mapper = mock(OrderSqlMapper.class);
    when(mapper.countOrders(anyString(), eq(7L), any(), any(), isNull())).thenReturn(1L);
    Map<String, Object> row = new HashMap<>();
    row.put("order_no", "202608summary");
    row.put("user_id", 7L);
    row.put("status", "PAID");
    row.put("total_amount", new BigDecimal("12.30"));
    row.put("pay_amount", new BigDecimal("10.00"));
    row.put("address_snapshot", null);
    row.put("created_at", Timestamp.valueOf("2026-08-15 12:00:00.123"));
    row.put("expire_at", null);
    when(mapper.findOrderPage(anyString(), eq(7L), any(), any(), isNull(), eq(0L), eq(1)))
        .thenReturn(List.of(row));
    var transactions = new RecordingTransactions();
    AuthContext.set(7L, "USER");
    var response =
        mvc(proxied(mapper, transactions))
            .perform(
                get("/api/orders")
                    .param("startTime", "2026-08-01T00:00:00+08:00")
                    .param("endTime", "2026-09-01T00:00:00+08:00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].totalAmount").value("12.30"))
            .andExpect(jsonPath("$.data.items[0].payAmount").value("10.00"))
            .andExpect(jsonPath("$.data.items[0].addressSnapshot").value("null"))
            .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-08-15T12:00:00.123+08:00"))
            .andExpect(jsonPath("$.data.items[0].items").isEmpty())
            .andReturn()
            .getResponse();
    var item =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getContentAsString())
            .path("data")
            .path("items")
            .get(0);
    assertTrue(item.has("expireAt"));
    assertTrue(item.get("expireAt").isNull());
    assertEquals(1, transactions.commits);
    assertEquals(0, transactions.rollbacks);
  }

  @Test
  void commitFailureCannotBeWrappedAsSuccessfulEmptyPage() throws Exception {
    var mapper = mock(OrderSqlMapper.class);
    var transactions = new RecordingTransactions();
    transactions.failCommit = true;
    AuthContext.set(7L, "USER");
    mvc(proxied(mapper, transactions))
        .perform(
            get("/api/orders")
                .param("startTime", "2026-12-31T23:59:59.999000001+08:00")
                .param("endTime", "2027-01-01T00:00:00+08:00"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
    assertEquals(1, transactions.begins);
    assertEquals(1, transactions.commits);
    verifyNoInteractions(mapper);
  }

  private static OrderQueryService proxied(
      OrderSqlMapper mapper, RecordingTransactions transactions) {
    var factory = new ProxyFactory(new OrderQueryServiceImpl(mapper));
    factory.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (OrderQueryService) factory.getProxy();
  }

  private static MockMvc mvc(OrderQueryService service) {
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    return MockMvcBuilders.standaloneSetup(new OrderQueryController(service), new OrderController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
        .build();
  }

  /** 记录真实事务拦截器的调用；数据库隔离另由MySQL IT验证。 */
  static class RecordingTransactions extends AbstractPlatformTransactionManager {
    /** 开始次数。 */
    private int begins;

    /** 提交尝试次数。 */
    private int commits;

    /** 回滚次数。 */
    private int rollbacks;

    /** 当前用例是否模拟提交异常。 */
    private boolean failCommit;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertTrue(definition.isReadOnly());
      assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getIsolationLevel());
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
      if (failCommit) throw new TransactionSystemException("TEST_COMMIT_FAILED");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
