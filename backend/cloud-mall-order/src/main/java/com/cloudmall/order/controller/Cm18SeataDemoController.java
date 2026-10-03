package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.service.impl.Cm18ControlledRollbackException;
import com.cloudmall.order.service.impl.Cm18SeataDemoFacade;
import com.cloudmall.order.service.impl.Cm18UnknownTransactionException;
import io.seata.common.exception.FrameworkException;
import javax.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CM-18 Seata 受控演练内部入口，仅在显式演练开关下注册。 */
@RestController
@RequestMapping("/api/internal/cm18/seata/demo")
@Profile("!prod")
@ConditionalOnProperty(prefix = "cloudmall.seata.cm18", name = "enabled", havingValue = "true")
public class Cm18SeataDemoController {
  /** CM-18 全局事务编排服务。 */
  private final Cm18SeataDemoFacade demoFacade;

  /** 创建 CM-18 演练 Controller。 */
  public Cm18SeataDemoController(Cm18SeataDemoFacade demoFacade) {
    this.demoFacade = demoFacade;
  }

  /**
   * 执行受控的 order/stock Seata 演练。
   *
   * @param request 演练请求
   * @param internalService 内部调用标识
   * @return 事务状态
   */
  @PostMapping
  public ResponseEntity<ApiResponse<Cm18SeataDemoVO>> execute(
      @Valid @RequestBody Cm18SeataDemoDTO request,
      @RequestHeader(value = "X-Internal-Service", required = false) String internalService) {
    if (!"cloud-mall-cm18-test".equals(internalService)) {
      throw new BizException("COMMON_FORBIDDEN", "CM18 仅允许内部演练调用", 403);
    }
    try {
      return ResponseEntity.ok(ApiResponse.ok(demoFacade.execute(request)));
    } catch (Cm18ControlledRollbackException exception) {
      return ResponseEntity.ok(ApiResponse.ok(new Cm18SeataDemoVO(request.txId, "", "ROLLED_BACK")));
    } catch (Cm18UnknownTransactionException exception) {
      ApiResponse<Cm18SeataDemoVO> response = new ApiResponse<>(
          "CM18_SEATA_UNKNOWN", "Seata 全局事务结果待确认",
          new Cm18SeataDemoVO(request.txId, "", "UNKNOWN"));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .cacheControl(CacheControl.noStore()).body(response);
    } catch (DuplicateKeyException exception) {
      // 唯一键竞争只表示同一 txId 已有并发演练，不提前把全局结果判定为 COMMITTED。
      ApiResponse<Cm18SeataDemoVO> response =
          new ApiResponse<>(
              "CM18_IN_PROGRESS",
              "相同 txId 的全局事务正在完成，请稍后重试",
              new Cm18SeataDemoVO(request.txId, "", "UNKNOWN"));
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .cacheControl(CacheControl.noStore()).body(response);
    } catch (FrameworkException exception) {
      if (!isSeataConnectivityFailure(exception)) {
        throw exception;
      }
      ApiResponse<Cm18SeataDemoVO> response =
          new ApiResponse<>(
              "CM18_SEATA_UNKNOWN",
              "Seata 协调器连接状态未知，请稍后查询",
              new Cm18SeataDemoVO(request.txId, "", "UNKNOWN"));
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .cacheControl(CacheControl.noStore()).body(response);
    }
  }

  /** 仅识别 Seata TM/RM 注册及 TC 网络连接故障，避免吞掉业务 FrameworkException。 */
  private boolean isSeataConnectivityFailure(FrameworkException exception) {
    if (exception.getErrcode() != null) {
      String errorCode = exception.getErrcode().name();
      if ("RegisterRM".equals(errorCode)
          || "NetConnect".equals(errorCode)
          || "NoAvailableService".equals(errorCode)
          || "TransactionManagerError".equals(errorCode)) {
        return true;
      }
    }
    Throwable current = exception;
    while (current != null) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("can not connect")
            || normalized.contains("cannot connect")
            || normalized.contains("connection refused")
            || normalized.contains("services-server")
            || normalized.contains("register rm")
            || normalized.contains("register tm")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
