# 后端重构验收汇总与剩余工作

核验日期：2026-11-14。第60节统一验收源码起点：`5798b822`；最新进度为第64节Stock规范收尾，方案基线`3275970d`。责任人：当前会话主 Agent，单 Agent 执行。

本报告以[重构方案第60节](backend_refactoring_plan.md)为基线，按后续阶段更新当前状态；不覆盖原停工边界，也不改写历史实验结论。

## 1. 当前判断

**八模块的中等深度结构目标已经达到；Common、Product、User、Stock四批规范清单已全部处理，统一规范总复核仍待执行。**

- 第60节统一执行267类1948项，第61节12类85项，第62节41类386项，第63节22类309项，第64节Stock/Common共52类234项，各次分别0失败/错误/跳过，不相加计数。第64节八模块package通过，628个生产class与编译输出及现存SourceFile逐一核验。
- 第60节641份生产Java（含package-info）结构扫描中，33个Controller采用独立职责，41个Service接口均非空；未发现Cm编号生产类型、Controller继承ServiceImpl、Service HTTP映射、Controller/Service直接JDBC、指定SQL字面量候选或DTO/VO/PO后缀嵌套模型。四批规范整理不改变结构，后续仍需在统一源码状态总复核。
- 结构扫描不等于逐字段语义证明；HTTP/事务/消息兼容仍依据已有行为测试与逐切片记录。第49至60节间13个变动文件全部位于src/test；第61–64节分别整理Common5、Product21、User5、Stock16文件，均经完整AST及常量对照。Product启动类、User地址DTO仅工作区行尾统一；47个清单文件中45个形成Git内容差异。配置资源/SQL未改。
- 原清单的说明绑定、格式、非id短名和模板化说明已分批处理，剩余文件0；本轮Stock87文件扫描通过。这是原清单处理结果，不等于本轮重新执行了全库规范扫描。
- 第60节为只读验收；第61–64节完成四模块规范整理，没有修改测试源码或运行新的真实中间件测试。

## 2. 模块职责与第60节统一测试基线

下表“完成”仅指本轮约定的命名、分层和职责整理。生产Java数包含包说明，不能等同Jar class数。

| 模块 | 已形成的职责边界 | Java / Controller / Service接口 | 第60节测试类/项 | 规范结论 | 结构检查点 |
| --- | --- | --- | --- | --- | --- |
| Common | 响应、异常、身份、消息指纹等跨模块基础能力；不承载服务业务 | 17 / 0 / 0 | 4 / 25 | 第61节规范检查通过 | `refactor/common-standards-complete-20261114`（规范收尾） |
| Gateway | filter鉴权/请求链，util路径策略，config规则解析与发布 | 7 / 0 / 0 | 5 / 21 | 第60节扫描通过 | `refactor/gateway-complete-20261104` / `faaecef2` |
| Cart | Controller → CartService/Impl → CartStorage；结算VO独立 | 24 / 1 / 1 | 13 / 35 | 第60节扫描通过 | `refactor/cart-complete-20261104` / `9eab801a` |
| Product | 查询/管理/分类/活动服务；index、metrics、search分组 | 102 / 1 / 5 | 37 / 361 | 第62节规范检查通过 | 结构`refactor/product-complete-20261104` / `e345bb4d`；规范`refactor/product-standards-complete-20261114` |
| User | 账号/资料/地址、扣款/退款、TCC/Fence及只读取证分离 | 100 / 7 / 8 | 18 / 284 | 第63节规范检查通过 | 结构`refactor/user-complete-20261104` / `418cd0d0`；规范`refactor/user-standards-complete-20261114` |
| Stock | 查询/预留/扣减/事实、cache、seckill、metrics及Seata演练分离 | 87 / 3 / 6 | 48 / 209 | 第64节规范检查通过 | 结构`refactor/stock-complete-20261104` / `ef53b37d`；规范`refactor/stock-standards-complete-20261114` |
| Order | 创建/查询/状态、秒杀接收/落单、超时/出站消息、恢复与分片分离 | 117 / 6 / 10 | 65 / 341 | 第60节扫描通过 | `refactor/order-complete-20261113` / `458efb7c` |
| Pay | 查询/创建/成功/失败/回调五用例，Legacy和TCC各职责分组 | 187 / 15 / 11 | 77 / 672 | 第60节扫描通过 | `refactor/pay-complete-20261114` / `3f5d069c` |

上表为第60节267类/1948项统一基线；其中265个*Test类，另含Order的StockSeckillFactFeignIT和Pay的PayOrderStateFeignIT。两个IT通过本机HTTP使用真实Feign/响应类型，但持久化边界受控，不能称真实商城链路。Product原生命周期单测产生一个自有Java子进程，已验证naturalExit/执行器关闭/AMQPConnections=0；这不涉及多Agent。

调用链索引：Cart见方案§13、Gateway§14、Product§15、User§16–18、Stock§19–22、Order§23–33、Pay§34–48。动态分片与元数据/恢复SQL仍封装在Mapper；这是保留既有实现，不是全面ORM替换。Feign内局部record以及表、Bean、消息/指标中的cm18/cm21兼容标识继续保留。

## 3. 实库与真实链路证据的分层结论

下表是此前实际运行、在第60节校验成功XML SHA/标签/提交可达性后的结果，**没有在第61–64节重跑，也不与1948、85、386、309或234项累加**。源代码迁移阶段曾做的其它局部实验仍在原章节，不把此表称为所有历史实验索引。

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

## 4. 规范清单处理结果与待执行总复核

原模块“结构完成”不覆盖后续发现的规范缺口。第60节只读审计采用同一JDK DocTrees/语法树与google-java-format 1.22.0；第61节Common17文件、第62节Product102文件、第63节User100文件、第64节Stock87文件分别再次检查通过。下表为各批处理后的原清单剩余数，待统一总复核。所有入口/成员检查排除record隐式生成方法，匿名类的显式public方法仍纳入。

| 模块 | JavaDoc绑定问题 | dry-run格式文件 | 非id短名 | 模板化说明 | 去重文件 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Common（第61节完成） | 0 | 0 | 0 | 0 | 0 |
| Product（第62节完成） | 0 | 0 | 0 | 0 | 0 |
| User（第63节完成） | 0 | 0 | 0 | 0 | 0 |
| Stock（第64节完成） | 0 | 0 | 0 | 0 | 0 |
| Cart/Gateway/Order/Pay | 0 | 0 | 0 | 0 | 0 |

第60节原60处说明绑定问题已分批处理：Product34处、User17处、Stock9处。Stock包含2处任务注释位置与7处独立说明，不能把原9处全部称为无注释；本批还将相邻分组说明分别写明SCAN提示/集合上限和返回后耗时/字段字节校验。Product四文件的23条模板说明及同方法空泛步骤已替换，本地ACK返回/未知结果语义保持。扫描仍不代替逐段说明的语义复核。

第60节103个短声明中96个id保留原主键/查询/传输语义；Common一处与Product六处非id短名均已完成语义改名。Product102文件现只剩40个语义明确id，没有新的非id短名命中。没有发现Cm编号生产类型；冻结测试、工具、表或消息中的编号不是本次类名重构遗漏。

后续顺序与通过门：

1. **Common（第61节已完成）**：四个格式文件与AuthInterceptor的ex，共五文件；补充ErrorCodes十二个常量的准确独立说明。异常处理顺序、JSON/ThreadLocal和全部错误码值保持。Common25项、Cart HTTP8项、Order/Stock指纹消费者52项，共85项通过；完整AST规范化只允许未使用形参改名。
2. **Product（第62节已完成）**：二十一文件全部复核，20个形成Git内容变化、启动类仅统一混合行尾。补说明/注释位置/六处局部命名/格式，完整AST和841个字面量保持；Product361与Common25共386项及八包通过，不重开历史CM21实验。
3. **User（第63节已完成）**：五文件复核，移动两个PO的16字段说明、补BalanceTccDebitVO.from映射说明、删除BalanceOperationVO未使用import，地址DTO仅行尾归一。完整AST及28字面量保持，User284/Common25共309项和八包通过。
4. **Stock（第64节已完成）**：十六文件，补监听/批处理入口和成员说明、修正两个任务注释位置并统一格式。完整AST/import集合及833字面量保持，Stock209/Common25共234项和八包通过；SQL/Lua、规则/指标、调度/租约未变。

下一步在四批完成后的统一源码状态做规范总复核，核对分层/命名/说明/格式及适用回归，再给出整体结论；当前只标记原清单处理完成。若发现新问题，先记录与界定范围，不能把总复核当作全库无边界格式化授权。

### 4.1 文件级清单

以下保留四批47文件的处理对照，原清单剩余0；Product启动类与User地址DTO仅工作区行尾归一，其余45文件有Git内容差异。“说明”是AST声明数，“模板”是匹配行数，不可相加为独立缺陷数量。统一规范总复核尚未执行。

#### cloud-mall-common（第61节已完成）

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [auth/AuthContext.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/auth/AuthContext.java) | 0 | 已完成 | 0 | 0 |
| [error/ErrorCodes.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/error/ErrorCodes.java) | 0 | 已完成 | 0 | 0 |
| [messaging/SeckillRequestFingerprint.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/messaging/SeckillRequestFingerprint.java) | 0 | 已完成 | 0 | 0 |
| [web/AuthInterceptor.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/web/AuthInterceptor.java) | 0 | 通过 | 0 | 0 |
| [web/GlobalExceptionHandler.java](../../backend/cloud-mall-common/src/main/java/com/cloudmall/common/web/GlobalExceptionHandler.java) | 0 | 已完成 | 0 | 0 |

#### cloud-mall-product（第62节已完成）

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [ProductApplication.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/ProductApplication.java) | 0 | 通过 | 0 | 0 |
| [config/ElasticsearchConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ElasticsearchConfiguration.java) | 0 | 通过 | 0 | 0 |
| [config/ProductIndexFeatureConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductIndexFeatureConfiguration.java) | 0 | 通过 | 0 | 0 |
| [config/ProductMessagingConfiguration.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductMessagingConfiguration.java) | 0 | 通过 | 0 | 0 |
| [config/ProductReadExceptionHandler.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/config/ProductReadExceptionHandler.java) | 0 | 通过 | 0 | 0 |
| [domain/po/ProductIndexDocument.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/po/ProductIndexDocument.java) | 0 | 通过 | 0 | 0 |
| [domain/po/ProductSearchHit.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/po/ProductSearchHit.java) | 0 | 通过 | 0 | 0 |
| [domain/vo/HotStatVO.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/domain/vo/HotStatVO.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductHotStatMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductHotStatMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductIndexConsumptionMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexConsumptionMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductIndexDeadLetterMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexDeadLetterMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductIndexMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductIndexOutboxMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexOutboxMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductIndexRebuildMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductIndexRebuildMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/ProductSqlMapper.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/mapper/ProductSqlMapper.java) | 0 | 通过 | 0 | 0 |
| [service/ProductIndexWriter.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/ProductIndexWriter.java) | 0 | 通过 | 0 | 0 |
| [service/impl/index/ElasticsearchProductIndexWriter.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ElasticsearchProductIndexWriter.java) | 0 | 通过 | 0 | 0 |
| [service/impl/index/ProductIndexConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexConsumer.java) | 0 | 通过 | 0 | 0 |
| [service/impl/index/ProductIndexDocumentLoader.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexDocumentLoader.java) | 0 | 通过 | 0 | 0 |
| [service/impl/index/ProductIndexOutboxDispatcher.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/index/ProductIndexOutboxDispatcher.java) | 0 | 通过 | 0 | 0 |
| [service/impl/metrics/ProductMetricConsumer.java](../../backend/cloud-mall-product/src/main/java/com/cloudmall/product/service/impl/metrics/ProductMetricConsumer.java) | 0 | 通过 | 0 | 0 |


#### cloud-mall-user（第63节已完成）

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [domain/dto/AddressRequestDTO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/dto/AddressRequestDTO.java) | 0 | 通过 | 0 | 0 |
| [domain/po/BalanceDebitAttemptPO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/po/BalanceDebitAttemptPO.java) | 0 | 通过 | 0 | 0 |
| [domain/po/BalanceTccDebitPO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/po/BalanceTccDebitPO.java) | 0 | 通过 | 0 | 0 |
| [domain/vo/BalanceOperationVO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/vo/BalanceOperationVO.java) | 0 | 通过 | 0 | 0 |
| [domain/vo/BalanceTccDebitVO.java](../../backend/cloud-mall-user/src/main/java/com/cloudmall/user/domain/vo/BalanceTccDebitVO.java) | 0 | 通过 | 0 | 0 |

#### cloud-mall-stock（第64节已完成）

| 文件 | 说明 | 格式 | 短名 | 模板 |
| --- | ---: | --- | ---: | ---: |
| [config/SeckillSentinelConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/SeckillSentinelConfiguration.java) | 0 | 通过 | 0 | 0 |
| [config/StockDependencyAspect.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockDependencyAspect.java) | 0 | 通过 | 0 | 0 |
| [config/StockDependencySentinelConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockDependencySentinelConfiguration.java) | 0 | 通过 | 0 | 0 |
| [config/StockMessagingConfiguration.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/config/StockMessagingConfiguration.java) | 0 | 通过 | 0 | 0 |
| [domain/po/SeckillOrderOutboxPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/SeckillOrderOutboxPO.java) | 0 | 通过 | 0 | 0 |
| [domain/po/StockCacheRepairPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/StockCacheRepairPO.java) | 0 | 通过 | 0 | 0 |
| [domain/po/StockReservationRequestPO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/po/StockReservationRequestPO.java) | 0 | 通过 | 0 | 0 |
| [domain/vo/StockReservationItemVO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/vo/StockReservationItemVO.java) | 0 | 通过 | 0 | 0 |
| [domain/vo/StockReservationVO.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/domain/vo/StockReservationVO.java) | 0 | 通过 | 0 | 0 |
| [mapper/StockConsistencyAuditStateMapper.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/mapper/StockConsistencyAuditStateMapper.java) | 0 | 通过 | 0 | 0 |
| [mapper/StockSqlMapper.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/mapper/StockSqlMapper.java) | 0 | 通过 | 0 | 0 |
| [service/impl/StockQueryServiceImpl.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/StockQueryServiceImpl.java) | 0 | 通过 | 0 | 0 |
| [service/impl/StockReservationRequestService.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/StockReservationRequestService.java) | 0 | 通过 | 0 | 0 |
| [service/impl/cache/StockCacheReconciler.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/cache/StockCacheReconciler.java) | 0 | 通过 | 0 | 0 |
| [service/impl/cache/StockConsistencyReconciler.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/cache/StockConsistencyReconciler.java) | 0 | 通过 | 0 | 0 |
| [service/impl/seckill/SeckillQuotaSettlementService.java](../../backend/cloud-mall-stock/src/main/java/com/cloudmall/stock/service/impl/seckill/SeckillQuotaSettlementService.java) | 0 | 通过 | 0 | 0 |

### 4.2 已完成的短名更正

Common的ex→exception已在第61节完成。第62节Product完成六处：Outbox Mapper的rs→resultSet；ES兼容删除e→deleteFailure；Consumer的消费/写入/删除/日期解析异常分别改为consumptionFailure/writeFailure/deleteFailure/invalidTimestamp。仅改各自作用域内标识符，异常类型、包装、日志和重抛顺序保持；原行号和允许变化明细保留于阶段证据。

## 5. 额外真实场景与原有风险

这些事项必须保持可见，但不自动扩展为本轮结构重构的执行队列。

| 事项 | 当前证据/限制 | 启动条件 |
| --- | --- | --- |
| 公开TCC主动取消、未决中止的真实链路 | 第60节统一单测包含取消/入口/投影；§46有34项局部MySQL，§47有7项入口本地事务。四服务delivery不覆盖完整取消矩阵 | 若要声明公开取消全链通过，先评审有限场景和新隔离环境，再运行 |
| 旧PayTccCancelIT复用 | 仍有固定Nacos/TC地址、自建Redis、旧TC证据默认入口和RmTcLinkProxy源码/class固定SHA准入 | 不原样启动；先核当前构建pin和隔离/资源生命周期，不删除或绕过pin检查 |
| Gateway与真实Product/Cart的全商城请求 | 第60节Gateway/Feign为实际入口+受控依赖，§59的Product/Cart为只读替身 | 作为独立全商城验收制定数据、登录、路由及清理范围 |
| Gateway版本差异 | 第60节Surefire classpath读回spring-cloud-gateway-server **3.1.4**，规格写3.1.5；属既有差异 | 单独核对BOM/规格决策，本轮不升级依赖 |
| Sharding解析器版本提示 | 统一测试出现既有ANTLR4.7.2/4.8提示，测试通过 | 记录兼容观察，不把提示误称当前失败或擅自换栈 |
| 全故障/性能/CM21遗留 | 有限回归不覆盖每个网络/崩溃窗口；历史NO-GO/UNKNOWN不因当前通过消失 | 明确的新任务与范围；不自动重启历史维护或性能runner |

## 6. 失败现场与资源保留

第60节只读核对本表覆盖的12个实验项目：容器/网络均无残留，43个记录中的命名卷仍存在。这个数量仅是本报告选取的§46/47/50–59实验集合，**不是整机或整个重构所有历史卷的总量**；没有创建、启动、挂载或删除任何卷。

- §54 Nacos授权首轮失败的两owned库仍保留于该实验MySQL卷；不因后续桥接测试通过删除。
- §59首轮四库仍保留：TCC已提交、余额90/冻结0，Pay PENDING、Order PENDING_PAYMENT、库存9/1/0，尚未交付；第二轮12项通过没有清算或改写此样本。首轮/第二轮共12卷保留。
- §59第二轮两个Redis字符串Key精确归属两笔LEGACY扣款缓存，沿原30天TTL保留；没有用FLUSHDB清场。
- 当时另有三个CloudNest容器由其他操作启动，调用者未知；原记录保留。本轮不要求其他项目状态静止，也不操作它们。

证据中的UNKNOWN、BEGIN_UNKNOWN、PENDING及EVIDENCE_INCOMPLETE须结合具体用例解释，不能把TC结束、容器关闭或新轮通过当作旧业务结清。

## 7. 回退与证据定位

阶段完成标签只定位那个提交，不等于一条revert撤销整个模块。Order完整切片逆序索引见方案§33.4，Pay见§48.4，其余见对应模块章节。已存在后续依赖时先核改动、保存无关工作树、按逆序处理并重编译；不得用reset --hard、全量clean或删除历史证据演示回退。

第60节方案基线为`refactor/backend-acceptance-baseline-20261114`，汇总完成标签为`refactor/backend-acceptance-complete-20261114`。它只覆盖两份Markdown，回退不触及Java或任何运行数据：

```powershell
git revert refactor/backend-acceptance-complete-20261114
```

第60节证据位于`target/backend-refactor-20261114/refactoring-acceptance/`：

- `verification.json`：提交、测试、规范待办与逆补丁入口。
- `final-summary.json`、`final-reports/`：第60节267类1948项及SHA，未混入旧Surefire XML。
- `historical-evidence-check.json`：12组历史报告SHA/标签/提交树核对；只复核，不冒充新执行。
- `structural-inventory.json`、`reviewed-followup-inventory.json`、各模块TSV/format输出：641文件结构和第60节原始47文件规范清单。
- `jar-check.json`：八Jar共628生产class，编译字节/SourceFile对应。
- `retained-experiment-resources.json`：上述12项目43卷只读检查。

ignored证据保留在当前工作区；Git版本化本报告、主方案和各阶段明确修改的源码。后续会话先读本报告、第64节及实际Git状态，从统一规范总复核继续。


### 7.1 第61节增量结果与回退

Common五文件已完成；完整AST对照排除注释后，仅afterCompletion的未使用Exception ex改为exception，ErrorCodes十二字段的名称/修饰符/顺序/字符串值保持。Common17文件必需说明绑定/短名/格式检查通过，1118份其它backend/deploy与README保持。

第61节12类85项（Common25、Cart8、Order25、Stock27）和八模块package通过。证据`target/backend-refactor-20261114/common-standards/verification.json`包含XML、AST、十二错误码契约、源码保持与八Jar检查；未重跑1948项或真实中间件。

方案基线`refactor/common-standards-baseline-20261114`，完成标签`refactor/common-standards-complete-20261114`；精确回退五Java与两份文档，Git不回退运行数据：

```powershell
git revert refactor/common-standards-complete-20261114
```


### 7.2 第62节增量结果与回退

Product21文件全部完成；其中ProductApplication仅将混合行尾统一为CRLF，规范化Git blob前后相同，其余20文件形成内容差异。完整Javac语法树只允许六个作用域内局部更名，import集合保持；841个字面量的值与顺序一致，覆盖SQL/ES脚本、消息/指标/错误常量。34处说明绑定、16个格式差异、六短名、23条模板说明处理后，Product全102文件检查通过，40处原id保留。

本轮41类386项（Product361/Common25）首次通过，八模块package及628生产class检查通过；原Product生命周期测试的自有Java子进程自然退出、执行器关闭、AMQPConnections=0。没有改测试源码、运行真实中间件或叠加旧85/1948项。

证据`target/backend-refactor-20261114/product-standards/verification.json`；方案基线`refactor/product-standards-baseline-20261114`，完成标签`refactor/product-standards-complete-20261114`。精确回退20个有内容差异的Java与两份文档；Git不还原工作区原混合行尾或运行数据：

```powershell
git revert refactor/product-standards-complete-20261114
```

### 7.3 第63节增量结果与回退

User五文件完成，四个形成Git内容差异：两个PO的16字段说明移到注解前，BalanceTccDebitVO.from补准确说明，BalanceOperationVO移除未使用import；AddressRequestDTO仅工作区行尾统一，规范化Git blob不变。五完整AST无标识符/方法体归一且保持一致，28字面量值/顺序保持。空取消临时摘要对象、null/XID最多前8位、金额toPlainString及表/列映射不变。

本轮22类309项（User284/Common25）首次通过，八包628生产class验证通过，User全100文件说明/格式/非id短名检查通过。1118其它backend/deploy和README字节保持；不改测试、不新增真实中间件回归，不与旧测试数量相加。首次import审计误将空白行计入删除，已留痕修正工具并重新验证；没有业务测试失败。

证据`target/backend-refactor-20261114/user-standards/verification.json`；方案基线`refactor/user-standards-baseline-20261114`，完成标签`refactor/user-standards-complete-20261114`。精确回退四Java与两文档，不恢复地址DTO原混合行尾或运行数据：

```powershell
git revert refactor/user-standards-complete-20261114
```

### 7.4 第64节增量结果与回退

Stock16文件全部完成并形成Git内容差异，处理9处说明绑定和12个格式文件；两个分组说明改为各字段准确含义。原20秒审计租约/5秒续期、SCAN COUNT提示/集合上限、Redis返回后阈值、批量Long 0L→STALE/其它→APPLIED及全部SQL/Lua不变。

16完整AST直接相等，无标识符/方法体归一，import集合与833字面量值/顺序保持；Stock87文件说明/格式/非id短名检查通过，13个语义id保留。本轮52类234项（Stock209/Common25）首次通过，八包628生产class验证通过；1107其它backend/deploy与README保持，不改测试、不运行新的真实中间件回归。提交前归档脚本复制时误改class预期数628为1828，已留痕修正工具并重新核验；业务测试无失败。

原47文件规范清单现为0剩余，下一步统一规范总复核，仍保留完整商城/性能及既有真实场景的验收边界。证据`target/backend-refactor-20261114/stock-standards/verification.json`，基线`refactor/stock-standards-baseline-20261114`。完成标签回退16Java与两文档，不恢复运行数据：

```powershell
git revert refactor/stock-standards-complete-20261114
```
