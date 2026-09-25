package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Prepares the rolling physical tables used by the application's monthly order routing. */
@Component
public class OrderTableInitializer {
  /** 执行 getLogger 相关操作。 */
  private static final Logger log = LoggerFactory.getLogger(OrderTableInitializer.class);

  /** 执行 ofPattern 相关操作。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 月表月份必须按数据库使用的上海本地时间计算。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 保存 db 的业务状态或配置。 */
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
    ensureTemplate("mall_order_item_template", "mall_order_item_202608");
    ensureTemplate("order_status_log_template", "order_status_log_202608");
    // 2. 同时准备上月、本月和未来两个月的三张物理月表。
    YearMonth firstMonth = currentMonth.minusMonths(1);
    for (int monthOffset = 0; monthOffset < 4; monthOffset++) {
      String suffix = firstMonth.plusMonths(monthOffset).format(MONTH);
      createLike("mall_order_" + suffix, "mall_order_template");
      createLike("mall_order_item_" + suffix, "mall_order_item_template");
      createLike("order_status_log_" + suffix, "order_status_log_template");
    }
    log.info("订单月度分表已准备，覆盖 {} 至 {}", firstMonth, firstMonth.plusMonths(3));
  }

  /** 执行 ensureTemplate 相关操作。 */
  private void ensureTemplate(String template, String existingTable) {
    createLike(template, existingTable);
  }

  /** 执行 createLike 相关操作。 */
  private void createLike(String table, String template) {
    orderSqlMapper.execute("CREATE TABLE IF NOT EXISTS " + table + " LIKE " + template);
  }
}
