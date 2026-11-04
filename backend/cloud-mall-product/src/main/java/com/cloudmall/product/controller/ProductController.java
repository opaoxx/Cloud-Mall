package com.cloudmall.product.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.product.domain.dto.ActivityDTO;
import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.dto.ProductReadResultDTO;
import com.cloudmall.product.domain.vo.ActivityVO;
import com.cloudmall.product.domain.vo.CategoryVO;
import com.cloudmall.product.domain.vo.HotStatVO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.domain.vo.SkuSummaryVO;
import com.cloudmall.product.service.CategoryService;
import com.cloudmall.product.service.ProductManagementService;
import com.cloudmall.product.service.ProductQueryService;
import com.cloudmall.product.service.SeckillActivityService;
import java.util.List;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 绑定商品HTTP请求并委托独立业务服务，按原契约装配响应。 */
@RestController
@RequestMapping("/api")
public class ProductController {
  /** 商品查询与指标登记业务。 */
  private final ProductQueryService productQueryService;

  /** 管理员商品维护业务。 */
  private final ProductManagementService productManagementService;

  /** 分类查询与维护业务。 */
  private final CategoryService categoryService;

  /** 秒杀活动与配额发布业务。 */
  private final SeckillActivityService activityService;

  /** 注入四项业务契约，数据库与事务处理由对应业务实现承担。 */
  public ProductController(
      ProductQueryService productQueryService,
      ProductManagementService productManagementService,
      CategoryService categoryService,
      SeckillActivityService activityService) {
    this.productQueryService = productQueryService;
    this.productManagementService = productManagementService;
    this.categoryService = categoryService;
    this.activityService = activityService;
  }

  /** 根据父分类和状态查询分类。 */
  @GetMapping("/categories")
  public ApiResponse<List<CategoryVO>> categories(
      @RequestParam(required = false) Long parentId,
      @RequestParam(required = false) Integer status) {
    return ApiResponse.ok(categoryService.categories(parentId, status));
  }

  /** 创建商品分类。 */
  @PostMapping("/categories")
  public ApiResponse<CategoryVO> addCategory(@RequestBody CategoryDTO request) {
    return ApiResponse.ok(categoryService.addCategory(operator(), request));
  }

  /** 更新指定分类。 */
  @PutMapping("/categories/{id}")
  public ApiResponse<CategoryVO> updateCategory(
      @PathVariable("id") Long categoryId, @RequestBody CategoryDTO request) {
    return ApiResponse.ok(categoryService.updateCategory(operator(), categoryId, request));
  }

  /** 停用指定分类。 */
  @DeleteMapping("/categories/{id}")
  public ApiResponse<Void> deleteCategory(@PathVariable("id") Long categoryId) {
    categoryService.deleteCategory(operator(), categoryId);
    return ApiResponse.ok(null);
  }

  /** 按原分页、过滤与排序参数读取商品列表。 */
  @GetMapping("/products")
  public ApiResponse<PageResult<ProductVO>> products(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Integer status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String sort) {
    return readResponse(
        productQueryService.products(keyword, categoryId, status, page, pageSize, sort));
  }

  /** 读取商品详情，沿原指标模式设置响应关联号。 */
  @GetMapping("/products/{id}")
  public ApiResponse<ProductVO> product(@PathVariable("id") Long productId) {
    return readResponse(productQueryService.product(productId));
  }

  /** 提供SKU商品摘要、金额和规格。 */
  @GetMapping("/products/skus/{skuId}")
  public ApiResponse<SkuSummaryVO> sku(@PathVariable("skuId") Long skuId) {
    return ApiResponse.ok(productQueryService.sku(skuId));
  }

  /** 查询历史热度及已启用的在线统计。 */
  @GetMapping("/products/{id}/hot-stat")
  public ApiResponse<HotStatVO> hot(@PathVariable("id") Long productId) {
    return ApiResponse.ok(productQueryService.hot(productId));
  }

  /** 按原Bean校验规则绑定创建请求，写入交给事务服务。 */
  @PostMapping("/products")
  public ApiResponse<ProductVO> create(@Valid @RequestBody ProductDTO request) {
    return ApiResponse.ok(productManagementService.create(operator(), request));
  }

  /** 更新商品，业务校验顺序由事务服务保持。 */
  @PutMapping("/products/{id}")
  public ApiResponse<ProductVO> update(
      @PathVariable("id") Long productId, @RequestBody ProductDTO request) {
    return ApiResponse.ok(productManagementService.update(operator(), productId, request));
  }

  /** 上架商品并登记索引事件。 */
  @PostMapping("/products/{id}/publish")
  public ApiResponse<ProductVO> publish(@PathVariable("id") Long productId) {
    return ApiResponse.ok(productManagementService.publish(operator(), productId));
  }

  /** 下架商品并登记索引事件。 */
  @PostMapping("/products/{id}/unpublish")
  public ApiResponse<ProductVO> unpublish(@PathVariable("id") Long productId) {
    return ApiResponse.ok(productManagementService.unpublish(operator(), productId));
  }

  /** 查询活动状态和剩余配额。 */
  @GetMapping("/seckill/activities/{id}")
  public ApiResponse<ActivityVO> activity(@PathVariable("id") Long activityId) {
    return ApiResponse.ok(activityService.activity(activityId));
  }

  /** 创建秒杀活动。 */
  @PostMapping("/seckill/activities")
  public ApiResponse<ActivityVO> createActivity(@RequestBody ActivityDTO request) {
    return ApiResponse.ok(activityService.createActivity(operator(), request));
  }

  /** 更新秒杀活动。 */
  @PutMapping("/seckill/activities/{id}")
  public ApiResponse<ActivityVO> updateActivity(
      @PathVariable("id") Long activityId, @RequestBody ActivityDTO request) {
    return ApiResponse.ok(activityService.updateActivity(operator(), activityId, request));
  }

  /** 发布活动与Redis配额。 */
  @PostMapping("/seckill/activities/{id}/publish")
  public ApiResponse<ActivityVO> publishActivity(@PathVariable("id") Long activityId) {
    return ApiResponse.ok(activityService.publishActivity(operator(), activityId));
  }

  /** 启动已有活动。 */
  @PostMapping("/seckill/activities/{id}/start")
  public ApiResponse<ActivityVO> startActivity(@PathVariable("id") Long activityId) {
    return ApiResponse.ok(activityService.startActivity(operator(), activityId));
  }

  /** 显式传递原身份，管理员校验仍在原业务位置及事务内进行。 */
  private ProductOperatorDTO operator() {
    return new ProductOperatorDTO(AuthContext.userId(), AuthContext.role());
  }

  /** 指标启用时沿用登记关联号，其余读响应使用ApiResponse原默认值。 */
  private <T> ApiResponse<T> readResponse(ProductReadResultDTO<T> result) {
    ApiResponse<T> response = ApiResponse.ok(result.data());
    if (result.requestId() != null) response.requestId = result.requestId();
    return response;
  }
}
