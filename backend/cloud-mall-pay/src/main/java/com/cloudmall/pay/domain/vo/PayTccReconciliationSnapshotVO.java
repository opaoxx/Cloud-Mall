package com.cloudmall.pay.domain.vo;

import java.util.Map;

/**
 * 同一本地数据库快照的非敏感摘要。
 *
 * @param payNo 已精确核验的支付号
 * @param mode 白名单持久支付协议
 * @param paymentState 白名单支付状态
 * @param orderNo 原订单号
 * @param userId 原用户主键
 * @param amount 原金额字符串
 * @param execution 执行来源
 * @param dispatch 派发来源
 * @param receipt Pay分支来源
 * @param fence Pay分支Fence来源
 * @param legacyIntent Legacy意图来源
 * @param errorPresence 仅表示错误是否存在，不输出错误内容
 */
public record PayTccReconciliationSnapshotVO(
    String payNo,
    String mode,
    String paymentState,
    String orderNo,
    Long userId,
    String amount,
    PayTccLocalFactVO execution,
    PayTccLocalFactVO dispatch,
    PayTccLocalFactVO receipt,
    PayTccLocalFactVO fence,
    PayTccLocalFactVO legacyIntent,
    Map<String, Boolean> errorPresence) {
  /** 复制错误存在性映射，防止调用方修改报告。 */
  public PayTccReconciliationSnapshotVO {
    errorPresence = Map.copyOf(errorPresence);
  }
}
