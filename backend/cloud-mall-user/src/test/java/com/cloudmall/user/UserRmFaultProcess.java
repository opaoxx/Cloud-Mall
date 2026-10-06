package com.cloudmall.user;

import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.vo.BalanceTccDebitVO;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.BalanceTccDebitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.seata.core.context.RootContext;
import io.seata.core.model.BranchType;
import io.seata.rm.DefaultResourceManager;
import io.seata.rm.tcc.TCCResource;
import io.seata.rm.tcc.api.BusinessActionContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.PriorityOrdered;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅在测试 classpath 中存在的独立 User RM；控制端口不打入业务 Jar。 */
public final class UserRmFaultProcess {
  /** 测试工具不允许实例化。 */
  private UserRmFaultProcess() {}

  /** 启动真实 User RM 与绑定 loopback 的测试控制通道。 */
  public static void main(String[] args) throws Exception {
    ConfigurableApplicationContext application =
        new SpringApplicationBuilder(UserApplication.class).web(WebApplicationType.NONE).run(args);
    FaultGate gate = application.getBean(FaultGate.class);
    BalanceTccDebitLocalResource resource = application.getBean(BalanceTccDebitLocalResource.class);
    TCCResource registered =
        (TCCResource)
            DefaultResourceManager.get()
                .getResourceManager(BranchType.TCC)
                .getManagedResources()
                .get(BalanceTccDebitLocalResource.ACTION_NAME);
    Object callbackTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
            registered.getTargetBean());
    if (!(callbackTarget instanceof PausedResource))
      throw new IllegalStateException("测试暂停资源没有被 Seata 注册，不能证明 ACK 前故障点");
    ObjectMapper mapper = application.getBean(ObjectMapper.class);
    String controlKey =
        application.getEnvironment().getRequiredProperty("cloudmall.test.control-key");
    Path readyFile =
        Path.of(application.getEnvironment().getRequiredProperty("cloudmall.test.ready-file"));
    HttpServer control =
        HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    control.setExecutor(
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "cm15b-control");
              thread.setDaemon(true);
              return thread;
            }));
    control.createContext(
        "/",
        exchange -> {
          try {
            if (!controlKey.equals(exchange.getRequestHeaders().getFirst("X-Test-Key"))) {
              respond(exchange, mapper, 403, Map.of("error", "invalid test key"));
              return;
            }
            switch (exchange.getRequestURI().getPath()) {
              case "/state":
                respond(exchange, mapper, 200, gate.snapshot());
                break;
              case "/arm":
                gate.arm(mapper.readTree(exchange.getRequestBody()).get("point").asText());
                respond(exchange, mapper, 200, Map.of("armed", true));
                break;
              case "/prepare":
                JsonNode body = mapper.readTree(exchange.getRequestBody());
                BalanceTccBranchDTO request =
                    mapper.treeToValue(body.get("request"), BalanceTccBranchDTO.class);
                RootContext.bind(body.get("xid").asText());
                try {
                  respond(
                      exchange, mapper, 200, Map.of("prepared", resource.prepare(null, request)));
                } finally {
                  RootContext.unbind();
                }
                break;
              default:
                respond(exchange, mapper, 404, Map.of("error", "test control route not found"));
            }
          } catch (Exception failure) {
            respond(
                exchange,
                mapper,
                500,
                Map.of(
                    "error",
                    failure.getClass().getName(),
                    "message",
                    String.valueOf(failure.getMessage())));
          } finally {
            exchange.close();
          }
        });
    control.start();
    Files.writeString(readyFile, Integer.toString(control.getAddress().getPort()));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  control.stop(0);
                  application.close();
                },
                "cm15b-shutdown"));
    new CountDownLatch(1).await();
  }

  /** 回应仅测试使用的 JSON 控制消息。 */
  private static void respond(
      HttpExchange exchange, ObjectMapper mapper, int status, Object response) throws IOException {
    byte[] encoded = mapper.writeValueAsBytes(response);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, encoded.length);
    exchange.getResponseBody().write(encoded);
  }

  /** 用事务真实状态和分支标识证明暂停窗口，不使用 sleep 猜阶段。 */
  public static final class FaultGate {
    /** 可选暂停点，空值表示不注入故障。 */
    private final AtomicReference<String> armed = new AtomicReference<>();

    /** 已进入暂停点的不可变证据，控制线程可以独立读取。 */
    private final AtomicReference<Map<String, Object>> reached =
        new AtomicReference<>(Map.of("paused", false));

    /** 为下一次指定阶段设置一次性暂停。 */
    public void arm(String point) {
      if (!Set.of(
                  "TRY_BEFORE_COMMIT",
                  "TRY_AFTER_COMMIT",
                  "CONFIRM_BEFORE_COMMIT",
                  "CONFIRM_AFTER_COMMIT",
                  "CANCEL_BEFORE_COMMIT",
                  "CANCEL_AFTER_COMMIT")
              .contains(point)
          || Boolean.TRUE.equals(reached.get().get("paused"))
          || !armed.compareAndSet(null, point)) throw new IllegalStateException("无效或重复的测试暂停点");
    }

    /** 返回确定性阶段证据。 */
    public Map<String, Object> snapshot() {
      return reached.get();
    }

    /** 匹配阶段只触发一次；由外部进程终止解除暂停。 */
    public void pause(String point, String xid, long branchId) {
      String expected = armed.get();
      if (!point.equals(expected) || !armed.compareAndSet(expected, null)) return;
      boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
      if (transactionActive != point.endsWith("BEFORE_COMMIT"))
        throw new IllegalStateException("测试暂停点事务状态不匹配: " + point);
      reached.set(
          Map.of(
              "paused",
              true,
              "point",
              point,
              "xid",
              xid,
              "branchId",
              Long.toString(branchId),
              "transactionActive",
              transactionActive));
      System.out.println("CM15B_BARRIER " + point + " xid=" + xid + " branch=" + branchId);
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
  }

  /** 保留原资金服务的 Spring 事务代理，在其加入外层事务后暂停。 */
  private static final class PausedFunds implements BalanceTccDebitService {
    /** 已验收的生产资金代理。 */
    private final BalanceTccDebitService delegate;

    /** 测试 barrier。 */
    private final FaultGate gate;

    /** 绑定生产资金代理和测试 barrier。 */
    PausedFunds(BalanceTccDebitService delegate, FaultGate gate) {
      this.delegate = delegate;
      this.gate = gate;
    }

    /** 在 Try SQL 完成、外层事务未提交时暂停。 */
    @Override
    public BalanceTccDebitVO tryDebit(BalanceTccDebitTryDTO request) {
      BalanceTccDebitVO response = delegate.tryDebit(request);
      gate.pause("TRY_BEFORE_COMMIT", request.xid, request.branchId);
      return response;
    }

    /** 在 Confirm SQL 完成、外层事务未提交时暂停。 */
    @Override
    public BalanceTccDebitVO confirmDebit(BalanceTccDebitFinalizeDTO request) {
      BalanceTccDebitVO response = delegate.confirmDebit(request);
      gate.pause("CONFIRM_BEFORE_COMMIT", request.xid, request.branchId);
      return response;
    }

    /** 在 Cancel SQL 完成、外层事务未提交时暂停。 */
    @Override
    public BalanceTccDebitVO cancelDebit(BalanceTccDebitFinalizeDTO request) {
      BalanceTccDebitVO response = delegate.cancelDebit(request);
      gate.pause("CANCEL_BEFORE_COMMIT", request.xid, request.branchId);
      return response;
    }
  }

  /** 在 Seata 注册资源之前装饰原资源，只暂停已提交且尚未向框架返回的窗口。 */
  public static final class ResourceFaultProcessor implements BeanPostProcessor, PriorityOrdered {
    /** 一次性故障 barrier。 */
    private final FaultGate gate;

    /** 创建测试资源装饰器。 */
    ResourceFaultProcessor(FaultGate gate) {
      this.gate = gate;
    }

    /** 在普通 Seata auto proxy creator 之前执行。 */
    @Override
    public int getOrder() {
      return HIGHEST_PRECEDENCE;
    }

    /** 保留原资源方法和官方 Fence，只延迟返回。 */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      if (!"balanceTccDebitLocalResource".equals(name)) return bean;
      return new PausedResource((BalanceTccDebitLocalResource) bean, gate);
    }
  }

  /** 测试资源包装仍使用同一 LocalTCC 接口、名称和 XID。 */
  public static class PausedResource implements BalanceTccDebitLocalResource {
    /** 生产资源，内部已使用显式 TransactionTemplate。 */
    private final BalanceTccDebitLocalResource delegate;

    /** 一次性故障 barrier。 */
    private final FaultGate gate;

    /** 绑定生产资源及测试 barrier。 */
    public PausedResource(BalanceTccDebitLocalResource delegate, FaultGate gate) {
      this.delegate = delegate;
      this.gate = gate;
    }

    /** 在冻结提交后、Try 响应前暂停。 */
    @Override
    public boolean prepare(BusinessActionContext context, BalanceTccBranchDTO request) {
      boolean accepted = delegate.prepare(context, request);
      if (accepted) gate.pause("TRY_AFTER_COMMIT", context.getXid(), context.getBranchId());
      return accepted;
    }

    /** 在确认提交后、TC 收到 ACK 前暂停。 */
    @Override
    public boolean commit(BusinessActionContext context) {
      boolean accepted = delegate.commit(context);
      if (accepted) gate.pause("CONFIRM_AFTER_COMMIT", context.getXid(), context.getBranchId());
      return accepted;
    }

    /** 在取消提交后、TC 收到 ACK 前暂停。 */
    @Override
    public boolean rollback(BusinessActionContext context) {
      boolean accepted = delegate.rollback(context);
      if (accepted) gate.pause("CANCEL_AFTER_COMMIT", context.getXid(), context.getBranchId());
      return accepted;
    }
  }

  /** 只在独立测试子 JVM 的专用 profile 下启用，不属于生产构件。 */
  @Configuration
  @Profile("cm15b-rm-child")
  public static class FaultConfiguration {
    /** 创建不依赖事务代理的测试 barrier。 */
    @Bean
    public static FaultGate rmFaultGate() {
      return new FaultGate();
    }

    /** 在生产资金代理外注入提交前暂停点。 */
    @Bean
    @Primary
    public BalanceTccDebitService faultObservedFunds(
        @Qualifier("balanceTccDebitServiceImpl") BalanceTccDebitService delegate, FaultGate gate) {
      return new PausedFunds(delegate, gate);
    }

    /** 保证资源在 Seata 解析前完成测试包装。 */
    @Bean
    public static ResourceFaultProcessor rmFaultResourceProcessor(FaultGate gate) {
      return new ResourceFaultProcessor(gate);
    }
  }
}
