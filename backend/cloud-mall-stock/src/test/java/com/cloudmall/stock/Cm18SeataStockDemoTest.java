package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.controller.Cm18SeataStockDemoController;
import com.cloudmall.stock.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.stock.domain.po.Cm18SeataStockDemoPO;
import com.cloudmall.stock.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.stock.mapper.Cm18SeataStockDemoMapper;
import com.cloudmall.stock.service.Cm18SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.impl.Cm18SeataStockDemoServiceImpl;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** CM-18 Stock 演练分支的边界回归测试。 */
class Cm18SeataStockDemoTest {
  private static final String XID = "127.0.0.1:8091:cm18-test";

  @AfterEach
  void clearXid() {
    if (RootContext.getXID() != null) {
      RootContext.unbind();
    }
  }

  @Test
  void serviceWritesOnlyTheDemoRowWithCurrentXid() {
    Cm18SeataStockDemoMapper mapper = org.mockito.Mockito.mock(Cm18SeataStockDemoMapper.class);
    Cm18SeataStockDemoServiceImpl service = new Cm18SeataStockDemoServiceImpl(mapper);
    Cm18SeataBranchDTO request = request("cm18-stock-test");

    Cm18SeataBranchVO result = service.writeBranch(request, XID);

    assertEquals(request.getTxId(), result.getTxId());
    assertEquals("WRITTEN", result.getStatus());
    verify(mapper).insert(any(Cm18SeataStockDemoPO.class));
  }

  @Test
  void controllerRejectsMismatchedHeaderAndBoundXid() {
    var service = org.mockito.Mockito.mock(Cm18SeataStockDemoOrchestrator.class);
    var controller = new Cm18SeataStockDemoController(service);
    RootContext.bind(XID);

    ResponseStatusException exception = assertThrows(
        ResponseStatusException.class,
        () -> controller.writeBranch(
            "cloud-mall-cm18-test", "NONE", "other-xid", request("cm18-stock-test")));

    assertEquals(400, exception.getStatus().value());
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void controllerInjectsFailureOnlyAfterServiceReturns() {
    var service = org.mockito.Mockito.mock(Cm18SeataStockDemoOrchestrator.class);
    when(service.execute(any(), org.mockito.ArgumentMatchers.eq(XID), org.mockito.ArgumentMatchers.eq("STOCK_AFTER_WRITE")))
        .thenThrow(new BizException("CM18_CONTROLLED_ROLLBACK", "CM18 STOCK_AFTER_WRITE failure", 409));
    var controller = new Cm18SeataStockDemoController(service);
    RootContext.bind(XID);

    BizException exception = assertThrows(
        BizException.class,
        () -> controller.writeBranch(
            "cloud-mall-cm18-test", "STOCK_AFTER_WRITE", XID, request("cm18-stock-test")));

    assertEquals("CM18_CONTROLLED_ROLLBACK", exception.getCode());
    verify(service).execute(any(), org.mockito.ArgumentMatchers.eq(XID), org.mockito.ArgumentMatchers.eq("STOCK_AFTER_WRITE"));
  }

  private static Cm18SeataBranchDTO request(String txId) {
    Cm18SeataBranchDTO request = new Cm18SeataBranchDTO();
    request.setTxId(txId);
    return request;
  }
}
