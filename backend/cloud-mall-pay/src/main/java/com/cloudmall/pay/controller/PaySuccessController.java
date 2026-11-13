package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.service.PaySuccessService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模拟支付成功的请求绑定与响应包装。 */
@RestController
@RequestMapping("/api/payments")
public class PaySuccessController {
  /** 保留原共享锁和协议路由的业务服务。 */
  private final PaySuccessService successService;

  /** 注入模拟成功业务服务。 */
  public PaySuccessController(PaySuccessService successService) {
    this.successService = successService;
  }

  /**
   * 按原路径处理模拟成功请求，在业务获取锁后才读取认证身份。
   *
   * @param payNo 原路径支付单号
   * @return 原成功响应；未知或失败仍由业务异常映射
   */
  @PostMapping("/{payNo}/mock-success")
  public ApiResponse<PayVO> success(@PathVariable String payNo) {
    return ApiResponse.ok(successService.succeed(payNo, AuthContext::requireUserId));
  }
}
