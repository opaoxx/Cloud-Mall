package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;

/** 内部扣款切片编排，不直接推进公开支付/订单状态。 */
public interface PayTccCoordinatorService {
  /** 原支付只有唯一首次调用可 begin 和执行两个 Try。 */
  PayTccExecutionVO execute(PayTccDispatchDTO request);

  /** 按原 XID 核对两端事实和重试既有决议，绝不 begin 或 Try。 */
  PayTccExecutionVO recover(String payNo);
}
