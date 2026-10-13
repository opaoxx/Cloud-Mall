package com.cloudmall.pay.util;

/** Pay诊断字段的字符容量处理，不改变错误前缀或业务状态。 */
public final class PayErrorMessages {
  /** Pay诊断VARCHAR(512)可保存的最大Unicode码点数量。 */
  private static final int MAX_ERROR_CODE_POINTS = 512;

  /** 禁止实例化无状态工具。 */
  private PayErrorMessages() {}

  /**
   * 按完整码点保留诊断前缀，避免截断合法代理对；不归一化或修复孤立代理。
   *
   * @param error 原诊断文本，允许null或空串
   * @return 原文本的最多512码点前缀，null保持null
   */
  public static String truncate(String error) {
    if (error == null) return null;
    int retainedCodePoints =
        Math.min(MAX_ERROR_CODE_POINTS, error.codePointCount(0, error.length()));
    return error.substring(0, error.offsetByCodePoints(0, retainedCodePoints));
  }
}
