# CM-20261010：Sentinel Dashboard 宿主保留端口映射评审

## 1. 结论与范围

结论：**GO，限定修复 Docker Desktop 重启后 Sentinel Dashboard 宿主端口映射失败；仅调整宿主 published port，不改变 Sentinel 容器端口、镜像、认证、健康检查或业务 Sentinel 规则。**

当前证据：`docker compose start sentinel-dashboard` 在宿主 `8858` 失败并报告 forbidden；现有两族 `netsh` excluded range 已排除 `8773–8872`，`8858` 当前无监听、无 Java/javaw 进程。该证据支持宿主保留端口/映射边界问题，不支持修改 Sentinel Java、镜像或认证配置。

本批不保证 Docker Desktop 重启后系统永远不会重新保留高端口；每次启动前仍可使用既有只读端口诊断确认。不得停止 WinNAT/WSL、删除 excluded range、改防火墙或执行管理员级系统修复。

## 2. 最小映射与配置契约

### 2.1 Compose

Sentinel Dashboard 继续使用：

- 镜像 `bladex/sentinel-dashboard:1.8.6`；
- 容器监听 `8858`；
- 容器健康检查仍访问 `http://127.0.0.1:8858/`；
- `TZ=Asia/Shanghai`、`JAVA_TOOL_OPTIONS=-Dauth.username=root -Dauth.password=root`、container name、network、restart policy 和依赖保持；
- 宿主映射改为字面 `18858:8858`。

不得把 18858 用作容器间地址；Docker network 内仍使用 service name 与容器端口 8858。Dashboard 镜像、账号密码和健康检查不因端口迁移改变。

### 2.2 Gateway/Order/Stock 本地客户端

三服务 Sentinel transport dashboard 默认值统一改为：

```text
localhost:18858
```

并允许环境变量 `CLOUDMALL_SENTINEL_DASHBOARD` 覆盖完整 `host:port` 值；覆盖只影响 Sentinel 客户端注册/心跳目标，不改变服务监听端口、Nacos 地址、业务 Sentinel 资源、规则 dataId/group 或 QPS。

容器内运行的业务服务若连接 Compose 内 Dashboard，必须使用 `sentinel-dashboard:8858` 或等价 service-name/container-port 配置；不得把宿主 `localhost:18858` 写进容器内部通信契约。现有本地 file-mode/host 端口语义与 Docker 内部网络语义必须分开说明。

## 3. 不变边界

- Sentinel Dashboard image、认证、healthcheck、volume/network、container name 和依赖关系不变。
- Gateway、Order、Stock 的 Sentinel eager 开关、资源名、规则校验、Nacos dataId/group、fallback QPS、熔断/限流响应和业务端口不变。
- 不修改 Windows excluded ranges、WinNAT、WSL、Docker Desktop 全局设置或系统服务；不杀进程、不释放系统端口。
- 不修改业务数据库、消息、API、Feign、Seata、Redis、RabbitMQ 或 CM18 资金协议。
- 该批只处理 Dashboard 控制面可达性，不宣称 Sentinel 规则已经写入 Nacos、业务服务已重启或全链路限流已验证。

## 4. 验收矩阵

1. 只读诊断记录旧 `8858` 的 forbidden/excluded 证据、无监听/Java 证据，并记录当前 `18858` 不在已排除范围且无 foreign listener；诊断继续使用现有只读命令，不改变系统状态。
2. `docker compose config` 显示 Sentinel 唯一 published 映射为 `18858:8858`；容器内部仍为 8858，healthcheck 仍访问容器 loopback 8858。
3. `docker compose start/recreate sentinel-dashboard` 后容器 healthy；宿主 `http://localhost:18858` 可访问登录页/健康响应，root/root 认证行为保持；不要求 8858 宿主端口继续可用。
4. Gateway、Order、Stock 默认配置文本为 `localhost:18858`，`CLOUDMALL_SENTINEL_DASHBOARD` 可覆盖；覆盖值不被静默拼接、截断或替换，非法/缺失值沿现有配置启动校验策略处理。
5. 本地服务连接 Dashboard 的验证使用 host `localhost:18858`；容器内验证使用 `sentinel-dashboard:8858`，证明不把 host 端口用于 Docker 内部通信。Sentinel 规则/认证/业务 QPS 回归沿既有测试，不在本批新增业务场景。
6. 原有 volumes、network、容器认证、业务固定端口和其他中间件映射 checksum/配置差异仅包含 Sentinel host published port 与三服务 dashboard default/override 约定。
7. 回退验证将映射和默认值恢复为旧 `8858`，并明确当前宿主仍可能 forbidden；回退不得修改系统保留范围或伪称端口已恢复。

## 5. README/诊断说明边界

部署说明应记录 Dashboard 宿主入口 `http://localhost:18858`、容器内部入口 `http://sentinel-dashboard:8858`、默认环境变量覆盖方式和 root/root 认证。必须保留“无 PID 监听仍可能被 Windows excluded range 拒绝”的诊断说明，并注明高端口只是本次宿主排除范围外的可用选择，不是永久系统保证。

既有只读端口诊断继续报告监听、PID、excluded range、foreign listener 和项目容器占用；不得因本批增加自动改端口、自动杀进程、删除保留范围或管理员命令。

## 6. 实施边界

实现限定为：Docker Compose Sentinel published mapping、Gateway/Order/Stock Sentinel dashboard 默认配置及 `CLOUDMALL_SENTINEL_DASHBOARD` 覆盖、部署说明和既有只读诊断验证。不得修改业务实现、业务测试、Sentinel 规则逻辑、容器内部端口或系统保留配置。本批不运行 Maven，也不扩展 CM18 资金主项范围。
