package com.cloudmall.pay.domain.vo;

/** TCC 未决 TRYING 阶段主动回滚证明的完整不可变快照。 */
public class PayTccAbortProofVO {
  /** 支付号。 */
  public String payNo;

  /** 关联订单号。 */
  public String orderNo;

  /** 支付用户主键。 */
  public Long userId;

  /** 原始支付金额字符串。 */
  public String amount;

  /** 原 TCC 全局事务标识。 */
  public String xid;

  /** 原 TCC 操作键。 */
  public String operationKey;

  /** 原最终账本键。 */
  public String ledgerKey;

  /** Pay receipt 分支标识。 */
  public Long payBranchId;

  /** User dispatch 分支标识。 */
  public Long userBranchId;
}
