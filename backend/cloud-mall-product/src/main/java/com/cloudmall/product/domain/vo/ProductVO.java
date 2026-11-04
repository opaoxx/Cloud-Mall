package com.cloudmall.product.domain.vo;

import com.cloudmall.product.domain.dto.ProductDTO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.ArrayList;
import java.util.List;

/** 商品新增、修改和展示过程中的响应视图。 */
public class ProductVO {
  /** 商品主键。 */
  @JsonSerialize(using = ToStringSerializer.class)
  public Long id;

  /** 分类主键。 */
  public Long categoryId = 0L;

  /** 商品名称。 */
  public String name;

  /** 主图地址。 */
  public String mainImage;

  /** 商品描述。 */
  public String description;

  /** 商品价格字符串。 */
  public String price;

  /** 是否已上架。 */
  public boolean published;

  /** 商品状态。 */
  public int status;

  /** 商品 SKU 列表。 */
  public List<SkuVO> skus = new ArrayList<>();

  /** 商品参数列表。 */
  public List<ProductParameterVO> parameters;

  /** 按字段保留原请求写回值的响应表示。 */
  public static ProductVO from(ProductDTO request) {
    ProductVO response = new ProductVO();
    response.id = request.id;
    response.categoryId = request.categoryId;
    response.name = request.name;
    response.mainImage = request.mainImage;
    response.description = request.description;
    response.price = request.price;
    response.published = request.published;
    response.status = request.status;
    response.skus = request.skus == null ? null : request.skus.stream().map(SkuVO::from).toList();
    response.parameters =
        request.parameters == null
            ? null
            : request.parameters.stream().map(ProductParameterVO::from).toList();
    return response;
  }
}
