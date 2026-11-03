package com.cloudmall.order;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.service.impl.OrderOutboxDispatcher;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 真实 OrderApplication 的创建观察入口；不注册可被普通应用扫描的配置或业务替身。 */
public final class OrderCreationFaultProcess {
  /** 工具入口不创建业务对象。 */
  private OrderCreationFaultProcess() {}

  /** 启动原应用并透传原 Mapper、调度器、Feign 和 Rabbit 回调，只暂停 A 的提交前同步点。 */
  public static void main(String[] arguments) throws Exception {
    // 1. 启动前固定两个原幂等键与回环控制凭据，不接受动态方法或 SQL 指令。
    State state =
        new State(option(arguments, "creation-key-a"), option(arguments, "creation-key-b"));
    byte[] controlKey = option(arguments, "control-key").getBytes(StandardCharsets.UTF_8);
    int port = Integer.parseInt(option(arguments, "control-port"));
    SpringApplication launcher = new SpringApplication(OrderApplication.class);
    launcher.addInitializers(
        context -> context.getBeanFactory().addBeanPostProcessor(new Observers(state)));
    ConfigurableApplicationContext application = launcher.run(arguments);
    ExecutorService controls = Executors.newFixedThreadPool(2);
    HttpServer server = null;
    try {
      // 2. 只开放有界状态读取和一次 release，不暴露 recover、资金或数据库命令。
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 10);
      server.createContext("/", exchange -> control(exchange, controlKey, state));
      server.setExecutor(controls);
      server.start();
      state.ready.set(true);
      HttpServer ownedServer = server;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    state.release.countDown();
                    ownedServer.stop(0);
                    controls.shutdownNow();
                    application.close();
                    try {
                      if (!controls.awaitTermination(15, TimeUnit.SECONDS))
                        state.failure("CONTROL_WORKER_UNRESOLVED");
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      state.failure("CONTROL_CLOSE_INTERRUPTED");
                    }
                  },
                  "order-create-observer-close"));
      // 3. 生命周期由精确 owned PID 收尾；业务异常始终沿原调用链传播。
      new CountDownLatch(1).await();
    } catch (Throwable failure) {
      state.release.countDown();
      try {
        if (server != null) server.stop(0);
      } catch (Throwable closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      controls.shutdownNow();
      try {
        if (!controls.awaitTermination(15, TimeUnit.SECONDS))
          failure.addSuppressed(new IllegalStateException("CONTROL_WORKER_UNRESOLVED"));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        failure.addSuppressed(interrupted);
      }
      try {
        application.close();
      } catch (Throwable closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private static String option(String[] arguments, String name) {
    String prefix = "--" + name + "=";
    String result = null;
    for (String argument : arguments)
      if (argument.startsWith(prefix)) {
        if (result != null) throw new IllegalArgumentException("DUPLICATE_CONTROL_OPTION");
        result = argument.substring(prefix.length());
      }
    if (result == null || result.isBlank() || result.length() > 128)
      throw new IllegalArgumentException("CONTROL_OPTION_REQUIRED");
    return result;
  }

  private static void control(HttpExchange exchange, byte[] key, State state) throws IOException {
    String supplied = exchange.getRequestHeaders().getFirst("X-Control-Key");
    boolean authorized =
        supplied != null
            && supplied.length() <= 128
            && MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8));
    int status = 200;
    Object value;
    if (!authorized) {
      status = 403;
      value = Map.of("reason", "CONTROL_FORBIDDEN");
    } else if (exchange.getRequestURI().getRawQuery() != null
        || exchange.getRequestBody().readNBytes(1025).length > 1024) {
      status = 400;
      value = Map.of("reason", "CONTROL_BOUND");
    } else if ("GET".equals(exchange.getRequestMethod())
        && "/state".equals(exchange.getRequestURI().getPath())) {
      value = state.snapshot();
    } else if ("POST".equals(exchange.getRequestMethod())
        && "/release".equals(exchange.getRequestURI().getPath())
        && state.beforeCommitReady.get()
        && state.releaseCalls.compareAndSet(0, 1)) {
      state.release.countDown();
      value = Map.of("released", true);
    } else {
      status = 409;
      value = Map.of("reason", "CONTROL_ROUTE_OR_STATE_REJECTED");
    }
    byte[] body = state.json.writeValueAsBytes(value);
    if (body.length > 131072) {
      state.failure("CONTROL_RESPONSE_BOUND");
      body = "{\"reason\":\"CONTROL_RESPONSE_BOUND\"}".getBytes(StandardCharsets.UTF_8);
      status = 503;
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    } finally {
      exchange.close();
    }
  }

  /** 不改变目标返回值与异常；所有观察包裹原对象，不新建 Mapper 或业务实现。 */
  private static final class Observers implements BeanPostProcessor {
    /** 本进程唯一的有界观察容器。 */
    private final State state;

    private Observers(State state) {
      this.state = state;
    }

    /** 保留原 Bean 的公开契约，只为固定方法追加透传观察。 */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
      if (bean instanceof RabbitTemplate template) {
        template.addBeforePublishPostProcessors(new PublishObserver(state, template));
        state.publisherInstalled.set(true);
      }
      if (!(bean instanceof OrderReservationIntentMapper)
          && !(bean instanceof OrderOutboxMapper)
          && !(bean instanceof OrderReservationRecovery)
          && !(bean instanceof OrderOutboxDispatcher)
          && !(bean instanceof StockClient)) return bean;
      ProxyFactory proxy = new ProxyFactory(bean);
      proxy.setProxyTargetClass(!(bean instanceof StockClient));
      proxy.addAdvice((MethodInterceptor) invocation -> observe(bean, invocation));
      return proxy.getProxy();
    }

    private Object observe(Object bean, MethodInvocation invocation) throws Throwable {
      String method = invocation.getMethod().getName();
      boolean recovery =
          bean instanceof OrderReservationRecovery && method.equals("recoverStaleReservations");
      boolean dispatcher =
          bean instanceof OrderOutboxDispatcher && method.equals("dispatchPending");
      if (recovery || dispatcher) state.jobStarted(recovery);
      try {
        Object result = invocation.proceed();
        if (bean instanceof OrderReservationIntentMapper) {
          if (method.equals("insertIfAbsent") && Integer.valueOf(1).equals(result))
            state.inserted((OrderReservationIntentPO) invocation.getArguments()[0]);
          if (method.equals("markCommitted") && Integer.valueOf(1).equals(result))
            state.prepareBarrier((String) invocation.getArguments()[0]);
          if (method.equals("findRecoverable") && result instanceof List<?> rows)
            state.emptyQuery(true, rows.isEmpty());
          if (method.equals("claimRecovery") && Integer.valueOf(1).equals(result))
            state.bEvent("CLAIM_RECOVERY", invocation.getArguments(), result);
          if (method.equals("markReleased") && Integer.valueOf(1).equals(result))
            state.bEvent("MARK_RELEASED", invocation.getArguments(), result);
        }
        if (bean instanceof OrderOutboxMapper
            && method.equals("findPendingOutbox")
            && result instanceof List<?> rows) state.emptyQuery(false, rows.isEmpty());
        if (bean instanceof StockClient
            && (method.equals("reservation") || method.equals("rollback")))
          state.bEvent(
              "STOCK_" + method.toUpperCase(java.util.Locale.ROOT),
              invocation.getArguments(),
              result);
        if (recovery || dispatcher) state.jobReturned(recovery);
        return result;
      } catch (Throwable failure) {
        if (!(bean instanceof StockClient
            && method.equals("reserve")
            && state.expectedReserveFailure(invocation.getArguments(), failure)))
          state.failure("ORIGINAL_" + method + "_FAILED:" + failure.getClass().getName());
        throw failure;
      } finally {
        if (recovery || dispatcher) state.jobEnded(recovery);
      }
    }
  }

  /** 与原 MQ 观察方式一致，只追加原 future callback，返回同一 Message，不占用确认回调。 */
  private static final class PublishObserver implements MessagePostProcessor {
    /** 原状态与原 RabbitTemplate，只读 mandatory 与已完成的关联回执。 */
    private final State state;

    private final RabbitTemplate template;

    private PublishObserver(State state, RabbitTemplate template) {
      this.state = state;
      this.template = template;
    }

    /** 不改变原消息。 */
    @Override
    public Message postProcessMessage(Message message) {
      return message;
    }

    /** 原业务 MPP 已完成后的发送观察；不替换 CorrelationData 或 future。 */
    @Override
    public Message postProcessMessage(
        Message message, Correlation correlation, String exchange, String route) {
      if (correlation instanceof CorrelationData data && state.matches(message)) {
        Map<String, Object> trace =
            state.sent(message, data, exchange, route, template.isMandatoryFor(message));
        state.callbacks.incrementAndGet();
        data.getFuture()
            .addCallback(
                confirm -> {
                  try {
                    state.confirmed(trace, data, confirm);
                  } finally {
                    state.callbacks.decrementAndGet();
                  }
                },
                failure -> {
                  try {
                    state.failure("PUBLISH_CONFIRM_FAILED:" + failure.getClass().getName());
                  } finally {
                    state.callbacks.decrementAndGet();
                  }
                });
      }
      return message;
    }
  }

  /** 原身份仅内存匹配，所有控制输出只包含指纹、状态、计数和安全时间。 */
  private static final class State {
    /** A/B 的原 HTTP 幂等键，不序列化。 */
    private final String keyA, keyB;

    /** 原生成订单号仅内存保存。 */
    private String orderA, orderB;

    /** 安全 JSON writer，不承载业务替代实现。 */
    private final ObjectMapper json = new ObjectMapper();

    /** 只允许 A 注册一次真实提交屏障。 */
    private final AtomicBoolean barrierInstalled = new AtomicBoolean();

    /** 控制端就绪、真实 beforeCommit 与原 publisher 已安装。 */
    private final AtomicBoolean ready = new AtomicBoolean(),
        beforeCommitReady = new AtomicBoolean(),
        publisherInstalled = new AtomicBoolean();

    /** A 提交屏障；超时沿原事务失败，不自动改写业务成功。 */
    private final CountDownLatch release = new CountDownLatch(1);

    /** release 次数与实际原提交、完成状态。 */
    private final AtomicInteger releaseCalls = new AtomicInteger();

    /** 未退出的原 job 与关联 callback。 */
    private final AtomicInteger recoveryActive = new AtomicInteger(),
        dispatcherActive = new AtomicInteger(),
        callbacks = new AtomicInteger();

    /** 有界安全观察，最多 100 条，溢出固定失败。 */
    private final List<Map<String, Object>> published = new ArrayList<>(),
        bEvents = new ArrayList<>();

    /** 原异常只保类型/固定标签，未知绝不伪装为空结果。 */
    private final List<String> failures = new ArrayList<>();

    /** 同步上下文与首次真实空扫描完成事实。 */
    private boolean transactionActive,
        synchronizationActive,
        beforeCommitReadOnly,
        firstRecoveryEmptyReturned,
        firstDispatcherEmptyReturned;

    /** 每个原 job 执行线程对应的空查询事实。 */
    private final ThreadLocal<Boolean> recoveryEmpty = new ThreadLocal<>(),
        dispatcherEmpty = new ThreadLocal<>();

    /** 原 beforeCommit 注册及完成，按同一线程核验。 */
    private long registeringThread;

    /** 原事务 completion 状态，未完成保持 null。 */
    private Integer completionStatus;

    /** 原调度方法实际返回次数。 */
    private int recoveryReturns, dispatcherReturns;

    /** 明确由 B 原 reserve 抛回的故障次数；仍将同一异常交回生产链处理。 */
    private int expectedBReserveFailures;

    /** 预期原异常的安全类型，不保留可能含请求身份的消息。 */
    private String expectedBReserveFailureClass;

    /** 观察条目超过界限后永久失败，不静默丢弃。 */
    private boolean boundExceeded;

    private State(String keyA, String keyB) {
      this.keyA = keyA;
      this.keyB = keyB;
    }

    private synchronized void inserted(OrderReservationIntentPO intent) {
      if (keyA.equals(intent.idempotencyKey)) orderA = intent.orderNo;
      if (keyB.equals(intent.idempotencyKey)) orderB = intent.orderNo;
    }

    private synchronized boolean expectedReserveFailure(Object[] arguments, Throwable failure) {
      var attributes = RequestContextHolder.getRequestAttributes();
      if (!(attributes instanceof ServletRequestAttributes request)
          || !keyB.equals(request.getRequest().getHeader("Idempotency-Key"))
          || arguments.length != 1
          || !(arguments[0] instanceof StockClient.Reservation reservation)
          || orderB == null
          || !orderB.equals(reservation.orderNo())) return false;
      expectedBReserveFailures++;
      expectedBReserveFailureClass = failure.getClass().getName();
      bEvent("STOCK_RESERVE_RESPONSE_FAILURE", new Object[] {orderB}, null);
      return true;
    }

    private void prepareBarrier(String orderNo) {
      var attributes = RequestContextHolder.getRequestAttributes();
      if (!(attributes instanceof ServletRequestAttributes request)
          || !keyA.equals(request.getRequest().getHeader("Idempotency-Key"))) return;
      synchronized (this) {
        if (!orderNo.equals(orderA) || !barrierInstalled.compareAndSet(false, true))
          throw new IllegalStateException("ORIGINAL_A_BARRIER_IDENTITY_CHANGED");
        transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
        synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        registeringThread = Thread.currentThread().getId();
        if (!transactionActive
            || !synchronizationActive
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
          throw new IllegalStateException("ORIGINAL_CREATE_TRANSACTION_REQUIRED");
      }
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            /** SQL 已真实执行，但事务仍未提交；外连接验证后只允许一次 release。 */
            @Override
            public void beforeCommit(boolean readOnly) {
              synchronized (State.this) {
                beforeCommitReadOnly = readOnly;
                if (readOnly || Thread.currentThread().getId() != registeringThread)
                  throw new IllegalStateException("ORIGINAL_COMMIT_THREAD_CHANGED");
                beforeCommitReady.set(true);
              }
              try {
                if (!release.await(45, TimeUnit.SECONDS))
                  throw new IllegalStateException("CREATE_COMMIT_BARRIER_TIMEOUT");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("CREATE_COMMIT_BARRIER_INTERRUPTED", interrupted);
              }
            }

            /** 实际 Spring completion，未用 HTTP 返回值猜测事务已提交。 */
            @Override
            public void afterCompletion(int status) {
              synchronized (State.this) {
                completionStatus = status;
              }
            }
          });
    }

    private void jobStarted(boolean recovery) {
      (recovery ? recoveryActive : dispatcherActive).incrementAndGet();
      (recovery ? recoveryEmpty : dispatcherEmpty).set(false);
    }

    private void emptyQuery(boolean recovery, boolean empty) {
      (recovery ? recoveryEmpty : dispatcherEmpty).set(empty);
    }

    private synchronized void jobReturned(boolean recovery) {
      if (recovery) {
        recoveryReturns++;
        firstRecoveryEmptyReturned |= Boolean.TRUE.equals(recoveryEmpty.get());
      } else {
        dispatcherReturns++;
        firstDispatcherEmptyReturned |= Boolean.TRUE.equals(dispatcherEmpty.get());
      }
    }

    private void jobEnded(boolean recovery) {
      (recovery ? recoveryEmpty : dispatcherEmpty).remove();
      (recovery ? recoveryActive : dispatcherActive).decrementAndGet();
    }

    private synchronized void bEvent(String type, Object[] arguments, Object result) {
      if (orderB == null || arguments.length == 0 || !orderB.equals(arguments[0])) return;
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("type", type);
      event.put("observedAt", Instant.now().toString());
      event.put("orderNoSha256", hash(orderB));
      if (result instanceof ApiResponse<?> response) event.put("responseCode", response.code);
      if (result instanceof ApiResponse<?> response
          && response.data instanceof com.cloudmall.order.domain.vo.StockReservationStatusVO status)
        event.put("reservationStatus", status.status);
      if (type.equals("STOCK_ROLLBACK")) {
        if (arguments.length > 1 && arguments[1] instanceof StockClient.Action action) {
          event.put("operationKeySha256", hash(action.operationKey()));
          event.put("operationKeySource", "OBSERVED_ACTION_ARGUMENT");
        } else if (arguments.length == 1) {
          event.put("operationKeySha256", hash(orderB + ":ROLLBACK"));
          event.put("operationKeySource", "ORIGINAL_DEFAULT_METHOD_CONTRACT_AND_PERSISTED_FLOW");
        }
      }
      if (bEvents.size() >= 100) failure("B_EVENT_BOUND_EXCEEDED");
      else bEvents.add(event);
    }

    private synchronized boolean matches(Message message) {
      try {
        var body = json.readTree(message.getBody());
        return orderA != null
            && ("order-timeout:" + orderA).equals(body.path("eventId").textValue())
            && orderA.equals(body.path("orderNo").textValue())
            && "ORDER_TIMEOUT".equals(body.path("eventType").textValue());
      } catch (IOException malformed) {
        failure("PUBLISH_BODY_UNREADABLE");
        return false;
      }
    }

    private synchronized Map<String, Object> sent(
        Message message, CorrelationData data, String exchange, String route, boolean mandatory) {
      if (published.size() >= 100) throw new IllegalStateException("PUBLISH_EVENT_BOUND_EXCEEDED");
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("bodySha256", hash(message.getBody()));
      try {
        var envelope = json.readTree(message.getBody());
        java.util.SortedMap<String, com.fasterxml.jackson.databind.JsonNode> payload =
            new java.util.TreeMap<>();
        envelope
            .path("payload")
            .fields()
            .forEachRemaining(entry -> payload.put(entry.getKey(), entry.getValue()));
        trace.put("payloadSha256", hash(json.writeValueAsBytes(payload)));
        trace.put("occurredAt", envelope.path("occurredAt").textValue());
        trace.put("expireAt", envelope.path("expireAt").textValue());
      } catch (IOException malformed) {
        failure("PUBLISH_ENVELOPE_UNREADABLE");
        throw new IllegalStateException("PUBLISH_ENVELOPE_UNREADABLE", malformed);
      }
      trace.put("eventIdSha256", hash("order-timeout:" + orderA));
      trace.put("orderNoSha256", hash(orderA));
      trace.put("correlationSha256", hash(data.getId()));
      trace.put("exchange", exchange);
      trace.put("routingKey", route);
      trace.put("expiration", message.getMessageProperties().getExpiration());
      trace.put("mandatory", mandatory);
      trace.put("confirmObserved", false);
      trace.put("confirmAck", false);
      trace.put("returned", false);
      trace.put("sentAt", Instant.now().toString());
      published.add(trace);
      return trace;
    }

    private synchronized void confirmed(
        Map<String, Object> trace, CorrelationData data, CorrelationData.Confirm confirm) {
      trace.put("confirmObserved", true);
      trace.put("confirmAck", confirm != null && confirm.isAck());
      trace.put("returned", data.getReturned() != null);
      trace.put("confirmedAt", Instant.now().toString());
    }

    private synchronized void failure(String category) {
      if (failures.size() < 100) failures.add(category);
      else boundExceeded = true;
    }

    private synchronized Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ready", ready.get());
      result.put("pid", ProcessHandle.current().pid());
      result.put(
          "startedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());
      result.put("publisherInstalled", publisherInstalled.get());
      result.put("beforeCommitReady", beforeCommitReady.get());
      result.put("transactionActiveAtRegistration", transactionActive);
      result.put("synchronizationActiveAtRegistration", synchronizationActive);
      result.put("beforeCommitReadOnly", beforeCommitReadOnly);
      result.put("completionStatus", completionStatus);
      result.put("releaseCalls", releaseCalls.get());
      result.put("aOrderNoSha256", orderA == null ? null : hash(orderA));
      result.put("bOrderNoSha256", orderB == null ? null : hash(orderB));
      result.put("firstRecoveryEmptyReturned", firstRecoveryEmptyReturned);
      result.put("firstDispatcherEmptyReturned", firstDispatcherEmptyReturned);
      result.put("recoveryReturns", recoveryReturns);
      result.put("dispatcherReturns", dispatcherReturns);
      result.put("activeRecovery", recoveryActive.get());
      result.put("activeDispatcher", dispatcherActive.get());
      result.put("activeCallbacks", callbacks.get());
      result.put("published", published.stream().map(LinkedHashMap::new).toList());
      result.put("bEvents", bEvents.stream().map(LinkedHashMap::new).toList());
      result.put("failures", List.copyOf(failures));
      result.put("expectedBReserveFailures", expectedBReserveFailures);
      result.put("expectedBReserveFailureClass", expectedBReserveFailureClass);
      result.put("boundExceeded", boundExceeded);
      result.put("automaticActionsAllowed", false);
      return result;
    }
  }

  private static String hash(String text) {
    return hash(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
