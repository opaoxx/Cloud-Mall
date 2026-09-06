package com.cloudmall.order.feign;

import com.cloudmall.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "cloud-mall-user")
public interface UserClient {
  @GetMapping("/api/users/me/addresses")
  ApiResponse<List<Map<String, Object>>> addresses();
}
