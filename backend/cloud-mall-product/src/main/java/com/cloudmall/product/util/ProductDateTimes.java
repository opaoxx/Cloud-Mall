package com.cloudmall.product.util;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 兼容 MyBatis 与 Spring JDBC 对 DATETIME(3) 的两种 Java 映射。 */
public final class ProductDateTimes {
  /** 工具类禁止实例化。 */
  private ProductDateTimes() {}

  /** 将 MySQL DATETIME(3) 读取值统一成无时区的业务本地时间。 */
  public static LocalDateTime asLocalDateTime(Object databaseValue) {
    if (databaseValue instanceof LocalDateTime localDateTime) return localDateTime;
    if (databaseValue instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    throw new IllegalArgumentException("商品更新时间不是有效的 MySQL DATETIME 值");
  }
}
