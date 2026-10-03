package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.Cm18SeataOrderDemoMapper;
import com.cloudmall.order.service.impl.Cm18ControlledRollbackException;
import com.cloudmall.order.service.impl.Cm18SeataDemoService;
import com.cloudmall.order.service.impl.Cm18UnknownTransactionException;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** CM-18 order 侧事务编排定向测试。 */
class Cm18SeataDemoServiceTest {
  @Mock private Cm18SeataOrderDemoMapper demoMapper;
  @Mock private com.cloudmall.order.service.impl.Cm18SeataOrderBranchService orderBranch;
  @Mock private StockClient stockClient;

  private AutoCloseable mocks;
  private Cm18SeataDemoService service;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    service = new Cm18SeataDemoService(demoMapper, orderBranch, stockClient);
    RootContext.bind("xid-cm18-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    RootContext.unbind();
    mocks.close();
  }

  @Test
  void commitsAndPropagatesSameXid() {
    Cm18SeataDemoDTO request = request("cm18-normal", "NONE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);
    Cm18SeataBranchVO stockResult = new Cm18SeataBranchVO();
    stockResult.txId = request.txId;
    stockResult.status = "WRITTEN";
    stockResult.xid = "2402d9f47de7f00cc4d9a188";
    when(stockClient.cm18SeataBranch(any(), eq("xid-cm18-test"), eq("NONE"), eq("cloud-mall-cm18-test")))
        .thenReturn(com.cloudmall.common.api.ApiResponse.ok(stockResult));

    Cm18SeataDemoVO result = service.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(orderBranch).write(request.txId, "xid-cm18-test");
    verify(stockClient).cm18SeataBranch(any(), eq("xid-cm18-test"), eq("NONE"), eq("cloud-mall-cm18-test"));
  }

  @Test
  void incompleteHttp200ResponseRemainsUnknown() {
    Cm18SeataDemoDTO request = request("cm18-unknown", "NONE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);
    when(stockClient.cm18SeataBranch(any(), any(), any(), any())).thenReturn(null);

    assertThrows(
        com.cloudmall.order.service.impl.Cm18UnknownTransactionException.class,
        () -> service.execute(request));
  }

  @Test
  void orderFailureIsRaisedAfterLocalBranchCall() {
    Cm18SeataDemoDTO request = request("cm18-order-failure", "ORDER_AFTER_WRITE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);

    assertThrows(Cm18ControlledRollbackException.class, () -> service.execute(request));

    verify(orderBranch).write(request.txId, "xid-cm18-test");
    verify(stockClient, never()).cm18SeataBranch(any(), any(), any(), any());
  }

  @Test
  void committedTxIdIsIdempotentWithoutCallingStock() {
    Cm18SeataDemoDTO request = request("cm18-repeated", "NONE");
    Cm18SeataOrderDemoPO existing = new Cm18SeataOrderDemoPO();
    existing.txId = request.txId;
    existing.txXid = "xid-already-committed";
    existing.status = "COMMITTED";
    when(demoMapper.selectById(request.txId)).thenReturn(existing);

    Cm18SeataDemoVO result = service.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(orderBranch, never()).write(any(), any());
    verify(stockClient, never()).cm18SeataBranch(any(), any(), any(), any());
  }

  @Test
  void visibleLocalBranchIsNotReportedCommittedWhileGlobalRollbackIsPossible() {
    Cm18SeataDemoDTO request = request("cm18-in-flight", "NONE");
    Cm18SeataOrderDemoPO existing = new Cm18SeataOrderDemoPO();
    existing.txId = request.txId;
    existing.txXid = "xid-still-active";
    existing.status = "CREATED";
    when(demoMapper.selectById(request.txId)).thenReturn(existing);

    assertThrows(Cm18UnknownTransactionException.class, () -> service.execute(request));

    verify(orderBranch, never()).write(any(), any());
    verify(stockClient, never()).cm18SeataBranch(any(), any(), any(), any());
  }

  private Cm18SeataDemoDTO request(String txId, String failurePoint) {
    Cm18SeataDemoDTO request = new Cm18SeataDemoDTO();
    request.txId = txId;
    request.failurePoint = failurePoint;
    return request;
  }
}
