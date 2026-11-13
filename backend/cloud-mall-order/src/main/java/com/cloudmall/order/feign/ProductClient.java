package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "cloud-mall-product")
public interface ProductClient {
  /**
   * 读取Product提供的原SKU事实，身份、状态码和价格资格由订单业务继续核验。
   *
   * @param skuId 原SKU标识
   * @return 包含原商品快照的响应信封
   */
  @GetMapping("/api/products/skus/{skuId}")
  ApiResponse<SkuView> sku(@PathVariable("skuId") Long skuId);

  /**
   * Product返回的客户端局部不可变SKU事实，不在此类型中补值或验证业务资格。
   *
   * @param skuId 原SKU身份
   * @param productId 所属商品身份
   * @param productName 原商品名称
   * @param skuCode 原SKU编码
   * @param unitPrice 原单价，金额资格由订单业务核验
   * @param skuSnapshot 原SKU规格快照
   */
  record SkuView(
      Long skuId,
      Long productId,
      String productName,
      String skuCode,
      BigDecimal unitPrice,
      Map<String, String> skuSnapshot) {}
}
