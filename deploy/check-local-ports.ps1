<#
.SYNOPSIS
Read-only Windows checks for Compose TCP bindings and optional local service ports.
#>
[CmdletBinding()]
param(
    [string]$ComposeFile,
    [switch]$IncludeBusinessPorts,
    [switch]$ProbeProtocols
)

$ErrorActionPreference = 'Stop'
try {
    if (!$ComposeFile) { $ComposeFile = Join-Path $PSScriptRoot '../docker-compose.yml' }
    $resolvedCompose = (Resolve-Path -LiteralPath $ComposeFile).Path
    $configText = & docker compose -f $resolvedCompose config --format json
    if ($LASTEXITCODE -ne 0) { throw 'docker compose config failed.' }
    $config = ($configText -join "`n") | ConvertFrom-Json
    $bindings = @(
        foreach ($service in $config.services.PSObject.Properties) {
            foreach ($port in $service.Value.ports) {
                if ($port.protocol -eq 'tcp' -and $port.published) {
                    [pscustomobject]@{ Service = $service.Name; Port = [int]$port.published }
                }
            }
        }
    )
    if ($IncludeBusinessPorts) {
        $serviceNames = @('gateway', 'user', 'product', 'cart', 'order', 'stock', 'pay')
        $bindings += @(for ($index = 0; $index -lt $serviceNames.Count; $index++) {
            [pscustomobject]@{ Service = $serviceNames[$index]; Port = 8080 + $index }
        })
        $bindings += [pscustomobject]@{ Service = 'frontend'; Port = 5173 }
    }
    $excludedRanges = @(
        foreach ($family in @('ipv4', 'ipv6')) {
            $rangeText = & netsh interface $family show excludedportrange protocol=tcp
            if ($LASTEXITCODE -ne 0) { throw "Cannot query $family excluded ports." }
            foreach ($line in $rangeText) {
                if ($line -match '^\s*(\d+)\s+(\d+)\s*\*?\s*$') {
                    [pscustomobject]@{
                        Family = $family; First = [int]$Matches[1]; Last = [int]$Matches[2]
                    }
                }
            }
        }
    )
    $ownPorts = @{}
    $containerIds = @(& docker compose -f $resolvedCompose ps -q)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot query running Compose containers.' }
    if ($containerIds.Count -gt 0) {
        $inspectText = & docker inspect @containerIds
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect running Compose containers.' }
        foreach ($container in (($inspectText -join "`n") | ConvertFrom-Json)) {
            if (!$container.State.Running) { continue }
            foreach ($port in $container.NetworkSettings.Ports.PSObject.Properties) {
                if (!$port.Name.EndsWith('/tcp')) { continue }
                foreach ($hostBinding in $port.Value) {
                    $ownPorts[[int]$hostBinding.HostPort] = $container.Name.TrimStart('/')
                }
            }
        }
    }
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop)
    $conflicts = 0
    foreach ($binding in ($bindings | Sort-Object Port, Service)) {
        $portListeners = @($listeners | Where-Object LocalPort -eq $binding.Port)
        $ranges = @($excludedRanges | Where-Object {
            $binding.Port -ge $_.First -and $binding.Port -le $_.Last
        })
        foreach ($range in $ranges) {
            Write-Output "BLOCKED $($binding.Service) port=$($binding.Port) excluded=$($range.Family):$($range.First)-$($range.Last) (system reservation, not a listener PID)"
            $conflicts++
        }
        foreach ($listener in $portListeners) {
            $process = Get-Process -Id $listener.OwningProcess -ErrorAction Stop
            $owned = $ownPorts.ContainsKey($binding.Port) -and
                $process.ProcessName -in @('com.docker.backend', 'wslrelay')
            if ($owned) {
                Write-Output "OWN $($binding.Service) port=$($binding.Port) container=$($ownPorts[$binding.Port]) PID=$($process.Id) process=$($process.ProcessName)"
            } else {
                Write-Output "BLOCKED $($binding.Service) port=$($binding.Port) address=$($listener.LocalAddress) PID=$($process.Id) process=$($process.ProcessName)"
                $conflicts++
            }
        }
        if ($ranges.Count -eq 0 -and $portListeners.Count -eq 0) {
            Write-Output "AVAILABLE $($binding.Service) port=$($binding.Port)"
        }
    }
    if ($ProbeProtocols) {
        . (Join-Path $PSScriptRoot 'service-protocol.ps1')
        foreach ($binding in ($bindings | Where-Object Service -in @('mysql', 'redis') | Sort-Object Service, Port)) {
            foreach ($hostAddress in @('localhost', '127.0.0.1')) {
                $probe = Test-CloudMallServiceProtocol -Protocol $binding.Service -HostAddress $hostAddress -Port $binding.Port
                if ($probe.Reachable) {
                    Write-Output "PROTOCOL $($binding.Service) port=$($binding.Port) host=$hostAddress peer=$($probe.RemoteEndpoint) result=$($probe.Status)"
                } else {
                    Write-Output "UNREACHABLE $($binding.Service) port=$($binding.Port) host=$hostAddress reason=$($probe.Error)"
                    $conflicts++
                }
            }
        }
        Write-Output 'Protocol probes check MySQL greeting / Redis PING only; they do not validate authentication or business health.'
    }
    if ($conflicts -gt 0) { exit 1 }
    Write-Output 'TCP port checks passed. No processes or system settings were changed.'
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 2
}
