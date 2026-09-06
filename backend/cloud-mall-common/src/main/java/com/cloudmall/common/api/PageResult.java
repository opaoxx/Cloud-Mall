package com.cloudmall.common.api;

import java.util.*;

public class PageResult<T> {
  /** 保存 items 对应的内部状态或配置。 */
  public List<T> items;

  /** 保存 page 对应的内部状态或配置。 */
  public int page;

  /** 保存 pageSize 对应的内部状态或配置。 */
  public int pageSize;

  /** 保存 total 对应的内部状态或配置。 */
  public long total;

  /** 初始化分页结果对象。 */
  public PageResult(List<T> items, int page, int pageSize, long total) {
    this.items = items;
    this.page = page;
    this.pageSize = pageSize;
    this.total = total;
  }
}
