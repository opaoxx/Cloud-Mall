package com.cloudmall.stock.domain.vo;

/** CM-18 Stock 分支结果。 */
public class Cm18SeataBranchVO {
  /** 演练请求标识。 */
  private String txId;
  /** 不暴露原始 XID 的不可逆摘要。 */
  private String xid;
  /** 已写入本地分支时为 WRITTEN。 */
  private String status;

  /** 初始化分支响应。 */
  public Cm18SeataBranchVO() {}

  /** 初始化已写入的分支响应。 */
  public Cm18SeataBranchVO(String txId, String xid, String status) {
    this.txId = txId;
    this.xid = xid;
    this.status = status;
  }

  /** 返回演练请求标识。 */
  public String getTxId() {
    return txId;
  }

  /** 返回 XID 摘要。 */
  public String getXid() {
    return xid;
  }

  /** 返回分支状态。 */
  public String getStatus() {
    return status;
  }
}
