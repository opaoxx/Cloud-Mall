package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.SeataStockDemoBranchDTO;
import com.cloudmall.order.domain.vo.SeataStockDemoBranchVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "cloud-mall-stock")
public interface StockClient {
  /** 调用 Stock 的 CM-18 独立演练分支并传播 Seata XID。 */
  @PostMapping("/api/internal/cm18/seata/demo/branch")
  ApiResponse<SeataStockDemoBranchVO> executeSeataDemoBranch(
      @RequestBody SeataStockDemoBranchDTO request,
      @RequestHeader("TX_XID") String xid,
      @RequestHeader("X-CM18-Failure-Point") String failurePoint,
      @RequestHeader("X-Internal-Service") String internalService);

  /** 按原订单号读取库存预扣的持久化结果，不改变数量。 */
  @GetMapping("/api/stock/reservations/{orderNo}")
  ApiResponse<StockReservationStatusVO> reservation(@PathVariable("orderNo") String orderNo);

  /**
   * 按原订单号、明细和场景请求预扣库存。
   *
   * @param request 原订单身份和SKU数量明细
   * @return 原库存响应信封，调用方按既有持久证明处理未知结果
   */
  @PostMapping("/api/stock/reservations")
  ApiResponse<?> reserve(@RequestBody Reservation request);

  /**
   * 按原订单号和操作键确认库存，保持可空请求体的兼容绑定。
   *
   * @param orderNo 原订单号
   * @param action 原确认操作键载荷，可为空
   * @return 原库存确认响应信封
   */
  @PostMapping("/api/stock/reservations/{orderNo}/confirm")
  ApiResponse<?> confirm(
      @PathVariable("orderNo") String orderNo, @RequestBody(required = false) Action action);

  /**
   * 按原订单号和操作键回滚库存，保持可空请求体的兼容绑定。
   *
   * @param orderNo 原订单号
   * @param action 原回滚操作键载荷，可为空
   * @return 原库存回滚响应信封
   */
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

  /**
   * 提交原秒杀受理请求；受理成功不代表异步订单已落库。
   *
   * @param request 原活动、SKU、用户、幂等键和地址快照载荷
   * @return Stock原受理信封，包含其业务码和结果字段
   */
  @PostMapping("/api/stock/seckill/reservations")
  ApiResponse<?> seckill(@RequestBody Map<String, Object> request);

  /** 只读核对秒杀受理台账，供订单死信恢复确认库存事实。 */
  @GetMapping("/api/internal/stock/seckill/reservations/{orderNo}")
  ApiResponse<Map<String, Object>> seckillReservationFact(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader("X-Internal-Service") String internalService);

  /**
   * 原库存预扣请求的客户端局部传输模型。
   *
   * @param orderNo 原订单号
   * @param items 原SKU及数量明细
   * @param scene 原库存预扣场景标识
   */
  record Reservation(String orderNo, List<Line> items, String scene) {}

  /**
   * 单个SKU的原预扣数量，不在传输层归一化。
   *
   * @param skuId 原SKU身份
   * @param quantity 原请求数量
   */
  record Line(Long skuId, int quantity) {}

  /**
   * 订单状态事务提交后使用的稳定库存动作键。
   *
   * @param operationKey 原订单号和动作方向绑定的幂等操作键
   */
  record Action(String operationKey) {}
}
