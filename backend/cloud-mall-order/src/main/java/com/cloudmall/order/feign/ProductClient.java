package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "cloud-mall-product")
public interface ProductClient {
  @GetMapping("/api/products/skus/{skuId}")
  ApiResponse<SkuView> sku(@PathVariable("skuId") Long skuId);

  record SkuView(
      Long skuId,
      Long productId,
      String productName,
      String skuCode,
      BigDecimal unitPrice,
      Map<String, String> skuSnapshot) {}
}
