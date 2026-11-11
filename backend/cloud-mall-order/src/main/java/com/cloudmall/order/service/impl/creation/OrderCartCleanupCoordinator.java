package com.cloudmall.order.service.impl.creation;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.feign.CartClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 普通订单的Cart证明采样和提交后条件清理；沿调用方现有事务，不创建新事务。 */
public final class OrderCartCleanupCoordinator {
  /** Cart证明、事务边界及逐SKU清理失败的诊断日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderCartCleanupCoordinator.class);

  /** 当前请求沿用的Cart客户端，回调只提交原证明。 */
  private final CartClient cartClient;

  /** 当前请求沿用的JSON解析配置，保留原数值和完整根校验规则。 */
  private final ObjectMapper objectMapper;

  /** 绑定当前创建请求使用的已有依赖，仅赋值，不执行远程操作或事务操作。 */
  public OrderCartCleanupCoordinator(CartClient cartClient, ObjectMapper objectMapper) {
    this.cartClient = cartClient;
    this.objectMapper = objectMapper;
  }

  /** 创建入口只读采样。只对购买聚合数量与原JSON正int数量一致的项提供不可变证明； long安全承载Java List中正int请求的总和，超int仅跳过清理，不改变订单流程。 */
  public List<CartCleanupSnapshotDTO> captureSnapshots(
      long userId, Map<Long, Long> purchaseQuantities) {
    // 1. 无真实事务或同步上下文时不调用Cart，也不猜测提交事实。
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn("订单购物车快照缺少实际事务上下文，保留购物车，userId={}", userId);
      return List.of();
    }
    // 2. 每个SKU只读一次，坏身份/数量或普通故障只保留该项，Error原传播。
    List<CartCleanupSnapshotDTO> captured = new ArrayList<>();
    for (Map.Entry<Long, Long> purchase : purchaseQuantities.entrySet()) {
      Long skuId = purchase.getKey();
      long purchasedQuantity = purchase.getValue();
      if (purchasedQuantity > Integer.MAX_VALUE) {
        log.warn("订单购买聚合数量超Cart范围，保留该项，userId={}，skuId={}", userId, skuId);
        continue;
      }
      try {
        ApiResponse<CartCleanupSnapshotDTO> response = cartClient.cleanupSnapshot(skuId, userId);
        // 成功空项没有可清理证明，安静跳过且不授权提交后删除。
        if (response != null && "0".equals(response.code) && response.data == null) continue;
        if (response == null
            || !"0".equals(response.code)
            || !matchesSnapshot(skuId, purchasedQuantity, response.data))
          throw new IllegalStateException("购物车清理快照身份或购买数量未确认");
        captured.add(response.data);
      } catch (RuntimeException snapshotFailure) {
        log.warn("订单购物车前置快照捕获失败，保留该项，userId={}，skuId={}", userId, skuId, snapshotFailure);
      }
    }
    // 3. 保存原DTO，创建末尾与提交回调不GET重建或替换证明。
    return List.copyOf(captured);
  }

  /** 创建成功末尾只注册原证明回调，提交后条件删除，不重读Cart或改变原用户。 */
  public void registerAfterCommit(
      String orderNo, long userId, List<CartCleanupSnapshotDTO> cleanupSnapshots) {
    try {
      // 1. 无实际事务或同步上下文时不能证明提交，禁止提前删除购物车。
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()) {
        log.warn("订单购物车清理缺少实际事务上下文，保留购物车，orderNo={}", orderNo);
        return;
      }
      // 2. 只注册入口捕获的不可变证明，业务失败或rollback不执行POST。
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            /** 订单已提交后逐SKU清理；普通失败隔离，Error保持传播。 */
            @Override
            public void afterCommit() {
              // 3. 远端清理失败不得反向回滚已提交订单或库存，继续后续SKU。
              for (CartCleanupSnapshotDTO snapshot : cleanupSnapshots) {
                Long skuId = snapshot.skuId();
                try {
                  ApiResponse<Boolean> removed = cartClient.cleanupItem(skuId, userId, snapshot);
                  if (removed == null || !"0".equals(removed.code) || removed.data == null)
                    throw new IllegalStateException("购物车清理响应未确认");
                } catch (RuntimeException cleanupFailure) {
                  log.warn("订单提交后购物车清理失败，orderNo={}，skuId={}", orderNo, skuId, cleanupFailure);
                }
              }
            }
          });
    } catch (RuntimeException registrationFailure) {
      log.warn("订单购物车清理回调注册失败，保留购物车，orderNo={}", orderNo, registrationFailure);
    }
  }

  /** 原DTO/JSON数值SKU及原正int数量须匹配购买聚合，不从坏响应拼接或重建证明。 */
  private boolean matchesSnapshot(
      Long skuId, long purchasedQuantity, CartCleanupSnapshotDTO snapshot) {
    if (snapshot == null
        || !Objects.equals(skuId, snapshot.skuId())
        || snapshot.expectedJson() == null
        || snapshot.expectedJson().isBlank()) return false;
    try {
      JsonNode proof =
          objectMapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(snapshot.expectedJson());
      JsonNode proofSku = proof == null ? null : proof.path("skuId");
      JsonNode proofQuantity = proof == null ? null : proof.path("quantity");
      return proof != null
          && proof.isObject()
          && proofSku.isIntegralNumber()
          && proofSku.canConvertToLong()
          && proofSku.longValue() == skuId.longValue()
          && proofQuantity.isIntegralNumber()
          && proofQuantity.canConvertToInt()
          && proofQuantity.intValue() > 0
          && proofQuantity.longValue() == purchasedQuantity;
    } catch (JsonProcessingException malformedProof) {
      return false;
    }
  }
}
