package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-user")
public interface UserClient {
  /** 用支付号作为持久幂等键扣款。 */
  @PostMapping("/api/internal/users/{userId}/balance/debits")
  ApiResponse<BalanceView> debit(
      @PathVariable("userId") Long userId,
      @RequestHeader("X-User-Id") Long callerId,
      @RequestBody DebitRequest request);

  /** 查询扣款或退款在用户账本中的最终结果。 */
  @GetMapping("/api/internal/users/{userId}/balance/operations/{operationKey}")
  ApiResponse<BalanceOperationView> operation(
      @PathVariable("userId") Long userId,
      @PathVariable("operationKey") String operationKey,
      @RequestHeader("X-User-Id") Long callerId);

  /** 对已应用扣款执行同键只一次的本地余额退款。 */
  @PostMapping("/api/internal/users/{userId}/balance/refunds")
  ApiResponse<BalanceView> refund(
      @PathVariable("userId") Long userId,
      @RequestHeader("X-User-Id") Long callerId,
      @RequestBody RefundRequest request);

  /** 支付服务向用户账本传输的扣款请求。 */
  record DebitRequest(String operationKey, String payNo, String orderNo, BigDecimal amount) {}

  /** 支付服务向用户账本传输的退款请求。 */
  record RefundRequest(
      String refundOperationKey,
      String originalOperationKey,
      String payNo,
      String orderNo,
      BigDecimal amount) {}

  /** 用户账本写接口的稳定响应。 */
  record BalanceView(
      @JsonAlias("refundOperationKey") String operationKey, String state, String balance) {}

  /** 用户账本只读查询响应。 */
  record BalanceOperationView(
      String operationKey,
      String type,
      String state,
      String amount,
      String refundOperationKey) {}
}
