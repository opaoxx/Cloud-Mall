package com.cloudmall.product.service;

import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.vo.CategoryVO;
import java.util.List;

/** 商品分类查询与管理员维护契约。 */
public interface CategoryService {
  /** 按原父分类与状态过滤条件查询分类。 */
  List<CategoryVO> categories(Long parentId, Integer status);

  /** 在本地事务内验证管理员并创建分类。 */
  CategoryVO addCategory(ProductOperatorDTO operator, CategoryDTO request);

  /** 在本地事务内更新已有分类。 */
  CategoryVO updateCategory(ProductOperatorDTO operator, Long categoryId, CategoryDTO request);

  /** 在本地事务内停用已有分类。 */
  void deleteCategory(ProductOperatorDTO operator, Long categoryId);
}
