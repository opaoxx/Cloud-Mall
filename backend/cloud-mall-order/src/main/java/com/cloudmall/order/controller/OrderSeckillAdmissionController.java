package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.service.OrderSeckillAdmissionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 秒杀受理请求绑定与身份读取，完整透传Stock受理响应。 */
@RestController
@RequestMapping("/api")
public class OrderSeckillAdmissionController {
  /** 负责限流、快照和一次远端受理调用的业务契约。 */
  private final OrderSeckillAdmissionService admissionService;

  /** 注入秒杀受理业务服务。 */
  public OrderSeckillAdmissionController(OrderSeckillAdmissionService admissionService) {
    this.admissionService = admissionService;
  }

  /** 保留原必填请求头/请求体，鉴权早于Service的业务参数校验。 */
  @PostMapping("/seckill/orders")
  public ApiResponse<?> seckill(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SeckillOrderDTO request) {
    return admissionService.submit(idempotencyKey, request, AuthContext.requireUserId());
  }
}
