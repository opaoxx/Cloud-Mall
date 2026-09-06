package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the current MySQL fact before projecting it to Elasticsearch. */
@Component
public class ProductIndexDocumentLoader {
  /** 保存 db 的业务状态或配置。 */
  private final JdbcTemplate db;

  /** 创建 ProductIndexDocumentLoader 实例。 */
  public ProductIndexDocumentLoader(JdbcTemplate db) {
    // 1. 接收并整理 ProductIndexDocumentLoader 的业务请求。
    // 2. 执行 ProductIndexDocumentLoader 的核心业务校验与状态处理。
    // 3. 返回 ProductIndexDocumentLoader 的处理结果。
    this.db = db;
  }

  /** 执行 load 相关操作。 */
  public Optional<ProductIndexDocument> load(long productId) {
    // 1. 接收并整理 load 的业务请求。
    // 2. 执行 load 的核心业务校验与状态处理。
    // 3. 返回 load 的处理结果。
    List<ProductIndexDocument> documents =
        db.query(
            "select id,category_id,name,main_image,description,price,status from product where"
                + " id=?",
            (rs, rowNum) ->
                new ProductIndexDocument(
                    rs.getLong("id"),
                    rs.getLong("category_id"),
                    rs.getString("name"),
                    rs.getString("main_image"),
                    rs.getString("description"),
                    rs.getBigDecimal("price"),
                    rs.getInt("status")),
            productId);
    return documents.stream().findFirst();
  }
}
