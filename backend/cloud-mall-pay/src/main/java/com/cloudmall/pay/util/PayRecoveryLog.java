package com.cloudmall.pay.util;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.feign.OrderClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;

/** 已有恢复事实的有限日志投影；日志失败不得变更原业务结果。 */
public final class PayRecoveryLog {
  /** 只接受原持久意图的已知状态，不渲染任意字符串。 */
  private static final Set<String> INTENT_STATES =
      Set.of(
          "INIT",
          "DEBITED",
          "ORDER_SYNC_UNKNOWN",
          "REFUND_PENDING",
          "SUCCESS",
          "REFUNDED",
          "FAILED");

  /** 原订单领域允许的状态，不把PAID独立推断为支付成功。 */
  private static final Set<String> ORDER_STATES =
      Set.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED");

  /** 已知库存持久动作状态；空值和未知值分开表示。 */
  private static final Set<String> STOCK_STATES =
      Set.of("NONE", "PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT");

  /** 订单证据失败发生在原方法的哪个观察/校验阶段，不标注未证实的远端原因。 */
  public enum OrderStage {
    /** 调用原订单RPC阶段抛出异常，未取得可校验响应。 */
    RPC,
    /** 已返回响应，正在校验信封及数据是否存在。 */
    RESPONSE,
    /** 响应数据存在，正在核验订单号、用户和金额。 */
    IDENTITY,
    /** 身份金额已核验，正在检查订单状态是否属于原允许集合。 */
    STATUS
  }

  /** 日志纯静态工具，不持有全局扫描状态或创建外部资源。 */
  private PayRecoveryLog() {}

  /**
   * 记录原扫描结束和原CAS结果；空批仅DEBUG。
   *
   * @param logger 原Service日志入口
   * @param startCursor 本轮原起点
   * @param candidateEnd 原计算出的候选终点
   * @param applied 原唯一CAS的返回值
   * @param wrapped 是否进行过原回头查询
   * @param queryCount 原查询次数
   * @param candidates 原已读取列表，仅在隔离区内取size
   * @param startedNanos 同方法的nanoTime起点
   */
  public static void scanFinished(
      Logger logger,
      Long startCursor,
      Long candidateEnd,
      boolean applied,
      boolean wrapped,
      int queryCount,
      List<PayDebitIntentPO> candidates,
      long startedNanos) {
    safely(
        () -> {
          int count = candidates.size();
          String template =
              "event=SCAN_FINISHED startCursor={} candidateEndCursor={} casApplied={}"
                  + " appliedEndCursor={} wrapped={} candidateCount={} queryCount={}"
                  + " durationNanos={}";
          if (count == 0) {
            if (logger.isDebugEnabled())
              logger.debug(
                  template,
                  cursor(startCursor),
                  cursor(candidateEnd),
                  applied,
                  applied ? cursor(candidateEnd) : null,
                  wrapped,
                  count,
                  queryCount,
                  duration(startedNanos));
          } else if (logger.isInfoEnabled()) {
            logger.info(
                template,
                cursor(startCursor),
                cursor(candidateEnd),
                applied,
                applied ? cursor(candidateEnd) : null,
                wrapped,
                count,
                queryCount,
                duration(startedNanos));
          }
        });
  }

  /** 记录原查询失败，不伪造候选终点或再次读取当前游标。 */
  public static void scanQueryFailed(
      Logger logger, Long startCursor, boolean wrapped, int queryCount, long startedNanos) {
    safely(
        () ->
            logger.warn(
                "event=SCAN_QUERY_FAILED startCursor={} wrapped={} queryCount={}"
                    + " failureKind=QUERY_RUNTIME_FAILURE durationNanos={}",
                cursor(startCursor),
                wrapped,
                queryCount,
                duration(startedNanos)));
  }

  /** 记录未取得claim及原已加载对象快照，不把非1行数解释为具体租约或到期原因。 */
  public static void claimNotAcquired(
      Logger logger, String payNo, int rows, PayDebitIntentPO pending, PayDebitIntentPO current) {
    safely(
        () -> {
          if (logger.isInfoEnabled())
            logger.info(
                "event=CLAIM_NOT_ACQUIRED payFingerprint={} claimRows={} reason=UNDETERMINED"
                    + " snapshotScope=OBSERVED_SNAPSHOT pendingStatus={} pendingAttempts={}"
                    + " pendingNextRetryAt={} currentStatus={} currentAttempts={}"
                    + " currentNextRetryAt={} snapshotZone=ASIA_SHANGHAI_LOCAL",
                fingerprint(payNo),
                rows,
                pending == null ? "NOT_OBSERVED" : state(pending.status, INTENT_STATES),
                pending == null ? "NOT_OBSERVED" : count(pending.attempts),
                pending == null ? "NOT_OBSERVED" : date(pending.nextRetryAt),
                current == null ? "NOT_OBSERVED" : state(current.status, INTENT_STATES),
                current == null ? "NOT_OBSERVED" : count(current.attempts),
                current == null ? "NOT_OBSERVED" : date(current.nextRetryAt));
        });
  }

  /** 记录通过原身份、金额和状态校验的已有订单响应，不增加RPC或推断提交时刻。 */
  public static void orderObserved(
      Logger logger, PayDebitIntentPO intent, OrderClient.OrderView observed, long startedNanos) {
    safely(
        () -> {
          if (logger.isInfoEnabled())
            logger.info(
                "event=ORDER_EVIDENCE_OBSERVED payFingerprint={} orderStatus={}"
                    + " stockActionStatus={} validation=VALIDATED durationNanos={}",
                fingerprint(intent == null ? null : intent.payNo),
                observed == null ? "NOT_OBSERVED" : state(observed.status(), ORDER_STATES),
                observed == null
                    ? "NOT_OBSERVED"
                    : state(observed.stockActionStatus(), STOCK_STATES),
                duration(startedNanos));
        });
  }

  /** 记录原订单读取/校验失败阶段和已见状态，未通过的响应不作为资金证据。 */
  public static void orderFailed(
      Logger logger,
      PayDebitIntentPO intent,
      OrderClient.OrderView observed,
      OrderStage stage,
      long startedNanos) {
    safely(
        () -> {
          if (logger.isInfoEnabled())
            logger.info(
                "event=ORDER_EVIDENCE_FAILED payFingerprint={} orderStatus={} stockActionStatus={}"
                    + " validation=NOT_VALIDATED stage={} durationNanos={}",
                fingerprint(intent == null ? null : intent.payNo),
                observed == null ? "NOT_OBSERVED" : state(observed.status(), ORDER_STATES),
                observed == null
                    ? "NOT_OBSERVED"
                    : state(observed.stockActionStatus(), STOCK_STATES),
                stage == null ? "UNKNOWN" : stage.name(),
                duration(startedNanos));
        });
  }

  /** 保原逐候选失败WARN时点，仅安全指纹和有限异常类别，不携Throwable。 */
  public static void candidateFailed(
      Logger logger, PayDebitIntentPO intent, RuntimeException failure) {
    safely(
        () ->
            logger.warn(
                "event=SCAN_CANDIDATE_FAILED payFingerprint={} failureKind={}",
                fingerprint(intent == null ? null : intent.payNo),
                failureKind(failure)));
  }

  /** 保原支付等待WARN时点，持久last_error仍由原业务代码处理。 */
  public static void retryDeferred(
      Logger logger, PayDebitIntentPO intent, String retryStatus, RuntimeException failure) {
    safely(
        () ->
            logger.warn(
                "event=RETRY_DEFERRED payFingerprint={} retryStatus={} failureKind={}",
                fingerprint(intent == null ? null : intent.payNo),
                state(retryStatus, INTENT_STATES),
                failureKind(failure)));
  }

  /** 格式、指纹、级别判断与append全部在同一隔离区；保中断标志，不捕获JVM Error。 */
  private static void safely(Runnable observation) {
    boolean interrupted = Thread.currentThread().isInterrupted();
    try {
      observation.run();
    } catch (RuntimeException ignored) {
      // 观测失败不覆盖原业务返回或异常，也不递归记录另一个日志失败。
    } finally {
      if (interrupted && !Thread.currentThread().isInterrupted())
        Thread.currentThread().interrupt();
    }
  }

  /** 指纹版本和128bit SHA256前缀只用于日志关联，不是任何授权或身份强证明。 */
  private static String fingerprint(String payNo) {
    if (payNo == null) return "MISSING";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(payNo.getBytes(StandardCharsets.UTF_8));
      return "v1:" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA256_UNAVAILABLE", unavailable);
    }
  }

  private static String state(String value, Set<String> allowed) {
    return value == null ? "MISSING" : allowed.contains(value) ? value : "UNKNOWN";
  }

  private static String count(Integer value) {
    return value == null ? "MISSING" : Integer.toString(value);
  }

  private static String date(LocalDateTime value) {
    if (value == null) return "MISSING";
    if (value.getYear() < 1000 || value.getYear() > 9999) return "UNKNOWN";
    return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value);
  }

  private static String cursor(Long value) {
    return value == null ? null : Long.toString(value);
  }

  private static String duration(long startedNanos) {
    long elapsed = System.nanoTime() - startedNanos;
    return elapsed < 0 ? "UNKNOWN" : Long.toString(elapsed);
  }

  private static String failureKind(RuntimeException failure) {
    if (failure instanceof BizException) return "BIZ_EXCEPTION";
    if (failure instanceof DataAccessException) return "DATA_ACCESS";
    if (failure instanceof IllegalStateException) return "ILLEGAL_STATE";
    return "OTHER_RUNTIME";
  }
}
