package com.cloudmall.cart.feign;

import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "cloud-mall-product")
public interface ProductClient {
  /** 根据原 SKU 主键读取商品身份、摘要和金额字符串。 */
  @GetMapping("/api/products/skus/{skuId}")
  ApiResponse<ProductSkuDTO> getSku(@PathVariable("skuId") Long skuId);
}
