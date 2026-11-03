package com.cloudmall.product.domain.vo;

/** 商品热度统计响应视图。 */
public class HotStatVO {
  /** Separate online series; null preserves the original legacy JSON shape. */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public final OnlineMetricVO online;
  /** 商品主键。 */
  public final long productId;

  /** 浏览次数。 */
  public final long viewCount;

  /** 搜索次数。 */
  public final long searchCount;

  /** 热度分数。 */
  public final String hotScore;

  /** 指标来源类型，历史目录为 LEGACY_IMPORT。 */
  public final String sourceType;

  /** 指标来源引用；未知来源时为空。 */
  public final String sourceRef;

  /** 创建商品热度响应。 */
  public HotStatVO(long productId, long viewCount, long searchCount, String hotScore) {
    this(productId, viewCount, searchCount, hotScore, "UNKNOWN", null);
  }

  /** 创建携带来源标记的商品热度响应。 */
  public HotStatVO(long productId, long viewCount, long searchCount, String hotScore,
      String sourceType, String sourceRef) {
    this(productId,viewCount,searchCount,hotScore,sourceType,sourceRef,null);
  }

  /** Keep imported counters unchanged and attach the separate online aggregate. */
  public HotStatVO(long productId,long viewCount,long searchCount,String hotScore,String sourceType,String sourceRef,OnlineMetricVO online) {
    this.online=online;
    this.productId = productId;
    this.viewCount = viewCount;
    this.searchCount = searchCount;
    this.hotScore = hotScore;
    this.sourceType = sourceType;
    this.sourceRef = sourceRef;
  }
}
