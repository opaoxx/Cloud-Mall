package com.cloudmall.product.service.impl;

import static com.cloudmall.product.util.ProductDateTimes.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.ActivityDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.po.SeckillActivityPO;
import com.cloudmall.product.domain.vo.ActivityVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.SeckillActivityService;
import com.cloudmall.product.util.ProductAuthorization;
import com.cloudmall.product.util.ProductIdentifiers;
import com.cloudmall.product.util.SeckillActivityStock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 管理秒杀活动事实及原Redis配额发布流程。 */
@Service
public class SeckillActivityServiceImpl implements SeckillActivityService {

  /** 活动事实查询与写入Mapper。 */
  private final ProductSqlMapper productSqlMapper;

  /** 活动配额与元数据存储客户端。 */
  private final StringRedisTemplate redis;

  /** 注入原活动数据库与Redis依赖。 */
  public SeckillActivityServiceImpl(ProductSqlMapper productSqlMapper, StringRedisTemplate redis) {
    this.productSqlMapper = productSqlMapper;
    this.redis = redis;
  }

  /** 读取活动事实并返回Redis当前剩余库存。 */
  @Override
  public ActivityVO activity(Long activityId) {
    return toActivityResponse(activityRecord(activityId));
  }

  /** 验证管理员和参数后创建活动，保持原数据库与Redis调用顺序。 */
  @Override
  public ActivityVO createActivity(ProductOperatorDTO operator, ActivityDTO request) {
    // 1. 校验原管理员身份及活动参数。
    ProductAuthorization.requireAdmin(operator);
    validateActivity(request);
    long activityId = ProductIdentifiers.nextId();
    // 2. 按原独立写入方式创建活动。
    productSqlMapper.insertActivity(activityId, request, toTimestamp(now()));
    // 3. 读取已保存活动及Redis配额作为响应。
    return activity(activityId);
  }

  /** 验证已有活动并更新可变字段，保留原部分成功语义。 */
  @Override
  public ActivityVO updateActivity(
      ProductOperatorDTO operator, Long activityId, ActivityDTO request) {
    // 1. 保留管理员、请求及原活动读取的先后顺序。
    ProductAuthorization.requireAdmin(operator);
    validateActivity(request);
    activity(activityId);
    // 2. 更新数据库事实。
    productSqlMapper.updateActivity(activityId, request, toTimestamp(now()));
    // 3. 重新读取活动和Redis配额，后续失败保留原已完成写入。
    return activity(activityId);
  }

  /** 按原顺序发布活动状态、配额和Redis元数据。 */
  @Override
  public ActivityVO publishActivity(ProductOperatorDTO operator, Long activityId) {
    // 1. 核验管理员并读取原活动。
    ProductAuthorization.requireAdmin(operator);
    SeckillActivityPO snapshot = activityRecord(activityId);
    // 2. 原数据库状态写入先于Redis操作，按同一顺序发布配额及元数据。
    productSqlMapper.updateActivityStatus(activityId, "PUBLISHED", toTimestamp(now()));
    String suffix = activityId + ":" + snapshot.skuId;
    Duration ttl = Duration.ofSeconds(Math.max(1, secondsUntil(snapshot.endAt)));
    redis.opsForValue().set("seckill:stock:" + suffix, String.valueOf(snapshot.stockLimit), ttl);
    redis
        .opsForValue()
        .set("seckill:product:" + suffix, String.valueOf(toActivityResponse(snapshot)), ttl);
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "perUserLimit", String.valueOf(snapshot.perUserLimit));
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "stockLimit", String.valueOf(snapshot.stockLimit));
    redis
        .opsForHash()
        .put(
            "seckill:meta:" + suffix, "startAt", toOffset(snapshot.startAt).toInstant().toString());
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "endAt", toOffset(snapshot.endAt).toInstant().toString());
    redis.expire("seckill:meta:" + suffix, ttl);
    // 3. 返回重新读取的活动事实。
    return activity(activityId);
  }

  /** 将既有活动状态更新为STARTED后读取返回事实。 */
  @Override
  public ActivityVO startActivity(ProductOperatorDTO operator, Long activityId) {
    // 1. 校验管理员和已有活动。
    ProductAuthorization.requireAdmin(operator);
    activityRecord(activityId);
    // 2. 更新状态后读取当前活动与配额。
    productSqlMapper.updateActivityStatus(activityId, "STARTED", toTimestamp(now()));
    return activity(activityId);
  }

  /** 按原时间和库存规则组装活动响应。 */
  private ActivityVO toActivityResponse(SeckillActivityPO snapshot) {
    String stock = redis.opsForValue().get("seckill:stock:" + snapshot.id + ":" + snapshot.skuId);
    return new ActivityVO(
        snapshot.id,
        snapshot.skuId,
        toOffset(snapshot.startAt),
        toOffset(snapshot.endAt),
        SeckillActivityStock.remainingStock(
            stock, snapshot.status, toOffset(snapshot.endAt), now()),
        snapshot.perUserLimit,
        snapshot.status);
  }

  /** 将原JDBC时间投影转换为Timestamp。 */
  private static java.sql.Timestamp toTimestamp(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp;
    if (value instanceof java.time.LocalDateTime localDateTime)
      return java.sql.Timestamp.valueOf(localDateTime);
    return java.sql.Timestamp.valueOf(String.valueOf(value).replace('T', ' '));
  }

  /** 校验原活动时间、库存和限购参数。 */
  private static void validateActivity(ActivityDTO request) {
    if (request == null
        || request.skuId == null
        || request.startAt == null
        || request.endAt == null
        || !request.endAt.isAfter(request.startAt)
        || request.stockLimit < 1
        || request.perUserLimit < 1) throw new BizException(ErrorCodes.INVALID, "秒杀活动参数无效", 400);
  }

  /** 将完整活动行映射到已有PO，缺失活动时保留404语义。 */
  private SeckillActivityPO activityRecord(Long activityId) {
    List<SeckillActivityPO> records =
        productSqlMapper.findActivity(activityId).stream()
            .map(
                row -> {
                  SeckillActivityPO snapshot = new SeckillActivityPO();
                  snapshot.id = ((Number) row.get("id")).longValue();
                  snapshot.skuId = ((Number) row.get("sku_id")).longValue();
                  snapshot.startAt = toTimestamp(row.get("start_at")).toLocalDateTime();
                  snapshot.endAt = toTimestamp(row.get("end_at")).toLocalDateTime();
                  snapshot.stockLimit = ((Number) row.get("stock_limit")).intValue();
                  snapshot.perUserLimit = ((Number) row.get("per_user_limit")).intValue();
                  snapshot.status = String.valueOf(row.get("status"));
                  return snapshot;
                })
            .toList();
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "秒杀活动不存在", 404);
    return records.get(0);
  }

  /** 按原Timestamp转换方式输出东八区活动时间。 */
  private static OffsetDateTime toOffset(LocalDateTime value) {
    return java.sql.Timestamp.valueOf(value).toInstant().atOffset(ZoneOffset.ofHours(8));
  }

  /** 保留活动Redis有效期的整秒与最少一秒规则。 */
  private static long secondsUntil(LocalDateTime endAt) {
    return Math.max(1, Duration.between(now(), toOffset(endAt)).getSeconds());
  }

  /** 按原写入方式将带偏移时间转换为Timestamp。 */
  private static java.sql.Timestamp toTimestamp(OffsetDateTime value) {
    return java.sql.Timestamp.from(value.toInstant());
  }
}
