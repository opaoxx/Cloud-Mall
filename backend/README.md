# CloudMall backend

用 IntelliJ IDEA 打开项目根目录 `F:\Cloud-Mall`，在 Maven 面板加载根目录 `pom.xml`，先执行 `mvn clean test`，再分别运行各模块的 `*Application`。后端模块源码仍位于 `backend/`。
业务服务端口依次为 8080-8086。Redis、MySQL、RabbitMQ 等连接参数位于各模块 `src/main/resources/application.yml`，本地约定账号密码为 root/root。
本首轮实现保留可脱离外部中间件运行的内存业务样例，Redis、MySQL、MQ、Nacos、ES 等配置为接入位；正式联调前需启动计划书规定的 Docker 中间件并补齐对应持久化适配。

## IDEA 运行时要求

项目固定使用 JDK 17（Maven 编译目标也是 Java 17）。order/pay 使用 Seata 1.5.2，不能使用 JDK 21 直接启动；若因本机环境必须在 JDK 21 下临时运行，请在 IDEA 的 Run/Debug Configuration 的 VM options 中加入：

```text
--add-opens java.base/java.lang=ALL-UNNAMED
```

该参数仅用于 JDK 21 对 Seata 1.5.2 CGLIB 的兼容，推荐仍将 IDEA Project SDK、Maven Runner JRE 和各服务运行 JRE 统一设为 JDK 17。
