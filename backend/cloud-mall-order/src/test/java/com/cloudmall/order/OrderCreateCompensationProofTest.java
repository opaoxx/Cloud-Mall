package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** 同步补偿的创建原异常、单次记录及嵌套suppressed关系保持。 */
class OrderCreateCompensationProofTest {
  /** 只清理本例鉴权上下文。 */
  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void unsuccessfulRollbackDoesNotQueryProofOrHideTheOriginalCreateFailure() {
    for (String failure : List.of("null", "nonzero", "exception")) {
      Fixture fixture = new Fixture();
      switch (failure) {
        case "null" -> doReturn(null).when(fixture.stock).rollback(anyString());
        case "nonzero" ->
            doReturn(ApiResponse.error("503", "unknown")).when(fixture.stock).rollback(anyString());
        case "exception" ->
            doThrow(new IllegalStateException("rollback unavailable"))
                .when(fixture.stock)
                .rollback(anyString());
        default -> throw new AssertionError(failure);
      }
      RuntimeException returned =
          assertThrows(
              RuntimeException.class,
              () -> fixture.service.create("compensation-proof", request()));
      assertSame(fixture.creationFailure, returned);
      assertEquals(1, returned.getSuppressed().length);
      verify(fixture.stock).reserve(any());
      verify(fixture.stock).rollback(anyString());
      verify(fixture.stock, never()).reservation(anyString());
      verify(fixture.intents).recordFailure(anyString(), anyString());
      verify(fixture.intents, never()).markReleased(anyString());
    }
  }

  @Test
  void proofOrReleaseFailureRetainsOriginalAndNestedRecordingEvidence() {
    for (String phase : List.of("query", "release", "record")) {
      Fixture fixture = new Fixture();
      doReturn(ApiResponse.ok("accepted")).when(fixture.stock).rollback(anyString());
      when(fixture.stock.reservation(anyString()))
          .thenAnswer(
              invocation ->
                  fact(
                      invocation.getArgument(0),
                      "record".equals(phase) ? "UNKNOWN" : "ROLLED_BACK"));
      IllegalStateException compensationFailure = new IllegalStateException("compensation failure");
      IllegalStateException recordFailure = new IllegalStateException("record failure");
      if ("query".equals(phase))
        doThrow(compensationFailure).when(fixture.stock).reservation(anyString());
      if ("release".equals(phase))
        doThrow(compensationFailure).when(fixture.intents).markReleased(anyString());
      if ("record".equals(phase))
        doThrow(recordFailure).when(fixture.intents).recordFailure(anyString(), anyString());
      RuntimeException returned =
          assertThrows(
              RuntimeException.class,
              () -> fixture.service.create("compensation-proof", request()));
      assertSame(fixture.creationFailure, returned);
      assertEquals(1, returned.getSuppressed().length);
      if ("record".equals(phase)) {
        assertEquals(1, returned.getSuppressed()[0].getSuppressed().length);
        assertSame(recordFailure, returned.getSuppressed()[0].getSuppressed()[0]);
      } else assertSame(compensationFailure, returned.getSuppressed()[0]);
      verify(fixture.intents).recordFailure(anyString(), anyString());
      verify(fixture.stock).reserve(any());
      verify(fixture.stock).rollback(anyString());
      verify(fixture.stock).reservation(anyString());
      if ("release".equals(phase)) verify(fixture.intents).markReleased(anyString());
      else verify(fixture.intents, never()).markReleased(anyString());
    }
  }

  /** 实际Service编排，创建SQL与独立意图服务异常由替身明确注入。 */
  private static class Fixture {
    /** 库存客户端替身。 */
    final StockClient stock = mock(StockClient.class);

    /** 意图独立事务服务替身。 */
    final OrderReservationIntentService intents = mock(OrderReservationIntentService.class);

    /** 原订单落库异常，必须由create原样抛回。 */
    final DataAccessResourceFailureException creationFailure =
        new DataAccessResourceFailureException("create unavailable");

    /** 实际被测创建与补偿编排。 */
    final OrderServiceImpl service;

    private Fixture() {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      ProductClient products = mock(ProductClient.class);
      UserClient users = mock(UserClient.class);
      when(products.sku(7L))
          .thenReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", BigDecimal.TEN, Map.of())));
      when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
      when(intents.createIntent(any())).thenReturn(true);
      doAnswer(
              invocation -> {
                StockClient.Reservation request = invocation.getArgument(0);
                return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
              })
          .when(stock)
          .reserve(any());
      when(orders.insertOrder(anyString(), any(OrderPO.class))).thenThrow(creationFailure);
      service =
          new OrderServiceImpl(
              stock, products, users, mock(CartClient.class), orders, new ObjectMapper());
      ReflectionTestUtils.setField(service, "reservationIntentService", intents);
      AuthContext.set(7L, "USER");
    }
  }

  /** 原合法结算输入，不替换库存响应身份。 */
  private static CreateOrderDTO request() {
    OrderItemDTO item = new OrderItemDTO();
    item.skuId = 7L;
    item.quantity = 1;
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    request.items = List.of(item);
    return request;
  }

  /** 原单状态证明，必须由调用参数以外的响应字段单独核验。 */
  private static ApiResponse<StockReservationStatusVO> fact(String orderNo, String status) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = status;
    fact.items = List.of();
    return ApiResponse.ok(fact);
  }
}
