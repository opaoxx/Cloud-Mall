package com.cloudmall.stock.service.impl;

import static com.cloudmall.stock.service.impl.StockReservationSnapshotCodec.sha256;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.vo.StockReservationItemVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.util.SeckillAcceptanceDimensions;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 查询与幂等重放共用的持久库存证明，不自行开启事务或依赖保护。 */
@Component
public class StockReservationReader {
  /** 原始库存请求、流水与秒杀认领查询。 */
  private final StockSqlMapper stockSqlMapper;

  /** 仅在副本上启用严格快照解析。 */
  private final ObjectMapper mapper;

  /** 优先读取现有秒杀结算证明；历史裸台账测试可显式不提供。 */
  private final SeckillQuotaSettlementService seckillSettlementService;

  /** 与普通预扣writer共用的规范JSON与hash规则。 */
  private final StockReservationSnapshotCodec snapshotCodec;

  /** 创建无额外事务边界的持久事实读取器。 */
  public StockReservationReader(
      StockSqlMapper stockSqlMapper,
      ObjectMapper mapper,
      SeckillQuotaSettlementService seckillSettlementService,
      StockReservationSnapshotCodec snapshotCodec) {
    this.stockSqlMapper = stockSqlMapper;
    this.mapper = mapper;
    this.seckillSettlementService = seckillSettlementService;
    this.snapshotCodec = snapshotCodec;
  }

  /** 只读查询原订单预扣事实；缺证保守返回UNKNOWN，不新增事务。 */
  public ApiResponse<StockReservationVO> reservation(String orderNo) {
    // 1. 优先核对秒杀结算与旧受理台账，任何非空候选都不能落普通库存分支。
    if (seckillSettlementService != null) {
      StockReservationVO seckillResult = seckillSettlementService.reservation(orderNo);
      if (seckillResult != null) return ApiResponse.ok(seckillResult);
    }
    Map<String, Object> seckillFact = stockSqlMapper.findSeckillByOrderNo(orderNo);
    if (seckillFact != null && !seckillFact.isEmpty()) {
      // 原CI候选必须属于请求原订单；错身份不能改为NOT_FOUND或落普通路径。
      if (!Objects.equals(orderNo, seckillFact.get("order_no")))
        return ApiResponse.ok(unknownReservationProof(orderNo));
      // 原SKU必须无损正long；坏legacy字段只能缺证，不能保留REJECTED或伪造明细。
      long legacySkuId;
      try {
        legacySkuId = exactReservationNumber(seckillFact.get("sku_id")).longValueExact();
        if (legacySkuId <= 0) return ApiResponse.ok(unknownReservationProof(orderNo));
      } catch (IllegalArgumentException | ArithmeticException invalidIdentity) {
        return ApiResponse.ok(unknownReservationProof(orderNo));
      }
      // 与主legacy读共用原维度门；缺证不回显SKU或保留REJECTED等已知状态。
      if (!SeckillAcceptanceDimensions.hasValidContext(seckillFact))
        return ApiResponse.ok(unknownReservationProof(orderNo));
      String acceptedStatus = String.valueOf(seckillFact.get("status"));
      // 历史未受支持状态不能公开原SKU；保留非null候选以阻止普通库存fallback。
      if (!"ACCEPTED".equals(acceptedStatus) && !"REJECTED".equals(acceptedStatus))
        return ApiResponse.ok(unknownReservationProof(orderNo));
      String status =
          "ACCEPTED".equals(acceptedStatus)
              ? "RESERVED"
              : ("REJECTED".equals(acceptedStatus) ? "REJECTED" : "UNKNOWN");
      // 只读兼容存量台账；GET 不补表、不造回执，也不猜测最终结算。
      return ApiResponse.ok(
          new StockReservationVO(
              orderNo, status, List.of(new StockReservationItemVO(legacySkuId, 1, status))));
    }
    // 2. 读取普通请求和完整流水，保留原非RR观察与缺证语义。
    Map<String, Object> requestRow = stockSqlMapper.findReservationRequest(orderNo);
    List<Map<String, Object>> rows = stockSqlMapper.findReservationFlows(orderNo);
    if ((rows == null || rows.isEmpty()) && requestRow == null) {
      return ApiResponse.ok(new StockReservationVO(orderNo, "NOT_FOUND", List.of()));
    }
    if (rows == null || rows.isEmpty()) {
      String requestStatus = String.valueOf(requestRow.get("status"));
      if ("RESERVED".equals(requestStatus)
          || "CONFIRMED".equals(requestStatus)
          || "ROLLED_BACK".equals(requestStatus)) {
        throw new IllegalStateException("库存请求状态与持久流水不一致: " + orderNo);
      }
      return ApiResponse.ok(
          new StockReservationVO(
              orderNo,
              requestStatus,
              itemsFromSnapshot(String.valueOf(requestRow.get("items_snapshot")), requestStatus)));
    }
    return ApiResponse.ok(normalReservationProof(orderNo, requestRow, rows));
  }

  /** 核验普通流水和存在的原请求快照；全部SKU同向且原载荷一致才有已知状态。 */
  private StockReservationVO normalReservationProof(
      String orderNo, Map<String, Object> requestRow, List<Map<String, Object>> rows) {
    // 1. 精确读取原SKU/数量，拒绝缺值、截断、负数、超容量与超原RESERVE量。
    List<StockReservationItemVO> items = new ArrayList<>();
    String aggregateStatus = null;
    for (Map<String, Object> row : rows) {
      if (row == null) return unknownReservationProof(orderNo);
      try {
        // 原SUM不能证明来源：字节身份须唯一且精确，非法原行不能被抵消或忽略。
        if (!Objects.equals(orderNo, row.get("original_order_no"))
            || exactReservationNumber(row.get("order_identity_count")).longValueExact() != 1
            || exactReservationNumber(row.get("invalid_flow_count")).longValueExact() != 0)
          return unknownReservationProof(orderNo);
        long skuId = exactReservationNumber(row.get("sku_id")).longValueExact();
        int reserved = exactReservationNumber(row.get("reserved_quantity")).intValueExact();
        int confirmed = exactReservationNumber(row.get("confirmed_quantity")).intValueExact();
        int rolledBack = exactReservationNumber(row.get("rolled_back_quantity")).intValueExact();
        if (skuId <= 0
            || reserved <= 0
            || confirmed < 0
            || rolledBack < 0
            || (long) confirmed + rolledBack > reserved) return unknownReservationProof(orderNo);
        // 2. 单SKU只有全量同向或未结算才有已知状态，跨SKU混合与部分结清均阻塞。
        String itemStatus =
            confirmed == reserved && rolledBack == 0
                ? "CONFIRMED"
                : rolledBack == reserved && confirmed == 0
                    ? "ROLLED_BACK"
                    : confirmed == 0 && rolledBack == 0 ? "RESERVED" : "UNKNOWN";
        if ("UNKNOWN".equals(itemStatus)
            || aggregateStatus != null && !aggregateStatus.equals(itemStatus))
          return unknownReservationProof(orderNo);
        aggregateStatus = itemStatus;
        items.add(new StockReservationItemVO(skuId, reserved, itemStatus));
      } catch (IllegalArgumentException | ArithmeticException invalidNumber) {
        return unknownReservationProof(orderNo);
      }
    }
    // 3. 验证原请求状态和完整SKU快照；读中竞争或历史矛盾保留原事实。
    if (aggregateStatus == null
        || requestRow != null
            && (!Objects.equals(aggregateStatus, requestRow.get("status"))
                || !requestSnapshotMatches(orderNo, requestRow, items)))
      return unknownReservationProof(orderNo);
    return new StockReservationVO(orderNo, aggregateStatus, items);
  }

  /** 验证原规范快照和完整SKU/数量集合；MySQL JSON规范化后仍沿原writer重建hash。 */
  private boolean requestSnapshotMatches(
      String orderNo, Map<String, Object> requestRow, List<StockReservationItemVO> observedItems) {
    // 1. 不借排序规则别名或其他场景的请求为当前普通库存提供证据。
    if (orderNo == null
        || orderNo.isBlank()
        || !Objects.equals(orderNo, requestRow.get("order_no"))
        || !"NORMAL".equals(requestRow.get("scene"))
        || !(requestRow.get("items_snapshot") instanceof String snapshot)
        || snapshot.isBlank()
        || !(requestRow.get("items_hash") instanceof String persistedHash)) return false;
    try {
      // 2. 仅在独立副本启用严格解析；不得改变整个服务的JSON请求/序列化策略。
      JsonNode root =
          mapper
              .copy()
              .enable(
                  DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                  DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(snapshot);
      if (root == null || !root.isArray() || root.isEmpty()) return false;
      List<StockLineDTO> lines = new ArrayList<>();
      Map<Long, Integer> expectedQuantities = new HashMap<>();
      long previousSkuId = 0;
      for (JsonNode entry : root) {
        if (!entry.isObject() || entry.size() != 2) return false;
        JsonNode skuNode = entry.get("skuId");
        JsonNode quantityNode = entry.get("quantity");
        if (skuNode == null
            || !skuNode.isIntegralNumber()
            || !skuNode.canConvertToLong()
            || quantityNode == null
            || !quantityNode.isIntegralNumber()
            || !quantityNode.canConvertToInt()) return false;
        long skuId = skuNode.longValue();
        int quantity = quantityNode.intValue();
        if (skuId <= previousSkuId || quantity <= 0) return false;
        previousSkuId = skuId;
        lines.add(new StockLineDTO(skuId, quantity));
        expectedQuantities.put(skuId, quantity);
      }
      // 3. 重用原DTO序列化规则，不能对读取时带空白/重排key的JSON直接hash。
      if (!sha256(snapshotCodec.toJson(lines)).equals(persistedHash)) return false;
      // 4. 消费完整原集合，缺/多/重复SKU或数量不一致都不能成为终态证据。
      if (expectedQuantities.size() != observedItems.size()) return false;
      for (StockReservationItemVO item : observedItems) {
        if (!Objects.equals(expectedQuantities.remove(item.skuId), item.quantity)) return false;
      }
      return expectedQuantities.isEmpty();
    } catch (JsonProcessingException | BizException malformedSnapshot) {
      return false;
    }
  }

  /** JDBC整数/DECIMAL以精确值处理，不接收浮点、文本或null来补造库存事实。 */
  private static BigDecimal exactReservationNumber(Object value) {
    if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
      throw new IllegalArgumentException("普通库存流水数值不可信");
    return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
  }

  /** 缺证不得回显看似终态的部分明细；不删除或修改任何原流水。 */
  private static StockReservationVO unknownReservationProof(String orderNo) {
    return new StockReservationVO(orderNo, "UNKNOWN", List.of());
  }

  /** 在尚未写入库存流水时从不可变请求快照展示预扣明细。 */
  private List<StockReservationItemVO> itemsFromSnapshot(String snapshot, String status) {
    try {
      List<StockLineDTO> lines =
          mapper.readValue(
              snapshot, new com.fasterxml.jackson.core.type.TypeReference<List<StockLineDTO>>() {});
      return lines.stream()
          .map(line -> new StockReservationItemVO(line.skuId, line.quantity, status))
          .toList();
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformedSnapshot) {
      throw new IllegalStateException("库存预扣快照无法解析", malformedSnapshot);
    }
  }

  /** 返回同一订单号、同一 SKU 快照的幂等预扣结果。 */
  public ApiResponse<StockReservationVO> alreadyReserved(String orderNo) {
    ApiResponse<StockReservationVO> response;
    try {
      response = reservation(orderNo);
    } catch (RuntimeException unavailableProof) {
      throw reservationProofUnavailable();
    }
    if (response == null || response.data == null || !"RESERVED".equals(response.data.status))
      throw reservationProofUnavailable();
    response.data.status = "ALREADY_RESERVED";
    return response;
  }

  /** 重放证据缺失保持原事实，不返回底层错误或强制ACK成功。 */
  private static BizException reservationProofUnavailable() {
    return new BizException("STOCK_RESERVATION_PROOF_UNAVAILABLE", "库存预扣持久事实待核验", 503);
  }
}
