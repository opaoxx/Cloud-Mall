package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** 内部 HTTP 桥：认证、不可变接收认领、XID 绑定与唯一分支调用。 */
public class UserTccHttpService {
  /** 已注册的真实 LocalTCC 代理，不允许使用 raw 实现。 */
  private final BalanceTccDebitLocalResource resource;

  /** 注册分支前独立提交的接收事务。 */
  private final UserTccReceiveStore receives;

  /** 只读持久事实查询。 */
  private final UserTccStateQuery query;

  /** 注册分支前校验用户存在，避免无有效用户的不可清算分支。 */
  private final UserMapper users;

  /** 内部调用凭证的固定字节表示，不进入日志或响应。 */
  private final byte[] token;

  /** 构造唯一入口，配置凭证不得为空。 */
  public UserTccHttpService(
      BalanceTccDebitLocalResource resource,
      UserTccReceiveStore receives,
      UserTccStateQuery query,
      UserMapper users,
      String token) {
    if (token == null || token.isBlank()) throw new IllegalStateException("内部 TCC 凭证未配置");
    this.resource = resource;
    this.receives = receives;
    this.query = query;
    this.users = users;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  /** 只有首次接收 winner 进入 LocalTCC；所有重放都只读事实。 */
  public UserTccStateVO prepare(
      Long userId,
      Long callerId,
      String service,
      String suppliedToken,
      String xid,
      UserTccTryDTO request) {
    // 1. 所有身份和原载荷校验在持久化/分支注册之前完成。
    authenticate(userId, callerId, service, suppliedToken);
    validate(xid, request);
    String previous = RootContext.getXID();
    if (previous != null && !previous.equals(xid)) throw conflict();
    UserPO user = users.selectById(userId);
    if (user == null) throw new BizException("COMMON_NOT_FOUND", "用户不存在", 404);
    boolean winner = receives.claim(userId, request, user.status);
    if (!winner) return query.read(userId, request.operationKey, xid);
    // 2. 先提交 CLAIMED 再调用代理；即使失败、超时或崩溃也不恢复派发权限。
    boolean bound = previous == null;
    if (bound) RootContext.bind(xid);
    try {
      BalanceTccBranchDTO branch = new BalanceTccBranchDTO();
      branch.userId = userId;
      branch.payNo = request.payNo;
      branch.orderNo = request.orderNo;
      branch.amount = request.amount;
      if (!resource.prepare(null, branch))
        throw new BizException("USER_TCC_TRY_UNKNOWN", "TCC Try 待核对", 503);
      UserTccStateVO state = query.read(userId, request.operationKey, xid);
      receives.bindBranch(request.payNo, xid, state.branchId);
      return state;
    } finally {
      // 3. 本方法只清理自己建立的上下文，不能污染复用的 HTTP 线程。
      if (bound) RootContext.unbind();
    }
  }

  /** 只读查询不绑定事务，也不修改接收凭据。 */
  public UserTccStateVO state(
      Long userId,
      Long callerId,
      String service,
      String suppliedToken,
      String operationKey,
      String xid) {
    authenticate(userId, callerId, service, suppliedToken);
    if (operationKey == null
        || !operationKey.startsWith("tcc-debit:")
        || xid == null
        || xid.isBlank()
        || xid.length() > 128) throw new BizException("COMMON_INVALID_ARGUMENT", "查询参数不合法", 400);
    return query.read(userId, operationKey, xid);
  }

  /** 服务凭证与用户归属必须同时匹配。 */
  private void authenticate(Long userId, Long callerId, String service, String suppliedToken) {
    if (userId == null
        || userId <= 0
        || !Objects.equals(userId, callerId)
        || !"cloud-mall-pay".equals(service)
        || suppliedToken == null
        || !MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.UTF_8)))
      throw new BizException("COMMON_FORBIDDEN", "内部调用身份无效", 403);
  }

  /** 固定键、事务和无损金额，非法载荷不消耗一次性接收权。 */
  private void validate(String xid, UserTccTryDTO request) {
    if (request == null
        || xid == null
        || xid.isBlank()
        || xid.length() > 128
        || !Objects.equals(xid, request.xid)
        || request.payNo == null
        || !request.payNo.matches("[A-Za-z0-9_-]{1,64}")
        || request.orderNo == null
        || request.orderNo.isBlank()
        || request.orderNo.length() > 64
        || !Objects.equals(request.operationKey, "tcc-debit:" + request.payNo)
        || !Objects.equals(request.ledgerKey, request.payNo)
        || request.amount == null
        || request.amount.signum() <= 0
        || request.amount.stripTrailingZeros().scale() > 2
        || request.amount.compareTo(new BigDecimal("9999999999999999.99")) > 0) throw conflict();
  }

  /** 不允许请求覆盖框架或持久身份。 */
  private BizException conflict() {
    return new BizException("IDEMPOTENCY_CONFLICT", "TCC 请求身份或载荷不一致", 409);
  }
}
