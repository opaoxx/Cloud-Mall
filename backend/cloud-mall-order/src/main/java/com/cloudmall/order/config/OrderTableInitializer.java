package com.cloudmall.order.config;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Prepares the rolling physical tables used by the application's monthly order routing. */
@Component
public class OrderTableInitializer {
  private static final Logger log = LoggerFactory.getLogger(OrderTableInitializer.class);
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
  private final JdbcTemplate db;

  public OrderTableInitializer(JdbcTemplate db) {
    this.db = db;
  }

  @PostConstruct
  public void prepareRollingTables() {
    ensureTemplate("mall_order_item_template", "mall_order_item_202608");
    ensureTemplate("order_status_log_template", "order_status_log_202608");
    YearMonth month = YearMonth.now().minusMonths(1);
    for (int i = 0; i < 4; i++) {
      String suffix = month.plusMonths(i).format(MONTH);
      createLike("mall_order_" + suffix, "mall_order_template");
      createLike("mall_order_item_" + suffix, "mall_order_item_template");
      createLike("order_status_log_" + suffix, "order_status_log_template");
    }
    log.info("订单月度分表已准备，覆盖 {} 至 {}", month, month.plusMonths(3));
  }

  private void ensureTemplate(String template, String existingTable) {
    createLike(template, existingTable);
  }

  private void createLike(String table, String template) {
    db.execute("CREATE TABLE IF NOT EXISTS " + table + " LIKE " + template);
  }
}
