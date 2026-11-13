package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.service.PayQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户支付状态的只读HTTP入口。 */
@RestController
@RequestMapping("/api/payments")
public class PayQueryController {
  /** 按订单号和用户身份读取本地支付记录的业务服务。 */
  private final PayQueryService payQueryService;

  /**
   * 创建支付查询入口。
   *
   * @param payQueryService 本地支付查询服务
   */
  public PayQueryController(PayQueryService payQueryService) {
    this.payQueryService = payQueryService;
  }

  /**
   * 查询当前用户订单的支付状态。
   *
   * @param orderNo 请求路径中的原订单号
   * @return 原支付响应结构；未登录或无记录时抛出既有业务异常
   */
  @GetMapping("/orders/{orderNo}")
  public ApiResponse<PayVO> get(@PathVariable String orderNo) {
    // 1. 在任何支付查询之前读取当前用户身份。
    long userId = AuthContext.requireUserId();
    // 2. 委托只读业务并使用原统一响应包装。
    return ApiResponse.ok(payQueryService.getByOrderNo(orderNo, userId));
  }
}
