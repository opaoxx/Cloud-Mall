package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pay/User 完整回滚证明和 FAILED 投影的独立事务外服务。 */
@Service
public class PayTccRollbackProjectionService {
  /** 本地 Pay 取消证明和状态投影。 */
  private final PayTccPublicStateStore states;

  /** User live TCC 状态客户端。 */
  private final UserTccClient users;

  /** User 内部认证凭证。 */
  private final String token;

  /** 创建回滚投影服务。 */
  public PayTccRollbackProjectionService(
      PayTccPublicStateStore states,
      UserTccClient users,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}") String token) {
    this.states = states;
    this.users = users;
    this.token = token;
  }

  /** 核对 Pay/User 完整取消事实并投影 FAILED。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String project(PayVO payment) {
    // 1. 外层事务已暂停，读取本地快照和当前远端证明。
    try {
      PayTccDispatchVO proof = verifiedProof(payment);
      // 2. 证明齐全后在独立短事务投影，不改变原执行决议。
      return states.projectFailed(payment, proof.xid);
    } catch (feign.FeignException unavailable) {
      throw pending();
    }
  }

  /** 核对完整取消事实后使用恢复 token 完成受保护投影。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String projectClaimed(PayVO payment, String recoveryToken) {
    // 1. 后台必须携带认领 token，不能降级为公开无租约路径。
    if (recoveryToken == null || recoveryToken.isBlank() || recoveryToken.length() > 128) {
      throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "回滚投影租约无效", 202);
    }
    try {
      PayTccDispatchVO proof = verifiedProof(payment);
      // 2. 远端证明完成后再次锁内检查原 token 与租约。
      return states.projectFailedClaimed(payment, proof.xid, recoveryToken);
    } catch (feign.FeignException unavailable) {
      throw pending();
    }
  }

  /** 读取 Pay 取消证明并核对 User live CANCELLED/no-ledger tuple。 */
  private PayTccDispatchVO verifiedProof(PayVO payment) {
    // 1. 同库快照核验原 Pay、execution、receipt/Fence 和 dispatch。
    PayTccDispatchVO proof = states.cancelledProof(payment);
    // 2. 本地事务结束后独立读取 User 当前永久事实。
    com.cloudmall.common.api.ApiResponse<UserTccStateVO> response =
        users.state(
            payment.userId, proof.operationKey, payment.userId, "cloud-mall-pay", token, proof.xid);
    if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
    // 3. 逐项核对同一分支与无扣款证明，缺一项即保持未知。
    UserTccStateVO user = response.data;
    if (!"CANCELLED".equals(user.state)
        || user.ledgerApplied
        || !Objects.equals(user.payNo, payment.payNo)
        || !Objects.equals(user.orderNo, payment.orderNo)
        || !Objects.equals(user.userId, payment.userId)
        || !Objects.equals(user.xid, proof.xid)
        || !Objects.equals(user.branchId, proof.userBranchId)
        || !Objects.equals(user.operationKey, proof.operationKey)
        || !Objects.equals(user.ledgerKey, proof.ledgerKey)
        || user.amount == null
        || !sameAmount(user.amount, payment.amount)) throw pending();
    return proof;
  }

  /** 严格解析金额字符串，格式错误只保留未知结果。 */
  private boolean sameAmount(String amount, BigDecimal expected) {
    try {
      return new BigDecimal(amount).compareTo(expected) == 0;
    } catch (NumberFormatException invalidAmount) {
      return false;
    }
  }

  /** 证据不足时保持原支付未知。 */
  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "订单或资金结果待确认，请保留原支付单", 202);
  }
}
