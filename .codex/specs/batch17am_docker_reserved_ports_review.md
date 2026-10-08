# CM-20260924-01：Docker Compose 保留端口冲突修复评审

## 1. 评审结论与范围

结论：**GO，限定修复 Docker Desktop Compose 的宿主机保留端口冲突；不停止系统 WinNAT/WSL、不修改业务固定端口、不执行管理员级系统操作。**

已复现：无 Java/javaw 残留，8088/8091 无监听，但 Windows IPv4/IPv6 excluded port range 覆盖 7998–8097，Compose 映射 Nginx 8088 与 Seata 8091 失败；Grafana/Kibana 可单独启动。因此该故障属于宿主机端口保留/映射边界，不应归因于 Java 进程或业务服务。

## 2. 最小映射调整

仅调整宿主机 published port，容器内部端口、服务名、network、volumes、hostname 和依赖关系保持：

- Nginx：host `18088:80`，容器内部仍 `80`；
- Seata：host `18091:8091`，容器内部仍 `8091`。

同步本地 file-mode Seata TC 连接配置：Order/Stock/User/Pay 默认 `127.0.0.1:18091`，并保留 `CLOUDMALL_SEATA_TC` 环境变量覆盖能力。Docker Compose 内部服务连接继续使用 Compose service name/container port，不把 host 端口用于容器间通信。

业务服务 Gateway/user/product/cart/order/stock/pay 的 8080–8086 端口冻结不变；不为解决中间件冲突改动它们。现有 XID 样例不变，仅更新真实连接地址契约。User 两项真实 IT 的默认 TC 地址和 common BackendP1RulesTest 配置契约同步为 18091。

## 3. 诊断与配置校验

建议增加只读 `deploy/check-local-ports.ps1`：

1. 使用 `docker compose config` 解析最终 published/container 端口；
2. 读取 `netsh interface ipv4/ipv6 show excludedportrange protocol=tcp` 和当前监听/PID 证据；
3. 允许已运行的本项目容器占用其自身 published port；
4. foreign listener 或 excluded range 冲突返回明确错误；
5. 业务 8080–8086 被占用/排除时只报告诊断，不自动改端口、不杀进程、不改系统保留范围；
6. 报告 PID/进程名、端口、来源类别，避免把“无 PID 但 excluded”误报为后台 Java 占用。

脚本只做配置解析和只读系统查询，不需要管理员权限；不能通过停止 WinNAT/WSL、删除 excluded range 或修改防火墙规避冲突。`docker compose config` 校验必须同时覆盖旧映射失败和新映射通过。

## 4. 验收矩阵

1. 旧 Nginx 8088/Seata 8091 映射在当前宿主机诊断中明确报告 excluded/不可用；新 18088/18091 映射通过。
2. 只重建 Nginx/Seata 后，所有常驻基础设施容器 healthy；Kibana/Grafana 可访问，Nginx HTTP 入口返回正常。
3. 业务服务连接本机 Seata 18091 成功；容器内部连接仍使用 8091/service name。
4. User 两项真实 IT、common BackendP1RulesTest、Order/Stock/Pay 连接契约和全仓测试使用 18091；XID 样例不变。
5. 诊断脚本覆盖监听、excluded range、foreign PID、当前项目容器占用、业务端口 8080–8086 排除和非法 compose 配置，输出稳定退出码。
6. 原 volumes、hostname、network、服务依赖和认证配置不变；不修改业务数据库数据。
7. HTTP/真实 TC 网络连接和 Compose 常驻容器状态验证通过后，才宣称 Docker 基础设施故障闭环。

## 5. README 与边界说明

README 必须记录 Nginx 新入口 `http://localhost:18088`、Seata host 端口 `18091`、容器内仍为 80/8091，以及 Docker Desktop 报错中“无 PID 监听但被 Windows excluded range 拒绝”的诊断方法。必须明确：当前证据排除了 Java/javaw 残留，不能把后台占用疑问当作 Java 故障；业务固定端口、系统 WinNAT/WSL 和历史数据不在自动修复范围。

## 6. 实施门与范围声明

实现限定在 Compose published mappings、Seata 本地连接配置、User/Stock/Pay/Order/common 测试配置契约、只读端口诊断脚本和 README。不得新增管理员操作、停止系统服务、修改业务 8080–8086、接入新的中间件或改变容器内部网络端口。
