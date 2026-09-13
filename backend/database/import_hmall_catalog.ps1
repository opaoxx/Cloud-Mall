param(
  [string]$SourceSql = 'F:\hmall\sql\hm-item.sql',
  [string]$Container = 'cloud-mall-mysql'
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
Write-Host '商品数据迁移完成。'
