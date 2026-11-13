package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.vo.PayVO;
import java.util.function.LongSupplier;

/** 模拟成功请求的原协议校验及支付编排入口。 */
public interface PaySuccessService {
  /**
   * 按持久协议处理成功请求，未决结果沿原业务异常返回。
   *
   * @param payNo 原支付单号
   * @param userIdSupplier 获得共享监视器后读取身份
   * @return 原支付视图；只有原成功路径返回
   */
  PayVO succeed(String payNo, LongSupplier userIdSupplier);
}
