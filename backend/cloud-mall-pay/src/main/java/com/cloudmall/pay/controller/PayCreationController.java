package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.service.PayCreationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付创建请求绑定和响应包装，业务及事务由创建服务负责。 */
@RestController
@RequestMapping("/api/payments")
public class PayCreationController {
  /** 包含原事务和幂等规则的创建业务代理。 */
  private final PayCreationService creationService;

  /**
   * 注入支付创建服务。
   *
   * @param creationService 实际创建事务代理
   */
  public PayCreationController(PayCreationService creationService) {
    this.creationService = creationService;
  }

  /**
   * 创建或重放当前用户支付；身份读取延迟至原加锁位置。
   *
   * @param idempotencyKey 原请求头中的幂等键
   * @param request 原请求体
   * @return 创建事务成功后的支付响应
   */
  @PostMapping
  public ApiResponse<PayVO> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody PayCreateDTO request) {
    return ApiResponse.ok(
        creationService.create(idempotencyKey, request, AuthContext::requireUserId));
  }
}
