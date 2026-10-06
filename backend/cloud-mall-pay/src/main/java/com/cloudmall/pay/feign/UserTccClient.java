package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.config.UserTccFeignConfiguration;
import com.cloudmall.pay.domain.dto.UserTccTryDTO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Pay 到 User 的一次性 Try 与只读状态客户端。 */
@FeignClient(
    name = "cloud-mall-user",
    contextId = "userTccClient",
    configuration = UserTccFeignConfiguration.class)
public interface UserTccClient {
  /** 发出唯一一次 User TCC Try POST。 */
  @PostMapping("/api/internal/users/{userId}/balance/tcc-debits/try")
  ApiResponse<UserTccStateVO> prepare(
      @PathVariable("userId") Long userId,
      @RequestHeader("X-User-Id") Long callerId,
      @RequestHeader("X-Internal-Service") String service,
      @RequestHeader("X-Internal-Callback-Token") String token,
      @RequestHeader("TX_XID") String xid,
      @RequestBody UserTccTryDTO request);

  /** 读取 User TCC 持久化状态，不注册或重试分支。 */
  @GetMapping("/api/internal/users/{userId}/balance/tcc-debits/{operationKey}")
  ApiResponse<UserTccStateVO> state(
      @PathVariable("userId") Long userId,
      @PathVariable("operationKey") String operationKey,
      @RequestHeader("X-User-Id") Long callerId,
      @RequestHeader("X-Internal-Service") String service,
      @RequestHeader("X-Internal-Callback-Token") String token,
      @RequestParam("xid") String xidParam);
}
