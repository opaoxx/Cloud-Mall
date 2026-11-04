package com.cloudmall.cart.domain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.Map;

/** 购物车结算选择请求，保持原 List<Long> Map 的转换与缺省规则。 */
public class CartSelectionDTO {
  /** 显式选择的 SKU，字段缺失时为空列表，显式 null 保留给原异常路径。 */
  public final List<Long> skuIds;

  /** 使用原泛型 Map 绑定规则接收选择，保留重复 SKU 和额外字段的转换。 */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public CartSelectionDTO(Map<String, List<Long>> properties) {
    this.skuIds = properties.getOrDefault("skuIds", List.of());
  }
}
