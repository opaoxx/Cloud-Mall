package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;

/** TCC 确认后的独立交付，不调用旧扣款或改变全局决议。 */
public interface PayTccDeliveryService {
  /** 原 payNo 幂等交付一次；未确认 execution 返回空且不产生任务。 */
  PayTccDeliveryVO deliver(String payNo);
}
