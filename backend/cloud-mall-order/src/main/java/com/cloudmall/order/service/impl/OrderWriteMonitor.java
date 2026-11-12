package com.cloudmall.order.service.impl;

import org.springframework.stereotype.Component;

/** 创建、取消、支付共用的本地操作监视器；不覆盖事务提交或提供分布式互斥。 */
@Component
public final class OrderWriteMonitor {
  /** 创建监视器身份，Spring生产装配中只有一个共享实例。 */
  public OrderWriteMonitor() {}
}
