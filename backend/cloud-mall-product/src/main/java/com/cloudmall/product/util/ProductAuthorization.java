package com.cloudmall.product.util;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;

/** 对显式传入的操作者执行原商品管理员权限校验。 */
public final class ProductAuthorization {
  /** 禁止实例化无状态权限校验工具。 */
  private ProductAuthorization() {}

  /** 保留先验证登录身份、再验证 ADMIN 角色的错误顺序与消息。 */
  public static void requireAdmin(ProductOperatorDTO operator) {
    if (operator == null || operator.userId() == null) {
      throw new BizException(ErrorCodes.UNAUTHORIZED, "请先登录", 401);
    }
    if (!"ADMIN".equals(operator.role())) {
      throw new BizException(ErrorCodes.FORBIDDEN, "需要管理员权限", 403);
    }
  }
}
