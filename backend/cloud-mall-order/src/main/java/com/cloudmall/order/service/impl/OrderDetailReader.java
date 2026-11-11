package com.cloudmall.order.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.util.OrderSummaryAssembler;
import com.cloudmall.order.util.OrderTableRouting;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 包内共享详情读取算法；使用调用方既有Mapper/JSON配置，不创建事务或读取身份。 */
final class OrderDetailReader {
  /** 禁止实例化无状态读取组件。 */
  private OrderDetailReader() {}

  /** 在映射视图和读取明细前绑定请求号与主表原身份，错单候选沿原404拒绝。 */
  static OrderVO read(OrderSqlMapper orderSqlMapper, ObjectMapper objectMapper, String orderNo) {
    // 1. 沿原格式/月校验查询主表原始候选，不改变数据库比较规则。
    String orderTable = OrderTableRouting.tableFromOrderNo(orderNo);
    List<Map<String, Object>> orderRows = orderSqlMapper.findOrder(orderTable, orderNo);
    // 2. 无行或身份不精确时拒绝，禁止借别名继续明细、鉴权或后续状态操作。
    if (orderRows.isEmpty() || !Objects.equals(orderNo, orderRows.get(0).get("order_no")))
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    // 3. 身份有效后保持原视图和明细顺序，调用方继续原鉴权与业务流程。
    List<OrderVO> orders = orderRows.stream().map(OrderSummaryAssembler::fromRow).toList();
    OrderVO order = orders.get(0);
    order.items =
        orderSqlMapper
            .findOrderItems(orderTable.replace("mall_order_", "mall_order_item_"), orderNo)
            .stream()
            .map(row -> toItem(objectMapper, row))
            .toList();
    return order;
  }

  /** 将订单明细数据库行转换为明细响应视图。 */
  private static OrderItemVO toItem(ObjectMapper objectMapper, Map<String, Object> row) {
    return new OrderItemVO(
        ((Number) row.get("product_id")).longValue(),
        ((Number) row.get("sku_id")).longValue(),
        String.valueOf(row.get("product_name_snapshot")),
        readSnapshot(objectMapper, (String) row.get("sku_snapshot")),
        (BigDecimal) row.get("unit_price"),
        ((Number) row.get("quantity")).intValue(),
        (BigDecimal) row.get("line_amount"));
  }

  /** 保留null快照为空Map、坏JSON转换为原INTERNAL错误的规则。 */
  private static Map<String, String> readSnapshot(ObjectMapper objectMapper, String value) {
    try {
      return value == null
          ? Map.of()
          : objectMapper.readValue(value, new TypeReference<Map<String, String>>() {});
    } catch (Exception invalidSnapshot) {
      throw new BizException(ErrorCodes.INTERNAL, "SKU快照读取失败", 500);
    }
  }
}
