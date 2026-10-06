package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;

/** Pay LocalTCC receipt Facade。 */
public interface PayTccReceiptService {
  /** 独立认领原 Pay dispatch，唯一胜者注册 Pay 分支，重放只读。 */
  PayTccReceiptVO prepare(PayTccDispatchDTO request);

  /** 读取 Pay receipt 持久状态。 */
  PayTccReceiptVO read(String payNo);
}
