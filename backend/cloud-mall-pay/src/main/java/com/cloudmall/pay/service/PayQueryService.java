package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.vo.PayVO;

/** 只读取本地支付状态，不驱动扣款、回调或恢复。 */
public interface PayQueryService {
  /**
   * 读取该用户订单的支付记录，沿用Mapper返回顺序中的第一条。
   *
   * @param orderNo 原订单号
   * @param userId 已认证的用户身份
   * @return 原支付视图；无匹配记录时抛出404业务异常
   */
  PayVO getByOrderNo(String orderNo, long userId);
}
