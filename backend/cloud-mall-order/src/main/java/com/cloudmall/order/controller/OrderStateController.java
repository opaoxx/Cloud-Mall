package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.service.OrderStateService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订单状态请求的原绑定、延迟身份来源与响应装配。 */
@RestController
@RequestMapping("/api")
public class OrderStateController {
  /** 承担原状态规则及事务/锁边界的服务代理。 */
  private final OrderStateService stateService;

  /** 注入状态业务契约。 */
  public OrderStateController(OrderStateService stateService) {
    this.stateService = stateService;
  }

  /** 保留取消入口的可选用户头优先语义，不新增请求体绑定。 */
  @PostMapping("/orders/{orderNo}/cancel")
  public ApiResponse<OrderVO> cancel(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
    return ApiResponse.ok(
        stateService.cancel(
            orderNo, headerUser == null ? AuthContext::requireUserId : () -> headerUser));
  }

  /** 在原详情读取后从认证上下文确认用户，事务完成后包装支付状态结果。 */
  @PostMapping("/orders/{orderNo}/paid")
  public ApiResponse<OrderVO> paid(@PathVariable("orderNo") String orderNo) {
    return ApiResponse.ok(stateService.paid(orderNo, AuthContext::requireUserId));
  }

  /** 保留原收货确认入口，不把HTTP层变成新的事务或锁边界。 */
  @PostMapping("/orders/{orderNo}/confirm")
  public ApiResponse<OrderVO> confirm(@PathVariable("orderNo") String orderNo) {
    return ApiResponse.ok(stateService.confirm(orderNo, AuthContext::requireUserId));
  }
}
