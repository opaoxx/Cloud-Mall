package com.cloudmall.product.domain.dto;

/**
 * 查询结果与本次指标登记的关联信息，由 HTTP 层装配统一响应。
 *
 * @param data 查询得到的业务响应对象
 * @param requestId 指标登记关联号，指标关闭时为 null
 */
public record ProductReadResultDTO<T>(T data, String requestId) {}
