package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayTccAbortProofVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式失败请求只为原未决执行持久选择回滚；TC 清算由既有恢复器处理。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
public class PayTccAbortService {
  /** 两段短事务与原决议互斥。 */
  private final PayTccAbortStore store;

  /** 当前 User 分支只读证明，禁止任何资金写入。 */
  private final UserTccClient users;

  /** User 内部只读接口凭证。 */
  private final String token;

  /** 创建不依赖 coordinator/TC/Order 的回滚请求服务。 */
  public PayTccAbortService(
      PayTccAbortStore store,
      UserTccClient users,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}") String token) {
    this.store = store;
    this.users = users;
    this.token = token;
  }

  /** 只有双端受理、无扣款和原 NONE 决议仍成立时正常返回；不表示已完成回滚。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void requestRollback(PayVO payment) {
    if (RootContext.getXID() != null)
      throw new BizException("PAY_TCC_CONTEXT_INVALID", "回滚请求不得继承其他全局事务", 409);
    // 1. 取得完整本地原分支快照，离开事务再查询 User。
    PayTccAbortProofVO proof = store.preparedProof(payment);
    ApiResponse<UserTccStateVO> response;
    try {
      response =
          users.state(
              proof.userId, proof.operationKey, proof.userId, "cloud-mall-pay", token, proof.xid);
    } catch (feign.FeignException unavailable) {
      throw pending();
    }
    // 2. 只有同 XID/branch 的当前 TRYING 且无账本，才可竞争回滚决议。
    if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
    UserTccStateVO user = response.data;
    if (!"TRYING".equals(user.state)
        || !user.ledgerAppliedPresent
        || user.ledgerApplied
        || !Objects.equals(user.payNo, proof.payNo)
        || !Objects.equals(user.orderNo, proof.orderNo)
        || !Objects.equals(user.userId, proof.userId)
        || !Objects.equals(user.xid, proof.xid)
        || !Objects.equals(user.branchId, proof.userBranchId)
        || !Objects.equals(user.operationKey, proof.operationKey)
        || !Objects.equals(user.ledgerKey, proof.ledgerKey)
        || !sameAmount(user.amount, proof.amount)) throw pending();
    // 3. 锁内重新核验并持久化单向 ROLLBACK，不直接调用 TC 或投影 FAILED。
    store.requestRollback(payment, proof);
  }

  /** 非法或缺失远端金额只表示证明不足。 */
  private boolean sameAmount(String left, String right) {
    if (left == null || right == null) return false;
    try {
      return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    } catch (NumberFormatException malformed) {
      return false;
    }
  }

  /** 用户当前事实不允许据此执行相反决议。 */
  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "User分支尚不能证明可回滚，请等待原资金结果", 202);
  }
}
