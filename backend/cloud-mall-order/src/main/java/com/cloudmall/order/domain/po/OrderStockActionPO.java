package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 订单状态迁移后必须执行的持久库存动作。 */
@TableName("order_stock_action")
public class OrderStockActionPO {
  /** 动作主键。 */
  @TableId public Long id;

  /** 唯一订单号。 */
  public String orderNo;

  /** CONFIRM 或 ROLLBACK。 */
  public String actionType;

  /** 传给库存服务的稳定操作键。 */
  public String operationKey;

  /** PENDING、UNKNOWN、SUCCEEDED 或 CONFLICT。 */
  public String status;

  /** 已领取的处理次数。 */
  public Integer attempts;

  /** 下次处理时间。 */
  public LocalDateTime nextRetryAt;
}
