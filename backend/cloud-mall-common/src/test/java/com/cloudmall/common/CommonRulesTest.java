package com.cloudmall.common;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.api.*;
import org.junit.jupiter.api.Test;

class CommonRulesTest {
  @Test
  void successEnvelopeUsesContract() {
    ApiResponse<String> response = ApiResponse.ok("ok");
    assertEquals("0", response.code);
    assertEquals("ok", response.data);
    assertNotNull(response.requestId);
  }

  @Test
  void pageEnvelopeKeepsMetadata() {
    PageResult<String> pageResult =
        new PageResult<>(java.util.Collections.singletonList("x"), 1, 20, 1);
    assertEquals(1, pageResult.total);
  }

  @Test
  void authContextDefaultsRoleAndClearsThreadState() {
    com.cloudmall.common.auth.AuthContext.set(7L, null);
    assertTrue(com.cloudmall.common.auth.AuthContext.authenticated());
    assertFalse(com.cloudmall.common.auth.AuthContext.isAdmin());
    com.cloudmall.common.auth.AuthContext.clear();
    assertFalse(com.cloudmall.common.auth.AuthContext.authenticated());
  }
}
