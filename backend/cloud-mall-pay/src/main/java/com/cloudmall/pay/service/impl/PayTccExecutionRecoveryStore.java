package com.cloudmall.pay.service.impl;

import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 恢复调度短事务，不包含任何 TC 或 Feign 调用。 */
@Service
public class PayTccExecutionRecoveryStore {
  /** 后台投影领取与支付终态提交保持 Pay→execution 锁序。 */
  private final PayRecordMapper payments;

  /** 到期候选、随机租约和完成 CAS 的数据库入口。 */
  private final PayTccExecutionRecoveryMapper recovery;

  /** 读取已认领的不可变 execution 与实际尝试次数。 */
  private final PayTccExecutionMapper executions;

  /** 创建恢复调度存储边界。 */
  public PayTccExecutionRecoveryStore(
      PayTccExecutionRecoveryMapper recovery,
      PayTccExecutionMapper executions,
      PayRecordMapper payments) {
    this.payments = payments;
    this.recovery = recovery;
    this.executions = executions;
  }

  /** 先 SQL 精确过滤再限制批量，不能让隔离记录占满窗口。 */
  public List<PayTccExecutionPO> findDue(LocalDateTime now, int limit) {
    if (limit < 1 || limit > 20) throw new IllegalArgumentException("恢复批量必须在 1..20");
    return recovery.findDue(now, limit);
  }

  /** 已回滚投影独立筛选，缺失分支或冲突意图不得占用有限窗口。 */
  public List<PayTccExecutionPO> findDueRollbackProjection(LocalDateTime now, int limit) {
    if (limit < 1 || limit > 20) throw new IllegalArgumentException("恢复批量必须在 1..20");
    return recovery.findDueRollbackProjection(now, limit);
  }

  /** 仅领取已有 ROLLEDBACK 的投影租约，事务提交后才读取远端证明。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO claimRollbackProjection(
      String payNo, String token, LocalDateTime leaseUntil, LocalDateTime now) {
    if (token == null || token.isBlank() || token.length() > 128 || !leaseUntil.isAfter(now))
      throw new IllegalArgumentException("无效恢复租约");
    payments.selectForUpdate(payNo);
    if (recovery.claimRollbackProjection(payNo, token, leaseUntil, now) != 1) return null;
    return executions.selectByPayNo(payNo);
  }

  /** 原 tuple、decision、到期与租约在同一原子 UPDATE 中重新校验。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO claimRecovery(
      String payNo, String token, LocalDateTime leaseUntil, LocalDateTime now) {
    if (token == null || token.isBlank() || token.length() > 128 || !leaseUntil.isAfter(now))
      throw new IllegalArgumentException("无效恢复租约");
    if (recovery.claimRecovery(payNo, token, leaseUntil, now) != 1) return null;
    return executions.selectByPayNo(payNo);
  }

  /** 只允许原 token 写调度元数据，永远不修改 execution 资金状态或决议。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean finishRecovery(String payNo, String token, LocalDateTime nextAt, String error) {
    if (error != null && error.length() > 512) error = error.substring(0, 512);
    return recovery.finishRecovery(payNo, token, nextAt, error) == 1;
  }
}
