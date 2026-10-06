package com.cloudmall.user.domain.dto;

/** 内部 TCC Try 载荷；分支号只能由 Seata 生成。 */
public class UserTccTryDTO {
  /** 支付号。 */
  public String payNo;

  /** 原订单号。 */
  public String orderNo;

  /** 原扣款金额。 */
  public java.math.BigDecimal amount;

  /** 与 TX_XID 一致的全局事务。 */
  public String xid;

  /** 固定 TCC 操作键。 */
  public String operationKey;

  /** 固定最终账本键。 */
  public String ledgerKey;
}
