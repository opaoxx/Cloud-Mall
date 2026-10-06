package com.cloudmall.pay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 将 Pay 测试请求转发到本机 User 服务，并模拟响应丢失。 */
public final class TccResponseProxy implements AutoCloseable {
  /** 每次请求连接和读取的最长等待时间。 */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** 不应跨越代理边界转发的 hop-by-hop 请求头。 */
  private static final Set<String> HOP_HEADERS =
      Set.of("host", "connection", "content-length", "transfer-encoding", "expect", "upgrade");

  /** 实际 User 服务端口。 */
  private final int targetPort;

  /** 无自动重试的 JDK HTTP 客户端。 */
  private final HttpClient httpClient;

  /** loopback HTTP 监听器。 */
  private final HttpServer server;

  /** daemon HTTP 工作线程池。 */
  private final ExecutorService workerPool;

  /** 只阻断 TCC GET 的可恢复故障，不影响既有资金写入。 */
  private final AtomicBoolean readsUnavailable = new AtomicBoolean();

  /** TCC POST 计数器。 */
  private final AtomicInteger postRequestCount = new AtomicInteger();

  /** TCC GET 计数器。 */
  private final AtomicInteger getRequestCount = new AtomicInteger();

  /** 下一次 POST 是否截断响应。 */
  private final AtomicBoolean dropNextPostResponse = new AtomicBoolean();

  /** 指定业务 POST 的一次性响应故障，不影响原 TCC 用例。 */
  private final AtomicReference<String> dropPostSuffix = new AtomicReference<>();

  /** 退款请求次数，验证重放未换键。 */
  private final AtomicInteger refundPosts = new AtomicInteger();

  /** 订单 paid 请求次数。 */
  private final AtomicInteger paidPosts = new AtomicInteger();

  /** 代理是否已经关闭。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 在 loopback 随机端口启动一个指向本机 User 服务的代理。 */
  public TccResponseProxy(int targetPort) throws IOException {
    if (targetPort < 1 || targetPort > 65535) {
      throw new IllegalArgumentException("targetPort must be between 1 and 65535");
    }
    this.targetPort = targetPort;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
    this.server.createContext("/", this::handle);
    this.workerPool = Executors.newCachedThreadPool(new ProxyThreadFactory());
    this.server.setExecutor(workerPool);
    this.server.start();
  }

  /** 返回代理监听端口。 */
  public int port() {
    return server.getAddress().getPort();
  }

  /** 控制 TCC 只读查询故障，供真实后台证据恢复测试。 */
  public void setTccReadsUnavailable(boolean unavailable) {
    readsUnavailable.set(unavailable);
  }

  /** 返回已转发的 TCC POST 请求数。 */
  public int postCount() {
    return postRequestCount.get();
  }

  /** 返回已转发的 TCC GET 请求数。 */
  public int getCount() {
    return getRequestCount.get();
  }

  /** 使下一次 TCC POST 在后端响应完成后收到截断响应。 */
  public void dropNextPostBody() {
    dropNextPostResponse.set(true);
  }

  /** 清除尚未触发的响应故障。 */
  public void clearFault() {
    dropNextPostResponse.set(false);
    dropPostSuffix.set(null);
    readsUnavailable.set(false);
  }

  /** 在指定业务路径已处理后截断下一次 POST 响应。 */
  public void dropNextPostBodyForPath(String suffix) {
    dropPostSuffix.set(suffix);
  }

  /** 读取真实退款 POST 数量。 */
  public int refundPostCount() {
    return refundPosts.get();
  }

  /** 读取真实订单 paid POST 数量。 */
  public int paidPostCount() {
    return paidPosts.get();
  }

  /** 清零 POST/GET 统计计数。 */
  public void resetCounters() {
    postRequestCount.set(0);
    getRequestCount.set(0);
    refundPosts.set(0);
    paidPosts.set(0);
  }

  /** 幂等停止 HTTP 服务和 daemon 工作线程。 */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      server.stop(0);
      workerPool.shutdownNow();
    }
  }

  /** 读取请求、转发到真实 User 实例并在必要时截断完整响应。 */
  private void handle(HttpExchange exchange) {
    boolean tccPath = exchange.getRequestURI().getPath().contains("/balance/tcc-debits");
    String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
    if ("POST".equals(method) && exchange.getRequestURI().getPath().endsWith("/refunds"))
      refundPosts.incrementAndGet();
    if ("POST".equals(method) && exchange.getRequestURI().getPath().endsWith("/paid"))
      paidPosts.incrementAndGet();
    if (tccPath && "POST".equals(method)) {
      postRequestCount.incrementAndGet();
    } else if (tccPath && "GET".equals(method)) {
      getRequestCount.incrementAndGet();
    }
    if (tccPath && "GET".equals(method) && readsUnavailable.get()) {
      replyBadGateway(exchange);
      return;
    }
    try {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      HttpRequest.BodyPublisher bodyPublisher =
          "GET".equals(method) || "HEAD".equals(method)
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(requestBody);
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(targetUri(exchange.getRequestURI()))
              .timeout(REQUEST_TIMEOUT)
              .method(method, bodyPublisher);
      copyRequestHeaders(exchange, requestBuilder);
      HttpResponse<byte[]> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
      String suffix = dropPostSuffix.get();
      boolean dropResponse =
          "POST".equals(method)
              && ((tccPath && dropNextPostResponse.compareAndSet(true, false))
                  || (suffix != null
                      && exchange.getRequestURI().getPath().endsWith(suffix)
                      && dropPostSuffix.compareAndSet(suffix, null)));
      copyResponseHeaders(response, exchange);
      if (dropResponse) {
        sendIncompleteResponse(exchange, response.statusCode(), response.body());
      } else {
        byte[] responseBody = response.body();
        exchange.sendResponseHeaders(response.statusCode(), responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
      }
    } catch (Exception proxyFailure) {
      replyBadGateway(exchange);
    }
  }

  /** 构造同路径和查询串的 loopback User 地址。 */
  private URI targetUri(URI requestUri) {
    return URI.create(
        "http://127.0.0.1:"
            + targetPort
            + requestUri.getRawPath()
            + (requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery()));
  }

  /** 转发内部认证和业务请求头，过滤 HTTP hop-by-hop 头。 */
  private void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder requestBuilder) {
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) -> {
              if (!HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                  requestBuilder.header(name, value);
                }
              }
            });
  }

  /** 转发后端响应头，过滤会被代理重新计算的 hop-by-hop 头。 */
  private void copyResponseHeaders(HttpResponse<byte[]> response, HttpExchange exchange) {
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                exchange.getResponseHeaders().put(name, List.copyOf(values));
              }
            });
  }

  /** 先声明完整长度，再只写部分响应体并关闭连接，模拟响应解析失败。 */
  private void sendIncompleteResponse(HttpExchange exchange, int statusCode, byte[] responseBody)
      throws IOException {
    int announcedLength = Math.max(1, responseBody.length);
    exchange.sendResponseHeaders(statusCode, announcedLength);
    int bytesToWrite = responseBody.length == 0 ? 0 : Math.max(0, responseBody.length - 1);
    if (bytesToWrite > 0) {
      exchange.getResponseBody().write(responseBody, 0, bytesToWrite);
    }
    exchange.close();
  }

  /** 代理无法连接目标 User 时返回 502。 */
  private void replyBadGateway(HttpExchange exchange) {
    try {
      byte[] body = "Bad Gateway".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(502, body.length);
      exchange.getResponseBody().write(body);
    } catch (IOException ignored) {
      // 客户端已经断开时忽略测试代理的错误响应异常。
    } finally {
      exchange.close();
    }
  }

  /** 创建 daemon HTTP 工作线程，避免代理阻止测试 JVM 退出。 */
  private static final class ProxyThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger();

    /** 创建单个 daemon 工作线程。 */
    @Override
    public Thread newThread(Runnable task) {
      Thread worker = new Thread(task, "tcc-response-proxy-" + threadNumber.incrementAndGet());
      worker.setDaemon(true);
      return worker;
    }
  }
}
