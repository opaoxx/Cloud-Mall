package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 默认关闭的非事务诊断门；所有校验先于连接，不依赖资金写RM。 */
@Service
public class UserTccReconciliationService {
  /** 审计不记录资金标识、凭证或底层异常。 */
  private static final Logger log = LoggerFactory.getLogger(UserTccReconciliationService.class);

  /** 校验后才进入的只读事务代理。 */
  private final UserTccReconciliationReadService reader;

  /** 独立默认关闭诊断开关。 */
  private final boolean enabled;

  /** 现有callback凭证，不进入报告。 */
  private final String expectedToken;

  /** 创建无DB访问的诊断入口门。 */
  public UserTccReconciliationService(
      UserTccReconciliationReadService reader,
      @Value("${cloudmall.user.tcc-reconciliation.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String expectedToken) {
    this.reader = reader;
    this.enabled = enabled;
    this.expectedToken = expectedToken;
  }

  /** 认证、参数及上下文门在只读代理前执行，DB不可用固定503。 */
  public UserTccReconciliationVO inspect(Long userId, String payNo, String caller, String token) {
    // 1. 任一失败门不会启动事务或访问任何资金证据。
    validateForRead(userId, payNo, caller, token);
    // 2. 数据库错误不伪装为没冻结或没退款，不泄露连接文本。
    try {
      var report = reader.inspect(userId, payNo);
      // 3. 固定分类审计不打印原标识、金额、账户或退款账本。
      log.info(
          "CM18 User取证 userId={} payNo={} classification={}", userId, payNo, report.classification);
      return report;
    } catch (DataAccessException unavailable) {
      log.warn("CM18 User取证不可用 userId={} payNo={}", userId, payNo);
      throw new BizException("USER_TCC_RECONCILIATION_UNAVAILABLE", "本地User资金证据暂不可用", 503);
    }
  }

  /** 共享无DB入口门，在任何连接或事务前验证原local开关、凭证与范围/上下文。 */
  public void validateForRead(Long userId, String payNo, String caller, String token) {
    if (!enabled) throw new BizException("USER_TCC_RECONCILIATION_DISABLED", "User取证入口未启用", 503);
    if (expectedToken == null
        || expectedToken.isBlank()
        || token == null
        || !"cm18-reconciliation".equals(caller)
        || !MessageDigest.isEqual(
            expectedToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
      throw new BizException("COMMON_FORBIDDEN", "内部取证凭证无效", 403);
    if (userId == null || userId <= 0 || payNo == null || !payNo.matches("[A-Za-z0-9_-]{1,64}"))
      throw new BizException("COMMON_INVALID_ARGUMENT", "资金查询范围不合法", 400);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || RootContext.getXID() != null)
      throw new BizException("USER_TCC_RECONCILIATION_CONTEXT_INVALID", "取证不允许继承资金或本地事务上下文", 409);
  }
}
