package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.service.PayFailureService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模拟支付失败的路径绑定、身份读取和响应包装。 */
@RestController
@RequestMapping("/api/payments")
public class PayFailureController {
  /** 无新增外层事务或锁的失败业务入口。 */
  private final PayFailureService failureService;

  /** 注入失败业务服务。 */
  public PayFailureController(PayFailureService failureService) {
    this.failureService = failureService;
  }

  /**
   * 按原路径处理失败请求；请求体保持原未绑定、未校验行为。
   *
   * @param payNo 原路径支付单号
   * @return 原协议失败处理的响应
   */
  @PostMapping("/{payNo}/mock-fail")
  public ApiResponse<PayVO> fail(@PathVariable String payNo) {
    long userId = AuthContext.requireUserId();
    return ApiResponse.ok(failureService.fail(payNo, userId));
  }
}
