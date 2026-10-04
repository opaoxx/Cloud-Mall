package com.cloudmall.order.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.micrometer.core.instrument.Metrics;
import java.util.Set;
import java.util.function.Supplier;

/** 统一保护 Order 发往 Stock 的 Feign 调用，并区分业务拒绝与系统故障。 */
public final class OrderStockCircuitBreaker {
  /** 普通库存预扣及其原订单号恢复调用。 */
  public static final String RESERVE = "order-stock-reserve";
  /** 秒杀库存受理调用。 */
  public static final String SECKILL = "order-stock-seckill";
  /** 普通/秒杀库存持久事实查询。 */
  public static final String STATUS = "order-stock-status";
  /** 已提交订单状态后的库存确认或回滚。 */
  public static final String ACTION = "order-stock-action";

  /** 仅允许已冻结的四个资源进入统一包装。 */
  private static final Set<String> RESOURCES = Set.of(RESERVE, SECKILL, STATUS, ACTION);
  /** 解析 Feign 业务错误码，不反序列化库存业务 DTO。 */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 工具类禁止实例化。 */
  private OrderStockCircuitBreaker() {}

  /** 在 Sentinel 资源内执行一次 Feign 请求，绝不自动重试写调用。 */
  public static <T> ApiResponse<T> call(String resource, Supplier<ApiResponse<T>> remoteCall) {
    if (!RESOURCES.contains(resource)) throw new IllegalArgumentException("未知 Stock 依赖资源");
    // 1. 本地阻断发生在 Feign 前；调用方必须按自身持久意图处理未知结果。
    Entry entry;
    try {
      entry = SphU.entry(resource, EntryType.OUT);
    } catch (BlockException blocked) {
      Metrics.counter("sentinel.degrade.block", "service", "order", "resource", resource,
          "status", "503").increment();
      throw new StockCircuitOpenException(resource, blocked);
    }
    try {
      // 2. Sentinel 自行统计 Feign 耗时；仅系统失败进入异常比例统计。
      ApiResponse<T> result = remoteCall.get();
      if (result == null || (!"0".equals(result.code) && !businessCode(result.code)))
        Tracer.traceEntry(new IllegalStateException("Stock 返回空值或非业务错误码"), entry);
      return result;
    } catch (FeignException failure) {
      if (failure.status() < 0 || failure.status() >= 500
          || !validBusinessResponse(failure)) Tracer.traceEntry(failure, entry);
      throw failure;
    } catch (RuntimeException failure) {
      Tracer.traceEntry(failure, entry);
      throw failure;
    } finally {
      // 3. 所有放行调用恰好退出一次；本地 BlockException 从未取得 Entry。
      entry.exit();
    }
  }

  /** 已知业务错误不会打开依赖熔断，系统错误和无法识别响应必须计入异常。 */
  private static boolean businessCode(String code) {
    if (code == null || code.isBlank()) return false;
    return code.startsWith("SECKILL_") || code.startsWith("ORDER_")
        || code.equals("GATEWAY_RATE_LIMITED")
        || code.equals("COMMON_INVALID_ARGUMENT") || code.equals("COMMON_FORBIDDEN")
        || code.equals("COMMON_NOT_FOUND") || code.equals("COMMON_CONFLICT");
  }

  /** 仅明确的 4xx 业务响应不向 Sentinel 记录系统异常。 */
  private static boolean validBusinessResponse(FeignException failure) {
    if (failure.status() < 400 || failure.status() >= 500) return false;
    try {
      JsonNode body = MAPPER.readTree(failure.contentUTF8());
      return body != null && businessCode(body.path("code").asText());
    } catch (Exception malformedBody) {
      return false;
    }
  }
}
