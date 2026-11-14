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
    /** 原Legacy协议标签，公开入口使用已校验的持久模式。 */
    LEGACY,
    /** TCC协议标签，专用TCC方法scope不额外证明每笔记录模式。 */
    TCC,
    /** 未取得严格LEGACY/TCC模式，入口route不会记录此模式。 */
    UNKNOWN
  }

  /** 已通过原入口校验的路由来源。 */
  public enum Entry {
    /** 通过原校验后的模拟支付成功入口。 */
    MOCK_SUCCESS,
    /** 通过原校验后的模拟支付失败入口。 */
    MOCK_FAIL,
    /** 通过原校验后的支付回调入口。 */
    CALLBACK
  }

  /** 各自独立的原方法调用边界，不能跨scope合并为支付分母。 */
  public enum Scope {
    /** 原Legacy意图恢复方法的一次调用观察。 */
    LEGACY_RECOVERY,
    /** 原TCC首次执行方法的一次调用观察。 */
    TCC_EXECUTE,
    /** 原TCC执行恢复方法的一次调用观察。 */
    TCC_RECOVERY,
    /** Pay/User资源证据合并的一次结果观察。 */
    TCC_RESOURCE_EVIDENCE,
    /** 原TCC订单交付与退款恢复方法的一次调用观察。 */
    TCC_DELIVERY
  }

  /** 原返回事实的有限投影；资金终态与交付终态分别保留。 */
  public enum Outcome {
    /** 原execution返回BEGIN_UNKNOWN，不能据此推断TC是否建立事务。 */
    BEGIN_UNKNOWN,
    /** 原execution返回TRYING，资金终态尚未确定。 */
    TRYING,
    /** 原执行或资源证据返回COMMITTED，不等于订单已经交付。 */
    MONEY_COMMITTED,
    /** 原执行或资源证据返回ROLLEDBACK，不等于已提交扣款的退款。 */
    MONEY_CANCELLED,
    /** 原Legacy或交付结果返回SUCCESS，对应交付完成维度。 */
    DELIVERED,
    /** 原结果返回REFUND_PENDING，退款终态尚未确认。 */
    REFUND_PENDING,
    /** 原结果返回REFUNDED，与TCC资源取消分别统计。 */
    REFUNDED,
    /** 原Legacy结果返回FAILED；交付FAILED不会映射到此值。 */
    FAILED,
    /** 资源证据返回UNKNOWN，不能据此推断相反资金终态。 */
    EVIDENCE_MISSING,
    /** 原事实或既有业务异常代码表明冲突，不按HTTP码猜测。 */
    CONFLICT,
    /** 返回值或异常尚不能映射为其他已知结果。 */
    UNKNOWN
  }

  /** 原决议RPC的调用来源，不表示TC内部重试次数。 */
  public enum Origin {
    /** 原首次执行流程发起的决议SDK调用。 */
    INITIAL,
    /** 原恢复流程发起的决议SDK调用。 */
    RECOVERY
  }

  /** 原持久决议对应的实际SDK方法。 */
  public enum Decision {
    /** 按原持久COMMIT决议调用提交SDK方法。 */
    COMMIT,
    /** 按原持久ROLLBACK决议调用回滚SDK方法。 */
    ROLLBACK
  }

  /** RPC返回只说明方法返回，不说明资金终态。 */
  public enum RpcResult {
    /** SDK方法已正常返回，不能单独证明资金终态。 */
    RETURNED,
    /** SDK方法抛出异常，不能单独证明远端未执行。 */
    EXCEPTION
  }

  /** 原回调请求的布尔方向。 */
  public enum Direction {
    /** 原回调请求选择成功方向。 */
    SUCCESS,
    /** 原回调请求选择失败方向。 */
    FAILURE
  }

  /** 原claim校验结果，NEW不是外层事务已经提交的证明。 */
  public enum ClaimResult {
    /** 原插入返回1行，记录新claim观察，不单独证明外层已提交。 */
    NEW,
    /** 原插入返回0行且既有绑定校验通过的重放观察。 */
    REPLAY,
    /** 原claim校验抛出既有冲突业务异常。 */
    CONFLICT,
    /** 原claim出现其他结果或异常，不能推断新的绑定事实。 */
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
