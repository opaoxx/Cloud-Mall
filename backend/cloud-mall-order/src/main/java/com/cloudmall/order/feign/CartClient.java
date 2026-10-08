package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-cart")
public interface CartClient {
  /**
   * 显式按SKU删除原用户购物车项；订单条件清理不得调用此接口作为fallback。
   *
   * @param skuId 原SKU身份
   * @param userId 原用户身份
   * @return 原响应信封
   */
  @DeleteMapping("/api/cart/items/{skuId}")
  ApiResponse<?> deleteItem(
      @PathVariable("skuId") Long skuId, @RequestHeader("X-User-Id") Long userId);

  /**
   * 事务提交前读取原用户的只读清理快照。
   *
   * @param skuId 原SKU身份
   * @param userId 原用户身份
   * @return 原始JSON不可变快照，缺项data为null
   */
  @GetMapping("/api/cart/items/{skuId}/cleanup-snapshot")
  ApiResponse<CartCleanupSnapshotDTO> cleanupSnapshot(
      @PathVariable("skuId") Long skuId, @RequestHeader("X-User-Id") Long userId);

  /**
   * 提交后一次条件删除未变化的原项，禁止自动重试或旧DELETE fallback。
   *
   * @param skuId 原SKU身份
   * @param userId 捕获时原用户身份
   * @param snapshot 捕获时原始JSON快照
   * @return code0/data true为删除，false为已缺失或变更
   */
  @PostMapping("/api/cart/items/{skuId}/cleanup")
  ApiResponse<Boolean> cleanupItem(
      @PathVariable("skuId") Long skuId,
      @RequestHeader("X-User-Id") Long userId,
      @RequestBody CartCleanupSnapshotDTO snapshot);
}
