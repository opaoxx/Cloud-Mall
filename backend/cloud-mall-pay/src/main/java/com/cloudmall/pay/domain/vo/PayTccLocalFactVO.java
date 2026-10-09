package com.cloudmall.pay.domain.vo;

/**
 * 本地证据来源的存在性与白名单状态，不承载原始标识或错误文本。
 *
 * @param present 原记录是否存在
 * @param state 白名单状态，缺行时为null，未知值显示UNRECOGNIZED
 */
public record PayTccLocalFactVO(boolean present, String state) {
  /** 创建不可变的本地来源摘要。 */
  public PayTccLocalFactVO {}
}
