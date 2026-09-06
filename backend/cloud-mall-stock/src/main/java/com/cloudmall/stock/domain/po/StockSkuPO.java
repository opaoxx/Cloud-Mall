package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.LocalDateTime;

/** SKU 库存持久化对象。 */
@TableName("stock_sku")
public class StockSkuPO {
  /** SKU 主键。 */
  @TableId public Long skuId;

  /** 商品主键。 */
  @TableField("product_id")
  public Long productId;

  /** 总库存。 */
  @TableField("total_quantity")
  public Integer totalQuantity;

  /** 可用库存。 */
  @TableField("available_quantity")
  public Integer availableQuantity;

  /** 预扣库存。 */
  @TableField("reserved_quantity")
  public Integer reservedQuantity;

  /** 已售库存。 */
  @TableField("sold_quantity")
  public Integer soldQuantity;

  /** 乐观锁版本。 */
  @Version public Integer version;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
