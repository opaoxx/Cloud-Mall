package com.cloudmall.product;

public interface ProductIndexWriter {
    void upsert(ProductIndexDocument document) throws Exception;

    void delete(long productId) throws Exception;
}
