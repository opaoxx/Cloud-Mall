package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

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

  /** 锁定用户余额，保证余额和账本在同一事务内串行修改。 */
  @Select("select * from mall_user where id=#{userId} and status=1 for update")
  UserPO selectActiveForUpdate(@Param("userId") Long userId);

  /** 按用户主键增加余额。 */
  @Update("update mall_user set balance=balance+#{amount},updated_at=#{updatedAt} where id=#{userId}")
  int creditBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
