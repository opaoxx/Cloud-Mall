package com.cloudmall.user.domain.dto;

import java.math.BigDecimal;

/** User 本地 TCC Confirm/Cancel 可信载荷。 */
public class BalanceTccDebitFinalizeDTO {
  /** 用户主键。 */
  public Long userId;

  /** 支付号。 */
  public String payNo;

  /** 订单号。 */
  public String orderNo;

  /** TCC 操作键。 */
  public String operationKey;

  /** 账本唯一操作键，TCC 必须等于 payNo。 */
  public String ledgerKey;

  /** XID。 */
  public String xid;

  /** 分支标识。 */
  public Long branchId;

  /** 清算金额。 */
  public BigDecimal amount;
}
