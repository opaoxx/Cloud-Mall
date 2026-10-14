# CM18 16T181：Docker 宿主协议连通性恢复与只读诊断规格

## 1. 评审结论与目标

本批 **GO**，范围限定为恢复和验证宿主到 Docker 基础设施的协议连通性，并补齐只读诊断工具。当前证据显示 Windows `localhost`、`127.0.0.1`、`::1` 的 MySQL `13307` 与 Redis `16379` 连接立即 EOF；容器内 MySQL TCP、Redis `PONG` 及容器互联正常，Nginx `18088` 三地址 HTTP 200。监听归属为 Docker Desktop backend/WSL relay，不能据此认定残留业务进程或协议健康。

本批不得通过改 host 端口、改镜像/账号/卷/网络、修改系统或 WSL 设置来掩盖问题，也不把临时转发通道纳入常规部署。

## 2. 恢复前基线与保护条件

恢复前必须生成 canonical 记录，包含：Compose 文件与渲染配置摘要、容器 ID/镜像/网络/hostname、命名卷列表与配置、原业务 MySQL 全表名及逐表 checksum、只读 `mysqldump` 归档、Redis key 数量与 persistence 状态及只读备份。原 MySQL/Redis 业务数据只读，任何校验失败或备份缺失即停止恢复。

恢复动作按最小范围顺序执行：

1. 先仅对受影响的 MySQL/Redis 服务执行受控 restart，并验证协议、JDBC 只读查询、数据指纹和 Redis AUTH/PING。
2. 仍然 EOF 且数据卷与基线已核对时，才按原 Compose 配置对对应服务执行 force-recreate。
3. recreate 必须保持 ports、镜像、账号、named volume、network、hostname 和业务端口不变；禁止 `down -v`、删除卷、修改系统/WSL、杀用户进程或启动业务服务。
4. 恢复后再次核对 MySQL 全表 checksum、只读 dump 对照、Redis key/persistence 状态，并记录容器与端口归属。若仍失败，保留未闭环状态，不转用 host 端口或临时 relay 冒充恢复。

恢复验证只承诺基础设施协议可达和数据未变，不承诺业务服务健康、Java 测试通过、TC/RM 正常或跨服务资金链闭环。

## 3. `check-local-ports.ps1` 的只读扩展

保留现有默认行为：Compose TCP 绑定、系统 excluded port range、Docker/WSL 自有监听和可选业务端口诊断，不修改系统设置、不杀进程。

新增显式 opt-in 参数 `-ProbeProtocols`，仅在调用者明确指定时运行协议探测：

- MySQL：对 `localhost` 与 `127.0.0.1` 的每个映射端口执行只读 packet 探测，确认收到 MySQL greeting（协议版本 10）；不执行登录、SQL、DDL 或数据写入。
- Redis：对 `localhost` 与 `127.0.0.1` 发送无凭据 `PING`。收到 `PONG` 或 `NOAUTH` 均表示 TCP/Redis 协议可达；这不代表认证成功、实例健康或业务 key 正常。
- 每个探测连接超时 2 秒。连接 EOF、错误 banner、协议不匹配、超时或地址不可达均使该探测失败并返回非零退出码；报告必须区分 `OWN` 端口诊断与 protocol probe 结果。
- 不向 Redis 发送 AUTH，不输出密码，不修改 MySQL/Redis 状态；默认不启用协议探测，因此既有端口诊断兼容保持。

新增 PowerShell/Python helper 或标准库测试只能服务于上述探测协议，禁止引入额外运行时依赖、固定监听端口或把临时 relay 写入部署脚本。README 必须明确：`OWN` 只证明端口归属，不能证明 host 协议可达；协议可达也不能替代业务健康检查。

## 4. 验收与测试边界

- 保留 connection EOF、容器内协议成功、Docker/WSL relay 归属和端口未修复的原始日志；若恢复动作未执行或失败，不得计入业务测试失败，也不得宣称环境闭环。
- 先运行标准库协议测试：MySQL greeting、EOF、错误 banner、timeout；Redis `PONG`、`NOAUTH`、EOF、timeout，覆盖 IPv4/IPv6 结果分类。测试不得启动业务服务或写原数据库。
- 恢复成功后，验证 MySQL 只读 JDBC 连接、原业务表 checksum/dump 一致，Redis AUTH/PING 与 key/persistence 对照一致；执行既有基础设施健康检查，记录 Java 测试为 0（若本批未运行 Java，不得虚报）。
- Nginx `18088` 已能 HTTP 200 的证据单独记录，不用它推断 MySQL/Redis 协议健康。容器内 TCP/PONG 正常也不替代宿主协议探测。
- 所有 restart/recreate、备份、探测、relay/socket、CLI 进程和临时文件都需记录开始/结束时间及清理结果；失败路径必须在 `finally` 中关闭连接、监听器和子进程。

## 5. 明确不在本批范围

不修改业务 Java、数据库 schema、Redis key、Compose 端口契约、镜像/账号/卷/网络、WSL/Windows TCP 设置，不重启或重建无关服务，不删除卷，不修复业务服务端口，不把临时传输通道纳入部署。协议恢复不等于业务健康、认证成功、跨服务事务、TC/RM、资金事实或 TCC fencing 完成。
