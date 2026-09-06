package com.cloudmall.product.domain.dto;

import java.time.OffsetDateTime;

/** 秒杀活动新增和修改请求。 */
public class ActivityDTO {
  /** 参与活动的 SKU。 */
  public Long skuId;

  /** 活动开始时间。 */
  public OffsetDateTime startAt;

  /** 活动结束时间。 */
  public OffsetDateTime endAt;

  /** 活动库存上限。 */
  public int stockLimit;

  /** 每用户限购数量。 */
  public int perUserLimit;
}
