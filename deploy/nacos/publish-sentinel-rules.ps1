param(
  [string]$Server = 'http://localhost:18848',
  [string]$Username = 'root',
  [string]$Password = 'root',
  [string]$Group = 'CLOUDMALL_DEV',
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
$group = $Group
$rulesDirectory = Join-Path $PSScriptRoot 'sentinel'
$dataIds = @(
  'cloudmall-gateway-flow-rules',
  'cloudmall-order-flow-rules',
  'cloudmall-order-param-flow-rules',
  'cloudmall-order-degrade-rules',
  'cloudmall-stock-flow-rules'
)

$login = Invoke-RestMethod -Method Post -Uri "$Server/nacos/v1/auth/login" -Body @{
  username = $Username
  password = $Password
}
if ([string]::IsNullOrWhiteSpace($login.accessToken)) {
  throw 'Nacos did not return an access token.'
}

foreach ($dataId in $dataIds) {
  $file = Join-Path $rulesDirectory "$dataId.json"
  $content = Get-Content -LiteralPath $file -Raw -Encoding UTF8
  $parsed = ConvertFrom-Json -InputObject $content
  if ($null -eq $parsed -or @($parsed).Count -eq 0) {
    throw "Sentinel rules are empty: $file"
  }

  $query = [uri]::EscapeDataString($dataId)
  $groupQuery = [uri]::EscapeDataString($group)
  $tokenQuery = [uri]::EscapeDataString($login.accessToken)
  $existing = Invoke-WebRequest -Method Get -SkipHttpErrorCheck -Uri "$Server/nacos/v1/cs/configs?dataId=$query&group=$groupQuery&accessToken=$tokenQuery"
  if ($existing.StatusCode -eq 200 -and -not $Force) {
    Write-Output "Skipped existing Nacos rule: $dataId/$group"
    continue
  }
  if ($existing.StatusCode -notin @(200, 404)) {
    throw "Could not check Nacos rule $dataId (HTTP $($existing.StatusCode))."
  }

  $published = Invoke-WebRequest -Method Post -SkipHttpErrorCheck -Uri "$Server/nacos/v1/cs/configs" -Body @{
    dataId = $dataId
    group = $group
    content = $content
    type = 'json'
    accessToken = $login.accessToken
  }
  if ($published.StatusCode -ne 200 -or $published.Content.Trim() -ne 'true') {
    throw "Could not publish Nacos rule $dataId (HTTP $($published.StatusCode))."
  }
  Write-Output "Published Nacos rule: $dataId/$group"
}
