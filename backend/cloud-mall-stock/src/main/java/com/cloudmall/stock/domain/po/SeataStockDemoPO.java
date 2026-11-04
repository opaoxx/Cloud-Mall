package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** CM-18 Stock 独立演练表持久化对象。 */
@TableName("seata_cm18_stock_demo")
public class SeataStockDemoPO {
  /** 演练请求主键。 */
  @TableId(value = "tx_id", type = IdType.INPUT)
  private String txId;

  /** 与 Order 分支相同的完整 Seata XID。 */
  @TableField("tx_xid")
  private String txXid;

  /** Stock 演练分支的持久状态。 */
  @TableField("marker")
  private String marker;

  /** 演练行创建时间。 */
  @TableField("created_at")
  private LocalDateTime createdAt;

  /** 返回演练请求主键。 */
  public String getTxId() {
    return txId;
  }

  /** 设置演练请求主键。 */
  public void setTxId(String txId) {
    this.txId = txId;
  }

  /** 返回完整 Seata XID。 */
  public String getTxXid() {
    return txXid;
  }

  /** 设置完整 Seata XID。 */
  public void setTxXid(String txXid) {
    this.txXid = txXid;
  }

  /** 返回 Stock 分支状态。 */
  public String getMarker() {
    return marker;
  }

  /** 设置 Stock 分支状态。 */
  public void setMarker(String marker) {
    this.marker = marker;
  }

  /** 返回创建时间。 */
  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  /** 设置创建时间。 */
  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
