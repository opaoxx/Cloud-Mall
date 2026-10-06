package com.cloudmall.user.service;

import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

/** User 唯一 LocalTCC 资源；关闭自动 Fence，由适配器原子组合官方 Fence 与业务事实。 */
@LocalTCC
public interface BalanceTccDebitLocalResource {
  /** TC 识别的稳定资源名称，不随实例或重启变化。 */
  String ACTION_NAME = "cloudmallUserBalanceDebit";

  /** 由 Seata 代理注册分支后冻结资金；调用者不能提供分支标识。 */
  @TwoPhaseBusinessAction(
      name = ACTION_NAME,
      commitMethod = "commit",
      rollbackMethod = "rollback",
      useTCCFence = false)
  boolean prepare(
      BusinessActionContext context,
      @BusinessActionContextParameter(paramName = "request") BalanceTccBranchDTO request);

  /** TC 确认回调；false 表示资金结果尚未安全确认，需要重试。 */
  boolean commit(BusinessActionContext context);

  /** TC 回滚回调；空回滚也必须保留永久的取消归属。 */
  boolean rollback(BusinessActionContext context);
}
