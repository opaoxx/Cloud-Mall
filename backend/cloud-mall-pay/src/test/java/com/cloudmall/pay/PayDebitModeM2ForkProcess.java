package com.cloudmall.pay;

import com.cloudmall.pay.config.PayDebitIntentTableInitializer;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只启动 M2 指定生产组件的独立 JVM；不扫描业务、自动配置或注册任何调度器。 */
public final class PayDebitModeM2ForkProcess {
  /** 工具进程无可创建业务实例。 */
  private PayDebitModeM2ForkProcess() {}

  /** 启动真实 Spring 生命周期，保存安全失败链/实际 Environment 并以失败状态退出。 */
  public static void main(String[] arguments) throws Exception {
    // 1. 在 SpringApplication 之前阻止 classpath 原配置/bootstrap 访问外部系统。
    for (String key :
        Set.of(
            "spring.cloud.bootstrap.enabled",
            "spring.cloud.nacos.config.enabled",
            "spring.cloud.nacos.discovery.enabled",
            "spring.cloud.service-registry.auto-registration.enabled"))
      System.setProperty(key, "false");
    System.setProperty(
        "spring.config.location", "optional:classpath:/cm18-m2-empty-configuration/");
    System.setProperty("spring.config.name", "cm18-m2-isolated");
    String caseId = required(arguments, "m2.case");
    Path reportPath = Path.of(required(arguments, "m2.report"));
    boolean migration =
        Set.of("LEGACY_COLUMNS_MIGRATE", "INCOMPLETE_SCHEMA_REJECTED").contains(caseId);
    Map<String, Object> report =
        PayDebitModeM2MySqlIT.result(caseId, migration ? "R10" : "R08", Map.of());
    report.put("ready", false);
    report.put("businessCalls", 0);
    report.put("businessCallEvidence", "STRUCTURAL_NO_BUSINESS_BEANS_NO_AUTO_SCAN");
    report.put("databaseConfigured", migration);
    report.put("forkClassSha256", PayDebitModeM2MySqlIT.classSha(PayDebitModeM2ForkProcess.class));
    SpringApplication application =
        new SpringApplication(
            migration ? MigrationConfiguration.class : AdmissionConfiguration.class);
    application.setAdditionalProfiles(
        migration ? "cm18-m2-fork-migration" : "cm18-m2-fork-admission");
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.setLogStartupInfo(false);
    application.setDefaultProperties(
        Map.of(
            "spring.main.banner-mode",
            "off",
            "logging.level.root",
            "OFF",
            "spring.jmx.enabled",
            "false"));
    application.addListeners(
        (org.springframework.context.ApplicationListener<ApplicationEnvironmentPreparedEvent>)
            event -> report.put("effectiveProperties", effective(event.getEnvironment())));
    ConfigurableApplicationContext context = null;
    int exit = 2;
    try {
      // 2. PostConstruct 为原生产准入/迁移入口；测试不手调 validate 或重写迁移 DDL。
      context = application.run(arguments);
      report.put("effectiveProperties", effective(context.getEnvironment()));
      report.put(
          "newPaymentMode", context.getBean(PayDebitModeConfiguration.class).newPaymentMode());
      if (migration) {
        String schema = context.getEnvironment().getRequiredProperty("m2.schema");
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        PayDebitIntentMapper intents = context.getBean(PayDebitIntentMapper.class);
        String payNo = jdbc.queryForObject("SELECT pay_no FROM pay_record", String.class);
        report.put(
            "mapperPayMode",
            context.getBean(PayRecordMapper.class).selectByPayNo(payNo).get(0).debitMode);
        report.put("mapperIntentMode", intents.findByPayNo(payNo).debitMode);
        report.put(
            "findDueCount",
            intents.findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), 2).size());
        Map<String, Object> first = PayDebitModeM2MySqlIT.facts(jdbc, schema);
        intents.ensureTable();
        Map<String, Object> second = PayDebitModeM2MySqlIT.facts(jdbc, schema);
        report.put("factsAfterReentry", second);
        report.put("reentryFactsUnchanged", first.equals(second));
      }
      report.put("ready", true);
      exit = 0;
    } catch (Throwable failure) {
      report.put("failure", PayDebitModeM2MySqlIT.safeError(failure));
      report.put("failureGate", gate(failure));
      report.put(
          "productionValidateCauseObserved",
          stackContains(failure, PayDebitModeConfiguration.class, "validate"));
      report.put(
          "productionMigrationCauseObserved",
          stackContains(failure, PayDebitIntentMapper.class, "ensureTable")
              && stackContains(failure, PayDebitIntentTableInitializer.class, "prepareTable"));
    } finally {
      // 3. 无 ready 的异常不得吞成成功；context 先结束，再写不可变报告并明确退出。
      try {
        if (context != null) context.close();
      } catch (Throwable closeFailure) {
        report.put("ready", false);
        report.put("closeFailure", PayDebitModeM2MySqlIT.safeError(closeFailure));
        exit = 2;
      }
      report.put("contextReturned", context != null);
      report.put("returnedContextClosed", context == null ? null : !context.isActive());
      PayDebitModeM2MySqlIT.writeNew(reportPath, report);
    }
    System.exit(exit);
  }

  /** primary source 显式注册并仅在专属测试 profile 激活，仅导入原模式配置。 */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("cm18-m2-fork-admission")
  @Import(PayDebitModeConfiguration.class)
  static class AdmissionConfiguration {}

  /** 专属迁移 profile 额外注册真实有界数据源和原 Mapper/Initializer；没有业务调度器。 */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("cm18-m2-fork-migration")
  @Import(PayDebitModeConfiguration.class)
  static class MigrationConfiguration {
    @Bean(destroyMethod = "close")
    com.zaxxer.hikari.HikariDataSource dataSource(Environment environment) {
      String schema = environment.getRequiredProperty("m2.schema");
      String url = System.getenv("CM18_M2_JDBC_URL");
      if (!schema.matches("cm16m2_[0-9a-f]{32}")
          || url == null
          || !url.startsWith("jdbc:mysql://127.0.0.1:13307/" + schema + "?")
              && !url.startsWith("jdbc:mysql://localhost:13307/" + schema + "?"))
        throw new IllegalArgumentException("OWNED_MIGRATION_URL_REQUIRED");
      return PayDebitModeM2MySqlIT.pool(url);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source, Environment environment) {
      JdbcTemplate jdbc = PayDebitModeM2MySqlIT.jdbc(source);
      if (!environment
          .getRequiredProperty("m2.schema")
          .equals(jdbc.queryForObject("SELECT DATABASE()", String.class)))
        throw new IllegalStateException("OWNED_MIGRATION_CATALOG_CHANGED");
      return jdbc;
    }

    @Bean
    PayDebitIntentMapper intents(JdbcTemplate jdbc) {
      return new PayDebitIntentMapper(jdbc);
    }

    @Bean
    PayRecordMapper records(DataSource source) throws Exception {
      return PayDebitModeM2MySqlIT.recordMapper(source);
    }

    @Bean
    PayDebitIntentTableInitializer initializer(PayDebitIntentMapper mapper) {
      return new PayDebitIntentTableInitializer(mapper);
    }
  }

  private static Map<String, String> effective(Environment environment) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : PayDebitModeM2MySqlIT.baselineProperties().keySet())
      values.put(key, environment.getProperty(key));
    return values;
  }

  /** 只输出预先固定的错误族；未知异常不回显正文。 */
  private static String gate(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      String text = cause.getMessage();
      for (String feature :
          new String[] {"entry", "coordinator", "receipt", "bridge", "recovery", "delivery"})
        if (("TCC 新支付要求启用 tcc-" + feature).equals(text)) return "tcc-" + feature;
      if ("未知支付模式或 TCC 依赖未配置".equals(text)) return "UNSUPPORTED_MODE";
      if ("TCC 新支付要求启用 Seata 并关闭 AT 自动代理".equals(text)) return "SEATA_AT_ADMISSION";
    }
    return "OTHER_STARTUP_FAILURE";
  }

  private static boolean stackContains(Throwable failure, Class<?> type, String method) {
    for (int depth = 0; failure != null && depth < 20; depth++, failure = failure.getCause())
      for (StackTraceElement frame : failure.getStackTrace())
        if (frame.getClassName().equals(type.getName()) && frame.getMethodName().equals(method))
          return true;
    return false;
  }

  private static String required(String[] arguments, String key) {
    for (String argument : arguments)
      if (argument.startsWith("--" + key + "=")) return argument.substring(key.length() + 3);
    throw new IllegalArgumentException("REQUIRED_FORK_ARGUMENT_MISSING");
  }
}
