package com.cloudmall.product.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 商品参数传输对象。 */
public class ProductParameterDTO {
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

  /** 创建空参数请求。 */
  public ProductParameterDTO() {}

  /** 创建商品查询使用的参数对象。 */
  public ProductParameterDTO(Long id, Long productId, String name, String value, int sortNo) {
    this.id = id;
    this.productId = productId;
    this.name = name;
    this.value = value;
    this.sortNo = sortNo;
  }
}
