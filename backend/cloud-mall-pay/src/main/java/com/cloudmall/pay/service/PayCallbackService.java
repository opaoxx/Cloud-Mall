package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.vo.PayVO;

/** 支付回调的凭证、持久绑定及原协议处理入口。 */
public interface PayCallbackService {
  /**
   * 在共享监视器内沿原顺序处理回调，不新增外层事务。
   *
   * @param token 原可选内部回调凭证
   * @param callback 原回调请求，身份取自该载荷
   * @return 完成日志后重读的支付视图；异常保持原语义
   */
  PayVO handle(String token, PayCallbackDTO callback);
}
