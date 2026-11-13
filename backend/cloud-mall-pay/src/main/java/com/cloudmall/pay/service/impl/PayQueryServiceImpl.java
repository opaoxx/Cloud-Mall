package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayQueryService;
import com.cloudmall.pay.util.PayRecordViews;
import java.util.List;
import org.springframework.stereotype.Service;

/** 保持既有筛选和映射顺序的本地支付查询实现。 */
@Service
public class PayQueryServiceImpl implements PayQueryService {
  /** 同时按订单号与用户身份筛选支付记录的持久化入口。 */
  private final PayRecordMapper payRecordMapper;

  /**
   * 创建本地支付查询服务。
   *
   * @param payRecordMapper 原支付记录Mapper
   */
  public PayQueryServiceImpl(PayRecordMapper payRecordMapper) {
    this.payRecordMapper = payRecordMapper;
  }

  /** {@inheritDoc} */
  @Override
  public PayVO getByOrderNo(String orderNo, long userId) {
    // 1. 按原条件读取并完整映射全部结果，保留后续异常行的失败语义。
    List<PayVO> records =
        payRecordMapper.selectByOrderNoAndUserId(orderNo, userId).stream()
            .map(PayRecordViews::toView)
            .toList();
    // 2. 空列表沿用原404，不追加远程订单查询或归属判断。
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    // 3. 返回原Mapper顺序的首条，不新增排序或截断查询。
    return records.get(0);
  }
}
