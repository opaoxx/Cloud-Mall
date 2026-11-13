package com.cloudmall.pay.service.impl;

import org.springframework.stereotype.Component;

/** 创建、模拟成功和回调共享的单JVM监视器；不覆盖事务提交，也不提供跨实例互斥。 */
@Component
public final class PayWriteMonitor {
  /** 创建生产装配中唯一共享的监视器身份。 */
  public PayWriteMonitor() {}
}
