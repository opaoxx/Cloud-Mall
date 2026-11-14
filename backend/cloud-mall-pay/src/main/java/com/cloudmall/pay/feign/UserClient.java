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

/** User余额扣款、退款及持久账本查询的原调用契约。 */
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

  /**
   * 支付服务向用户账本传输的扣款请求。
   *
   * @param operationKey 原扣款幂等操作键
   * @param payNo 扣款所属支付号
   * @param orderNo 支付所属订单号
   * @param amount 本次扣款金额
   */
  record DebitRequest(String operationKey, String payNo, String orderNo, BigDecimal amount) {}

  /**
   * 支付服务向用户账本传输的退款请求，绑定原扣款事实。
   *
   * @param refundOperationKey 本次退款幂等操作键
   * @param originalOperationKey 被退款的原扣款操作键
   * @param payNo 退款所属原支付号
   * @param orderNo 原订单号
   * @param amount 本次退款金额
   */
  record RefundRequest(
      String refundOperationKey,
      String originalOperationKey,
      String payNo,
      String orderNo,
      BigDecimal amount) {}

  /**
   * 用户账本写接口的稳定响应；方法返回不替代恢复流程所需的账本证明。
   *
   * @param operationKey 响应操作键，兼容原refundOperationKey JSON别名
   * @param state 用户端返回的操作状态
   * @param balance 用户端返回的余额金额字符串
   */
  record BalanceView(
      @JsonAlias("refundOperationKey") String operationKey, String state, String balance) {}

  /**
   * 用户账本只读查询响应，缺失字段保留原null语义。
   *
   * @param operationKey 被查询的原操作键
   * @param type 账本操作类型
   * @param state 账本操作状态
   * @param amount 账本记录的金额字符串
   * @param refundOperationKey 与原操作关联的退款键
   */
  record BalanceOperationView(
      String operationKey, String type, String state, String amount, String refundOperationKey) {}
}
