package com.cloudmall.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guards for the frozen backend contracts touched in the P1 pass. */
class BackendP1RulesTest {
    @Test
    void stockChecksDatabaseWritesAndCompensatesRedis() throws Exception {
        String source = source("cloud-mall-stock", "StockController.java");
        assertTrue(source.contains("if(changed!=1)"));
        assertTrue(source.contains("compensateReservation"));
        assertTrue(source.contains("on duplicate key update idempotency_key"));
        assertTrue(source.contains("SECKILL_ROLLBACK"));
        assertTrue(source.contains("redis.execute(seckillRollbackScript,keys)"));
    }

    @Test
    void productPersistsAndReadsParameters() throws Exception {
        String source = source("cloud-mall-product", "ProductController.java");
        assertTrue(source.contains("product_parameter"));
        assertTrue(source.contains("parameters"));
        assertTrue(source.contains("replaceParameters"));
        assertTrue(source.contains("db.update(\"delete from product_parameter where product_id=?\""));
    }

    @Test
    void productMapsDatabaseSnakeCaseToApiDtos() throws Exception {
        String source = source("cloud-mall-product", "ProductController.java");
        assertTrue(source.contains("toActivityResponse(activityRecord(id))"));
        assertTrue(source.contains("new HotStatResponse"));
        assertTrue(source.contains("public final long activityId,skuId"));
        assertTrue(source.contains("public final int remainingStock,perUserLimit"));
        assertTrue(source.contains("public final long productId,viewCount,searchCount"));
        assertTrue(source.contains("public final String hotScore"));
    }

    @Test
    void paymentAndOrderBindIdempotencyAndPathVariables() throws Exception {
        String payment = source("cloud-mall-pay", "PayController.java");
        String order = source("cloud-mall-order", "OrderController.java");
        assertTrue(payment.contains("payNoFor"));
        assertTrue(payment.contains("Idempotency-Key"));
        assertTrue(order.contains("@PathVariable(\"orderNo\")"));
        assertTrue(order.contains("TIMEOUT_DLQ"));
        assertTrue(order.contains("YearMonth.parse"));
    }

    @Test
    void seckillConsumerRejectsFailuresAndQueueHasDeadLetterRetrySemantics() throws Exception {
        String order = source("cloud-mall-order", "OrderController.java");
        String messaging = source("cloud-mall-order", "OrderMessagingConfiguration.java");
        String config = Files.readString(Path.of("..", "cloud-mall-order", "src", "main", "resources", "application.yml").normalize());
        assertTrue(order.contains("catch(Exception e){throw new IllegalStateException"));
        assertTrue(order.contains("交由 RabbitMQ 重试或死信"));
        assertTrue(messaging.contains("x-dead-letter-exchange"));
        assertTrue(messaging.contains("SECKILL_DLQ"));
        assertTrue(config.contains("default-requeue-rejected: false"));
        assertTrue(config.contains("max-attempts: 3"));
    }

    @Test
    void productEventFailureIsLogged() throws Exception {
        String product = source("cloud-mall-product", "ProductController.java");
        assertTrue(product.contains("LoggerFactory.getLogger(ProductController.class)"));
        assertTrue(product.contains("log.error(\"商品事件发送失败"));
        assertTrue(!product.contains("catch(Exception ignored){}"));
    }

    @Test
    void gatewaySplitsSeckillActivitiesAndOrdersByConcretePath() throws Exception {
        String config = Files.readString(Path.of("..", "cloud-mall-gateway", "src", "main", "resources", "application.yml").normalize());
        int activities = config.indexOf("id: seckill-activities");
        int orders = config.indexOf("id: order");

        assertTrue(config.contains("server: {port: 8080}"));
        assertTrue(activities >= 0);
        assertTrue(orders > activities);
        assertTrue(config.contains("uri: lb://cloud-mall-product, predicates: [Path=/api/seckill/activities,/api/seckill/activities/**]"));
        assertTrue(config.contains("uri: lb://cloud-mall-order, predicates: [Path=/api/orders/**,/api/seckill/orders,/api/seckill/orders/**]"));
        assertTrue(!config.contains("Path=/api/seckill/**"));
    }

    private static String source(String module, String file) throws Exception {
        Path path = Path.of("..", module, "src", "main", "java", "com", "cloudmall",
                module.substring("cloud-mall-".length()), file).normalize();
        return Files.readString(path);
    }
}
