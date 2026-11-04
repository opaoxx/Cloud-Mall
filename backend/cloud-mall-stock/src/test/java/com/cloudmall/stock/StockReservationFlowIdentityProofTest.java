package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 聚合数量相同也不能替代原订单字节身份及每条原流水的合法性。 */
class StockReservationFlowIdentityProofTest {
  @Test
  void aliasOrMixedOriginalIdentitiesAreNotCurrentOrderEvidence() {
    for (String identity : List.of("ORDER-PROOF", "order-proof ", "other")) {
      var row = flow();
      row.put("original_order_no", identity);
      assertUnknown(row);
    }
    var mixed = flow();
    mixed.put("order_identity_count", 2L);
    assertUnknown(mixed);
  }

  @Test
  void hiddenInvalidRawRowsCannotBeIgnoredWhenAggregateCountsLookComplete() {
    for (Number count : List.of(1L, BigDecimal.valueOf(2), -1L)) {
      var row = flow();
      row.put("invalid_flow_count", count);
      assertUnknown(row);
    }
  }

  @Test
  void missingMalformedAndOverCapacityMetadataNeverDefaultToTrusted() {
    for (String field :
        List.of("original_order_no", "order_identity_count", "invalid_flow_count")) {
      var missing = flow();
      missing.remove(field);
      assertUnknown(missing);
      var empty = flow();
      empty.put(field, null);
      assertUnknown(empty);
    }
    for (String field : List.of("order_identity_count", "invalid_flow_count"))
      for (Object value :
          List.of("1", 1.0d, new BigDecimal("0.5"), new BigDecimal("9223372036854775808"))) {
        var row = flow();
        row.put(field, value);
        assertUnknown(row);
      }
  }

  @Test
  void exactOriginalIdentityAndZeroInvalidRowsKeepHealthyLegacyEvidence() {
    var report = read(flow());
    assertEquals("CONFIRMED", report.status);
    assertEquals(1, report.items.size());
    assertEquals(2, report.items.get(0).quantity);
    assertEquals("order-proof", report.orderNo);
  }

  /** 普通旧流水的完整健康聚合及真实来源辅助字段。 */
  private static Map<String, Object> flow() {
    Map<String, Object> row = new HashMap<>();
    row.put("sku_id", 7L);
    row.put("reserved_quantity", BigDecimal.valueOf(2));
    row.put("confirmed_quantity", BigDecimal.valueOf(2));
    row.put("rolled_back_quantity", BigDecimal.ZERO);
    row.put("original_order_no", "order-proof");
    row.put("order_identity_count", 1L);
    row.put("invalid_flow_count", BigDecimal.ZERO);
    return row;
  }

  /** 不可信来源输出UNKNOWN，且不回显别单原标识。 */
  private static void assertUnknown(Map<String, Object> row) {
    var report = read(row);
    assertEquals("UNKNOWN", report.status);
    assertTrue(report.items.isEmpty());
    assertEquals("order-proof", report.orderNo);
  }

  /** 原三次Mapper读调用，保持源Map不变且禁止写调用。 */
  private static StockReservationVO read(Map<String, Object> row) {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findSeckillByOrderNo("order-proof")).thenReturn(null);
    when(mapper.findReservationRequest("order-proof")).thenReturn(null);
    when(mapper.findReservationFlows("order-proof")).thenReturn(List.of(row));
    var before = new HashMap<>(row);
    var result =
        (StockReservationVO)
            StockTestServices.reader(mapper, new ObjectMapper()).reservation("order-proof");
    assertEquals(before, row);
    verify(mapper).findSeckillByOrderNo("order-proof");
    verify(mapper).findReservationRequest("order-proof");
    verify(mapper).findReservationFlows("order-proof");
    verifyNoMoreInteractions(mapper);
    return result;
  }
}
