# CM-20261003-27：Stock Sentinel 动态 flow 规则评审

## 1. 评审结论

本规格只冻结 Stock 服务 `seckill-stock-reservation` 的 Sentinel **flow** 规则动态加载。当前实现由 `SeckillSentinelConfiguration` 在构造阶段向进程内 `FlowRuleManager` 写入硬编码 QPS=200，未订阅 Nacos；Gateway 与 Order 已具备 Nacos 规则订阅和本地兜底。Stock 需要补齐同一规则来源、严格校验、失败保留和部署发布链路。

本轮不实现 Sentinel `DegradeRule`，也不改变 order→stock、stock→Redis/MySQL 的异常或慢调用熔断降级语义。上述能力作为 CM27 后续阶段，不能在本轮验收中宣称完成。

## 2. 范围与不变量

- 资源名固定为 `seckill-stock-reservation`。
- 规则权威持久来源为 Nacos：Data ID `cloudmall-stock-flow-rules`，Group `CLOUDMALL_DEV`。每个 Stock 实例只订阅这一组配置。
- 本轮只允许 `FlowRule`，不引入参数热点规则、熔断规则、Dashboard 双写或跨实例全局配额。
- Sentinel 拒绝必须发生在请求认领、Redis Lua、库存流水、受理状态和 outbox 之前。鉴权、请求归属校验和必要的格式校验可以先执行，但不得产生库存事实。
- 保持现有 429 业务码 `GATEWAY_RATE_LIMITED` 和响应结构；规则变化不得改变 Stock API、幂等键、Redis Key、库存状态机或恢复链路。

## 3. 默认值与配置契约

应用配置新增可覆盖的本地默认值：

```yaml
cloudmall:
  stock:
    seckill:
      sentinel:
        flow-qps: 200
        nacos-retry-ms: 5000
```

`flow-qps` 必须为有限正数，默认 200 只代表单个 Stock 实例的本地保护上限；它不是集群总配额。`nacos-retry-ms` 必须为正数。Nacos 地址、用户名和密码沿用 Stock 已有 `spring.cloud.nacos.discovery.*` 配置及项目本地认证约定，不在规则 JSON 中携带凭据。

Nacos 内容必须是非空 JSON 数组，且本轮必须**恰好包含一条**目标资源规则：

```json
[{"resource":"seckill-stock-reservation","grade":1,"count":200,"limitApp":"default","strategy":0,"controlBehavior":0,"warmUpPeriodSec":0,"maxQueueingTimeMs":0,"clusterMode":false}]
```

实现必须使用严格 Jackson 解析，拒绝未知字段、空数组、重复资源、资源名不匹配、非有限或不大于零的 `count`、非法 `grade/strategy/controlBehavior`、负时间窗口、负排队时间及不受支持的集群模式。不得接受一个数组中夹带其它资源后再静默过滤。校验成功后以完整快照替换 Stock 自己的目标规则；不得部分加载。

## 4. 动态加载与失败语义

1. Bean 初始化先安装本地默认规则，再创建 Nacos `ConfigService` 并调用 `getConfigAndSignListener(dataId, group, timeout, listener)`。
2. 首次读取成功且通过严格校验时替换目标规则；Nacos 缺席、超时、空内容、解析失败或规则非法时保留本地默认规则或最近一次有效规则，并记录包含 Data ID、Group 和原因的告警。
3. Listener 收到变更时使用与首次读取相同的严格校验。非法刷新不得清空规则、降为零或替换为默认值；最近一次有效规则继续生效。
4. 首次订阅失败后由 `@Scheduled(fixedDelayString=...)` 周期重试；已订阅实例不重复创建客户端或 listener。连接恢复后读取当前完整快照并替换规则。
5. 服务停止时移除 listener 并关闭本服务创建的 `ConfigService`；关闭失败只能记录日志，不阻塞进程退出。

规则替换需串行化，避免 Nacos listener、启动重试和测试调用同时读取/写入同一快照。替换时必须保留 `FlowRuleManager` 中不属于 Stock 资源的既有规则，不能调用只含 Stock 规则的 `loadRules` 造成进程内其它规则丢失。Stock 多实例之间不共享 JVM 规则对象；每个实例从同一 Nacos Group 收到相同有效快照，阈值统计仍是实例级。需要集群流控时另立规格并做容量评审。

## 5. 部署与发布顺序

`deploy/nacos/sentinel/cloudmall-stock-flow-rules.json` 纳入仓库，内容使用上述严格契约。`deploy/nacos/publish-sentinel-rules.ps1` 的 Data ID 列表增加 `cloudmall-stock-flow-rules`，继续沿用默认跳过已有配置、`-Force` 才覆盖的发布语义。脚本必须在发布前读取并解析该 JSON，发布目标固定为 `CLOUDMALL_DEV`。

发布顺序：先启动 Nacos，再执行规则脚本，最后滚动启动/重启 Stock。Nacos 尚未可用时 Stock 仍以本地默认规则启动并持续重试；规则发布失败不能导致 Stock 清除现有规则。Dashboard 的临时内存调整不等于持久化，长期调整必须回写 Nacos 文件并用 `-Force` 明确发布。

## 6. 全局污染与多实例风险控制

- `FlowRuleManager` 是进程级全局管理器。更新时按资源过滤并合并快照，只替换 `seckill-stock-reservation`，保留其它资源；不能在构造器中无条件追加重复规则。
- 不使用静态跨实例缓存、文件锁或 Redis 锁模拟规则一致性；Nacos Group 是共享配置来源，实例本地规则是运行时副本。
- Nacos listener 不能把非法快照发布到 Sentinel，也不能在连接断开时调用 `loadRules(empty)`。日志和指标只能暴露 Data ID、规则版本/哈希、状态与原因，不记录密码、Token、请求体或用户标识。
- 测试必须在每个用例后恢复 Sentinel 全局规则，防止 Stock 测试污染同 JVM 的其它测试；生产代码不得为测试而清空全局规则。

## 7. 验收门槛

实现后至少通过以下检查：

1. 单元测试覆盖默认值、有效完整替换、空/非法/未知字段/重复资源拒绝、非法刷新保留最近有效规则、Nacos 首次不可达后重试成功、服务关闭移除 listener。
2. 全局管理器测试证明其它资源规则在 Stock 动态刷新前后保持不变，重复初始化不会产生重复目标规则。
3. 两个独立 Stock 实例连接同一 Nacos：有效变更最终在两实例生效；断开 Nacos 时两实例各自保持最近有效规则；不把实例 QPS 误报为全局配额。
4. 真实联调使用 `deploy/nacos/publish-sentinel-rules.ps1` 发布默认 JSON，启动 Stock，修改规则并核对新阈值生效；随后发布非法 JSON，确认旧阈值仍生效；恢复合法规则后再次核对。
5. 受限流请求的 MySQL `seckill_request_claim`、库存流水、Redis Lua 扣减、受理记录和 outbox 均无新增副作用；放行请求仍通过现有幂等、Redis、MySQL、RabbitMQ 链路。
6. 执行 Stock 模块测试及全仓适用 Maven 构建；若 Nacos 或 Sentinel Dashboard 不可用，必须记录使用本地默认规则的证据，不能以“服务能启动”代替动态刷新验收。

## 8. 未纳入本轮的后续工作

order→stock、stock→Redis、stock→MySQL 的 Sentinel 熔断/慢调用降级，降级 503 契约、故障注入、跨实例全局限流和 Dashboard→Nacos 双写均保留为 CM27 后续阶段。实现本规格的开发角色不得顺带修改这些链路。
