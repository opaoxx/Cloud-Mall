package com.cloudmall.pay.service;

import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import java.util.function.LongSupplier;

/** 创建支付记录并沿原幂等键重放，不执行资金扣款。 */
public interface PayCreationService {
  /**
   * 在原事务和共享监视器内校验请求并创建或重放支付记录。
   *
   * @param idempotencyKey 未经改写的原幂等键
   * @param request 原支付请求
   * @param userIdSupplier 在事务开始且获得锁后读取当前用户身份
   * @return 已有或新建的支付视图
   */
  PayVO create(String idempotencyKey, PayCreateDTO request, LongSupplier userIdSupplier);
}
