package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Prepares the rolling physical tables used by the application's monthly order routing. */
@Component
@DependsOn("orderRoutingAudit")
public class OrderTableInitializer {
  /** 月表维护成功窗口及失败原因的日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderTableInitializer.class);

  /** 物理月表的固定年月后缀格式。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 月表月份必须按数据库使用的上海本地时间计算。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 返回上海时区上月到未来两个月的四个目标月，供审计和 DDL 共用。 */
  static List<String> rollingMonths(YearMonth currentMonth) {
    List<String> months = new ArrayList<>();
    YearMonth firstMonth = currentMonth.minusMonths(1);
    for (int monthOffset = 0; monthOffset < 4; monthOffset++)
      months.add(firstMonth.plusMonths(monthOffset).format(MONTH));
    return List.copyOf(months);
  }

  /** 负责模板选择和物理月表 DDL 的持久化入口。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 创建 OrderTableInitializer 实例。 */
  public OrderTableInitializer(OrderSqlMapper orderSqlMapper) {
    this.orderSqlMapper = orderSqlMapper;
  }

  /** 服务启动时准备当前窗口内的订单、明细和状态日志月表。 */
  @PostConstruct
  public void prepareRollingTables() {
    prepareForMonth(YearMonth.now(BUSINESS_ZONE));
  }

  /** 长运行实例定时续建未来月表；失败保留错误日志供排查。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.order.table-maintenance-ms:86400000}",
      initialDelayString = "${cloudmall.order.table-maintenance-ms:86400000}")
  public void prepareFutureTables() {
    // 1. 读取上海时区当前月份，避免服务器时区不同导致提前或延后建表。
    YearMonth currentMonth = YearMonth.now(BUSINESS_ZONE);
    // 2. 若 DDL 失败则记录并保留异常，本轮不假称目标月表已经就绪。
    try {
      prepareForMonth(currentMonth);
    } catch (RuntimeException preparationFailure) {
      log.error("订单未来月表准备失败，currentMonth={}", currentMonth, preparationFailure);
      throw preparationFailure;
    }
  }

  /** 用显式月份准备滚动窗口，供定时任务与回归测试复用。 */
  void prepareForMonth(YearMonth currentMonth) {
    // 1. 准备订单明细与状态日志模板。
    orderSqlMapper.ensureMonthlyCompanionTemplates();
    // 2. 同时准备上月、本月和未来两个月的三张物理月表。
    for (String suffix : rollingMonths(currentMonth)) {
      orderSqlMapper.createMonthlyOrderTables(suffix);
    }
    log.info(
        "订单月度分表已准备，覆盖 {} 至 {}",
        rollingMonths(currentMonth).get(0),
        rollingMonths(currentMonth).get(3));
  }
}
