package com.cloudmall.order.service;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;

/** 只协调Order/Stock独立演练表的Seata全局事务，不接入普通订单补偿链。 */
public interface SeataTransactionDemoOrchestrator {
  /**
   * 经独立Seata代理执行本地与远程分支；调用方只能在代理成功返回后确认提交。
   *
   * @param request 演练请求及受控故障点
   * @return 代理完成后可用于提交确认的演练结果
   * @throws SeataDemoControlledRollbackException 分支写入后触发受控回滚
   * @throws SeataDemoUnknownTransactionException 已有分支或远程响应不足以证明提交
   */
  SeataTransactionDemoVO execute(SeataTransactionDemoDTO request);
}
