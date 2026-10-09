package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import io.seata.core.context.RootContext;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 不持有事务的入口门，校验通过后才进入只读事务与连接。 */
@Service
public class PayTccReconciliationService {
  /** 审计只记录安全支付号与固定分类，不记录原错误或凭证。 */
  private static final Logger log = LoggerFactory.getLogger(PayTccReconciliationService.class);

  /** 默认关闭的诊断入口开关。 */
  private final boolean enabled;

  /** 已有内部callback凭证，不出现在报告或日志中。 */
  private final String expectedToken;

  /** 校验后才调用的RR只读服务。 */
  private final PayTccReconciliationReadService reader;

  /** 创建无数据库副作用的入口门。 */
  public PayTccReconciliationService(
      PayTccReconciliationReadService reader,
      @Value("${cloudmall.pay.tcc-reconciliation.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String expectedToken) {
    this.reader = reader;
    this.enabled = enabled;
    this.expectedToken = expectedToken;
  }

  /** 先验证开关、凭证、调用者和有界支付号；不让失败请求启动事务。 */
  public PayTccReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 所有入口校验先于ReadService代理，disabled或未授权请求零DB访问。
    validateForRead(payNo, caller, token);
    // 2. 只读SQL不可用不伪装成缺行，不向调用方泄露连接或异常文本。
    try {
      PayTccReconciliationVO report = reader.inspect(payNo);
      // 3. 审计不打印报告、XID、branch、操作键或底层异常。
      log.info("CM18本地取证 payNo={} classification={}", payNo, report.classification);
      return report;
    } catch (DataAccessException unavailable) {
      log.warn("CM18本地取证不可用 payNo={}", payNo);
      throw new BizException("PAY_TCC_RECONCILIATION_UNAVAILABLE", "本地资金证据暂不可用", 503);
    }
  }

  /** 共享入口门，必须在任何事务、连接或远端请求之前调用。 */
  public void validateForRead(String payNo, String caller, String token) {
    if (!enabled) throw new BizException("PAY_TCC_RECONCILIATION_DISABLED", "本地取证入口未启用", 503);
    if (expectedToken == null
        || expectedToken.isBlank()
        || !Objects.equals(expectedToken, token)
        || !"cm18-reconciliation".equals(caller))
      throw new BizException(ErrorCodes.FORBIDDEN, "内部取证凭证无效", 403);
    if (payNo == null || !payNo.matches("[A-Za-z0-9_-]{1,64}"))
      throw new BizException(ErrorCodes.INVALID, "支付号格式无效", 400);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || RootContext.getXID() != null)
      throw new BizException("PAY_TCC_RECONCILIATION_CONTEXT_INVALID", "取证不允许继承资金或本地事务上下文", 409);
  }
}
