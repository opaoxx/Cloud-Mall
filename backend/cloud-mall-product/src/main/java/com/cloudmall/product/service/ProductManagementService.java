package com.cloudmall.product.service;

import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.vo.ProductVO;

/** 管理员商品写入与索引事件的本地事务契约。 */
public interface ProductManagementService {
  /** 在同一事务内保存商品、SKU、参数及索引 outbox。 */
  ProductVO create(ProductOperatorDTO operator, ProductDTO request);

  /** 更新商品事实和版本，并写入索引 outbox。 */
  ProductVO update(ProductOperatorDTO operator, Long productId, ProductDTO request);

  /** 在本地事务内上架商品并登记索引事件。 */
  ProductVO publish(ProductOperatorDTO operator, Long productId);

  /** 在本地事务内下架商品并登记索引事件。 */
  ProductVO unpublish(ProductOperatorDTO operator, Long productId);
}
