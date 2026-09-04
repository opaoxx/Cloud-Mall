package com.cloudmall.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Reads the current MySQL fact before projecting it to Elasticsearch. */
@Component
public class ProductIndexDocumentLoader {
    private final JdbcTemplate db;

    public ProductIndexDocumentLoader(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<ProductIndexDocument> load(long productId) {
        List<ProductIndexDocument> documents = db.query(
                "select id,category_id,name,main_image,description,price,status from product where id=?",
                (rs, rowNum) -> new ProductIndexDocument(rs.getLong("id"), rs.getLong("category_id"),
                        rs.getString("name"), rs.getString("main_image"), rs.getString("description"),
                        rs.getBigDecimal("price"), rs.getInt("status")), productId);
        return documents.stream().findFirst();
    }
}
