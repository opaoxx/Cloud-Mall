package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name="cloud-mall-stock")
public interface StockClient {
    @PostMapping("/api/stock/reservations") ApiResponse<?> reserve(@RequestBody Reservation request);
    @PostMapping("/api/stock/reservations/{orderNo}/confirm") ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo);
    @PostMapping("/api/stock/reservations/{orderNo}/rollback") ApiResponse<?> rollback(@PathVariable("orderNo") String orderNo);
    @PostMapping("/api/stock/seckill/reservations") ApiResponse<?> seckill(@RequestBody Map<String,Object> request);
    record Reservation(String orderNo, List<Line> items, String scene) {}
    record Line(Long skuId, int quantity) {}
}
