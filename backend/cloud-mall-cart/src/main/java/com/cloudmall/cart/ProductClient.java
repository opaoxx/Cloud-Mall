package com.cloudmall.cart;

import com.cloudmall.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "cloud-mall-product")
public interface ProductClient {
    @GetMapping("/api/products/skus/{skuId}") ApiResponse<SkuView> getSku(@PathVariable("skuId") Long skuId);
    record SkuView(Long skuId, Long productId, String productName, String unitPrice,
                   Map<String, String> skuSnapshot) {}
}
