package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.controller.SeataStockDemoController;
import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.domain.po.SeataStockDemoPO;
import com.cloudmall.stock.domain.vo.SeataStockBranchVO;
import com.cloudmall.stock.mapper.SeataStockDemoMapper;
import com.cloudmall.stock.service.SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.impl.seata.SeataStockDemoServiceImpl;
import io.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** CM-18 Stock 演练分支的边界回归测试。 */
class SeataStockDemoTest {
  private static final String XID = "127.0.0.1:8091:cm18-test";

  @AfterEach
  void clearXid() {
    if (RootContext.getXID() != null) {
      RootContext.unbind();
    }
  }

  @Test
  void serviceWritesOnlyTheDemoRowWithCurrentXid() {
    SeataStockDemoMapper mapper = org.mockito.Mockito.mock(SeataStockDemoMapper.class);
    SeataStockDemoServiceImpl service = new SeataStockDemoServiceImpl(mapper);
    SeataStockBranchDTO request = request("cm18-stock-test");

    SeataStockBranchVO result = service.writeBranch(request, XID);

    assertEquals(request.getTxId(), result.getTxId());
    assertEquals("WRITTEN", result.getStatus());
    verify(mapper).insert(any(SeataStockDemoPO.class));
  }

  @Test
  void controllerRejectsMismatchedHeaderAndBoundXid() {
    var service = org.mockito.Mockito.mock(SeataStockDemoOrchestrator.class);
    var controller = new SeataStockDemoController(service);
    RootContext.bind(XID);

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.writeBranch(
                    "cloud-mall-cm18-test", "NONE", "other-xid", request("cm18-stock-test")));

    assertEquals(400, exception.getStatus().value());
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  @Test
  void controllerInjectsFailureOnlyAfterServiceReturns() {
    var service = org.mockito.Mockito.mock(SeataStockDemoOrchestrator.class);
    when(service.execute(
            any(),
            org.mockito.ArgumentMatchers.eq(XID),
            org.mockito.ArgumentMatchers.eq("STOCK_AFTER_WRITE")))
        .thenThrow(
            new BizException("CM18_CONTROLLED_ROLLBACK", "CM18 STOCK_AFTER_WRITE failure", 409));
    var controller = new SeataStockDemoController(service);
    RootContext.bind(XID);

    BizException exception =
        assertThrows(
            BizException.class,
            () ->
                controller.writeBranch(
                    "cloud-mall-cm18-test", "STOCK_AFTER_WRITE", XID, request("cm18-stock-test")));

    assertEquals("CM18_CONTROLLED_ROLLBACK", exception.getCode());
    verify(service)
        .execute(
            any(),
            org.mockito.ArgumentMatchers.eq(XID),
            org.mockito.ArgumentMatchers.eq("STOCK_AFTER_WRITE"));
  }

  private static SeataStockBranchDTO request(String txId) {
    SeataStockBranchDTO request = new SeataStockBranchDTO();
    request.setTxId(txId);
    return request;
  }
}
