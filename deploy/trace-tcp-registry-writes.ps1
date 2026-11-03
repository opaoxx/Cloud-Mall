<#
.SYNOPSIS
Enable, inspect, read, or undo the reviewed MaxUserPort write audit.
.DESCRIPTION
Only Enable/Disable change audit settings. No registry values, TCP settings,
containers, services, log sizes, or process-creation policies are changed.
#>
[CmdletBinding()]
param(
    [ValidateSet('Enable', 'Status', 'Read', 'Disable')]
    [string]$Action = 'Status',
    [string]$StatePath
)

$ErrorActionPreference = 'Stop'
$auditGuid = '{0CCE921E-69AE-11D9-BED3-505054503030}'
$projectRoot = Split-Path -Parent $PSScriptRoot
$auditRoot = Join-Path $projectRoot 'target/tcp-registry-audit'

function Assert-AuditAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator PowerShell is required. No audit settings were changed.'
    }
}

function Get-RegistryAuditPolicy {
    if (!('CloudMallRegistryAudit.NativePolicy' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace CloudMallRegistryAudit {
    public static class NativePolicy {
        [StructLayout(LayoutKind.Sequential)]
        private struct PolicyInfo { public Guid Subcategory; public uint Flags; public Guid Category; }
        [DllImport("advapi32.dll", SetLastError=true)]
        private static extern bool AuditQuerySystemPolicy(ref Guid subcategory, uint count, out IntPtr policy);
        [DllImport("advapi32.dll")]
        private static extern void AuditFree(IntPtr buffer);
        public static int ReadRegistry() {
            Guid category = new Guid("0CCE921E-69AE-11D9-BED3-505054503030");
            IntPtr buffer;
            if (!AuditQuerySystemPolicy(ref category, 1, out buffer))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            try { return (int)(Marshal.PtrToStructure<PolicyInfo>(buffer).Flags & 3); }
            finally { AuditFree(buffer); }
        }
    }
}
'@
    }
    return [CloudMallRegistryAudit.NativePolicy]::ReadRegistry()
}

function New-TargetAuditRule {
    return [Security.AccessControl.RegistryAuditRule]::new(
        [Security.Principal.SecurityIdentifier]::new('S-1-1-0'),
        [Security.AccessControl.RegistryRights]::SetValue,
        [Security.AccessControl.InheritanceFlags]::None,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AuditFlags]::Success)
}

function Get-TargetSnapshot {
    param([string]$TargetPath)
    $security = Get-Acl -Path $TargetPath -Audit
    $registryKey = Get-Item -LiteralPath $TargetPath
    $present = $registryKey.GetValueNames() -contains 'MaxUserPort'
    $kind = $null
    $data = $null
    if ($present) {
        $kind = $registryKey.GetValueKind('MaxUserPort').ToString()
        $data = $registryKey.GetValue('MaxUserPort')
    }
    $stableSections = [Security.AccessControl.AccessControlSections]::Owner -bor
        [Security.AccessControl.AccessControlSections]::Group -bor
        [Security.AccessControl.AccessControlSections]::Access
    return [pscustomobject]@{
        AuditSddl = $security.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Audit)
        AuditRuleCount = @($security.GetAuditRules($true, $true, [Security.Principal.SecurityIdentifier])).Count
        StableSddl = $security.GetSecurityDescriptorSddlForm($stableSections)
        Value = [pscustomobject]@{ Present = $present; Kind = $kind; Data = $data }
    }
}

function New-AuditOnlySecurity {
    param([string]$AuditSddl)
    $security = [Security.AccessControl.RegistrySecurity]::new()
    if ([string]::IsNullOrEmpty($AuditSddl)) { $AuditSddl = 'S:' }
    $security.SetSecurityDescriptorSddlForm($AuditSddl, [Security.AccessControl.AccessControlSections]::Audit)
    return $security
}

function Assert-OwnedAuditRule {
    param([Security.AccessControl.RegistrySecurity]$Security)
    $rules = @($Security.GetAuditRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne 'S-1-1-0' -or
        $rules[0].RegistryRights -ne [Security.AccessControl.RegistryRights]::SetValue -or
        $rules[0].AuditFlags -ne [Security.AccessControl.AuditFlags]::Success -or
        $rules[0].InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
        $rules[0].PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or $rules[0].IsInherited) {
        throw 'Audit rule drift: expected only the owned Everyone / SetValue / Success / this-key rule.'
    }
}

function Set-RegistrySuccessAudit {
    param([bool]$Enabled)
    $setting = if ($Enabled) { '/success:enable' } else { '/success:disable' }
    $output = @(& auditpol.exe /set "/subcategory:$auditGuid" $setting)
    $commandExit = $LASTEXITCODE
    if ($commandExit -ne 0) { throw "auditpol failed (ExitCode=$commandExit): $($output -join ' ')" }
    return ($output -join "`n")
}

function Save-AuditState {
    param($State)
    $State | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $StatePath -Encoding UTF8
}

function Convert-TargetAuditEvent {
    param([string]$Xml, [string]$NativePath)
    $eventXml = [xml]$Xml
    $fields = @{}
    foreach ($field in $eventXml.Event.EventData.Data) { $fields[$field.GetAttribute('Name')] = $field.InnerText }
    $alias = '\REGISTRY\MACHINE\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters'
    if ($fields['ObjectValueName'] -ine 'MaxUserPort' -or
        ($fields['ObjectName'] -ine $NativePath -and $fields['ObjectName'] -ine $alias)) { return }
    $decimalPid = $null
    if ($fields['ProcessId'] -match '^0x[0-9a-f]+$') { $decimalPid = [Convert]::ToInt64($fields['ProcessId'].Substring(2), 16) }
    return [pscustomobject]@{
        RecordId = [string]$eventXml.Event.System.EventRecordID
        TimeUtc = [string]$eventXml.Event.System.TimeCreated.SystemTime
        ObjectName = $fields['ObjectName']; OperationType = $fields['OperationType']
        ProcessName = $fields['ProcessName']; ProcessIdRaw = $fields['ProcessId']; ProcessId = $decimalPid
        SubjectSid = $fields['SubjectUserSid']; OldType = $fields['OldValueType']; OldValue = $fields['OldValue']
        NewType = $fields['NewValueType']; NewValue = $fields['NewValue']; RawXml = $Xml
    }
}

# Dot-sourcing exposes pure helpers for in-memory checks without entering a mode.
if ($MyInvocation.InvocationName -eq '.') { return }

$state = $null
try {
    Assert-AuditAdministrator
    if (!$StatePath) { $StatePath = Join-Path $auditRoot 'state.json' }
    $StatePath = [IO.Path]::GetFullPath($StatePath)
    $allowedRoot = [IO.Path]::GetFullPath($auditRoot).TrimEnd('\') + '\'
    if (!$StatePath.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'StatePath must stay inside this project target/tcp-registry-audit directory.'
    }
    $currentControlSet = [int](Get-ItemProperty 'HKLM:\SYSTEM\Select' -Name Current).Current
    $targetPath = 'HKLM:\SYSTEM\ControlSet{0:D3}\Services\Tcpip\Parameters' -f $currentControlSet
    $nativePath = '\REGISTRY\MACHINE\SYSTEM\ControlSet{0:D3}\Services\Tcpip\Parameters' -f $currentControlSet
    $state = $null
    if (Test-Path -LiteralPath $StatePath) {
        $state = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        if ($state.Computer -ne $env:COMPUTERNAME -or $state.TargetPath -cnotmatch '^HKLM:\\SYSTEM\\ControlSet\d{3}\\Services\\Tcpip\\Parameters$') {
            throw 'State machine identity or target path is invalid.'
        }
        if ($state.CurrentControlSet -ne $currentControlSet) {
            throw 'Current ControlSet changed. Coverage is UNKNOWN; no automatic expansion or rollback.'
        }
        if ($state.TargetPath -ine $targetPath -or $state.NativePath -ine $nativePath) { throw 'State path identity mismatch.' }
    }
    if ($Action -eq 'Enable' -and $state) {
        if ($state.Phase -ne 'Enabled') { throw 'Prior state exists; review it instead of overwriting or reapplying.' }
        $Action = 'Status'
    }
    if ($Action -eq 'Enable') {
        $before = Get-TargetSnapshot $targetPath
        # The reviewed machine has no existing SACL. Refuse unknown rules rather than merging ownership.
        if ($before.AuditRuleCount -ne 0) { throw 'Pre-existing audit rules require review; nothing changed.' }
        $policy = Get-RegistryAuditPolicy
        $policyOutput = @(& auditpol.exe /get "/subcategory:$auditGuid" /r)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot back up auditpol output.' }
        $runDirectory = Join-Path $auditRoot ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
        $state = [pscustomobject]@{
            Version = 1; Computer = $env:COMPUTERNAME; Phase = 'BackedUp'; StartedAt = (Get-Date).ToString('o')
            ScriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
            CurrentControlSet = $currentControlSet; TargetPath = $targetPath; NativePath = $nativePath
            PolicyBefore = $policy; PolicyExpected = ($policy -bor 1); PolicyOutput = $policyOutput
            Before = $before; After = $null; Steps = @(); RunDirectory = $runDirectory
        }
        $backupPath = Join-Path $runDirectory 'before.json'
        $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $backupPath -Encoding UTF8
        $backup = Get-Content -Raw -LiteralPath $backupPath | ConvertFrom-Json
        if ($backup.Before.AuditSddl -cne $before.AuditSddl -or $backup.Before.StableSddl -cne $before.StableSddl -or
            $backup.PolicyBefore -ne $policy -or ($backup.Before.Value | ConvertTo-Json -Compress) -cne ($before.Value | ConvertTo-Json -Compress)) {
            throw 'Backup readback failed; no settings changed.'
        }
        $stateStream = [IO.File]::Open($StatePath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stateStream.Dispose()
        Save-AuditState $state
        $fresh = Get-TargetSnapshot $targetPath
        if (($fresh | ConvertTo-Json -Compress -Depth 6) -cne ($before | ConvertTo-Json -Compress -Depth 6) -or
            (Get-RegistryAuditPolicy) -ne $policy) { throw 'Settings drifted after backup; no mutation attempted.' }
        $auditOnly = New-AuditOnlySecurity $before.AuditSddl
        $auditOnly.AddAuditRule((New-TargetAuditRule))
        Assert-OwnedAuditRule $auditOnly
        Set-Acl -Path $targetPath -AclObject $auditOnly
        $state.Steps += 'Added this-key success SetValue SACL'
        Save-AuditState $state
        $state.Steps += (Set-RegistrySuccessAudit $true)
        Save-AuditState $state
        $after = Get-TargetSnapshot $targetPath
        Assert-OwnedAuditRule (Get-Acl -Path $targetPath -Audit)
        if ($after.StableSddl -cne $before.StableSddl -or (Get-RegistryAuditPolicy) -ne $state.PolicyExpected -or
            ($after.Value | ConvertTo-Json -Compress) -cne ($before.Value | ConvertTo-Json -Compress)) {
            throw 'Post-apply verification failed or concurrent drift occurred; preserve evidence.'
        }
        $state.After = $after
        $state.Phase = 'Enabled'
        Save-AuditState $state
        Write-Output "ENABLED: this-key successful SetValue audit; Read filters MaxUserPort. No test value written. State: $StatePath"
    } elseif ($Action -eq 'Disable') {
        if (!$state -or $state.Phase -ne 'Enabled') { throw 'A verified Enabled state is required for automatic removal.' }
        $live = Get-TargetSnapshot $targetPath
        if ($live.AuditSddl -cne $state.After.AuditSddl -or $live.StableSddl -cne $state.After.StableSddl -or
            (Get-RegistryAuditPolicy) -ne $state.PolicyExpected) { throw 'Audit/policy/permission drift: refusing automatic rollback.' }
        $auditOnly = New-AuditOnlySecurity $live.AuditSddl
        Assert-OwnedAuditRule $auditOnly
        $auditOnly.RemoveAuditRuleSpecific((New-TargetAuditRule))
        $state.Phase = 'Disabling'
        Save-AuditState $state
        Set-Acl -Path $targetPath -AclObject $auditOnly
        $state.Steps += 'Removed owned this-key SACL'
        Save-AuditState $state
        $state.Steps += (Set-RegistrySuccessAudit (($state.PolicyBefore -band 1) -ne 0))
        $restored = Get-TargetSnapshot $targetPath
        if ($restored.AuditRuleCount -ne 0 -or $restored.StableSddl -cne $live.StableSddl -or
            (Get-RegistryAuditPolicy) -ne $state.PolicyBefore -or
            ($restored.Value | ConvertTo-Json -Compress) -cne ($live.Value | ConvertTo-Json -Compress)) {
            throw 'Disable verification failed or concurrent drift occurred.'
        }
        $state.Phase = 'Disabled'
        Save-AuditState $state
        Write-Output 'DISABLED: owned audit removed; original success bit restored; target value untouched.'
    } elseif ($Action -eq 'Status') {
        if (!$state) { throw 'No saved audit state. Enable has not been applied by this tool.' }
        $live = Get-TargetSnapshot $targetPath
        $policy = Get-RegistryAuditPolicy
        $verified = $state.Phase -eq 'Enabled' -and $policy -eq $state.PolicyExpected -and
            $live.AuditSddl -ceq $state.After.AuditSddl -and $live.StableSddl -ceq $state.After.StableSddl
        [pscustomobject]@{ Phase = $state.Phase; ConfigurationVerified = $verified; PolicyMask = $policy; Target = $nativePath; Value = $live.Value; StatePath = $StatePath } | ConvertTo-Json -Depth 6
        if (!$verified) { throw 'Audit configuration not verified. No settings changed by Status.' }
    } else {
        if (!$state) { throw 'No saved audit start time; refusing an unbounded event query.' }
        $since = [DateTimeOffset]::Parse($state.StartedAt).LocalDateTime
        try { $events = @(Get-WinEvent -FilterHashtable @{LogName = 'Security'; Id = 4657; StartTime = $since} -MaxEvents 10000 -ErrorAction Stop) }
        catch { if ($_.FullyQualifiedErrorId -notmatch '^NoMatchingEventsFound') { throw }; $events = @() }
        $oldest = Get-WinEvent -LogName Security -Oldest -MaxEvents 1 -ErrorAction Stop
        $matches = @($events | ForEach-Object { Convert-TargetAuditEvent -Xml $_.ToXml() -NativePath $nativePath })
        $result = [pscustomobject]@{ Since = $state.StartedAt; OldestSecurityEvent = $oldest.TimeCreated.ToString('o'); RetentionGap = $oldest.TimeCreated -gt $since; Scanned = $events.Count; PossiblyTruncated = $events.Count -eq 10000; Matches = $matches }
        $evidenceFile = Join-Path $state.RunDirectory ('read-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')
        $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidenceFile -Encoding UTF8
        $result | ConvertTo-Json -Depth 8
        Write-Output "Read evidence: $evidenceFile"
    }
} catch {
    if ($state -and $state.RunDirectory -and (Test-Path -LiteralPath $state.RunDirectory)) {
        [pscustomobject]@{ Action = $Action; Error = $_.Exception.Message; State = $state; Time = (Get-Date).ToString('o') } |
            ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $state.RunDirectory ('failure-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')) -Encoding UTF8
    }
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
