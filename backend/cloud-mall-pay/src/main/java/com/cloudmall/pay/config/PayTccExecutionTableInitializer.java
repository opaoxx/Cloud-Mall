package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import javax.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仅在 coordinator 启用时幂等准备 execution 表。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-coordinator.enabled", havingValue = "true")
public class PayTccExecutionTableInitializer {
  /** Execution 表 Mapper。 */
  private final PayTccExecutionMapper executions;

  /** 创建 execution 表初始化器。 */
  public PayTccExecutionTableInitializer(PayTccExecutionMapper executions) {
    this.executions = executions;
  }

  /** 幂等创建 execution 表。 */
  @PostConstruct
  public void prepareTable() {
    executions.ensureTable();
  }
}
