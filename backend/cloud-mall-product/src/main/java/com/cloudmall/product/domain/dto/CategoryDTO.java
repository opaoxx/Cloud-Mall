package com.cloudmall.product.domain.dto;

import javax.validation.constraints.NotBlank;

/** 商品分类传输对象。 */
public class CategoryDTO {
  /** 分类主键。 */
  public Long id;

  /** 父分类主键。 */
  public Long parentId = 0L;

  /** 分类名称。 */
  @NotBlank public String name;

  /** 展示顺序。 */
  public int sortNo;

  /** 分类状态。 */
  public int status;

  /** 创建空分类请求。 */
  public CategoryDTO() {}

  /** 创建分类查询对象。 */
  public CategoryDTO(long id, long parentId, String name, int sortNo, int status) {
    this.id = id;
    this.parentId = parentId;
    this.name = name;
    this.sortNo = sortNo;
    this.status = status;
  }
}
