package com.cloudmall.stock.service.impl;

import static com.cloudmall.stock.service.impl.StockReservationSnapshotCodec.sha256;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.StockReservationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 普通库存预扣及最终动作编排，秒杀终态委托已有结算状态机。 */
@Service
public class StockReservationServiceImpl implements StockReservationService {
  /** 记录本地提交后的旧Redis标记清理失败。 */
  private static final Logger log = LoggerFactory.getLogger(StockReservationServiceImpl.class);

  /** 仅用于清理旧协议标记，不用作库存数量事实。 */
  private static final String RESERVATION_KEY_PREFIX = "stock:reservation:";

  /** 提交后清理旧预扣标记的Redis客户端。 */
  private final StringRedisTemplate redis;

  /** 普通库存条件更新、流水、请求状态与修复意图。 */
  private final StockSqlMapper stockSqlMapper;

  /** 独立事务中认领原订单的规范SKU快照。 */
  private final StockReservationRequestService reservationRequestService;

  /** 秒杀订单最终动作的持久状态机。 */
  private final SeckillQuotaSettlementService seckillSettlementService;

  /** 与GET共用的原请求/完整流水证明。 */
  private final StockReservationReader reservationReader;

  /** 普通预扣规范快照序列化与摘要。 */
  private final StockReservationSnapshotCodec snapshotCodec;

  /** 启用时与库存确认同事务写入销量事件。 */
  private final com.cloudmall.stock.service.StockSalesOutboxService salesMetrics;

  /** 启动时必须核验销量事务代理的配置开关。 */
  private final boolean salesMetricsEnabled;

  /** 创建普通库存写入口，所有持久状态参与者由Spring提供真实代理。 */
  public StockReservationServiceImpl(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      StockReservationRequestService reservationRequestService,
      SeckillQuotaSettlementService seckillSettlementService,
      StockReservationReader reservationReader,
      StockReservationSnapshotCodec snapshotCodec,
      @org.springframework.lang.Nullable
          com.cloudmall.stock.service.StockSalesOutboxService salesMetrics,
      @org.springframework.beans.factory.annotation.Value(
              "${cloudmall.stock.sales-metrics.enabled:false}")
          boolean salesMetricsEnabled) {
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
    this.reservationRequestService = reservationRequestService;
    this.seckillSettlementService = seckillSettlementService;
    this.reservationReader = reservationReader;
    this.snapshotCodec = snapshotCodec;
    this.salesMetrics = salesMetrics;
    this.salesMetricsEnabled = salesMetricsEnabled;
  }

  /** Require the actual transaction proxy whenever first-confirm metrics are enabled. */
  @javax.annotation.PostConstruct
  public void validateSalesMetrics() {
    if (salesMetricsEnabled
        && (salesMetrics == null
            || !org.springframework.aop.support.AopUtils.isAopProxy(salesMetrics)))
      throw new IllegalStateException("SALES_TRANSACTION_PROXY_REQUIRED");
  }

  /** 按原订单号认领快照并原子预扣整笔普通库存。 */
  @Transactional(noRollbackFor = StockReservationRejectedException.class)
  public StockReservationVO reserve(StockReservationDTO request) {
    validate(request);
    List<StockLineDTO> lines =
        merge(request.items).stream().sorted(Comparator.comparing(line -> line.skuId)).toList();
    String scene = request.scene == null || request.scene.isBlank() ? "NORMAL" : request.scene;
    if (!"NORMAL".equals(scene)) throw new BizException(ErrorCodes.INVALID, "普通预扣场景无效", 400);
    String itemsSnapshot = snapshotCodec.toJson(lines);
    String itemsHash = sha256(itemsSnapshot);

    // 旧版本只有按 SKU 的流水；迁移期间仍须识别原订单，禁止再次占用库存。
    if (stockSqlMapper.findReservationRequest(request.orderNo) == null) {
      Map<Long, int[]> legacySnapshot = stockSnapshot(request.orderNo);
      if (!legacySnapshot.isEmpty()) {
        if (legacySnapshot.size() != lines.size())
          throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
        for (StockLineDTO line : lines) {
          int[] persistedLine = legacySnapshot.get(line.skuId);
          if (persistedLine == null || persistedLine[0] != line.quantity)
            throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
          if (persistedLine[1] > 0) throw new BizException(ErrorCodes.DUPLICATE, "同一订单库存已结算", 409);
        }
        return reservationReader.alreadyReserved(request.orderNo);
      }
    }

    // 1. 在独立事务中把原订单号绑定到唯一 SKU 快照。
    reservationRequestService.claim(request.orderNo, scene, itemsSnapshot, itemsHash);
    Map<String, Object> requestRow =
        stockSqlMapper.findReservationRequestForUpdate(request.orderNo);
    if (requestRow == null) throw new BizException(ErrorCodes.INTERNAL, "库存请求认领不存在", 500);
    if (!itemsHash.equals(String.valueOf(requestRow.get("items_hash"))))
      throw new BizException("ORDER_RESERVATION_KEY_REUSED", "同一订单号的库存明细不一致", 409);
    String currentStatus = String.valueOf(requestRow.get("status"));
    if ("RESERVED".equals(currentStatus)) return reservationReader.alreadyReserved(request.orderNo);
    if ("REJECTED".equals(currentStatus)) throw new StockReservationRejectedException("库存预扣已拒绝");
    if (!"INIT".equals(currentStatus))
      throw new BizException(ErrorCodes.DUPLICATE, "同一订单库存已结算", 409);

    // 2. 升序锁定所有 SKU 并完成整笔可用量校验，拒绝时不修改任何数量。
    List<Map<String, Object>> stockRows = new ArrayList<>();
    for (StockLineDTO line : lines) {
      Map<String, Object> stockRow = stockSqlMapper.findStockForUpdate(line.skuId);
      if (stockRow == null
          || ((Number) stockRow.get("available_quantity")).intValue() < line.quantity) {
        stockSqlMapper.updateReservationRequestStatus(
            request.orderNo, "INIT", "REJECTED", ErrorCodes.STOCK, now());
        throw new StockReservationRejectedException("库存不足");
      }
      stockRows.add(stockRow);
    }

    // 3. MySQL 数量、幂等流水、Redis 修复意图和请求状态同事务提交。
    OffsetDateTime occurredAt = now();
    for (int index = 0; index < lines.size(); index++) {
      StockLineDTO line = lines.get(index);
      Map<String, Object> stockRow = stockRows.get(index);
      int version = ((Number) stockRow.get("version")).intValue();
      if (stockSqlMapper.updateAvailableWithVersion(line.skuId, line.quantity, version, occurredAt)
          != 1) throw new BizException(ErrorCodes.CONFLICT, "库存版本已变化", 409);
      flow(line, request.orderNo, "RESERVE", request.orderNo + ":RESERVE:" + line.skuId);
      int available = ((Number) stockRow.get("available_quantity")).intValue() - line.quantity;
      stockSqlMapper.insertCacheRepair(line.skuId, available, version + 1, occurredAt);
    }
    if (stockSqlMapper.updateReservationRequestStatus(
            request.orderNo, "INIT", "RESERVED", null, occurredAt)
        != 1) throw new BizException(ErrorCodes.CONFLICT, "库存请求状态已变化", 409);
    return reservationReader.reservation(request.orderNo);
  }

  /** 按原操作键确认秒杀配额或普通库存，重复动作读取同一终态。 */
  @Transactional
  public StockActionResultVO confirm(String orderNo, StockActionDTO action) {
    // 1. 校验稳定动作键，秒杀结算由独立持久状态机处理。
    validateActionKey(orderNo, "CONFIRM", action);
    if (seckillSettlementService != null) {
      StockActionResultVO seckillResult = seckillSettlementService.settle(orderNo, "CONFIRM");
      if (seckillResult != null) return seckillResult;
      requireNoUnsettledSeckillFact(orderNo);
    }
    // 2. 普通库存按持久请求状态及流水确认，拒绝相反终态。
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequestForUpdate(orderNo);
    if (requestRow != null) {
      if (salesMetrics != null && !Objects.equals(orderNo, requestRow.get("order_no")))
        throw new BizException(
            "STOCK_SALES_EVENT_CONFLICT", "Original reservation identity conflicts", 409);
      String status = String.valueOf(requestRow.get("status"));
      if ("CONFIRMED".equals(status)) return StockActionResultVO.confirmed(true);
      if (!"RESERVED".equals(status))
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣状态不允许确认", 409);
    }
    List<Map<String, Object>> lines = stockSqlMapper.findOpenReservations(orderNo);
    if (lines == null) lines = List.of();
    for (Map<String, Object> line : lines) settleNormalLine(orderNo, line, "CONFIRM");
    if (requestRow != null
        && stockSqlMapper.updateReservationRequestStatus(
                orderNo, "RESERVED", "CONFIRMED", null, now())
            != 1) throw new BizException(ErrorCodes.CONFLICT, "库存确认状态已变化", 409);
    clearLegacyReservationAfterCommit(orderNo);
    return StockActionResultVO.confirmed(lines.isEmpty());
  }

  /** 兼容已有内部调用，统一使用订单号标识同一库存动作。 */
  @Transactional
  public StockActionResultVO confirm(String orderNo) {
    return confirm(orderNo, null);
  }

  /** 按原操作键释放秒杀配额或普通预扣，秒杀 Redis 投影由提交后恢复器执行。 */
  @Transactional
  public StockActionResultVO rollback(String orderNo, StockActionDTO action) {
    // 1. 校验稳定动作键，秒杀仅提交唯一释放意图。
    validateActionKey(orderNo, "ROLLBACK", action);
    if (seckillSettlementService != null) {
      StockActionResultVO seckillResult = seckillSettlementService.settle(orderNo, "ROLLBACK");
      if (seckillResult != null) return seckillResult;
      requireNoUnsettledSeckillFact(orderNo);
    }
    // 2. 普通预扣沿用 MySQL 流水和版本投影，不操作秒杀配额。
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequestForUpdate(orderNo);
    if (requestRow != null) {
      String status = String.valueOf(requestRow.get("status"));
      if ("ROLLED_BACK".equals(status) || "REJECTED".equals(status))
        return StockActionResultVO.rolledBack(true);
      if (!"RESERVED".equals(status))
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣结果尚未确定", 409);
    }
    List<Map<String, Object>> lines = stockSqlMapper.findOpenReservations(orderNo);
    if (lines == null) lines = List.of();
    for (Map<String, Object> line : lines) settleNormalLine(orderNo, line, "ROLLBACK");
    if (requestRow != null
        && stockSqlMapper.updateReservationRequestStatus(
                orderNo, "RESERVED", "ROLLED_BACK", null, now())
            != 1) throw new BizException(ErrorCodes.CONFLICT, "库存回滚状态已变化", 409);
    clearLegacyReservationAfterCommit(orderNo);
    return StockActionResultVO.rolledBack(lines.isEmpty());
  }

  /** 兼容已有内部调用，统一使用订单号标识同一库存动作。 */
  @Transactional
  public StockActionResultVO rollback(String orderNo) {
    return rollback(orderNo, null);
  }

  /** 拒绝新协议中与订单号和动作不匹配的幂等键。 */
  private static void validateActionKey(String orderNo, String type, StockActionDTO action) {
    if (action != null && !Objects.equals(action.operationKey, orderNo + ":" + type))
      throw new BizException(ErrorCodes.CONFLICT, "库存动作键与订单不匹配", 409);
  }

  /** 秒杀认领未受理或结算行损坏时不能落入普通空流水的幂等成功分支。 */
  private void requireNoUnsettledSeckillFact(String orderNo) {
    Map<String, Object> fact = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (fact != null && !fact.isEmpty())
      throw new BizException("SECKILL_SETTLEMENT_UNKNOWN", "秒杀受理结算事实尚未确认", 503);
  }

  /** 把单个未完成预扣在 MySQL 中确认或回滚，并同事务排队版本投影。 */
  private void settleNormalLine(String orderNo, Map<String, Object> line, String flowType) {
    long skuId = ((Number) line.get("sku_id")).longValue();
    int quantity = ((Number) line.get("quantity")).intValue();
    Map<String, Object> stockRow = stockSqlMapper.findStockForUpdate(skuId);
    if (stockRow == null) throw new BizException(ErrorCodes.STOCK, "预扣库存 SKU 不存在", 409);
    int version = ((Number) stockRow.get("version")).intValue();
    OffsetDateTime changedAt = now();
    if (stockSqlMapper.updateSettlementWithVersion(skuId, flowType, quantity, version, changedAt)
        != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或版本冲突", 409);
    stockSqlMapper.insertFlow(
        skuId, orderNo, flowType, quantity, orderNo + ":" + flowType + ":" + skuId, changedAt);
    int targetAvailable = ((Number) stockRow.get("available_quantity")).intValue();
    if ("ROLLBACK".equals(flowType)) targetAvailable += quantity;
    stockSqlMapper.insertCacheRepair(skuId, targetAvailable, version + 1, changedAt);
    if (salesMetrics != null && "CONFIRM".equals(flowType)) {
      Object productId = stockRow.get("product_id");
      if (!(productId instanceof Number) || ((Number) productId).longValue() <= 0)
        throw com.cloudmall.stock.service.StockSalesOutboxService.unavailable(null);
      salesMetrics.normal(
          orderNo,
          skuId,
          quantity,
          ((Number) productId).longValue(),
          (long) version + 1,
          org.slf4j.MDC.get("traceId"));
    }
  }

  /** 读取 durable stock_flow 中指定订单的预扣快照，阻止 Redis 标记丢失后的重复预扣。 */
  private Map<Long, int[]> stockSnapshot(String orderNo) {
    Map<Long, int[]> snapshot = new LinkedHashMap<>();
    List<Map<String, Object>> durableSnapshot = stockSqlMapper.findReservationSnapshot(orderNo);
    if (durableSnapshot == null) return snapshot;
    for (Map<String, Object> row : durableSnapshot) {
      Object skuId = row.get("sku_id");
      Object reserved = row.get("reserved_quantity");
      Object settled = row.get("settled_quantity");
      if (skuId instanceof Number sku
          && reserved instanceof Number reserve
          && settled instanceof Number done) {
        snapshot.put(sku.longValue(), new int[] {reserve.intValue(), done.intValue()});
      }
    }
    return snapshot;
  }

  /** 库存事实提交后清理旧协议标记；Redis 故障不改变普通库存事务结论。 */
  private void clearLegacyReservationAfterCommit(String orderNo) {
    Runnable release =
        () -> {
          try {
            redis.delete(RESERVATION_KEY_PREFIX + orderNo);
            redis.delete(RESERVATION_KEY_PREFIX + orderNo + ":lines");
          } catch (RuntimeException unavailableRedis) {
            log.warn("旧版库存预扣标记清理失败，orderNo={}", orderNo, unavailableRedis);
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      release.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          /** 只有库存本地事务提交成功后才清理旧标记。 */
          @Override
          public void afterCommit() {
            release.run();
          }
        });
  }

  /** 按原订单与SKU操作键写入唯一库存流水。 */
  private void flow(StockLineDTO line, String orderNo, String flowType, String operationKey) {
    stockSqlMapper.insertFlow(line.skuId, orderNo, flowType, line.quantity, operationKey, now());
  }

  /** 校验明细并按首次出现顺序合并SKU数量，保留整型溢出异常。 */
  private static List<StockLineDTO> merge(List<StockLineDTO> lines) {
    Map<Long, Integer> quantities = new LinkedHashMap<>();
    for (StockLineDTO line : lines) {
      if (line == null || line.skuId == null || line.quantity < 1)
        throw new BizException(ErrorCodes.INVALID, "库存数量必须为正数", 400);
      quantities.merge(line.skuId, line.quantity, Math::addExact);
    }
    return quantities.entrySet().stream()
        .map(entry -> new StockLineDTO(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
  }

  /** 拒绝缺失订单号或空明细的普通预扣请求。 */
  private static void validate(StockReservationDTO request) {
    if (request == null
        || request.orderNo == null
        || request.orderNo.isBlank()
        || request.items == null
        || request.items.isEmpty()) throw new BizException(ErrorCodes.INVALID, "库存预扣参数不完整", 400);
  }

  /** 读取Asia/Shanghai偏移下的当前业务时间。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }
}
