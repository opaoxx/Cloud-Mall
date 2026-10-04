param(
  [string]$ElasticsearchUrl = 'http://localhost:9200',
  [string]$IndexName = 'cloudmall_product'
)

$ErrorActionPreference = 'Stop'
if ($IndexName -notmatch '^[a-z0-9_-]+$') { throw 'Invalid product index name' }
$indexUrl = "$($ElasticsearchUrl.TrimEnd('/'))/$IndexName"
$mapping = Invoke-RestMethod -Uri "$indexUrl/_mapping" -Method Get
$properties = $mapping.$IndexName.mappings.properties
if ($null -eq $properties -or $properties.id.type -ne 'long' -or
    $properties.price.type -ne 'scaled_float' -or
    $properties.productVersion.type -ne 'long' -or
    $properties.visible.type -ne 'boolean') {
  throw 'Existing product index mapping is not compatible with the reviewed in-place migration'
}

$sample = Invoke-RestMethod -Uri "$indexUrl/_search?size=1&version=true" -Method Get
if ($sample.hits.hits.Count -lt 1 -or
    [long]$sample.hits.hits[0]._version -ne
        ([long]$sample.hits.hits[0]._source.productVersion + 1)) {
  throw 'Existing product index external version semantics could not be verified'
}

$fields = @{
  properties = @{
    updatedAt = @{ type = 'date'; format = 'epoch_millis' }
    viewCount = @{ type = 'long' }
    searchCount = @{ type = 'long' }
    hotScore = @{ type = 'scaled_float'; scaling_factor = 1000000 }
    metricSourceType = @{ type = 'keyword' }
    metricSourceRef = @{ type = 'keyword' }
  }
} | ConvertTo-Json -Depth 6 -Compress
$mappingResult = Invoke-RestMethod -Uri "$indexUrl/_mapping" -Method Put `
  -ContentType 'application/json' -Body $fields
if (-not $mappingResult.acknowledged) { throw 'Product index mapping update was not acknowledged' }
$settings = @{ index = @{ max_result_window = 100100 } } | ConvertTo-Json -Depth 4 -Compress
$settingsResult = Invoke-RestMethod -Uri "$indexUrl/_settings" -Method Put `
  -ContentType 'application/json' -Body $settings
if (-not $settingsResult.acknowledged) { throw 'Product index result window update was not acknowledged' }

$verified = Invoke-RestMethod -Uri "$indexUrl/_mapping" -Method Get
foreach ($field in @('updatedAt', 'viewCount', 'searchCount', 'hotScore',
    'metricSourceType', 'metricSourceRef')) {
  if ($null -eq $verified.$IndexName.mappings.properties.$field) {
    throw "Product index mapping is still missing $field"
  }
}
Write-Output "Product index $IndexName mapping and result window are ready for controlled backfill"
