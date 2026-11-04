package com.cloudmall.order.service;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.order.domain.vo.OrderVO;

/** 当前用户的跨月订单列表查询。 */
public interface OrderQueryService {
  /**
   * 在同一只读快照中统计命中月份并读取目标页，不加载订单明细。
   *
   * @param callerUserId Controller读取的可空身份，在原事务内首步校验
   * @param status 可选状态，按原值传递给Mapper
   * @param page 请求页码，最小规范化为1
   * @param pageSize 每页数量，规范化到1至100
   * @param startTime 可选ISO-8601左闭起点
   * @param endTime 可选ISO-8601右开终点
   * @return 保留精确总数、稳定排序及原字段语义的分页结果
   */
  PageResult<OrderVO> list(
      Long callerUserId, String status, int page, int pageSize, String startTime, String endTime);
}
