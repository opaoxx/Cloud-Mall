package com.cloudmall.cart.feign;

import com.cloudmall.common.api.ApiResponse;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "cloud-mall-product")
public interface ProductClient {
  /** 根据原 SKU 主键读取商品身份、摘要和金额字符串。 */
  @GetMapping("/api/products/skus/{skuId}")
  ApiResponse<SkuView> getSku(@PathVariable("skuId") Long skuId);

  /**
   * 商品服务返回的 SKU 事实。
   *
   * @param skuId SKU 主键
   * @param productId 商品主键
   * @param productName 商品名称
   * @param unitPrice 原始单价字符串
   * @param skuSnapshot SKU 规格快照
   */
  record SkuView(
      Long skuId,
      Long productId,
      String productName,
      String unitPrice,
      Map<String, String> skuSnapshot) {}
}
