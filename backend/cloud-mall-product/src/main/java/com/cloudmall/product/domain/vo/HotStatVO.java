package com.cloudmall.product.domain.vo;

/** 商品热度统计响应视图。 */
public class HotStatVO {
  /** 商品主键。 */
  public final long productId;

  /** 浏览次数。 */
  public final long viewCount;

  /** 搜索次数。 */
  public final long searchCount;

  /** 热度分数。 */
  public final String hotScore;

  /** 创建商品热度响应。 */
  public HotStatVO(long productId, long viewCount, long searchCount, String hotScore) {
    this.productId = productId;
    this.viewCount = viewCount;
    this.searchCount = searchCount;
    this.hotScore = hotScore;
  }
}
