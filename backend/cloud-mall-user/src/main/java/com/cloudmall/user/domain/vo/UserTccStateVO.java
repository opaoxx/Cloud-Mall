package com.cloudmall.user.domain.vo;

/** 只读 TCC 状态，精确标识仅供 Pay 内部核验。 */
public class UserTccStateVO {
  /** 资金所属用户。 */
  public Long userId;

  /** 原支付号。 */
  public String payNo;

  /** 原订单号。 */
  public String orderNo;

  /** 金额字符串。 */
  public String amount;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;

  /** 精确全局事务标识。 */
  public String xid;

  /** 已观察到的分支号，未知时为空。 */
  public Long branchId;

  /** UNKNOWN、TRYING、CONFIRMED 或 CANCELLED。 */
  public String state;

  /** 是否有一致的 APPLIED 扣款账本。 */
  public boolean ledgerApplied;
}
