package com.cloudmall.cart.domain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;

/** 购物车勾选请求，保持原 Boolean Map 的字段转换和 null 语义。 */
public class CartCheckedDTO {
  /** 请求的勾选意图，缺失或 null 由业务层解释为 false。 */
  public final Boolean checked;

  /** 使用原 Map 绑定规则接收请求，包括额外字段的 Boolean 转换。 */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public CartCheckedDTO(Map<String, Boolean> properties) {
    this.checked = properties.get("checked");
  }
}
