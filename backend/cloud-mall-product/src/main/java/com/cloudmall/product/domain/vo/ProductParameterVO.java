package com.cloudmall.product.domain.vo;

import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 商品参数响应视图。 */
public class ProductParameterVO {
  /** 参数主键。 */
  public Long id;

  /** 所属商品主键。 */
  @JsonSerialize(using = ToStringSerializer.class)
  public Long productId;

  /** 参数名称。 */
  public String name;

  /** 参数值。 */
  public String value;

  /** 展示顺序。 */
  public int sortNo;

  /** 创建空参数响应。 */
  public ProductParameterVO() {}

  /** 创建商品查询使用的参数对象。 */
  public ProductParameterVO(Long id, Long productId, String name, String value, int sortNo) {
    this.id = id;
    this.productId = productId;
    this.name = name;
    this.value = value;
    this.sortNo = sortNo;
  }

  /** 按字段保留原请求写回值的响应表示。 */
  public static ProductParameterVO from(ProductParameterDTO request) {
    ProductParameterVO response = new ProductParameterVO();
    response.id = request.id;
    response.productId = request.productId;
    response.name = request.name;
    response.value = request.value;
    response.sortNo = request.sortNo;
    return response;
  }
}
