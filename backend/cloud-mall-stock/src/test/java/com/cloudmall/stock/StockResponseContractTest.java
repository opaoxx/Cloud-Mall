package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.vo.SeckillAcceptanceVO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationService;
import com.cloudmall.stock.service.StockQueryService;
import com.cloudmall.stock.service.StockReservationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 以旧HTTP字段集合验证具体VO，尤其区分false、显式null和不存在的字段。 */
class StockResponseContractTest {
  /** 采用项目默认Jackson规则，不修改全局配置。 */
  private final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
  }

  @Test
  void actionVariantsKeepExactFieldsIncludingFalse() {
    var values =
        List.of(
            StockActionResultVO.confirmed(false),
            StockActionResultVO.confirmed(true),
            StockActionResultVO.rolledBack(false),
            StockActionResultVO.rolledBack(true),
            StockActionResultVO.pendingRollback());
    var expected =
        List.of(
            Map.of("confirmed", true, "idempotent", false),
            Map.of("confirmed", true, "idempotent", true),
            Map.of("rolledBack", true, "idempotent", false),
            Map.of("rolledBack", true, "idempotent", true),
            Map.of("rolledBack", false, "pending", true, "status", "UNKNOWN"));
    for (int index = 0; index < values.size(); index++)
      assertEquals(json.valueToTree(expected.get(index)), json.valueToTree(values.get(index)));
    // 即使外围默认包含null，也只省略本VO未参与该分支的字段。
    assertEquals(
        json.valueToTree(expected.get(4)),
        new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .valueToTree(values.get(4)));
  }

  @Test
  void seckillVariantsKeepExactStatusReasonAndIdentityFields() {
    var expected =
        List.of(
            Map.of(
                "accepted",
                true,
                "orderNo",
                "order-1",
                "eventId",
                "event-1",
                "reservationStatus",
                "ACCEPTED",
                "status",
                "PENDING_PAYMENT"),
            Map.of(
                "accepted",
                false,
                "orderNo",
                "order-1",
                "eventId",
                "event-1",
                "reservationStatus",
                "REJECTED"),
            Map.of(
                "accepted",
                false,
                "orderNo",
                "order-1",
                "eventId",
                "event-1",
                "reservationStatus",
                "RECOVERY_REQUIRED",
                "status",
                "ORDER_RECOVERY_PENDING",
                "reason",
                "SECKILL_ACCEPTANCE_RESULT_UNKNOWN"));
    var values =
        List.of(
            SeckillAcceptanceVO.accepted("order-1", "ACCEPTED", "event-1"),
            SeckillAcceptanceVO.rejected("order-1", "REJECTED", "event-1"),
            SeckillAcceptanceVO.recoveryRequired(
                "order-1", "event-1", "SECKILL_ACCEPTANCE_RESULT_UNKNOWN"));
    for (int index = 0; index < values.size(); index++)
      assertEquals(json.valueToTree(expected.get(index)), json.valueToTree(values.get(index)));
  }

  @Test
  void requiredNullValuesRemainRejectedAsWithOriginalMapOf() {
    for (String[] values :
        new String[][] {
          {null, "ACCEPTED", "event"}, {"order", null, "event"}, {"order", "ACCEPTED", null}
        }) {
      assertThrows(
          NullPointerException.class,
          () -> SeckillAcceptanceVO.accepted(values[0], values[1], values[2]));
      assertThrows(
          NullPointerException.class,
          () -> SeckillAcceptanceVO.rejected(values[0], values[1], values[2]));
    }
    assertThrows(
        NullPointerException.class,
        () -> SeckillAcceptanceVO.recoveryRequired(null, "event", "reason"));
    assertThrows(
        NullPointerException.class,
        () -> SeckillAcceptanceVO.recoveryRequired("order", null, "reason"));
    assertThrows(
        NullPointerException.class,
        () -> SeckillAcceptanceVO.recoveryRequired("order", "event", null));
  }

  @Test
  void existingQueryNullFieldsRemainExplicit() throws Exception {
    assertEquals(
        json.readTree("{\"skuId\":null,\"availableQuantity\":0}"),
        json.valueToTree(new StockQuantityVO(null, 0)));
    assertEquals(
        json.readTree("{\"orderNo\":null,\"status\":null,\"items\":null}"),
        json.valueToTree(new StockReservationVO(null, null, null)));
  }

  @Test
  void controllerWrapsAllActionAndSeckillVariantsForUntypedFeignDecoders() throws Exception {
    var reservations = mock(StockReservationService.class);
    var seckill = mock(SeckillReservationService.class);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new StockController(mock(StockQueryService.class), reservations, seckill))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    Set<String> requestIds = new HashSet<>();
    for (var value :
        List.of(
            StockActionResultVO.confirmed(false),
            StockActionResultVO.confirmed(true),
            StockActionResultVO.rolledBack(false),
            StockActionResultVO.rolledBack(true),
            StockActionResultVO.pendingRollback())) {
      String action = value.confirmed != null ? "confirm" : "rollback";
      when(reservations.confirm("order-1", null)).thenReturn(value);
      when(reservations.rollback("order-1", null)).thenReturn(value);
      String body =
          mvc.perform(post("/api/stock/reservations/order-1/" + action))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertEnvelope(body, value, requestIds);
    }
    AuthContext.set(8L, "USER");
    for (var value :
        List.of(
            SeckillAcceptanceVO.accepted("order-1", "ACCEPTED", "event-1"),
            SeckillAcceptanceVO.rejected("order-1", "REJECTED", "event-1"),
            SeckillAcceptanceVO.recoveryRequired(
                "order-1", "event-1", "SECKILL_ACCEPTANCE_RESULT_UNKNOWN"))) {
      when(seckill.seckill(any(), eq(8L))).thenReturn(value);
      String body =
          mvc.perform(
                  post("/api/stock/seckill/reservations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertEnvelope(body, value, requestIds);
    }
    assertEquals(8, requestIds.size());
  }

  @Test
  void replayStillRejectsMissingBusinessProofBeforePromotingStatus() {
    var reader = spy(StockTestServices.reader(mock(StockSqlMapper.class), json));
    doReturn(null).when(reader).reservation("order-1");
    BizException unavailable =
        assertThrows(BizException.class, () -> reader.alreadyReserved("order-1"));
    assertEquals("STOCK_RESERVATION_PROOF_UNAVAILABLE", unavailable.getCode());
    assertEquals(503, unavailable.getHttpStatus());
    var proof = new StockReservationVO("order-1", "RESERVED", List.of());
    doReturn(proof).when(reader).reservation("order-1");
    assertSame(proof, reader.alreadyReserved("order-1"));
    assertEquals("ALREADY_RESERVED", proof.status);
  }

  private void assertEnvelope(String body, Object expected, Set<String> requestIds)
      throws Exception {
    JsonNode envelope = json.readTree(body);
    Set<String> fields = new HashSet<>();
    envelope.fieldNames().forEachRemaining(fields::add);
    assertEquals(Set.of("code", "message", "data", "requestId"), fields);
    assertEquals("0", envelope.path("code").asText());
    assertEquals("OK", envelope.path("message").asText());
    assertEquals(json.valueToTree(expected), envelope.get("data"));
    String requestId = envelope.path("requestId").asText();
    UUID.fromString(requestId);
    assertTrue(requestIds.add(requestId));
    ApiResponse<?> decoded = json.readValue(body, new TypeReference<ApiResponse<?>>() {});
    assertInstanceOf(Map.class, decoded.data);
    assertEquals(envelope.get("data"), json.valueToTree(decoded.data));
  }
}
