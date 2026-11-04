package com.cloudmall.order.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跨月订单列表的HTTP绑定、身份读取和响应装配。 */
@RestController
@RequestMapping("/api")
public class OrderQueryController {
  /** 负责完整列表快照和分页规则的查询服务。 */
  private final OrderQueryService orderQueryService;

  /** 注入独立的订单查询服务代理。 */
  public OrderQueryController(OrderQueryService orderQueryService) {
    this.orderQueryService = orderQueryService;
  }

  /**
   * 查询当前用户的订单列表，服务事务成功返回后包装HTTP响应。
   *
   * @param status 可选订单状态
   * @param page 请求页码，HTTP缺省为1
   * @param pageSize 请求页大小，HTTP缺省为20
   * @param startTime 可选查询起点
   * @param endTime 可选查询终点
   * @return 原统一响应与分页结构
   */
  @GetMapping("/orders")
  public ApiResponse<PageResult<OrderVO>> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String startTime,
      @RequestParam(required = false) String endTime) {
    return ApiResponse.ok(
        orderQueryService.list(AuthContext.userId(), status, page, pageSize, startTime, endTime));
  }
}
