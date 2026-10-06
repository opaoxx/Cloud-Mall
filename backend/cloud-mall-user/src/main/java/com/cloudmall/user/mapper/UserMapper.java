package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户表数据访问接口。 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
  /** 为旧用户表幂等补充 TCC 冻结余额列。 */
  @Select(
      "select count(*) from information_schema.columns where table_schema=database() and"
          + " table_name='mall_user' and column_name='frozen_balance'")
  int countFrozenBalanceColumn();

  /** 执行一次性冻结余额列迁移。 */
  @Update(
      "alter table mall_user add column frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00 AFTER"
          + " balance")
  int addFrozenBalanceColumn();

  /** 按主键锁定用户，允许调用方在同一事务内编排资金状态迁移。 */
  @Select("select * from mall_user where id=#{userId} for update")
  UserPO selectByIdForUpdate(@Param("userId") Long userId);

  /** 在用户行锁下冻结可用余额。 */
  @Update(
      "update mall_user set balance=balance-#{amount}, frozen_balance=frozen_balance+#{amount},"
          + " updated_at=#{updatedAt} where id=#{userId} and status=1 and balance>=#{amount}")
  int freezeBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 在用户行锁下释放冻结余额。 */
  @Update(
      "update mall_user set balance=balance+#{amount}, frozen_balance=frozen_balance-#{amount},"
          + " updated_at=#{updatedAt} where id=#{userId} and frozen_balance>=#{amount}")
  int releaseFrozenBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 在用户行锁下清算冻结余额。 */
  @Update(
      "update mall_user set frozen_balance=frozen_balance-#{amount}, updated_at=#{updatedAt} where"
          + " id=#{userId} and frozen_balance>=#{amount}")
  int clearFrozenBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

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
