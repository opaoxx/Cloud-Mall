package com.cloudmall.pay.domain.vo;

import com.cloudmall.pay.domain.po.PayTccReceiptPO;

/** Pay LocalTCC receipt 状态摘要。 */
public class PayTccReceiptVO {
  /** 支付号。 */
  public String payNo;

  /** XID。 */
  public String xid;

  /** Pay 分支标识。 */
  public Long branchId;

  /** Receipt 状态。 */
  public String state;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;

  /** 从 receipt 持久事实构造视图。 */
  public static PayTccReceiptVO from(PayTccReceiptPO receipt) {
    PayTccReceiptVO view = new PayTccReceiptVO();
    view.payNo = receipt.payNo;
    view.xid = receipt.xid;
    view.branchId = receipt.branchId;
    view.state = receipt.state;
    view.operationKey = receipt.operationKey;
    view.ledgerKey = receipt.ledgerKey;
    return view;
  }
}
