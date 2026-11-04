package com.cloudmall.product.domain.vo;

import com.cloudmall.product.domain.dto.SkuDTO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.LinkedHashMap;
import java.util.Map;

/** 商品 SKU 响应视图。 */
public class SkuVO {
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

  /** 创建空 SKU 响应。 */
  public SkuVO() {}

  /** 创建商品查询使用的 SKU 对象。 */
  public SkuVO(
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

  /** 按字段保留原请求写回值的响应表示。 */
  public static SkuVO from(SkuDTO request) {
    SkuVO response = new SkuVO();
    response.id = request.id;
    response.productId = request.productId;
    response.skuCode = request.skuCode;
    response.price = request.price;
    response.specJson = request.specJson;
    response.status = request.status;
    return response;
  }
}
