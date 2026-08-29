package com.cloudmall.common;
import com.cloudmall.common.api.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CommonRulesTest{@Test void successEnvelopeUsesContract(){ApiResponse<String> r=ApiResponse.ok("ok");assertEquals("0",r.code);assertEquals("ok",r.data);assertNotNull(r.requestId);}@Test void pageEnvelopeKeepsMetadata(){PageResult<String> p=new PageResult<>(java.util.Collections.singletonList("x"),1,20,1);assertEquals(1,p.total);}}
