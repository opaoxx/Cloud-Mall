package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.OrderBasicEvidenceDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.vo.PayTccOrderEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccOrderReconciliationVO;
import com.cloudmall.pay.feign.OrderBasicEvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 两次Pay只读快照夹住一次事务外Order GET，任何结果均不授权资金动作。 */
@Service
public class PayTccOrderEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 仅get GET且保原生token的取证客户端，不使用业务OrderClient。 */
  private final OrderBasicEvidenceClient orders;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** Order状态白名单，不回显未识别值。 */
  private static final Set<String> ORDER_STATES =
      Set.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED");

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccOrderEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      OrderBasicEvidenceClient orders,
      @Value("${cloudmall.pay.tcc-order-evidence.enabled:false}") boolean enabled) {
    this.gate = gate;
    this.reader = reader;
    this.orders = orders;
    this.enabled = enabled;
  }

  /** 校验后依次读取Pay、Order、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccOrderReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled) throw new BizException("PAY_TCC_ORDER_EVIDENCE_DISABLED", "Order取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccOrderReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          summary(false, "NOT_QUERIED", null, false));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<OrderBasicEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryOrderWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "ORDER_QUERY_UNAVAILABLE";
    }
    // 3. 无论Order成功或失败，都重新读取Pay；变更优先于远端基本事实。
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
    if (failure != null) return report(before, "ORDER_EVIDENCE_UNAVAILABLE", failure, null);
    if (response == null || !"0".equals(response.code) || response.data == null)
      return report(before, "ORDER_EVIDENCE_UNAVAILABLE", "ORDER_RESPONSE_UNAVAILABLE", null);
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
  private ApiResponse<OrderBasicEvidenceDTO> queryOrderWithoutCallerContext(
      PayTccEvidenceAnchorDTO anchor) {
    Long originalUserId = AuthContext.userId();
    String originalRole = AuthContext.role();
    AuthContext.clear();
    try {
      return orders.get(anchor.orderNo(), anchor.userId());
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

  /** 只核对订单主行基本事实，不把订单状态解释成资金或库存终态。 */
  private static PayTccOrderReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, OrderBasicEvidenceDTO observed) {
    if (text(observed.orderNo) == null
        || userId(observed.userId) == null
        || money(observed.payAmount) == null
        || text(observed.status) == null
        || !ORDER_STATES.contains(text(observed.status)))
      return report(capture, "ORDER_EVIDENCE_INCOMPLETE", "ORDER_BASIC_FIELDS_MISSING", observed);
    PayTccEvidenceAnchorDTO anchor = capture.trustedAnchor();
    if (!Objects.equals(anchor.orderNo(), text(observed.orderNo))
        || !Objects.equals(anchor.userId(), userId(observed.userId))
        || new BigDecimal(anchor.amountString()).compareTo(money(observed.payAmount)) != 0)
      return report(capture, "ORDER_EVIDENCE_CONFLICT", "ORDER_TUPLE_CONFLICT", observed);
    return new PayTccOrderReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("USER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        summary(true, "MATCHED", observed, true));
  }

  /** 固定阻塞摘要，不携带底层错误或原资金锚点。 */
  private static PayTccOrderReconciliationVO report(
      PayTccEvidenceCaptureDTO capture,
      String classification,
      String reason,
      OrderBasicEvidenceDTO observed) {
    return new PayTccOrderReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        summary(true, classification, observed, false));
  }

  /** 只输出白名单状态和基本事实指纹；错单响应身份也不原样回显。 */
  private static PayTccOrderEvidenceVO summary(
      boolean attempted, String status, OrderBasicEvidenceDTO observed, boolean matched) {
    return new PayTccOrderEvidenceVO(
        attempted,
        status,
        observed != null && ORDER_STATES.contains(Objects.toString(text(observed.status), ""))
            ? text(observed.status)
            : null,
        matched,
        hash(observed == null ? null : text(observed.orderNo)),
        hash(
            observed == null || userId(observed.userId) == null
                ? null
                : userId(observed.userId).toString()),
        hash(
            observed == null || money(observed.payAmount) == null
                ? null
                : money(observed.payAmount).toString()));
  }

  /** 仅原生正整数且可无损表示long；不接受text/float/boolean。 */
  private static Long userId(JsonNode value) {
    return value != null
            && value.isIntegralNumber()
            && value.canConvertToLong()
            && value.longValue() > 0
        ? value.longValue()
        : null;
  }

  /** 原生有限十进制文本；尾零无损兼容，不接受number/指数/空白或截断精度。 */
  private static BigDecimal money(JsonNode value) {
    String raw = text(value);
    if (raw == null || !raw.matches("-?(0|[1-9][0-9]*)([.][0-9]+)?")) return null;
    BigDecimal amount = new BigDecimal(raw);
    return amount.stripTrailingZeros().scale() <= 2
            && amount.abs().compareTo(new BigDecimal("9999999999999999.99")) <= 0
        ? amount
        : null;
  }

  /** 原字段长度有界且必须原生text，不产生假身份/状态/金额指纹。 */
  private static String text(JsonNode value) {
    return value != null && value.isTextual() && value.textValue().length() <= 128
        ? value.textValue()
        : null;
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
