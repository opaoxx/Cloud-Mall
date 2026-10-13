# CM-18 16T169：Pay Legacy retry error Unicode truncation

## 1. 结论与范围

GO。`recordRetry` 与 `recordRetryOwnedLegacy` 以 UTF-16 `substring(0,512)` 截断 last_error；512 边界可能切断 supplementary pair，造成孤立 surrogate/替换字符，且纯 supplementary 字符被按 code unit 过早截断。

本批只修两个 retry error 参数的 Unicode code-point 截断。

## 2. Shared helper

两方法复用同一 private `truncateRetryError`：

- null 保持 null，empty 保持 empty；
- `maxCodePoints = Math.min(512, error.codePointCount(0,error.length()))`；
- 通过 `offsetByCodePoints(0,maxCodePoints)` 取得 substring 边界，保留最多512完整 Unicode code points；
- 不 trim、normalize、替换或 fallback，不改错误文本顺序。

保 SQL、绑定顺序、VARCHAR(512)、状态/租约/owner/退避/Service/API/TCC；仅修 Java 诊断字符串截断。历史 lone-surrogate 字符串不作修复或合法化承诺。

## 3. 验收

- Owned mapper：511 ASCII+emoji+tail、BMP/null/empty/511/512/513、纯 supplementary、mixed prefix；验证字符数/字节数、emoji 完整性和原 immutable facts。
- Process：用户证据异常带 prefix 按原 retry 保存完整 code-point 截断、清 lease/未来退避、无资金 RPC；错 owner/过期/mode/退款方向仍拒绝不写。
- 真实 `last_error` SQL 读回与原库指纹/两 schema/池 cleanup；不启动 App/RM/TC。

## 4. 交付边界

实现仅允许新增共享 retry error helper、替换两处绑定及必要 JavaDoc/测试；不得修改 DDL、SQL、历史诊断、归一化或其它 TCC 代码。
