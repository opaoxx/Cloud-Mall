package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.order.controller.SeataTransactionDemoController;
import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.service.impl.seata.SeataTransactionDemoServiceImpl;
import io.seata.common.exception.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** CM-18 相同 txId 并发竞争的 HTTP 语义测试。 */
class SeataTransactionDemoControllerTest {
  @Test
  void duplicateTxIdCompetitionReturnsAcceptedUnknown() {
    SeataTransactionDemoServiceImpl service = mock(SeataTransactionDemoServiceImpl.class);
    SeataTransactionDemoDTO request = new SeataTransactionDemoDTO();
    request.txId = "cm18-concurrent";
    request.failurePoint = "NONE";
    when(service.execute(request)).thenThrow(new DuplicateKeyException("duplicate tx_id"));

    ResponseEntity<?> response =
        new SeataTransactionDemoController(service).execute(request, "cloud-mall-cm18-test");

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    @SuppressWarnings("unchecked")
    com.cloudmall.common.api.ApiResponse<SeataTransactionDemoVO> body =
        (com.cloudmall.common.api.ApiResponse<SeataTransactionDemoVO>) response.getBody();
    assertEquals("CM18_IN_PROGRESS", body.code);
    assertEquals("UNKNOWN", body.data.status);
  }

  @Test
  void seataConnectivityFailureReturnsServiceUnavailableUnknown() {
    SeataTransactionDemoServiceImpl service = mock(SeataTransactionDemoServiceImpl.class);
    SeataTransactionDemoDTO request = request("cm18-tc-down");
    when(service.execute(request))
        .thenThrow(
            new FrameworkException("can not register RM, err: can not connect to services-server"));

    ResponseEntity<?> response =
        new SeataTransactionDemoController(service).execute(request, "cloud-mall-cm18-test");

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    @SuppressWarnings("unchecked")
    com.cloudmall.common.api.ApiResponse<SeataTransactionDemoVO> body =
        (com.cloudmall.common.api.ApiResponse<SeataTransactionDemoVO>) response.getBody();
    assertEquals("CM18_SEATA_UNKNOWN", body.code);
    assertEquals("UNKNOWN", body.data.status);
  }

  private SeataTransactionDemoDTO request(String txId) {
    SeataTransactionDemoDTO request = new SeataTransactionDemoDTO();
    request.txId = txId;
    request.failurePoint = "NONE";
    return request;
  }
}
