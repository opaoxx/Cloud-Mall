package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.vo.UserFrozenOwnershipEvidenceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 无事务入口先验证共享门和新开关，缺表/连接错误不伪装成无冻结。 */
@Service
public class UserFrozenOwnershipEvidenceService {
  /** 审计仅记录安全用户/支付号和固定分类，不打印其他支付号/原资金标识或异常。 */
  private static final Logger log =
      LoggerFactory.getLogger(UserFrozenOwnershipEvidenceService.class);

  /** 16T9 local开关与原认证/参数/上下文门。 */
  private final UserTccReconciliationService gate;

  /** 校验后才调用的RR读代理。 */
  private final UserFrozenOwnershipEvidenceReadService reader;

  /** 独立默认关闭的活跃冻结归属取证开关。 */
  private final boolean enabled;

  /** 创建无连接和事务副作用的入口门。 */
  public UserFrozenOwnershipEvidenceService(
      UserTccReconciliationService gate,
      UserFrozenOwnershipEvidenceReadService reader,
      @Value("${cloudmall.user.tcc-frozen-ownership-evidence.enabled:false}") boolean enabled) {
    this.gate = gate;
    this.reader = reader;
    this.enabled = enabled;
  }

  /** 先校验，再只读；不可用固定503，不输出连接字符串或原错误。 */
  public UserFrozenOwnershipEvidenceVO inspect(
      Long userId, String payNo, String caller, String token) {
    // 1. 两flag/认证/范围/noTx-noXID均在任何DB访问前。
    if (!enabled)
      throw new BizException("USER_FROZEN_OWNERSHIP_EVIDENCE_DISABLED", "活跃冻结归属取证入口未启用", 503);
    gate.validateForRead(userId, payNo, caller, token);
    // 2. 读取不触发释放冻结、恢复或其他资金动作。
    try {
      var report = reader.inspect(userId, payNo);
      // 3. 审计只用固定结果，不打印候选/键/错误/凭证。
      log.info(
          "CM18活跃冻结归属取证 userId={} payNo={} classification={}",
          userId,
          payNo,
          report.classification);
      return report;
    } catch (DataAccessException unavailable) {
      log.warn("CM18活跃冻结归属取证不可用 userId={} payNo={}", userId, payNo);
      throw new BizException("USER_FROZEN_OWNERSHIP_EVIDENCE_UNAVAILABLE", "活跃冻结归属证据暂不可用", 503);
    }
  }
}
