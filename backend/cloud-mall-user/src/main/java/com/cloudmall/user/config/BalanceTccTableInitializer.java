package com.cloudmall.user.config;

import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时幂等准备 User 余额 TCC 状态表。 */
@Component
public class BalanceTccTableInitializer {
  /** 共享扣款归属表访问器。 */
  private final BalanceDebitAttemptMapper attemptMapper;

  /** TCC 冻结表访问器。 */
  private final BalanceTccDebitMapper tccMapper;

  /** HTTP关闭后仍需观察的同库接收来源表访问器。 */
  private final UserTccReceiveMapper receiveMapper;

  /** 创建 TCC 状态表初始化器。 */
  public BalanceTccTableInitializer(
      BalanceDebitAttemptMapper attemptMapper,
      BalanceTccDebitMapper tccMapper,
      UserTccReceiveMapper receiveMapper) {
    this.attemptMapper = attemptMapper;
    this.tccMapper = tccMapper;
    this.receiveMapper = receiveMapper;
  }

  /** 幂等创建共享归属、冻结及接收来源表，不启动HTTP资源或登记RM。 */
  @PostConstruct
  public void prepareTables() {
    attemptMapper.ensureTable();
    tccMapper.ensureTable();
    receiveMapper.ensureTable();
  }
}
