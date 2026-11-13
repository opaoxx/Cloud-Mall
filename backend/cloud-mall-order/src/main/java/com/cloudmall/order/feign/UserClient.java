package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "cloud-mall-user")
public interface UserClient {
  /**
   * 读取当前认证用户的收货地址列表，身份由既有Feign拦截器透传。
   *
   * @return 原地址列表响应，字段和null语义由User契约提供
   */
  @GetMapping("/api/users/me/addresses")
  ApiResponse<List<Map<String, Object>>> addresses();
}
