package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayTccDeliveryReconciliationVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 非事务入口先校验双开关和共享门，再进入八来源只读事务。 */
@Service
public class PayTccDeliveryEvidenceService {
  /** 审计仅记录安全支付号和固定分类。 */
  private static final Logger log = LoggerFactory.getLogger(PayTccDeliveryEvidenceService.class);

  /** 原local开关、鉴权、支付号和上下文校验门。 */
  private final PayTccReconciliationService gate;

  /** 校验通过后才调用的RR只读代理。 */
  private final PayTccDeliveryEvidenceReadService reader;

  /** 默认关闭的交付/取消诊断开关。 */
  private final boolean enabled;

  /** 创建无连接和事务副作用的入口。 */
  public PayTccDeliveryEvidenceService(
      PayTccReconciliationService gate,
      PayTccDeliveryEvidenceReadService reader,
      @Value("${cloudmall.pay.tcc-delivery-evidence.enabled:false}") boolean enabled) {
    this.gate = gate;
    this.reader = reader;
    this.enabled = enabled;
  }

  /** 先校验，缺表/DB不可用固定503，不执行建表或恢复。 */
  public PayTccDeliveryReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 所有门在事务与连接前执行。
    gate.validateForRead(payNo, caller, token);
    if (!enabled) throw new BizException("PAY_TCC_DELIVERY_EVIDENCE_DISABLED", "交付取消取证入口未启用", 503);
    // 2. 只读读取，原数据库异常不进入响应或日志。
    try {
      var report = reader.inspect(payNo);
      // 3. 固定分类审计不记录台账、XID、退款键或租约凭证。
      log.info("CM18交付取消取证 payNo={} classification={}", payNo, report.classification);
      return report;
    } catch (DataAccessException unavailable) {
      log.warn("CM18交付取消取证不可用 payNo={}", payNo);
      throw new BizException("PAY_TCC_DELIVERY_EVIDENCE_UNAVAILABLE", "本地交付取消证据暂不可用", 503);
    }
  }
}
