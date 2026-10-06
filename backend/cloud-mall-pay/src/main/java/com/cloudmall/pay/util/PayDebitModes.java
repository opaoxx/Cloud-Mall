package com.cloudmall.pay.util;

import com.cloudmall.common.error.BizException;

/** 扣款路由按持久事实选择，未知或尚未实现的协议禁止回落到旧扣款。 */
public final class PayDebitModes {
  /** 当前唯一已经完成业务接入的余额账本扣款协议。 */
  public static final String LEGACY = "LEGACY";

  /** 工具类禁止实例化。 */
  private PayDebitModes() {}

  /** 仅兼容旧 Java 对象的 null；空串和未知值不视为 LEGACY。 */
  public static String persisted(String mode) {
    return mode == null ? LEGACY : mode;
  }

  /** 在领取恢复租约或资金/订单副作用之前检查协议，不改变任何持久状态。 */
  public static void requireSupported(String mode) {
    if (!LEGACY.equals(persisted(mode))) throw unavailable();
  }

  /** 原支付单缺失或两份路由事实不一致时保持结果未知。 */
  public static BizException unavailable() {
    return new BizException("PAY_DEBIT_MODE_UNAVAILABLE", "支付结果暂时无法确认，请稍后重试", 503);
  }
}
