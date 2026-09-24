package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-order")
public interface OrderClient {
  /** 查询订单归属与金额，供创建支付记录使用。 */
  @GetMapping("/api/orders/{orderNo}")
  ApiResponse<OrderView> get(
      @PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);

  /** 查询订单当前支付状态，结果未知时不得猜测失败。 */
  @GetMapping("/api/internal/orders/{orderNo}/payment-state")
  ApiResponse<OrderView> paymentState(
      @PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);

  /** 幂等同步订单支付成功并确认库存。 */
  @PostMapping("/api/orders/{orderNo}/paid")
  ApiResponse<OrderView> paid(
      @PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);

  /** 仅待支付订单可取消并回滚库存。 */
  @PostMapping("/api/orders/{orderNo}/cancel")
  ApiResponse<OrderView> cancel(
      @PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);

  record OrderView(String orderNo, String status, BigDecimal payAmount, Long userId) {}
}
