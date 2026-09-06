package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 用户表数据访问接口。 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
  /** 按用户和支付幂等键扣减余额。 */
  @Update(
      "update mall_user set balance=balance-#{amount},updated_at=#{updatedAt} where id=#{userId}"
          + " and status=1 and balance>=#{amount}")
  int debitBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
