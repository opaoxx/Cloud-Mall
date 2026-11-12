package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.service.OrderCreationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 普通下单HTTP绑定和统一响应装配。 */
@RestController
@RequestMapping("/api")
public class OrderCreationController {
  /** 承担创建事务及原幂等流程的业务代理。 */
  private final OrderCreationService creationService;

  /** 注入独立的创建服务。 */
  public OrderCreationController(OrderCreationService creationService) {
    this.creationService = creationService;
  }

  /** 保持原请求头/请求体绑定，身份由业务在原加锁位置读取，事务成功后包装。 */
  @PostMapping("/orders")
  public ApiResponse<OrderVO> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody CreateOrderDTO request) {
    return ApiResponse.ok(creationService.create(key, request, AuthContext::requireUserId));
  }
}
