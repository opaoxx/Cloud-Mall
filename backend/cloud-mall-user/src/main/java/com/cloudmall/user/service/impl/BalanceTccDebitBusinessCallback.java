package com.cloudmall.user.service.impl;

import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.BalanceTccDebitService;
import io.seata.rm.tcc.api.BusinessActionContext;

/** 官方 Fence 的公开反射委托，仅调用已验收的本地资金服务，不复制资金 SQL。 */
public class BalanceTccDebitBusinessCallback {
  /** 加入外层 Fence 事务的本地资金代理。 */
  private final BalanceTccDebitService funds;

  /** 创建业务委托，必须传入 Spring 事务代理。 */
  public BalanceTccDebitBusinessCallback(BalanceTccDebitService funds) {
    this.funds = funds;
  }

  /** 只有成功冻结才允许 TC 继续确认；已取消的迟到 Try 必须失败。 */
  public boolean tryBusiness(BusinessActionContext context) {
    if (!"TRYING".equals(funds.tryDebit(payload(context)).state))
      throw new IllegalStateException("迟到的分支不能再次进入 Try");
    return true;
  }

  /** 确认业务事实；异常必须穿透 Fence，触发同事务回滚。 */
  public boolean confirmBusiness(BusinessActionContext context) {
    return "CONFIRMED".equals(funds.confirmDebit(completion(context)).state);
  }

  /** 回滚已有冻结或保存空回滚的永久取消事实。 */
  public boolean cancelBusiness(BusinessActionContext context) {
    return "CANCELLED".equals(funds.cancelDebit(completion(context)).state);
  }

  /** 仅从 Seata 上下文绑定分支；DTO 只承载原业务载荷。 */
  public BalanceTccDebitTryDTO payload(BusinessActionContext context) {
    if (context == null
        || context.getXid() == null
        || context.getXid().isBlank()
        || context.getBranchId() <= 0
        || !BalanceTccDebitLocalResource.ACTION_NAME.equals(context.getActionName()))
      throw new IllegalArgumentException("无效的 Seata 资金分支上下文");
    BalanceTccBranchDTO source = context.getActionContext("request", BalanceTccBranchDTO.class);
    if (source == null) throw new IllegalArgumentException("分支缺少原业务载荷");
    BalanceTccDebitTryDTO request = new BalanceTccDebitTryDTO();
    request.userId = source.userId;
    request.payNo = source.payNo;
    request.orderNo = source.orderNo;
    request.amount = source.amount;
    request.xid = context.getXid();
    request.branchId = context.getBranchId();
    request.operationKey = "tcc-debit:" + source.payNo;
    request.ledgerKey = source.payNo;
    return request;
  }

  /** 保留同一不可变载荷，仅转换为内部清算 DTO。 */
  private BalanceTccDebitFinalizeDTO completion(BusinessActionContext context) {
    BalanceTccDebitTryDTO source = payload(context);
    BalanceTccDebitFinalizeDTO request = new BalanceTccDebitFinalizeDTO();
    request.userId = source.userId;
    request.payNo = source.payNo;
    request.orderNo = source.orderNo;
    request.amount = source.amount;
    request.xid = source.xid;
    request.branchId = source.branchId;
    request.operationKey = source.operationKey;
    request.ledgerKey = source.ledgerKey;
    return request;
  }
}
