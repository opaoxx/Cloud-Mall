package com.cloudmall.stock.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.SeckillAcceptanceVO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.service.SeckillReservationService;
import com.cloudmall.stock.service.StockQueryService;
import com.cloudmall.stock.service.StockReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存 HTTP 接口层，负责暴露内部库存调用契约。 */
@RestController
@RequestMapping("/api/stock")
public class StockController {
  /** 数量投影与持久预扣状态查询。 */
  private final StockQueryService queryService;

  /** 普通预扣及订单最终库存动作。 */
  private final StockReservationService reservationService;

  /** 秒杀幂等认领与配额受理。 */
  private final SeckillReservationService seckillService;

  /** 创建只负责HTTP绑定、身份读取和业务委托的库存入口。 */
  public StockController(
      StockQueryService queryService,
      StockReservationService reservationService,
      SeckillReservationService seckillService) {
    this.queryService = queryService;
    this.reservationService = reservationService;
    this.seckillService = seckillService;
  }

  /** 查询SKU可用库存。 */
  @GetMapping("/skus/{skuId}")
  public ApiResponse<StockQuantityVO> get(@PathVariable("skuId") Long skuId) {
    return ApiResponse.ok(queryService.get(skuId));
  }

  /** 按原订单号预扣普通库存。 */
  @PostMapping("/reservations")
  public ApiResponse<StockReservationVO> reserve(@RequestBody StockReservationDTO request) {
    return ApiResponse.ok(reservationService.reserve(request));
  }

  /** 确认原订单的普通库存或秒杀配额。 */
  @PostMapping("/reservations/{orderNo}/confirm")
  public ApiResponse<StockActionResultVO> confirm(
      @PathVariable("orderNo") String orderNo,
      @RequestBody(required = false) StockActionDTO action) {
    return ApiResponse.ok(reservationService.confirm(orderNo, action));
  }

  /** 释放原订单的普通库存或秒杀配额。 */
  @PostMapping("/reservations/{orderNo}/rollback")
  public ApiResponse<StockActionResultVO> rollback(
      @PathVariable("orderNo") String orderNo,
      @RequestBody(required = false) StockActionDTO action) {
    return ApiResponse.ok(reservationService.rollback(orderNo, action));
  }

  /** 查询远程写响应未知时可用于恢复的持久预扣事实。 */
  @GetMapping("/reservations/{orderNo}")
  public ApiResponse<StockReservationVO> reservation(@PathVariable("orderNo") String orderNo) {
    return ApiResponse.ok(queryService.reservation(orderNo));
  }

  /** 在与原实现相同的首个业务检查点读取身份，再委托秒杀受理。 */
  @PostMapping("/seckill/reservations")
  public ApiResponse<SeckillAcceptanceVO> seckill(@RequestBody SeckillReservationDTO request) {
    return ApiResponse.ok(seckillService.seckill(request, AuthContext.requireUserId()));
  }
}
