package com.cloudmall.user.domain.dto;

import java.math.BigDecimal;

/** User TCC 资源分支的业务载荷；XID 和 branchId 只能来自 Seata 上下文。 */
public class BalanceTccBranchDTO {
  /** 用户主键。 */
  public Long userId;

  /** 支付号。 */
  public String payNo;

  /** 订单号。 */
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;
}
