package com.cloudmall.product.service;

import com.cloudmall.product.domain.dto.ActivityDTO;
import com.cloudmall.product.domain.dto.ProductOperatorDTO;
import com.cloudmall.product.domain.vo.ActivityVO;

/** 秒杀活动管理与 Redis 配额发布契约。 */
public interface SeckillActivityService {
  /** 查询活动和 Redis 当前剩余配额。 */
  ActivityVO activity(Long activityId);

  /** 校验管理员和活动参数后创建活动。 */
  ActivityVO createActivity(ProductOperatorDTO operator, ActivityDTO request);

  /** 按原数据库更新和 Redis 读取顺序更新活动。 */
  ActivityVO updateActivity(ProductOperatorDTO operator, Long activityId, ActivityDTO request);

  /** 发布活动并按原顺序写入 Redis 配额与元数据。 */
  ActivityVO publishActivity(ProductOperatorDTO operator, Long activityId);

  /** 将活动状态推进为 STARTED 并返回当前活动。 */
  ActivityVO startActivity(ProductOperatorDTO operator, Long activityId);
}
