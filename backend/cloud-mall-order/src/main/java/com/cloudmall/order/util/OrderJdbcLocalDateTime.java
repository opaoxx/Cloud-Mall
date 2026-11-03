package com.cloudmall.order.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅解析MySQL已CAST为字符的DATETIME(3)组件，不经时区、Timestamp或宽松历法。 */
public final class OrderJdbcLocalDateTime {
  /** 固定四位年份、完整秒及可选一至三位小数，不接受空白补正或offset。 */
  private static final Pattern COMPONENTS =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,3}))?");

  private OrderJdbcLocalDateTime() {}

  /**
   * 读取可空字符投影，NULL保持缺失，驱动SQLException同一对象传播。
   *
   * @param resultSet 当前真实结果集
   * @param columnAlias Mapper固定的cm_local列别名
   * @return 原本地日期时间组件，SQL NULL为null
   * @throws SQLException 非法组件为22007，驱动读取错误原样传播
   */
  public static LocalDateTime readNullable(ResultSet resultSet, String columnAlias)
      throws SQLException {
    // 1. 只读已投影文本，不调用不支持的typed getter或历史历法getter。
    String text = resultSet.getString(columnAlias);
    // 2. SQL NULL仅在nullable入口保持缺失。
    if (text == null) return null;
    // 3. 严格解析原组件，不修改源值或附加时区含义。
    return parse(text);
  }

  /**
   * 读取必填字符投影，SQL NULL与非法组件均明确拒绝。
   *
   * @param resultSet 当前真实结果集
   * @param columnAlias Mapper固定的cm_local列别名
   * @return 原本地日期时间组件
   * @throws SQLException NULL或非法组件为22007，驱动读取错误原样传播
   */
  public static LocalDateTime readRequired(ResultSet resultSet, String columnAlias)
      throws SQLException {
    // 1. 复用唯一文本读取路径，保留原驱动异常身份。
    LocalDateTime value = readNullable(resultSet, columnAlias);
    // 2. 必填NULL不能被误解为无候选或默认时间。
    if (value == null) throw invalid();
    // 3. 直接返回同一本地组件，不进行时区或精度转换。
    return value;
  }

  /** 小数右补到毫秒，LocalDateTime.of使用严格公历拒绝零日和不可能日期。 */
  private static LocalDateTime parse(String text) throws SQLException {
    Matcher match = COMPONENTS.matcher(text);
    if (!match.matches()) throw invalid();
    try {
      int year = Integer.parseInt(match.group(1));
      if (year < 1000) throw invalid();
      String fraction = match.group(7);
      int millis = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
      return LocalDateTime.of(
          year,
          Integer.parseInt(match.group(2)),
          Integer.parseInt(match.group(3)),
          Integer.parseInt(match.group(4)),
          Integer.parseInt(match.group(5)),
          Integer.parseInt(match.group(6)),
          millis * 1_000_000);
    } catch (DateTimeException invalidDate) {
      throw invalid();
    }
  }

  private static SQLException invalid() {
    return new SQLException("Invalid projected DATETIME(3) components", "22007");
  }
}
