package com.cloudmall.product.service;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.product.domain.dto.ProductReadResultDTO;
import com.cloudmall.product.domain.vo.HotStatVO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.domain.vo.SkuSummaryVO;

/** 商品事实查询、检索与指标登记契约。 */
public interface ProductQueryService {
  /** 按原 SQL/ES 路由与快照规则查询商品页并登记搜索事件。 */
  ProductReadResultDTO<PageResult<ProductVO>> products(
      String keyword, Long categoryId, Integer status, int page, int pageSize, String sort);

  /** 读取商品详情并返回其浏览登记关联号。 */
  ProductReadResultDTO<ProductVO> product(Long productId);

  /** 读取可购买 SKU 的事实、金额字符串及规格。 */
  SkuSummaryVO sku(Long skuId);

  /** 返回历史热度以及当前模式启用的在线指标。 */
  HotStatVO hot(Long productId);
}
