package com.cloudmall.order.domain.vo;

/** CM-18 Stock 分支成功响应的最小投影。 */
public class SeataStockDemoBranchVO {
  /** 演练请求标识。 */
  public String txId;

  /** Stock 侧确认的 XID 摘要。 */
  public String xid;

  /** 分支写入状态，成功时固定为 WRITTEN。 */
  public String status;
}
