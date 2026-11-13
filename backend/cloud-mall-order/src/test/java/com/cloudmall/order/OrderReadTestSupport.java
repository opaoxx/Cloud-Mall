package com.cloudmall.order;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.controller.OrderDetailQueryController;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderDetailQueryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;

/** 既有混合写夹具与新读取入口共用同一组测试依赖，不替代生产算法。 */
final class OrderReadTestSupport {
  private OrderReadTestSupport() {}

  static OrderDetailQueryController controller(OrderTestService source) {
    return new OrderDetailQueryController(
        new OrderDetailQueryServiceImpl(
            (OrderSqlMapper) ReflectionTestUtils.getField(source, "orderSqlMapper"),
            (ObjectMapper) ReflectionTestUtils.getField(source, "mapper"),
            (OrderStockActionMapper) ReflectionTestUtils.getField(source, "orderStockActionMapper"),
            (StockClient) ReflectionTestUtils.getField(source, "stock")));
  }

  /** 仅投影实际VO以保留原大规模状态矩阵断言；真实JSON由独立MVC契约测试验证。 */
  static ApiResponse<Map<String, Object>> paymentState(
      OrderTestService source, String orderNo, Long userId) {
    var result = controller(source).paymentState(orderNo, userId).data;
    return ApiResponse.ok(
        Map.of(
            "orderNo",
            result.orderNo,
            "status",
            result.status,
            "payAmount",
            result.payAmount,
            "userId",
            result.userId,
            "stockActionStatus",
            result.stockActionStatus));
  }
}
