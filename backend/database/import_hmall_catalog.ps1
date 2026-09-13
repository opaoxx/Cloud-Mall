param(
  [string]$SourceSql = 'F:\hmall\sql\hm-item.sql',
  [string]$Container = 'cloud-mall-mysql',
  [string]$RedisContainer = 'cloud-mall-redis'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $SourceSql -PathType Leaf)) {
  throw "找不到 hmall 商品 SQL：$SourceSql"
}

docker inspect $Container *> $null
if ($LASTEXITCODE -ne 0) {
  throw "MySQL 容器不存在：$Container，请先执行 docker compose up -d mysql"
}

Write-Host "[1/2] 导入 hmall 源商品表..."
Get-Content -Raw -Encoding UTF8 -LiteralPath $SourceSql |
  docker exec -i $Container mysql -uroot -proot --default-character-set=utf8mb4
if ($LASTEXITCODE -ne 0) {
  throw 'hmall 源 SQL 导入失败'
}

Write-Host '[2/2] 执行 CloudMall 商品 ETL...'
Get-Content -Raw -Encoding UTF8 -LiteralPath "$PSScriptRoot\migrate_hmall_catalog.sql" |
  docker exec -i $Container mysql -uroot -proot --default-character-set=utf8mb4
if ($LASTEXITCODE -ne 0) {
  throw 'CloudMall 商品 ETL 失败，事务应已回滚'
}

Write-Host '校验商品、SKU、库存和分类数量...'
$counts = docker exec $Container mysql -uroot -proot --batch --skip-column-names -e @'
SELECT
  (SELECT COUNT(*) FROM `hm-item`.item),
  (SELECT COUNT(*) FROM cloudmall_product.product),
  (SELECT COUNT(*) FROM cloudmall_product.product_sku),
  (SELECT COUNT(*) FROM cloudmall_stock.stock_sku),
  (SELECT COUNT(*) FROM cloudmall_product.product_category);
'@
if ($LASTEXITCODE -ne 0) {
  throw '商品 ETL 数量校验查询失败'
}
$values = ($counts -split '\s+') | Where-Object { $_ -ne '' }
if ($values.Count -lt 5 -or $values[0] -ne $values[1] -or $values[0] -ne $values[2] -or $values[0] -ne $values[3]) {
  throw "商品 ETL 数量不一致：$counts"
}
Write-Host "源商品 $($values[0]) 条，分类 $($values[4]) 个。"

docker inspect $RedisContainer *> $null
if ($LASTEXITCODE -ne 0) {
  throw "Redis 容器不存在：$RedisContainer，无法清理与旧商品关联的购物车"
}

Write-Host '初始化首页秒杀活动 Redis 状态...'
$activityInfo = docker exec $Container mysql -uroot -proot --batch --skip-column-names -e @'
SELECT sku_id, UNIX_TIMESTAMP(start_at), UNIX_TIMESTAMP(end_at), stock_limit
FROM cloudmall_product.seckill_activity
WHERE id = 910000000001;
'@
if ($LASTEXITCODE -ne 0) {
  throw '秒杀活动查询失败'
}
$activityValues = ($activityInfo -split '\s+') | Where-Object { $_ -ne '' }
if ($activityValues.Count -lt 4) {
  throw "秒杀活动未生成：$activityInfo"
}
$activitySkuId = $activityValues[0]
$activityStartEpoch = [long]$activityValues[1]
$activityEndEpoch = [long]$activityValues[2]
$activityStockLimit = $activityValues[3]
$activityTtl = [Math]::Max(1, $activityEndEpoch - [DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
$activityStartIso = [DateTimeOffset]::FromUnixTimeSeconds($activityStartEpoch).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
$activityEndIso = [DateTimeOffset]::FromUnixTimeSeconds($activityEndEpoch).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')

foreach ($pattern in @('seckill:stock:*', 'seckill:meta:*', 'seckill:product:*', 'seckill:user:*', 'seckill:accepted:*')) {
  $keys = docker exec $RedisContainer redis-cli -a root --no-auth-warning --scan --pattern $pattern
  if ($LASTEXITCODE -ne 0) {
    throw "Redis 秒杀缓存扫描失败：$pattern"
  }
  foreach ($key in ($keys -split '\r?\n' | Where-Object { $_ -ne '' })) {
    docker exec $RedisContainer redis-cli -a root --no-auth-warning DEL $key *> $null
    if ($LASTEXITCODE -ne 0) {
      throw "Redis 秒杀缓存清理失败：$key"
    }
  }
}
$activitySuffix = "910000000001:$activitySkuId"
docker exec $RedisContainer redis-cli -a root --no-auth-warning SET "seckill:stock:$activitySuffix" $activityStockLimit EX $activityTtl *> $null
docker exec $RedisContainer redis-cli -a root --no-auth-warning HSET "seckill:meta:$activitySuffix" perUserLimit 1 stockLimit $activityStockLimit startAt $activityStartIso endAt $activityEndIso *> $null
docker exec $RedisContainer redis-cli -a root --no-auth-warning EXPIRE "seckill:meta:$activitySuffix" $activityTtl *> $null
if ($LASTEXITCODE -ne 0) {
  throw '首页秒杀活动 Redis 状态初始化失败'
}

Write-Host '清理旧商品对应的 Redis 购物车...'
$cartKeys = docker exec $RedisContainer redis-cli -a root --no-auth-warning --scan --pattern 'cart:*'
if ($LASTEXITCODE -ne 0) {
  throw 'Redis 购物车扫描失败'
}
foreach ($cartKey in ($cartKeys -split '\r?\n' | Where-Object { $_ -ne '' })) {
  docker exec $RedisContainer redis-cli -a root --no-auth-warning DEL $cartKey *> $null
  if ($LASTEXITCODE -ne 0) {
    throw "Redis 购物车清理失败：$cartKey"
  }
}
Write-Host '商品数据迁移完成。'
