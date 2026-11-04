package com.cloudmall.product.service.impl;

import static com.cloudmall.product.util.ProductDateTimes.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.vo.CategoryVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.CategoryService;
import com.cloudmall.product.util.ProductAuthorization;
import com.cloudmall.product.util.ProductIdentifiers;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按原管理员校验与事务边界管理商品分类。 */
@Service
public class CategoryServiceImpl implements CategoryService {

  /** 分类查询与写入的Mapper。 */
  private final ProductSqlMapper productSqlMapper;

  /** 注入分类数据库访问入口。 */
  public CategoryServiceImpl(ProductSqlMapper productSqlMapper) {
    this.productSqlMapper = productSqlMapper;
  }

  /** 按父分类和状态查询分类。 */
  @Override
  public List<CategoryVO> categories(Long parentId, Integer status) {
    return productSqlMapper.findCategories(parentId, status).stream()
        .map(this::categoryView)
        .toList();
  }

  /** 在同一事务内校验管理员并写入分类。 */
  @Override
  @Transactional
  public CategoryVO addCategory(ProductOperatorDTO operator, CategoryDTO request) {
    // 1. 在当前事务内按原顺序校验身份和分类参数。
    ProductAuthorization.requireAdmin(operator);
    validateCategory(request);
    long categoryId = ProductIdentifiers.nextId();
    // 2. 保存分类事实。
    productSqlMapper.insertCategory(categoryId, request, now());
    request.id = categoryId;
    // 3. 保留原请求写回的主键和默认状态。
    request.status = 1;
    return CategoryVO.from(request);
  }

  /** 校验已有分类并更新其可变字段。 */
  @Override
  @Transactional
  public CategoryVO updateCategory(
      ProductOperatorDTO operator, Long categoryId, CategoryDTO request) {
    // 1. 先校验管理员、请求和目标分类。
    ProductAuthorization.requireAdmin(operator);
    validateCategory(request);
    requireCategory(categoryId);
    // 2. 更新原可变字段。
    productSqlMapper.updateCategory(categoryId, request, now());
    request.id = categoryId;
    // 3. 按原响应规则写回主键和状态。
    request.status = 1;
    return CategoryVO.from(request);
  }

  /** 在原本地事务中停用分类。 */
  @Override
  @Transactional
  public void deleteCategory(ProductOperatorDTO operator, Long categoryId) {
    // 1. 验证管理员和既有分类。
    ProductAuthorization.requireAdmin(operator);
    requireCategory(categoryId);
    // 2. 在原事务内停用分类。
    productSqlMapper.disableCategory(categoryId, now());
  }

  /** 核验分类存在，保持原404语义。 */
  private void requireCategory(Long categoryId) {
    if (productSqlMapper.countCategory(categoryId) == 0)
      throw new BizException(ErrorCodes.NOT_FOUND, "分类不存在", 404);
  }

  /** 将数据库分类投影映射为响应对象。 */
  private CategoryVO categoryView(Map<String, Object> row) {
    return new CategoryVO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("parent_id")).longValue(),
        String.valueOf(row.get("name")),
        ((Number) row.get("sort_no")).intValue(),
        ((Number) row.get("status")).intValue());
  }

  /** 验证分类名称并保留父分类默认值。 */
  private static void validateCategory(CategoryDTO request) {
    if (request == null || request.name == null || request.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "分类名称不能为空", 400);
    if (request.parentId == null) request.parentId = 0L;
  }
}
