package com.cloudmall.product.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.LinkedHashMap;
import java.util.Map;

/** 商品 SKU 传输对象。 */
public class SkuDTO {
  /** SKU 主键。 */
  public Long id;

  /** 所属商品主键。 */
  @JsonSerialize(using = ToStringSerializer.class)
  public Long productId;

  /** SKU 编码。 */
  public String skuCode;

  /** SKU 价格字符串。 */
  public String price;

  /** SKU 规格 JSON 对象。 */
  public Map<String, String> specJson = new LinkedHashMap<>();

  /** SKU 状态。 */
  public boolean status;

  /** 创建空 SKU 请求。 */
  public SkuDTO() {}

  /** 创建商品查询使用的 SKU 对象。 */
  public SkuDTO(
      Long id,
      Long productId,
      String skuCode,
      Map<String, String> specJson,
      String price,
      boolean status) {
    this.id = id;
    this.productId = productId;
    this.skuCode = skuCode;
    this.specJson = specJson;
    this.price = price;
    this.status = status;
  }
}
