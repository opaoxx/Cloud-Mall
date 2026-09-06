package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品 SKU 持久化对象。 */
@TableName("product_sku")
public class ProductSkuPO {
  /** SKU 主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 所属商品主键。 */
  @TableField("product_id")
  public Long productId;

  /** SKU 编码。 */
  @TableField("sku_code")
  public String skuCode;

  /** SKU 规格 JSON。 */
  @TableField("spec_json")
  public String specJson;

  /** SKU 销售价。 */
  public BigDecimal price;

  /** SKU 状态。 */
  public Integer status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
