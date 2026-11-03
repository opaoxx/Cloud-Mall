package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import java.util.List;

/** 以 MySQL 商品事实版本写入 ES 投影。 */
public interface ProductIndexWriter {
  /** 按商品主键和版本幂等更新单个投影。 */
  void upsert(ProductIndexDocument document) throws Exception;

  /** 兼容历史缺失商品事实的索引删除事件。 */
  void delete(long productId) throws Exception;

  /** Version-aware deletion is mandatory for V2; legacy implementations retain their original behavior. */
  default void delete(long productId,long eventVersion) throws Exception { delete(productId); }

  /** 将有界商品事实批次幂等投影到 ES，部分失败时抛出异常供游标重试。 */
  void upsertBatch(List<ProductIndexDocument> documents) throws Exception;

  /** 确保索引具有当前商品投影所需的显式字段映射。 */
  void ensureIndex() throws Exception;

  /** 检查当前目标索引是否仍存在，丢失后触发新代次重建。 */
  boolean indexExists() throws Exception;

  /** 读取 ES 索引文档总数，供重建后的事实核对。 */
  long countDocuments() throws Exception;
}
