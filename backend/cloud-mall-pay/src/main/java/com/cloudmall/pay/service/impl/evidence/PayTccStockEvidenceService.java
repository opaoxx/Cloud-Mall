package com.cloudmall.pay.service.impl.evidence;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.StockReservationEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccStockEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccStockReconciliationVO;
import com.cloudmall.pay.feign.StockEvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 两次Pay只读快照夹住一次事务外Stock GET，任何结果均不授权资金动作。 */
@Service
public class PayTccStockEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 只调用库存reservation GET的既有客户端。 */
  private final StockEvidenceClient stocks;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** Stock状态白名单，不回显未识别值。 */
  private static final Set<String> STOCK_STATES =
      Set.of("RESERVED", "CONFIRMED", "ROLLED_BACK", "UNKNOWN", "NOT_FOUND", "REJECTED", "INIT");

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccStockEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      StockEvidenceClient stocks,
      @Value("${cloudmall.pay.tcc-stock-evidence.enabled:false}") boolean enabled) {
    this.gate = gate;
    this.reader = reader;
    this.stocks = stocks;
    this.enabled = enabled;
  }

  /** 校验后依次读取Pay、Stock、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccStockReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled) throw new BizException("PAY_TCC_STOCK_EVIDENCE_DISABLED", "Stock取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccStockReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          summary(false, "NOT_QUERIED", null, false, null));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<StockReservationEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryStockWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "STOCK_QUERY_UNAVAILABLE";
    }
    // 3. 无论Stock成功或失败，都重新读取Pay；变更优先于远端基本事实。
    PayTccEvidenceCaptureDTO after;
    try {
      after = capture(payNo);
    } catch (BizException identityLost) {
      if (identityLost.getHttpStatus() != 404 && identityLost.getHttpStatus() != 409)
        throw identityLost;
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_IDENTITY_CHANGED", null);
    }
    if (!sameCapture(before, after))
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_SNAPSHOT_CHANGED", null);
    if (failure != null) return report(before, "STOCK_EVIDENCE_UNAVAILABLE", failure, null);
    if (response == null || !"0".equals(response.code) || response.data == null)
      return report(before, "STOCK_EVIDENCE_UNAVAILABLE", "STOCK_RESPONSE_UNAVAILABLE", null);
    return verify(before, response.data);
  }

  /** 数据库不可用与缺行区别对待，不输出底层错误文本。 */
  private PayTccEvidenceCaptureDTO capture(String payNo) {
    try {
      return reader.capture(payNo);
    } catch (DataAccessException unavailable) {
      throw new BizException("PAY_TCC_RECONCILIATION_UNAVAILABLE", "本地资金证据暂不可用", 503);
    }
  }

  /** 仅在此GET窗口隔离请求用户上下文，防止全局Feign拦截器追加身份；finally完整恢复。 */
  private ApiResponse<StockReservationEvidenceDTO> queryStockWithoutCallerContext(
      PayTccEvidenceAnchorDTO anchor) {
    Long originalUserId = AuthContext.userId();
    String originalRole = AuthContext.role();
    AuthContext.clear();
    try {
      return stocks.reservation(anchor.orderNo());
    } finally {
      AuthContext.clear();
      if (originalUserId != null || originalRole != null)
        AuthContext.set(originalUserId, originalRole);
    }
  }

  /** 忽略采集时刻；比较全部已冻结的本地摘要及原始锚点。 */
  private static boolean sameCapture(
      PayTccEvidenceCaptureDTO before, PayTccEvidenceCaptureDTO after) {
    return Objects.equals(before.trustedAnchor(), after.trustedAnchor())
        && Objects.equals(before.localReport().snapshot, after.localReport().snapshot)
        && Objects.equals(before.localReport().identifiers, after.localReport().identifiers)
        && Objects.equals(before.localReport().classification, after.localReport().classification)
        && Objects.equals(before.localReport().reasonCodes, after.localReport().reasonCodes);
  }

  /** 只验证当前观察向量；不拿Stock状态判断资金、退款或原订单全部SKU。 */
  private static PayTccStockReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, StockReservationEvidenceDTO observed) {
    if (observed.orderNo == null || !observed.orderNo.isTextual())
      return report(capture, "STOCK_EVIDENCE_INCOMPLETE", "STOCK_ORDER_ID_MISSING", observed);
    if (!Objects.equals(capture.trustedAnchor().orderNo(), observed.orderNo.textValue()))
      return report(capture, "STOCK_EVIDENCE_CONFLICT", "STOCK_ORDER_ID_CONFLICT", observed);
    String state = text(observed.status);
    if (state == null || !Set.of("RESERVED", "CONFIRMED", "ROLLED_BACK").contains(state))
      return report(capture, "STOCK_EVIDENCE_INCOMPLETE", "STOCK_STATE_NOT_PROVEN", observed);
    if (observed.items == null || !observed.items.isArray() || observed.items.isEmpty())
      return report(capture, "STOCK_EVIDENCE_INCOMPLETE", "STOCK_ITEMS_MISSING", observed);
    TreeMap<Long, String> vector = new TreeMap<>();
    for (JsonNode item : observed.items) {
      if (!item.isObject() || item.size() != 3)
        return report(capture, "STOCK_EVIDENCE_INCOMPLETE", "STOCK_ITEM_FIELDS_INVALID", observed);
      JsonNode skuNode = item.get("skuId");
      JsonNode quantityNode = item.get("quantity");
      String itemState = text(item.get("status"));
      if (skuNode == null
          || !skuNode.isIntegralNumber()
          || !skuNode.canConvertToLong()
          || skuNode.longValue() <= 0
          || quantityNode == null
          || !quantityNode.isIntegralNumber()
          || !quantityNode.canConvertToInt()
          || quantityNode.intValue() <= 0
          || itemState == null
          || !Set.of("RESERVED", "CONFIRMED", "ROLLED_BACK").contains(itemState))
        return report(capture, "STOCK_EVIDENCE_INCOMPLETE", "STOCK_ITEM_VALUE_INVALID", observed);
      long skuId = skuNode.longValue();
      if (!state.equals(itemState) || vector.containsKey(skuId))
        return report(capture, "STOCK_EVIDENCE_CONFLICT", "STOCK_ITEM_VECTOR_CONFLICT", observed);
      vector.put(skuId, skuId + ":" + quantityNode.intValue() + ":" + itemState + ";");
    }
    return new PayTccStockReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("USER_ORDER_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        summary(true, "MATCHED", observed, true, hash(String.join("", vector.values()))));
  }

  /** 固定阻塞摘要，不输出未验证向量指纹、原始标识或异常。 */
  private static PayTccStockReconciliationVO report(
      PayTccEvidenceCaptureDTO capture,
      String classification,
      String reason,
      StockReservationEvidenceDTO observed) {
    return new PayTccStockReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        summary(true, classification, observed, false, null));
  }

  /** 保留已观察数组长度，但不把它当作原订单SKU数量证明。 */
  private static PayTccStockEvidenceVO summary(
      boolean attempted,
      String status,
      StockReservationEvidenceDTO observed,
      boolean matched,
      String vectorHash) {
    String state = observed == null ? null : text(observed.status);
    return new PayTccStockEvidenceVO(
        attempted,
        status,
        state != null && STOCK_STATES.contains(state) ? state : null,
        matched,
        observed != null && observed.items != null && observed.items.isArray()
            ? observed.items.size()
            : null,
        hash(observed == null ? null : text(observed.orderNo)),
        vectorHash);
  }

  /** 非原生文本不强转为字符串或原身份。 */
  private static String text(JsonNode token) {
    return token != null && token.isTextual() ? token.textValue() : null;
  }

  /** 指纹仅用于比较，缺值保持null。 */
  private static String hash(String value) {
    if (value == null) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
