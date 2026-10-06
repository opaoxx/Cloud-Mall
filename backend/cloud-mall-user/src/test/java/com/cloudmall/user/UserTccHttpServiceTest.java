package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccHttpService;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import com.cloudmall.user.service.impl.UserTccStateQuery;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** User TCC HTTP 桥的认证、接收认领和 XID 绑定测试。 */
class UserTccHttpServiceTest {
  private static final String TOKEN = "callback-token";
  private BalanceTccDebitLocalResource resource;
  private UserTccReceiveStore receives;
  private UserTccStateQuery query;
  private UserMapper users;
  private UserTccHttpService service;

  /** 准备内部 HTTP 桥依赖。 */
  @BeforeEach
  void setUp() {
    resource = mock(BalanceTccDebitLocalResource.class);
    receives = mock(UserTccReceiveStore.class);
    query = mock(UserTccStateQuery.class);
    users = mock(UserMapper.class);
    service = new UserTccHttpService(resource, receives, query, users, TOKEN);
    RootContext.unbind();
  }

  /** 清理测试线程的 XID。 */
  @AfterEach
  void tearDown() {
    RootContext.unbind();
  }

  /** 错误服务身份在接收认领和资源注册前被拒绝。 */
  @Test
  void invalidAuthenticationDoesNotTouchReceiveOrResource() {
    assertThrows(
        BizException.class,
        () -> service.prepare(7L, 8L, "cloud-mall-pay", TOKEN, "xid-http-1", request()));

    verifyNoInteractions(receives, resource, query, users);
  }

  /** 请求 XID 与 body XID 不一致时不消耗接收认领权。 */
  @Test
  void mismatchedXidDoesNotClaimReceive() {
    UserTccTryDTO request = request();
    request.xid = "xid-body-other";

    assertThrows(
        BizException.class,
        () -> service.prepare(7L, 7L, "cloud-mall-pay", TOKEN, "xid-header", request));

    verifyNoInteractions(receives, resource, query);
    verify(users, never()).selectById(7L);
  }

  /** 重复 claim 只读取 User 状态，不能再次进入 LocalTCC 资源。 */
  @Test
  void repeatedClaimReadsStateWithoutRegisteringSecondBranch() {
    UserTccTryDTO request = request();
    UserTccStateVO state = state("TRYING", 17L);
    doReturn(new com.cloudmall.user.domain.po.UserPO()).when(users).selectById(7L);
    doReturn(false).when(receives).claim(eq(7L), any(UserTccTryDTO.class));
    doReturn(state).when(query).read(7L, request.operationKey, request.xid);

    service.prepare(7L, 7L, "cloud-mall-pay", TOKEN, request.xid, request);

    verify(query).read(7L, request.operationKey, request.xid);
    verify(resource, never()).prepare(any(), any());
    verify(receives, never()).bindBranch(any(), any(), any());
  }

  /** 首次 claim 才能调用资源并补记 User 分支。 */
  @Test
  void firstClaimRegistersResourceAndBindsObservedBranch() {
    UserTccTryDTO request = request();
    UserTccStateVO state = state("TRYING", 18L);
    doReturn(new com.cloudmall.user.domain.po.UserPO()).when(users).selectById(7L);
    doReturn(true).when(receives).claim(eq(7L), any(UserTccTryDTO.class));
    doReturn(true).when(resource).prepare(any(), any());
    doReturn(state).when(query).read(7L, request.operationKey, request.xid);

    service.prepare(7L, 7L, "cloud-mall-pay", TOKEN, request.xid, request);

    verify(resource).prepare(any(), any());
    verify(receives).bindBranch(request.payNo, request.xid, 18L);
  }

  /** GET 状态查询不绑定或改变当前线程 RootContext XID。 */
  @Test
  void stateQueryDoesNotBindRootContext() {
    String callerXid = "caller-xid";
    RootContext.bind(callerXid);
    UserTccStateVO state = state("CANCELLED", 19L);
    doReturn(state).when(query).read(7L, "tcc-debit:pay-http-1", "xid-http-1");

    service.state(7L, 7L, "cloud-mall-pay", TOKEN, "tcc-debit:pay-http-1", "xid-http-1");

    org.junit.jupiter.api.Assertions.assertEquals(callerXid, RootContext.getXID());
    verify(query).read(7L, "tcc-debit:pay-http-1", "xid-http-1");
  }

  private UserTccTryDTO request() {
    UserTccTryDTO request = new UserTccTryDTO();
    request.payNo = "pay-http-1";
    request.orderNo = "order-http-1";
    request.amount = new BigDecimal("10.00");
    request.xid = "xid-http-1";
    request.operationKey = "tcc-debit:pay-http-1";
    request.ledgerKey = "pay-http-1";
    return request;
  }

  private UserTccStateVO state(String state, Long branchId) {
    UserTccStateVO view = new UserTccStateVO();
    view.userId = 7L;
    view.payNo = "pay-http-1";
    view.orderNo = "order-http-1";
    view.amount = "10.00";
    view.operationKey = "tcc-debit:pay-http-1";
    view.ledgerKey = "pay-http-1";
    view.xid = "xid-http-1";
    view.branchId = branchId;
    view.state = state;
    return view;
  }
}
