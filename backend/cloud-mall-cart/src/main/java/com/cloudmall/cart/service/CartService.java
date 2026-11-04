package com.cloudmall.cart.service;

import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.domain.vo.CartSettlementPreviewVO;
import java.util.List;

/** 购物车领域业务服务契约。 */
public interface CartService {
  /** 刷新指定用户的商品摘要并返回购物车，商品失效项继续可见。 */
  List<CartItemVO> listItems(Long userId);

  /** 根据当前商品事实新增或条件合并购物车项。 */
  CartItemVO addItem(Long userId, CartItemDTO request);

  /** 条件更新目标项的数量和请求中提供的勾选状态。 */
  CartItemVO updateItem(Long userId, Long skuId, CartItemDTO request);

  /** 删除指定用户的 SKU 字段，允许删除数据损坏的目标项。 */
  void deleteItem(Long userId, Long skuId);

  /** 读取完整校验后的原 JSON 清理快照，目标缺失时返回 null。 */
  CartCleanupSnapshotDTO getCleanupSnapshot(Long userId, Long skuId);

  /** 按原始 JSON 快照执行一次条件删除，目标缺失或变化时返回 false。 */
  boolean cleanupItem(Long userId, Long skuId, CartCleanupSnapshotDTO snapshot);

  /** 条件更新勾选状态，同时保留并发修改后的数量和商品摘要。 */
  CartItemVO setItemChecked(Long userId, Long skuId, Boolean checked);

  /** 逐项清理初读已勾选且原 JSON 未变化的购物车项。 */
  void clearCheckedItems(Long userId);

  /** 校验选中项并计算结算预览，空 SKU 集合按 checked 选择。 */
  CartSettlementPreviewVO previewSettlement(Long userId, List<Long> selectedSkuIds);
}
