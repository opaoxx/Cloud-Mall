package com.cloudmall.product.service.impl;

import static com.cloudmall.product.util.ProductDateTimes.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.cloudmall.product.domain.dto.SkuDTO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductIndexOutboxService;
import com.cloudmall.product.service.ProductManagementService;
import com.cloudmall.product.util.ProductAuthorization;
import com.cloudmall.product.util.ProductEventEnvelope;
import com.cloudmall.product.util.ProductIdentifiers;
import com.cloudmall.product.util.ProductValueCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在原本地事务中维护商品、SKU、参数及索引outbox。 */
@Service
public class ProductManagementServiceImpl implements ProductManagementService {

  /** 商品事实写入Mapper。 */
  private final ProductSqlMapper productSqlMapper;

  /** 复用商品读取及附属数据装配。 */
  private final ProductCatalogReader catalogReader;

  /** 与商品事实处于同一事务的索引outbox。 */
  private final ProductIndexOutboxService productIndexOutboxService;

  /** 保留原金额及规格JSON语义的编解码器。 */
  private final ProductValueCodec valueCodec;

  /** 注入商品事实、读取装配及索引事件依赖。 */
  public ProductManagementServiceImpl(
      ProductSqlMapper productSqlMapper,
      ProductCatalogReader catalogReader,
      ProductIndexOutboxService productIndexOutboxService,
      ProductValueCodec valueCodec) {
    this.productSqlMapper = productSqlMapper;
    this.catalogReader = catalogReader;
    this.productIndexOutboxService = productIndexOutboxService;
    this.valueCodec = valueCodec;
  }

  /** 在原事务内保存商品事实和索引outbox，返回原请求写回字段。 */
  @Override
  @Transactional
  public ProductVO create(ProductOperatorDTO operator, ProductDTO request) {
    // 1. 在原事务入口内校验管理员和商品参数。
    ProductAuthorization.requireAdmin(operator);
    validateProduct(request);
    if (request.parameters == null) request.parameters = new ArrayList<>();
    long productId = ProductIdentifiers.nextId();
    // 2. 同资源保存商品、SKU和参数。
    saveProductFacts(productId, request);
    request.id = productId;
    request.published = false;
    // 3. 追加同事务outbox后返回原请求写回值。
    appendIndexEvent(productId);
    return ProductVO.from(request);
  }

  /** 校验管理员并更新商品、SKU和参数，重新读取已保存的事实。 */
  @Override
  @Transactional
  public ProductVO update(ProductOperatorDTO operator, Long productId, ProductDTO request) {
    // 1. 校验管理员、请求及当前商品事实。
    ProductAuthorization.requireAdmin(operator);
    validateProduct(request);
    catalogReader.requireProduct(productId);
    // 2. 更新事实与原请求声明的附属数据。
    productSqlMapper.updateProduct(
        productId, request, valueCodec.formatPrice(request.price), now());
    if (request.skus != null) replaceSkus(productId, request.skus);
    if (request.parameters != null) replaceParameters(productId, request.parameters);
    ProductVO saved = catalogReader.requireProduct(productId);
    // 3. 重新读取的事实与索引事件在同一次事务中提交。
    appendIndexEvent(productId);
    return saved;
  }

  /** 在事务内上架商品并登记同版本索引事件。 */
  @Override
  @Transactional
  public ProductVO publish(ProductOperatorDTO operator, Long productId) {
    // 1. 验证原管理员身份。
    ProductAuthorization.requireAdmin(operator);
    // 2. 在当前事务中推进状态与索引事件。
    return change(productId, 1);
  }

  /** 在事务内下架商品并登记同版本索引事件。 */
  @Override
  @Transactional
  public ProductVO unpublish(ProductOperatorDTO operator, Long productId) {
    // 1. 验证原管理员身份。
    ProductAuthorization.requireAdmin(operator);
    // 2. 在当前事务中推进状态与索引事件。
    return change(productId, 0);
  }

  /** 在同一事务中推进商品状态并写入原索引事件。 */
  private ProductVO change(Long productId, int newStatus) {
    ProductVO product = catalogReader.requireProduct(productId);
    productSqlMapper.updateProductStatus(productId, newStatus, now());
    product.status = newStatus;
    product.published = newStatus == 1;
    appendIndexEvent(productId);
    return product;
  }

  /** 按原顺序保存商品、SKU及参数。 */
  private void saveProductFacts(long productId, ProductDTO request) {
    productSqlMapper.insertProduct(
        productId, request, valueCodec.formatPrice(request.price), now());
    replaceSkus(productId, request.skus == null ? List.of() : request.skus);
    replaceParameters(productId, request.parameters == null ? List.of() : request.parameters);
  }

  /** 沿原规则补充SKU主键并保存规格JSON。 */
  private void replaceSkus(long productId, List<SkuDTO> skus) {
    for (SkuDTO sku : skus) {
      if (sku == null) throw new BizException(ErrorCodes.INVALID, "SKU参数无效", 400);
      if (sku.id == null) sku.id = ProductIdentifiers.nextId();
      productSqlMapper.saveSku(productId, sku, valueCodec.encodeSkuSpec(sku.specJson), now());
    }
  }

  /** 完整校验参数后删除旧值并逐项写入新值。 */
  private void replaceParameters(long productId, List<ProductParameterDTO> parameters) {
    for (ProductParameterDTO parameter : parameters) {
      if (parameter == null
          || parameter.name == null
          || parameter.name.isBlank()
          || parameter.value == null) throw new BizException(ErrorCodes.INVALID, "商品参数无效", 400);
    }
    productSqlMapper.deleteParameters(productId);
    for (ProductParameterDTO parameter : parameters)
      productSqlMapper.insertParameter(productId, parameter, now());
  }

  /** 验证商品名称、金额并补充原分类默认值。 */
  private void validateProduct(ProductDTO request) {
    if (request == null || request.name == null || request.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "商品名称不能为空", 400);
    valueCodec.formatPrice(request.price);
    if (request.categoryId == null) request.categoryId = 0L;
  }

  /** 读取原商品版本并在当前事务中追加索引事件，异常直接交给事务调用方。 */
  private void appendIndexEvent(long productId) {
    Long storedVersion = productSqlMapper.findProductVersion(productId);
    long productVersion = storedVersion == null ? 0L : storedVersion;
    String traceId = ProductEventEnvelope.traceId(UUID.randomUUID().toString());
    productIndexOutboxService.append("PRODUCT_CHANGED", productId, productVersion, traceId);
  }
}
