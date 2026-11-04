package com.cloudmall.product.domain.dto;

/**
 * HTTP 层读取并传入业务层的操作者身份。
 *
 * @param userId 原认证上下文中的用户主键，未认证时为 null
 * @param role 原认证上下文中的角色
 */
public record ProductOperatorDTO(Long userId, String role) {}
