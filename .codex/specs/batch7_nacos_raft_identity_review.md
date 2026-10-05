# CM-20261005-30 Nacos standalone Raft 节点身份漂移修复规格

## 1. 问题与证据

Docker Compose 中的 Nacos 服务设置了 `PREFER_HOST_MODE=hostname`，但没有固定
`hostname`。容器重建后 Docker 为容器分配新的随机 hostname，而 Nacos 2.2.0
standalone 的持久 Raft 日志仍保存旧节点身份。现场三个持久 Raft 组
`naming_persistent_service_v2`、`naming_service_metadata`、
`naming_instance_metadata` 的日志包含：

```text
conf=57c05639b175:7848
currentNode=c459c5e2731c:7848
can't do preVote as it is not in conf
```

当前容器 hostname 为 `c459c5e2731c`，而持久配置仍指向旧 peer
`57c05639b175:7848`。因此容器 HTTP 健康检查可能成功，但 naming Raft 没有
leader，注册、发现、持久实例删除和依赖 Nacos 的业务启动均不可靠。Nacos 的
持久数据和日志分别位于 named volume `cloud_mall_nacos_data` 与
`cloud_mall_nacos_logs`，不能以删除 volume 或清空 Raft 目录作为修复。

## 2. 评审结论

本轮采用 **恢复持久日志原有 peer 身份并固定以后重建使用同一 hostname** 的最小
恢复方案。Nacos 2.2.0 的公开 HTTP 运维接口用于状态检查和数据核验，没有可作为
本项目发布契约的“在线修改 standalone Raft 本地 peer 身份”接口；未发现可安全替换
三个持久 Raft 组成员配置的官方 API。因此不得伪造 ops API、直接编辑 Raft 日志、
删除 `data/protocol/raft`，也不得把单节点 standalone 临时改成 cluster 模式。

恢复值必须来自启动前备份中实际记录的 peer hostname 和端口，而不能由当前容器
ID 猜测。当前现场若备份核验仍为 `57c05639b175`，Compose 的 Nacos 服务应在
保留 named volume 的前提下显式设置：

```yaml
hostname: 57c05639b175
```

仓库使用 `hostname: ${NACOS_NODE_HOSTNAME:-cloud-mall-nacos}`；当前已有数据卷通过本机
`.env` 显式设置 `NACOS_NODE_HOSTNAME=57c05639b175`。全新数据卷缺省使用固定的
`cloud-mall-nacos`，以后也不得随容器 ID 改变。健康检查同时核对三个组的 leader
与实际 hostname 相同，避免只检查 HTTP UP。

`container_name: cloud-mall-nacos` 继续作为容器管理名；它与 Raft peer hostname
是两个不同概念。后续 `docker compose up -d --force-recreate nacos` 必须复用该
固定 hostname，避免 Docker 再生成随机身份。hostname 的值应放入本地部署变量或
经评审的环境配置，不能写成所有环境通用的硬编码 peer；新环境首次初始化时应先
生成稳定的、可复用的 hostname，再创建 Nacos 数据。

## 3. 执行前门槛与备份

执行人必须先停止会持续写入 Nacos 的业务服务和 `nacos-bootstrap`，确认没有待处理
配置发布、服务注册刷新或用户操作；不删除任何 volume。按以下顺序创建可恢复备份：

1. 记录 `docker inspect cloud-mall-nacos` 的容器 hostname、镜像 digest、端口、
   volume 名称和挂载点；记录当前 `docker compose config` 的完整渲染结果。
2. 停止 Nacos 后，以只读方式导出并校验 `cloud_mall_nacos_data`、
   `cloud_mall_nacos_logs` 到带时间戳的备份目录；备份必须包含数据文件、Raft 日志、
   用户/权限配置和持久服务数据。为每个归档计算 SHA-256。
3. 保存 Nacos HTTP 数据核对结果：root/root 登录成功、配置条目数量和摘要、持久
   服务/实例列表、命名空间、角色/权限，以及现存业务注册服务名称。导出物中不得
   在日志中打印 access token 或密码。
4. 从备份中的 Raft 日志/配置确认三个组共同使用的旧 peer hostname、端口和本地
   节点身份；若三组身份不一致、备份不可读、数据卷挂载不明或无法确认 peer，停止
   发布并转人工恢复评审。

## 4. 恢复步骤

1. 在 Compose Nacos 服务增加显式 `hostname`，值为前置核验的原 peer hostname；
   保持镜像版本 `nacos/nacos-server:v2.2.0`、standalone 模式、认证参数、8848/
   9848/​9849 映射和两个 named volume 不变。
2. 停止旧容器并仅重建 Nacos 容器，禁止 `down -v`。启动后先查看 Raft 日志，确认
   `currentNode=<原peer>:7848`，三个持久组均完成 preVote/leader 选举且不再出现
   `not in conf`、`no leader`、反复重置日志等错误。
3. 等待 HTTP health、Nacos console 登录和 naming leader 均通过，再启动业务服务；
   最后运行 bootstrap 的幂等用户/权限初始化，不在恢复过程中重置业务数据。
4. 验证持久数据：root/root 登录、读取一个已存在配置、发布并读取一个带唯一
   临时 dataId/group 的配置后删除；读取并删除一个临时持久服务实例；确认原有业务
   服务注册、注销和重新注册正常。临时对象必须在验证后删除。
5. 执行一次 Docker `--force-recreate` 后重复上述身份和 leader 检查，证明重建不再
   产生随机 hostname。仅 HTTP 200 不能作为通过条件。

## 5. 健康门槛

只有全部条件满足才允许恢复业务流量：

- 容器 hostname 与备份确认的 Raft peer 完全一致，端口为 7848；
- 三个持久 Raft 组各自存在稳定 leader，日志连续 5 分钟无 `not in conf`、`no
  leader`、`preVote` 失败循环或反复重启；
- Nacos HTTP health、root/root 登录、配置读写删、持久实例注册/查询/删除均成功；
- 原有 namespace、配置摘要、持久服务数量和权限与恢复前一致；
- 业务服务能重新注册并通过至少一次发现调用；
- `docker compose up -d --force-recreate nacos` 后以上身份、leader 和数据检查仍
  通过；
- Docker Desktop 启动全 Compose 时不再因 Nacos 9848 端口或健康依赖失败，Kibana、
  Grafana、Nginx 等服务可独立达到各自健康条件。

## 6. 回滚

若任一门槛失败，立即停止业务服务和 Nacos，保留失败容器日志与检查结果；不继续
尝试改写 Raft 文件。将 Compose hostname 恢复为原配置，使用执行前保存的完整
`cloud_mall_nacos_data` 与 `cloud_mall_nacos_logs` 备份恢复到新的临时 named volume，
先在隔离端口启动并验证备份完整性，再按原容器参数恢复正式 volume。回滚禁止删除原
卷，正式卷仅在备份校验和人工确认后替换。若原 peer 不能启动，进入 Nacos 官方
集群/数据恢复方案评审；本规格不授权通过删除 Raft 状态来“初始化修复”。

## 7. 验证与提交门槛

实现阶段只能修改 Compose/部署配置、必要的运维校验脚本和 README 操作说明，不得
修改商品、订单、库存或 Nacos 业务客户端契约。提交前必须执行：

- `docker compose config`，确认 hostname、volume、端口和认证参数未漂移；
- Nacos 日志与三个 Raft 组 leader 检查；
- root/root 的配置与 naming 持久化读写删回归；
- 一次停启和一次 `--force-recreate` 回归；
- 全 Compose 健康检查及业务服务注册/发现回归；
- `git diff --check`，并按阶段提交部署变更。

任何数据导出、备份路径、临时账号 token 或测试对象不得提交到仓库。发现官方
Nacos 版本的运维 API 支持正式 peer 迁移时，应先补充 API 版本、请求参数、响应和
回滚证据，再另开规格评审；在此之前禁止在线迁移或手工改 Raft 配置。
