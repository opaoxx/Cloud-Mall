package com.cloudmall.stock.service.impl.seckill;


import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.util.SeckillAcceptanceDimensions;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 秒杀配额最终结算事实服务；不触碰普通库存流水。 */
@Service
public class SeckillQuotaSettlementService {
  /** Enabled-only sale outbox uses the settlement transaction and actual post-CAS revision. */
  @Autowired(required = false)
  private com.cloudmall.stock.service.impl.metrics.StockSalesOutboxService salesMetrics;

  /** Enablement may not silently fall back to an unwired legacy confirmation. */
  @org.springframework.beans.factory.annotation.Value(
      "${cloudmall.stock.sales-metrics.enabled:false}")
  private boolean salesMetricsEnabled;

  /** Require the actual transaction proxy whenever first-confirm metrics are enabled. */
  @javax.annotation.PostConstruct
  public void validateSalesMetrics() {
    if (salesMetricsEnabled
        && (salesMetrics == null
            || !org.springframework.aop.support.AopUtils.isAopProxy(salesMetrics)))
      throw new IllegalStateException("SALES_TRANSACTION_PROXY_REQUIRED");
  }

  /** 秒杀结算事实 Mapper。 */
  private final SeckillQuotaSettlementMapper settlementMapper;

  /** 只读读取历史 ACCEPTED 事实，GET 不通过它写入结算行。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建秒杀结算服务。 */
  @Autowired
  public SeckillQuotaSettlementService(
      SeckillQuotaSettlementMapper settlementMapper, StockSqlMapper stockSqlMapper) {
    this.settlementMapper = settlementMapper;
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 启动时幂等建表并为已有 ACCEPTED 事实补建 OPEN，不改受理状态。 */
  @PostConstruct
  public void ensureTable() {
    // 1. 先兼容已有数据卷的受理表，再创建独立结算事实。
    stockSqlMapper.ensureSeckillLedgerTable();
    settlementMapper.ensureTable();
    settlementMapper.insertMissingAcceptedOpen(now());
  }

  /** 查询秒杀结算状态，未找到秒杀事实时返回 null 交给普通库存路径。 */
  public StockReservationVO reservation(String orderNo) {
    // 1. 只读取结算行；缺行时读取旧受理事实，不在 GET 中迁移。
    if (orderNo == null || orderNo.isBlank()) return null;
    SeckillQuotaSettlementPO settlement = settlementMapper.findSettlement(orderNo);
    if (settlement == null) {
      Map<String, Object> accepted = stockSqlMapper.findSeckillByOrderNo(orderNo);
      if (accepted == null || !"ACCEPTED".equals(String.valueOf(accepted.get("status"))))
        return null;
      // CI查询仅返回候选；原身份缺失或错别名不能作为当前订单的保留事实。
      if (!Objects.equals(orderNo, accepted.get("order_no")))
        return unknownSettlementProof(orderNo);
      long skuId = requiredPositive(accepted.get("sku_id"), "sku_id");
      // 原SKU优先保持；缺活动/用户/幂等维度不能把历史受理包装为可用库存。
      if (!SeckillAcceptanceDimensions.hasValidContext(accepted))
        throw new BizException("SECKILL_SETTLEMENT_CORRUPT", "秒杀受理事实维度无效", 500);
      return new StockReservationVO(
          orderNo,
          "RESERVED",
          List.of(new com.cloudmall.stock.domain.vo.StockReservationItemVO(skuId, 1, "RESERVED")));
    }
    // 2. 先核原订单号再验维度；不能借别单终态或落入普通库存路径。
    if (!Objects.equals(orderNo, settlement.orderNo)) return unknownSettlementProof(orderNo);
    validateSettlement(settlement);
    // 3. 五种结算状态核操作键，RELEASED 再核持久回执；缺证仍返回已观察候选，阻止普通库存fallback。
    if (!settlementOperationKeyMatches(settlement) || !releasedReceiptMatches(settlement))
      return unknownSettlementProof(orderNo);
    // 4. 非五状态不输出未经证明的明细；已知状态再观察原来源，未知不能落普通库存。
    if (!("OPEN".equals(settlement.status)
            || "RELEASE_PENDING".equals(settlement.status)
            || "PROJECTION_BLOCKED".equals(settlement.status)
            || "CONFIRMED".equals(settlement.status)
            || "RELEASED".equals(settlement.status))
        || settlementMapper.findMatchingAcceptanceId(settlement) == null)
      return unknownSettlementProof(orderNo);
    // 5. 通过本次观察后仅映射查询状态；两个点读不代表原子来源证明。
    String status = externalStatus(settlement.status);
    return new StockReservationVO(
        orderNo,
        status,
        List.of(
            new com.cloudmall.stock.domain.vo.StockReservationItemVO(
                settlement.skuId, settlement.quantity, status)));
  }

  /** 原身份、操作键、释放回执或受理来源缺证仍是已观察候选；空明细不回显别单SKU或允许fallback。 */
  private static StockReservationVO unknownSettlementProof(String orderNo) {
    return new StockReservationVO(orderNo, "UNKNOWN", List.of());
  }

  /** 按既有库存动作协议确认或释放秒杀配额；rollback 只提交待投影事实。 */
  @Transactional
  public StockActionResultVO settle(String orderNo, String actionType) {
    if (orderNo == null || orderNo.isBlank()) return null;
    if (!"CONFIRM".equals(actionType) && !"ROLLBACK".equals(actionType)) {
      throw new BizException("COMMON_INVALID_ARGUMENT", "秒杀结算动作无效", 400);
    }
    // 1. 只从已提交 ACCEPTED 受理事实补建 OPEN，禁止请求参数拼接结算维度。
    // 先插入再锁定，避免两个存量请求先取得相同缺行 gap lock 再相互等待插入。
    settlementMapper.insertOpenIfAbsent(orderNo, now());
    SeckillQuotaSettlementPO settlement = settlementMapper.findSettlementForUpdate(orderNo);
    if (settlement == null) {
      return null;
    }
    // CI锁定行只是候选；拒绝错原订单，让同事务中的临时OPEN插入整体回滚。
    if (!Objects.equals(orderNo, settlement.orderNo))
      throw new BizException("SECKILL_SETTLEMENT_CONFLICT", "秒杀结算原订单归属冲突", 409);
    validateSettlement(settlement);
    if (!settlementOperationKeyMatches(settlement))
      throw new BizException("SECKILL_SETTLEMENT_CORRUPT", "秒杀结算操作键缺证", 500);
    if (!releasedReceiptMatches(settlement))
      throw new BizException("SECKILL_SETTLEMENT_CORRUPT", "秒杀释放回执缺证", 500);
    String operationKey = orderNo + ":" + actionType;
    String status = settlement.status;
    long revision = settlement.revision;
    // 2. 在 MySQL 中先完成唯一状态 CAS；Redis 只由恢复器在提交后投影。
    if ("CONFIRM".equals(actionType)) {
      if ("CONFIRMED".equals(status)) {
        // 同向终态重放也核原受理来源；只读观察不替代原事实，不宣称源冻结。
        if (settlementMapper.findMatchingAcceptanceId(settlement) == null)
          throw unknownCas(orderNo);
        return StockActionResultVO.confirmed(true);
      }
      if ("RELEASED".equals(status)
          || "RELEASE_PENDING".equals(status)
          || ("PROJECTION_BLOCKED".equals(status) && !operationKey.equals(settlement.operationKey)))
        return conflict();
      int changed =
          "PROJECTION_BLOCKED".equals(status)
              ? settlementMapper.transitionBlockedToConfirmed(orderNo, operationKey, revision)
              : settlementMapper.transitionOpenToConfirmed(orderNo, operationKey, revision);
      if (changed == 1) {
        if (salesMetrics != null) {
          SeckillQuotaSettlementPO persisted = settlementMapper.findSettlementForUpdate(orderNo);
          Map<String, Object> sku = stockSqlMapper.findStockForUpdate(settlement.skuId);
          if (persisted == null
              || !orderNo.equals(persisted.orderNo)
              || !"CONFIRMED".equals(persisted.status)
              || persisted.revision == null
              || sku == null
              || !(sku.get("product_id") instanceof Number)
              || ((Number) sku.get("product_id")).longValue() <= 0)
            throw com.cloudmall.stock.service.impl.metrics.StockSalesOutboxService.unavailable(
                null);
          salesMetrics.seckill(
              orderNo,
              settlement.skuId,
              ((Number) sku.get("product_id")).longValue(),
              persisted.revision,
              org.slf4j.MDC.get("traceId"));
        }
        return StockActionResultVO.confirmed(false);
      }
      throw unknownCas(orderNo);
    }
    if ("RELEASED".equals(status)) {
      // 同向释放终态复查原受理来源；反向冲突仍保原语义且不读取来源。
      if (settlementMapper.findMatchingAcceptanceId(settlement) == null) throw unknownCas(orderNo);
      return StockActionResultVO.rolledBack(true);
    }
    if ("CONFIRMED".equals(status)) return conflict();
    if ("OPEN".equals(status)) {
      int changed = settlementMapper.transitionToReleasePending(orderNo, operationKey, revision);
      if (changed == 1) return pendingRollback();
      throw unknownCas(orderNo);
    }
    if ("RELEASE_PENDING".equals(status) || "PROJECTION_BLOCKED".equals(status)) {
      if (!operationKey.equals(settlement.operationKey)) return conflict();
      // 已有待释放重试也核原受理来源；只观察，不修改租约或诊断以补证。
      if (settlementMapper.findMatchingAcceptanceId(settlement) == null) throw unknownCas(orderNo);
      return pendingRollback();
    }
    return conflict();
  }

  /** 对外隐藏待投影内部状态，避免订单动作误判为成功。 */
  private static StockActionResultVO pendingRollback() {
    return StockActionResultVO.pendingRollback();
  }

  /** 返回与普通库存动作一致的冲突语义。 */
  private static StockActionResultVO conflict() {
    throw new BizException("SECKILL_SETTLEMENT_CONFLICT", "秒杀结算动作与既有终态冲突", 409);
  }

  /** CAS 竞争或提交结果未知时返回 503，交给订单动作恢复器重试。 */
  private static BizException unknownCas(String orderNo) {
    return new BizException("SECKILL_SETTLEMENT_UNKNOWN", "秒杀结算提交结果未知: " + orderNo, 503);
  }

  /** 按五种既有状态核原订单与允许方向；OPEN空键兼容，未知状态不扩展规则。 */
  private static boolean settlementOperationKeyMatches(SeckillQuotaSettlementPO settlement) {
    String confirmKey = settlement.orderNo + ":CONFIRM";
    String rollbackKey = settlement.orderNo + ":ROLLBACK";
    if ("CONFIRMED".equals(settlement.status))
      return Objects.equals(confirmKey, settlement.operationKey);
    if ("RELEASED".equals(settlement.status) || "RELEASE_PENDING".equals(settlement.status))
      return Objects.equals(rollbackKey, settlement.operationKey);
    if ("OPEN".equals(settlement.status) && settlement.operationKey == null) return true;
    if ("OPEN".equals(settlement.status) || "PROJECTION_BLOCKED".equals(settlement.status))
      return Objects.equals(confirmKey, settlement.operationKey)
          || Objects.equals(rollbackKey, settlement.operationKey);
    return true;
  }

  /** RELEASED 仅接收原释放 Lua 的合法回执域；数值合法不代表原 Redis 来源已证明。 */
  private static boolean releasedReceiptMatches(SeckillQuotaSettlementPO settlement) {
    return !"RELEASED".equals(settlement.status)
        || (settlement.redisRevision != null
            && settlement.redisRevision > 0
            && settlement.redisRevision <= 1000000000000L);
  }

  /** 严格拒绝损坏的结算维度，避免以默认值掩盖事实。 */
  private static void validateSettlement(SeckillQuotaSettlementPO settlement) {
    if (settlement.revision == null
        || settlement.revision <= 0
        || settlement.quantity == null
        || settlement.quantity != 1
        || settlement.activityId == null
        || settlement.activityId <= 0
        || settlement.skuId == null
        || settlement.skuId <= 0
        || settlement.userId == null
        || settlement.userId <= 0
        || settlement.idempotencyKey == null
        || "null".equals(settlement.idempotencyKey)
        || settlement.idempotencyKey.isBlank()
        || settlement.idempotencyKey.codePointCount(0, settlement.idempotencyKey.length()) > 128) {
      throw new BizException("SECKILL_SETTLEMENT_CORRUPT", "秒杀结算事实维度无效", 500);
    }
  }

  /** 旧台账缺少合法 SKU 时保持错误，禁止默认成可结算事实。 */
  private static long requiredPositive(Object value, String name) {
    try {
      // JDBC整数/DECIMAL保持无损；浮点可能已丢原精度，不能据此补造SKU。
      if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
        throw new IllegalArgumentException();
      BigDecimal original =
          value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
      long skuId = original.longValueExact();
      if (skuId <= 0) throw new IllegalArgumentException();
      return skuId;
    } catch (IllegalArgumentException | ArithmeticException invalidIdentity) {
      throw new BizException("SECKILL_SETTLEMENT_CORRUPT", "秒杀受理事实 " + name + " 无效", 500);
    }
  }

  /** 获取与 MySQL DATETIME(3) 一致的上海本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  /** 将内部结算状态映射为既有库存查询状态。 */
  private static String externalStatus(String status) {
    if ("CONFIRMED".equals(status)) return "CONFIRMED";
    if ("RELEASED".equals(status)) return "ROLLED_BACK";
    if ("OPEN".equals(status)) return "RESERVED";
    return "UNKNOWN";
  }
}
