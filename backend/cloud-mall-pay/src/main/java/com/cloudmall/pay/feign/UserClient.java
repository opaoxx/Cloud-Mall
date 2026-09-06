package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-user")
public interface UserClient {
  @PostMapping("/api/internal/users/{userId}/balance/debit")
  ApiResponse<BalanceView> debit(
      @PathVariable("userId") Long userId,
      @RequestHeader("X-User-Id") Long callerId,
      @RequestBody DebitRequest request);

  record DebitRequest(String paymentKey, BigDecimal amount) {}

  record BalanceView(String balance) {}
}
