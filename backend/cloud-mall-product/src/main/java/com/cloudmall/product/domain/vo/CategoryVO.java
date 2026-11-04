package com.cloudmall.product.domain.vo;

import com.cloudmall.product.domain.dto.CategoryDTO;

/** 商品分类响应视图。 */
public class CategoryVO {
  /** 分类主键。 */
  public Long id;

  /** 父分类主键。 */
  public Long parentId = 0L;

  /** 分类名称。 */
  public String name;

  /** 展示顺序。 */
  public int sortNo;

  /** 分类状态。 */
  public int status;

  /** 创建空分类响应。 */
  public CategoryVO() {}

  /** 创建分类查询对象。 */
  public CategoryVO(long id, long parentId, String name, int sortNo, int status) {
    this.id = id;
    this.parentId = parentId;
    this.name = name;
    this.sortNo = sortNo;
    this.status = status;
  }

  /** 按字段保留原请求写回值的响应表示。 */
  public static CategoryVO from(CategoryDTO request) {
    CategoryVO response = new CategoryVO();
    response.id = request.id;
    response.parentId = request.parentId;
    response.name = request.name;
    response.sortNo = request.sortNo;
    response.status = request.status;
    return response;
  }
}
