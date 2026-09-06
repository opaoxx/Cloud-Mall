package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 商品参数持久化对象。 */
@TableName("product_parameter")
public class ProductParameterPO {
  /** 参数主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 所属商品主键。 */
  @TableField("product_id")
  public Long productId;

  /** 参数名称。 */
  @TableField("param_name")
  public String parameterName;

  /** 参数值。 */
  @TableField("param_value")
  public String parameterValue;

  /** 展示顺序。 */
  @TableField("sort_no")
  public Integer sortNo;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
