package com.cloudmall.stock.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.stock.service.SeckillReservationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订单服务使用的秒杀受理事实内部查询入口。 */
@RestController
@RequestMapping("/api/internal/stock")
public class SeckillReservationQueryController {
  private final SeckillReservationQueryService queryService;

  /** 创建内部秒杀受理查询控制器。 */
  public SeckillReservationQueryController(SeckillReservationQueryService queryService) {
    this.queryService = queryService;
  }

  /** 只允许订单服务读取库存侧不可变受理事实。 */
  @GetMapping("/seckill/reservations/{orderNo}")
  public ApiResponse<?> find(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-Internal-Service", required = false) String internalService) {
    if (!"cloud-mall-order".equals(internalService)) {
      throw new BizException(ErrorCodes.FORBIDDEN, "内部服务标识无效", 403);
    }
    return ApiResponse.ok(queryService.find(orderNo));
  }
}
