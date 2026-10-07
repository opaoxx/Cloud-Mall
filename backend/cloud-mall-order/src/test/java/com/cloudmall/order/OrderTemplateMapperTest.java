package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 模板优先级、元数据失败与月份 DDL 输入边界。 */
class OrderTemplateMapperTest {
  /** 验证 Mapper 数据库交互范围的替身。 */
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  /** 被测真实模板/建表 Mapper。 */
  private final OrderSqlMapper mapper = new OrderSqlMapper(jdbc);

  @Test
  void existingTemplatesNeverAccessLegacySourcesOrIssueDdl() {
    when(jdbc.queryForList(anyString(), eq(String.class), anyString()))
        .thenReturn(List.of("BASE TABLE"));
    mapper.ensureMonthlyCompanionTemplates();
    verify(jdbc).queryForList(anyString(), eq(String.class), eq("mall_order_item_template"));
    verify(jdbc).queryForList(anyString(), eq(String.class), eq("order_status_log_template"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void missingTemplatesCloneOnlyTheirOwnKnownLegacySource() {
    when(jdbc.queryForList(anyString(), eq(String.class), anyString()))
        .thenAnswer(
            invocation ->
                invocation.getArgument(2, String.class).endsWith("_template")
                    ? List.of()
                    : List.of("BASE TABLE"));
    mapper.ensureMonthlyCompanionTemplates();
    verify(jdbc)
        .execute("CREATE TABLE IF NOT EXISTS mall_order_item_template LIKE mall_order_item_202608");
    verify(jdbc)
        .execute(
            "CREATE TABLE IF NOT EXISTS order_status_log_template LIKE order_status_log_202608");
  }

  @Test
  void missingBothSourcesFailsWithoutDdl() {
    when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of());
    assertEquals(
        "ORDER_TEMPLATE_UNAVAILABLE: mall_order_item_template",
        assertThrows(
                DataAccessResourceFailureException.class, mapper::ensureMonthlyCompanionTemplates)
            .getMessage());
    verify(jdbc, never()).execute(anyString());
  }

  @Test
  void metadataFailureOrViewCannotBeTreatedAsUsableTemplate() {
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("metadata unavailable");
    when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenThrow(failure);
    assertSame(
        failure,
        assertThrows(
            DataAccessResourceFailureException.class, mapper::ensureMonthlyCompanionTemplates));
    reset(jdbc);
    when(jdbc.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of("VIEW"));
    assertThrows(DataAccessResourceFailureException.class, mapper::ensureMonthlyCompanionTemplates);
    verify(jdbc, never()).execute(anyString());
  }

  @Test
  void invalidMonthSuffixIsRejectedBeforeAnySql() {
    for (String suffix :
        new String[] {
          null,
          "",
          "202613",
          "202600",
          "099912",
          "1000010",
          "2027-01",
          "202701;DROP TABLE x",
          "20270x"
        }) {
      assertThrows(IllegalArgumentException.class, () -> mapper.createMonthlyOrderTables(suffix));
    }
    verifyNoInteractions(jdbc);
  }

  @Test
  void validYearBoundaryCreatesExactlyThreeFixedCompanionTables() {
    mapper.createMonthlyOrderTables("202701");
    var ordered = inOrder(jdbc);
    ordered
        .verify(jdbc)
        .execute("CREATE TABLE IF NOT EXISTS mall_order_202701 LIKE mall_order_template");
    ordered
        .verify(jdbc)
        .execute("CREATE TABLE IF NOT EXISTS mall_order_item_202701 LIKE mall_order_item_template");
    ordered
        .verify(jdbc)
        .execute(
            "CREATE TABLE IF NOT EXISTS order_status_log_202701 LIKE order_status_log_template");
    ordered.verifyNoMoreInteractions();
  }
}
