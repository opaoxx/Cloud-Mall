package com.cloudmall.pay.domain.vo;

import com.cloudmall.pay.domain.po.PayTccDispatchPO;

/** Pay TCC 派发状态摘要。 */
public class PayTccDispatchVO {
  /** 支付号。 */
  public String payNo;

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

  /** XID。 */
  public String xid;

  /** User 分支标识。 */
  public Long userBranchId;

  /** 派发状态。 */
  public String state;

  /** 最近错误摘要。 */
  public String lastError;

  /** 从持久化派发事实构造状态视图。 */
  public static PayTccDispatchVO from(PayTccDispatchPO dispatch) {
    PayTccDispatchVO view = new PayTccDispatchVO();
    view.payNo = dispatch.payNo;
    view.userId = dispatch.userId;
    view.orderNo = dispatch.orderNo;
    view.amount = dispatch.amount.toPlainString();
    view.operationKey = dispatch.operationKey;
    view.ledgerKey = dispatch.ledgerKey;
    view.xid = dispatch.xid;
    view.userBranchId = dispatch.userBranchId;
    view.state = dispatch.state;
    view.lastError = dispatch.lastError;
    return view;
  }
}
