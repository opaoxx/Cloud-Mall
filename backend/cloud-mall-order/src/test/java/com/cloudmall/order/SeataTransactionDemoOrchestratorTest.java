package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.SeataStockDemoBranchVO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoControlledRollbackException;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoOrchestratorImpl;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** CM-18 order 侧事务编排定向测试。 */
class SeataTransactionDemoOrchestratorTest {
  @Mock private SeataOrderDemoMapper demoMapper;
  @Mock private com.cloudmall.order.service.impl.seata.SeataOrderDemoBranchServiceImpl orderBranch;
  @Mock private StockClient stockClient;

  private AutoCloseable mocks;
  private SeataTransactionDemoOrchestratorImpl service;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    service = new SeataTransactionDemoOrchestratorImpl(demoMapper, orderBranch, stockClient);
    RootContext.bind("xid-cm18-test");
  }

  @AfterEach
  void tearDown() throws Exception {
    RootContext.unbind();
    mocks.close();
  }

  @Test
  void commitsAndPropagatesSameXid() {
    SeataTransactionDemoDTO request = request("cm18-normal", "NONE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);
    SeataStockDemoBranchVO stockResult = new SeataStockDemoBranchVO();
    stockResult.txId = request.txId;
    stockResult.status = "WRITTEN";
    stockResult.xid = "2402d9f47de7f00cc4d9a188";
    when(stockClient.executeSeataDemoBranch(
            any(), eq("xid-cm18-test"), eq("NONE"), eq("cloud-mall-cm18-test")))
        .thenReturn(com.cloudmall.common.api.ApiResponse.ok(stockResult));

    SeataTransactionDemoVO result = service.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(orderBranch).write(request.txId, "xid-cm18-test");
    verify(stockClient)
        .executeSeataDemoBranch(any(), eq("xid-cm18-test"), eq("NONE"), eq("cloud-mall-cm18-test"));
  }

  @Test
  void incompleteHttp200ResponseRemainsUnknown() {
    SeataTransactionDemoDTO request = request("cm18-unknown", "NONE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);
    when(stockClient.executeSeataDemoBranch(any(), any(), any(), any())).thenReturn(null);

    assertThrows(
        com.cloudmall.order.service.SeataDemoUnknownTransactionException.class,
        () -> service.execute(request));
  }

  @Test
  void orderFailureIsRaisedAfterLocalBranchCall() {
    SeataTransactionDemoDTO request = request("cm18-order-failure", "ORDER_AFTER_WRITE");
    when(demoMapper.selectById(request.txId)).thenReturn(null);

    assertThrows(SeataDemoControlledRollbackException.class, () -> service.execute(request));

    verify(orderBranch).write(request.txId, "xid-cm18-test");
    verify(stockClient, never()).executeSeataDemoBranch(any(), any(), any(), any());
  }

  @Test
  void committedTxIdIsIdempotentWithoutCallingStock() {
    SeataTransactionDemoDTO request = request("cm18-repeated", "NONE");
    SeataOrderDemoPO existing = new SeataOrderDemoPO();
    existing.txId = request.txId;
    existing.txXid = "xid-already-committed";
    existing.status = "COMMITTED";
    when(demoMapper.selectById(request.txId)).thenReturn(existing);

    SeataTransactionDemoVO result = service.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(orderBranch, never()).write(any(), any());
    verify(stockClient, never()).executeSeataDemoBranch(any(), any(), any(), any());
  }

  @Test
  void visibleLocalBranchIsNotReportedCommittedWhileGlobalRollbackIsPossible() {
    SeataTransactionDemoDTO request = request("cm18-in-flight", "NONE");
    SeataOrderDemoPO existing = new SeataOrderDemoPO();
    existing.txId = request.txId;
    existing.txXid = "xid-still-active";
    existing.status = "CREATED";
    when(demoMapper.selectById(request.txId)).thenReturn(existing);

    assertThrows(SeataDemoUnknownTransactionException.class, () -> service.execute(request));

    verify(orderBranch, never()).write(any(), any());
    verify(stockClient, never()).executeSeataDemoBranch(any(), any(), any(), any());
  }

  private SeataTransactionDemoDTO request(String txId, String failurePoint) {
    SeataTransactionDemoDTO request = new SeataTransactionDemoDTO();
    request.txId = txId;
    request.failurePoint = failurePoint;
    return request;
  }
}
