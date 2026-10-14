package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccBridgeConfiguration;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.dto.UserTccTryDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.service.PayTccBridgeService;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Pay TCC 一次性派发桥，POST 后永不自动重发。 */
@Service
public class PayTccBridgeServiceImpl implements PayTccBridgeService {
  /** User 和 Pay 金额列允许的最大无损金额。 */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

  /** 业务数据库时间语义。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 原支付记录载荷与模式来源。 */
  private final PayRecordMapper payRecordMapper;

  /** 派发事实只读访问器。 */
  private final PayTccDispatchMapper dispatchMapper;

  /** 独立提交派发权限和镜像的事务代理。 */
  private final PayTccDispatchPersistence persistence;

  /** 只能一次 POST、后续只读 GET 的 User 客户端。 */
  private final UserTccClient userTccClient;

  /** 桥接功能及事务配置。 */
  private final PayTccBridgeConfiguration configuration;

  /** 仅用于内部 Feign 认证，不写入日志。 */
  private final String callbackToken;

  /** 创建 Pay TCC 派发桥。 */
  public PayTccBridgeServiceImpl(
      PayRecordMapper payRecordMapper,
      PayTccDispatchMapper dispatchMapper,
      PayTccDispatchPersistence persistence,
      UserTccClient userTccClient,
      PayTccBridgeConfiguration configuration,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.payRecordMapper = payRecordMapper;
    this.dispatchMapper = dispatchMapper;
    this.persistence = persistence;
    this.userTccClient = userTccClient;
    this.configuration = configuration;
    this.callbackToken = callbackToken;
  }

  /** 校验原支付事实、提交 PREPARED 并唯一发出 User Try。 */
  @Override
  public PayTccDispatchVO dispatch(PayTccDispatchDTO request) {
    // 1. 保存或核对不可变锚点，只有 PREPARED 可竞争一次性 POST 权限。
    PayTccDispatchPO dispatch = prepareDispatch(request);
    // 2. 已消费权限的请求只能查询，不能注册第二 User 分支。
    return "PREPARED".equals(dispatch.state) ? postOnce(dispatch) : recover(dispatch.payNo);
  }

  /** 在注册 Pay 分支前持久保存 XID，绝不调用 User 或消费 POST 权限。 */
  @Override
  public PayTccDispatchVO prepare(PayTccDispatchDTO request) {
    return PayTccDispatchVO.from(prepareDispatch(request));
  }

  /** 持久锚点独立于后续 Pay/User 分支，本方法任何重入不得改变原 XID。 */
  private PayTccDispatchPO prepareDispatch(PayTccDispatchDTO request) {
    // 1. 校验开关、RootContext XID 和原支付记录的不可变载荷。
    validateRequest(request);
    String xid = RootContext.getXID();
    if (xid == null || xid.isBlank() || xid.length() > 128) throw unavailable("缺少或无效 Seata XID");
    if (!configuration.isEnabled()) throw unavailable("TCC bridge 当前未启用");
    PayRecordPO payment = readPayment(request);
    PayTccDispatchPO existing = dispatchMapper.selectByPayNo(request.payNo);
    if (existing != null) {
      validatePayment(payment, request, false);
      validateDispatch(existing, request, xid);
      return existing;
    }
    validatePayment(payment, request, true);
    // 2. 独立提交不可变 PREPARED；唯一键竞争者只核对原事实。
    PayTccDispatchPO prepared = prepared(request, payment, xid);
    try {
      persistence.prepare(prepared);
    } catch (DuplicateKeyException duplicateDispatch) {
      PayTccDispatchPO concurrent = dispatchMapper.selectByPayNo(request.payNo);
      if (concurrent == null) throw duplicateDispatch;
      validateDispatch(concurrent, request, xid);
      return concurrent;
    }
    return prepared;
  }

  /** 只读 User 状态并更新 Pay 镜像，绝不重新发送 POST。 */
  @Override
  public PayTccDispatchVO recover(String payNo) {
    // 1. 读取派发事实并核对原支付号，先拒绝查询别名，再处理终态或配置。
    PayTccDispatchPO dispatch = dispatchMapper.selectByPayNo(payNo);
    if (dispatch == null) throw new BizException("PAY_TCC_DISPATCH_NOT_FOUND", "TCC 派发不存在", 404);
    if (dispatch.payNo == null || !Objects.equals(dispatch.payNo, payNo))
      throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "派发记录与原支付号不一致", 409);
    if (terminal(dispatch.state)) return PayTccDispatchVO.from(dispatch);
    if (!configuration.isEnabled()) throw unavailable("TCC bridge 当前未启用");
    if ("PREPARED".equals(dispatch.state)) return PayTccDispatchVO.from(dispatch);
    // 2. 只调用 User GET，所有 HTTP/Feign/空响应错误保持 UNKNOWN。
    try {
      ApiResponse<UserTccStateVO> response =
          userTccClient.state(
              dispatch.userId,
              dispatch.operationKey,
              dispatch.userId,
              "cloud-mall-pay",
              callbackToken,
              dispatch.xid);
      if (response == null || response.data == null || !"0".equals(response.code)) {
        return mirrorUnknown(dispatch, "User GET 未返回可核对事实");
      }
      validateState(response.data, dispatch);
      String target = mapState(response.data.state);
      if (target == null) return mirrorUnknown(dispatch, "User 状态仍未知");
      return mirror(dispatch, target, response.data.branchId, null);
    } catch (Exception readFailure) {
      rethrowIdentityConflict(readFailure);
      return mirrorUnknown(dispatch, readFailure.getClass().getSimpleName());
    }
  }

  /** 先持久消耗唯一 POST 权限，再发送请求；任何不确定响应都不归还权限。 */
  private PayTccDispatchVO postOnce(PayTccDispatchPO dispatch) {
    LocalDateTime now = now();
    if (!persistence.markPostAttempted(dispatch, now)) {
      PayTccDispatchPO current = dispatchMapper.selectByPayNo(dispatch.payNo);
      return current == null ? mirrorUnknown(dispatch, "派发租约丢失") : PayTccDispatchVO.from(current);
    }
    UserTccTryDTO request = new UserTccTryDTO();
    request.payNo = dispatch.payNo;
    request.orderNo = dispatch.orderNo;
    request.amount = dispatch.amount;
    request.xid = dispatch.xid;
    request.operationKey = dispatch.operationKey;
    request.ledgerKey = dispatch.ledgerKey;
    try {
      ApiResponse<UserTccStateVO> response =
          userTccClient.prepare(
              dispatch.userId,
              dispatch.userId,
              "cloud-mall-pay",
              callbackToken,
              dispatch.xid,
              request);
      if (response == null || response.data == null || !"0".equals(response.code)) {
        return mirrorUnknown(dispatch, "User POST 响应未知");
      }
      validateState(response.data, dispatch);
      String targetState = mapResponseState(response.data);
      if (targetState == null) return mirrorUnknown(dispatch, "User POST 状态未知");
      return mirror(dispatch, targetState, response.data.branchId, null);
    } catch (Exception postFailure) {
      rethrowIdentityConflict(postFailure);
      return mirrorUnknown(dispatch, postFailure.getClass().getSimpleName());
    }
  }

  /** 保存不可决议结果，不能推断未扣款或改用新键重发。 */
  private PayTccDispatchVO mirrorUnknown(PayTccDispatchPO dispatch, String reason) {
    return mirror(dispatch, "TRY_UNKNOWN", null, reason);
  }

  /** 事务内校验分支与镜像终态，再返回实际已提交状态。 */
  private PayTccDispatchVO mirror(
      PayTccDispatchPO dispatch, String targetState, Long branchId, String reason) {
    persistence.mirror(dispatch, dispatch.state, targetState, branchId, reason, now());
    PayTccDispatchPO updated = dispatchMapper.selectByPayNo(dispatch.payNo);
    if (updated == null) throw unavailable("派发镜像事实丢失");
    return PayTccDispatchVO.from(updated);
  }

  /** 不可变分支或终态矛盾不能被当作普通网络未知吞掉。 */
  private void rethrowIdentityConflict(Exception failure) {
    if (failure instanceof BizException business
        && "PAY_TCC_DISPATCH_CONFLICT".equals(business.getCode())) throw business;
  }

  /** 读取支付事实；锁定仅发生在 POST_ATTEMPTED 独立事务中。 */
  private PayRecordPO readPayment(PayTccDispatchDTO request) {
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(request.payNo);
    return records.size() == 1 ? records.get(0) : null;
  }

  /** 校验支付载荷和派发所需状态。 */
  private void validatePayment(
      PayRecordPO payment, PayTccDispatchDTO request, boolean requirePending) {
    if (payment == null
        || !Objects.equals(payment.payNo, request.payNo)
        || !Objects.equals(payment.userId, request.userId)
        || !Objects.equals(payment.orderNo, request.orderNo)
        || payment.amount.compareTo(request.amount) != 0
        || !"TCC".equals(payment.debitMode)
        || (requirePending && !"PENDING".equals(payment.status))) {
      throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "原支付记录载荷或状态不允许 TCC 派发", 409);
    }
  }

  /** 只从原支付记录建立不可变 TCC 派发事实。 */
  private PayTccDispatchPO prepared(PayTccDispatchDTO request, PayRecordPO payment, String xid) {
    LocalDateTime now = now();
    PayTccDispatchPO dispatch = new PayTccDispatchPO();
    dispatch.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    dispatch.payNo = request.payNo;
    dispatch.xid = xid;
    dispatch.userId = payment.userId;
    dispatch.orderNo = payment.orderNo;
    dispatch.amount = payment.amount;
    dispatch.operationKey = "tcc-debit:" + request.payNo;
    dispatch.ledgerKey = request.payNo;
    dispatch.state = "PREPARED";
    dispatch.createdAt = now;
    dispatch.updatedAt = now;
    return dispatch;
  }

  /** 同支付号的用户、订单、金额和 XID 不允许被重放覆盖。 */
  private void validateDispatch(PayTccDispatchPO dispatch, PayTccDispatchDTO request, String xid) {
    if (!Objects.equals(dispatch.xid, xid)
        || !Objects.equals(dispatch.payNo, request.payNo)
        || !Objects.equals(dispatch.userId, request.userId)
        || !Objects.equals(dispatch.orderNo, request.orderNo)
        || dispatch.amount.compareTo(request.amount) != 0) {
      throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "TCC 派发载荷不一致", 409);
    }
  }

  /** 完整验证 User 状态身份、分支和账本证据，不能只看 HTTP 成功。 */
  private void validateState(UserTccStateVO state, PayTccDispatchPO dispatch) {
    if (!Objects.equals(state.userId, dispatch.userId)
        || !Objects.equals(state.payNo, dispatch.payNo)
        || !Objects.equals(state.orderNo, dispatch.orderNo)
        || state.amountValue().compareTo(dispatch.amount) != 0
        || !Objects.equals(state.operationKey, dispatch.operationKey)
        || !Objects.equals(state.ledgerKey, dispatch.ledgerKey)
        || !Objects.equals(state.xid, dispatch.xid)) throw unavailable("User TCC 响应载荷不一致");
    if ("UNKNOWN".equals(state.state)) {
      if (state.ledgerApplied) throw unavailable("未知状态不能声称已扣款");
      return;
    }
    if (state.branchId == null || state.branchId <= 0) throw unavailable("User 分支标识缺失");
    if (dispatch.userBranchId != null && !dispatch.userBranchId.equals(state.branchId))
      throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "User 分支标识不一致", 409);
    if (!state.ledgerAppliedPresent) throw unavailable("User账本证据缺失");
    if ("CONFIRMED".equals(state.state) && !state.ledgerApplied) {
      throw unavailable("User CONFIRMED 缺少 APPLIED 账本");
    }
    if (!"CONFIRMED".equals(state.state) && state.ledgerApplied) {
      throw unavailable("User 非确认状态携带 APPLIED 账本");
    }
  }

  /** 将 User 已核验状态映射为 Pay 镜像状态。 */
  private static String mapResponseState(UserTccStateVO state) {
    if ("TRYING".equals(state.state)) return "TRY_ACCEPTED";
    if ("CONFIRMED".equals(state.state)) return "CONFIRMED";
    if ("CANCELLED".equals(state.state)) return "CANCELLED";
    return null;
  }

  /** 将 User 资金状态转换为 Pay 的派发镜像，冻结不等于支付成功。 */
  private static String mapState(String state) {
    if ("TRYING".equals(state)) return "TRY_ACCEPTED";
    if ("CONFIRMED".equals(state)) return "CONFIRMED";
    if ("CANCELLED".equals(state)) return "CANCELLED";
    return null;
  }

  /** 镜像终态只包含已证实的确认或取消。 */
  private static boolean terminal(String state) {
    return "CONFIRMED".equals(state) || "CANCELLED".equals(state);
  }

  /** 不合法的载荷不能获得一次性派发权限。 */
  private static void validateRequest(PayTccDispatchDTO request) {
    if (request == null
        || blank(request.payNo)
        || request.userId == null
        || request.userId <= 0
        || blank(request.orderNo)
        || request.amount == null
        || request.amount.signum() <= 0
        || request.amount.compareTo(MAX_AMOUNT) > 0
        || request.amount.stripTrailingZeros().scale() > 2
        || request.payNo.length() > 64
        || request.orderNo.length() > 64) throw new BizException("INVALID", "TCC 派发参数不合法", 400);
  }

  /** 判断必需业务键是否缺失。 */
  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** 在证据不足时保留原事务等待核对。 */
  private static BizException unavailable(String message) {
    return new BizException("PAY_TCC_UNKNOWN", message, 503);
  }

  /** 生成固定业务时区的数据库时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
