package com.cloudmall.user.config;

import com.cloudmall.user.mapper.BalanceLedgerMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 在已有本地 MySQL 数据卷中准备余额账本。 */
@Component
public class BalanceLedgerTableInitializer {
  /** 余额账本 Mapper，负责所有账本 SQL。 */
  private final BalanceLedgerMapper balanceLedgerMapper;

  /** 创建余额账本初始化器。 */
  public BalanceLedgerTableInitializer(BalanceLedgerMapper balanceLedgerMapper) {
    this.balanceLedgerMapper = balanceLedgerMapper;
  }

  /** 幂等创建余额账本表。 */
  @PostConstruct
  public void prepareTable() {
    // 1. 为新旧本地数据库统一准备持久化幂等表。
    balanceLedgerMapper.ensureTable();
  }
}
