package com.cloudmall.cart.controller;

import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.domain.vo.CartSettlementPreviewVO;
import com.cloudmall.cart.service.CartService;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 购物车 HTTP 接口层，负责暴露购物车请求映射。 */
@RestController
@RequestMapping("/api/cart")
public class CartController {
  /** 负责指定用户购物车规则与持久化编排的业务服务。 */
  private final CartService cartService;

  /** 创建购物车接口控制器。 */
  public CartController(CartService cartService) {
    this.cartService = cartService;
  }

  /** 获取当前用户的购物车项与最新商品摘要。 */
  @GetMapping
  public ApiResponse<List<CartItemVO>> all() {
    return ApiResponse.ok(cartService.listItems(AuthContext.requireUserId()));
  }

  /** 新增购物车项或合并数量。 */
  @PostMapping("/items")
  public ApiResponse<CartItemVO> add(@RequestBody CartItemDTO request) {
    return ApiResponse.ok(cartService.addItem(AuthContext.requireUserId(), request));
  }

  /** 更新购物车项数量和请求提供的勾选状态。 */
  @PutMapping("/items/{skuId}")
  public ApiResponse<CartItemVO> update(
      @PathVariable("skuId") Long skuId, @RequestBody CartItemDTO request) {
    return ApiResponse.ok(cartService.updateItem(AuthContext.requireUserId(), skuId, request));
  }

  /** 删除当前用户的指定购物车项。 */
  @DeleteMapping("/items/{skuId}")
  public ApiResponse<Void> delete(@PathVariable("skuId") Long skuId) {
    cartService.deleteItem(AuthContext.requireUserId(), skuId);
    return ApiResponse.ok(null);
  }

  /** 提供订单提交前采样所需的原 JSON 快照，缺项时返回 null。 */
  @GetMapping("/items/{skuId}/cleanup-snapshot")
  public ApiResponse<CartCleanupSnapshotDTO> cleanupSnapshot(@PathVariable("skuId") Long skuId) {
    return ApiResponse.ok(cartService.getCleanupSnapshot(AuthContext.requireUserId(), skuId));
  }

  /** 根据订单提交前的快照执行条件清理。 */
  @PostMapping("/items/{skuId}/cleanup")
  public ApiResponse<Boolean> cleanupItem(
      @PathVariable("skuId") Long skuId, @RequestBody CartCleanupSnapshotDTO snapshot) {
    return ApiResponse.ok(cartService.cleanupItem(AuthContext.requireUserId(), skuId, snapshot));
  }

  /** 更新勾选状态，缺少 checked 或其值为 null 时按 false 处理。 */
  @PutMapping("/items/{skuId}/checked")
  public ApiResponse<CartItemVO> checked(
      @PathVariable("skuId") Long skuId, @RequestBody Map<String, Boolean> body) {
    return ApiResponse.ok(
        cartService.setItemChecked(AuthContext.requireUserId(), skuId, body.get("checked")));
  }

  /** 清理原快照仍成立的已勾选项。 */
  @DeleteMapping("/checked-items")
  public ApiResponse<Void> clearChecked() {
    cartService.clearCheckedItems(AuthContext.requireUserId());
    return ApiResponse.ok(null);
  }

  /** 返回当前选中项的金额及失效项，空选择按 checked 处理。 */
  @PostMapping("/settlement/preview")
  public ApiResponse<CartSettlementPreviewVO> preview(@RequestBody Map<String, List<Long>> body) {
    return ApiResponse.ok(
        cartService.previewSettlement(
            AuthContext.requireUserId(), body.getOrDefault("skuIds", List.of())));
  }
}
