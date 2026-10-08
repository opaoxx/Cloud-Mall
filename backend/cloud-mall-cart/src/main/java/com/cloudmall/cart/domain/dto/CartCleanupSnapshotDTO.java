package com.cloudmall.cart.domain.dto;

/**
 * 跨服务清理的不可变原始购物车项快照，仅授权原JSON未变化的目标删除。
 *
 * @param skuId 快照所属的原SKU身份
 * @param expectedJson 同次Hash读取的原始JSON，不重序列化或归一化
 */
public record CartCleanupSnapshotDTO(Long skuId, String expectedJson) {}
