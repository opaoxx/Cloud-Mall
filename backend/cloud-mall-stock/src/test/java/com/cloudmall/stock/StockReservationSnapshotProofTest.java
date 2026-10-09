package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 请求原快照必须覆盖完整观察流水，JSON存储规范化不得破坏合法hash。 */
class StockReservationSnapshotProofTest {
  /** 原writer的合并升序规范JSON。 */
  private static final String SNAPSHOT =
      "[{\"skuId\":7,\"quantity\":2},{\"skuId\":8,\"quantity\":3}]";

  @Test
  void missingExtraAndWrongQuantitySkuCannotBecomeWholeRequestTerminal() {
    for (List<Map<String, Object>> rows :
        List.of(
            List.of(flow(7, 2)),
            List.of(flow(7, 2), flow(8, 3), flow(9, 1)),
            List.of(flow(7, 2), flow(8, 4)),
            List.of(flow(7, 2), flow(7, 2)))) assertUnknown(rows, request(SNAPSHOT));
  }

  @Test
  void wrongIdentitySceneMissingSnapshotAndBadHashAreBlocked() {
    for (String field : List.of("order_no", "scene", "items_snapshot", "items_hash")) {
      var missing = request(SNAPSHOT);
      missing.remove(field);
      assertUnknown(flows(), missing);
      var incorrect = request(SNAPSHOT);
      incorrect.put(field, "wrong");
      assertUnknown(flows(), incorrect);
    }
    var alias = request(SNAPSHOT);
    alias.put("order_no", "ORDER-PROOF");
    assertUnknown(flows(), alias);
    var upperHash = request(SNAPSHOT);
    upperHash.put("items_hash", hash(SNAPSHOT).toUpperCase());
    assertUnknown(flows(), upperHash);
  }

  @Test
  void malformedNonNativeDuplicateUnsortedAndOverCapacitySnapshotIsNotEvidence() {
    for (String snapshot :
        List.of(
            "null",
            "{}",
            "[]",
            "[null]",
            "broken",
            "[{\"skuId\":7,\"quantity\":2}] {}",
            "[{\"skuId\":7,\"skuId\":8,\"quantity\":2}]",
            "[{\"skuId\":7,\"quantity\":2},{\"skuId\":7,\"quantity\":2}]",
            "[{\"skuId\":8,\"quantity\":3},{\"skuId\":7,\"quantity\":2}]",
            "[{\"skuId\":\"7\",\"quantity\":2},{\"skuId\":8,\"quantity\":3}]",
            "[{\"skuId\":7,\"quantity\":2.0},{\"skuId\":8,\"quantity\":3}]",
            "[{\"skuId\":7,\"quantity\":2,\"extra\":1},{\"skuId\":8,\"quantity\":3}]",
            "[{\"skuId\":9223372036854775808,\"quantity\":2}]",
            "[{\"skuId\":7,\"quantity\":2147483648}]")) assertUnknown(flows(), request(snapshot));
  }

  @Test
  void jsonWhitespaceAndObjectKeyOrderUseOriginalWriterCanonicalHash() {
    var normalized = request(SNAPSHOT);
    normalized.put(
        "items_snapshot",
        "[ { \"quantity\": 2, \"skuId\": 7 }, { \"quantity\": 3, \"skuId\": 8 } ]");
    var result = read(flows(), normalized);
    assertEquals("CONFIRMED", result.status);
    assertEquals(2, result.items.size());
    assertEquals("CONFIRMED", read(flows(), null).status);
  }

  @Test
  void replayCannotAcknowledgeMismatchedSnapshotOrHideReadFailure() {
    String original = "[{\"skuId\":7,\"quantity\":2}]";
    var values = request(original);
    values.put("status", "RESERVED");
    values.put("items_snapshot", "[{\"skuId\":7,\"quantity\":3}]");
    for (boolean fails : List.of(false, true)) {
      StockSqlMapper mapper = mock(StockSqlMapper.class);
      when(mapper.findReservationRequest("order-proof")).thenReturn(values);
      when(mapper.findSeckillByOrderNo("order-proof")).thenReturn(null);
      when(mapper.findReservationRequestForUpdate("order-proof")).thenReturn(values);
      if (fails)
        when(mapper.findReservationFlows("order-proof"))
            .thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("password=secret"));
      else
        when(mapper.findReservationFlows("order-proof"))
            .thenReturn(
                List.of(
                    Map.of(
                        "sku_id",
                        7L,
                        "original_order_no",
                        "order-proof",
                        "order_identity_count",
                        1L,
                        "invalid_flow_count",
                        BigDecimal.ZERO,
                        "reserved_quantity",
                        BigDecimal.valueOf(2),
                        "confirmed_quantity",
                        BigDecimal.ZERO,
                        "rolled_back_quantity",
                        BigDecimal.ZERO)));
      var claims = mock(com.cloudmall.stock.service.impl.StockReservationRequestService.class);
      var service = new StockServiceImpl(null, mapper, null, new ObjectMapper());
      org.springframework.test.util.ReflectionTestUtils.setField(
          service, "reservationRequestService", claims);
      var input = new com.cloudmall.stock.domain.dto.StockReservationDTO();
      input.orderNo = "order-proof";
      input.scene = "NORMAL";
      input.items = List.of(new com.cloudmall.stock.domain.dto.StockLineDTO(7L, 2));
      var failure =
          assertThrows(com.cloudmall.common.error.BizException.class, () -> service.reserve(input));
      assertEquals(503, failure.getHttpStatus());
      assertEquals("STOCK_RESERVATION_PROOF_UNAVAILABLE", failure.getCode());
      assertFalse(failure.getMessage().contains("secret"));
      verify(mapper, never()).findStockForUpdate(anyLong());
      verify(mapper, never()).updateAvailableWithVersion(anyLong(), anyInt(), anyInt(), any());
    }
  }

  @Test
  void readDoesNotMutateSharedObjectMapperParsingPolicy() {
    ObjectMapper json = new ObjectMapper();
    boolean duplicates =
        json.getFactory()
            .isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findSeckillByOrderNo("order-proof")).thenReturn(null);
    when(mapper.findReservationRequest("order-proof")).thenReturn(request(SNAPSHOT));
    when(mapper.findReservationFlows("order-proof")).thenReturn(flows());
    var service = new StockServiceImpl(null, mapper, null, json);
    assertEquals(
        "CONFIRMED", ((StockReservationVO) service.reservation("order-proof").data).status);
    assertEquals(
        duplicates,
        json.getFactory()
            .isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
  }

  /** 库存被全部确认的同向观察事实。 */
  private static List<Map<String, Object>> flows() {
    return List.of(flow(7, 2), flow(8, 3));
  }

  /** 真实JDBC聚合类型，原量等于确认量。 */
  private static Map<String, Object> flow(long skuId, int quantity) {
    return Map.of(
        "sku_id",
        skuId,
        "original_order_no",
        "order-proof",
        "order_identity_count",
        1L,
        "invalid_flow_count",
        BigDecimal.ZERO,
        "reserved_quantity",
        BigDecimal.valueOf(quantity),
        "confirmed_quantity",
        BigDecimal.valueOf(quantity),
        "rolled_back_quantity",
        BigDecimal.ZERO);
  }

  /** 原请求元数据及原writer hash，不伪造新操作键。 */
  private static Map<String, Object> request(String snapshot) {
    Map<String, Object> values = new HashMap<>();
    values.put("order_no", "order-proof");
    values.put("scene", "NORMAL");
    values.put("status", "CONFIRMED");
    values.put("items_snapshot", snapshot);
    values.put("items_hash", hash(snapshot));
    return values;
  }

  /** 与既有writer的SHA256相同。 */
  private static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** 缺证只读阻塞，不输出看似已结清的明细。 */
  private static void assertUnknown(List<Map<String, Object>> rows, Map<String, Object> request) {
    var result = read(rows, request);
    assertEquals("UNKNOWN", result.status);
    assertTrue(result.items.isEmpty());
  }

  /** 仅三个原Mapper读调用，禁止新增写入或对源Map修改。 */
  private static StockReservationVO read(
      List<Map<String, Object>> rows, Map<String, Object> request) {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findSeckillByOrderNo("order-proof")).thenReturn(null);
    when(mapper.findReservationRequest("order-proof")).thenReturn(request);
    when(mapper.findReservationFlows("order-proof")).thenReturn(rows);
    Map<String, Object> before = request == null ? null : new HashMap<>(request);
    var result =
        (StockReservationVO)
            new StockServiceImpl(null, mapper, null, new ObjectMapper())
                .reservation("order-proof")
                .data;
    assertEquals(before, request);
    verify(mapper).findSeckillByOrderNo("order-proof");
    verify(mapper).findReservationRequest("order-proof");
    verify(mapper).findReservationFlows("order-proof");
    verifyNoMoreInteractions(mapper);
    return result;
  }
}
