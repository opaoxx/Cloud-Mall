package com.cloudmall.pay.feign;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.pay.domain.dto.StockReservationEvidenceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 仅有GET的库存观察客户端，沿已有3s/5s与默认不重试配置，不提供写接口。 */
@FeignClient(name = "cloud-mall-stock", contextId = "stockEvidenceClient")
public interface StockEvidenceClient {
  /** 按原Pay持久锚点订单号观察Stock预扣事实，不传调用方认证/事务上下文。 */
  @GetMapping("/api/stock/reservations/{orderNo}")
  ApiResponse<StockReservationEvidenceDTO> reservation(@PathVariable("orderNo") String orderNo);
}
