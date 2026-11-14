package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** 原订单身份查询、支付状态读取及幂等支付/取消动作的跨服务契约。 */
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

  /**
   * 支付恢复使用的订单事实；库存动作缺失不能自动解释为完成。
   *
   * @param orderNo 原订单号
   * @param status 原订单状态
   * @param payAmount 订单应付金额，按BigDecimal数值语义核验
   * @param userId 订单所属用户
   * @param stockActionStatus 订单对应的库存动作状态，响应缺失时保持null
   */
  record OrderView(
      String orderNo, String status, BigDecimal payAmount, Long userId, String stockActionStatus) {
    /** 兼容四参数构造调用，显式使用原SUCCEEDED默认值；远端JSON缺失字段仍保留null。 */
    public OrderView(String orderNo, String status, BigDecimal payAmount, Long userId) {
      this(orderNo, status, payAmount, userId, "SUCCEEDED");
    }
  }
}
