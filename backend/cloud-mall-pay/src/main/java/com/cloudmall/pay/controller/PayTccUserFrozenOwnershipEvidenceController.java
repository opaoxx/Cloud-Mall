package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayTccUserFrozenOwnershipReconciliationVO;
import com.cloudmall.pay.service.impl.evidence.PayTccUserFrozenOwnershipEvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 绑定内部User活跃冻结归属只读请求，业务规则与数据库访问归Service。 */
@RestController
@RequestMapping("/api/internal/payments")
public class PayTccUserFrozenOwnershipEvidenceController {
  /** 非事务取证门与报告服务。 */
  private final PayTccUserFrozenOwnershipEvidenceService service;

  /** 创建只委托Service的内部入口。 */
  public PayTccUserFrozenOwnershipEvidenceController(
      PayTccUserFrozenOwnershipEvidenceService service) {
    this.service = service;
  }

  /** 报告禁止缓存，不接受客户端覆盖原资金身份。 */
  @GetMapping("/{payNo}/tcc-reconciliation/user-frozen-ownership-evidence")
  public ResponseEntity<ApiResponse<PayTccUserFrozenOwnershipReconciliationVO>> inspect(
      @PathVariable String payNo,
      @RequestHeader(value = "X-Internal-Service", required = false) String caller,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.inspect(payNo, caller, token)));
  }

  /** 内部诊断的鉴权、缺证与不可用错误也禁止缓存，保留统一错误契约。 */
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Object>> failure(BizException failure) {
    return ResponseEntity.status(failure.getHttpStatus())
        .header("Cache-Control", "no-store")
        .body(ApiResponse.error(failure.getCode(), failure.getMessage()));
  }
}
