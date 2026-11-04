package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.order.controller.SeataTransactionDemoController;
import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.service.SeataDemoControlledRollbackException;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.SeataTransactionDemoService;
import io.seata.common.exception.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 覆盖真实MVC绑定、精确内部身份、原响应状态和Controller注册开关。 */
class SeataDemoHttpContractTest {
  /** 原有受控演练地址，Java类改名不得改变它。 */
  private static final String PATH = "/api/internal/cm18/seata/demo";

  /** 用于隔离HTTP契约的真实业务接口替身。 */
  private final SeataTransactionDemoService service = mock(SeataTransactionDemoService.class);

  /** 使用项目异常处理器的MVC入口。 */
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SeataTransactionDemoController(service))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  void validationAndExactInternalIdentityPreventServiceCalls() throws Exception {
    String valid = "{\"txId\":\"cm18-http\",\"failurePoint\":\"NONE\"}";
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(valid))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    mvc.perform(
            post(PATH)
                .header("X-Internal-Service", "CLOUD-MALL-CM18-TEST")
                .contentType(MediaType.APPLICATION_JSON)
                .content(valid))
        .andExpect(status().isForbidden());
    for (String body :
        new String[] {
          "{\"txId\":\"real-order-1\",\"failurePoint\":\"NONE\"}",
          "{\"txId\":\"cm18-http\",\"failurePoint\":\"unknown\"}",
          "{\"txId\":\"cm18-http\"}"
        }) {
      mvc.perform(
              post(PATH)
                  .header("X-Internal-Service", "cloud-mall-cm18-test")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void committedAndControlledRollbackPreserveThreeFieldsAndNoCacheHeader() throws Exception {
    when(service.execute(any()))
        .thenAnswer(
            invocation -> {
              SeataTransactionDemoDTO request = invocation.getArgument(0);
              assertEquals("cm18-http", request.txId);
              assertEquals("NONE", request.failurePoint);
              return new SeataTransactionDemoVO(request.txId, "masked-xid", "COMMITTED");
            });
    perform()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data.txId").value("cm18-http"))
        .andExpect(jsonPath("$.data.xid").value("masked-xid"))
        .andExpect(jsonPath("$.data.status").value("COMMITTED"))
        .andExpect(header().doesNotExist("Cache-Control"));
    doThrow(new SeataDemoControlledRollbackException("controlled")).when(service).execute(any());
    perform()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data.status").value("ROLLED_BACK"))
        .andExpect(jsonPath("$.data.xid").value(""))
        .andExpect(header().doesNotExist("Cache-Control"));
  }

  @Test
  void unknownAndConcurrentResultsRetainStatusCodeAndNoStore() throws Exception {
    doThrow(new SeataDemoUnknownTransactionException("unknown", null)).when(service).execute(any());
    perform()
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CM18_SEATA_UNKNOWN"))
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.xid").value(""))
        .andExpect(header().string("Cache-Control", "no-store"));
    doThrow(new DuplicateKeyException("duplicate")).when(service).execute(any());
    perform()
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.code").value("CM18_IN_PROGRESS"))
        .andExpect(jsonPath("$.data.status").value("UNKNOWN"))
        .andExpect(header().string("Cache-Control", "no-store"));
    doThrow(new FrameworkException("can not connect to services-server"))
        .when(service)
        .execute(any());
    perform()
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CM18_SEATA_UNKNOWN"))
        .andExpect(header().string("Cache-Control", "no-store"));
    FrameworkException unrelated = new FrameworkException("unrelated framework failure");
    doThrow(unrelated).when(service).execute(any());
    assertSame(
        unrelated,
        assertThrows(
            FrameworkException.class,
            () ->
                new SeataTransactionDemoController(service)
                    .execute(new SeataTransactionDemoDTO(), "cloud-mall-cm18-test")));
    perform()
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
  }

  @Test
  void originalBeanNameAndNonProductionOptInRemainRequired() {
    var runner =
        new ApplicationContextRunner()
            .withBean(SeataTransactionDemoService.class, () -> service)
            .withUserConfiguration(SeataTransactionDemoController.class);
    runner.run(
        context ->
            assertEquals(0, context.getBeansOfType(SeataTransactionDemoController.class).size()));
    runner
        .withPropertyValues("cloudmall.seata.cm18.enabled=true")
        .run(
            context -> {
              assertSame(
                  context.getBean(SeataTransactionDemoController.class),
                  context.getBean("cm18SeataDemoController"));
              assertFalse(context.containsBean("seataTransactionDemoController"));
            });
    runner
        .withPropertyValues("cloudmall.seata.cm18.enabled=true", "spring.profiles.active=prod")
        .run(
            context ->
                assertEquals(
                    0, context.getBeansOfType(SeataTransactionDemoController.class).size()));
  }

  private org.springframework.test.web.servlet.ResultActions perform() throws Exception {
    return mvc.perform(
        post(PATH)
            .header("X-Internal-Service", "cloud-mall-cm18-test")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"txId\":\"cm18-http\",\"failurePoint\":\"NONE\"}"));
  }
}
