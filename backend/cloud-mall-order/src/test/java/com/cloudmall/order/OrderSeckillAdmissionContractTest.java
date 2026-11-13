package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.config.SeckillSentinelConfiguration;
import com.cloudmall.order.controller.OrderSeckillAdmissionController;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.service.OrderSeckillAdmissionService;
import com.cloudmall.order.service.impl.OrderSeckillAdmissionServiceImpl;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 实际受理入口的HTTP、资源生命周期和事务边界；远端依赖为受控替身。 */
class OrderSeckillAdmissionContractTest {
  /** 原五字段受理载荷的Stock观察点。 */
  private final StockClient stock = mock(StockClient.class);

  /** 原地址查询的观察点。 */
  private final UserClient users = mock(UserClient.class);

  /** 快照与HTTP响应使用的JSON工具。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /** 实际业务实现与Controller，不经过旧混合测试夹具。 */
  private final OrderSeckillAdmissionController controller =
      new OrderSeckillAdmissionController(
          new OrderSeckillAdmissionServiceImpl(stock, users, mapper));

  @BeforeEach
  void isolateRulesAndIdentity() {
    FlowRuleManager.loadRules(List.of());
    ParamFlowRuleManager.loadRules(List.of());
    AuthContext.set(33L, "USER");
  }

  @AfterEach
  void releaseRulesAndIdentity() {
    FlowRuleManager.loadRules(List.of());
    ParamFlowRuleManager.loadRules(List.of());
    AuthContext.clear();
    assertTrue(ContextUtil.getContext() == null || ContextUtil.getContext().getCurEntry() == null);
    ContextUtil.exit();
  }

  @Test
  void originalEnvelopeIncludingNonzeroCodeNullsAndRequestIdIsNeverRewrapped() throws Exception {
    addressAvailable();
    Map<String, Object> accepted = new HashMap<>();
    accepted.put("accepted", true);
    accepted.put("reservationStatus", "ACCEPTED");
    accepted.put("orderNo", "202611original");
    accepted.put("eventId", null);
    accepted.put("futureField", Map.of("amount", "12.30"));
    for (ApiResponse<?> response :
        List.of(
            new ApiResponse<>("0", "原受理消息", accepted),
            new ApiResponse<>("SECKILL_PENDING", "处理中", null),
            new ApiResponse<>(null, null, List.of("opaque")))) {
      response.requestId = "stock-original-id";
      doReturn(response).when(stock).seckill(anyMap());
      AuthContext.set(33L, "USER");
      assertSame(response, controller.seckill("key", request(11L, 22L, 3L)));
      var result =
          mvc(controller)
              .perform(
                  post("/api/seckill/orders")
                      .header("Idempotency-Key", "key")
                      .header("X-User-Id", "33")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(mapper.writeValueAsBytes(request(11L, 22L, 3L))))
              .andExpect(status().isOk())
              .andReturn();
      assertEquals(
          mapper.valueToTree(response),
          mapper.readTree(result.getResponse().getContentAsByteArray()));
    }
    verify(stock, times(6)).seckill(anyMap());
  }

  @Test
  void bindingPrecedesIdentityAndIdentityPrecedesBusinessValidation() throws Exception {
    AuthContext.clear();
    var mvc = mvc(controller);
    mvc.perform(post("/api/seckill/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    mvc.perform(
            post("/api/seckill/orders")
                .header("Idempotency-Key", " ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/seckill/orders")
                .header("Idempotency-Key", "key")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    // 原统一异常处理器把JSON绑定失败归为500；本次结构迁移不更改此行为。
    mvc.perform(
            post("/api/seckill/orders")
                .header("Idempotency-Key", "key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
        .andExpect(status().isInternalServerError());
    assertEquals(
        401,
        assertThrows(BizException.class, () -> controller.seckill(null, null)).getHttpStatus());
    AuthContext.set(33L, "USER");
    for (String key : new String[] {null, "", " ", "😀".repeat(129)}) {
      assertEquals(
          400,
          assertThrows(BizException.class, () -> controller.seckill(key, request(11L, 22L, 3L)))
              .getHttpStatus());
    }
    for (SeckillOrderDTO request :
        new SeckillOrderDTO[] {
          null, request(null, 22L, 3L), request(11L, null, 3L), request(11L, 22L, null)
        }) {
      assertEquals(
          400,
          assertThrows(BizException.class, () -> controller.seckill("key", request))
              .getHttpStatus());
    }
    verifyNoInteractions(users, stock);
  }

  @Test
  void addressUsesFirstNumericMatchAndPreservesTheFiveOriginalFields() throws Exception {
    Map<String, Object> address = new HashMap<>();
    address.put("id", 0);
    address.put("receiver", "原地址");
    address.put("phone", null);
    when(users.addresses())
        .thenReturn(
            new ApiResponse<>(
                "legacy-code",
                "original",
                List.of(Map.of("id", "0", "receiver", "string-id"), address, Map.of("id", 0L))));
    when(stock.seckill(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> sent = invocation.getArgument(0);
              assertEquals(5, sent.size());
              assertEquals(-1L, sent.get("activityId"));
              assertEquals(0L, sent.get("skuId"));
              assertEquals(33L, sent.get("userId"));
              assertEquals(" key with spaces ", sent.get("idempotencyKey"));
              assertEquals(
                  mapper.valueToTree(address),
                  mapper.readTree((String) sent.get("addressSnapshot")));
              return ApiResponse.ok(Map.of("accepted", true));
            });
    controller.seckill(" key with spaces ", request(-1L, 0L, 0L));
    var ordered = inOrder(users, stock);
    ordered.verify(users).addresses();
    ordered.verify(stock).seckill(anyMap());
    ordered.verifyNoMoreInteractions();
  }

  @Test
  void localFlowBlockRecordsTheOriginalMetricBeforeAnyRemoteCall() throws Exception {
    var rule = new FlowRule(SeckillSentinelConfiguration.RESOURCE);
    rule.setGrade(1);
    rule.setCount(0);
    FlowRuleManager.loadRules(List.of(rule));
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    try {
      mvc(controller)
          .perform(
              post("/api/seckill/orders")
                  .header("X-User-Id", "33")
                  .header("Idempotency-Key", "key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(mapper.writeValueAsBytes(request(11L, 22L, 3L))))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.code").value("GATEWAY_RATE_LIMITED"));
      assertEquals(
          1.0,
          registry
              .get("sentinel.block")
              .tags(
                  "service",
                  "order",
                  "resource",
                  SeckillSentinelConfiguration.RESOURCE,
                  "rule_type",
                  "flow",
                  "status",
                  "429")
              .counter()
              .count());
      verifyNoInteractions(users, stock);
    } finally {
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void actualAdmissionProtectsUserAndActivityAtTheirOriginalParameterPositions() {
    addressAvailable();
    doReturn(ApiResponse.ok(Map.of("accepted", true))).when(stock).seckill(anyMap());
    var service = new OrderSeckillAdmissionServiceImpl(stock, users, mapper);
    ParamFlowRuleManager.loadRules(
        List.of(
            new ParamFlowRule(SeckillSentinelConfiguration.RESOURCE)
                .setParamIdx(0)
                .setCount(1)
                .setDurationInSec(60)));
    service.submit("user-a", request(801L, 22L, 3L), 701L);
    assertEquals(
        "GATEWAY_RATE_LIMITED",
        assertThrows(
                BizException.class, () -> service.submit("user-b", request(802L, 22L, 3L), 701L))
            .getCode());
    service.submit("user-c", request(801L, 22L, 3L), 702L);
    ParamFlowRuleManager.loadRules(
        List.of(
            new ParamFlowRule(SeckillSentinelConfiguration.RESOURCE)
                .setParamIdx(1)
                .setCount(1)
                .setDurationInSec(60)));
    service.submit("activity-a", request(901L, 22L, 3L), 703L);
    assertEquals(
        "GATEWAY_RATE_LIMITED",
        assertThrows(
                BizException.class,
                () -> service.submit("activity-b", request(901L, 22L, 3L), 704L))
            .getCode());
    service.submit("activity-c", request(902L, 22L, 3L), 703L);
    verify(users, times(4)).addresses();
    verify(stock, times(4)).seckill(anyMap());
  }

  @Test
  void nestedSentinelEntriesExitOnSuccessAddressFailureAndStockFailure() throws Exception {
    try (var outer = SphU.entry("admission-contract-outer")) {
      when(users.addresses())
          .thenAnswer(
              invocation -> {
                assertEquals(SeckillSentinelConfiguration.RESOURCE, currentResource());
                return ApiResponse.ok(List.of(Map.of("id", 3L)));
              });
      when(stock.seckill(anyMap()))
          .thenAnswer(
              invocation -> {
                assertEquals(OrderStockCircuitBreaker.SECKILL, currentResource());
                return ApiResponse.ok(Map.of("accepted", true));
              });
      controller.seckill("key", request(11L, 22L, 3L));
      assertSame(outer, ContextUtil.getContext().getCurEntry());
      var stockFailure = new IllegalStateException("unknown stock result");
      doThrow(stockFailure).when(stock).seckill(anyMap());
      assertSame(
          stockFailure,
          assertThrows(
              IllegalStateException.class, () -> controller.seckill("key", request(11L, 22L, 3L))));
      assertSame(outer, ContextUtil.getContext().getCurEntry());
      doReturn(null).when(stock).seckill(anyMap());
      assertEquals(
          503,
          assertThrows(BizException.class, () -> controller.seckill("key", request(11L, 22L, 3L)))
              .getHttpStatus());
      assertSame(outer, ContextUtil.getContext().getCurEntry());
      var userFailure = mock(FeignException.class);
      doThrow(userFailure).when(users).addresses();
      assertSame(
          userFailure,
          assertThrows(
              FeignException.class, () -> controller.seckill("key", request(11L, 22L, 3L))));
      assertSame(outer, ContextUtil.getContext().getCurEntry());
      doReturn(null).when(users).addresses();
      assertEquals(
          404,
          assertThrows(BizException.class, () -> controller.seckill("key", request(11L, 22L, 3L)))
              .getHttpStatus());
      assertSame(outer, ContextUtil.getContext().getCurEntry());
    }
  }

  @Test
  void serializationFailureExitsAdmissionWithoutCallingStock() throws Exception {
    addressAvailable();
    var brokenMapper = mock(ObjectMapper.class);
    when(brokenMapper.writeValueAsString(any()))
        .thenThrow(new JsonProcessingException("broken") {});
    var service = new OrderSeckillAdmissionServiceImpl(stock, users, brokenMapper);
    try (var outer = SphU.entry("admission-json-outer")) {
      BizException failure =
          assertThrows(BizException.class, () -> service.submit("key", request(11L, 22L, 3L), 33L));
      assertEquals(500, failure.getHttpStatus());
      assertEquals("地址快照生成失败", failure.getMessage());
      assertSame(outer, ContextUtil.getContext().getCurEntry());
      verifyNoInteractions(stock);
    }
  }

  @Test
  void springWiringAddsNoTransactionAndDoesNotSuspendAnExistingCallerTransaction() {
    addressAvailable();
    var transactions = new ObservedTransactions();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("order-admission-contract-test");
      context.register(
          Transactions.class,
          OrderSeckillAdmissionController.class,
          OrderSeckillAdmissionServiceImpl.class);
      context.registerBean(StockClient.class, () -> stock);
      context.registerBean(UserClient.class, () -> users);
      context.registerBean(ObjectMapper.class, () -> mapper);
      context.registerBean("transactionManager", ObservedTransactions.class, () -> transactions);
      context.refresh();
      var actualController = context.getBean(OrderSeckillAdmissionController.class);
      assertFalse(AopUtils.isAopProxy(context.getBean(OrderSeckillAdmissionService.class)));
      when(stock.seckill(anyMap()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return ApiResponse.ok(null);
              });
      actualController.seckill("key", request(11L, 22L, 3L));
      assertEquals(0, transactions.begins);
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return ApiResponse.ok(null);
              })
          .when(stock)
          .seckill(anyMap());
      new TransactionTemplate(transactions)
          .execute(
              status -> {
                actualController.seckill("key", request(11L, 22L, 3L));
                return null;
              });
      assertEquals(1, transactions.begins);
      assertEquals(1, transactions.commits);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  private void addressAvailable() {
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 3L))));
  }

  private static String currentResource() {
    return ContextUtil.getContext().getCurEntry().getResourceWrapper().getName();
  }

  private static SeckillOrderDTO request(Long activityId, Long skuId, Long addressId) {
    var request = new SeckillOrderDTO();
    request.activityId = activityId;
    request.skuId = skuId;
    request.addressId = addressId;
    return request;
  }

  private static MockMvc mvc(OrderSeckillAdmissionController controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .addInterceptors(new AuthInterceptor())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  /** 只在本类显式激活的上下文启用原Spring事务拦截机制。 */
  @Configuration
  @Profile("order-admission-contract-test")
  @EnableTransactionManagement
  static class Transactions {}

  /** 观察是否出现新事务或挂起行为，无数据库资源。 */
  static class ObservedTransactions extends AbstractPlatformTransactionManager {
    /** 实际开始事务次数。 */
    private int begins;

    /** 实际提交次数。 */
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      fail("unexpected rollback");
    }
  }
}
