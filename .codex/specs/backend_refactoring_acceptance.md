# 后端重构验收汇总与剩余工作

核验日期：2026-11-14。源码起点：`5798b822`；本轮方案基线：`53d41be1`。责任人：当前会话主 Agent，单 Agent 执行。

本报告对应[重构方案第60节](backend_refactoring_plan.md)，不覆盖原停工边界，也不改写历史实验结论。

## 1. 当前判断

**八模块的中等深度结构目标已经达到；整体规范验收尚有四个模块的小范围收尾，不能宣称全部完成。**

- 当前源码新执行267类、1948项测试，0失败/错误/跳过；八模块package通过，628个生产class与编译输出及现存SourceFile逐一核验。
- 641份生产Java（含package-info）中，33个Controller采用独立职责，41个Service接口均非空。当前扫描未发现Cm编号生产类型、Controller继承ServiceImpl、Service HTTP映射、Controller/Service直接JDBC、指定SQL字面量候选或DTO/VO/PO后缀嵌套模型。
- 结构扫描不等于逐字段语义证明；HTTP/事务/消息兼容仍依据已有行为测试与逐切片记录。源码/配置/SQL自第49节以来没有生产变更，之后13个变动文件全部位于src/test。
- 当前发现60处必需入口/成员JavaDoc绑定问题、34个格式不一致文件、7个非id短变量/形参、23条明确模板化说明；去重涉及47个文件。这些数量单位不同且相互重叠，不能相加为缺陷总数。
- 本轮只新增/更新Markdown和ignored验收证据，没有修复或格式化Java，也没有运行真实中间件测试。

## 2. 模块职责与当前统一测试

下表“完成”仅指本轮约定的命名、分层和职责整理。生产Java数包含包说明，不能等同Jar class数。

| 模块 | 已形成的职责边界 | Java / Controller / Service接口 | 当前测试类/项 | 规范结论 | 结构检查点 |
| --- | --- | --- | --- | --- | --- |
| Common | 响应、异常、身份、消息指纹等跨模块基础能力；不承载服务业务 | 17 / 0 / 0 | 4 / 25 | 待规范收尾 | 无独立业务拆分完成标签；按公共契约联测 |
| Gateway | filter鉴权/请求链，util路径策略，config规则解析与发布 | 7 / 0 / 0 | 5 / 21 | 通过本轮扫描 | `refactor/gateway-complete-20261104` / `faaecef2` |
| Cart | Controller → CartService/Impl → CartStorage；结算VO独立 | 24 / 1 / 1 | 13 / 35 | 通过本轮扫描 | `refactor/cart-complete-20261104` / `9eab801a` |
| Product | 查询/管理/分类/活动服务；index、metrics、search分组 | 102 / 1 / 5 | 37 / 361 | 待规范收尾 | `refactor/product-complete-20261104` / `e345bb4d` |
| User | 账号/资料/地址、扣款/退款、TCC/Fence及只读取证分离 | 100 / 7 / 8 | 18 / 284 | 待规范收尾 | `refactor/user-complete-20261104` / `418cd0d0` |
| Stock | 查询/预留/扣减/事实、cache、seckill、metrics及Seata演练分离 | 87 / 3 / 6 | 48 / 209 | 待规范收尾 | `refactor/stock-complete-20261104` / `ef53b37d` |
| Order | 创建/查询/状态、秒杀接收/落单、超时/出站消息、恢复与分片分离 | 117 / 6 / 10 | 65 / 341 | 通过本轮扫描 | `refactor/order-complete-20261113` / `458efb7c` |
| Pay | 查询/创建/成功/失败/回调五用例，Legacy和TCC各职责分组 | 187 / 15 / 11 | 77 / 672 | 通过本轮扫描 | `refactor/pay-complete-20261114` / `3f5d069c` |

共267类/1948项；其中265个*Test类，另含Order的StockSeckillFactFeignIT和Pay的PayOrderStateFeignIT。两个IT通过本机HTTP使用真实Feign/响应类型，但持久化边界受控，不能称真实商城链路。Product原生命周期单测产生一个自有Java子进程，已验证naturalExit/执行器关闭/AMQPConnections=0；这不涉及多Agent。

调用链索引：Cart见方案§13、Gateway§14、Product§15、User§16–18、Stock§19–22、Order§23–33、Pay§34–48。动态分片与元数据/恢复SQL仍封装在Mapper；这是保留既有实现，不是全面ORM替换。Feign内局部record以及表、Bean、消息/指标中的cm18/cm21兼容标识继续保留。

## 3. 实库与真实链路证据的分层结论

下表是此前实际运行、本轮校验成功XML SHA/标签/提交可达性后的结果，**没有在本轮重跑，也不与1948项累加**。源代码迁移阶段曾做的其它局部实验仍在原章节，不把此表称为所有历史实验索引。

| 阶段 | 已验证范围 | 原测试项数 | 不能推导的结论 |
| --- | --- | ---: | --- |
| §46 取消/中止分组 | Cancel lease 7、Committed cancel 17、精确Legacy退款/决议10项真实MySQL | 34（原713中实库子集） | User/TC为受控边界，不证明完整公开取消资金RPC |
| §47 TCC公开编排 | Failure 4、Success 3项真实本地事务/模式/代理 | 7（原688中实库子集） | 远端执行/投影有替身，不证明真实资金已回滚 |
| §50 跨模块数据 | Cart42、User4、Product5、Stock4，独立MySQL/Redis | 55 | 不等于完整前后端交易 |
| §51 Order/Pay事务与消息 | Sharding创建、订单状态、TTL/DLX/秒杀消息、Pay本地事务 | 29 | 远端资金/TC仍有替身 |
| §52 商品内容索引 | MySQL outbox → Rabbit → ES，旧版本/重试/DLQ | 4 | 不替代在线指标V2/重建/性能矩阵 |
| §53 User LocalTCC | 官方Fence与真实TC提交/回滚/重试 | 6 | 不等于多服务公开支付 |
| §54 Pay/User桥 | 一次POST、GET核验、并发/重启/Nacos重选 | 9 | 不等于公开支付完成 |
| §55 双资源receipt | Pay/User双分支与真实TC二阶段重试 | 11 | 不等于交付/退款已完成 |
| §56 coordinator | 持久决议、未知结果、指定进程中断与恢复 | 15 | BEGIN_UNKNOWN/UNKNOWN保护不是恢复成功 |
| §57 自动恢复 | 真Scheduled、同向原XID恢复、重启自动发现 | 5 | 提前到期不证明全部墙钟退避/崩溃窗口 |
| §59 四服务交付 | User/Pay/Order/Stock实际链路、退款、两类Legacy新进程、MQ重投 | 12 | Product/Cart是原只读metadata替身；未走Gateway/前端 |

§59成功轮包含14笔支付（12 TCC、2 LEGACY）、13次扣款和6次退款，12 TC全局/24分支；1条ROLLEDBACK负例公开支付仍PENDING，这是原验收边界。31份Stage保持EVIDENCE_INCOMPLETE；MQ专项验证不声称拥有broker ACK历史。以上负向/缺证状态不能在汇总中改成成功。

## 4. 必须完成的规范收尾

原模块“结构完成”不覆盖本次新发现的规范缺口。只读审计采用同一JDK DocTrees/语法树与google-java-format 1.22.0，未改任何文件。所有入口/成员检查排除record隐式生成方法，匿名类的显式public方法仍纳入。

| 模块 | JavaDoc绑定问题 | dry-run格式文件 | 非id短名 | 模板化说明 | 去重文件 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Common | 0 | 4 | 1 | 0 | 5 |
| Product | 34 | 16 | 6 | 23 | 21 |
| User | 17 | 2 | 0 | 0 | 5 |
| Stock | 9 | 12 | 0 | 0 | 16 |
| Cart/Gateway/Order/Pay | 0 | 0 | 0 | 0 | 0 |

60处中，21处已有说明但放在注解之后，JDK没有绑定到成员：User两个PO的16字段、Product upsert/delete/consume三个方法、Stock initialize/scheduledAudit两个入口。其余39处需要补全独立入口/成员说明，其中部分匿名回调或成组字段已有上层说明，不能说60处都完全没有注释。23条模板化说明来自Product四个文件，与部分JavaDoc问题重叠。扫描不能代替对每段注释内容的逐行语义复核。

103个两字符以内声明中，96个id为已有主键/查询/传输语义；它们不因长度被自动改名，尤其不得改PO/JSON/SQL字段。另7处ex/e/rs需要语义命名，见附表。没有发现Cm编号生产类型；冻结测试、工具、表或消息中的编号不是本次类名重构遗漏。

后续顺序与通过门：

1. **Common（下一步）**：四个格式文件与AuthInterceptor的ex，共五文件。仅排版/import及异常形参语义命名；保持异常处理顺序、JSON和认证上下文。执行Common25项及受影响契约，记录逐token/符号归一差异和独立回退点。
2. **Product**：二十一文件，补Bean/Mapper/回调说明、修正注解顺序、替换模板化描述、六处局部短名并统一格式。SQL/Lua/消息键、重试、确认、Bean名和算法不变；现有单测/契约加源码语义核验，不为注释排版重开历史CM21实验。
3. **User**：五文件，调整两个PO注释位置、补BalanceTccDebitVO.from说明和两个文件格式。映射注解、字段、null/金额保持；执行相关表示/资金契约。
4. **Stock**：十六文件，补监听/批处理入口与成组成员的独立说明、修正两个任务注释位置并统一格式。SQL、Redis Lua、指标/规则名、调度/租约与边界不变；执行相关模块回归。

四批完成后再做一次规范总复核；只有实际零待办且适用测试通过才把“规范收尾”置完成。每批都先冻结文件/差异范围，不能把本清单当作一次性全库格式化授权。

### 4.1 文件级清单

以下为当前源码快照；“说明”是AST声明数，“模板”是匹配行数，格式列只列dry-run命中。名称对应完整可点击源码路径，后续改动后需更新行号/状态。

#### cloud-mall-common

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [auth/AuthContext.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/auth/AuthContext.java) | 0 | 待处理 | 0 | 0 |
| [error/ErrorCodes.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/error/ErrorCodes.java) | 0 | 待处理 | 0 | 0 |
| [messaging/SeckillRequestFingerprint.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/messaging/SeckillRequestFingerprint.java) | 0 | 待处理 | 0 | 0 |
| [web/AuthInterceptor.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/web/AuthInterceptor.java) | 0 | — | 1 | 0 |
| [web/GlobalExceptionHandler.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/web/GlobalExceptionHandler.java) | 0 | 待处理 | 0 | 0 |

#### cloud-mall-product

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [ProductApplication.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/ProductApplication.java) | 0 | 待处理 | 0 | 0 |
| [config/ElasticsearchConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ElasticsearchConfiguration.java) | 1 | — | 0 | 0 |
| [config/ProductIndexFeatureConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductIndexFeatureConfiguration.java) | 0 | 待处理 | 0 | 0 |
| [config/ProductMessagingConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductMessagingConfiguration.java) | 12 | 待处理 | 0 | 6 |
| [config/ProductReadExceptionHandler.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductReadExceptionHandler.java) | 0 | 待处理 | 0 | 0 |
| [domain/po/ProductIndexDocument.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/po/ProductIndexDocument.java) | 0 | 待处理 | 0 | 0 |
| [domain/po/ProductSearchHit.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/po/ProductSearchHit.java) | 0 | 待处理 | 0 | 0 |
| [domain/vo/HotStatVO.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/vo/HotStatVO.java) | 0 | 待处理 | 0 | 0 |
| [mapper/ProductHotStatMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductHotStatMapper.java) | 0 | 待处理 | 0 | 0 |
| [mapper/ProductIndexConsumptionMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexConsumptionMapper.java) | 4 | 待处理 | 0 | 0 |
| [mapper/ProductIndexDeadLetterMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexDeadLetterMapper.java) | 2 | 待处理 | 0 | 0 |
| [mapper/ProductIndexMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexMapper.java) | 0 | 待处理 | 0 | 0 |
| [mapper/ProductIndexOutboxMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexOutboxMapper.java) | 3 | 待处理 | 1 | 0 |
| [mapper/ProductIndexRebuildMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexRebuildMapper.java) | 0 | 待处理 | 0 | 0 |
| [mapper/ProductSqlMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductSqlMapper.java) | 4 | 待处理 | 0 | 0 |
| [service/ProductIndexWriter.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/ProductIndexWriter.java) | 0 | 待处理 | 0 | 0 |
| [service/impl/index/ElasticsearchProductIndexWriter.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ElasticsearchProductIndexWriter.java) | 2 | 待处理 | 1 | 4 |
| [service/impl/index/ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 1 | — | 4 | 12 |
| [service/impl/index/ProductIndexDocumentLoader.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexDocumentLoader.java) | 0 | — | 0 | 1 |
| [service/impl/index/ProductIndexOutboxDispatcher.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexOutboxDispatcher.java) | 2 | — | 0 | 0 |
| [service/impl/metrics/ProductMetricConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/metrics/ProductMetricConsumer.java) | 3 | — | 0 | 0 |

#### cloud-mall-user

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [domain/dto/AddressRequestDTO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/dto/AddressRequestDTO.java) | 0 | 待处理 | 0 | 0 |
| [domain/po/BalanceDebitAttemptPO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/po/BalanceDebitAttemptPO.java) | 7 | — | 0 | 0 |
| [domain/po/BalanceTccDebitPO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/po/BalanceTccDebitPO.java) | 9 | — | 0 | 0 |
| [domain/vo/BalanceOperationVO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/vo/BalanceOperationVO.java) | 0 | 待处理 | 0 | 0 |
| [domain/vo/BalanceTccDebitVO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/vo/BalanceTccDebitVO.java) | 1 | — | 0 | 0 |

#### cloud-mall-stock

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [config/SeckillSentinelConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/SeckillSentinelConfiguration.java) | 1 | 待处理 | 0 | 0 |
| [config/StockDependencyAspect.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockDependencyAspect.java) | 0 | 待处理 | 0 | 0 |
| [config/StockDependencySentinelConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockDependencySentinelConfiguration.java) | 1 | — | 0 | 0 |
| [config/StockMessagingConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockMessagingConfiguration.java) | 1 | — | 0 | 0 |
| [domain/po/SeckillOrderOutboxPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/SeckillOrderOutboxPO.java) | 0 | 待处理 | 0 | 0 |
| [domain/po/StockCacheRepairPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/StockCacheRepairPO.java) | 0 | 待处理 | 0 | 0 |
| [domain/po/StockReservationRequestPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/StockReservationRequestPO.java) | 0 | 待处理 | 0 | 0 |
| [domain/vo/StockReservationItemVO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/vo/StockReservationItemVO.java) | 0 | 待处理 | 0 | 0 |
| [domain/vo/StockReservationVO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/vo/StockReservationVO.java) | 0 | 待处理 | 0 | 0 |
| [mapper/StockConsistencyAuditStateMapper.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/mapper/StockConsistencyAuditStateMapper.java) | 0 | 待处理 | 0 | 0 |
| [mapper/StockSqlMapper.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/mapper/StockSqlMapper.java) | 2 | 待处理 | 0 | 0 |
| [service/impl/StockQueryServiceImpl.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/StockQueryServiceImpl.java) | 0 | 待处理 | 0 | 0 |
| [service/impl/StockReservationRequestService.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/StockReservationRequestService.java) | 0 | 待处理 | 0 | 0 |
| [service/impl/cache/StockCacheReconciler.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/cache/StockCacheReconciler.java) | 2 | — | 0 | 0 |
| [service/impl/cache/StockConsistencyReconciler.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/cache/StockConsistencyReconciler.java) | 2 | — | 0 | 0 |
| [service/impl/seckill/SeckillQuotaSettlementService.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/seckill/SeckillQuotaSettlementService.java) | 0 | 待处理 | 0 | 0 |

### 4.2 七个短名定位

| 文件 | 当前行 | 声明 |
| --- | ---: | --- |
| [AuthInterceptor.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/web/AuthInterceptor.java) | 28 | `METHOD:Exception:ex` |
| [ProductIndexOutboxMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexOutboxMapper.java) | 34 | `LAMBDA_EXPRESSION:null:rs` |
| [ElasticsearchProductIndexWriter.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ElasticsearchProductIndexWriter.java) | 432 | `CATCH:ElasticsearchStatusException:e` |
| [ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 220 | `CATCH:Exception:e` |
| [ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 244 | `CATCH:Exception:e` |
| [ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 254 | `CATCH:Exception:e` |
| [ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 268 | `CATCH:Exception:e` |

## 5. 额外真实场景与原有风险

这些事项必须保持可见，但不自动扩展为本轮结构重构的执行队列。

| 事项 | 当前证据/限制 | 启动条件 |
| --- | --- | --- |
| 公开TCC主动取消、未决中止的真实链路 | 本轮单测包含取消/入口/投影；§46有34项局部MySQL，§47有7项入口本地事务。四服务delivery不覆盖完整取消矩阵 | 若要声明公开取消全链通过，先评审有限场景和新隔离环境，再运行 |
| 旧PayTccCancelIT复用 | 仍有固定Nacos/TC地址、自建Redis、旧TC证据默认入口和RmTcLinkProxy源码/class固定SHA准入 | 不原样启动；先核当前构建pin和隔离/资源生命周期，不删除或绕过pin检查 |
| Gateway与真实Product/Cart的全商城请求 | 本轮Gateway/Feign为实际入口+受控依赖，§59的Product/Cart为只读替身 | 作为独立全商城验收制定数据、登录、路由及清理范围 |
| Gateway版本差异 | 本轮Surefire classpath读回spring-cloud-gateway-server **3.1.4**，规格写3.1.5；属既有差异 | 单独核对BOM/规格决策，本轮不升级依赖 |
| Sharding解析器版本提示 | 统一测试出现既有ANTLR4.7.2/4.8提示，测试通过 | 记录兼容观察，不把提示误称当前失败或擅自换栈 |
| 全故障/性能/CM21遗留 | 有限回归不覆盖每个网络/崩溃窗口；历史NO-GO/UNKNOWN不因当前通过消失 | 明确的新任务与范围；不自动重启历史维护或性能runner |

## 6. 失败现场与资源保留

本轮只读核对本表覆盖的12个实验项目：容器/网络均无残留，43个记录中的命名卷仍存在。这个数量仅是本报告选取的§46/47/50–59实验集合，**不是整机或整个重构所有历史卷的总量**；没有创建、启动、挂载或删除任何卷。

- §54 Nacos授权首轮失败的两owned库仍保留于该实验MySQL卷；不因后续桥接测试通过删除。
- §59首轮四库仍保留：TCC已提交、余额90/冻结0，Pay PENDING、Order PENDING_PAYMENT、库存9/1/0，尚未交付；第二轮12项通过没有清算或改写此样本。首轮/第二轮共12卷保留。
- §59第二轮两个Redis字符串Key精确归属两笔LEGACY扣款缓存，沿原30天TTL保留；没有用FLUSHDB清场。
- 当时另有三个CloudNest容器由其他操作启动，调用者未知；原记录保留。本轮不要求其他项目状态静止，也不操作它们。

证据中的UNKNOWN、BEGIN_UNKNOWN、PENDING及EVIDENCE_INCOMPLETE须结合具体用例解释，不能把TC结束、容器关闭或新轮通过当作旧业务结清。

## 7. 回退与证据定位

阶段完成标签只定位那个提交，不等于一条revert撤销整个模块。Order完整切片逆序索引见方案§33.4，Pay见§48.4，其余见对应模块章节。已存在后续依赖时先核改动、保存无关工作树、按逆序处理并重编译；不得用reset --hard、全量clean或删除历史证据演示回退。

本轮方案基线为`refactor/backend-acceptance-baseline-20261114`，本轮汇总完成标签为`refactor/backend-acceptance-complete-20261114`。它只覆盖两份Markdown，回退不触及Java或任何运行数据：

```powershell
git revert refactor/backend-acceptance-complete-20261114
```

本轮证据位于`target/backend-refactor-20261114/refactoring-acceptance/`：

- `verification.json`：提交、测试、规范待办与逆补丁入口。
- `final-summary.json`、`final-reports/`：本轮267类1948项及SHA，未混入旧Surefire XML。
- `historical-evidence-check.json`：12组历史报告SHA/标签/提交树核对；只复核，不冒充新执行。
- `structural-inventory.json`、`reviewed-followup-inventory.json`、各模块TSV/format输出：641文件结构和46文件规范清单。
- `jar-check.json`：八Jar共628生产class，编译字节/SourceFile对应。
- `retained-experiment-resources.json`：上述12项目43卷只读检查。

ignored证据保留在当前工作区；Git只版本化本报告和主方案。后续会话先读本报告、第60节及实际Git状态，再从Common五文件规范收尾继续。
