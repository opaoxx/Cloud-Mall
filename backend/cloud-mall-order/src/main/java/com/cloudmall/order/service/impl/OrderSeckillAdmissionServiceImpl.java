package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderWorkflowSupport.bad;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.findAddress;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.remoteCode;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.toJson;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.config.SeckillSentinelConfiguration;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.service.OrderSeckillAdmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 秒杀入口的原受理编排，无本地写事务、共享写锁或自动重试。 */
@Service
public class OrderSeckillAdmissionServiceImpl implements OrderSeckillAdmissionService {
  /** 承担持久受理与库存事实的远端Stock契约。 */
  private final StockClient stockClient;

  /** 查询当前用户可用收货地址的原User契约。 */
  private final UserClient userClient;

  /** 保持既有地址快照序列化及Feign错误解码配置。 */
  private final ObjectMapper objectMapper;

  /** 注入原受理流程的三个依赖，不持有订单Mapper或消息发送器。 */
  public OrderSeckillAdmissionServiceImpl(
      StockClient stockClient, UserClient userClient, ObjectMapper objectMapper) {
    this.stockClient = stockClient;
    this.userClient = userClient;
    this.objectMapper = objectMapper;
  }

  /** 按原限流/地址/远端调用顺序执行一次请求，并保留完整受理响应和异常分类。 */
  @Override
  public ApiResponse<?> submit(String idempotencyKey, SeckillOrderDTO request, long userId) {
    // 1. 保留原基础参数和Unicode键长度校验，不归一化请求。
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.codePointCount(0, idempotencyKey.length()) > 128
        || request == null
        || request.activityId == null
        || request.skuId == null
        || request.addressId == null) bad("秒杀参数不完整");
    // 2. 先进入原用户/活动热点资源，阻断时不读取地址或调用Stock。
    Entry sentinelEntry;
    try {
      sentinelEntry = SeckillSentinelConfiguration.enter(userId, request.activityId);
    } catch (BlockException blocked) {
      SeckillSentinelConfiguration.recordBlock();
      throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
    }
    try {
      // 3. 固定原地址快照和五字段载荷，在依赖保护内仅提交一次。
      Map<String, Object> address = findAddress(userClient, request.addressId);
      Map<String, Object> stockRequest = new HashMap<>();
      stockRequest.put("activityId", request.activityId);
      stockRequest.put("skuId", request.skuId);
      stockRequest.put("userId", userId);
      stockRequest.put("idempotencyKey", idempotencyKey);
      stockRequest.put("addressSnapshot", toJson(objectMapper, address));
      try {
        ApiResponse<?> response =
            OrderStockCircuitBreaker.call(
                OrderStockCircuitBreaker.SECKILL, () -> stockClient.seckill(stockRequest));
        if (response == null)
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        // 4. 完整保留远端信封，包含非零业务码及原requestId。
        return response;
      } catch (FeignException exception) {
        if (exception.status() == 429) {
          String downstreamCode = remoteCode(objectMapper, exception);
          if ("GATEWAY_RATE_LIMITED".equals(downstreamCode))
            throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
          if ("SECKILL_LIMIT_REACHED".equals(downstreamCode))
            throw new BizException("SECKILL_LIMIT_REACHED", "每位用户只能购买一次该秒杀商品", 429);
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        }
        String downstreamCode = remoteCode(objectMapper, exception);
        if (exception.status() == 409 && "SECKILL_IDEMPOTENCY_CONFLICT".equals(downstreamCode))
          throw new BizException("SECKILL_IDEMPOTENCY_CONFLICT", "秒杀幂等键载荷已被占用", 409);
        if (exception.status() == 409
            && Set.of(
                    "SECKILL_NOT_STARTED",
                    "SECKILL_ENDED",
                    "SECKILL_SOLD_OUT",
                    "SECKILL_CLAIM_CONFLICT",
                    "COMMON_CONFLICT")
                .contains(downstreamCode))
          throw new BizException("SECKILL_NOT_AVAILABLE", "秒杀活动当前不可参与，请稍后再试", 409);
        throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
      } catch (StockCircuitOpenException circuitOpen) {
        throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
      }
    } finally {
      // 5. 已放行的成功与异常路径均按原参数退出一次资源。
      sentinelEntry.exit(1, userId, request.activityId);
    }
  }
}
