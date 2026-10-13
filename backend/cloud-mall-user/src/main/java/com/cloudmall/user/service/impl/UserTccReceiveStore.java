package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** LocalTCC 分支登记前独立提交一次性接收事实。 */
public class UserTccReceiveStore {
  /** 接收台账访问器。 */
  private final UserTccReceiveMapper mapper;

  /** 创建接收事务服务。 */
  public UserTccReceiveStore(UserTccReceiveMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 仅原用户状态启用的首次winner取得派发权，已有接收重放不受当前状态限制。
   *
   * @param userId 原调用用户标识
   * @param request 已通过HTTP校验的不可变请求
   * @param userStatus 调用方此前观察的用户状态，不提供读取后变化的fencing
   * @return 是否取得首次派发权
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(Long userId, UserTccTryDTO request, Integer userStatus) {
    // 1. 固定全部载荷并认领数据库唯一键。
    UserTccReceivePO receipt = new UserTccReceivePO();
    receipt.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    receipt.userId = userId;
    receipt.payNo = request.payNo;
    receipt.orderNo = request.orderNo;
    receipt.amount = request.amount;
    receipt.xid = request.xid;
    receipt.operationKey = request.operationKey;
    receipt.ledgerKey = request.ledgerKey;
    receipt.createdAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    receipt.updatedAt = receipt.createdAt;
    int inserted = mapper.claim(receipt);
    // 2. 唯一键冲突不得换用户、金额、事务或操作键。
    UserTccReceivePO stored = readLockedReceipt(request.payNo);
    if (stored == null
        || stored.amount == null
        || !Objects.equals(stored.userId, userId)
        || !Objects.equals(stored.payNo, request.payNo)
        || !Objects.equals(stored.orderNo, request.orderNo)
        || !Objects.equals(stored.xid, request.xid)
        || !Objects.equals(stored.operationKey, request.operationKey)
        || !Objects.equals(stored.ledgerKey, request.ledgerKey)
        || stored.amount.compareTo(request.amount) != 0) throw conflict();
    // 3. 原载荷已先证明；禁用首次winner必须在独立事务提交前回滚认领，不影响既有重放。
    if (inserted == 1 && !Objects.equals(userStatus, 1))
      throw new BizException("IDEMPOTENCY_CONFLICT", "用户不可用", 409);
    return inserted == 1;
  }

  /** POST 成功后补记首次观察分支，既有分支不能被覆盖。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void bindBranch(String payNo, String xid, Long branchId) {
    // 1. 未观察分支保持noop，非法分支不执行Mapper。
    if (branchId == null) return;
    if (branchId <= 0) throw conflict();
    // 2. 原候选更新与锁读留在同一个独立事务中。
    mapper.bindBranch(payNo, xid, branchId, LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    UserTccReceivePO stored = readLockedReceipt(payNo);
    // 3. 原支付/事务/分支不匹配时，整个元数据事务回滚。
    if (stored == null
        || !Objects.equals(stored.payNo, payNo)
        || !Objects.equals(stored.xid, xid)
        || !Objects.equals(stored.branchId, branchId)) throw conflict();
  }

  /** 保持原单次FOR UPDATE；仅精确多结果转载荷冲突，使当前独立事务写入回滚。 */
  private UserTccReceivePO readLockedReceipt(String payNo) {
    try {
      return mapper.lockByPayNo(payNo);
    } catch (TooManyResultsException | MyBatisSystemException failure) {
      if (failure instanceof TooManyResultsException
          || failure.getCause() instanceof TooManyResultsException) throw conflict();
      throw failure;
    }
  }

  /** 冲突不改变原接收事实。 */
  private BizException conflict() {
    return new BizException("IDEMPOTENCY_CONFLICT", "TCC 接收载荷不一致", 409);
  }
}
