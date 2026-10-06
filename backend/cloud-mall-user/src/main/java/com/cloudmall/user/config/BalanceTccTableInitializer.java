package com.cloudmall.user.config;

import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时幂等准备 User 余额 TCC 状态表。 */
@Component
public class BalanceTccTableInitializer {
  /** 共享扣款归属表访问器。 */
  private final BalanceDebitAttemptMapper attemptMapper;

  /** TCC 冻结表访问器。 */
  private final BalanceTccDebitMapper tccMapper;

  /** 创建 TCC 状态表初始化器。 */
  public BalanceTccTableInitializer(
      BalanceDebitAttemptMapper attemptMapper, BalanceTccDebitMapper tccMapper) {
    this.attemptMapper = attemptMapper;
    this.tccMapper = tccMapper;
  }

  /** 幂等创建共享归属和冻结状态表。 */
  @PostConstruct
  public void prepareTables() {
    attemptMapper.ensureTable();
    tccMapper.ensureTable();
  }
}
