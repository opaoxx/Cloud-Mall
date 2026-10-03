package com.cloudmall.order.domain.vo;

/** CM-18 Seata 受控演练响应。 */
public class Cm18SeataDemoVO {
  /** 演练请求标识。 */
  public String txId;

  /** 脱敏后的 Seata XID 标识。 */
  public String xid;

  /** 全局事务结果：COMMITTED、ROLLED_BACK 或 UNKNOWN。 */
  public String status;

  /** 创建演练响应。 */
  public Cm18SeataDemoVO() {}

  /** 创建演练响应。 */
  public Cm18SeataDemoVO(String txId, String xid, String status) {
    this.txId = txId;
    this.xid = xid;
    this.status = status;
  }
}
