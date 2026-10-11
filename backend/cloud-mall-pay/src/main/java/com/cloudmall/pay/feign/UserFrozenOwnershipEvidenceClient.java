package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.dto.UserFrozenOwnershipEvidenceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** 仅有GET的User活跃冻结归属诊断客户端，沿3s/5s与Spring默认不重试。 */
@FeignClient(name = "cloud-mall-user", contextId = "userFrozenOwnershipEvidenceClient")
public interface UserFrozenOwnershipEvidenceClient {
  /** 只按原Pay锚点userId/payNo读取，不传资金XID/分支/写参数。 */
  @GetMapping(
      "/api/internal/users/{userId}/balance/tcc-reconciliation/{payNo}/frozen-ownership-evidence")
  ApiResponse<UserFrozenOwnershipEvidenceDTO> inspect(
      @PathVariable("userId") Long userId,
      @PathVariable("payNo") String payNo,
      @RequestHeader("X-Internal-Service") String caller,
      @RequestHeader("X-Internal-Callback-Token") String token);
}
