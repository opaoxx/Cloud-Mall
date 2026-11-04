package com.cloudmall.stock.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** 内部受理事实的最小响应；子类型只添加已经观察到的字段。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SeckillReservationFactVO {
  /** 调用方查询的原订单号，不用候选行中的别名替换。 */
  public final String orderNo;

  /** 原查询规则推导的状态；缺失受理时为NOT_FOUND。 */
  public final String status;

  /** 创建已观察响应的公共字段，保留原LinkedHashMap允许null的语义。 */
  protected SeckillReservationFactVO(String orderNo, String status) {
    this.orderNo = orderNo;
    this.status = status;
  }

  /** 构造仅含两个字段的未找到响应，沿用原Map.of对null订单号的拒绝。 */
  public static SeckillReservationFactVO notFound(String orderNo) {
    return new SeckillReservationFactVO(Objects.requireNonNull(orderNo), "NOT_FOUND");
  }
}
