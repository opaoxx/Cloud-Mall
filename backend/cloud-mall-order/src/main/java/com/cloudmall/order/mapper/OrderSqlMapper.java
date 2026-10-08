package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 订单动态分片和兼容性 SQL 的统一持久化入口。 */
@Repository
public class OrderSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建订单 SQL Mapper。 */
  public OrderSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行订单查询。 */
  private <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 使用参数数组执行订单查询。 */
  private <T> List<T> query(String sql, Object[] arguments, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询订单单值。 */
  private <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 查询订单行集合。 */
  private List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 执行订单写操作。 */
  private int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 优先复用正式明细/状态日志模板，仅在缺失时从固定历史来源兼容引导。 */
  public void ensureMonthlyCompanionTemplates() {
    // 1. 正式模板存在时不再解析可能已归档的 legacy LIKE 源表。
    ensureTemplate("mall_order_item_template", "mall_order_item_202608");
    // 2. 两类模板独立选择来源；任何失败都向上传播，阻止后续月表维护。
    ensureTemplate("order_status_log_template", "order_status_log_202608");
  }

  /**
   * 使用固定三类模板幂等创建指定月份的物理表，不更改已有表结构或数据。
   *
   * @param suffix 服务端计算的 yyyyMM，年份1000..9999、月份01..12
   * @throws IllegalArgumentException 月份非法，执行任何 SQL 前拒绝
   */
  public void createMonthlyOrderTables(String suffix) {
    // 1. 表名只接受完整合法月份，不能把任意后缀插入 DDL。
    if (suffix == null || !suffix.matches("[1-9][0-9]{3}(0[1-9]|1[0-2])")) {
      throw new IllegalArgumentException("订单建表月份无效");
    }
    // 2. 主表、明细和状态日志共同沿用已准备的正式模板。
    createLike("mall_order_" + suffix, "mall_order_template");
    createLike("mall_order_item_" + suffix, "mall_order_item_template");
    createLike("order_status_log_" + suffix, "order_status_log_template");
  }

  /** 判断模板优先级，缺少所有受支持来源时明确失败，不猜测其他业务月份。 */
  private void ensureTemplate(String template, String legacySource) {
    if (baseTableExists(template)) return;
    if (!baseTableExists(legacySource)) {
      throw new DataAccessResourceFailureException("ORDER_TEMPLATE_UNAVAILABLE: " + template);
    }
    createLike(template, legacySource);
  }

  /** 元数据读取失败不能当作缺表；视图等同名对象也不能当作可克隆模板。 */
  private boolean baseTableExists(String table) {
    List<String> types =
        jdbcTemplate.queryForList(
            "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
            String.class,
            table);
    if (types.isEmpty()) return false;
    if (types.size() != 1 || !"BASE TABLE".equals(types.get(0))) {
      throw new DataAccessResourceFailureException("ORDER_TEMPLATE_UNAVAILABLE: " + table);
    }
    return true;
  }

  /** 参数仅由固定模板名和已校验月份构成；并发初始化依赖 IF NOT EXISTS 幂等。 */
  private void createLike(String table, String template) {
    jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table + " LIKE " + template);
  }

  /**
   * 兼容读取数据库排序规则命中的候选订单号，普通创建使用完整身份查询。
   *
   * @param userId 请求用户主键
   * @param idempotencyKey 原始请求键
   * @return 候选订单号，业务入口仍需核验持久化原键
   */
  public List<String> findIdempotentOrderNos(Long userId, String idempotencyKey) {
    return findIdempotentOrders(userId, idempotencyKey).stream()
        .map(existingOrder -> existingOrder.orderNo)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * 读取数据库排序规则命中的原幂等候选，由Service精确核验原始键。
   *
   * @param userId 请求用户主键
   * @param idempotencyKey 原始请求键，不做大小写或空白归一化
   * @return 持久幂等事实；不存在时为空列表
   */
  public List<OrderIdempotencyPO> findIdempotentOrders(Long userId, String idempotencyKey) {
    return query(
        "select id,user_id,idempotency_key,order_no,created_at from order_idempotency"
            + " where user_id=? and idempotency_key=?",
        (resultSet, rowNumber) -> {
          OrderIdempotencyPO existingOrder = new OrderIdempotencyPO();
          existingOrder.id = resultSet.getLong("id");
          existingOrder.userId = resultSet.getLong("user_id");
          existingOrder.idempotencyKey = resultSet.getString("idempotency_key");
          existingOrder.orderNo = resultSet.getString("order_no");
          existingOrder.createdAt =
              resultSet.getObject("created_at", java.time.LocalDateTime.class);
          return existingOrder;
        },
        userId,
        idempotencyKey);
  }

  /** 保存订单主表记录。 */
  public int insertOrder(String tableName, OrderPO order) {
    return update(
        "insert into "
            + tableName
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?, ?,?,?)",
        order.id,
        order.orderNo,
        order.userId,
        order.status,
        order.totalAmount,
        order.payAmount,
        order.addressSnapshot,
        order.expireAt,
        order.createdAt,
        order.updatedAt);
  }

  /** 按订单号读取订单内部主键。 */
  public Long findOrderId(String tableName, String orderNo) {
    return queryForObject("select id from " + tableName + " where order_no=?", Long.class, orderNo);
  }

  /**
   * 单次读取原订单号候选，区分精确已提交、真正缺行和不可信身份。
   *
   * @param tableName 调用方根据原订单号计算的物理月表
   * @param orderNo 原订单号，不裁剪或归一化
   * @return 精确唯一候选为true，真正无候选为false
   * @throws IllegalStateException 候选不唯一或原始订单号不精确匹配
   */
  public boolean orderExists(String tableName, String orderNo) {
    // 1. 保留collation索引候选，不把别名过滤成缺行并触发库存补偿。
    List<Map<String, Object>> candidates =
        queryForList("select order_no from " + tableName + " where order_no=?", orderNo);
    if (candidates.isEmpty()) return false;
    // 2. 只允许唯一且原值精确匹配的订单证明已提交。
    if (candidates.size() != 1
        || !(candidates.get(0).get("order_no") instanceof String)
        || !Objects.equals(orderNo, candidates.get(0).get("order_no"))) {
      throw new IllegalStateException("订单存在性候选身份与原意图不一致");
    }
    // 3. 未知候选抛异常走原失败退避；只有可信候选返回true。
    return true;
  }

  /** 保存订单明细快照。 */
  public int insertOrderItem(String tableName, OrderItemPO item) {
    return update(
        "insert into "
            + tableName
            + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?)",
        item.id,
        item.orderId,
        item.orderNo,
        item.productId,
        item.skuId,
        item.productNameSnapshot,
        item.skuSnapshot,
        item.unitPrice,
        item.quantity,
        item.lineAmount,
        item.createdAt);
  }

  /** 保存订单创建幂等记录。 */
  public int insertIdempotency(OrderIdempotencyPO idempotency) {
    return update(
        "insert into order_idempotency(id,user_id,idempotency_key,order_no,created_at)"
            + " values(?,?,?,?,?)",
        idempotency.id,
        idempotency.userId,
        idempotency.idempotencyKey,
        idempotency.orderNo,
        idempotency.createdAt);
  }

  /** 统计单个月表中符合用户、半开时间范围和状态的订单。 */
  public long countOrders(
      String tableName, long userId, Object startTime, Object endTime, String status) {
    requireMonthlyOrderTable(tableName);
    String condition = " where user_id=? and created_at>=? and created_at<?";
    List<Object> arguments = new java.util.ArrayList<>(List.of(userId, startTime, endTime));
    if (status != null) {
      condition += " and status=?";
      arguments.add(status);
    }
    Long count = queryForObject(
        "select count(*) from " + tableName + condition,
        Long.class,
        arguments.toArray());
    if (count == null) throw new IllegalStateException("订单月表计数结果为空: " + tableName);
    return count;
  }

  /** 仅从一个月表读取目标页所需的有界订单摘要。 */
  public List<Map<String, Object>> findOrderPage(
      String tableName,
      long userId,
      Object startTime,
      Object endTime,
      String status,
      long offset,
      int limit) {
    requireMonthlyOrderTable(tableName);
    if (offset < 0 || limit <= 0) throw new IllegalArgumentException("订单分页参数无效");
    String condition = " where user_id=? and created_at>=? and created_at<?";
    List<Object> arguments = new java.util.ArrayList<>(List.of(userId, startTime, endTime));
    if (status != null) {
      condition += " and status=?";
      arguments.add(status);
    }
    arguments.add(limit);
    arguments.add(offset);
    return queryForList(
        "select"
            + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
            + " from "
            + tableName
            + condition
            + " order by created_at desc,order_no desc limit ? offset ?",
        arguments.toArray());
  }

  /** 动态表名只能由服务端生成的 yyyyMM 月份组成。 */
  private static void requireMonthlyOrderTable(String tableName) {
    if (tableName == null || !tableName.matches("mall_order_\\d{6}"))
      throw new IllegalArgumentException("订单月表名无效");
  }

  /** 按订单号查询订单主表。 */
  public List<Map<String, Object>> findOrder(String tableName, String orderNo) {
    return queryForList(
        "select"
            + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
            + " from "
            + tableName
            + " where order_no=?",
        orderNo);
  }

  /** 查询订单明细快照。 */
  public List<Map<String, Object>> findOrderItems(String tableName, String orderNo) {
    return queryForList(
        "select"
            + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
            + " from "
            + tableName
            + " where order_no=? order by id",
        orderNo);
  }

  /** 按当前状态条件迁移订单状态。 */
  public int updateStatus(
      String tableName,
      String orderNo,
      String targetStatus,
      String currentStatus,
      Object updatedAt) {
    return update(
        "update "
            + tableName
            + " set status=?,updated_at=?,paid_at=case when ?='PAID' then ? else paid_at"
            + " end,cancelled_at=case when ?='CANCELLED' then ? else cancelled_at end where"
            + " order_no=? and status=?",
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        orderNo,
        currentStatus);
  }
}
