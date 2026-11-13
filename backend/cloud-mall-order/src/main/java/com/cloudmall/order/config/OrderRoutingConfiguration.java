package com.cloudmall.order.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Guards explicit routing before any data source or maintenance DDL is instantiated. */
@Configuration
public class OrderRoutingConfiguration {
  /** Original default routing remains unchanged. */
  static final String DYNAMIC_MONTHLY = "dynamic-monthly";

  /** Only this explicit opt-in permits the published logical snapshot. */
  static final String SHARDING_MONTHLY = "sharding-monthly";

  /** Fixed safe rejection category, containing no connection settings. */
  static final String UNSUPPORTED = "ORDER_ROUTING_UNSUPPORTED";

  /** Registers a metadata-only guard before application singleton construction. */
  @Bean
  public static BeanFactoryPostProcessor orderRoutingProfileGuard(Environment environment) {
    return beanFactory -> {
      validate(environment);
      if (SHARDING_MONTHLY.equals(mode(environment))) rejectConflictingBindings(beanFactory);
    };
  }

  /** Parses exactly the same trimmed mode for validation, conditions and lifecycle branches. */
  static String mode(Environment environment) {
    return environment.getProperty("cloudmall.order.routing.mode", DYNAMIC_MONTHLY).trim();
  }

  /** Rejects legacy profiles, unknown modes and unsupported automatic Seata combinations. */
  static void validate(Environment environment) {
    boolean oldProfile =
        Arrays.stream(
                environment.getActiveProfiles().length == 0
                    ? environment.getDefaultProfiles()
                    : environment.getActiveProfiles())
            .map(profile -> profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT))
            .anyMatch("sharding"::equals);
    String selected = mode(environment);
    if (oldProfile || (!DYNAMIC_MONTHLY.equals(selected) && !SHARDING_MONTHLY.equals(selected)))
      throw unsupported();
    if (SHARDING_MONTHLY.equals(selected)
        && environment.getProperty(
            "spring.main.allow-bean-definition-overriding", Boolean.class, false))
      throw unsupported();
    if (SHARDING_MONTHLY.equals(selected)
        && environment.getProperty("seata.enabled", Boolean.class, true)
        && !"false"
            .equalsIgnoreCase(
                environment.getProperty("seata.enable-auto-data-source-proxy", "").trim()))
      throw unsupported();
  }

  /** Uses definition types only; neither getBean nor getConnection is permitted here. */
  private static void rejectConflictingBindings(ConfigurableListableBeanFactory factory) {
    Set<String> names = new LinkedHashSet<>(Arrays.asList(factory.getBeanDefinitionNames()));
    names.addAll(Arrays.asList(factory.getSingletonNames()));
    for (String name : names) {
      Class<?> type = factory.getType(name, false);
      boolean business =
          type != null
              && (DataSource.class.isAssignableFrom(type)
                  || JdbcTemplate.class.isAssignableFrom(type)
                  || PlatformTransactionManager.class.isAssignableFrom(type));
      if (business) {
        if (!factory.containsBeanDefinition(name)) throw unsupported();
        var definition = factory.getBeanDefinition(name);
        String configuration = definition.getFactoryBeanName();
        Class<?> configurationType =
            configuration == null ? null : factory.getType(configuration, false);
        if (configurationType == null
            || !OrderShardingRuntimeConfiguration.class.isAssignableFrom(configurationType)
            || !name.equals(definition.getFactoryMethodName())
            || !Set.of("dataSource", "jdbcTemplate", "transactionManager").contains(name))
          throw unsupported();
      } else if (type == null && factory.containsBeanDefinition(name)) {
        String className = factory.getBeanDefinition(name).getBeanClassName();
        if (className != null) {
          try {
            if (FactoryBean.class.isAssignableFrom(
                Class.forName(className, false, factory.getBeanClassLoader()))) throw unsupported();
          } catch (ClassNotFoundException unreadable) {
            throw unsupported();
          }
        } else throw unsupported();
      }
    }
  }

  /** Selects the original Repository only when the same mode parser selects dynamic routing. */
  public static final class DynamicMonthly implements Condition {
    /** Evaluates metadata without creating any data source. */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return DYNAMIC_MONTHLY.equals(mode(context.getEnvironment()));
    }
  }

  /** Selects only the explicit logical production graph. */
  public static final class ShardingMonthly implements Condition {
    /** Evaluates metadata without creating any data source. */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return SHARDING_MONTHLY.equals(mode(context.getEnvironment()));
    }
  }

  private static IllegalStateException unsupported() {
    return new IllegalStateException(UNSUPPORTED);
  }
}
