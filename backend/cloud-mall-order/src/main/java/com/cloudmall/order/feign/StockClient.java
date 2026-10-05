package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.order.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-stock")
public interface StockClient {
  /** 调用 Stock 的 CM-18 独立演练分支并传播 Seata XID。 */
  @PostMapping("/api/internal/cm18/seata/demo/branch")
  ApiResponse<Cm18SeataBranchVO> cm18SeataBranch(
      @RequestBody Cm18SeataBranchDTO request,
      @RequestHeader("TX_XID") String xid,
      @RequestHeader("X-CM18-Failure-Point") String failurePoint,
      @RequestHeader("X-Internal-Service") String internalService);

  /** 按原订单号读取库存预扣的持久化结果，不改变数量。 */
  @GetMapping("/api/stock/reservations/{orderNo}")
  ApiResponse<StockReservationStatusVO> reservation(@PathVariable("orderNo") String orderNo);

  @PostMapping("/api/stock/reservations")
  ApiResponse<?> reserve(@RequestBody Reservation request);

  @PostMapping("/api/stock/reservations/{orderNo}/confirm")
  ApiResponse<?> confirm(
      @PathVariable("orderNo") String orderNo, @RequestBody(required = false) Action action);

  @PostMapping("/api/stock/reservations/{orderNo}/rollback")
  ApiResponse<?> rollback(
      @PathVariable("orderNo") String orderNo, @RequestBody(required = false) Action action);

  /** 兼容已有原订单号回滚调用。 */
  default ApiResponse<?> rollback(String orderNo) {
    return rollback(orderNo, new Action(orderNo + ":ROLLBACK"));
  }

  /** 兼容已有原订单号确认调用。 */
  default ApiResponse<?> confirm(String orderNo) {
    return confirm(orderNo, new Action(orderNo + ":CONFIRM"));
  }

  @PostMapping("/api/stock/seckill/reservations")
  ApiResponse<?> seckill(@RequestBody Map<String, Object> request);

  /** 只读核对秒杀受理台账，供订单死信恢复确认库存事实。 */
  @GetMapping("/api/internal/stock/seckill/reservations/{orderNo}")
  ApiResponse<Map<String, Object>> seckillReservationFact(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader("X-Internal-Service") String internalService);

  record Reservation(String orderNo, List<Line> items, String scene) {}

  record Line(Long skuId, int quantity) {}

  /** 订单状态事务提交后使用的稳定库存动作键。 */
  record Action(String operationKey) {}
}
