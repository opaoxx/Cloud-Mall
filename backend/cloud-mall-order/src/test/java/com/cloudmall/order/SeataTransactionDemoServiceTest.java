package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoOrchestratorImpl;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoServiceImpl;
import org.junit.jupiter.api.Test;

/** 确认标记只能在 Seata 全局代理明确完成之后写入。 */
class SeataTransactionDemoServiceTest {
  private final SeataTransactionDemoOrchestratorImpl globalService =
      mock(SeataTransactionDemoOrchestratorImpl.class);
  private final SeataOrderDemoMapper mapper = mock(SeataOrderDemoMapper.class);
  private final SeataTransactionDemoServiceImpl facade =
      new SeataTransactionDemoServiceImpl(globalService, mapper);

  @Test
  void confirmsCommittedResultBeforeReturningItToCaller() {
    SeataTransactionDemoDTO request = request("cm18-facade-commit");
    when(globalService.execute(request))
        .thenReturn(new SeataTransactionDemoVO(request.txId, "xid-hash", "COMMITTED"));
    when(mapper.markCommitted(request.txId)).thenReturn(1);

    SeataTransactionDemoVO result = facade.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(mapper).markCommitted(request.txId);
  }

  @Test
  void failedConfirmationDoesNotPretendTheVisibleBranchCommitted() {
    SeataTransactionDemoDTO request = request("cm18-facade-unknown");
    when(globalService.execute(request))
        .thenReturn(new SeataTransactionDemoVO(request.txId, "xid-hash", "COMMITTED"));
    SeataOrderDemoPO visibleBranch = new SeataOrderDemoPO();
    visibleBranch.txId = request.txId;
    visibleBranch.status = "CREATED";
    when(mapper.selectById(request.txId)).thenReturn(visibleBranch);

    assertThrows(SeataDemoUnknownTransactionException.class, () -> facade.execute(request));
  }

  private static SeataTransactionDemoDTO request(String txId) {
    SeataTransactionDemoDTO request = new SeataTransactionDemoDTO();
    request.txId = txId;
    request.failurePoint = "NONE";
    return request;
  }
}
