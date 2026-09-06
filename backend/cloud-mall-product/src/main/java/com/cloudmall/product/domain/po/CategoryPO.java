package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 商品分类持久化对象。 */
@TableName("product_category")
public class CategoryPO {
  /** 分类主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 父分类主键。 */
  @TableField("parent_id")
  public Long parentId;

  /** 分类名称。 */
  public String name;

  /** 展示顺序。 */
  @TableField("sort_no")
  public Integer sortNo;

  /** 分类状态。 */
  public Integer status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
