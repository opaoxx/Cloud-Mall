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
  /** 只读核验用户及地址id为signed BIGINT非空主键且已启用数据库自增。 */
  @Select(
      "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
          + " AND table_name IN ('mall_user','user_address') AND column_name='id'"
          + " AND data_type='bigint' AND column_type NOT LIKE '%unsigned%'"
          + " AND is_nullable='NO' AND column_key='PRI' AND extra='auto_increment'")
  int countCompatibleIdentifierColumns();

  /** 两张表合计必须恰为两个主键列，拒绝把复合主键误作单列身份。 */
  @Select(
      "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
          + " AND table_name IN ('mall_user','user_address') AND index_name='PRIMARY'")
  int countIdentifierPrimaryColumns();

  /** 读取当前最大用户标识，空表返回零，不修改计数器。 */
  @Select("SELECT COALESCE(MAX(id),0) FROM mall_user")
  Long maximumUserIdentifier();

  /** 读取当前最大地址标识，空表返回零，不修改计数器。 */
  @Select("SELECT COALESCE(MAX(id),0) FROM user_address")
  Long maximumAddressIdentifier();

  /** 只读观察用户表自增计数器，不预分配或降低下一值。 */
  @Select(
      "SELECT auto_increment FROM information_schema.tables WHERE table_schema=DATABASE()"
          + " AND table_name='mall_user' AND table_type='BASE TABLE'")
  Long nextUserIdentifier();

  /** 只读观察地址表自增计数器，不预分配或降低下一值。 */
  @Select(
      "SELECT auto_increment FROM information_schema.tables WHERE table_schema=DATABASE()"
          + " AND table_name='user_address' AND table_type='BASE TABLE'")
  Long nextAddressIdentifier();

  /** 内部只读取证仅投影账户资金字段，不读取密码、电话或角色。 */
  @Select("SELECT id,status,balance,frozen_balance FROM mall_user WHERE id=#{userId}")
  UserPO selectBalanceEvidenceById(@Param("userId") Long userId);

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
  @Update(
      "update mall_user set balance=balance+#{amount},updated_at=#{updatedAt} where id=#{userId}")
  int creditBalance(
      @Param("userId") Long userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
