package com.cloudmall.pay.config;

import com.cloudmall.pay.service.PayTccTransactionClient;
import io.seata.tm.TransactionManagerHolder;
import io.seata.tm.api.GlobalTransaction;
import io.seata.tm.api.GlobalTransactionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 编排默认关闭，显式启用时复用 receipt 已校验的本地事务配置。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.pay.tcc-coordinator.enabled", havingValue = "true")
public class PayTccCoordinatorConfiguration {
  /** 校验同库 RM 配置和有界超时后提供真实 TC 客户端。 */
  @Bean
  public PayTccTransactionClient payTccTransactionClient(
      PayTccReceiptConfiguration receipt, Environment environment) {
    receipt.validate();
    int timeout =
        environment.getProperty("cloudmall.pay.tcc-coordinator.timeout-ms", Integer.class, 30000);
    if (timeout <= 0 || timeout > 120000)
      throw new IllegalStateException("TCC timeout 必须在 1..120000ms");
    return new OfficialTransactionClient(timeout);
  }

  /** 官方 1.5.2 SDK 适配，避免恢复时错误使用新 begin。 */
  private static final class OfficialTransactionClient implements PayTccTransactionClient {
    /** 原全局事务的超时时长。 */
    private final int timeout;

    private OfficialTransactionClient(int timeout) {
      this.timeout = timeout;
    }

    /** 唯一首次 begin，由协调器负责 finally 清理绑定。 */
    @Override
    public String begin() throws Exception {
      GlobalTransaction transaction = GlobalTransactionContext.createNew();
      transaction.begin(timeout, "cloudmall-pay-debit");
      return transaction.getXid();
    }

    /** 使用原 XID 请求提交；返回状态仍需两端持久事实核对。 */
    @Override
    public void commit(String xid) throws Exception {
      TransactionManagerHolder.get().commit(xid);
    }

    /** 使用原 XID 请求回滚；返回状态仍需两端持久事实核对。 */
    @Override
    public void rollback(String xid) throws Exception {
      TransactionManagerHolder.get().rollback(xid);
    }
  }
}
