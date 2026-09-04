package com.cloudmall.pay;

import com.cloudmall.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;

@FeignClient(name="cloud-mall-order")
public interface OrderClient {
    @GetMapping("/api/orders/{orderNo}") ApiResponse<OrderView> get(@PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);
    @PostMapping("/api/orders/{orderNo}/paid") ApiResponse<OrderView> paid(@PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);
    @PostMapping("/api/orders/{orderNo}/cancel") ApiResponse<OrderView> cancel(@PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);
    record OrderView(String orderNo, String status, BigDecimal payAmount, Long userId) {}
}
