package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单明细快照持久化对象。 */
@TableName("mall_order_item")
public class OrderItemPO {
  /** 明细主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 订单内部主键。 */
  @TableField("order_id")
  public Long orderId;

  /** 对外订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 商品主键。 */
  @TableField("product_id")
  public Long productId;

  /** SKU 主键。 */
  @TableField("sku_id")
  public Long skuId;

  /** 商品名称快照。 */
  @TableField("product_name_snapshot")
  public String productNameSnapshot;

  /** SKU 规格快照 JSON。 */
  @TableField("sku_snapshot")
  public String skuSnapshot;

  /** 下单单价。 */
  @TableField("unit_price")
  public BigDecimal unitPrice;

  /** 购买数量。 */
  public Integer quantity;

  /** 明细金额。 */
  @TableField("line_amount")
  public BigDecimal lineAmount;

  /** 创建时间及分片键。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
