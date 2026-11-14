# 完整商城普通购物链路验收方案与记录

日期：2026-11-14。用户在后端重构第65节完成后明确选择本新范围；当前会话仍由主Agent单独执行。基线e99545dc，本阶段验证实际页面与Gateway、User、Product、Cart、Order、Stock、Pay七服务，不使用Product/Cart替身。

## 1. 冻结范围与通过条件

以现有默认普通购物、LEGACY余额支付和dynamic-monthly订单路由为基线；启用Product已有search开关，验证真实MySQL outbox→Rabbit→Elasticsearch索引和检索。保留Gateway鉴权、Nacos发现、Redis登录态/购物车、订单提交后条件清理、库存持久事实和真实余额账本。此选择不覆盖TCC取消/未决中止矩阵、秒杀/性能压测或全部故障窗口。

1. 匿名访问商品可用、购物车被拒；浏览器注册/登录及地址保存成功，中文页面无阻断错误。
2. 使用新实例内预置商品，经真实管理员发布接口触发索引；等待持久消费与ES可见，页面检索并打开详情，加入购物车，核对数量和金额。
3. 页面结算并创建订单，核对地址/SKU/金额快照、库存预留、购物车条件清理；从页面创建支付并模拟成功，等待订单PAID/支付SUCCESS、余额仅扣一次、库存reserved=0且sold对应购买数量。
4. 补充同支付成功请求重放的资金幂等、另一新用户不可读取首用户订单、普通待支付订单取消释放库存、退出登录后受保护接口拒绝。不会通过直接改订单/支付/资金状态使断言通过。
5. 归档浏览器快照/截图和请求摘要、各真实服务日志、MySQL订单/库存/资金关联事实、Redis/MQ/ES及Nacos来源，区分测试夹具初始化与真实业务效果。

金额按服务端BigDecimal/JSON字符串核对，时间沿Asia/Shanghai。前端只使用当前代码与现有API，不注入mock路由。外部商品图片加载情况单独记录，不作为支付正确性的替代证据。

## 2. 环境与数据归属

使用deploy/experiments/compose.yml及本次独立覆盖文件，新项目cloud-mall-storefront-20261114-<runId>、新owner、七个新卷：MySQL、Redis、Rabbit、ES、Nacos data/logs及Seata file store。六容器均沿既定镜像/root认证，ES保持开发环境无认证。启动前核对端口、镜像、子网、容量和项目/卷不存在；不复用任何历史卷、不停止其它项目或根Compose服务。

业务七服务优先沿8080–8086，前端5173，已读回无监听后仍须bind准入；中间件计划使用MySQL20100、Rabbit20101/20102、ES20103、Redis20104、Nacos20105及gRPC21105/21106、TC20106，专用子网10.254.213.0/24。若占用则先记录并调整本次显式清单，绝不终止占用者。所有业务连接参数显式指向本组；Sentinel保留规则功能，控制台地址覆盖为未使用的本地实验地址以免连接日常Dashboard。

新MySQL导入当前schema.sql及其演示商品，只操作本组数据库；Nacos初始化root及含public资源的配置/注册权限。测试管理员通过公开注册生成密码摘要，仅在新库授ADMIN夹具角色后重新登录；用户、地址、购物车、订单和支付均通过真实入口产生。普通商品使用已有演示库存；不接触日常商城数据。

Java子进程采用当前打包Jar、JDK17、+08时区/UTF-8及明确堆上限，每次保存PID/命令摘要、退出回执。前端沿Vite5173代理8080，通过Playwright独立session操作，使用output/playwright/storefront-<runId>存浏览器证据。业务源码、测试源码、前端源码、共享配置与README默认保持，发现真实Bug先记bugfix_log并限定修复范围。

## 3. 执行、证据与停止规则

先提交本方案并建立acceptance/storefront-baseline-20261114。本次one-off编排和证据存target/storefront-acceptance-20261114，记录新项目全量资源与既有容器身份/挂载前像。复用已验证构建前须核Jar与当前classes/source；前端执行实际lint/test/build后启动。测试失败立即保存原请求/响应和相关事实，不隐去失败、不更换断言制造成功。

成功后先归档本组数据库dump、各服务与中间件日志/inspect、HTTP/浏览器摘要和业务核账，再关闭本次自有浏览器/Vite/七Java进程，核对退出；删除本组容器/网络，保留七卷及证据。失败也先保存现场；不执行FLUSH、DROP失败库、down -v、prune或全量clean。停止只针对记录的PID且核命令/创建时间，不使用全局进程名终止。

本阶段默认只提交方案、必要的缺陷修正（如有）与验收报告，不提交凭证、临时产物或无关工作树。最终记录当前通过项/未覆盖范围、所有实际失败与修复、资源关闭/保留清单和Git逆补丁验证；测试未通过不宣称验收完成。Git回退不恢复运行数据。

## 4. 首轮观察与限定修复（执行中补充）

项目cloud-mall-storefront-20261114-32b97a49已使用六容器/七全新卷、七真实服务和Vite页面。首次浏览器订单2026117e1ef505390347259848322e4bd0bf47金额79.80，余额扣款请求返回202/PAY_ORDER_SYNC_UNKNOWN；21:42:00日志为Order PAID、stockAction PENDING，21:42:03库存确认，21:42:06原Pay定时任务读取SUCCEEDED后完成SUCCESS。只有一条DEBIT79.80，余额9920.20，库存9998/0/2；这是原异步收敛路径，没有补账或重发扣款。

前端Pay把202只当报错显示，PayResult也只读一次，导致后端已成功而页面仍提示待确认。先在bugfix_log记录，再限定改frontend/src/App.tsx的Pay/PayResult与frontend/src/App.test.tsx：202进入原订单支付结果页，结果页最多30次、每次完成后隔2秒串行只读GET，终态SUCCESS/FAILED/REFUNDED停止；暂时查询失败可继续，超出边界保持待确认/查看订单，不重发createPayment/mockSuccess/mockFail。写请求处理中禁用重复按钮；卸载停止后续查询。其它页面、后端、支付状态机和API不变。

先补真实React组件测试复现202后未进入只读查询，验证无重复POST、非终态不误判成功、终态/卸载停止与次数边界，再实现并运行前端全量lint/test/build。首轮订单与202证据保留；用同一隔离环境的第二笔新UI订单验证修复，分别核账，不覆盖第一次记录。favicon缺失404、没有预置固定秒杀活动的404和外部图片ORB失败单列非阻断观察，不扩展为新页面改版。

## 5. 执行结果与收尾

普通购物LEGACY全链路已完成，详细结果见[验收报告](storefront_acceptance_report.md)。首笔支付暴露页面202后不跟踪的问题，后端原调度正确收敛；仅Pay/PayResult组件及5项组件回归发生源码变化。结果页每个有效effect实例最多30次查询，每次响应后隔2秒；终态停止，卸载的旧实例不再调度。Vite开发StrictMode初始化会出现一次被清理effect的额外GET，网络证据保留该事实，不称全开发页面绝对只有一次首次GET；没有重复扣款POST。

修复后第二笔UI订单再次收到202，21:49:33开始只读查询，21:49:43已读取SUCCESS，早于21:50:10显示文案热更新；因此成功不是靠刷新或重发扣款。结果页使用“支付成功/支付失败/已退款/确认中”而非协议状态码。两笔各79.80，最终两条DEBIT共159.60，余额9840.40；第三笔39.90仅作为普通取消用例，由API创建后在页面取消，库存最终available9996/reserved0/sold4，余额不变。同一成功key重放两次账本保持，另一用户读订单403，UI退出及三枚夹具API token退出后均拒绝受保护访问。

前端初始21项通过；新增组件红测首轮3失败/20通过（单App测试文件），修复与补充重复点击测试后全量26项通过，lint/build通过。复用的八个后端Jar共628生产class已与classes/SourceFile核对；没有更改后端或重跑旧1948项。1166份其它已跟踪backend/frontend/deploy与原README字节保持。

归档工具问题均留痕：login.userId字段误读、分片名误猜t_order、Docker Mounts数组顺序误判、当前Playwright的_file响应体引用未被初版sha1采集器收集；分别按真实契约/元数据/挂载内容/200条文件引用修正，只补证据，没有改写业务状态。首轮资料、SQL dump、网络响应、截图和工具失败记录保留。

归档完成后仅关闭本次浏览器session、Vite和七Java（按PID/命令/创建时间核对后Stop-Process，不能称自然退出）；Rabbit连接0，Nacos七个自有实例明确注销。六容器和项目网络已移除，七命名卷保留；35既有容器ID/镜像/挂载/重启计数与运行状态保持。Redis保留8个库存投影和2个30天扣款缓存，没有auth/购物车Key；Rabbit保留3条原订单TTL消息，未清空队列或删除业务数据。

## 6. Git与证据

初始方案4b807e56，首次前端修复范围冻结b8233a48。完成提交覆盖App.tsx、App.test.tsx、本方案、新报告及原重构验收汇总中的新验收链接，共五文件；不提交output/、target/、凭证或README。完成前执行git diff --check及临时index逆补丁验证，完成标签acceptance/storefront-complete-20261114；回退会撤销前端状态跟踪修复和本轮文档结果，不会撤销隔离实验交易。

证据入口target/storefront-acceptance-20261114/verification.json；浏览器资料output/playwright/storefront-32b97a49。它们保留在当前工作区，Git不管理运行数据。TCC公开取消/未决中止、秒杀、完整性能矩阵及所有故障窗口仍未纳入本次通过结论。
