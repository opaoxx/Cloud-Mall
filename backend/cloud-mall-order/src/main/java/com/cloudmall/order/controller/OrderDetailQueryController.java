package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.vo.OrderPaymentStateVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.service.OrderDetailQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单订单查询的HTTP绑定和延迟身份来源，不承载读取/库存证明规则。 */
@RestController
@RequestMapping("/api")
public class OrderDetailQueryController {
  /** 保持原读取顺序与归属校验的查询服务。 */
  private final OrderDetailQueryService queryService;

  /** 注入详情及支付状态查询契约。 */
  public OrderDetailQueryController(OrderDetailQueryService queryService) {
    this.queryService = queryService;
  }

  /** 读取当前用户的原订单详情，身份值在Service原位置读取。 */
  @GetMapping("/orders/{orderNo}")
  public ApiResponse<OrderVO> get(@PathVariable("orderNo") String orderNo) {
    return ApiResponse.ok(queryService.get(orderNo, AuthContext::requireUserId));
  }

  /** 只读查询支付恢复状态，显式用户头优先于上下文。 */
  @GetMapping("/internal/orders/{orderNo}/payment-state")
  public ApiResponse<OrderPaymentStateVO> paymentState(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long callerUserId) {
    return ApiResponse.ok(
        queryService.paymentState(
            orderNo, callerUserId == null ? AuthContext::requireUserId : () -> callerUserId));
  }
}
