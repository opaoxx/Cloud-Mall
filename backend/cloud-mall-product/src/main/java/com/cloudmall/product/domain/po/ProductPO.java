package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品事实数据持久化对象。 */
@TableName("product")
public class ProductPO {
  /** 商品主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 商品分类主键。 */
  @TableField("category_id")
  public Long categoryId;

  /** 商品名称。 */
  public String name;

  /** 商品主图。 */
  @TableField("main_image")
  public String mainImage;

  /** 商品描述。 */
  public String description;

  /** 商品基础价格。 */
  public BigDecimal price;

  /** 商品上下架状态。 */
  public Integer status;

  /** 乐观锁版本。 */
  @Version public Integer version;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
