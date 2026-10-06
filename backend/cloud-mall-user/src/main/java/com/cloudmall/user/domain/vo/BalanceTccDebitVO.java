package com.cloudmall.user.domain.vo;

import com.cloudmall.user.domain.po.BalanceTccDebitPO;

/** User 本地 TCC 状态摘要。 */
public class BalanceTccDebitVO {
  /** 当前状态。 */
  public String state;

  /** TCC 操作键。 */
  public String operationKey;

  /** 支付号。 */
  public String payNo;

  /** 脱敏 XID 摘要。 */
  public String xidSummary;

  /** 分支标识。 */
  public Long branchId;

  public static BalanceTccDebitVO from(BalanceTccDebitPO debit) {
    BalanceTccDebitVO view = new BalanceTccDebitVO();
    view.state = debit.state;
    view.operationKey = debit.operationKey;
    view.payNo = debit.payNo;
    view.xidSummary =
        debit.xid == null ? null : debit.xid.substring(0, Math.min(8, debit.xid.length()));
    view.branchId = debit.branchId;
    return view;
  }
}
