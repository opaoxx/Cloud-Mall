package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订单状态流水持久化对象。 */
@TableName("order_status_log")
public class OrderStatusLogPO {
  /** 流水主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 订单内部主键。 */
  @TableField("order_id")
  public Long orderId;

  /** 对外订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 变更前状态。 */
  @TableField("from_status")
  public String fromStatus;

  /** 变更后状态。 */
  @TableField("to_status")
  public String toStatus;

  /** 状态变更事件。 */
  @TableField("event_type")
  public String eventType;

  /** 操作人主键。 */
  @TableField("operator_id")
  public Long operatorId;

  /** 变更备注。 */
  public String remark;

  /** 创建时间及分片键。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
