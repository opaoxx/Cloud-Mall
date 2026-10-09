package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.vo.PayTccOrderReconciliationVO;
import com.cloudmall.pay.service.impl.PayTccOrderEvidenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部Pay/Order只读证据入口，只绑定参数并委托Service。 */
@RestController
@RequestMapping("/api/internal/payments")
public class PayTccOrderEvidenceController {
  /** 认证、两次快照和Order只读查询编排。 */
  private final PayTccOrderEvidenceService service;

  /** 创建无数据库操作的Controller。 */
  public PayTccOrderEvidenceController(PayTccOrderEvidenceService service) {
    this.service = service;
  }

  /** 报告禁止缓存；Gateway必须拒绝此内部路径。 */
  @GetMapping("/{payNo}/tcc-reconciliation/order-evidence")
  public ResponseEntity<ApiResponse<PayTccOrderReconciliationVO>> inspect(
      @PathVariable String payNo,
      @RequestHeader(value = "X-Internal-Service", required = false) String caller,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.inspect(payNo, caller, token)));
  }
}
