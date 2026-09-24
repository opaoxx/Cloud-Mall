package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 在已有本地 MySQL 数据卷中准备支付扣款恢复表。 */
@Component
public class PayDebitIntentTableInitializer {
  /** 支付意图持久层。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 创建支付意图建表器。 */
  public PayDebitIntentTableInitializer(PayDebitIntentMapper payDebitIntentMapper) {
    this.payDebitIntentMapper = payDebitIntentMapper;
  }

  /** 幂等创建本地支付恢复表。 */
  @PostConstruct
  public void prepareTable() {
    // 1. 保证 Docker 已有数据卷也具备恢复表。
    payDebitIntentMapper.ensureTable();
  }
}
