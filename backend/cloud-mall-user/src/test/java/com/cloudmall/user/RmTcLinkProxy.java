package com.cloudmall.user;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 测试专用 TCP 透明代理，用于隔离 RM 到 TC 的网络链路。 */
public final class RmTcLinkProxy implements AutoCloseable {
  /** 目标连接建立超时时间。 */
  private static final int CONNECT_TIMEOUT_MILLIS = 2_000;

  /** 接收循环检查关闭状态的超时时间。 */
  private static final int ACCEPT_TIMEOUT_MILLIS = 500;

  /** 目标 TC 主机。 */
  private final String targetHost;

  /** 目标 TC 端口。 */
  private final int targetPort;

  /** 本地监听 Socket。 */
  private final ServerSocket serverSocket;

  /** 连接建立和双向复制线程池。 */
  private final ExecutorService workerPool;

  /** 当前由代理拥有的连接。 */
  private final Set<ProxyConnection> connections = ConcurrentHashMap.newKeySet();

  /** 已接受连接总数。 */
  private final AtomicLong acceptedConnectionCount = new AtomicLong();

  /** 双向转发累计字节数。 */
  private final AtomicLong forwardedByteCount = new AtomicLong();

  /** 保护阻断状态与连接纳管竞态。 */
  private final Object stateGuard = new Object();

  /** 代理是否已关闭。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 是否阻止新连接转发。 */
  private volatile boolean blocked;

  /** 接收客户端连接的后台线程。 */
  private final Thread acceptThread;

  /** 在本机随机端口启动代理。 */
  public RmTcLinkProxy(String targetHost, int targetPort) throws IOException {
    if (targetHost == null || targetHost.isBlank()) {
      throw new IllegalArgumentException("targetHost must not be blank");
    }
    if (targetPort < 1 || targetPort > 65535) {
      throw new IllegalArgumentException("targetPort must be between 1 and 65535");
    }
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    this.serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
    this.serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
    this.workerPool =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread worker = new Thread(runnable, "rm-tc-link-proxy-worker");
              worker.setDaemon(true);
              return worker;
            });
    this.acceptThread = new Thread(this::acceptLoop, "rm-tc-link-proxy-accept");
    this.acceptThread.setDaemon(true);
    this.acceptThread.start();
  }

  /** 返回代理监听端口。 */
  public int port() {
    return serverSocket.getLocalPort();
  }

  /** 返回当前仍由代理管理的连接数。 */
  public int activeConnections() {
    return connections.size();
  }

  /** 返回代理累计接收的连接数。 */
  public long acceptedConnections() {
    return acceptedConnectionCount.get();
  }

  /** 返回双向转发的累计字节数。 */
  public long forwardedBytes() {
    return forwardedByteCount.get();
  }

  /** 阻止新转发，并关闭当前所有连接。 */
  public void block() {
    synchronized (stateGuard) {
      if (closed.get()) {
        return;
      }
      blocked = true;
      closeConnectionsLocked();
    }
  }

  /** 恢复后续连接转发，不重建已关闭的连接。 */
  public void restore() {
    synchronized (stateGuard) {
      if (!closed.get()) {
        blocked = false;
      }
    }
  }

  /** 幂等关闭监听器、连接和后台线程。 */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    synchronized (stateGuard) {
      blocked = true;
      closeConnectionsLocked();
    }
    closeQuietly(serverSocket);
    workerPool.shutdownNow();
    acceptThread.interrupt();
  }

  /** 接收客户端连接，并在状态锁内完成连接纳管。 */
  private void acceptLoop() {
    while (!closed.get()) {
      try {
        Socket clientSocket = serverSocket.accept();
        acceptedConnectionCount.incrementAndGet();
        ProxyConnection connection = new ProxyConnection(clientSocket);
        synchronized (stateGuard) {
          if (closed.get() || blocked) {
            connection.close();
            continue;
          }
          connections.add(connection);
        }
        try {
          workerPool.execute(connection::connectAndForward);
        } catch (RejectedExecutionException rejectedExecution) {
          connection.close();
        }
      } catch (java.net.SocketTimeoutException ignored) {
        // 定期检查 closed，避免依赖平台对 close(ServerSocket) 的唤醒行为。
      } catch (IOException acceptFailure) {
        if (!closed.get()) {
          close();
        }
      }
    }
  }

  /** 在持有 stateGuard 时关闭并移除全部连接。 */
  private void closeConnectionsLocked() {
    for (ProxyConnection connection : connections.toArray(new ProxyConnection[0])) {
      connection.close();
    }
    connections.clear();
  }

  /** 从代理拥有集合移除已结束连接。 */
  private void removeConnection(ProxyConnection connection) {
    connections.remove(connection);
  }

  /** 将一端输入复制到另一端，Socket 关闭后解除阻塞读取。 */
  private static void copy(
      Socket source, Socket destination, AtomicBoolean connectionClosed, AtomicLong byteCounter) {
    byte[] buffer = new byte[8 * 1024];
    try (InputStream input = source.getInputStream();
        OutputStream output = destination.getOutputStream()) {
      int read;
      while (!connectionClosed.get() && (read = input.read(buffer)) >= 0) {
        if (read == 0) {
          continue;
        }
        output.write(buffer, 0, read);
        output.flush();
        byteCounter.addAndGet(read);
      }
    } catch (IOException ignored) {
      // 另一方向或 block/close 通常会同时关闭该 socket。
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // 测试清理路径忽略关闭异常。
    }
  }

  private static void closeQuietly(ServerSocket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // 测试清理路径忽略关闭异常。
    }
  }

  private final class ProxyConnection {
    /** 客户端 Socket。 */
    private final Socket clientSocket;

    /** 该连接是否已关闭。 */
    private final AtomicBoolean connectionClosed = new AtomicBoolean();

    /** 目标 Socket，连接建立前也会登记以支持 block 打断 connect。 */
    private volatile Socket targetSocket;

    /** 创建一个尚未连接到目标 TC 的客户端连接。 */
    private ProxyConnection(Socket clientSocket) {
      this.clientSocket = clientSocket;
    }

    /** 登记目标 Socket、建立目标连接并提交双向复制任务。 */
    private void connectAndForward() {
      Socket connectedTarget = new Socket();
      try {
        synchronized (stateGuard) {
          if (closed.get() || blocked || connectionClosed.get()) {
            closeQuietly(connectedTarget);
            close();
            return;
          }
          targetSocket = connectedTarget;
        }
        connectedTarget.connect(
            new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MILLIS);
        synchronized (stateGuard) {
          if (closed.get() || blocked || connectionClosed.get()) {
            close();
            return;
          }
        }
        submitCopy(
            () -> {
              try {
                copy(clientSocket, connectedTarget, connectionClosed, forwardedByteCount);
              } finally {
                close();
              }
            });
        submitCopy(
            () -> {
              try {
                copy(connectedTarget, clientSocket, connectionClosed, forwardedByteCount);
              } finally {
                close();
              }
            });
      } catch (IOException connectFailure) {
        close();
      }
    }

    /** 向共享线程池提交复制任务；线程池关闭时立即释放连接。 */
    private void submitCopy(Runnable copyTask) {
      try {
        workerPool.execute(copyTask);
      } catch (RejectedExecutionException rejectedExecution) {
        close();
      }
    }

    /** 幂等关闭客户端和目标 Socket，并从代理拥有集合移除连接。 */
    private void close() {
      if (!connectionClosed.compareAndSet(false, true)) {
        return;
      }
      closeQuietly(clientSocket);
      Socket connectedTarget = targetSocket;
      if (connectedTarget != null) {
        closeQuietly(connectedTarget);
      }
      removeConnection(this);
    }
  }
}
