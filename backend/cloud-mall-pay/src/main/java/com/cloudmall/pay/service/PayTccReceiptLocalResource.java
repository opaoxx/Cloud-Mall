package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

/** Pay receipt 唯一 LocalTCC 资源。 */
@LocalTCC
public interface PayTccReceiptLocalResource {
  /** TC 识别的稳定资源名。 */
  String ACTION_NAME = "cloudmallPayDebitReceipt";

  /** 由 TC 注册 Pay 分支并推进 receipt Try。 */
  @TwoPhaseBusinessAction(
      name = ACTION_NAME,
      commitMethod = "commit",
      rollbackMethod = "rollback",
      useTCCFence = false)
  boolean prepare(
      BusinessActionContext context,
      @BusinessActionContextParameter(paramName = "request") PayTccDispatchDTO request);

  /** TC Confirm 回调。 */
  boolean commit(BusinessActionContext context);

  /** TC Cancel 回调，包括空回滚。 */
  boolean rollback(BusinessActionContext context);
}
