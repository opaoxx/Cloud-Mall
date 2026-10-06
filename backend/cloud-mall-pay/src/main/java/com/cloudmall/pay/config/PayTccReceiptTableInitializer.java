package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import javax.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 启用 Pay receipt 时幂等准备业务表。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-receipt.enabled", havingValue = "true")
public class PayTccReceiptTableInitializer {
  /** receipt DDL 的唯一入口。 */
  private final PayTccReceiptMapper receipts;

  /** 创建 receipt 表初始化器。 */
  public PayTccReceiptTableInitializer(PayTccReceiptMapper receipts) {
    this.receipts = receipts;
  }

  /** 幂等创建 Pay receipt 表。 */
  @PostConstruct
  public void prepareTable() {
    receipts.ensureTable();
  }
}
