package com.cloudmall.order.service;

/** Order演练表的独立本地事务分支。 */
public interface SeataOrderDemoBranchService {
  /**
   * 在原REQUIRED本地事务中插入CREATED事实，返回后才允许注入分支外故障。
   *
   * @param txId 演练请求标识
   * @param xid 当前Seata全局事务标识
   */
  void write(String txId, String xid);
}
