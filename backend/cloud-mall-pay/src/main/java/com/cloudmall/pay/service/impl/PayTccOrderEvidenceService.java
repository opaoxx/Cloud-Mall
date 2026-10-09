package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.vo.PayTccOrderEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccOrderReconciliationVO;
import com.cloudmall.pay.feign.OrderClient;
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

  /** 只调用订单get GET的既有客户端。 */
  private final OrderClient orders;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** Order状态白名单，不回显未识别值。 */
  private static final Set<String> ORDER_STATES =
      Set.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED");

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccOrderEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      OrderClient orders,
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
    ApiResponse<OrderClient.OrderView> response = null;
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
  private ApiResponse<OrderClient.OrderView> queryOrderWithoutCallerContext(
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
      PayTccEvidenceCaptureDTO capture, OrderClient.OrderView observed) {
    if (observed.orderNo() == null
        || observed.userId() == null
        || observed.payAmount() == null
        || observed.status() == null
        || !ORDER_STATES.contains(observed.status()))
      return report(capture, "ORDER_EVIDENCE_INCOMPLETE", "ORDER_BASIC_FIELDS_MISSING", observed);
    PayTccEvidenceAnchorDTO anchor = capture.trustedAnchor();
    if (!Objects.equals(anchor.orderNo(), observed.orderNo())
        || !Objects.equals(anchor.userId(), observed.userId())
        || new BigDecimal(anchor.amountString()).compareTo(observed.payAmount()) != 0)
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
      OrderClient.OrderView observed) {
    return new PayTccOrderReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        summary(true, classification, observed, false));
  }

  /** 只输出白名单状态和基本事实指纹；错单响应身份也不原样回显。 */
  private static PayTccOrderEvidenceVO summary(
      boolean attempted, String status, OrderClient.OrderView observed, boolean matched) {
    return new PayTccOrderEvidenceVO(
        attempted,
        status,
        observed != null && ORDER_STATES.contains(Objects.toString(observed.status(), ""))
            ? observed.status()
            : null,
        matched,
        hash(observed == null ? null : observed.orderNo()),
        hash(observed == null || observed.userId() == null ? null : observed.userId().toString()),
        hash(
            observed == null || observed.payAmount() == null
                ? null
                : observed.payAmount().toString()));
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
