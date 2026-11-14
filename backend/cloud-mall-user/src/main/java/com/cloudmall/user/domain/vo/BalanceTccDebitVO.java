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

  /**
   * 从本地 TCC 数据对象构造状态摘要，直接复制状态、操作键、支付号和分支标识。
   *
   * <p>XID 为 null 时摘要保持 null；否则只保留最多前 8 位，不修改原记录。
   *
   * @param debit 非 null 的本地 TCC 数据对象，空取消时可仅包含归属摘要字段
   * @return 新建的状态摘要
   */
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
