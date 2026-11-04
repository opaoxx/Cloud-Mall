package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.cloudmall.product.domain.dto.SkuDTO;
import com.cloudmall.product.domain.vo.CategoryVO;
import com.cloudmall.product.domain.vo.ProductParameterVO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.domain.vo.SkuVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 通过原DTO与独立VO的JSON树对照保护金额、ID类型和null语义。 */
class ProductResponseModelTest {
  /** 沿用无额外配置的字段序列化规则。 */
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void productGraphPreservesLargeIdentifiersAndRawRequestAmounts() {
    ProductDTO request = new ProductDTO();
    request.id = Long.MAX_VALUE;
    request.name = "原商品";
    request.price = "12.3";
    request.skus =
        List.of(new SkuDTO(71L, Long.MAX_VALUE, "sku", Map.of("颜色", "黑色"), "12.30", true));
    request.parameters = List.of(new ProductParameterDTO(81L, Long.MAX_VALUE, "材质", "金属", 2));
    assertEquals(
        objectMapper.valueToTree(request), objectMapper.valueToTree(ProductVO.from(request)));
  }

  @Test
  void nullableCollectionsAndOptionalFieldsKeepTheirOriginalJson() {
    ProductDTO request = new ProductDTO();
    request.skus = null;
    request.parameters = null;
    assertEquals(
        objectMapper.valueToTree(request), objectMapper.valueToTree(ProductVO.from(request)));
  }

  @Test
  void categorySkuAndParameterResponsesPreserveOriginalFieldTypes() {
    CategoryDTO category = new CategoryDTO(7L, 0L, "分类", 2, 1);
    SkuDTO sku = new SkuDTO(71L, Long.MAX_VALUE, "sku", null, "1e2", false);
    ProductParameterDTO parameter = new ProductParameterDTO(null, Long.MAX_VALUE, "参数", null, 0);
    assertEquals(
        objectMapper.valueToTree(category), objectMapper.valueToTree(CategoryVO.from(category)));
    assertEquals(objectMapper.valueToTree(sku), objectMapper.valueToTree(SkuVO.from(sku)));
    assertEquals(
        objectMapper.valueToTree(parameter),
        objectMapper.valueToTree(ProductParameterVO.from(parameter)));
  }
}
