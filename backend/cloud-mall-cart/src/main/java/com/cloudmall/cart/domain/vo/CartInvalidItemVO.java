package com.cloudmall.cart.domain.vo;

/**
 * 结算预览中的失效商品及原业务错误。
 *
 * @param skuId 失效项的 SKU 主键
 * @param code 商品查询返回的业务错误码
 * @param message 商品查询返回的可读错误消息
 */
public record CartInvalidItemVO(Long skuId, String code, String message) {}
