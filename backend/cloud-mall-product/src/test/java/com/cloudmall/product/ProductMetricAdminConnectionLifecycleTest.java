package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/** No-network regression for the exact administration resource owner used by the pipeline. */
public class ProductMetricAdminConnectionLifecycleTest {
  /** Native fork evidence retains real process identity and successful owner termination. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void parentAndDefaultPublisherShareOwnedExecutor() throws Exception {
    Map<String, Object> closed = exerciseRealHelper();
    assertEquals(true, closed.get("parentAndPublisherSame"));
    assertEquals(true, closed.get("executorTerminated"));
  }

  @Test
  void helperCloseTerminatesChildNaturally() throws Exception {
    Path evidence =
        Files.createDirectories(
            Path.of("target", "cm21-admin-connection-lifecycle", UUID.randomUUID().toString()));
    Path marker = evidence.resolve("closed.json").toAbsolutePath();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    List<String> command =
        List.of(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString(),
            "-Xmx256m",
            "-Dfile.encoding=UTF-8",
            "-cp",
            classpath,
            ProductMetricAdminConnectionLifecycleTest.class.getName(),
            "child",
            marker.toString());
    Process child =
        new ProcessBuilder(command)
            .redirectOutput(evidence.resolve("stdout.txt").toFile())
            .redirectError(evidence.resolve("stderr.txt").toFile())
            .start();
    Instant generation = child.info().startInstant().orElseThrow();
    boolean naturalExit = false;
    try {
      Files.write(
          evidence.resolve("process.json"),
          JSON.writeValueAsBytes(
              Map.of("pid", child.pid(), "startedAt", generation.toString(), "command", command)),
          StandardOpenOption.CREATE_NEW);
      naturalExit = child.waitFor(8, TimeUnit.SECONDS);
      assertTrue(
          naturalExit, "owned helper child did not terminate naturally; evidence=" + evidence);
      assertEquals(0, child.exitValue(), "child failure; evidence=" + evidence);
      JsonNode closed = JSON.readTree(Files.readAllBytes(marker));
      assertTrue(closed.path("parentAndPublisherSame").asBoolean());
      assertTrue(closed.path("tasksAllDone").asBoolean());
      assertTrue(closed.path("executorShutdown").asBoolean());
      assertTrue(closed.path("executorTerminated").asBoolean());
      assertEquals(0, closed.path("AMQPConnections").asInt(-1));
    } finally {
      if (child.isAlive()) {
        assertEquals(generation, child.info().startInstant().orElseThrow());
        child.destroy();
        if (!child.waitFor(1, TimeUnit.SECONDS)) {
          child.destroyForcibly();
          assertTrue(child.waitFor(2, TimeUnit.SECONDS), "owned child fallback failed");
        }
      }
      Files.write(
          evidence.resolve("exit.json"),
          JSON.writeValueAsBytes(
              Map.of(
                  "pid",
                  child.pid(),
                  "startedAt",
                  generation.toString(),
                  "naturalExit",
                  naturalExit,
                  "alive",
                  child.isAlive(),
                  "exit",
                  child.isAlive() ? "UNKNOWN" : child.exitValue())),
          StandardOpenOption.CREATE_NEW);
    }
  }

  @Test
  void destroyFailureStillClosesOwnedExecutor() throws Exception {
    CachingConnectionFactory failingFactory = spy(new CachingConnectionFactory("127.0.0.1", 9));
    ExecutorService realExecutor = Executors.newCachedThreadPool();
    OwnedProductMetricAdminConnection failing =
        new OwnedProductMetricAdminConnection(failingFactory, realExecutor, "cm21-no-network");
    RuntimeException original = new IllegalStateException("controlled destroy failure");
    doThrow(original).when(failingFactory).destroy();
    try {
      startTwoRealTasks(deferred(failingFactory));
      assertSame(original, assertThrows(RuntimeException.class, failing::close));
      assertTrue(realExecutor.isShutdown());
      assertTrue(realExecutor.isTerminated());
    } finally {
      realExecutor.shutdown();
      assertTrue(realExecutor.awaitTermination(2, TimeUnit.SECONDS));
    }

    CachingConnectionFactory interruptedFactory = new CachingConnectionFactory("127.0.0.1", 9);
    ExecutorService interruptedExecutor = Executors.newCachedThreadPool();
    OwnedProductMetricAdminConnection interrupted =
        new OwnedProductMetricAdminConnection(
            interruptedFactory, interruptedExecutor, "cm21-no-network");
    CountDownLatch workerEntered = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    try {
      startTwoRealTasks(deferred(interruptedFactory));
      interruptedExecutor.submit(
          () -> {
            workerEntered.countDown();
            releaseWorker.await();
            return null;
          });
      assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
      Thread.currentThread().interrupt();
      IllegalStateException failure = assertThrows(IllegalStateException.class, interrupted::close);
      assertEquals("CM21_ADMIN_EXECUTOR_CLOSE_INTERRUPTED", failure.getMessage());
      assertInstanceOf(InterruptedException.class, failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(interruptedExecutor.isShutdown());
    } finally {
      Thread.interrupted();
      releaseWorker.countDown();
      interruptedExecutor.shutdown();
      assertTrue(interruptedExecutor.awaitTermination(2, TimeUnit.SECONDS));
    }

    CachingConnectionFactory doubleFailureFactory =
        spy(new CachingConnectionFactory("127.0.0.1", 9));
    ExecutorService refusingTermination = mock(ExecutorService.class);
    OwnedProductMetricAdminConnection doubleFailure =
        new OwnedProductMetricAdminConnection(
            doubleFailureFactory, refusingTermination, "cm21-no-network");
    RuntimeException firstFailure = new IllegalStateException("controlled first destroy failure");
    doThrow(firstFailure).when(doubleFailureFactory).destroy();
    when(refusingTermination.awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS)))
        .thenReturn(false);
    assertSame(firstFailure, assertThrows(RuntimeException.class, doubleFailure::close));
    assertEquals(1, firstFailure.getSuppressed().length);
    assertEquals(
        "CM21_ADMIN_EXECUTOR_TERMINATION_TIMEOUT", firstFailure.getSuppressed()[0].getMessage());
    verify(refusingTermination).shutdown();
    verify(refusingTermination).awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS));
    verify(refusingTermination, never()).shutdownNow();
  }

  /** Run the exact helper in a standalone JVM so Surefire cannot hide deferred worker lifetime. */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2 || !"child".equals(arguments[0]))
      throw new IllegalArgumentException("CM21_ADMIN_CHILD_ARGUMENT_REQUIRED");
    Map<String, Object> closed = exerciseRealHelper();
    Files.write(
        Path.of(arguments[1]), JSON.writeValueAsBytes(closed), StandardOpenOption.CREATE_NEW);
  }

  /** Exercise real parent/publisher executor selection, task completion and actual owner close. */
  private static Map<String, Object> exerciseRealHelper() throws Exception {
    OwnedProductMetricAdminConnection resources =
        new OwnedProductMetricAdminConnection("127.0.0.1", 9, "cm21-no-network");
    try {
      CachingConnectionFactory parent = resources.connectionFactory();
      ExecutorService parentExecutor = deferred(parent);
      CachingConnectionFactory publisher =
          (CachingConnectionFactory) parent.getPublisherConnectionFactory();
      assertNotNull(publisher);
      assertSame(parentExecutor, deferred(publisher));
      List<Future<Boolean>> tasks = startTwoRealTasks(parentExecutor);
      parent.destroy();
      assertFalse(parentExecutor.isShutdown(), "external executor belongs to the helper");
      resources.close();
      assertTrue(parentExecutor.isShutdown());
      assertTrue(parentExecutor.isTerminated());
      return Map.of(
          "parentAndPublisherSame",
          parentExecutor == deferred(publisher),
          "tasksAllDone",
          tasks.stream().allMatch(Future::isDone),
          "executorShutdown",
          resources.executorShutdown(),
          "executorTerminated",
          resources.executorTerminated(),
          "AMQPConnections",
          0);
    } finally {
      if (!resources.executorTerminated()) resources.close();
    }
  }

  /** Reflection is limited to observing the real framework's task selection in this local test. */
  private static ExecutorService deferred(CachingConnectionFactory factory) throws Exception {
    Method getter = CachingConnectionFactory.class.getDeclaredMethod("getChannelsExecutor");
    getter.setAccessible(true);
    return (ExecutorService) getter.invoke(factory);
  }

  /** Make both real workers observable, then release and join every short task before closing. */
  private static List<Future<Boolean>> startTwoRealTasks(ExecutorService executor)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    List<Future<Boolean>> tasks = new ArrayList<>();
    try {
      for (int index = 0; index < 2; index++)
        tasks.add(
            executor.submit(
                () -> {
                  entered.countDown();
                  if (!release.await(2, TimeUnit.SECONDS))
                    throw new IllegalStateException("CM21_ADMIN_TEST_TASK_RELEASE_TIMEOUT");
                  return !Thread.currentThread().isDaemon();
                }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    for (Future<Boolean> task : tasks) assertTrue(task.get(2, TimeUnit.SECONDS));
    return tasks;
  }
}
