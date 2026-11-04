package com.cloudmall.gateway.util;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriUtils;

/** 网关公开访问与内部 TCC 路径规则，检查过程保留原转发路径。 */
public final class GatewayAccessPolicy {
  /** 在 URI 已解码的路径之外，继续检查的最大额外解码深度。 */
  private static final int MAX_EXTRA_DECODE_DEPTH = 2;

  /** 禁止实例化无状态访问规则。 */
  private GatewayAccessPolicy() {}

  /** 检查原路径及两层额外解码结果中的内部 TCC 标识，保留原大小写规则。 */
  public static boolean isInternalTccPath(String path) {
    String decodedPath = path;
    for (int depth = 0; depth <= MAX_EXTRA_DECODE_DEPTH; depth++) {
      if (decodedPath.contains("tcc-debits") || decodedPath.contains("tcc-reconciliation"))
        return true;
      if (!decodedPath.contains("%")) return false;
      try {
        decodedPath = UriUtils.decode(decodedPath, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException malformedEncoding) {
        return false;
      }
    }
    return false;
  }

  /** 按原方法和精确路径规则判定匿名访问资格。 */
  public static boolean isPublicRequest(HttpMethod method, String path) {
    if (path.startsWith("/actuator/")) return true;
    if (path.startsWith("/api/auth/")) {
      return method == HttpMethod.POST
          && (path.equals("/api/auth/login") || path.equals("/api/auth/register"));
    }
    return method == HttpMethod.GET
        && (path.equals("/api/products")
            || path.startsWith("/api/products/")
            || path.equals("/api/categories")
            || path.startsWith("/api/categories/")
            || path.startsWith("/api/seckill/activities/"));
  }
}
