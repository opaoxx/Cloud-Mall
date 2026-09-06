package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 库存流水持久化对象。 */
@TableName("stock_flow")
public class StockFlowPO {
  /** 流水主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** SKU 主键。 */
  @TableField("sku_id")
  public Long skuId;

  /** 关联订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 流水类型。 */
  @TableField("flow_type")
  public String flowType;

  /** 变更数量。 */
  public Integer quantity;

  /** 幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
