package com.cloudmall.user.config;

import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccHttpService;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import com.cloudmall.user.service.impl.UserTccStateQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 内部 HTTP 桥默认关闭；开启时必须注入已通过同库校验的 RM。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.user.tcc-http.enabled", havingValue = "true")
public class UserTccHttpConfiguration {
  /** 先准备接收表，再创建独立提交服务。 */
  @Bean
  public UserTccReceiveStore userTccReceiveStore(
      UserTccReceiveMapper mapper, BalanceTccDebitLocalResource resource) {
    mapper.ensureTable();
    return new UserTccReceiveStore(mapper);
  }

  /** 创建同一只读快照的状态查询器。 */
  @Bean
  public UserTccStateQuery userTccStateQuery(
      UserTccReceiveMapper receives,
      BalanceDebitAttemptMapper attempts,
      BalanceTccDebitMapper debits,
      BalanceLedgerMapper ledgers,
      TccFenceLogMapper fences) {
    return new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 复用现有内部回调凭证，不增加对外认证模式。 */
  @Bean
  public UserTccHttpService userTccHttpService(
      BalanceTccDebitLocalResource resource,
      UserTccReceiveStore receives,
      UserTccStateQuery query,
      UserMapper users,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}") String token,
      @Value("${cloudmall.user.tcc-debit.enabled:false}") boolean debitEnabled) {
    return new UserTccHttpService(resource, receives, query, users, token, debitEnabled);
  }
}
