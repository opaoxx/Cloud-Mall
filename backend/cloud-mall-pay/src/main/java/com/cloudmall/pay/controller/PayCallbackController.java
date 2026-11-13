package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.service.PayCallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付回调的原HTTP头/体绑定和响应包装。 */
@RestController
@RequestMapping("/api/payments")
public class PayCallbackController {
  /** 保持共享锁和持久绑定顺序的回调业务服务。 */
  private final PayCallbackService callbackService;

  /** 注入回调业务服务。 */
  public PayCallbackController(PayCallbackService callbackService) {
    this.callbackService = callbackService;
  }

  /**
   * 处理原支付回调，凭证由业务在原共享锁内核验。
   *
   * @param token 原可选内部凭证头
   * @param callback 原必需请求体
   * @return 原统一支付响应
   */
  @PostMapping("/callback")
  public ApiResponse<PayVO> callback(
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestBody PayCallbackDTO callback) {
    return ApiResponse.ok(callbackService.handle(token, callback));
  }
}
