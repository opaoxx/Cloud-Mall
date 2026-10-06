package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时幂等准备 Pay TCC 派发表。 */
@Component
public class PayTccDispatchTableInitializer {
  /** 派发表 DDL 的唯一访问入口。 */
  private final PayTccDispatchMapper dispatchMapper;

  /** 创建派发表初始化器。 */
  public PayTccDispatchTableInitializer(PayTccDispatchMapper dispatchMapper) {
    this.dispatchMapper = dispatchMapper;
  }

  /** 幂等创建派发表。 */
  @PostConstruct
  public void prepareTable() {
    dispatchMapper.ensureTable();
  }
}
