package com.cloudmall.pay.domain.vo;

import com.cloudmall.pay.domain.po.PayTccExecutionPO;

/** Pay coordinator 执行事实内部视图。 */
public class PayTccExecutionVO {
  /** 支付号。 */
  public String payNo;

  /** XID。 */
  public String xid;

  /** 用户主键。 */
  public Long userId;

  /** 订单号。 */
  public String orderNo;

  /** 金额字符串。 */
  public String amount;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;

  /** 执行状态。 */
  public String state;

  /** 决议方向。 */
  public String decision;

  /** 错误摘要。 */
  public String lastError;

  /** 从执行持久事实构造完整 tuple 视图。 */
  public static PayTccExecutionVO from(PayTccExecutionPO execution) {
    PayTccExecutionVO view = new PayTccExecutionVO();
    view.payNo = execution.payNo;
    view.xid = execution.xid;
    view.userId = execution.userId;
    view.orderNo = execution.orderNo;
    view.amount = execution.amount.toPlainString();
    view.operationKey = execution.operationKey;
    view.ledgerKey = execution.ledgerKey;
    view.state = execution.state;
    view.decision = execution.decision;
    view.lastError = execution.lastError;
    return view;
  }
}
