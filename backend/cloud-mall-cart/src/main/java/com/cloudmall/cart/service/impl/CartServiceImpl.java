package com.cloudmall.cart.service.impl;

import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartInvalidItemVO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.domain.vo.CartSettlementPreviewVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.CartService;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 编排购物车数量、商品事实、勾选状态与结算预览。 */
@Service
public class CartServiceImpl implements CartService {
  /** Cart条件更新最多三轮确认未写后的重读，未知Redis执行结果不得重试。 */
  private static final int CART_COMPARE_ATTEMPTS = 3;

  /** 封装 Redis Hash、原 JSON 与条件写删的存储实现。 */
  private final CartStorage cartStorage;

  /** 提供当前 SKU 身份、名称与价格事实的商品客户端。 */
  private final ProductClient productClient;

  /** 创建购物车业务服务并注入存储与商品查询依赖。 */
  public CartServiceImpl(CartStorage cartStorage, ProductClient productClient) {
    this.cartStorage = cartStorage;
    this.productClient = productClient;
  }

  /**
   * 按原JSON条件刷新商品摘要后返回购物车，冲突时要求客户端重新读取。
   *
   * @return 原购物车项数组，商品失效项继续可见
   * @throws BizException 已确认刷新冲突为409，坏数据或未知结果为500，Redis故障为503
   */
  @Override
  public List<CartItemVO> listItems(Long userId) {
    // 1. 在刷新流程中读取同次完整Hash与原JSON快照。
    // 2. 每项商品事实只在原快照仍成立时写入，失败停止后续刷新。
    // 3. 返回成功刷新的同一项数组，失效商品沿原可见语义保留。
    return refreshItems(userId).stream().map(CartItemVO::from).toList();
  }

  /**
   * 使用当前商品事实条件合并数量；确定竞争失败不写，执行结果未知不重复应用。
   *
   * @param userId 原购物车所属用户
   * @param request 原SKU与正int数量请求，客户端商品摘要不作为事实
   * @return 同一持久化项构造的购物车响应
   * @throws BizException 非法请求/合并溢出为400，非正存量/未知结果为500，竞争耗尽为409，Redis故障为503
   */
  @Override
  public CartItemVO addItem(Long userId, CartItemDTO request) {
    // 1. 保留请求正数校验并读取当前商品事实。
    if (request.skuId == null || request.quantity < 1)
      throw new BizException(ErrorCodes.INVALID, "购物车数量必须为正数", 400);
    ProductSkuDTO current = currentSku(request.skuId);
    // 2. 每轮取得同次原始JSON/合法PO，确认未写的竞争才重读并重算。
    String skuField = String.valueOf(request.skuId);
    for (int attempt = 0; attempt < CART_COMPARE_ATTEMPTS; attempt++) {
      Map<String, String> originalValues = new HashMap<>();
      CartItemPO item = cartStorage.readItems(userId, originalValues).get(skuField);
      if (item == null) {
        item = toPersistence(request);
        item.checked = true;
        item.addedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
      } else {
        if (item.quantity < 1) throw new BizException(ErrorCodes.INTERNAL, "购物车数量数据损坏", 500);
        long mergedQuantity = (long) item.quantity + request.quantity;
        if (mergedQuantity > Integer.MAX_VALUE)
          throw new BizException(ErrorCodes.INVALID, "购物车数量超过支持范围", 400);
        item.quantity = (int) mergedQuantity;
      }
      item.productId = current.productId();
      item.productName = current.productName();
      item.unitPrice = current.unitPrice();
      // 3. 条件写和TTL同脚本执行，只有此原JSON仍成立才返回本次响应。
      if (cartStorage.compareWrite(userId, skuField, originalValues.get(skuField), item))
        return CartItemVO.from(item);
    }
    throw new BizException(ErrorCodes.CONFLICT, "购物车已发生变化，请重试", 409);
  }

  /**
   * 条件修改已有项的绝对数量，并仅在请求提供checked时修改勾选状态。
   *
   * @param userId 原购物车所属用户
   * @param skuId 原购物车项SKU身份
   * @param request 正数绝对数量及可选勾选状态
   * @return 条件写成功的购物车项
   * @throws BizException 数量非法为400，缺项为404，竞争耗尽为409，未知结果为500，Redis故障为503
   */
  @Override
  public CartItemVO updateItem(Long userId, Long skuId, CartItemDTO request) {
    // 1. 校验并固定本次请求意图，后续重读不会改变请求字段。
    int requestedQuantity = request.quantity;
    Boolean requestedChecked = request.checked;
    if (requestedQuantity < 1) throw new BizException(ErrorCodes.INVALID, "数量必须为正数", 400);
    // 2. 每次只应用本次声明字段，保留最新快照的其他状态。
    CartItemVO updatedItem =
        mutateExisting(
            userId,
            skuId,
            item -> {
              item.quantity = requestedQuantity;
              if (requestedChecked != null) item.checked = requestedChecked;
            });
    // 3. 返回条件写入成功的同一项，不重新查询商品事实。
    return updatedItem;
  }

  /** 删除原用户的指定字段，允许通过显式删除移除损坏项。 */
  @Override
  public void deleteItem(Long userId, Long skuId) {
    // 1. 固定所属用户与目标 SKU。
    // 2. 直接删除指定字段，保持原 Redis 故障映射。
    cartStorage.deleteItem(userId, skuId);
  }

  /**
   * 读取原用户的合法购物车项快照，不刷新商品、写入或续期。
   *
   * @param userId 原购物车所属用户
   * @param skuId 原SKU身份
   * @return 原始JSON快照，目标缺失时data为null
   * @throws BizException Hash身份损坏为500，Redis故障为503
   */
  @Override
  public CartCleanupSnapshotDTO getCleanupSnapshot(Long userId, Long skuId) {
    // 1. 同次完整Hash校验并保存原始JSON，保持原用户上下文。
    Map<String, String> originalValues = new HashMap<>();
    Map<String, CartItemPO> items = cartStorage.readItems(userId, originalValues);
    // 2. 只有规范存在项提供证明，缺项不构造猜测快照。
    String skuField = String.valueOf(skuId);
    CartCleanupSnapshotDTO snapshot =
        items.containsKey(skuField)
            ? new CartCleanupSnapshotDTO(skuId, originalValues.get(skuField))
            : null;
    // 3. 返回不可变原值，不查询Product或刷新TTL。
    return snapshot;
  }

  /**
   * 使用跨服务原JSON证明条件清理，已缺失或变更的项保留且返回false。
   *
   * @param userId 原购物车所属用户
   * @param skuId 路径原SKU身份
   * @param snapshot 提交前捕获的不可变原JSON证明
   * @return true表示已删除，false表示目标已缺失或变化
   * @throws BizException 不可信证明为400，删除结果未知为500，Redis故障为503
   */
  @Override
  public boolean cleanupItem(Long userId, Long skuId, CartCleanupSnapshotDTO snapshot) {
    // 1. 路径、DTO和原JSON数值身份一致才授权Redis操作。
    if (!cartStorage.matchesCleanupSnapshot(skuId, snapshot))
      throw new BizException(ErrorCodes.INVALID, "购物车清理快照不可信", 400);
    // 2. 每项一次原JSON条件删除，变更不重读、不无条件fallback、不续TTL。
    boolean removed =
        cartStorage.compareDelete(userId, String.valueOf(skuId), snapshot.expectedJson());
    // 3. 只返回已确认结果，执行未知或依赖故障不自动重复。
    return removed;
  }

  /**
   * 条件修改已有项的勾选状态，保留并发更新后的数量和商品摘要。
   *
   * @param userId 原购物车所属用户
   * @param skuId 原购物车项SKU身份
   * @param checked 沿既有Boolean.TRUE语义读取的勾选意图
   * @return 条件写成功的购物车项
   * @throws BizException 缺项为404，竞争耗尽为409，未知结果为500，Redis故障为503
   */
  @Override
  public CartItemVO setItemChecked(Long userId, Long skuId, Boolean checked) {
    // 1. 固定本次勾选意图，缺失或null仍沿既有false语义。
    boolean requestedChecked = Boolean.TRUE.equals(checked);
    // 2. 只修改勾选字段，确认竞争失败后才重读最新项。
    CartItemVO updatedItem = mutateExisting(userId, skuId, item -> item.checked = requestedChecked);
    // 3. 返回成功写入的同一项，不覆盖最新数量。
    return updatedItem;
  }

  /**
   * 只清理初读已勾选且原JSON未变化的项，保留并发修改后的购物车项。
   *
   * @throws BizException Hash数据损坏或删除结果未知为500，Redis故障为503；前序删除不回滚
   */
  @Override
  public void clearCheckedItems(Long userId) {
    // 1. 同次读取原JSON与PO，整张Hash身份校验通过后才授权删除。
    Map<String, String> originalValues = new HashMap<>();
    Map<String, CartItemPO> items = cartStorage.readItems(userId, originalValues);
    // 2. 仅对初读勾选项执行一次条件删除，不重读或追删已变更的项。
    for (Map.Entry<String, CartItemPO> entry : items.entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue().checked))
        cartStorage.compareDelete(userId, entry.getKey(), originalValues.get(entry.getKey()));
    }
    // 3. 保留成功null语义，不把逐项清理当作整张Hash事务。
  }

  /**
   * 预检已选数量后读取当前商品事实并计算预览金额，坏数量不触发写回。
   *
   * @param userId 原购物车所属用户
   * @param selectedSkuIds 原skuIds选择，空集合继续按checked选中项处理
   * @return 原items/invalidItems和金额字符串响应
   * @throws BizException 刷新冲突为409，已选数量损坏或未知结果为500，Redis失败为503，商品失效沿原分类
   */
  @Override
  public CartSettlementPreviewVO previewSettlement(Long userId, List<Long> selectedSkuIds) {
    // 1. 保留显式SKU和checked选择，只预检本次选中集合。
    Set<Long> selected = new HashSet<>(selectedSkuIds);
    Map<String, String> originalValues = new HashMap<>();
    List<CartItemPO> items =
        cartStorage.readItems(userId, originalValues).values().stream()
            .filter(
                item ->
                    selected.isEmpty()
                        ? Boolean.TRUE.equals(item.checked)
                        : selected.contains(item.skuId))
            .collect(Collectors.toList());
    for (CartItemPO item : items) {
      if (item.quantity < 1) throw new BizException(ErrorCodes.INTERNAL, "购物车数量数据损坏", 500);
    }
    // 2. 全部数量合法后才查商品、刷新摘要与累加金额，原失效项语义保持。
    List<CartInvalidItemVO> invalidItems = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (CartItemPO item : items) {
      ProductSkuDTO current;
      try {
        current = currentSku(item.skuId);
      } catch (BizException invalidSku) {
        if (invalidSku.getHttpStatus() >= 500) throw invalidSku;
        invalidItems.add(
            new CartInvalidItemVO(item.skuId, invalidSku.getCode(), invalidSku.getMessage()));
        continue;
      }
      // 条件写和金额在商品失效catch之外，409/未知结果不能当作invalidItems吞掉。
      refreshItem(userId, originalValues.get(String.valueOf(item.skuId)), item, current);
      total =
          total.add(
              new BigDecimal(current.unitPrice()).multiply(BigDecimal.valueOf(item.quantity)));
    }
    // 3. 返回原字段/金额格式，不把预览当作库存锁定或下单价格依据。
    return new CartSettlementPreviewVO(
        items.stream().map(CartItemVO::from).toList(),
        invalidItems,
        total.toPlainString(),
        total.toPlainString());
  }

  /** 同次完整快照上的逐项摘要刷新，已确认冲突停止请求，商品失效项仍保留可见。 */
  private List<CartItemPO> refreshItems(Long userId) {
    // 1. 读取完整合法PO与原JSON，绑定本次原用户Cart键。
    Map<String, String> originalValues = new HashMap<>();
    List<CartItemPO> items =
        new ArrayList<>(cartStorage.readItems(userId, originalValues).values());
    // 2. 仅商品读取保留原失效语义，条件写异常直接传播。
    for (CartItemPO item : items) {
      ProductSkuDTO current;
      try {
        current = currentSku(item.skuId);
      } catch (BizException invalidSku) {
        if (invalidSku.getHttpStatus() >= 500) throw invalidSku;
        continue;
      }
      refreshItem(userId, originalValues.get(String.valueOf(item.skuId)), item, current);
    }
    // 3. 前序成功不整批回滚，成功响应只由当前快照项构造。
    return items;
  }

  /** 只更新当前商品摘要，保留快照数量/勾选/时间。每项仅一次原JSON条件写，0冲突抛409； 未知结果或依赖异常原样传播，不重读、不补偿前序刷新。 */
  private void refreshItem(
      Long userId, String expectedJson, CartItemPO item, ProductSkuDTO current) {
    item.productId = current.productId();
    item.productName = current.productName();
    item.unitPrice = current.unitPrice();
    if (!cartStorage.compareWrite(userId, String.valueOf(item.skuId), expectedJson, item))
      throw new BizException(ErrorCodes.CONFLICT, "购物车已发生变化，请重试", 409);
  }

  /** 从同次HGETALL的原JSON和合法PO修改已有项。仅0确认未写时重读，缺项直接404； 条件写成功才续TTL，未知执行结果与Redis异常传播且不重复修改。 */
  private CartItemVO mutateExisting(Long userId, Long skuId, Consumer<CartItemPO> mutation) {
    String skuField = String.valueOf(skuId);
    for (int attempt = 0; attempt < CART_COMPARE_ATTEMPTS; attempt++) {
      Map<String, String> originalValues = new HashMap<>();
      CartItemPO item = cartStorage.readItems(userId, originalValues).get(skuField);
      if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
      mutation.accept(item);
      if (cartStorage.compareWrite(userId, skuField, originalValues.get(skuField), item))
        return CartItemVO.from(item);
    }
    throw new BizException(ErrorCodes.CONFLICT, "购物车已发生变化，请重试", 409);
  }

  /** 核成功状态、原SKU及价格语法后返回原事实，坏证明抛500阻止当前项回写/结算。 */
  private ProductSkuDTO currentSku(Long skuId) {
    // 1. 保持原查询和缺失响应/数据的404语义。
    ApiResponse<ProductSkuDTO> response = productClient.getSku(skuId);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "商品或SKU不存在", 404);
    // 2. 可反序列化不代表可信，错误码或别SKU不得提供当前购物车价格。
    if (!"0".equals(response.code)
        || skuId == null
        || !Objects.equals(skuId, response.data.skuId()))
      throw new BizException(ErrorCodes.INTERNAL, "商品SKU响应身份或状态不可信", 500);
    // 3. 价格先验语法，不规范化或回写解析结果，保留原字符串金额语义。
    if (response.data.unitPrice() == null)
      throw new BizException(ErrorCodes.INTERNAL, "商品价格数据不可信", 500);
    try {
      new BigDecimal(response.data.unitPrice());
    } catch (NumberFormatException malformedPrice) {
      BizException invalidPrice = new BizException(ErrorCodes.INTERNAL, "商品价格数据不可信", 500);
      invalidPrice.addSuppressed(malformedPrice);
      throw invalidPrice;
    }
    return response.data;
  }

  /** 将购物车请求转换为 Redis 持久化对象。 */
  private CartItemPO toPersistence(CartItemDTO request) {
    CartItemPO item = new CartItemPO();
    item.skuId = request.skuId;
    item.productId = request.productId;
    item.productName = request.productName;
    item.unitPrice = request.unitPrice;
    item.quantity = request.quantity;
    item.checked = request.checked;
    item.addedAt = request.addedAt;
    return item;
  }
}
