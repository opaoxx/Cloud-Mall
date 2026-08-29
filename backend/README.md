# CloudMall backend

用 IntelliJ IDEA 以 Maven 方式打开本目录，先执行 `mvn clean test`，再分别运行各模块的 `*Application`。
业务服务端口依次为 8080-8086。Redis、MySQL、RabbitMQ 等连接参数位于各模块 `src/main/resources/application.yml`，本地约定账号密码为 root/root。
本首轮实现保留可脱离外部中间件运行的内存业务样例，Redis、MySQL、MQ、Nacos、ES 等配置为接入位；正式联调前需启动计划书规定的 Docker 中间件并补齐对应持久化适配。
