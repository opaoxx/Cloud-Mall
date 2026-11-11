package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.controller.OrderDetailQueryController;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.domain.vo.OrderPaymentStateVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderQueryService;
import com.cloudmall.order.service.impl.OrderDetailQueryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实MVC验证原路径、延迟身份读取、失败顺序及详情/支付VO序列化契约。 */
class OrderDetailQueryContractTest {
  /** 测试固定原订单号。 */
  private static final String ORDER = "202610detail";

  /** 受控订单数据源，返回原JDBC行结构。 */
  private final OrderSqlMapper orders = mock(OrderSqlMapper.class);

  /** 受控动作证明，不执行写入。 */
  private final OrderStockActionMapper actions = mock(OrderStockActionMapper.class);

  /** 存量终态的库存只读替身。 */
  private final StockClient stock = mock(StockClient.class);

  /** 实际新查询入口。 */
  private final OrderDetailQueryController controller =
      new OrderDetailQueryController(
          new OrderDetailQueryServiceImpl(orders, new ObjectMapper(), actions, stock));

  @AfterEach
  void clearContext() {
    AuthContext.clear();
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @Test
  void detailRouteRetainsMoneyTimeNullAndItemSnapshots() throws Exception {
    givenOrder("PENDING_PAYMENT");
    Map<String, Object> item = new HashMap<>();
    item.put("product_id", 1L);
    item.put("sku_id", 2L);
    item.put("product_name_snapshot", "原商品");
    item.put("sku_snapshot", null);
    item.put("unit_price", new BigDecimal("12.30"));
    item.put("quantity", 1);
    item.put("line_amount", new BigDecimal("12.30"));
    when(orders.findOrderItems(anyString(), eq(ORDER))).thenReturn(List.of(item));
    AuthContext.set(8L, "USER");
    var response =
        mvc()
            .perform(get("/api/orders/" + ORDER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderNo").value(ORDER))
            .andExpect(jsonPath("$.data.payAmount").value("12.30"))
            .andExpect(jsonPath("$.data.createdAt").value("2026-10-01T12:00:00.123+08:00"))
            .andExpect(jsonPath("$.data.addressSnapshot").value("null"))
            .andExpect(jsonPath("$.data.items[0].skuSnapshot").isEmpty())
            .andExpect(jsonPath("$.data.items[0].unitPrice").value("12.30"))
            .andReturn()
            .getResponse();
    var data = new ObjectMapper().readTree(response.getContentAsString()).get("data");
    assertTrue(data.has("expireAt"));
    assertTrue(data.get("expireAt").isNull());
    verifyNoInteractions(actions, stock);
  }

  @Test
  void invalidOrMissingOrderPrecedesMissingAuthentication() throws Exception {
    mvc().perform(get("/api/orders/202613bad")).andExpect(status().isNotFound());
    verifyNoInteractions(orders, actions, stock);
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of());
    mvc().perform(get("/api/orders/" + ORDER)).andExpect(status().isNotFound());
    when(orders.findOrder(anyString(), eq(ORDER)))
        .thenReturn(List.of(Map.of("order_no", ORDER.toUpperCase())));
    mvc()
        .perform(get("/api/internal/orders/" + ORDER + "/payment-state"))
        .andExpect(status().isNotFound());
    verify(orders, never()).findOrderItems(anyString(), anyString());
    verifyNoInteractions(actions, stock);
  }

  @Test
  void malformedItemPrecedesAuthenticationButValidDetailsRequireIdentity() throws Exception {
    givenOrder("PENDING_PAYMENT");
    when(orders.findOrderItems(anyString(), eq(ORDER)))
        .thenReturn(
            List.of(
                Map.of(
                    "product_id",
                    1L,
                    "sku_id",
                    2L,
                    "product_name_snapshot",
                    "item",
                    "sku_snapshot",
                    "{bad",
                    "unit_price",
                    BigDecimal.ONE,
                    "quantity",
                    1,
                    "line_amount",
                    BigDecimal.ONE)));
    mvc()
        .perform(get("/api/orders/" + ORDER))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("SKU快照读取失败"));
    when(orders.findOrderItems(anyString(), eq(ORDER))).thenReturn(List.of());
    mvc()
        .perform(get("/api/orders/" + ORDER))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("请先登录"));
    verifyNoInteractions(actions, stock);
  }

  @Test
  void contextIsReadAtOriginalLatePositionAndExplicitHeaderStillWins() throws Exception {
    givenOrder("PENDING_PAYMENT");
    when(orders.findOrderItems(anyString(), eq(ORDER)))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              AuthContext.set(8L, "USER");
              return List.of();
            });
    AuthContext.set(9L, "USER");
    mvc().perform(get("/api/orders/" + ORDER)).andExpect(status().isOk());
    when(orders.findOrderItems(anyString(), eq(ORDER)))
        .thenAnswer(
            invocation -> {
              AuthContext.set(9L, "USER");
              return List.of();
            });
    mvc()
        .perform(get("/api/internal/orders/" + ORDER + "/payment-state").header("X-User-Id", 8L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(8));
    mvc()
        .perform(get("/api/internal/orders/" + ORDER + "/payment-state"))
        .andExpect(status().isForbidden());
  }

  @Test
  void paymentStateHasExactlyFiveFieldsAndUsesOnlyProvenLegacyStock() throws Exception {
    givenOrder("PAID");
    var fact = new StockReservationStatusVO();
    fact.orderNo = ORDER;
    fact.status = "CONFIRMED";
    when(stock.reservation(ORDER)).thenReturn(ApiResponse.ok(fact));
    var response =
        mvc()
            .perform(
                get("/api/internal/orders/" + ORDER + "/payment-state").header("X-User-Id", 8L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(jsonPath("$.data.stockActionStatus").value("SUCCEEDED"))
            .andExpect(jsonPath("$.data.payAmount").value("12.30"))
            .andReturn()
            .getResponse();
    var data = new ObjectMapper().readTree(response.getContentAsString()).get("data");
    assertEquals(
        java.util.Set.of("orderNo", "status", "payAmount", "userId", "stockActionStatus"),
        new ObjectMapper().convertValue(data, Map.class).keySet());
    verify(actions).statusForOrder(ORDER, "CONFIRM");
    fact.orderNo = "other-order";
    assertEquals("UNKNOWN", controller.paymentState(ORDER, 8L).data.stockActionStatus);
    when(stock.reservation(ORDER)).thenThrow(new IllegalStateException("unavailable"));
    assertEquals("UNKNOWN", controller.paymentState(ORDER, 8L).data.stockActionStatus);
  }

  @Test
  void existingUnknownActionDoesNotTriggerStockFallbackAndNullAmountStillFails() throws Exception {
    var row = givenOrder("PAID");
    when(actions.statusForOrder(ORDER, "CONFIRM")).thenReturn("UNKNOWN");
    assertEquals("UNKNOWN", controller.paymentState(ORDER, 8L).data.stockActionStatus);
    verifyNoInteractions(stock);
    row.put("pay_amount", null);
    mvc()
        .perform(get("/api/internal/orders/" + ORDER + "/payment-state").header("X-User-Id", 8L))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void paymentProjectionPreservesOldMapNonNullContract() {
    assertThrows(
        NullPointerException.class,
        () -> new OrderPaymentStateVO(null, "PAID", BigDecimal.ONE, 8L, "UNKNOWN"));
    assertThrows(
        NullPointerException.class,
        () -> new OrderPaymentStateVO(ORDER, null, BigDecimal.ONE, 8L, "UNKNOWN"));
    assertThrows(
        NullPointerException.class,
        () -> new OrderPaymentStateVO(ORDER, "PAID", null, 8L, "UNKNOWN"));
    assertThrows(
        NullPointerException.class,
        () -> new OrderPaymentStateVO(ORDER, "PAID", BigDecimal.ONE, null, "UNKNOWN"));
    assertThrows(
        NullPointerException.class,
        () -> new OrderPaymentStateVO(ORDER, "PAID", BigDecimal.ONE, 8L, null));
  }

  private Map<String, Object> givenOrder(String status) {
    var row = new HashMap<String, Object>();
    row.put("order_no", ORDER);
    row.put("user_id", 8L);
    row.put("status", status);
    row.put("total_amount", new BigDecimal("12.30"));
    row.put("pay_amount", new BigDecimal("12.30"));
    row.put("address_snapshot", null);
    row.put("created_at", LocalDateTime.parse("2026-10-01T12:00:00.123"));
    row.put("expire_at", null);
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of(row));
    when(orders.findOrderItems(anyString(), eq(ORDER))).thenReturn(List.of());
    return row;
  }

  private MockMvc mvc() {
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    return MockMvcBuilders.standaloneSetup(
            controller,
            new OrderController(),
            new OrderQueryController(mock(OrderQueryService.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
        .build();
  }
}
