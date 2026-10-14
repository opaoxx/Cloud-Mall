<#
.SYNOPSIS
Read-only, bounded MySQL greeting and Redis PING probes for local diagnostics.
#>

function Test-CloudMallServiceProtocol {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('mysql', 'redis')][string]$Protocol,
        [Parameter(Mandatory)][string]$HostAddress,
        [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$Port,
        [ValidateRange(100, 10000)][int]$TimeoutMilliseconds = 2000
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $remoteEndpoint = $null
    $stream = $null
    try {
        if (!$client.ConnectAsync($HostAddress, $Port).Wait($TimeoutMilliseconds)) {
            throw [System.TimeoutException]::new('Connect deadline exceeded.')
        }
        $remoteEndpoint = $client.Client.RemoteEndPoint.ToString()
        $stream = $client.GetStream()

        if ($Protocol -eq 'mysql') {
            # Header and protocol byte can arrive in separate TCP reads.
            $header = [byte[]]::new(5)
            $offset = 0
            while ($offset -lt $header.Length) {
                $remaining = $TimeoutMilliseconds - [int]$timer.ElapsedMilliseconds
                if ($remaining -le 0) { throw [System.TimeoutException]::new('Read deadline exceeded.') }
                $stream.ReadTimeout = $remaining
                $received = $stream.Read($header, $offset, $header.Length - $offset)
                if ($received -eq 0) { throw [System.IO.EndOfStreamException]::new('EOF before MySQL greeting.') }
                $offset += $received
            }
            $payloadLength = [int]$header[0] + ([int]$header[1] -shl 8) + ([int]$header[2] -shl 16)
            if ($payloadLength -lt 1 -or $header[3] -ne 0 -or $header[4] -ne 10) {
                throw [System.IO.InvalidDataException]::new('Invalid MySQL greeting header.')
            }
            $status = 'MYSQL_GREETING'
        } else {
            # PING has no credentials and changes no Redis key. NOAUTH proves
            # protocol reachability only; authentication is a separate check.
            $request = [System.Text.Encoding]::ASCII.GetBytes("*1`r`n" + '$4' + "`r`nPING`r`n")
            $remaining = $TimeoutMilliseconds - [int]$timer.ElapsedMilliseconds
            if ($remaining -le 0) { throw [System.TimeoutException]::new('Write deadline exceeded.') }
            $stream.WriteTimeout = $remaining
            $stream.Write($request, 0, $request.Length)
            $reply = [System.Collections.Generic.List[byte]]::new()
            $singleByte = [byte[]]::new(1)
            while ($reply.Count -lt 256) {
                $remaining = $TimeoutMilliseconds - [int]$timer.ElapsedMilliseconds
                if ($remaining -le 0) { throw [System.TimeoutException]::new('Read deadline exceeded.') }
                $stream.ReadTimeout = $remaining
                if ($stream.Read($singleByte, 0, 1) -eq 0) {
                    throw [System.IO.EndOfStreamException]::new('EOF before Redis reply.')
                }
                $reply.Add($singleByte[0])
                if ($singleByte[0] -eq 10) { break }
            }
            $response = [System.Text.Encoding]::ASCII.GetString($reply.ToArray())
            if ($response -ceq "+PONG`r`n") { $status = 'PONG' }
            elseif ($response.StartsWith('-NOAUTH ', [System.StringComparison]::Ordinal) -and $response.EndsWith("`r`n")) {
                $status = 'NOAUTH'
            } else { throw [System.IO.InvalidDataException]::new('Invalid Redis PING reply.') }
        }
        [pscustomobject]@{
            Reachable = $true; Protocol = $Protocol; HostAddress = $HostAddress
            Port = $Port; RemoteEndpoint = $remoteEndpoint; Status = $status; Error = $null
        }
    } catch {
        [pscustomobject]@{
            Reachable = $false; Protocol = $Protocol; HostAddress = $HostAddress
            Port = $Port; RemoteEndpoint = $remoteEndpoint; Status = 'FAILED'
            Error = $_.Exception.GetBaseException().GetType().Name
        }
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        $client.Dispose()
        $timer.Stop()
    }
}
