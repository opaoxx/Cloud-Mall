package com.cloudmall.order.util;

import com.cloudmall.order.domain.vo.OrderVO;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Map;

/** 列表与详情共享的纯订单行映射，不访问数据库或加载明细。 */
public final class OrderSummaryAssembler {
  /** 禁止实例化无状态映射工具。 */
  private OrderSummaryAssembler() {}

  /**
   * 按原数据库类型构造摘要，保留金额、字符串null和时间格式语义。
   *
   * @param row 已经完成相应查询身份/月份校验的订单原始行
   * @return 明细保持初始空列表的订单视图
   */
  public static OrderVO fromRow(Map<String, Object> row) {
    // 1. 按原数据库类型读取摘要，保留金额及字符串null语义。
    OrderVO order = new OrderVO();
    order.orderNo = String.valueOf(row.get("order_no"));
    order.userId = ((Number) row.get("user_id")).longValue();
    order.status = String.valueOf(row.get("status"));
    order.totalAmount = (BigDecimal) row.get("total_amount");
    order.payAmount = (BigDecimal) row.get("pay_amount");
    order.addressSnapshot = String.valueOf(row.get("address_snapshot"));
    // 2. 保持时间null和上海偏移格式；明细由原详情流程另行加载。
    order.createdAt = formatTimestamp(row.get("created_at"));
    order.expireAt = formatTimestamp(row.get("expire_at"));
    return order;
  }

  /** 保持原Timestamp instant、LocalDateTime本地字段及其他值的转换分支。 */
  private static String formatTimestamp(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.ofHours(8)).toString();
    }
    if (value instanceof java.time.LocalDateTime localDateTime) {
      return localDateTime.atOffset(ZoneOffset.ofHours(8)).toString();
    }
    return String.valueOf(value);
  }
}
