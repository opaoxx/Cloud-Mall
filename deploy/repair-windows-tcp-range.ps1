<#
.SYNOPSIS
Audit Windows TCP port ranges; explicitly restore defaults with -Apply as administrator.
.DESCRIPTION
Does not restart Windows, Docker or WSL, delete exclusions, or change project ports.
Apply requires the reviewed MaxUserPort value (DWORD 15000), or its absence on a rerun.
#>
[CmdletBinding()]
param([switch]$Apply)

$ErrorActionPreference = 'Stop'
$registryPath = 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters'
$registryKey = Get-Item -LiteralPath $registryPath
$hasMaxUserPort = $registryKey.GetValueNames() -contains 'MaxUserPort'
$originalRegistry = [ordered]@{
    Path = $registryPath
    Name = 'MaxUserPort'
    Present = $hasMaxUserPort
    Kind = $null
    Value = $null
}
if ($hasMaxUserPort) {
    $originalRegistry.Kind = $registryKey.GetValueKind('MaxUserPort').ToString()
    $originalRegistry.Value = $registryKey.GetValue('MaxUserPort')
}

function Invoke-NetshChecked {
    param([string[]]$Arguments)
    $commandOutput = @(& netsh @Arguments)
    $commandExitCode = $LASTEXITCODE
    if ($commandExitCode -ne 0) {
        throw "netsh $($Arguments -join ' ') failed (ExitCode=$commandExitCode): $($commandOutput -join ' ')"
    }
    return ($commandOutput -join "`n")
}

$beforeSnapshot = [ordered]@{
    CapturedAt = (Get-Date).ToString('o')
    Registry = $originalRegistry
}
foreach ($family in @('ipv4', 'ipv6')) {
    $beforeSnapshot["$family-TCP"] = Invoke-NetshChecked -Arguments @('interface', $family, 'show', 'dynamicport', 'tcp')
    $beforeSnapshot["$family-UDP"] = Invoke-NetshChecked -Arguments @('interface', $family, 'show', 'dynamicport', 'udp')
    $beforeSnapshot["$family-excluded"] = Invoke-NetshChecked -Arguments @('interface', $family, 'show', 'excludedportrange', 'protocol=tcp')
    $beforeSnapshot["$family-read-exit-codes"] = @(0, 0, 0)
    Write-Output "$family TCP dynamic range:"
    Write-Output $beforeSnapshot["$family-TCP"]
}
Write-Output "MaxUserPort present=$hasMaxUserPort type=$($originalRegistry.Kind) value=$($originalRegistry.Value)"
if (!$Apply) {
    Write-Output 'Audit only. No files or system settings changed.'
    Write-Output 'To apply: save your work, exit Docker Desktop normally, then run this script with -Apply in Administrator PowerShell.'
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Administrator PowerShell is required. No system settings or backup files were changed.'
}
if ($hasMaxUserPort -and ($originalRegistry.Kind -ne 'DWord' -or $originalRegistry.Value -ne 15000)) {
    throw 'MaxUserPort differs from reviewed DWORD 15000. Stop and review the new value; no changes made.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$backupDirectory = Join-Path $projectRoot ("target/docker-tcp-repair-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backupDirectory | Out-Null
$beforeSnapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $backupDirectory 'before.json') -Encoding UTF8
# Verify the backup is readable before the first system mutation.
$backupReadback = Get-Content -Raw -LiteralPath (Join-Path $backupDirectory 'before.json') | ConvertFrom-Json
if ($backupReadback.Registry.Present -ne $hasMaxUserPort -or
    $backupReadback.Registry.Value -ne $originalRegistry.Value -or
    $backupReadback.Registry.Kind -ne $originalRegistry.Kind) {
    throw "Backup verification failed: $backupDirectory. No system settings changed."
}
Write-Output "Backup: $backupDirectory"

$completedSteps = @()
try {
    $currentRegistryKey = Get-Item -LiteralPath $registryPath
    $currentHasMaxUserPort = $currentRegistryKey.GetValueNames() -contains 'MaxUserPort'
    if ($currentHasMaxUserPort -ne $hasMaxUserPort -or
        ($currentHasMaxUserPort -and
            ($currentRegistryKey.GetValueKind('MaxUserPort').ToString() -ne $originalRegistry.Kind -or
                $currentRegistryKey.GetValue('MaxUserPort') -ne $originalRegistry.Value))) {
        throw 'MaxUserPort changed after backup. No system mutation attempted; review the concurrent writer.'
    }
    if ($hasMaxUserPort) {
        Remove-ItemProperty -LiteralPath $registryPath -Name MaxUserPort
        $completedSteps += @{ Step = 'Removed reviewed MaxUserPort DWORD 15000 only'; Succeeded = $true }
    }
    foreach ($family in @('ipv4', 'ipv6')) {
        $result = Invoke-NetshChecked -Arguments @('interface', $family, 'set', 'dynamicportrange', 'protocol=tcp', 'startport=49152', 'numberofports=16384', 'store=persistent')
        $completedSteps += @{ Step = "$family TCP persistent range set to 49152/16384"; ExitCode = 0; Output = $result }
        Write-Output $result
    }
    $afterSnapshot = [ordered]@{ CompletedSteps = $completedSteps }
    foreach ($family in @('ipv4', 'ipv6')) {
        $rangeOutput = Invoke-NetshChecked -Arguments @('interface', $family, 'show', 'dynamicport', 'tcp')
        $afterSnapshot[$family] = $rangeOutput
        Write-Output $rangeOutput
        $rangeNumbers = @([regex]::Matches($rangeOutput, '(?m):\s*(\d+)\s*$') | ForEach-Object { [int]$_.Groups[1].Value })
        if ($rangeNumbers.Count -ne 2 -or $rangeNumbers[0] -ne 49152 -or $rangeNumbers[1] -ne 16384) {
            throw "$family active range did not verify as 49152/16384. Review backup and actual output."
        }
        $udpOutput = Invoke-NetshChecked -Arguments @('interface', $family, 'show', 'dynamicport', 'udp')
        $afterSnapshot["$family-UDP"] = $udpOutput
        if ($udpOutput -ne $beforeSnapshot["$family-UDP"]) {
            throw "$family UDP range drifted during application. Review concurrent settings changes."
        }
    }
    if ((Get-Item -LiteralPath $registryPath).GetValueNames() -contains 'MaxUserPort') {
        throw 'MaxUserPort is present again. Investigate the writer before claiming recovery.'
    }
    $afterSnapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $backupDirectory 'after.json') -Encoding UTF8
} catch {
    [ordered]@{ CompletedSteps = $completedSteps; Error = $_.Exception.Message } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $backupDirectory 'failure.json') -Encoding UTF8
    throw "Repair did not complete; partial changes may exist. Evidence: $backupDirectory. $($_.Exception.Message)"
}
Write-Output 'Configuration applied. Existing exclusions and Docker recovery are NOT verified.'
Write-Output 'Restart Windows manually after saving your work; then start Docker Desktop normally.'
Write-Output 'After reboot, rerun this audit and deploy/check-local-ports.ps1 -IncludeBusinessPorts -ProbeProtocols, then docker compose start.'
