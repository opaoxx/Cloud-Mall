package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import javax.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仅在 coordinator 启用时幂等准备 execution 表。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-coordinator.enabled", havingValue = "true")
public class PayTccExecutionTableInitializer {
  /** Execution 表 Mapper。 */
  private final PayTccExecutionMapper executions;

  /** 新旧 execution 表的恢复调度列迁移。 */
  private final PayTccExecutionRecoveryMapper recovery;

  /** 创建 execution 表初始化器。 */
  public PayTccExecutionTableInitializer(
      PayTccExecutionMapper executions, PayTccExecutionRecoveryMapper recovery) {
    this.executions = executions;
    this.recovery = recovery;
  }

  /** 幂等创建 execution 表。 */
  @PostConstruct
  public void prepareTable() {
    executions.ensureTable();
    recovery.ensureSchema();
  }
}
