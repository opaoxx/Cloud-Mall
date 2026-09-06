package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-cart")
public interface CartClient {
  @DeleteMapping("/api/cart/items/{skuId}")
  ApiResponse<?> deleteItem(
      @PathVariable("skuId") Long skuId, @RequestHeader("X-User-Id") Long userId);
}
