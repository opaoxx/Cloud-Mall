package com.cloudmall.stock.domain.dto;

/** CM-18 受控 Seata 分支请求。 */
public class SeataStockBranchDTO {
  /** 仅用于演练表幂等的受控事务标识。 */
  private String txId;

  /** 返回受控事务标识。 */
  public String getTxId() {
    return txId;
  }

  /** 设置受控事务标识。 */
  public void setTxId(String txId) {
    this.txId = txId;
  }
}
