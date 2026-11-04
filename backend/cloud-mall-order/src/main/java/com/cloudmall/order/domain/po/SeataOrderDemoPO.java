package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** CM-18 Seata order 侧独立演练表持久化对象。 */
@TableName("seata_cm18_order_demo")
public class SeataOrderDemoPO {
  /** 演练请求标识。 */
  @TableId(type = IdType.INPUT)
  public String txId;

  /** Seata 全局事务 XID。 */
  @TableField("tx_xid")
  public String txXid;

  /** 演练分支状态。 */
  public String status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
