package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.controller.OrderStateController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderStateServiceImpl;
import com.cloudmall.order.service.impl.OrderWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 实际状态Controller/Service的绑定、鉴权时点、目标状态和原响应契约。 */
class OrderStateContractTest {
  /** 固定的已验证原单身份。 */
  private static final String ORDER = "202611state";

  /** 受控原订单持久层。 */
  private final OrderSqlMapper orders = mock(OrderSqlMapper.class);

  /** 受控库存动作写入观察点。 */
  private final OrderStockActionMapper actions = mock(OrderStockActionMapper.class);

  /** 保持原创建/状态互斥身份的监视器。 */
  private final OrderWriteMonitor monitor = new OrderWriteMonitor();

  /** 实际状态业务实现，本类只断言HTTP与规则，真实事务另由IT验证。 */
  private final OrderStateController controller =
      new OrderStateController(
          new OrderStateServiceImpl(orders, new ObjectMapper(), actions, monitor));

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void threeRoutesKeepFieldsMoneyAndOnlyTheirOriginalActionDirections() throws Exception {
    AuthContext.set(8L, "USER");
    for (String operation : List.of("cancel", "paid", "confirm")) {
      reset(orders, actions);
      String initial = operation.equals("confirm") ? "PAID" : "PENDING_PAYMENT";
      String target =
          operation.equals("cancel")
              ? "CANCELLED"
              : operation.equals("paid") ? "PAID" : "COMPLETED";
      var state = new AtomicReference<>(initial);
      when(orders.findOrder(anyString(), eq(ORDER)))
          .thenAnswer(invocation -> List.of(row(state.get())));
      when(orders.findOrderItems(anyString(), eq(ORDER))).thenReturn(List.of());
      when(orders.updateStatus(anyString(), eq(ORDER), eq(target), eq(initial), any()))
          .thenAnswer(
              invocation -> {
                assertEquals(!operation.equals("confirm"), Thread.holdsLock(monitor));
                state.set(target);
                return 1;
              });
      var result =
          mvc()
              .perform(
                  post("/api/orders/" + ORDER + "/" + operation)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("ignored, not parsed as JSON"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.status").value(target))
              .andExpect(jsonPath("$.data.payAmount").value("12.30"))
              .andExpect(jsonPath("$.data.items").isEmpty())
              .andReturn()
              .getResponse();
      var data = new ObjectMapper().readTree(result.getContentAsString()).get("data");
      assertTrue(data.has("expireAt"));
      assertTrue(data.get("expireAt").isNull());
      if (operation.equals("confirm")) verifyNoInteractions(actions);
      else
        verify(actions)
            .insert(
                anyLong(), eq(ORDER), eq(operation.equals("paid") ? "CONFIRM" : "ROLLBACK"), any());
    }
  }

  @Test
  void terminalReplayAndLostCasNeverWriteAnotherAction() {
    AuthContext.set(8L, "USER");
    for (String operation : List.of("cancel", "paid", "confirm")) {
      String initial = operation.equals("confirm") ? "PAID" : "PENDING_PAYMENT";
      String target =
          operation.equals("cancel")
              ? "CANCELLED"
              : operation.equals("paid") ? "PAID" : "COMPLETED";
      reset(orders, actions);
      when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of(row(target)));
      assertEquals(target, invoke(operation).data.status);
      verify(orders, never())
          .updateStatus(anyString(), anyString(), anyString(), anyString(), any());
      verifyNoInteractions(actions);

      when(orders.findOrder(anyString(), eq(ORDER)))
          .thenReturn(List.of(row(initial)), List.of(row(target)));
      assertEquals(target, invoke(operation).data.status);
      verifyNoInteractions(actions);
      when(orders.findOrder(anyString(), eq(ORDER)))
          .thenReturn(List.of(row(initial)), List.of(row("UNKNOWN")));
      assertEquals(409, assertThrows(BizException.class, () -> invoke(operation)).getHttpStatus());
      verifyNoInteractions(actions);
    }
  }

  @Test
  void missingIdentityDoesNotHideMissingOrMalformedOrderFacts() throws Exception {
    mvc().perform(post("/api/orders/202613bad/paid")).andExpect(status().isNotFound());
    verifyNoInteractions(orders, actions);
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of());
    mvc().perform(post("/api/orders/" + ORDER + "/cancel")).andExpect(status().isNotFound());
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of(row("PENDING_PAYMENT")));
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
        .perform(post("/api/orders/" + ORDER + "/paid"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("SKU快照读取失败"));
    when(orders.findOrderItems(anyString(), eq(ORDER))).thenReturn(List.of());
    mvc().perform(post("/api/orders/" + ORDER + "/paid")).andExpect(status().isUnauthorized());
    verifyNoInteractions(actions);
  }

  @Test
  void identityIsReadAfterDetailsAndCancelExplicitHeaderWins() throws Exception {
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of(row("PAID")));
    when(orders.findOrderItems(anyString(), eq(ORDER)))
        .thenAnswer(
            invocation -> {
              AuthContext.set(8L, "USER");
              return List.of();
            });
    AuthContext.set(9L, "USER");
    mvc().perform(post("/api/orders/" + ORDER + "/paid")).andExpect(status().isOk());
    when(orders.findOrder(anyString(), eq(ORDER))).thenReturn(List.of(row("CANCELLED")));
    when(orders.findOrderItems(anyString(), eq(ORDER)))
        .thenAnswer(
            invocation -> {
              AuthContext.set(9L, "USER");
              return List.of();
            });
    mvc()
        .perform(post("/api/orders/" + ORDER + "/cancel").header("X-User-Id", 8L))
        .andExpect(status().isOk());
    mvc().perform(post("/api/orders/" + ORDER + "/cancel")).andExpect(status().isForbidden());
    verifyNoInteractions(actions);
  }

  private com.cloudmall.common.api.ApiResponse<com.cloudmall.order.domain.vo.OrderVO> invoke(
      String operation) {
    return switch (operation) {
      case "cancel" -> controller.cancel(ORDER, null);
      case "paid" -> controller.paid(ORDER);
      default -> controller.confirm(ORDER);
    };
  }

  private static Map<String, Object> row(String status) {
    var row = new HashMap<String, Object>();
    row.put("order_no", ORDER);
    row.put("user_id", 8L);
    row.put("status", status);
    row.put("total_amount", new BigDecimal("12.30"));
    row.put("pay_amount", new BigDecimal("12.30"));
    row.put("address_snapshot", "{}");
    row.put("created_at", LocalDateTime.of(2026, 11, 1, 12, 0));
    row.put("expire_at", null);
    return row;
  }

  private MockMvc mvc() {
    var builder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(builder);
    return MockMvcBuilders.standaloneSetup(controller, new OrderController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
        .build();
  }
}
