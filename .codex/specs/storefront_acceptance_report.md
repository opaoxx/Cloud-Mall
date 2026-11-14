# 完整商城普通购物链路验收报告

日期：2026-11-14。方案：[storefront_acceptance_plan.md](storefront_acceptance_plan.md)。执行者：当前会话主Agent；单Agent执行。

## 结论

**真实浏览器→Gateway→七个业务服务的普通购物与LEGACY余额支付链路通过。** Product与Cart均为真实服务，使用独立MySQL/Redis/Rabbit/Elasticsearch/Nacos/Seata实例。本轮发现并修复前端支付202后不继续查询状态的问题，后端状态机和接口未改。

本结论覆盖注册/登录、地址、商品发布索引/检索/详情、购物车、结算、下单、余额支付、普通待支付取消、身份隔离和退出。未覆盖TCC取消矩阵、秒杀/所有故障窗口或性能压测；Seata实例用于现有客户端接入，不把LEGACY交易声称为TCC验收。

## 实际结果

| 场景 | 结果与证据 |
| --- | --- |
| 环境归属 | cloud-mall-storefront-20261114-32b97a49，6容器/7全新卷；Nacos七服务各唯一127.0.0.1实例，8080–8086；Vite5173代理Gateway |
| 匿名与登录 | 匿名购物车401；浏览器注册后自动登录，新账户10000.00；地址通过页面写入并用于订单快照 |
| 商品 | 本组管理员注册后授ADMIN夹具角色，公开发布接口产生outbox；真实Rabbit消费APPLIED，ES商品1003001可检索“马克杯”，页面单价39.90 |
| 购物车与结算 | 页面选SKU1103001、数量2，预览/订单金额均79.80；订单提交后原购物车项删除；支付前预留2、余额未动 |
| 首笔支付 | 2026117e1ef505390347259848322e4bd0bf47；首请求202，页面未继续跟踪；后端原任务约6秒后自动SUCCESS/PAID/库存售出；唯一DEBIT79.80 |
| 修复后支付 | 20261161131c9df65e41bfa55dcb3456e9c0a4；单次扣款POST返回202；原订单只读GET从ORDER_SYNC_UNKNOWN到SUCCESS，页面显示支付成功，没有重发扣款 |
| 幂等 | 对已成功支付使用同一key重放两次，余额/账本保持，共两条DEBIT159.60 |
| 身份隔离 | 另一新注册用户读取首用户已存在订单得到403/data=null |
| 普通取消 | API创建第三笔202611274e568e4c914aa0a2dc3b73dab2cafc（39.90），页面取消为CANCELLED，释放1件预留，无第三笔支付或扣款 |
| 最终核账 | 两订单PAID、一订单CANCELLED，两支付SUCCESS；余额9840.40、冻结0；库存总量10000/可用9996/预留0/售出4 |
| 退出 | 页面退出后进入购物车重定向登录；三个夹具API token退出后再次请求购物车均401，匿名同样401 |

## 缺陷、修复与验证

后端已明确用HTTP202表达暂时未知，首笔的Order已PAID但库存动作尚PENDING；原库存任务完成后Pay扫描确认SUCCEEDED并结清。缺陷在frontend/src/App.tsx：Pay只显示异常，PayResult只读取一次，用户看不到自动恢复。

仅修改Pay/PayResult及App.test.tsx。202进入原订单结果页，按响应完成后2秒间隔只读查询，当前有效effect最多30次；SUCCESS/FAILED/REFUNDED停止，临时读失败继续，超限保持“仍待确认”并提供查看订单链接。卸载清理调度，不把迟到响应用于已离开的页面；写请求进行中用ref和disabled阻止重复支付/取消。结果文案不展示协议内部状态。

新增5项真实React组件回归，覆盖202→只读查询→终态、查询失败重试/FAILED终态、30次上限、未完成请求/卸载停止、重复点击不重复写。首轮红测3失败，修复后前端全量26项、lint、build全部通过；日志分别保留。开发StrictMode导致初始额外GET，旧effect取消后不再轮询，未出现重复POST。第二笔13:49:43Z已GET到SUCCESS，早于13:50:10Z中文结果标题热更新；网络时间线和响应体可核实。

八个后端现有Jar的628生产class已核对编译字节和SourceFile，本轮未改后端、未重新运行旧1948项。除两个前端文件外1166份已跟踪backend/frontend/deploy源码保持，README原有修改保留。

## 非阻断观察与采集问题

- favicon.ico 404；没有预置首页固定秒杀活动时接口404且页面显示准备中；一张演示商品外链图片被浏览器ORB阻止并使用原fallback。未扩大为图标、秒杀或外部图片改造，也不宣称控制台完全无错误。
- 采集器曾误读login.user.id、猜测t_order分片名；修正为实际userId及mall_order元数据，未重发业务交易。
- 清理核验曾按Docker Mounts数组顺序误判变化；保留前后inspect后按Destination比较全部字段通过，没有操作其它容器。
- Playwright当前网络日志用_file引用响应体，初版仅收sha1引用；保留初版资料并补齐200条真实文件引用，支付时间线重新解析通过。

## 资源收尾与复盘入口

自有浏览器、Vite及七Java均已关闭；Java/Node经PID、命令及创建时间核对后Stop-Process，不声称自然退出。Rabbit连接0，Nacos自有七实例明确注销。六容器和专用网络删除，七卷全部保留；35既有容器身份/镜像/挂载/重启与运行状态保持。未FLUSH/DROP/prune或操作日常环境。

数据库完整dump、三订单与两笔账本、原202现场、Redis两个扣款缓存/八库存投影、Rabbit三条TTL消息和ES索引保留在本组卷与证据中；没有把“连接0”写成“队列0”。

- 证据入口：target/storefront-acceptance-20261114/verification.json。
- 核账：final-business.json、final-sql-proof.json、paid-idempotency-ownership-proof.json、database-final.sql。
- 页面/网络：output/playwright/storefront-32b97a49内付款/取消截图、trace及200个响应/快照文件；browser-payment-timeline.json记录202与后续GET。
- 清理：process-exits.json、resource-closeout.json、retained-volumes.json。

完成标签：acceptance/storefront-complete-20261114。已验证临时index逆补丁，回退只撤销本次前端修复和文档，不恢复数据库/缓存/消息：

```powershell
git revert acceptance/storefront-complete-20261114
```
