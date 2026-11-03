package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.config.ProductMetricMessagingConfiguration;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import com.cloudmall.product.service.ProductMetricBatchListener;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.service.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricRawBatchingStrategy;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Real Spring endpoint/container initialization, explicitly never starting or connecting a
 * consumer.
 */
class ProductMetricBatchWiringTest {
  @Test
  void missingAndFalseFlagsSelectExactlyOneOriginalEndpointAndOriginalContainer() {
    for (String flag : new String[] {"missing", "false"}) {
      try (Harness harness = new Harness(flag)) {
        assertEquals(1, harness.context.getBeansOfType(ProductMetricSingleListener.class).size());
        assertTrue(harness.context.getBeansOfType(ProductMetricBatchListener.class).isEmpty());
        SimpleMessageListenerContainer container = harness.container();
        assertEquals(1, ReflectionTestUtils.getField(container, "prefetchCount"));
        assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
        assertEquals(1, ReflectionTestUtils.getField(container, "maxConcurrentConsumers"));
        assertFalse((Boolean) ReflectionTestUtils.getField(container, "consumerBatchEnabled"));
        assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
        assertFalse(container.isChannelTransacted());
        verify(harness.connection, never()).createConnection();
      }
    }
  }

  @Test
  void trueFlagInitializesActualBatchRawContainerAndBindsBeforeAnyDelivery() {
    try (Harness harness = new Harness("true")) {
      assertEquals(1, harness.context.getBeansOfType(ProductMetricBatchListener.class).size());
      assertTrue(harness.context.getBeansOfType(ProductMetricSingleListener.class).isEmpty());
      assertInstanceOf(
          ProductMetricBatchContainerFactory.class,
          harness.context.getBean("productMetricContainerFactory"));
      SimpleMessageListenerContainer container = harness.container();
      assertEquals(4, ReflectionTestUtils.getField(container, "prefetchCount"));
      assertEquals(4, ReflectionTestUtils.getField(container, "batchSize"));
      assertEquals(25L, ReflectionTestUtils.getField(container, "receiveTimeout"));
      assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
      assertEquals(1, ReflectionTestUtils.getField(container, "maxConcurrentConsumers"));
      assertEquals(15000L, ReflectionTestUtils.getField(container, "shutdownTimeout"));
      assertTrue((Boolean) ReflectionTestUtils.getField(container, "consumerBatchEnabled"));
      assertTrue((Boolean) ReflectionTestUtils.getField(container, "forceStop"));
      assertTrue((Boolean) ReflectionTestUtils.getField(container, "deBatchingEnabled"));
      assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
      assertFalse(container.isChannelTransacted());
      assertInstanceOf(
          ProductMetricRawBatchingStrategy.class,
          ReflectionTestUtils.getField(container, "batchingStrategy"));
      ProductMetricBatchChannelRegistry registry =
          harness.context.getBean(ProductMetricBatchChannelRegistry.class);
      assertSame(registry, ReflectionTestUtils.getField(harness.consumer, "batchRegistry"));
      assertSame(
          registry,
          ReflectionTestUtils.getField(
              harness.context.getBean("productMetricContainerFactory"), "registry"));
      assertDoesNotThrow(registry::assertQuiescentAndChannelsClosed);
      verify(harness.connection, never()).createConnection();
    }
  }

  @Test
  void flagMustBeExactlyTrueOrFalse() {
    for (String flag : new String[] {"TRUE", "False", " true", "false ", "1", "", "unknown"}) {
      ProductMetricMessagingConfiguration configuration =
          new ProductMetricMessagingConfiguration(
              mock(ProductMetricMapper.class),
              mock(DataSourceTransactionManager.class),
              new MockEnvironment().withProperty("cloudmall.product.metrics.batch.enabled", flag));
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> configuration.factory(mock(CachingConnectionFactory.class)));
      assertEquals("METRIC_BATCH_FLAG_INVALID", failure.getMessage());
    }
  }

  @Test
  void consumerNoLongerRegistersACompetingListenerMethod() throws Exception {
    assertNull(
        ProductMetricConsumer.class
            .getMethod(
                "consume",
                org.springframework.amqp.core.Message.class,
                com.rabbitmq.client.Channel.class)
            .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class));
    assertNotNull(
        ProductMetricSingleListener.class
            .getMethod(
                "onMessage",
                org.springframework.amqp.core.Message.class,
                com.rabbitmq.client.Channel.class)
            .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class));
    assertNotNull(
        ProductMetricBatchListener.class
            .getMethod("onMessages", java.util.List.class, com.rabbitmq.client.Channel.class)
            .getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableRabbit
  static class RabbitInfrastructure {}

  private static final class Harness implements AutoCloseable {
    /** Real Spring registry and endpoint initialization, with all listener starts disabled. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** Real configured local CCF spy used to prove that no connection creation occurs. */
    private final CachingConnectionFactory connection;

    /** Real binding owner, with only destruction suppressed for this wiring-only fixture. */
    private final ProductMetricConsumer consumer;

    private Harness(String flag) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "fixture",
                  "missing".equals(flag)
                      ? Map.of("cloudmall.product.metrics.enabled", "true")
                      : Map.of(
                          "cloudmall.product.metrics.enabled",
                          "true",
                          "cloudmall.product.metrics.batch.enabled",
                          flag)));
      com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
      client.setAutomaticRecoveryEnabled(false);
      connection = spy(new CachingConnectionFactory(client));
      connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connection.setPublisherReturns(true);
      DataSource dataSource = mock(DataSource.class);
      ProductMetricMapper mapper = mock(ProductMetricMapper.class);
      when(mapper.dataSource()).thenReturn(dataSource);
      ProductMetricService service = mock(ProductMetricService.class);
      ProductMetricOutboxDispatcher publisher = mock(ProductMetricOutboxDispatcher.class);
      context
          .getBeanFactory()
          .addBeanPostProcessor(
              new BeanPostProcessor() {
                /**
                 * Prevent listener startup while retaining real factory/container initialization.
                 */
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                  if (bean instanceof SimpleRabbitListenerContainerFactory factory)
                    factory.setAutoStartup(false);
                  return bean;
                }
              });
      context.registerBean("connectionFactory", CachingConnectionFactory.class, () -> connection);
      context.registerBean(ProductMetricMapper.class, () -> mapper);
      context.registerBean(
          DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
      context.registerBean(ProductMetricService.class, () -> service);
      context.registerBean(
          ProductIndexDocumentLoader.class, () -> mock(ProductIndexDocumentLoader.class));
      context.registerBean(ProductMetricOutboxDispatcher.class, () -> publisher);
      context.registerBean(
          ProductMetricConsumer.class,
          () -> {
            ProductMetricConsumer wiringConsumer =
                spy(
                    new ProductMetricConsumer(
                        service, publisher, context.getBean(RabbitListenerEndpointRegistry.class)));
            // Wiring only initializes endpoints; real close boundaries are exercised by
            // DeliveryTest.
            doNothing().when(wiringConsumer).close();
            return wiringConsumer;
          },
          definition -> definition.setDestroyMethodName(""));
      context.register(RabbitInfrastructure.class, ProductMetricMessagingConfiguration.class);
      context.refresh();
      consumer = context.getBean(ProductMetricConsumer.class);
    }

    private SimpleMessageListenerContainer container() {
      RabbitListenerEndpointRegistry endpoints =
          context.getBean(RabbitListenerEndpointRegistry.class);
      assertEquals(java.util.Set.of("productMetrics"), endpoints.getListenerContainerIds());
      SimpleMessageListenerContainer container =
          (SimpleMessageListenerContainer) endpoints.getListenerContainer("productMetrics");
      assertNotNull(container);
      assertFalse(container.isRunning());
      assertEquals(0, container.getActiveConsumerCount());
      return container;
    }

    /** Release only the local Spring context; this fixture never starts a broker connection. */
    @Override
    public void close() {
      context.close();
    }
  }
}
