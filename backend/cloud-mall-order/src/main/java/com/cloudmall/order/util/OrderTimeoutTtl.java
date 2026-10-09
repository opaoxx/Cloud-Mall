package com.cloudmall.order.util;

import java.time.Duration;
import java.util.Objects;

/** 订单超时消息剩余时长的毫秒换算，不读取时钟、配置或中间件。 */
public final class OrderTimeoutTtl {
  /** 纯工具不允许实例化。 */
  private OrderTimeoutTtl() {}

  /**
   * 将正数剩余时长向上取整到毫秒，已到期返回0，溢出交由原投递失败路径处理。
   *
   * @param duration 调用方以同一次采样得到的剩余时长，非null
   * @return 正时长的完整毫秒数，零或负时长为0
   * @throws NullPointerException 时长为空
   * @throws ArithmeticException 正数时长转毫秒或向上加一超出long范围
   */
  public static long remainingMillis(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    // 1. 先判到期，避免对巨大负时长做可能溢出的毫秒换算。
    if (duration.isNegative() || duration.isZero()) return 0;
    // 2. 先取得完整毫秒，保留原转换溢出的异常语义。
    long wholeMillis = duration.toMillis();
    // 3. 只对亚毫秒余量加一，checked加法避免回绕后误判已到期。
    return duration.getNano() % 1_000_000 == 0 ? wholeMillis : Math.addExact(wholeMillis, 1L);
  }
}
