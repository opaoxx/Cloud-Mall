package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 普通库存GET必须证明每一SKU完整同向，错误证据只返回UNKNOWN。 */
class StockReservationTerminalProofTest {
  @Test
  void oneSettledSkuCannotMakePartiallySettledOrMixedOrdersTerminal() {
    for (List<Map<String, Object>> rows :
        List.of(
            List.of(flow(7, 2, 2, 0), flow(8, 3, 0, 0)),
            List.of(flow(8, 3, 0, 0), flow(7, 2, 2, 0)),
            List.of(flow(7, 2, 0, 2), flow(8, 3, 0, 0)),
            List.of(flow(7, 2, 2, 0), flow(8, 3, 0, 3)),
            List.of(flow(8, 3, 0, 3), flow(7, 2, 2, 0)))) assertUnknown(rows, null);
  }

  @Test
  void aPartiallySettledSkuOrMixedDirectionInsideOneSkuIsUnknown() {
    for (Map<String, Object> row : List.of(flow(7, 3, 1, 0), flow(7, 3, 0, 1), flow(7, 3, 1, 2)))
      assertUnknown(List.of(row), null);
  }

  @Test
  void requestTerminalCannotOverrideIncompleteOrOppositeFlows() {
    for (String requested : List.of("CONFIRMED", "ROLLED_BACK", "RESERVED", "INIT", "REJECTED"))
      assertUnknown(List.of(flow(7, 2, 2, 0), flow(8, 2, 0, 0)), requested);
    assertUnknown(List.of(flow(7, 2, 2, 0)), "ROLLED_BACK");
    assertUnknown(List.of(flow(7, 2, 0, 2)), "CONFIRMED");
    assertUnknown(List.of(flow(7, 2, 0, 0)), "CONFIRMED");
  }

  @Test
  void invalidNullFractionalNegativeAndOverCapacityCountsCannotBecomeProof() {
    List<Map<String, Object>> invalid = new ArrayList<>();
    invalid.add(flow(0, 2, 2, 0));
    invalid.add(flow(7, 0, 1, 0));
    invalid.add(flow(7, 2, -1, 0));
    invalid.add(flow(7, 2, 0, -1));
    invalid.add(flow(7, 2, 3, 0));
    for (String field :
        List.of("sku_id", "reserved_quantity", "confirmed_quantity", "rolled_back_quantity")) {
      Map<String, Object> missing = flow(7, 2, 2, 0);
      missing.put(field, null);
      invalid.add(missing);
      Map<String, Object> fractional = flow(7, 2, 2, 0);
      fractional.put(field, new BigDecimal("1.5"));
      invalid.add(fractional);
      Map<String, Object> text = flow(7, 2, 2, 0);
      text.put(field, "2");
      invalid.add(text);
      Map<String, Object> floating = flow(7, 2, 2, 0);
      floating.put(field, 2.0d);
      invalid.add(floating);
    }
    Map<String, Object> excessive = flow(7, 2, 2, 0);
    excessive.put("reserved_quantity", new BigDecimal("4294967298"));
    invalid.add(excessive);
    for (Map<String, Object> row : invalid) assertUnknown(List.of(row), "CONFIRMED");
  }

  @Test
  void summedConfirmAndRollbackCannotOverflowOrExceedOriginalReserve() {
    assertUnknown(List.of(flow(7, Integer.MAX_VALUE, Integer.MAX_VALUE, 1)), "CONFIRMED");
    assertUnknown(
        List.of(flow(7, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)), "ROLLED_BACK");
  }

  @Test
  void completeSingleAndMultipleSkuFactsRetainOriginalIntegersAndStatuses() {
    assertEquals("NOT_FOUND", read(List.of(), null).status);
    StockSqlMapper pendingMapper = mock(StockSqlMapper.class);
    when(pendingMapper.findReservationRequest("pending"))
        .thenReturn(Map.of("status", "INIT", "items_snapshot", "[{\"skuId\":7,\"quantity\":2}]"));
    when(pendingMapper.findReservationFlows("pending")).thenReturn(List.of());
    var pendingService = new StockServiceImpl(null, pendingMapper, null, new ObjectMapper());
    var pending = (StockReservationVO) pendingService.reservation("pending").data;
    assertEquals("INIT", pending.status);
    assertEquals(2, pending.items.get(0).quantity);
    when(pendingMapper.findReservationRequest("pending")).thenReturn(Map.of("status", "CONFIRMED"));
    assertThrows(IllegalStateException.class, () -> pendingService.reservation("pending"));
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK")) {
      int confirmed = "CONFIRMED".equals(state) ? 2 : 0;
      int rolledBack = "ROLLED_BACK".equals(state) ? 2 : 0;
      for (boolean hasRequest : List.of(false, true)) {
        var report =
            read(
                List.of(flow(7, 2, confirmed, rolledBack), flow(8, 2, confirmed, rolledBack)),
                hasRequest ? state : null);
        assertEquals(state, report.status);
        assertEquals(2, report.items.size());
        assertTrue(
            report.items.stream()
                .allMatch(item -> item.quantity == 2 && state.equals(item.status)));
      }
      var maximum =
          read(
              List.of(
                  flow(
                      7,
                      Integer.MAX_VALUE,
                      "CONFIRMED".equals(state) ? Integer.MAX_VALUE : 0,
                      "ROLLED_BACK".equals(state) ? Integer.MAX_VALUE : 0)),
              state);
      assertEquals(state, maximum.status);
      assertEquals(Integer.MAX_VALUE, maximum.items.get(0).quantity);
    }
  }

  /** 构造真实SQL SUM返回的BigDecimal数值形态，调用方可用于损坏证据样本。 */
  private static Map<String, Object> flow(long skuId, int reserved, int confirmed, int rolledBack) {
    Map<String, Object> row = new HashMap<>();
    row.put("sku_id", skuId);
    row.put("original_order_no", "order-proof");
    row.put("order_identity_count", 1L);
    row.put("invalid_flow_count", BigDecimal.ZERO);
    row.put("reserved_quantity", BigDecimal.valueOf(reserved));
    row.put("confirmed_quantity", BigDecimal.valueOf(confirmed));
    row.put("rolled_back_quantity", BigDecimal.valueOf(rolledBack));
    return row;
  }

  /** 错误聚合不暴露看似已结清的明细，并保留原查询身份。 */
  private static void assertUnknown(List<Map<String, Object>> rows, String requestState) {
    var result = read(rows, requestState);
    assertEquals("order-proof", result.orderNo);
    assertEquals("UNKNOWN", result.status);
    assertTrue(result.items.isEmpty());
  }

  /** 六种Mapper或资源写入均不允许；这条GET只调用三个原无锁读方法。 */
  private static StockReservationVO read(List<Map<String, Object>> rows, String requestState) {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findSeckillByOrderNo("order-proof")).thenReturn(null);
    Map<String, Object> request = null;
    if (requestState != null) {
      request = new HashMap<>();
      request.put("order_no", "order-proof");
      request.put("scene", "NORMAL");
      request.put("status", requestState);
      String snapshot;
      try {
        var lines =
            rows.stream()
                .map(
                    row ->
                        new com.cloudmall.stock.domain.dto.StockLineDTO(
                            ((Number) row.get("sku_id")).longValue(),
                            ((Number) row.get("reserved_quantity")).intValue()))
                .sorted(java.util.Comparator.comparing(line -> line.skuId))
                .toList();
        snapshot = new ObjectMapper().writeValueAsString(lines);
      } catch (Exception invalidFixture) {
        snapshot = "[]";
      }
      request.put("items_snapshot", snapshot);
      try {
        request.put(
            "items_hash",
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }
    when(mapper.findReservationRequest("order-proof")).thenReturn(request);
    when(mapper.findReservationFlows("order-proof")).thenReturn(rows);
    StockServiceImpl service = new StockServiceImpl(null, mapper, null, new ObjectMapper());
    var report = (StockReservationVO) service.reservation("order-proof").data;
    verify(mapper).findSeckillByOrderNo("order-proof");
    verify(mapper).findReservationRequest("order-proof");
    verify(mapper).findReservationFlows("order-proof");
    verifyNoMoreInteractions(mapper);
    return report;
  }
}
