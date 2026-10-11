package com.cloudmall.user.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 绑定内部只读诊断请求，不接受原资金键或XID覆盖，不包含数据库访问。 */
@RestController
@RequestMapping("/api/internal/users/{userId}/balance/tcc-reconciliation")
public class UserTccReconciliationController {
  /** 默认关闭的认证与只读报告服务。 */
  private final UserTccReconciliationService service;

  /** 创建只委托Service的内部Controller。 */
  public UserTccReconciliationController(UserTccReconciliationService service) {
    this.service = service;
  }

  /** 成功报告禁止缓存，只绑定本批userId/payNo与运维凭证。 */
  @GetMapping("/{payNo}")
  public ResponseEntity<ApiResponse<UserTccReconciliationVO>> inspect(
      @PathVariable Long userId,
      @PathVariable String payNo,
      @RequestHeader(value = "X-Internal-Service", required = false) String caller,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(service.inspect(userId, payNo, caller, token)));
  }

  /** 参数、鉴权、冲突与不可用响应保持统一错误字段并禁止缓存。 */
  @ExceptionHandler(BizException.class)
  public ResponseEntity<ApiResponse<Object>> failure(BizException failure) {
    return ResponseEntity.status(failure.getHttpStatus())
        .header("Cache-Control", "no-store")
        .body(ApiResponse.error(failure.getCode(), failure.getMessage()));
  }

  /** 非数字或溢出的用户主键不进入Service，按固定400处理且禁止缓存。 */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Object>> invalidBinding(
      MethodArgumentTypeMismatchException failure) {
    return ResponseEntity.badRequest()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.error("COMMON_INVALID_ARGUMENT", "资金查询范围不合法"));
  }
}
