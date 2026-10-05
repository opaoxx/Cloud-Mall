package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/** 用实际 OpenFeign 属性类核对项目 YAML，避免跨版本前缀被静默忽略。 */
class FeignTimeoutConfigurationTest {
  /** 三个 Feign 调用服务必须绑定冻结的连接和读取超时。 */
  @ParameterizedTest
  @ValueSource(strings = {"order", "cart", "pay"})
  void moduleYamlBindsTimeoutsToTheInstalledFeignVersion(String module) throws Exception {
    Path repository = Path.of("").toAbsolutePath();
    while (repository != null && !Files.isDirectory(repository.resolve("backend/cloud-mall-order")))
      repository = repository.getParent();
    assertTrue(repository != null, "无法定位标准 Maven 项目根目录");
    Path configuration = repository.resolve(
        "backend/cloud-mall-" + module + "/src/main/resources/application.yml");
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    new YamlPropertySourceLoader().load("module-timeouts", new FileSystemResource(configuration))
        .forEach(source -> environment.getPropertySources().addLast(source));
    FeignClientProperties properties = Binder.get(environment)
        .bind("feign.client", Bindable.of(FeignClientProperties.class)).get();

    assertEquals(3000, properties.getConfig().get("default").getConnectTimeout());
    assertEquals(5000, properties.getConfig().get("default").getReadTimeout());
  }
}
