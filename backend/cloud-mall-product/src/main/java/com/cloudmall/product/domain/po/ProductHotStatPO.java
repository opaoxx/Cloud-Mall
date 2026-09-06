package com.cloudmall.product.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 商品热度统计持久化对象。 */
@TableName("product_hot_stat")
public class ProductHotStatPO {
  /** 商品主键。 */
  @TableId public Long productId;

  /** 浏览次数。 */
  @TableField("view_count")
  public Long viewCount;

  /** 搜索次数。 */
  @TableField("search_count")
  public Long searchCount;

  /** 热度分数。 */
  @TableField("hot_score")
  public BigDecimal hotScore;

  /** 统计日期。 */
  @TableField("stat_date")
  public LocalDate statDate;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
