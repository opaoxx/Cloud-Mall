package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccCancelMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 无论取消开关是否启用，都预创建主动取消永久 gate 台账。 */
@Component
public class PayTccCancelTableInitializer {
  /** 取消台账 Mapper。 */
  private final PayTccCancelMapper cancels;

  /** 创建取消台账初始化器。 */
  public PayTccCancelTableInitializer(PayTccCancelMapper cancels) {
    this.cancels = cancels;
  }

  /** 幂等创建取消表。 */
  @PostConstruct
  public void prepareTable() {
    cancels.ensureTable();
  }
}
