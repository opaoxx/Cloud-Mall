package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

/** 只拥有随机vhost的Rabbit3.11真实夹具，不启动应用，不触碰原业务队列。 */
final class OrderTimeoutRabbitFixture implements AutoCloseable {
  /** 本机已有Rabbit管理API的短时请求客户端。 */
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 随机且只能精确删除的夹具vhost。 */
  private final String virtualHost = "cm17de_" + UUID.randomUUID().toString().replace("-", "");

  /** 已核验原不存在、且已尝试创建的资源清理权限。 */
  private boolean owned;

  /** 仅连接本夹具vhost的AMQP工厂。 */
  private CachingConnectionFactory connectionFactory;

  /** 仅在本夹具vhost内声明或移除绑定的管理器。 */
  private RabbitAdmin admin;

  /** 使用真实confirm/returned和JSON converter的投递模板。 */
  private RabbitTemplate rabbit;

  /** 创建隔离vhost并声明与生产同名的TTL/DLX拓扑，构造失败也精确清理。 */
  public OrderTimeoutRabbitFixture() throws Exception {
    try {
      String version =
          new ObjectMapper()
              .readTree(request("GET", "/api/overview", null).body())
              .path("rabbitmq_version")
              .asText();
      assertTrue(version.startsWith("3.11."), "需要固定Rabbit3.11，实际=" + version);
      System.out.println("CM17DE broker version: " + version);
      assertEquals(404, request("GET", "/api/vhosts/" + virtualHost, null).statusCode());
      owned = true;
      System.out.println("CM17DE fixture vhost: " + virtualHost);
      requireSuccess(request("PUT", "/api/vhosts/" + virtualHost, "{}"));
      requireSuccess(
          request(
              "PUT",
              "/api/permissions/" + virtualHost + "/root",
              "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}"));
      connectionFactory = new CachingConnectionFactory("127.0.0.1", 15673);
      connectionFactory.setUsername("root");
      connectionFactory.setPassword("root");
      connectionFactory.setVirtualHost(virtualHost);
      connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connectionFactory.setPublisherReturns(true);
      admin = new RabbitAdmin(connectionFactory);
      admin.declareExchange(new DirectExchange(OrderMessagingConfiguration.TIMEOUT_EXCHANGE));
      admin.declareExchange(new DirectExchange(OrderMessagingConfiguration.DLX));
      admin.declareQueue(
          new Queue(
              OrderMessagingConfiguration.TIMEOUT_QUEUE,
              true,
              false,
              false,
              Map.of("x-dead-letter-exchange", OrderMessagingConfiguration.DLX)));
      admin.declareQueue(new Queue(OrderMessagingConfiguration.DLQ, true));
      admin.declareBinding(
          new Binding(
              OrderMessagingConfiguration.TIMEOUT_QUEUE,
              Binding.DestinationType.QUEUE,
              OrderMessagingConfiguration.TIMEOUT_EXCHANGE,
              "",
              null));
      admin.declareBinding(deadLetterBinding());
      rabbit = new RabbitTemplate(connectionFactory);
      rabbit.setMessageConverter(new Jackson2JsonMessageConverter());
    } catch (Exception | Error failure) {
      try {
        close();
      } catch (Exception cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** 返回本夹具真实投递模板，禁止用它连接原业务vhost。 */
  public RabbitTemplate rabbit() {
    return rabbit;
  }

  /** 仅移除本夹具DLX到关单队列的绑定，用于真实mandatory返回验证。 */
  public void removeDeadLetterBinding() {
    admin.removeBinding(deadLetterBinding());
  }

  /** 有界等待本夹具关单队列的一条真实消息，不读取TTL头部以免影响队头实验。 */
  public Message awaitDueMessage() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < deadline) {
      Message message = rabbit.receive(OrderMessagingConfiguration.DLQ);
      if (message != null) return message;
      Thread.sleep(20);
    }
    return null;
  }

  /** 只读本夹具TTL队列的剩余条数，不消费队头。 */
  public int pendingTimeoutMessages() {
    return ((Number)
            admin
                .getQueueProperties(OrderMessagingConfiguration.TIMEOUT_QUEUE)
                .get(RabbitAdmin.QUEUE_MESSAGE_COUNT))
        .intValue();
  }

  /** 关闭测试连接并只删除已拥有的随机vhost，确认不存在。 */
  @Override
  public void close() throws Exception {
    if (connectionFactory != null) connectionFactory.destroy();
    if (!owned) return;
    if (!virtualHost.matches("cm17de_[0-9a-f]{32}")) throw new IllegalStateException("拒绝清理未知vhost");
    HttpResponse<String> deleted = request("DELETE", "/api/vhosts/" + virtualHost, null);
    assertTrue(
        deleted.statusCode() >= 200 && deleted.statusCode() < 300 || deleted.statusCode() == 404,
        deleted.body());
    assertEquals(404, request("GET", "/api/vhosts/" + virtualHost, null).statusCode());
    System.out.println("CM17DE fixture vhost dropped: " + virtualHost);
    owned = false;
  }

  /** 构造当前vhost内的原DLX绑定，无跨vhost目标。 */
  private static Binding deadLetterBinding() {
    return new Binding(
        OrderMessagingConfiguration.DLQ,
        Binding.DestinationType.QUEUE,
        OrderMessagingConfiguration.DLX,
        "",
        null);
  }

  /** 管理API只使用固定本机地址和开发认证，资源路径由本类生成。 */
  private HttpResponse<String> request(String method, String path, String body) throws Exception {
    String credentials =
        Base64.getEncoder().encodeToString("root:root".getBytes(StandardCharsets.UTF_8));
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:15674" + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Basic " + credentials)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 夹具声明错误立即失败，不把未成功的资源操作当完成。 */
  private static void requireSuccess(HttpResponse<String> response) {
    assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
  }
}
