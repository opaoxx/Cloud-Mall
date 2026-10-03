package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.mapper.Cm18SeataOrderDemoMapper;
import com.cloudmall.order.service.impl.Cm18SeataDemoFacade;
import com.cloudmall.order.service.impl.Cm18SeataDemoService;
import com.cloudmall.order.service.impl.Cm18UnknownTransactionException;
import org.junit.jupiter.api.Test;

/** 确认标记只能在 Seata 全局代理明确完成之后写入。 */
class Cm18SeataDemoFacadeTest {
  private final Cm18SeataDemoService globalService = mock(Cm18SeataDemoService.class);
  private final Cm18SeataOrderDemoMapper mapper = mock(Cm18SeataOrderDemoMapper.class);
  private final Cm18SeataDemoFacade facade = new Cm18SeataDemoFacade(globalService, mapper);

  @Test
  void confirmsCommittedResultBeforeReturningItToCaller() {
    Cm18SeataDemoDTO request = request("cm18-facade-commit");
    when(globalService.execute(request))
        .thenReturn(new Cm18SeataDemoVO(request.txId, "xid-hash", "COMMITTED"));
    when(mapper.markCommitted(request.txId)).thenReturn(1);

    Cm18SeataDemoVO result = facade.execute(request);

    assertEquals("COMMITTED", result.status);
    verify(mapper).markCommitted(request.txId);
  }

  @Test
  void failedConfirmationDoesNotPretendTheVisibleBranchCommitted() {
    Cm18SeataDemoDTO request = request("cm18-facade-unknown");
    when(globalService.execute(request))
        .thenReturn(new Cm18SeataDemoVO(request.txId, "xid-hash", "COMMITTED"));
    Cm18SeataOrderDemoPO visibleBranch = new Cm18SeataOrderDemoPO();
    visibleBranch.txId = request.txId;
    visibleBranch.status = "CREATED";
    when(mapper.selectById(request.txId)).thenReturn(visibleBranch);

    assertThrows(Cm18UnknownTransactionException.class, () -> facade.execute(request));
  }

  private static Cm18SeataDemoDTO request(String txId) {
    Cm18SeataDemoDTO request = new Cm18SeataDemoDTO();
    request.txId = txId;
    request.failurePoint = "NONE";
    return request;
  }
}
