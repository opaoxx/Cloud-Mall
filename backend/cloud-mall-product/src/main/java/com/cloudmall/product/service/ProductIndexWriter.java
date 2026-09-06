package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
public interface ProductIndexWriter {
    void upsert(ProductIndexDocument document) throws Exception;

    void delete(long productId) throws Exception;
}
