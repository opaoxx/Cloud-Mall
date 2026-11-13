package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只绑定内部取证请求并委托Service，不包含业务或数据库访问。 */
@RestController
@RequestMapping("/api/internal/payments")
public class PayTccReconciliationController {
  /** 入口门和只读报告服务。 */
  private final PayTccReconciliationService service;

  /** 创建取证Controller。 */
  public PayTccReconciliationController(PayTccReconciliationService service) {
    this.service = service;
  }

  /** 内部只读报告禁止缓存，不经Gateway暴露。 */
  @GetMapping("/{payNo}/tcc-reconciliation")
  public ResponseEntity<ApiResponse<PayTccReconciliationVO>> inspect(
      @PathVariable String payNo,
      @RequestHeader(value = "X-Internal-Service", required = false) String caller,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.inspect(payNo, caller, token)));
  }
}
