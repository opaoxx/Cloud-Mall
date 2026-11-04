package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** 生成键交接、地址更新身份和只读部署兼容门，不用mock冒实库并发。 */
class UserGeneratedIdentifierTest {
  /** 仅用于本测试模拟数据库返回的原13位安全数字。 */
  private static final long GENERATED_ID = 1793000727573L;

  /** 每个方法结束清除线程认证上下文。 */
  @AfterEach
  void clearContext() {
    AuthContext.clear();
  }

  /** 两个PO须明确采用MyBatis-Plus数据库生成键策略。 */
  @Test
  void bothPersistentIdsUseDatabaseAutoStrategy() throws Exception {
    assertEquals(IdType.AUTO, UserPO.class.getField("id").getAnnotation(TableId.class).type());
    assertEquals(
        IdType.AUTO, UserAddressPO.class.getField("id").getAnnotation(TableId.class).type());
  }

  /** 注册不分配毫秒主键，响应使用Mapper回填值且保持数字JSON。 */
  @Test
  void registrationReturnsGeneratedNumericKey() {
    UserMapper users = mock(UserMapper.class);
    UserServiceImpl service = service(users, mock(UserAddressMapper.class));
    when(users.insert(any(UserPO.class)))
        .thenAnswer(
            invocation -> {
              UserPO row = invocation.getArgument(0);
              assertNull(row.id);
              row.id = GENERATED_ID;
              return 1;
            });
    CredentialsDTO credentials = new CredentialsDTO();
    credentials.username = "generated-user";
    credentials.password = "Fixture123!";

    ApiResponse<?> response = service.register(credentials);

    assertEquals(GENERATED_ID, ((Map<?, ?>) response.data).get("userId"));
    var json = new ObjectMapper().valueToTree(response.data);
    assertTrue(json.get("userId").isIntegralNumber());
    assertEquals(GENERATED_ID, json.get("userId").longValue());
    assertEquals(GENERATED_ID, (long) json.get("userId").doubleValue());
  }

  /** 新地址使用回填主键，更新地址则始终使用原请求标识。 */
  @Test
  void newAddressDelegatesIdentityAndUpdateKeepsOriginalId() {
    UserAddressMapper addresses = mock(UserAddressMapper.class);
    UserServiceImpl service = service(mock(UserMapper.class), addresses);
    AuthContext.set(7L, "USER");
    when(addresses.insert(any(UserAddressPO.class)))
        .thenAnswer(
            invocation -> {
              UserAddressPO row = invocation.getArgument(0);
              assertNull(row.id);
              row.id = GENERATED_ID;
              return 1;
            });
    when(addresses.updateOwnedAddress(any(UserAddressPO.class)))
        .thenAnswer(
            invocation -> {
              UserAddressPO row = invocation.getArgument(0);
              assertEquals(41L, row.id);
              return 1;
            });
    AddressRequestDTO request = request();

    UserAddressVO added = (UserAddressVO) service.addAddress(request).data;
    UserAddressVO updated = (UserAddressVO) service.updateAddress(41L, request).data;

    assertEquals(GENERATED_ID, added.id);
    assertEquals(41L, updated.id);
    assertTrue(new ObjectMapper().valueToTree(added).get("id").isIntegralNumber());
  }

  /** 未迁移或复合主键必须在管理员写入之前明确拒绝，不启动自动DDL。 */
  @Test
  void startupRejectsMissingAutoAndCompositePrimaryKey() {
    UserMapper users = compatibleUsers();
    UserServiceImpl service = service(users, mock(UserAddressMapper.class));
    when(users.countCompatibleIdentifierColumns()).thenReturn(0);
    BizException missing = assertThrows(BizException.class, service::ensureLocalAdmin);
    assertEquals("USER_IDENTIFIER_SCHEMA_INCOMPATIBLE", missing.getCode());
    when(users.countCompatibleIdentifierColumns()).thenReturn(2);
    when(users.countIdentifierPrimaryColumns()).thenReturn(3);
    assertThrows(BizException.class, service::ensureLocalAdmin);
    verify(users, never()).selectCount(any());
    verify(users, never()).insert(any(UserPO.class));
  }

  /** 未知、非正next或靠近JS精度上界时拒绝本批启动，不能靠写后报错冒回滚。 */
  @Test
  void startupRejectsUnknownAndUnsafeRange() {
    UserMapper users = compatibleUsers();
    UserServiceImpl service = service(users, mock(UserAddressMapper.class));
    for (Long next : new Long[] {null, 0L, -1L, 9007199254740991L}) {
      when(users.nextUserIdentifier()).thenReturn(next);
      assertThrows(BizException.class, service::ensureLocalAdmin);
    }
    when(users.nextUserIdentifier()).thenReturn(GENERATED_ID);
    when(users.maximumAddressIdentifier()).thenReturn(9007199254740991L);
    assertThrows(BizException.class, service::ensureLocalAdmin);
    verify(users, never()).selectCount(any());
    verify(users, never()).insert(any(UserPO.class));
  }

  /** 空兼容表的管理员同样交由数据库生成键；已有管理员不新增记录。 */
  @Test
  void bootstrapDelegatesGeneratedKeyAndRetainsExistingAdmin() {
    UserMapper users = compatibleUsers();
    UserServiceImpl service = service(users, mock(UserAddressMapper.class));
    when(users.insert(any(UserPO.class)))
        .thenAnswer(
            invocation -> {
              UserPO row = invocation.getArgument(0);
              assertNull(row.id);
              row.id = GENERATED_ID;
              return 1;
            });
    service.ensureLocalAdmin();
    UserPO administrator = new UserPO();
    administrator.id = GENERATED_ID;
    administrator.passwordHash = new BCryptPasswordEncoder().encode("admin123");
    when(users.selectCount(any())).thenReturn(1L);
    when(users.selectOne(any())).thenReturn(administrator);
    service.ensureLocalAdmin();
    verify(users, times(1)).insert(any(UserPO.class));
    verify(users, never()).updateById(any(UserPO.class));
  }

  private static UserMapper compatibleUsers() {
    UserMapper users = mock(UserMapper.class);
    when(users.countCompatibleIdentifierColumns()).thenReturn(2);
    when(users.countIdentifierPrimaryColumns()).thenReturn(2);
    when(users.maximumUserIdentifier()).thenReturn(1793000727572L);
    when(users.maximumAddressIdentifier()).thenReturn(1793000727572L);
    when(users.nextUserIdentifier()).thenReturn(GENERATED_ID);
    when(users.nextAddressIdentifier()).thenReturn(GENERATED_ID);
    return users;
  }

  private static UserServiceImpl service(UserMapper users, UserAddressMapper addresses) {
    UserServiceImpl service = new UserServiceImpl();
    ReflectionTestUtils.setField(service, "userMapper", users);
    ReflectionTestUtils.setField(service, "userAddressMapper", addresses);
    return service;
  }

  private static AddressRequestDTO request() {
    AddressRequestDTO request = new AddressRequestDTO();
    request.receiver = "标识测试";
    request.phone = "13800000000";
    request.detailAddress = "隔离测试地址";
    request.isDefault = false;
    return request;
  }
}
