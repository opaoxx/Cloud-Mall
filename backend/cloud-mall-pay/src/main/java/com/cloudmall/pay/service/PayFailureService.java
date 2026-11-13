package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.vo.PayVO;

/** 模拟支付失败的持久协议分流，不额外持锁或开启事务。 */
public interface PayFailureService {
  /**
   * 将失败请求交给原协议的授权流程，沿原结果和异常返回。
   *
   * @param payNo 原支付单号
   * @param userId 已认证的用户身份
   * @return 原协议处理后的支付视图，包括原null语义
   */
  PayVO fail(String payNo, long userId);
}
