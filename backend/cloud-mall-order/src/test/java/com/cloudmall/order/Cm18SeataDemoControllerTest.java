package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.order.controller.Cm18SeataDemoController;
import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.service.impl.Cm18SeataDemoFacade;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.seata.common.exception.FrameworkException;

/** CM-18 相同 txId 并发竞争的 HTTP 语义测试。 */
class Cm18SeataDemoControllerTest {
  @Test
  void duplicateTxIdCompetitionReturnsAcceptedUnknown() {
    Cm18SeataDemoFacade service = mock(Cm18SeataDemoFacade.class);
    Cm18SeataDemoDTO request = new Cm18SeataDemoDTO();
    request.txId = "cm18-concurrent";
    request.failurePoint = "NONE";
    when(service.execute(request)).thenThrow(new DuplicateKeyException("duplicate tx_id"));

    ResponseEntity<?> response =
        new Cm18SeataDemoController(service).execute(request, "cloud-mall-cm18-test");

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    @SuppressWarnings("unchecked")
    com.cloudmall.common.api.ApiResponse<Cm18SeataDemoVO> body =
        (com.cloudmall.common.api.ApiResponse<Cm18SeataDemoVO>) response.getBody();
    assertEquals("CM18_IN_PROGRESS", body.code);
    assertEquals("UNKNOWN", body.data.status);
  }

  @Test
  void seataConnectivityFailureReturnsServiceUnavailableUnknown() {
    Cm18SeataDemoFacade service = mock(Cm18SeataDemoFacade.class);
    Cm18SeataDemoDTO request = request("cm18-tc-down");
    when(service.execute(request))
        .thenThrow(new FrameworkException("can not register RM, err: can not connect to services-server"));

    ResponseEntity<?> response =
        new Cm18SeataDemoController(service).execute(request, "cloud-mall-cm18-test");

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    @SuppressWarnings("unchecked")
    com.cloudmall.common.api.ApiResponse<Cm18SeataDemoVO> body =
        (com.cloudmall.common.api.ApiResponse<Cm18SeataDemoVO>) response.getBody();
    assertEquals("CM18_SEATA_UNKNOWN", body.code);
    assertEquals("UNKNOWN", body.data.status);
  }

  private Cm18SeataDemoDTO request(String txId) {
    Cm18SeataDemoDTO request = new Cm18SeataDemoDTO();
    request.txId = txId;
    request.failurePoint = "NONE";
    return request;
  }
}
