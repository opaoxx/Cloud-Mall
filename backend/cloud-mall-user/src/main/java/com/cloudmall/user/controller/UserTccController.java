package com.cloudmall.user.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.service.impl.tcc.UserTccHttpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 仅绑定内部请求并委托服务；不暴露 Confirm/Cancel。 */
@RestController
@RequestMapping("/api/internal/users/{userId}/balance/tcc-debits")
@ConditionalOnProperty(name = "cloudmall.user.tcc-http.enabled", havingValue = "true")
public class UserTccController {
  /** 认证、接收和资金查询入口。 */
  private final UserTccHttpService bridge;

  /** 创建内部控制器。 */
  public UserTccController(UserTccHttpService bridge) {
    this.bridge = bridge;
  }

  /** 内部 Try；重复 POST 不能再注册分支。 */
  @PostMapping("/try")
  public ApiResponse<UserTccStateVO> prepare(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @RequestHeader(value = "X-Internal-Service", required = false) String service,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestHeader(value = "TX_XID", required = false) String xid,
      @RequestBody UserTccTryDTO request) {
    return ApiResponse.ok(bridge.prepare(userId, callerId, service, token, xid, request));
  }

  /** 按原操作键与精确 XID 读取状态，不改变任何记录。 */
  @GetMapping("/{operationKey}")
  public ApiResponse<UserTccStateVO> state(
      @PathVariable Long userId,
      @PathVariable String operationKey,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @RequestHeader(value = "X-Internal-Service", required = false) String service,
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestParam String xid) {
    return ApiResponse.ok(bridge.state(userId, callerId, service, token, operationKey, xid));
  }
}
