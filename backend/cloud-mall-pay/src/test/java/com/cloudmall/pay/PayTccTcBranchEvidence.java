package com.cloudmall.pay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只读观察固定 TC 原生 INFO；不以全局提交推断单支提交 ACK。 */
final class PayTccTcBranchEvidence {
  /** 只允许读取该既有共享容器，绝不修改其配置或生命周期。 */
  private static final String CONTAINER = "cloud-mall-seata";

  /** 与评审冻结的字节/行上限一致；命中上限不接受部分成功。 */
  private static final int BYTE_BOUND = 4 * 1024 * 1024, LINE_BOUND = 5000;

  /** 错误通道单独有界，永不作为服务端事务事实解析。 */
  private static final int ERROR_BOUND = 64 * 1024;

  /** 只输出自行构造的安全结构，不序列化日志或异常对象。 */
  private static final ObjectMapper JSON =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /** Docker UTC 外层时间、INFO 与原生 logger 均须完整匹配。 */
  private static final Pattern PREFIX =
      Pattern.compile(
          "^(\\S+Z) \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+INFO\\s+--- \\[[^\\]\\r\\n]{1,80}\\]\\s+"
              + "(io\\.seata\\.server\\.coordinator\\.(?:AbstractCore|DefaultCore)"
              + "|i\\.seata\\.server\\.coordinator\\.AbstractCore)\\s*:\\s+(.*)$");

  /** 完整消息尾也属于白名单，禁止 XID/branch 前缀命中。 */
  private static final Pattern REGISTER =
      Pattern.compile(
          "Register branch successfully, xid = ([^,\\s]+), branchId = ([1-9][0-9]*), resourceId ="
              + " ([^\\s,]+) ,lockKeys = null");

  /** 该原生回滚记录位于 PhaseTwo_Rollbacked 返回后的服务端路径。 */
  private static final Pattern ROLLBACK =
      Pattern.compile(
          "Rollback branch transaction successfully, xid = ([^\\s]+) branchId = ([1-9][0-9]*)");

  /** 官方 1.5.2 源码只有全局提交完成 INFO，没有逐支提交成功 INFO。 */
  private static final Pattern GLOBAL =
      Pattern.compile(
          "(Committing global transaction is successfully done|Rollback global transaction"
              + " successfully), xid = ([^\\s]+)\\.");

  private PayTccTcBranchEvidence() {}

  /**
   * 将本次可信原身份对应的 TC 日志安全投影写为不可覆盖的独立证据文件。
   *
   * @return 新文件路径；读取失败或缺证也落包，不执行任何业务或 TC 写动作
   * @throws Exception 输入身份非法或无法保存证据时抛出
   */
  public static Path capture(
      Path artifacts,
      String phase,
      String xid,
      long payBranch,
      long userBranch,
      Window window,
      Path anchorArtifact)
      throws Exception {
    // 1. 固定原始开始窗口；仅完整读取后的缺证可短时重读，不补任何 TC 动作。
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    Map<String, Object> anchor = anchorReference(artifacts, anchorArtifact, window);
    List<Map<String, Object>> attempts = new ArrayList<>();
    Map<String, Object> latest;
    String previousEnd = null;
    while (true) {
      Path attempt =
          captureOnce(
              artifacts,
              phase,
              xid,
              payBranch,
              userBranch,
              window,
              anchorArtifact,
              anchor,
              previousEnd);
      byte[] bytes = Files.readAllBytes(attempt);
      latest = JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {});
      Map<String, Object> reference = new LinkedHashMap<>();
      reference.put("relativePath", artifacts.relativize(attempt).toString().replace('\\', '/'));
      reference.put("sha256", sha256(bytes));
      reference.put("bytes", bytes.length);
      reference.put("windowStartedAt", latest.get("windowStartedAt"));
      reference.put("windowEndedAt", latest.get("windowEndedAt"));
      reference.put("status", latest.get("status"));
      attempts.add(reference);
      if (!shouldRetry(latest) || attempts.size() >= 4 || System.nanoTime() >= deadline) break;
      previousEnd = (String) latest.get("windowEndedAt");
      TimeUnit.MILLISECONDS.sleep(250);
      if (System.nanoTime() >= deadline) break;
    }

    // 2. 原尝试包一律保留；另建最终包引用全部窗口和摘要，不将自身 SHA 递归写入。
    latest.put("attemptArtifacts", attempts);
    latest.put("attemptCount", attempts.size());
    latest.put("retryPolicy", "INCOMPLETE_AND_SOURCE_OK_ONLY_MAX_4_INTERVAL_250MS_DEADLINE_10S");
    latest.put("finalizedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
    return write(artifacts, phase + "-final", latest);
  }

  /** 完整业务事实缺证才允许重读；默认日志缺少提交分支 ACK 不要求补到该不存在的记录。 */
  static boolean shouldRetry(Map<String, Object> bundle) {
    return "INCOMPLETE".equals(bundle.get("status"))
        && bundle.get("sourceRead") instanceof Map<?, ?> source
        && "OK".equals(source.get("status"));
  }

  /** 单次观察只写一个独立包；外层重读保留相同 start 并推进实际 end。 */
  private static Path captureOnce(
      Path artifacts,
      String phase,
      String xid,
      long payBranch,
      long userBranch,
      Window window,
      Path anchorArtifact,
      Map<String, Object> anchor,
      String previousEnd)
      throws Exception {
    // 1. 信任锚点由调用方已核的持久行提供；仅接受本轮有界窗口与合法身份。
    require(phase != null && phase.matches("[A-Za-z0-9_-]{1,64}"), "phase invalid");
    require(
        xid != null && xid.matches("[A-Za-z0-9_.-]+:[1-9][0-9]{0,4}:[1-9][0-9]{0,18}"),
        "xid invalid");
    require(payBranch > 0 && userBranch > 0 && payBranch != userBranch, "branches invalid");
    Instant hostCaptureStartedAt = Instant.now();
    Map<String, Object> anchorBefore = anchorReference(artifacts, anchorArtifact, window);
    Window endWindow = markWindowStart();
    String clockStatus = clockStatus(window, endWindow, previousEnd);
    boolean anchorValid =
        "OK".equals(anchor.get("status"))
            && "OK".equals(anchorBefore.get("status"))
            && anchor.get("sha256").equals(anchorBefore.get("sha256"));

    // 2. 固定 Docker 命令只读身份和日志；错误通道不混入原生事实。
    Read logs =
        anchorValid && "OK".equals(clockStatus)
            ? read(
                List.of(
                    "docker",
                    "logs",
                    "--timestamps",
                    "--since",
                    window.sourceTime,
                    "--until",
                    endWindow.sourceTime,
                    "--tail",
                    Integer.toString(LINE_BOUND),
                    CONTAINER))
            : new Read(new byte[0], new byte[0], "NOT_READ");
    Read after = inspect();
    Map<String, Object> anchorAfter = anchorReference(artifacts, anchorArtifact, window);
    String sourceStatus =
        !"OK".equals(anchor.get("status"))
            ? anchor.get("status").toString()
            : !anchorValid
                    || !"OK".equals(anchorAfter.get("status"))
                    || !anchor.get("sha256").equals(anchorAfter.get("sha256"))
                ? "SOURCE_CHANGED"
                : !"OK".equals(clockStatus)
                    ? clockStatus
                    : !"OK".equals(after.status) || !validContainer(after.stdout)
                        ? "SOURCE_UNAVAILABLE"
                        : !window.tcIdentitySha256.equals(sha256(after.stdout))
                            ? "SOURCE_CHANGED"
                            : logs.status;
    Map<String, Object> facts =
        "OK".equals(sourceStatus)
            ? parseWindow(logs.stdout, xid, payBranch, userBranch, window, endWindow, previousEnd)
            : unavailable(sourceStatus, xid, payBranch, userBranch);
    if (List.of("BOUND_EXCEEDED", "PARSE_INCOMPLETE").contains(facts.get("status")))
      sourceStatus = facts.get("status").toString();
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("status", sourceStatus);
    source.put("container", CONTAINER);
    source.put("containerIdentitySha256", endWindow.tcIdentitySha256);
    source.put(
        "identityStable",
        "OK".equals(clockStatus)
            && "OK".equals(after.status)
            && validContainer(after.stdout)
            && window.tcIdentitySha256.equals(sha256(after.stdout)));
    source.put("declaredVersion", "1.5.2");
    source.put(
        "nativeSourceVersion", "seata/seata/v1.5.2/server/coordinator/AbstractCore+DefaultCore");
    try (InputStream implementation =
        PayTccTcBranchEvidence.class.getResourceAsStream("PayTccTcBranchEvidence.class")) {
      source.put("collectorClassSha256", sha256(implementation.readAllBytes()));
    }
    source.put(
        "commandTemplate",
        "docker logs --timestamps --since UTC --until UTC --tail 5000 cloud-mall-seata");
    source.put("stdoutBytes", logs.stdout.length);
    source.put("stdoutSha256", sha256(logs.stdout));
    source.put("stderrBytes", logs.stderr.length);
    source.put("stderrSha256", sha256(logs.stderr));
    source.put("readStatuses", List.of(window.status, endWindow.status, logs.status, after.status));
    source.put("endAnchor", safeWindow(endWindow));

    // 3. 仅保存安全结构与指纹；文件自身 SHA 由 fixture 外层 manifest 记录。
    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("schemaVersion", "CM18_TC_BRANCH_EVIDENCE_V1");
    bundle.put("source", "SEATA_1_5_2_SERVER_NATIVE_DOCKER_LOG");
    bundle.put("scope", "ISOLATED_TEST_TC_SERVER_OBSERVATION");
    bundle.put("automaticActionsAllowed", false);
    bundle.put("runId", artifacts.toAbsolutePath().normalize().getFileName().toString());
    bundle.put("phase", phase);
    bundle.put("windowStartedAt", window.sourceTime);
    bundle.put("windowEndedAt", endWindow.sourceTime);
    bundle.put("windowClockSource", "DOCKER_DAEMON_SYSTEM_TIME");
    bundle.put("sourceStartAnchor", safeWindow(window));
    bundle.put("anchorArtifactManifest", anchor);
    bundle.put("captureClockDomain", "HOST_AUDIT_ONLY");
    bundle.put("captureStartedAt", hostCaptureStartedAt.atOffset(ZoneOffset.ofHours(8)).toString());
    bundle.put("captureEndedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
    bundle.put("sourceRead", source);
    bundle.putAll(facts);
    bundle.put("factsDigestFields", new ArrayList<>(facts.keySet()));
    bundle.put("factsDigest", sha256(JSON.writeValueAsBytes(facts)));
    return write(artifacts, phase, bundle);
  }

  /**
   * 在首次 begin 前读取同源起点；调用方须原样 CREATE_NEW 保存并核 status 后才开始业务。
   *
   * @return 只含双时钟审计、状态与身份指纹的不可变锚点；失败不回退到主机时间
   */
  public static Window markWindowStart() {
    String hostStart = Instant.now().toString();
    long started = System.nanoTime();
    Read before = inspect();
    Read clock = read(List.of("docker", "info", "--format", "{{.SystemTime}}|{{.ID}}"));
    Read after = inspect();
    String sourceTime = null, daemon = null;
    String status =
        !"OK".equals(before.status) || !"OK".equals(clock.status) || !"OK".equals(after.status)
            ? "SOURCE_UNAVAILABLE"
            : !validContainer(before.stdout) || !validContainer(after.stdout)
                ? "SOURCE_UNAVAILABLE"
                : !java.util.Arrays.equals(before.stdout, after.stdout) ? "SOURCE_CHANGED" : "OK";
    if ("OK".equals(status)) {
      try {
        String[] fields = decode(clock.stdout).strip().split("\\|", -1);
        require(fields.length == 2 && fields[0].endsWith("Z"), "daemon clock invalid");
        require(fields[1].matches("[A-Za-z0-9:_-]{1,128}"), "daemon identity invalid");
        sourceTime = Instant.parse(fields[0]).toString();
        daemon = hash(fields[1]);
      } catch (Exception exception) {
        status = "PARSE_INCOMPLETE";
      }
    }
    return new Window(
        hostStart,
        Instant.now().toString(),
        sourceTime,
        daemon,
        status,
        sha256(before.stdout),
        System.nanoTime() - started);
  }

  /** 同源时界是实际 parser 的唯一时间输入；主机墙钟仅供审计。 */
  static Map<String, Object> parseWindow(
      byte[] raw,
      String xid,
      long payBranch,
      long userBranch,
      Window start,
      Window end,
      String previousEnd) {
    String status = clockStatus(start, end, previousEnd);
    return "OK".equals(status)
        ? parse(
            raw,
            xid,
            payBranch,
            userBranch,
            Instant.parse(start.sourceTime),
            Instant.parse(end.sourceTime))
        : unavailable(status, xid, payBranch, userBranch);
  }

  private static String clockStatus(Window start, Window end, String previousEnd) {
    if (!"OK".equals(start.status)) return start.status;
    if (!"OK".equals(end.status)) return end.status;
    if (!java.util.Objects.equals(start.daemonIdentitySha256, end.daemonIdentitySha256)
        || !java.util.Objects.equals(start.tcIdentitySha256, end.tcIdentitySha256))
      return "SOURCE_CHANGED";
    try {
      Instant first = Instant.parse(start.sourceTime), last = Instant.parse(end.sourceTime);
      if (last.isBefore(first) || previousEnd != null && last.isBefore(Instant.parse(previousEnd)))
        return "CLOCK_REGRESSION";
      return last.isAfter(first.plusSeconds(1800)) ? "BOUND_EXCEEDED" : "OK";
    } catch (Exception exception) {
      return "PARSE_INCOMPLETE";
    }
  }

  private static Map<String, Object> safeWindow(Window window) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("hostRequestStartedAt", window.hostRequestStartedAt);
    result.put("hostRequestEndedAt", window.hostRequestEndedAt);
    result.put("sourceTime", window.sourceTime);
    result.put("daemonIdentitySha256", window.daemonIdentitySha256);
    result.put("status", window.status);
    result.put("tcIdentitySha256", window.tcIdentitySha256);
    result.put("requestElapsedNanos", window.requestElapsedNanos);
    return result;
  }

  /** 锚点文件只能来自调用方 manifest 的当前 artifacts；前后内容/hash不稳定即缺证。 */
  private static Map<String, Object> anchorReference(Path artifacts, Path artifact, Window window)
      throws Exception {
    Path root = artifacts.toAbsolutePath().normalize(),
        owned = artifact.toAbsolutePath().normalize();
    require(
        root.equals(owned.getParent())
            && owned.getFileName().toString().matches("tc-window-anchor-[0-9a-f-]{36}\\.json"),
        "anchor ownership invalid");
    Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("relativePath", root.relativize(owned).toString().replace('\\', '/'));
    reference.put("status", "SOURCE_UNAVAILABLE");
    try {
      require(owned.toRealPath().getParent().equals(root.toRealPath()), "anchor ownership invalid");
      byte[] bytes;
      try (InputStream stream = Files.newInputStream(owned)) {
        bytes = stream.readNBytes(8193);
      }
      reference.put("bytes", bytes.length);
      reference.put("sha256", sha256(bytes));
      if (bytes.length > 8192) reference.put("status", "BOUND_EXCEEDED");
      else {
        Map<String, Object> saved =
            JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        reference.put(
            "status",
            java.util.Arrays.equals(
                    JSON.writeValueAsBytes(saved), JSON.writeValueAsBytes(safeWindow(window)))
                ? "OK"
                : "SOURCE_CHANGED");
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      reference.put("status", "SOURCE_UNAVAILABLE");
    }
    return reference;
  }

  /** 已知 source 字段才允许解析；坏 UTF-8 不以替代字符静默接受。 */
  private static String decode(byte[] raw) throws Exception {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
        .toString();
  }

  private static Path write(Path artifacts, String phase, Map<String, Object> bundle)
      throws Exception {
    Files.createDirectories(artifacts);
    Path destination =
        artifacts.resolve("tc-branch-evidence-" + phase + "-" + UUID.randomUUID() + ".json");
    Files.writeString(
        destination,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(bundle),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    return destination;
  }

  /** 受控 parser 单测只传文本，不启动 Docker；超限/坏 UTF-8 不保留部分成功。 */
  static Map<String, Object> parse(
      byte[] raw, String xid, long payBranch, long userBranch, Instant start, Instant end) {
    if (raw.length > BYTE_BOUND) return unavailable("BOUND_EXCEEDED", xid, payBranch, userBranch);
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
    } catch (Exception exception) {
      return unavailable("PARSE_INCOMPLETE", xid, payBranch, userBranch);
    }
    List<String> lines = text.lines().toList();
    if (lines.size() >= LINE_BOUND)
      return unavailable("BOUND_EXCEEDED", xid, payBranch, userBranch);
    Map<String, Object> result = empty(xid, payBranch, userBranch);
    List<Map<String, Object>> events = new ArrayList<>();
    boolean conflict = false, committed = false, rolledBack = false;
    int[] registrations = new int[2], rollbacks = new int[2];
    for (String line : lines) {
      Matcher prefix = PREFIX.matcher(line);
      if (!prefix.matches()) continue;
      Instant timestamp;
      try {
        timestamp = Instant.parse(prefix.group(1));
      } catch (Exception exception) {
        continue;
      }
      if (timestamp.isBefore(start) || timestamp.isAfter(end)) continue;
      String logger = prefix.group(2), message = prefix.group(3), type = null, resource = null;
      String branch = null;
      Matcher register = REGISTER.matcher(message),
          rollback = ROLLBACK.matcher(message),
          global = GLOBAL.matcher(message);
      if (logger.endsWith(".AbstractCore") && register.matches() && xid.equals(register.group(1))) {
        branch = register.group(2);
        resource = register.group(3);
        int index =
            branch.equals(Long.toString(payBranch))
                ? 0
                : branch.equals(Long.toString(userBranch)) ? 1 : -1;
        if (index < 0
            || !resource.equals(
                index == 0 ? "cloudmallPayDebitReceipt" : "cloudmallUserBalanceDebit"))
          conflict = true;
        else registrations[index]++;
        type = "REGISTER";
      } else if (logger.equals("io.seata.server.coordinator.DefaultCore")
          && rollback.matches()
          && xid.equals(rollback.group(1))) {
        branch = rollback.group(2);
        int index =
            branch.equals(Long.toString(payBranch))
                ? 0
                : branch.equals(Long.toString(userBranch)) ? 1 : -1;
        if (index < 0) conflict = true;
        else rollbacks[index]++;
        type = "PHASE_TWO_ROLLBACK";
      } else if (logger.equals("io.seata.server.coordinator.DefaultCore")
          && global.matches()
          && xid.equals(global.group(2))) {
        if (global.group(1).startsWith("Committing")) committed = true;
        else rolledBack = true;
        type = global.group(1).startsWith("Committing") ? "GLOBAL_COMMIT" : "GLOBAL_ROLLBACK";
      }
      if (type == null
          && (message.startsWith("Register branch successfully,")
              || message.startsWith("Rollback branch transaction successfully,")
              || message.startsWith("Committing global transaction is successfully done,")
              || message.startsWith("Rollback global transaction successfully,"))
          && Pattern.compile("xid = " + Pattern.quote(xid) + "(?:[, .]|$)").matcher(message).find())
        return unavailable("PARSE_INCOMPLETE", xid, payBranch, userBranch);
      if (type != null) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("observedAt", timestamp.toString());
        if (branch != null) event.put("branchSha256", hash(branch));
        if (resource != null)
          event.put(
              "resource",
              resource.equals("cloudmallPayDebitReceipt")
                      || resource.equals("cloudmallUserBalanceDebit")
                  ? resource
                  : "UNEXPECTED_RESOURCE");
        event.put("lineSha256", hash(line));
        events.add(event);
      }
    }
    conflict |= committed && (rolledBack || rollbacks[0] > 0 || rollbacks[1] > 0);
    List<String> missing = new ArrayList<>();
    if (registrations[0] == 0 || registrations[1] == 0) missing.add("MISSING_REGISTER");
    if (!committed && !rolledBack) missing.add("MISSING_GLOBAL_OUTCOME");
    if (committed) missing.add("TC_COMMIT_BRANCH_ACK_NOT_LOGGED");
    if (rolledBack && (rollbacks[0] == 0 || rollbacks[1] == 0))
      missing.add("MISSING_BRANCH_ROLLBACK");
    if (conflict) missing.add("PROTOCOL_CONFLICT");
    result.put(
        "status",
        conflict
            ? "PROTOCOL_CONFLICT"
            : registrations[0] > 0
                    && registrations[1] > 0
                    && (committed || rolledBack && rollbacks[0] > 0 && rollbacks[1] > 0)
                ? "OBSERVED"
                : "INCOMPLETE");
    result.put(
        "globalOutcome",
        conflict
            ? "CONFLICT"
            : committed
                ? "SERVER_GLOBAL_COMMIT_OBSERVED"
                : rolledBack ? "SERVER_GLOBAL_ROLLBACK_OBSERVED" : "UNOBSERVED");
    result.put("directionProvenByTc", !conflict && (committed || rolledBack));
    result.put(
        "branchDetailsObserved",
        !conflict
            && registrations[0] > 0
            && registrations[1] > 0
            && rollbacks[0] > 0
            && rollbacks[1] > 0);
    result.put(
        "pay", branch(payBranch, "cloudmallPayDebitReceipt", registrations[0], rollbacks[0]));
    result.put(
        "user", branch(userBranch, "cloudmallUserBalanceDebit", registrations[1], rollbacks[1]));
    result.put("events", events);
    result.put("missingEvidence", missing);
    return result;
  }

  private static Map<String, Object> empty(String xid, long payBranch, long userBranch) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("xidSha256", hash(xid));
    result.put("globalOutcome", "UNOBSERVED");
    result.put("directionProvenByTc", false);
    result.put("branchDetailsObserved", false);
    result.put("pay", branch(payBranch, "cloudmallPayDebitReceipt", 0, 0));
    result.put("user", branch(userBranch, "cloudmallUserBalanceDebit", 0, 0));
    result.put("events", List.of());
    return result;
  }

  private static Map<String, Object> branch(
      long identity, String resource, int registrations, int rollbacks) {
    return Map.of(
        "branchSha256",
        hash(Long.toString(identity)),
        "resource",
        resource,
        "registrationObserved",
        registrations > 0,
        "registrationCount",
        registrations,
        "phaseTwo",
        rollbacks > 0 ? "SERVER_PHASE_TWO_ROLLBACK_OBSERVED" : "ACK_UNOBSERVED",
        "rollbackCount",
        rollbacks,
        "commitAck",
        "ACK_UNOBSERVED");
  }

  private static Map<String, Object> unavailable(
      String status, String xid, long payBranch, long userBranch) {
    Map<String, Object> result = empty(xid, payBranch, userBranch);
    result.put("status", status);
    result.put("missingEvidence", List.of(status));
    return result;
  }

  private static Read inspect() {
    return read(
        List.of(
            "docker", "inspect", "--format", "{{.Id}} {{.Config.Image}} {{.Image}}", CONTAINER));
  }

  private static boolean validContainer(byte[] raw) {
    return new String(raw, StandardCharsets.UTF_8)
        .strip()
        .matches("[0-9a-f]{64} seataio/seata-server:1\\.5\\.2 sha256:[0-9a-f]{64}");
  }

  /** 仅管理本方法创建的 Docker CLI 进程；两个通道并行有界读取以避免管道死锁。 */
  private static Read read(List<String> command) {
    Process process = null;
    ExecutorService readers =
        Executors.newFixedThreadPool(
            2,
            runnable -> {
              Thread reader = new Thread(runnable, "tc-evidence-stream");
              reader.setDaemon(true);
              return reader;
            });
    byte[] stdout = new byte[0], stderr = new byte[0];
    try {
      process = new ProcessBuilder(command).start();
      Process owned = process;
      Future<byte[]> output =
          readers.submit(() -> bounded(owned.getInputStream(), BYTE_BOUND, owned));
      Future<byte[]> error =
          readers.submit(() -> bounded(owned.getErrorStream(), ERROR_BOUND, owned));
      boolean exited = process.waitFor(10, TimeUnit.SECONDS);
      if (!exited) process.destroyForcibly();
      stdout = output.get(2, TimeUnit.SECONDS);
      stderr = error.get(2, TimeUnit.SECONDS);
      if (stdout.length > BYTE_BOUND || stderr.length > ERROR_BOUND)
        return new Read(stdout, stderr, "BOUND_EXCEEDED");
      return new Read(
          stdout, stderr, exited && process.exitValue() == 0 ? "OK" : "SOURCE_UNAVAILABLE");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new Read(stdout, stderr, "SOURCE_UNAVAILABLE");
    } catch (Exception exception) {
      return new Read(stdout, stderr, "SOURCE_UNAVAILABLE");
    } finally {
      if (process != null) {
        if (process.isAlive()) process.destroyForcibly();
        try {
          process.getInputStream().close();
          process.getErrorStream().close();
          process.getOutputStream().close();
          process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        } catch (Exception ignored) {
          // 读取失败已按 SOURCE_UNAVAILABLE 返回，不保存完整异常或假称事实。
        }
      }
      readers.shutdownNow();
    }
  }

  private static byte[] bounded(InputStream stream, int bound, Process owned) throws Exception {
    byte[] bytes = stream.readNBytes(bound + 1);
    if (bytes.length > bound) owned.destroyForcibly();
    return bytes;
  }

  private static String hash(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }

  /** 子进程原始数据只在内存中短时存在，不可直接序列化。 */
  private record Read(byte[] stdout, byte[] stderr, String status) {}

  /**
   * 仅由本 fixture 在 begin 前读取并原样留存；墙钟不参与日志时间裁剪。
   *
   * @param hostRequestStartedAt 只读采样的主机开始时间，仅审计
   * @param hostRequestEndedAt 只读采样的主机结束时间，仅审计
   * @param sourceTime Docker daemon 返回的 UTC 时间，作为同源日志界限
   * @param daemonIdentitySha256 daemon 原身份的精确 UTF-8 指纹
   * @param status 读取/身份/格式检查结果，非 OK 禁止业务开始或继续补读
   * @param tcIdentitySha256 固定 TC 容器 ID/镜像组合指纹
   * @param requestElapsedNanos 使用单调时钟测得的采样耗时
   */
  public record Window(
      String hostRequestStartedAt,
      String hostRequestEndedAt,
      String sourceTime,
      String daemonIdentitySha256,
      String status,
      String tcIdentitySha256,
      long requestElapsedNanos) {}
}
