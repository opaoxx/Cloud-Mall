package com.cloudmall.pay.service;

/** 官方 TC 的窄接口；决议响应不作为资金终态证据。 */
public interface PayTccTransactionClient {
  /** 创建全局事务并绑定当前线程，仅初次 execution 胜者可调用。 */
  String begin() throws Exception;

  /** 对已持久化的原 XID 重试提交，不创建全局事务。 */
  void commit(String xid) throws Exception;

  /** 对已持久化的原 XID 重试回滚，不创建全局事务。 */
  void rollback(String xid) throws Exception;
}
