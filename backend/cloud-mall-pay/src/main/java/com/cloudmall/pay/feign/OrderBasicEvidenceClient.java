package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.dto.OrderBasicEvidenceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** 取证专用GET-only原生解码客户端；不影响OrderClient的资金/交付业务调用。 */
@FeignClient(name = "cloud-mall-order", contextId = "orderBasicEvidenceClient")
public interface OrderBasicEvidenceClient {
  /** 按可信Pay订单/用户身份读取原公开详情，不调用paymentState或任何写接口。 */
  @GetMapping("/api/orders/{orderNo}")
  ApiResponse<OrderBasicEvidenceDTO> get(
      @PathVariable("orderNo") String orderNo, @RequestHeader("X-User-Id") Long userId);
}
