package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.SeckillAcceptanceVO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationService;
import com.cloudmall.stock.service.StockQueryService;
import com.cloudmall.stock.service.StockReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证组合Controller保留六条HTTP绑定、匿名普通调用与秒杀首个鉴权检查点。 */
class StockControllerDelegationTest {
  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void allRoutesDelegateWithOriginalBindingsAndResponseFields() throws Exception {
    var queries = mock(StockQueryService.class);
    var reservations = mock(StockReservationService.class);
    var seckill = mock(SeckillReservationService.class);
    var controller = new StockController(queries, reservations, seckill);
    assertEquals(Object.class, StockController.class.getSuperclass());
    var mvc = mvc(controller);
    when(queries.get(7L)).thenReturn(new StockQuantityVO(7L, 3));
    when(queries.reservation("original-order"))
        .thenReturn(new StockReservationVO("original-order", "UNKNOWN", List.of()));
    doReturn(new StockReservationVO("original-order", "RESERVED", List.of()))
        .when(reservations)
        .reserve(any());
    doReturn(StockActionResultVO.confirmed(false))
        .when(reservations)
        .confirm(eq("original-order"), isNull());
    doReturn(StockActionResultVO.pendingRollback())
        .when(reservations)
        .rollback(eq("original-order"), any());
    doReturn(SeckillAcceptanceVO.rejected("original-order", "REJECTED", "original-event"))
        .when(seckill)
        .seckill(any(), eq(8L));

    // 普通内部调用不新增用户身份限制。
    assertNull(AuthContext.userId());
    mvc.perform(get("/api/stock/skus/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.availableQuantity").value(3));
    mvc.perform(get("/api/stock/reservations/original-order"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.items").isEmpty());
    mvc.perform(
            post("/api/stock/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"orderNo\":\"original-order\",\"items\":[{\"skuId\":7,\"quantity\":2}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("RESERVED"));
    mvc.perform(post("/api/stock/reservations/original-order/confirm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.confirmed").value(true))
        .andExpect(jsonPath("$.data.rolledBack").doesNotExist());
    mvc.perform(
            post("/api/stock/reservations/original-order/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"original-order:ROLLBACK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pending").value(true))
        .andExpect(jsonPath("$.data.idempotent").doesNotExist());
    AuthContext.set(8L, "USER");
    mvc.perform(
            post("/api/stock/seckill/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"activityId\":9,\"skuId\":7,\"userId\":8,\"idempotencyKey\":\"original-key\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accepted").value(false))
        .andExpect(jsonPath("$.data.status").doesNotExist());
    var request = ArgumentCaptor.forClass(StockReservationDTO.class);
    verify(reservations).reserve(request.capture());
    assertEquals("original-order", request.getValue().orderNo);
    assertEquals(7L, request.getValue().items.get(0).skuId);
    assertEquals(2, request.getValue().items.get(0).quantity);
    verify(reservations).confirm("original-order", null);
    var action = ArgumentCaptor.forClass(StockActionDTO.class);
    verify(reservations).rollback(eq("original-order"), action.capture());
    assertEquals("original-order:ROLLBACK", action.getValue().operationKey);
    verify(seckill).seckill(argThat(value -> value.userId.equals(8L)), eq(8L));
  }

  @Test
  void anonymousAndWrongOwnerSeckillRejectBeforeClaimOrRedis() throws Exception {
    var stocks = mock(StockSqlMapper.class);
    var redis = mock(StringRedisTemplate.class);
    var service = StockTestServices.seckillService(redis, stocks, new ObjectMapper());
    var mvc = mvc(new StockController(null, null, service));
    mvc.perform(
            post("/api/stock/seckill/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    AuthContext.set(8L, "USER");
    mvc.perform(
            post("/api/stock/seckill/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/stock/seckill/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"activityId\":9,\"skuId\":7,\"userId\":9,\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(stocks, redis);
  }

  private static MockMvc mvc(StockController controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }
}
