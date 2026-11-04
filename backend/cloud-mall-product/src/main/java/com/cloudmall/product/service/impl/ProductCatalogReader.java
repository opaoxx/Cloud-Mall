package com.cloudmall.product.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.vo.ProductParameterVO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.domain.vo.SkuVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.util.ProductValueCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 复用商品事实与附属数据的原读取和视图装配流程。 */
@Component
public class ProductCatalogReader {

  /** 商品事实和附属数据查询入口。 */
  private final ProductSqlMapper productSqlMapper;

  /** 保留金额和SKU JSON语义的字段编解码器。 */
  private final ProductValueCodec valueCodec;

  /** 注入读取事实所需的Mapper和字段编解码器。 */
  public ProductCatalogReader(ProductSqlMapper productSqlMapper, ProductValueCodec valueCodec) {
    this.productSqlMapper = productSqlMapper;
    this.valueCodec = valueCodec;
  }

  /** 按原查询与附属数据加载顺序读取商品，缺失时返回404。 */
  ProductVO requireProduct(Long productId) {
    List<ProductVO> products =
        productSqlMapper.findProduct(productId).stream().map(this::productView).toList();
    if (products.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "商品不存在", 404);
    return products.get(0);
  }

  /** 将商品事实行与SKU、参数装配为独立响应视图。 */
  ProductVO productView(Map<String, Object> row) {
    return read(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("category_id")).longValue(),
        String.valueOf(row.get("name")),
        (String) row.get("main_image"),
        (String) row.get("description"),
        (BigDecimal) row.get("price"),
        ((Number) row.get("status")).intValue());
  }

  /** 将SKU事实行转换为保持原金额和规格的视图。 */
  private SkuVO skuView(Map<String, Object> row) {
    return new SkuVO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("product_id")).longValue(),
        String.valueOf(row.get("sku_code")),
        valueCodec.decodeSkuSpec(row.get("spec_json")),
        valueCodec.formatPrice((BigDecimal) row.get("price")),
        ((Number) row.get("status")).intValue() == 1);
  }

  /** 将数据库参数名和值映射到原JSON字段。 */
  private ProductParameterVO parameterView(Map<String, Object> row) {
    return new ProductParameterVO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("product_id")).longValue(),
        String.valueOf(row.get("param_name")),
        String.valueOf(row.get("param_value")),
        ((Number) row.get("sort_no")).intValue());
  }

  /** 按原顺序读取附属SKU和参数并组装商品视图。 */
  private ProductVO read(
      long productId,
      long categoryId,
      String productName,
      String imageUrl,
      String description,
      BigDecimal price,
      int status) {
    ProductVO product = new ProductVO();
    product.id = productId;
    product.categoryId = categoryId;
    product.name = productName;
    product.mainImage = imageUrl;
    product.description = description;
    product.price = valueCodec.formatPrice(price);
    product.status = status;
    product.published = status == 1;
    product.skus = productSqlMapper.findSkus(productId).stream().map(this::skuView).toList();
    product.parameters =
        productSqlMapper.findParameters(productId).stream().map(this::parameterView).toList();
    return product;
  }
}
