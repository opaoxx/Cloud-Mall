package com.cloudmall.product.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.NotBlank;

/** 商品新增、修改和展示过程中的传输对象。 */
public class ProductDTO {
  /** 商品主键。 */
  @JsonSerialize(using = ToStringSerializer.class)
  public Long id;

  /** 分类主键。 */
  public Long categoryId = 0L;

  /** 商品名称。 */
  @NotBlank public String name;

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
  public List<SkuDTO> skus = new ArrayList<>();

  /** 商品参数列表。 */
  public List<ProductParameterDTO> parameters;
}
