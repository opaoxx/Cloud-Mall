package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;

/** Pay 到 User 的一次性 TCC 派发与只读恢复契约。 */
public interface PayTccBridgeService {
  /** 持久化派发事实并最多发出一次 User Try POST。 */
  PayTccDispatchVO dispatch(PayTccDispatchDTO request);

  /** 只通过 User GET 恢复已有派发镜像。 */
  PayTccDispatchVO recover(String payNo);
}
