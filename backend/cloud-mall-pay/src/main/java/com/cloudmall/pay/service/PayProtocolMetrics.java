package com.cloudmall.pay.service;

import com.cloudmall.common.error.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 有限协议调用观察；Counter不是唯一交易数，也不把未知结果推断为资金成功。 */
@Component
public class PayProtocolMetrics {
  /** 直接构造旧服务使用的无资源兼容实例，不参与Spring候选注册。 */
  private static final PayProtocolMetrics NOOP = new PayProtocolMetrics();

  /** 正式容器必须提供的真实registry；仅显式Noop实例为null。 */
  private final MeterRegistry registry;

  /** 合法持久模式；专用TCC scope表示方法协议而非逐笔持久模式已校验。 */
  public enum Mode {
    LEGACY,
    TCC,
    UNKNOWN
  }

  /** 已通过原入口校验的路由来源。 */
  public enum Entry {
    MOCK_SUCCESS,
    MOCK_FAIL,
    CALLBACK
  }

  /** 各自独立的原方法调用边界，不能跨scope合并为支付分母。 */
  public enum Scope {
    LEGACY_RECOVERY,
    TCC_EXECUTE,
    TCC_RECOVERY,
    TCC_RESOURCE_EVIDENCE,
    TCC_DELIVERY
  }

  /** 原返回事实的有限投影；资金终态与交付终态分别保留。 */
  public enum Outcome {
    BEGIN_UNKNOWN,
    TRYING,
    MONEY_COMMITTED,
    MONEY_CANCELLED,
    DELIVERED,
    REFUND_PENDING,
    REFUNDED,
    FAILED,
    EVIDENCE_MISSING,
    CONFLICT,
    UNKNOWN
  }

  /** 原决议RPC的调用来源，不表示TC内部重试次数。 */
  public enum Origin {
    INITIAL,
    RECOVERY
  }

  /** 原持久决议对应的实际SDK方法。 */
  public enum Decision {
    COMMIT,
    ROLLBACK
  }

  /** RPC返回只说明方法返回，不说明资金终态。 */
  public enum RpcResult {
    RETURNED,
    EXCEPTION
  }

  /** 原回调请求的布尔方向。 */
  public enum Direction {
    SUCCESS,
    FAILURE
  }

  /** 原claim校验结果，NEW不是外层事务已经提交的证明。 */
  public enum ClaimResult {
    NEW,
    REPLAY,
    CONFLICT,
    UNKNOWN
  }

  private PayProtocolMetrics() {
    this.registry = null;
  }

  /** 正式组件缺少registry时必须由Spring启动失败，不隐式降级Noop。 */
  @Autowired
  public PayProtocolMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "协议指标缺少MeterRegistry");
  }

  /** 返回不注册meter、不持有外部资源的兼容实例。 */
  public static PayProtocolMetrics noop() {
    return NOOP;
  }

  /** 仅记录原持久模式校验成功后的入口路由。 */
  public void route(Mode mode, Entry entry) {
    if (mode == Mode.UNKNOWN) return;
    increment("cloudmall.pay.protocol.route", "mode", mode.name(), "entry", entry.name());
  }

  /** 记录一次原方法结果，合法枚举组合的缓存由registry管理。 */
  public void observation(Mode mode, Scope scope, Outcome outcome) {
    increment(
        "cloudmall.pay.protocol.observation",
        "mode",
        mode.name(),
        "scope",
        scope.name(),
        "outcome",
        outcome.name());
  }

  /** 仅在原SDK调用实际返回或抛出后记录，不提前声明RETURNED。 */
  public void rpc(Origin origin, Decision decision, RpcResult result) {
    increment(
        "cloudmall.pay.tcc.decision.rpc",
        "origin",
        origin.name(),
        "decision",
        decision.name(),
        "result",
        result.name());
  }

  /** 记录原claim结果，缺失请求没有合法direction，调用方不生成样本。 */
  public void claim(Mode mode, Direction direction, ClaimResult result) {
    increment(
        "cloudmall.pay.callback.claim",
        "mode",
        mode.name(),
        "direction",
        direction.name(),
        "result",
        result.name());
  }

  /** 原字段严格相等才是已取得模式，既不查询也不修复未知值。 */
  public static Mode mode(String persisted) {
    return "LEGACY".equals(persisted)
        ? Mode.LEGACY
        : "TCC".equals(persisted) ? Mode.TCC : Mode.UNKNOWN;
  }

  /** 原Legacy返回值的交付维度投影。 */
  public static Outcome legacyOutcome(String state) {
    if (state == null) return Outcome.UNKNOWN;
    return switch (state) {
      case "SUCCESS" -> Outcome.DELIVERED;
      case "FAILED" -> Outcome.FAILED;
      case "REFUNDED" -> Outcome.REFUNDED;
      case "REFUND_PENDING" -> Outcome.REFUND_PENDING;
      default -> Outcome.UNKNOWN;
    };
  }

  /** 原execution最终VO投影，不使用SDK返回推断资金事实。 */
  public static Outcome executionOutcome(String state) {
    if (state == null) return Outcome.UNKNOWN;
    return switch (state) {
      case "BEGIN_UNKNOWN" -> Outcome.BEGIN_UNKNOWN;
      case "TRYING" -> Outcome.TRYING;
      case "COMMITTED" -> Outcome.MONEY_COMMITTED;
      case "ROLLEDBACK" -> Outcome.MONEY_CANCELLED;
      case "CONFLICT" -> Outcome.CONFLICT;
      default -> Outcome.UNKNOWN;
    };
  }

  /** 原证据UNKNOWN表示两端结论尚不完整，保留为EVIDENCE_MISSING。 */
  public static Outcome evidenceOutcome(String state) {
    return "UNKNOWN".equals(state) ? Outcome.EVIDENCE_MISSING : executionOutcome(state);
  }

  /** 原交付最终VO投影；退款不属于TCC资金取消。 */
  public static Outcome deliveryOutcome(String state) {
    return "FAILED".equals(state) ? Outcome.UNKNOWN : legacyOutcome(state);
  }

  /** 只复用原Coordinator冲突判据，绝不按HTTP状态或异常文本猜测。 */
  public static Outcome failureOutcome(RuntimeException failure) {
    return failure instanceof BizException business
            && business.getCode() != null
            && business.getCode().contains("CONFLICT")
        ? Outcome.CONFLICT
        : Outcome.UNKNOWN;
  }

  /** 只有metrics注册/增量RuntimeException在此隔离；JVM Error仍原样传播。 */
  private void increment(String name, String... tags) {
    if (registry == null) return;
    try {
      registry.counter(name, tags).increment();
    } catch (RuntimeException observationUnavailable) {
      // 观察失败不得覆盖原业务返回、异常、上下文清理或lease释放。
    }
  }
}
