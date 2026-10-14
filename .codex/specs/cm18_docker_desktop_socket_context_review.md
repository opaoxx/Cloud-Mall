# CM18 16T185：Docker Desktop socket 上下文诊断与有界恢复规格

## 1. 评审结论与问题边界

本批 **GO**，但当前状态仍为环境阻断。Docker engine pipe 不存在，Docker Desktop backend 日志显示 `sailor-ingest.sock` 删除失败（Win32 error 1920）；socket 为 0 byte Archive/ReparsePoint，目录 ACL 已确认当前用户拥有。外部 issue 只能作为排查线索，不能作为本机根因证据。

本批目标是确认是否存在“启动进程上下文不同导致 socket 虚拟化/访问语义不同”，并在不破坏本机数据和配置的前提下尝试恢复现有 Docker Desktop。禁止把上下文假设直接写成根因。

## 2. 只读上下文对照实验

在同一主机、同一用户和同一 socket 路径执行两组只读对照：

1. 当前 PowerShell 进程读取 `GetCurrentPackageFullName`、`fsutil reparsepoint query`、文件属性/ACL、socket 元数据和可读性。
2. 使用 `Win32_Process.Create` 启动 Hidden PowerShell，执行完全相同的只读命令并回收子进程。

两组结果必须逐项保存：包名/包上下文、进程完整路径、用户/Integrity 信息、socket 属性、reparse/Archive 状态、父目录 ACL 和 error code。实验只访问现有 socket 与父目录，不删除、重命名、修复 ACL、改变 reparse point 或写入目录。

仅当两组结果出现可重复的包内/包外差异时，才把“上下文相关”记录为支持性假设；即使如此，也不得声称已证明 Docker Desktop 根因。若两组一致，保留未定位状态。

## 3. 有界 Docker Desktop 恢复顺序

只有上下文对照和基线归档完成后，才允许进行恢复尝试：

1. 通过 Docker 官方 CLI 执行 graceful stop，单次总等待不超过 30 秒；禁止 force/kill、任务管理器终止、删除进程或强行关闭 WSL。
2. 使用 `Win32_Process.Create` Hidden 启动已安装的 Docker Desktop，保持原安装、用户、配置、socket 路径和数据卷不变。
3. 若 engine 仍不可达，立即保留阻断状态；不得升级/重置 Desktop、删除或重建 socket/父目录、改 ACL、重命名大目录、重置 WSL、`docker system prune`、`down -v` 或修改系统端口。
4. 不得使用临时 relay、改 host 端口或启动业务 Java/TC 客户端掩盖引擎故障。

恢复动作的 stdout/stderr、时间边界、退出码、进程树和 socket 状态必须归档；任何超时或不确定状态都停止后续动作。

## 4. 引擎恢复后的只读验证

引擎恢复后先保存原 Compose 渲染 blob、容器 ID、mount、ports、volumes 和网络摘要，再只启动已有 `cloud-mall` 容器；禁止 recreate。验证范围为：

- Docker engine pipe 与 `docker compose ps` 可用；
- MySQL/Redis/Nginx 协议和容器 health 按既有 16T181 规则验证；
- 两个 16T184 owned schema 确认不存在，原 Pay 176 表指纹与历史归档保持一致；
- 不启动业务 Java、TC/RM 客户端，不写业务库，不改变容器配置或持久化卷。

若引擎恢复但协议、容器 health、owned 清理或原指纹复核失败，报告部分恢复并停止，不宣称环境闭环。

## 5. 回退与证据要求

本批没有自动回退动作；graceful stop 后无法正常启动时保持 Docker Desktop 停止状态并报告。不得通过删除 socket、修改 ACL、系统/WSL 设置或数据卷操作“回退”。

必须保留：

- 恢复前后的 socket/ACL/reparse/进程上下文证据；
- Docker Desktop backend 日志与命令输出；
- Compose/container/mount/port/volume 摘要；
- MySQL/Redis 协议、health、原指纹及 owned schema 检查结果；
- 每次失败的退出码和清理状态。

Issue 链接、Docker 日志和上下文差异只能分别作为线索、运行时现象和本机实验结果记录，不能互相替代。

## 6. 明确不在范围

不修改业务代码、README、Compose、Docker 镜像、端口、账号、named volume、ACL、Windows/WSL 配置、安装包或系统网络；不执行 force/kill、升级、重置、删卷、重命名 socket/目录或启动业务服务。该规格不证明 Docker Desktop 根因、不承诺 TC/RM/Java 或资金链路健康，也不改变 16T184 业务验证历史证据。
