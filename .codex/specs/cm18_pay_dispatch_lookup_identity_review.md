# CM-18 16T177：Pay dispatch lookup identity

## 1. 结论与范围

GO。Bridge.recover and DispatchPersistence.mirror use CI payNo point lookup and only test null; alias rows may be treated as requested dispatch and reach terminal/User GET or mirror update.

本批只补 returned dispatch payNo exact identity，不改 SQL/collation/history。

## 2. Guards

After original null-not-found handling, both entry points require row.payNo non-null and `Objects.equals(row.payNo, requestedPayNo)`:

- Bridge mismatch throws existing `PAY_TCC_DISPATCH_CONFLICT/409` BizException before config/terminal/branch/User GET/POST/persistence;
- Persistence mismatch uses existing `conflict()` before terminal/false early return or mirrorState update.

Matching/missing404/terminal/PREPARED/branch/CAS/readback/config/status/diagnostic/API/other entries retain original behavior. No normalize/casefold/BINARY SQL/history repair.

## 3. 验收边界

- Native owned actual dispatch Mapper + Bridge/Persistence: actual CI case alias in both directions does not return foreign terminal, User GET/POST, mirror false or update; facts/checksum/pool/owned cleanup. Space/foreign/null variants are unit-simulated returned objects only: production collation may be NO_PAD, and NOT NULL columns are not claimed to return NULL.
- Matching, missing404, terminal/PREPARED/active controls; unit foreign/case/space/null (NULL production column only mocked).
- No App/TC/RM/original DB writes; no claim of full dispatch/source fencing.

## 4. 交付边界

实现仅允许在 Bridge.recover 与 DispatchPersistence.mirror point lookup 增加 exact payNo guards、JavaDoc/测试；不得修改 Mapper SQL、状态机、配置或其它入口。
