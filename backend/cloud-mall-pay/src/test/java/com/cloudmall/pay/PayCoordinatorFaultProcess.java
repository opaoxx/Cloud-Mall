package com.cloudmall.pay;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.seata.core.context.RootContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** 真实 Pay coordinator 进程级故障辅助器，不增加任何生产 HTTP 映射。 */
public final class PayCoordinatorFaultProcess {
  /** 控制请求的认证请求头。 */
  private static final String CONTROL_KEY_HEADER = "X-Control-Key";

  /** 故障窗口名称。 */
  private static final String BEFORE_COMMIT = "BEFORE_COMMIT";

  private PayCoordinatorFaultProcess() {}

  /** 启动 Pay Spring context 和 loopback 控制服务，随后保持进程存活。 */
  public static void main(String[] arguments) throws Exception {
    Map<String, String> options = parseOptions(arguments);
    int controlPort = Integer.parseInt(required(options, "control-port"));
    String controlKey = required(options, "control-key");
    String faultMode = options.getOrDefault("fault", "NONE");
    if (!"NONE".equals(faultMode)
        && !BEFORE_COMMIT.equals(faultMode)
        && !"BEFORE_RECEIPT".equals(faultMode)) {
      throw new IllegalArgumentException("fault must be NONE, BEFORE_COMMIT or BEFORE_RECEIPT");
    }

    ConfigurableApplicationContext application =
        SpringApplication.run(PayApplication.class, arguments);
    FaultState faultState = new FaultState();
    PayTccCoordinatorServiceImpl coordinator = coordinator(application, faultMode, faultState);
    ObjectMapper objectMapper = application.getBean(ObjectMapper.class);
    ProcessControl control = new ProcessControl(coordinator, objectMapper, controlKey, faultState);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 20);
    server.createContext("/state", control::state);
    server.createContext("/execute", control::execute);
    server.createContext("/recover", control::recover);
    PayTccDeliveryService delivery =
        application.getBeanProvider(PayTccDeliveryService.class).getIfAvailable();
    if (delivery != null) {
      server.createContext(
          "/deliver",
          exchange -> {
            if (!control.authorize(exchange)) return;
            try {
              var request = objectMapper.readTree(exchange.getRequestBody());
              control.respond(exchange, 200, delivery.deliver(request.path("payNo").asText()));
            } catch (Exception failure) {
              control.respond(exchange, 500, Map.of("error", failure.getClass().getSimpleName()));
            }
          });
    }
    server.setExecutor(Executors.newCachedThreadPool(new DaemonThreadFactory()));
    server.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  application.close();
                },
                "pay-coordinator-fault-shutdown"));
    new CountDownLatch(1).await();
  }

  /** 使用真实 Spring beans 创建仅替换决议调用的 coordinator。 */
  private static PayTccCoordinatorServiceImpl coordinator(
      ConfigurableApplicationContext application, String faultMode, FaultState faultState) {
    PayTccTransactionClient delegate = application.getBean(PayTccTransactionClient.class);
    PayTccTransactionClient transactions =
        new FaultTransactionClient(delegate, BEFORE_COMMIT.equals(faultMode), faultState);
    PayTccReceiptService receipts = application.getBean(PayTccReceiptService.class);
    if ("BEFORE_RECEIPT".equals(faultMode)) {
      receipts = new FaultReceiptService(receipts, faultState);
    }
    return new PayTccCoordinatorServiceImpl(
        application.getBean(PayTccExecutionStore.class),
        receipts,
        application.getBean(PayTccBridgeService.class),
        application.getBean(PayTccExecutionEvidence.class),
        transactions);
  }

  /** 解析普通 Spring 参数和控制参数。 */
  private static Map<String, String> parseOptions(String[] arguments) {
    Map<String, String> options = new HashMap<>();
    for (String argument : arguments) {
      if (argument.startsWith("--") && argument.contains("=")) {
        int separator = argument.indexOf('=');
        options.put(argument.substring(2, separator), argument.substring(separator + 1));
      }
    }
    return options;
  }

  /** 获取必需控制参数。 */
  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + name);
    return value;
  }

  /** 进程控制 HTTP 处理器。 */
  private static final class ProcessControl {
    /** 调用真实生产协调器的测试入口。 */
    private final PayTccCoordinatorServiceImpl coordinator;

    /** 复用 Spring 配置的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** 回环控制接口恒定时间校验凭证。 */
    private final byte[] controlKey;

    /** 进程内确定性故障屏障及原 XID。 */
    private final FaultState faultState;

    private ProcessControl(
        PayTccCoordinatorServiceImpl coordinator, ObjectMapper objectMapper, String controlKey) {
      this(coordinator, objectMapper, controlKey, new FaultState());
    }

    private ProcessControl(
        PayTccCoordinatorServiceImpl coordinator,
        ObjectMapper objectMapper,
        String controlKey,
        FaultState faultState) {
      this.coordinator = coordinator;
      this.objectMapper = objectMapper;
      this.controlKey = controlKey.getBytes(StandardCharsets.UTF_8);
      this.faultState = faultState;
    }

    /** 返回 ready、barrier 和原始 XID 状态。 */
    private void state(HttpExchange exchange) throws IOException {
      if (!authorize(exchange)) return;
      Map<String, Object> result = new HashMap<>();
      result.put("ready", true);
      result.put("barrier", faultState.barrierReached.get());
      result.put("xid", faultState.barrierXid.get());
      respond(exchange, 200, result);
    }

    /** 将 JSON Pay dispatch 交给真实 coordinator。 */
    private void execute(HttpExchange exchange) throws IOException {
      if (!authorize(exchange)) return;
      try {
        PayTccDispatchDTO request =
            objectMapper.readValue(exchange.getRequestBody(), PayTccDispatchDTO.class);
        respond(exchange, 200, coordinator.execute(request));
      } catch (Exception failure) {
        respond(exchange, 500, Map.of("error", failure.getClass().getSimpleName()));
      }
    }

    /** 将 JSON payNo 交给真实 coordinator recover。 */
    private void recover(HttpExchange exchange) throws IOException {
      if (!authorize(exchange)) return;
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(exchange.getRequestBody(), Map.class);
        respond(exchange, 200, coordinator.recover(String.valueOf(request.get("payNo"))));
      } catch (Exception failure) {
        respond(exchange, 500, Map.of("error", failure.getClass().getSimpleName()));
      }
    }

    /** 恒定时间校验控制密钥，失败不泄露详细原因。 */
    private boolean authorize(HttpExchange exchange) throws IOException {
      String supplied = exchange.getRequestHeaders().getFirst(CONTROL_KEY_HEADER);
      if (supplied == null
          || !MessageDigest.isEqual(controlKey, supplied.getBytes(StandardCharsets.UTF_8))) {
        respond(exchange, 403, Map.of("error", "forbidden"));
        return false;
      }
      return true;
    }

    /** 序列化控制响应并关闭 exchange。 */
    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
      byte[] payload = objectMapper.writeValueAsBytes(body);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    }
  }

  /** 仅在 commit 前设置 barrier 并阻塞，rollback 始终直通真实 TC。 */
  private static final class FaultTransactionClient implements PayTccTransactionClient {
    /** 真实官方 TC 客户端。 */
    private final PayTccTransactionClient delegate;

    /** 是否在持久决议之后、commit RPC 之前暂停。 */
    private final boolean blockBeforeCommit;

    /** 进程内确定性故障屏障及原 XID。 */
    private final FaultState faultState;

    private FaultTransactionClient(
        PayTccTransactionClient delegate, boolean blockBeforeCommit, FaultState faultState) {
      this.delegate = delegate;
      this.blockBeforeCommit = blockBeforeCommit;
      this.faultState = faultState;
    }

    /** 委托真实 SDK begin。 */
    @Override
    public String begin() throws Exception {
      return delegate.begin();
    }

    /** BEFORE_COMMIT 模式在原 XID commit 前永久等待进程终止。 */
    @Override
    public void commit(String xid) throws Exception {
      if (blockBeforeCommit && faultState.block(xid)) faultState.awaitTermination();
      delegate.commit(xid);
    }

    /** rollback 始终委托真实 SDK。 */
    @Override
    public void rollback(String xid) throws Exception {
      delegate.rollback(xid);
    }
  }

  /** 在 coordinator 已持久化 XID 后、receipt prepare 前阻塞。 */
  private static final class FaultReceiptService implements PayTccReceiptService {
    /** 真实 receipt 唯一认领入口。 */
    private final PayTccReceiptService delegate;

    /** 进程内确定性故障屏障及原 XID。 */
    private final FaultState faultState;

    private FaultReceiptService(PayTccReceiptService delegate, FaultState faultState) {
      this.delegate = delegate;
      this.faultState = faultState;
    }

    /** 捕获原 XID 后等待进程被 root 终止。 */
    @Override
    public PayTccReceiptVO prepare(PayTccDispatchDTO request) {
      String xid = RootContext.getXID();
      if (faultState.block(xid)) faultState.awaitTermination();
      return delegate.prepare(request);
    }

    /** read 直接委托真实 receipt。 */
    @Override
    public PayTccReceiptVO read(String payNo) {
      return delegate.read(payNo);
    }
  }

  /** 供控制面观察故障 barrier 和原 XID 的共享进程状态。 */
  private static final class FaultState {
    /** 故障屏障是否已由原执行线程到达。 */
    private final AtomicBoolean barrierReached = new AtomicBoolean();

    /** 到达屏障时捕获的原 XID，仅供测试审计。 */
    private final AtomicReference<String> barrierXid = new AtomicReference<>();

    /** 首次记录 barrier 及原 XID。 */
    private boolean block(String xid) {
      if (!barrierReached.compareAndSet(false, true)) return false;
      barrierXid.set(xid);
      return true;
    }

    /** 无限等待直到进程被外部终止。 */
    private void awaitTermination() {
      while (true) {
        try {
          Thread.sleep(60_000L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  /** 创建 daemon 控制线程，避免辅助进程阻止关闭。 */
  private static final class DaemonThreadFactory implements ThreadFactory {
    private int sequence;

    /** 创建命名 daemon 线程。 */
    @Override
    public Thread newThread(Runnable task) {
      Thread worker = new Thread(task, "pay-coordinator-control-" + ++sequence);
      worker.setDaemon(true);
      return worker;
    }
  }
}
