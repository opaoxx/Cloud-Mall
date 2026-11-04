package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.dto.OrderQueryWindow;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.OrderQueryService;
import com.cloudmall.order.util.OrderSummaryAssembler;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一只读快照内按月计数、跳页并读取订单摘要。 */
@Service
public class OrderQueryServiceImpl implements OrderQueryService {
  /** 缺失月表、读取失败和错月数据的诊断日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderQueryServiceImpl.class);

  /** 与既有物理月表名称一致的年月格式。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 由现有路由配置选择的物理或Sharding订单访问实现。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 注入现有订单持久层，列表不依赖远程服务或消息资源。 */
  public OrderQueryServiceImpl(OrderSqlMapper orderSqlMapper) {
    this.orderSqlMapper = orderSqlMapper;
  }

  /** 按上海时间路由月表，在同一数据库快照内计数和读取有界结果页。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  @Override
  public PageResult<OrderVO> list(
      Long callerUserId, String status, int page, int pageSize, String startTime, String endTime) {
    // 1. 在原事务开启后的首个业务步骤核对身份，再规范化时间和分页参数。
    if (callerUserId == null) throw new BizException(ErrorCodes.UNAUTHORIZED, "请先登录", 401);
    long userId = callerUserId;
    page = Math.max(1, page);
    pageSize = Math.min(Math.max(1, pageSize), 100);
    OrderQueryWindow window =
        OrderQueryWindow.from(startTime, endTime, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    if (window.isEmpty()) {
      return new PageResult<OrderVO>(List.of(), page, pageSize, 0);
    }
    long offset;
    try {
      offset = Math.multiplyExact((long) page - 1L, (long) pageSize);
    } catch (ArithmeticException invalidPage) {
      throw new BizException(ErrorCodes.INVALID, "页码超出支持范围", 400);
    }
    java.sql.Timestamp rangeStart = java.sql.Timestamp.from(window.getStart().toInstant());
    java.sql.Timestamp rangeEnd = java.sql.Timestamp.from(window.getEnd().toInstant());
    YearMonth firstMonth = window.getFirstMonth();
    YearMonth lastMonth = window.getLastMonth();

    // 2. 逐月计数；任何一个月的读取失败都会让整次查询失败，不返回部分结果。
    Map<YearMonth, Long> monthlyCounts = new LinkedHashMap<>();
    long total = 0;
    for (YearMonth month = lastMonth; !month.isBefore(firstMonth); month = month.minusMonths(1)) {
      String tableName = "mall_order_" + month.format(MONTH);
      try {
        long monthCount =
            orderSqlMapper.countOrders(tableName, userId, rangeStart, rangeEnd, status);
        monthlyCounts.put(month, monthCount);
        total = Math.addExact(total, monthCount);
      } catch (DataAccessException | ArithmeticException countFailure) {
        throw unavailableOrderMonth(month, countFailure);
      }
    }

    // 3. 按月倒序跳过整月，只从目标页涉及的月表读取有界 LIMIT/OFFSET。
    List<OrderVO> items = new ArrayList<>(pageSize);
    if (offset < total) {
      long remainingOffset = offset;
      for (Map.Entry<YearMonth, Long> monthCount : monthlyCounts.entrySet()) {
        if (remainingOffset >= monthCount.getValue()) {
          remainingOffset -= monthCount.getValue();
          continue;
        }
        YearMonth month = monthCount.getKey();
        String tableName = "mall_order_" + month.format(MONTH);
        int limit =
            (int) Math.min((long) pageSize - items.size(), monthCount.getValue() - remainingOffset);
        List<Map<String, Object>> rows;
        try {
          rows =
              orderSqlMapper.findOrderPage(
                  tableName, userId, rangeStart, rangeEnd, status, remainingOffset, limit);
        } catch (DataAccessException pageFailure) {
          throw unavailableOrderMonth(month, pageFailure);
        }
        if (rows.size() != limit) {
          throw unavailableOrderMonth(
              month,
              new IllegalStateException("月表页结果数量与同一快照内的 COUNT 不一致: " + rows.size() + "/" + limit));
        }
        for (Map<String, Object> row : rows) {
          requireCorrectOrderMonth(row, month);
          items.add(OrderSummaryAssembler.fromRow(row));
        }
        remainingOffset = 0;
        if (items.size() == pageSize) break;
      }
    }
    return new PageResult<>(items, page, pageSize, total);
  }

  /** 月表缺失、查询失败或分片数据异常时只返回明确失败。 */
  private static BizException unavailableOrderMonth(YearMonth month, RuntimeException failure) {
    log.error("订单月表查询失败，month={}", month, failure);
    return new BizException("ORDER_QUERY_SHARD_UNAVAILABLE", "订单列表暂不可用，请稍后重试", 503);
  }

  /** 发现目标页中的错月订单时拒绝按月份拼接出错误顺序。 */
  private static void requireCorrectOrderMonth(Map<String, Object> row, YearMonth month) {
    Object createdAt = row.get("created_at");
    LocalDateTime createdLocal =
        createdAt instanceof java.sql.Timestamp timestamp
            ? timestamp.toLocalDateTime()
            : createdAt instanceof LocalDateTime localDateTime ? localDateTime : null;
    if (createdLocal == null || !YearMonth.from(createdLocal).equals(month)) {
      throw unavailableOrderMonth(month, new IllegalStateException("订单行的 created_at 不属于所在月表"));
    }
  }
}
