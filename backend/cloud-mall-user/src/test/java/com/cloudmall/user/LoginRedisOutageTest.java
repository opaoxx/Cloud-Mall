package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.service.impl.AuthenticationServiceImpl;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class LoginRedisOutageTest {
  @Test
  void logoutRedisFailureRetains503InsteadOfReportingSuccess() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    AuthenticationServiceImpl service =
        new AuthenticationServiceImpl(mock(UserMapper.class), redis);
    when(redis.delete("auth:token:fixture-token"))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    BizException failure =
        assertThrows(BizException.class, () -> service.logout("Bearer  fixture-token  "));

    assertEquals(503, failure.getHttpStatus());
    assertEquals("REDIS_SERVICE_UNAVAILABLE", failure.getCode());
    verify(redis).delete("auth:token:fixture-token");
  }

  @Test
  void logoutWithoutExactBearerPrefixDoesNotAccessRedis() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    AuthenticationServiceImpl service =
        new AuthenticationServiceImpl(mock(UserMapper.class), redis);

    for (String header : new String[] {null, "", "bearer token", "Basic token", "Bearer"}) {
      service.logout(header);
    }

    verifyNoInteractions(redis);
  }

  @Test
  void validPasswordCannotReturnTokenWhenRedisWriteFails() {
    UserMapper users = mock(UserMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    UserPO user = user();
    when(users.selectOne(any())).thenReturn(user);
    when(redis.opsForValue()).thenReturn(values);
    doThrow(new RedisConnectionFailureException("redis down"))
        .when(values)
        .set(any(), any(), any(Duration.class));
    AuthenticationServiceImpl service = new AuthenticationServiceImpl(users, redis);

    BizException failure =
        assertThrows(BizException.class, () -> service.login(credentials("correct")));

    assertEquals(503, failure.getHttpStatus());
    assertEquals("REDIS_SERVICE_UNAVAILABLE", failure.getCode());
  }

  @Test
  void invalidPasswordStillReturnsAuthenticationErrorWithoutRedis() {
    UserMapper users = mock(UserMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(users.selectOne(any())).thenReturn(user());
    AuthenticationServiceImpl service = new AuthenticationServiceImpl(users, redis);

    BizException failure =
        assertThrows(BizException.class, () -> service.login(credentials("incorrect")));

    assertEquals(401, failure.getHttpStatus());
    verifyNoInteractions(redis);
  }

  private static UserPO user() {
    UserPO user = new UserPO();
    user.id = 33L;
    user.username = "alice";
    user.passwordHash = new BCryptPasswordEncoder().encode("correct");
    user.role = "USER";
    user.status = 1;
    return user;
  }

  private static CredentialsDTO credentials(String password) {
    CredentialsDTO credentials = new CredentialsDTO();
    credentials.username = "alice";
    credentials.password = password;
    return credentials;
  }
}
