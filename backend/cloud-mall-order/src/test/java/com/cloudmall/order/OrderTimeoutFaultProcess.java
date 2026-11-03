package com.cloudmall.order;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageAckListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ReflectionUtils;

/** 仅供隔离验收的真实 Order 进程；被动记录原发布和消费，支持提交后 ACK 前硬停。 */
public final class OrderTimeoutFaultProcess {
  /** 与既有测试进程一致的回环控制认证头。 */
  private static final String CONTROL_KEY_HEADER = "X-Control-Key";

  /** 唯一允许阻塞的测试故障模式。 */
  private static final String AFTER_TIMEOUT_COMMIT = "AFTER_TIMEOUT_COMMIT";

  private OrderTimeoutFaultProcess() {}

  /** 启动标准 Order Spring context，安装仅测试观察器并启动原超时消费者。 */
  public static void main(String[] arguments) throws Exception {
    // 1. 在任何 Spring 消费者创建前冻结目标，防止启动后才设置故障窗口。
    Map<String, String> options = parseOptions(arguments);
    int controlPort = Integer.parseInt(required(options, "control-port"));
    String controlKey = required(options, "control-key");
    String fault = options.getOrDefault("fault", "NONE");
    if (!"NONE".equals(fault) && !AFTER_TIMEOUT_COMMIT.equals(fault)) {
      throw new IllegalArgumentException("fault must be NONE or AFTER_TIMEOUT_COMMIT");
    }
    TraceState state = new TraceState(required(options, "target-order-no"), fault);
    SpringApplication launcher = new SpringApplication(OrderApplication.class);
    launcher.addInitializers(
        context -> context.getBeanFactory().addBeanPostProcessor(new ObserverBeans(state)));
    ConfigurableApplicationContext application = launcher.run(arguments);

    // 2. 未启动容器和既有真实重试链均须符合评审契约，控制面不接受业务指令。
    HttpServer server = null;
    try {
      SimpleMessageListenerContainer container = state.requiredContainer();
      if (!state.publisherInstalled.get()
          || !Boolean.TRUE.equals(
              application
                  .getEnvironment()
                  .getProperty(
                      "spring.rabbitmq.listener.simple.retry.enabled", Boolean.class, false))
          || application
                  .getEnvironment()
                  .getProperty(
                      "spring.rabbitmq.listener.simple.retry.max-attempts", Integer.class, 3)
              != 3) {
        throw new IllegalStateException("original publisher observer/retry configuration missing");
      }
      ProcessControl control =
          new ProcessControl(state, application.getBean(ObjectMapper.class), controlKey);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 20);
      server.createContext("/state", control::read);
      server.createContext("/events", control::read);
      server.setExecutor(Executors.newCachedThreadPool(new DaemonThreadFactory()));
      server.start();
      HttpServer runningServer = server;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    runningServer.stop(0);
                    application.close();
                  },
                  "order-timeout-fault-shutdown"));

      // 3. 只启动仍指向原 consumeTimeout(Message) 代理的目标容器，不手工 ACK。
      container.start();
      state.ready.set(true);
      new CountDownLatch(1).await();
    } catch (Throwable failure) {
      if (server != null) server.stop(0);
      application.close();
      throw failure;
    }
  }

  /** 保留普通 Spring 参数，仅读取本测试 main 的显式选项。 */
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

  /** 缺目标或控制参数时在启动消费者前失败。 */
  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + name);
    return value;
  }

  /** 只读访问固定版本中未公开的链配置，不替换原生产 factory 或 delegate。 */
  private static Object field(Object target, String name) {
    Field member = ReflectionUtils.findField(target.getClass(), name);
    if (member == null) throw new IllegalStateException("required field missing: " + name);
    ReflectionUtils.makeAccessible(member);
    return ReflectionUtils.getField(member, target);
  }

  /** 只读访问固定版本容器的 protected 配置。 */
  private static Object setting(Object target, String name) {
    Method method = ReflectionUtils.findMethod(target.getClass(), name);
    if (method == null) throw new IllegalStateException("required setting missing: " + name);
    ReflectionUtils.makeAccessible(method);
    return ReflectionUtils.invokeMethod(method, target);
  }

  /** 所有身份和原消息字节均一次投影为小写不可逆摘要。 */
  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** 安全投影单个身份；从不输出原 event、operation 或 consumer 标识。 */
  private static String fingerprint(String value) {
    return sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
  }

  /** 在 Spring 完成原 bean 配置后追加观察器，不改变原消息回调和 factory。 */
  private static final class ObserverBeans implements BeanPostProcessor {
    /** 本进程唯一目标与有界证据存储。 */
    private final TraceState state;

    private ObserverBeans(TraceState state) {
      this.state = state;
    }

    /** 追加 pass-through publisher 观察器及初始化前容器 customizer。 */
    @Override
    @SuppressWarnings("unchecked")
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (bean instanceof RabbitTemplate template && "rabbitTemplate".equals(beanName)) {
        template.addBeforePublishPostProcessors(new PublishObserver(state, template));
        state.publisherInstalled.set(true);
      }
      if (bean instanceof SimpleRabbitListenerContainerFactory factory
          && "rabbitListenerContainerFactory".equals(beanName)) {
        ContainerCustomizer<SimpleMessageListenerContainer> original =
            (ContainerCustomizer<SimpleMessageListenerContainer>)
                field(factory, "containerCustomizer");
        factory.setContainerCustomizer(
            container -> {
              if (original != null) original.configure(container);
              if (Arrays.asList(container.getQueueNames())
                  .contains(OrderMessagingConfiguration.TIMEOUT_DLQ)) {
                install(container);
              }
            });
      }
      return bean;
    }

    /** 原 factory 已配置好链而 registry 尚未 afterPropertiesSet；原 advice 原序保留。 */
    private void install(SimpleMessageListenerContainer container) {
      Advice[] original = (Advice[]) setting(container, "getAdviceChain");
      if (container.isRunning()
          || container.isAutoStartup()
          || container.getAcknowledgeMode() != AcknowledgeMode.AUTO
          || !Boolean.FALSE.equals(setting(container, "isDefaultRequeueRejected"))
          || !Integer.valueOf(1).equals(setting(container, "getPrefetchCount"))
          || !Integer.valueOf(1).equals(field(container, "concurrentConsumers"))
          || !Integer.valueOf(1).equals(field(container, "batchSize"))) {
        throw new IllegalStateException("requires stopped original AUTO single-message consumer");
      }
      List<RetryOperationsInterceptor> retries =
          Arrays.stream(original)
              .filter(RetryOperationsInterceptor.class::isInstance)
              .map(RetryOperationsInterceptor.class::cast)
              .toList();
      if (retries.size() != 1
          || !(field(retries.get(0), "retryOperations") instanceof RetryTemplate retryTemplate)
          || !(field(retryTemplate, "retryPolicy") instanceof SimpleRetryPolicy policy)
          || policy.getMaxAttempts() != 3) {
        throw new IllegalStateException("original three-attempt retry policy missing");
      }
      Advice[] observed = new Advice[original.length + 1];
      observed[0] = (MethodInterceptor) state::invoke;
      System.arraycopy(original, 0, observed, 1, original.length);
      container.setAdviceChain(observed);
      Advice[] retained = (Advice[]) setting(container, "getAdviceChain");
      for (int index = 0; index < original.length; index++) {
        if (retained[index + 1] != original[index]) {
          throw new IllegalStateException("original retry/advice identity changed");
        }
      }
      MessageAckListener originalAck =
          (MessageAckListener) setting(container, "getMessageAckListener");
      container.setMessageAckListener(
          (success, tag, failure) -> {
            state.ack(success, tag, failure);
            if (originalAck != null) originalAck.onComplete(success, tag, failure);
          });
      state.originalAdviceClasses =
          Arrays.stream(original).map(advice -> advice.getClass().getName()).toList();
      if (!state.container.compareAndSet(null, container)) {
        throw new IllegalStateException("multiple timeout containers would invalidate target");
      }
    }
  }

  /** 对真实 dispatcher 的原 Message/CorrelationData 追加无副作用发布观察。 */
  private static final class PublishObserver implements MessagePostProcessor {
    /** 本进程证据状态。 */
    private final TraceState state;

    /** 原生产 template，仅查询 mandatory 标志。 */
    private final RabbitTemplate template;

    private PublishObserver(TraceState state, RabbitTemplate template) {
      this.state = state;
      this.template = template;
    }

    /** 简化调用保持原 Message 对象。 */
    @Override
    public Message postProcessMessage(Message message) {
      return message;
    }

    /** 观察四参数发布入口，既不替换原 future，也不抢占原 returns/confirm callback。 */
    @Override
    public Message postProcessMessage(
        Message message, Correlation correlation, String exchange, String routingKey) {
      if (state.matches(message) && correlation instanceof CorrelationData data) {
        Map<String, Object> trace =
            state.sent(message, data, exchange, routingKey, template.isMandatoryFor(message));
        if (trace != null) {
          data.getFuture()
              .addCallback(
                  confirm -> state.confirmed(trace, data, confirm),
                  failure -> state.confirmFailed(trace, failure));
        }
      }
      return message;
    }
  }

  /** 只存白名单标量和摘要，所有事件更新及快照在同一监视器内完成。 */
  private static final class TraceState {
    /** 单进程证据条目上限，溢出保留明确缺证信号。 */
    private static final int MAX_EVENTS = 100;

    /** 固定目标订单，仅用于内存匹配，不写入响应。 */
    private final String targetOrderNo;

    /** 固定原 outbox eventId，仅用于内存匹配。 */
    private final String targetEventId;

    /** 已冻结故障模式。 */
    private final String fault;

    /** 仅被动解析事件身份的 JSON reader。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 唯一原超时容器。 */
    private final AtomicReference<SimpleMessageListenerContainer> container =
        new AtomicReference<>();

    /** 发布观察器是否已在原 template 上安装。 */
    private final AtomicBoolean publisherInstalled = new AtomicBoolean();

    /** 目标容器启动入口已返回。 */
    private final AtomicBoolean ready = new AtomicBoolean();

    /** 屏障只允许第一次成功的目标消费到达。 */
    private final AtomicBoolean blocked = new AtomicBoolean();

    /** 有界原发送记录。 */
    private final List<Map<String, Object>> published = new ArrayList<>();

    /** 有界目标 receipt、事务返回及本地 ACK 记录。 */
    private final List<Map<String, Object>> deliveries = new ArrayList<>();

    /** 同一消费线程的尚待本地 AUTO ACK 观察的精确 delivery。 */
    private final ThreadLocal<Map<String, Object>> pendingAck = new ThreadLocal<>();

    /** 原 factory advice 的实际类名，不输出任何消息内容。 */
    private volatile List<String> originalAdviceClasses = List.of();

    /** 进程内各观测点的总序号。 */
    private long sequence;

    /** 因条目上限未记录的数量，非零时不能声称证据完整。 */
    private long droppedEvents;

    private TraceState(String targetOrderNo, String fault) {
      this.targetOrderNo = targetOrderNo;
      this.targetEventId = "order-timeout:" + targetOrderNo;
      this.fault = fault;
    }

    /** 目标之外或格式未知时直通原 handler，解析失败不能制造新的重试。 */
    private boolean matches(Message message) {
      try {
        JsonNode event =
            mapper
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(message.getBody());
        return event != null
            && event.isObject()
            && event.path("eventId").isTextual()
            && targetEventId.equals(event.path("eventId").textValue())
            && event.path("orderNo").isTextual()
            && targetOrderNo.equals(event.path("orderNo").textValue())
            && "ORDER_TIMEOUT".equals(event.path("eventType").textValue());
      } catch (IOException | RuntimeException unknown) {
        return false;
      }
    }

    /** 原代理正常返回后才观察事务；永久屏障绝不因 interrupt 返回给 ACK 容器。 */
    private Object invoke(MethodInvocation invocation) throws Throwable {
      // 1. 仅观察精确目标 DLQ 的原 Message，其他方法和格式保持原调用语义。
      Message message =
          Arrays.stream(invocation.getArguments())
              .filter(Message.class::isInstance)
              .map(Message.class::cast)
              .findFirst()
              .orElse(null);
      if (message == null
          || !OrderMessagingConfiguration.TIMEOUT_DLQ.equals(
              message.getMessageProperties().getConsumerQueue())
          || !matches(message)) {
        return invocation.proceed();
      }
      Map<String, Object> trace = received(message, invocation);
      if (trace != null) pendingAck.set(trace);
      Object result;
      try {
        result = invocation.proceed();
      } catch (Throwable failure) {
        failed(trace, failure);
        throw failure;
      }
      // 2. 原 @Transactional 代理已返回；活跃事务时只报告缺证，绝不持锁等待。
      boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
      boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
      boolean shouldBlock =
          trace != null
              && !transactionActive
              && !synchronizationActive
              && AFTER_TIMEOUT_COMMIT.equals(fault)
              && blocked.compareAndSet(false, true);
      returned(trace, transactionActive, synchronizationActive, shouldBlock);
      // 3. 不释放、不 ACK、不 reject；只有父夹具的精确进程 hard kill 结束窗口。
      if (shouldBlock) {
        CountDownLatch neverReleased = new CountDownLatch(1);
        while (true) {
          try {
            neverReleased.await();
          } catch (InterruptedException ignored) {
            // 中断不是故障窗口的释放授权，也不能变成 listener 异常/retry/reject。
          }
        }
      }
      return result;
    }

    /** 基础安全标量；原消息正文、eventId 和 orderNo 只保留 SHA。 */
    private synchronized Map<String, Object> newTrace(Message message) {
      if (published.size() + deliveries.size() >= MAX_EVENTS) {
        droppedEvents++;
        return null;
      }
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("processSequence", ++sequence);
      trace.put("observedNanoTime", System.nanoTime());
      trace.put("processPid", ProcessHandle.current().pid());
      trace.put("bodySha256", sha256(message.getBody()));
      trace.put("eventIdSha256", fingerprint(targetEventId));
      trace.put("orderNoSha256", fingerprint(targetOrderNo));
      return trace;
    }

    /** 发布前记录原 body、路由和 correlation 指纹，返回同一 Message 由调用方完成。 */
    private synchronized Map<String, Object> sent(
        Message message,
        CorrelationData data,
        String exchange,
        String routingKey,
        Boolean mandatory) {
      Map<String, Object> trace = newTrace(message);
      if (trace == null) return null;
      trace.put("sequence", published.size() + 1);
      trace.put("sentAt", Instant.now().toString());
      trace.put("exchange", exchange);
      trace.put("routingKey", routingKey);
      trace.put("correlationSha256", fingerprint(data.getId()));
      trace.put("mandatory", Boolean.TRUE.equals(mandatory));
      trace.put("confirmObserved", false);
      trace.put("confirmAck", false);
      trace.put("returned", false);
      published.add(trace);
      return trace;
    }

    /** 原 future 完成时只读其 confirm 和 Rabbit 已关联的 returned Message。 */
    private synchronized void confirmed(
        Map<String, Object> trace, CorrelationData data, CorrelationData.Confirm confirm) {
      trace.put("confirmSequence", ++sequence);
      trace.put("confirmedAt", Instant.now().toString());
      trace.put("confirmObserved", true);
      trace.put("confirmAck", confirm != null && confirm.isAck());
      var returned = data.getReturned();
      trace.put("returned", returned != null);
      if (returned != null) {
        trace.put("returnedBodySha256", sha256(returned.getMessage().getBody()));
        trace.put("returnedReplyCode", returned.getReplyCode());
        trace.put("returnedExchange", returned.getExchange());
        trace.put("returnedRoutingKey", returned.getRoutingKey());
      }
    }

    /** 只记录 future 失败类型，不泄漏 exception 中的原消息或连接地址。 */
    private synchronized void confirmFailed(Map<String, Object> trace, Throwable failure) {
      trace.put("confirmFailureSequence", ++sequence);
      trace.put("confirmFailureAt", Instant.now().toString());
      trace.put("confirmFailureClass", failure.getClass().getName());
    }

    /** 原 handler 调用前记录 broker 实际给出的 delivery 属性。 */
    private synchronized Map<String, Object> received(
        Message message, MethodInvocation invocation) {
      Map<String, Object> trace = newTrace(message);
      if (trace == null) return null;
      trace.put("sequence", deliveries.size() + 1);
      var properties = message.getMessageProperties();
      trace.put("receivedAt", Instant.now().toString());
      trace.put("deliveryTag", properties.getDeliveryTag());
      trace.put("redelivered", Boolean.TRUE.equals(properties.getRedelivered()));
      trace.put("queue", properties.getConsumerQueue());
      trace.put("exchange", properties.getReceivedExchange());
      trace.put("routingKey", properties.getReceivedRoutingKey());
      trace.put("consumerSha256", fingerprint(properties.getConsumerTag()));
      trace.put(
          "channelSha256",
          fingerprint(
              ProcessHandle.current().pid()
                  + ":"
                  + Arrays.stream(invocation.getArguments())
                      .filter(com.rabbitmq.client.Channel.class::isInstance)
                      .map(channel -> Integer.toString(System.identityHashCode(channel)))
                      .findFirst()
                      .orElse("UNKNOWN")));
      trace.put("transactionReturned", false);
      trace.put("transactionActiveAfter", false);
      trace.put("blocked", false);
      trace.put("ackObserved", false);
      deliveries.add(trace);
      return trace;
    }

    /** 原重试链结束后的原异常类型；重新抛出同一对象。 */
    private synchronized void failed(Map<String, Object> trace, Throwable failure) {
      if (trace == null) return;
      trace.put("listenerFailureSequence", ++sequence);
      trace.put("listenerFailureClass", failure.getClass().getName());
      pendingAck.remove();
    }

    /** 记录事务返回与屏障时刻；持久提交仍须父进程独立连接核实。 */
    private synchronized void returned(
        Map<String, Object> trace,
        boolean transactionActive,
        boolean synchronizationActive,
        boolean shouldBlock) {
      if (trace == null) return;
      trace.put("transactionReturnSequence", ++sequence);
      trace.put("transactionReturnedAt", Instant.now().toString());
      trace.put("transactionReturned", true);
      trace.put("transactionActiveAfter", transactionActive);
      trace.put("synchronizationActiveAfter", synchronizationActive);
      trace.put("blocked", shouldBlock);
    }

    /** 只观察同一线程精确 tag 的本地 basicAck 返回，不宣称 broker 已确认。 */
    private synchronized void ack(boolean success, long tag, Throwable failure) {
      Map<String, Object> trace = pendingAck.get();
      pendingAck.remove();
      if (trace == null || !Long.valueOf(tag).equals(trace.get("deliveryTag"))) return;
      trace.put("ackSequence", ++sequence);
      trace.put("ackObservedAt", Instant.now().toString());
      trace.put("ackAttemptObserved", true);
      trace.put("ackObserved", success);
      if (failure != null) trace.put("ackFailureClass", failure.getClass().getName());
    }

    /** 安全快照复制所有可变 map，异步 confirm 不会在序列化时改写响应。 */
    private synchronized Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schemaVersion", "CM18_ORDER_TIMEOUT_PROCESS_TRACE_V1");
      result.put("ready", ready.get());
      result.put("blocked", blocked.get());
      result.put("barrierCount", blocked.get() ? 1 : 0);
      result.put("processPid", ProcessHandle.current().pid());
      result.put("fault", fault);
      result.put("queue", OrderMessagingConfiguration.TIMEOUT_DLQ);
      result.put("targetEventIdSha256", fingerprint(targetEventId));
      result.put("targetOrderNoSha256", fingerprint(targetOrderNo));
      result.put("ackMode", "AUTO");
      result.put("ackObservationScope", "LOCAL_BASIC_ACK_RETURN_ONLY");
      result.put("brokerAckConfirmed", false);
      result.put("adviceCount", originalAdviceClasses.size() + 1);
      result.put("originalAdviceClasses", originalAdviceClasses);
      result.put("retryPreserved", container.get() != null);
      result.put("retryMaxAttempts", 3);
      result.put("defaultRequeueRejected", false);
      result.put("prefetch", 1);
      result.put("concurrency", 1);
      result.put("publisherObserverInstalled", publisherInstalled.get());
      result.put("boundExceeded", droppedEvents > 0);
      result.put("droppedEvents", droppedEvents);
      result.put("maxEvents", MAX_EVENTS);
      result.put("published", published.stream().map(LinkedHashMap::new).toList());
      result.put("deliveries", deliveries.stream().map(LinkedHashMap::new).toList());
      return result;
    }

    /** 未安装唯一原容器时禁止以部分启动冒充 ready。 */
    private SimpleMessageListenerContainer requiredContainer() {
      SimpleMessageListenerContainer result = container.get();
      if (result == null)
        throw new IllegalStateException("original timeout container not observed");
      return result;
    }
  }

  /** 仅 loopback GET，恒时校验随机控制 key；无 release、publish 或消费控制口。 */
  private static final class ProcessControl {
    /** 有界安全状态，不直接访问数据库或 Rabbit 消息。 */
    private final TraceState state;

    /** 使用应用既有 JSON 序列化器。 */
    private final ObjectMapper mapper;

    /** 只在内存保存控制凭证，不进入响应和证据。 */
    private final byte[] controlKey;

    private ProcessControl(TraceState state, ObjectMapper mapper, String controlKey) {
      this.state = state;
      this.mapper = mapper;
      this.controlKey = controlKey.getBytes(StandardCharsets.UTF_8);
    }

    /** /state 与 /events 返回同一结构的安全拷贝。 */
    private void read(HttpExchange exchange) throws IOException {
      String supplied = exchange.getRequestHeaders().getFirst(CONTROL_KEY_HEADER);
      if (supplied == null
          || !MessageDigest.isEqual(controlKey, supplied.getBytes(StandardCharsets.UTF_8))) {
        respond(exchange, 403, Map.of("error", "forbidden"));
      } else if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, Map.of("error", "method_not_allowed"));
      } else if (!List.of("/state", "/events").contains(exchange.getRequestURI().getPath())) {
        respond(exchange, 404, Map.of("error", "not_found"));
      } else {
        respond(exchange, 200, state.snapshot());
      }
    }

    /** 序列化一次安全投影并关闭 exchange。 */
    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
      byte[] payload = mapper.writeValueAsBytes(value);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, payload.length);
      try (var output = exchange.getResponseBody()) {
        output.write(payload);
      } finally {
        exchange.close();
      }
    }
  }

  /** 控制面使用 daemon 线程，生命期仍由真实应用和父进程拥有。 */
  private static final class DaemonThreadFactory implements ThreadFactory {
    /** 控制线程名称的并发安全序号。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 创建不阻止进程退出的控制线程。 */
    @Override
    public Thread newThread(Runnable task) {
      Thread thread = new Thread(task, "order-timeout-control-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
