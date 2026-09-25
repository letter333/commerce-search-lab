#requires -Version 5.1
<#
.SYNOPSIS
Checks the local Elasticsearch contract without changing the engine or its data.
.DESCRIPTION
Credentials are sent to curl through stdin, never through process arguments or a
temporary file. Raw API responses and curl diagnostics are deliberately omitted.
Yellow health needs a separate replica allocation diagnosis; this check accepts
green only. Run again after any diagnostic or configuration change.
#>
[CmdletBinding()]
param([switch] $AsJson)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:failureReason = 'Local Elasticsearch verification failed.'
$script:deadline = [Diagnostics.Stopwatch]::StartNew()

function Stop-Verification([string] $Reason) {
    $script:failureReason = $Reason
    throw $Reason
}

function Get-RemainingSeconds {
    return [Math]::Max(0, 180 - $script:deadline.Elapsed.TotalSeconds)
}

function Invoke-CapturedProcess {
    param([string] $Executable, [string] $Arguments, [string] $InputText, [double] $TimeoutSeconds)

    if ($TimeoutSeconds -le 0) { Stop-Verification 'The 180-second verification deadline was reached.' }
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.Arguments = $Arguments
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void] $process.Start()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit([int] [Math]::Ceiling($TimeoutSeconds * 1000))) {
            $process.Kill()
            Stop-Verification 'A local verification command exceeded its time limit.'
        }
        $output = $outputTask.GetAwaiter().GetResult()
        # Drain diagnostics without displaying credentials, headers or raw responses.
        [void] $errorTask.GetAwaiter().GetResult()
        return [pscustomobject] @{ ExitCode = $process.ExitCode; Output = $output }
    }
    finally { $process.Dispose() }
}

function ConvertTo-CurlValue([string] $Value) {
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Invoke-EngineRequest([string] $ApiPath, [bool] $Authenticated) {
    $remaining = Get-RemainingSeconds
    if ($remaining -le 0) { Stop-Verification 'The 180-second verification deadline was reached.' }
    $requestLimit = [Math]::Min(40, $remaining)
    $connectLimit = [Math]::Min(5, $requestLimit)
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $config = @(
        'silent'
        'proto = "=https"'
        'noproxy = "*"'
        ('cacert = ' + (ConvertTo-CurlValue $script:caPath))
        ('connect-timeout = ' + $connectLimit.ToString('0.000', $culture))
        ('max-time = ' + $requestLimit.ToString('0.000', $culture))
        'write-out = "\n%{http_code}"'
        ('url = ' + (ConvertTo-CurlValue ('https://localhost:9200' + $ApiPath)))
    )
    # The development CA has no revocation distribution points. Schannel may
    # otherwise reject it before HTTP; CA-chain and hostname checks remain on.
    if ($script:schannel) { $config += 'ssl-revoke-best-effort' }
    if ($Authenticated) { $config += 'user = ' + (ConvertTo-CurlValue ('elastic:' + $script:password)) }
    # -q must be first: a user's curlrc must not disable certificate checks.
    $response = Invoke-CapturedProcess $script:curlPath '-q --config -' (($config -join "`n") + "`n") $requestLimit
    if ($response.ExitCode -ne 0) {
        if ($response.ExitCode -in @(7, 28, 35, 52, 56)) {
            return [pscustomobject] @{ Retry = $true; Reason = ('Connection or handshake not ready (curl {0})' -f $response.ExitCode); Status = 0; Body = '' }
        }
        Stop-Verification ('curl failed (exit code {0}); check CA trust, hostname and TLS/network configuration.' -f $response.ExitCode)
    }
    $separator = $response.Output.LastIndexOf("`n")
    if ($separator -lt 0) { Stop-Verification 'curl did not return an HTTP status marker.' }
    $statusText = $response.Output.Substring($separator + 1).Trim()
    if ($statusText -notmatch '^\d{3}$') { Stop-Verification 'curl returned an invalid HTTP status marker.' }
    $status = [int] $statusText
    return [pscustomobject] @{
        Retry = ($status -in @(502, 503, 504))
        Reason = ('HTTP {0}' -f $status)
        Status = $status
        Body = $response.Output.Substring(0, $separator)
    }
}

function Read-AuthenticatedJson([string] $ApiPath) {
    $response = Invoke-EngineRequest $ApiPath $true
    if ($response.Retry) { return [pscustomobject] @{ Retry = $true; Data = $null; Reason = $response.Reason } }
    # A restarting node can accept TLS before its security index is recovered.
    # Require successful authentication within the same overall deadline.
    if ($response.Status -eq 401) { return [pscustomobject] @{ Retry = $true; Data = $null; Reason = 'Authentication has not succeeded (HTTP 401); check readiness and existing credentials' } }
    if ($response.Status -eq 403) { Stop-Verification 'Authenticated verification was forbidden (HTTP 403).' }
    if ($response.Status -ne 200) { Stop-Verification ('Authenticated verification returned HTTP {0}.' -f $response.Status) }
    try { $data = ConvertFrom-Json -InputObject $response.Body -ErrorAction Stop }
    catch { Stop-Verification 'Authenticated verification returned invalid JSON.' }
    if ($null -eq $data) { Stop-Verification 'Authenticated verification returned an empty JSON value.' }
    return [pscustomobject] @{ Retry = $false; Data = $data }
}

try {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $composePath = Join-Path $repositoryRoot 'compose.yaml'
    if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) { Stop-Verification 'compose.yaml is missing; local engine configuration has not been prepared.' }
    $script:failureReason = 'Docker Compose configuration could not be checked.'
    $dockerPath = (Get-Command docker.exe -CommandType Application -ErrorAction Stop).Source
    $composeArguments = 'compose --project-directory "{0}" -p commerce-search-lab -f "{1}" config --quiet' -f $repositoryRoot, $composePath
    $compose = Invoke-CapturedProcess $dockerPath $composeArguments '' ([Math]::Min(30, (Get-RemainingSeconds)))
    if ($compose.ExitCode -ne 0) { Stop-Verification ('Docker Compose configuration failed (exit code {0}); check local paths and configuration.' -f $compose.ExitCode) }

    $script:caPath = Join-Path $repositoryRoot '.local/elasticsearch/certs/ca.crt'
    $passwordPath = Join-Path $repositoryRoot '.local/elasticsearch/elastic-password.txt'
    if (-not (Test-Path -LiteralPath $script:caPath -PathType Leaf)) { Stop-Verification 'The local CA certificate is missing.' }
    if (-not (Test-Path -LiteralPath $passwordPath -PathType Leaf)) { Stop-Verification 'The local password file is missing.' }
    $script:failureReason = 'Local verification files could not be read.'
    $script:password = [IO.File]::ReadAllText($passwordPath).TrimEnd([char[]] "`r`n")
    if ([string]::IsNullOrEmpty($script:password) -or $script:password -match '[\x00-\x1f\x7f]') { Stop-Verification 'The local password file must contain a nonempty single-line value without control characters.' }
    $caHash = (Get-FileHash -LiteralPath $script:caPath -Algorithm SHA256).Hash
    $script:failureReason = 'curl.exe is required for TLS and authenticated verification.'
    $script:curlPath = (Get-Command curl.exe -CommandType Application -ErrorAction Stop).Source
    $curlVersion = Invoke-CapturedProcess $script:curlPath '-q --version' '' ([Math]::Min(10, (Get-RemainingSeconds)))
    if ($curlVersion.ExitCode -ne 0) { Stop-Verification 'curl TLS backend could not be identified.' }
    $script:schannel = $curlVersion.Output -match '\bSchannel\b'
    $lastState = 'The API has not become reachable'

    while ((Get-RemainingSeconds) -gt 0) {
        $script:failureReason = 'The engine response could not be validated against the local contract.'
        $anonymous = Invoke-EngineRequest '/' $false
        if ($anonymous.Retry) { $lastState = $anonymous.Reason }
        elseif ($anonymous.Status -ne 401) { Stop-Verification ('Unauthenticated HTTPS must return 401, but returned {0}.' -f $anonymous.Status) }
        else {
            $infoResult = Read-AuthenticatedJson '/?filter_path=cluster_name,cluster_uuid,version.number'
            if (-not $infoResult.Retry) {
                $info = $infoResult.Data
                if ($info.cluster_name -cne 'commerce-search-lab' -or $info.version.number -cne '9.4.7') { Stop-Verification 'The authenticated cluster name or Elasticsearch version does not match the fixed contract.' }
                if ($info.cluster_uuid -notmatch '^[A-Za-z0-9_-]{22}$') { Stop-Verification 'The engine has not returned a valid cluster UUID.' }
                $pluginsResult = Read-AuthenticatedJson '/_nodes/plugins?filter_path=_nodes.total,_nodes.successful,_nodes.failed,nodes.*.version,nodes.*.plugins.name,nodes.*.plugins.version'
                if (-not $pluginsResult.Retry) {
                    $plugins = $pluginsResult.Data
                    if ($plugins._nodes.total -ne 1 -or $plugins._nodes.successful -ne 1 -or $plugins._nodes.failed -ne 0) { Stop-Verification 'Nori verification requires exactly one successful node and no failed nodes.' }
                    $nodes = @($plugins.nodes.PSObject.Properties | ForEach-Object { $_.Value })
                    if ($nodes.Count -ne 1 -or $nodes[0].version -cne '9.4.7') { Stop-Verification 'The node count or node Elasticsearch version is invalid.' }
                    $nori = @($nodes[0].plugins | Where-Object { $_.name -ceq 'analysis-nori' })
                    if ($nori.Count -ne 1 -or $nori[0].version -cne '9.4.7') { Stop-Verification 'Exactly one analysis-nori plugin at version 9.4.7 is required.' }
                    $healthResult = Read-AuthenticatedJson '/_cluster/health?wait_for_status=yellow&timeout=30s&filter_path=cluster_name,status,timed_out,number_of_nodes'
                    if (-not $healthResult.Retry) {
                        $health = $healthResult.Data
                        if ($health.cluster_name -cne 'commerce-search-lab' -or $health.number_of_nodes -ne 1) { Stop-Verification 'Cluster health has an unexpected cluster name or node count.' }
                        if ($health.timed_out -isnot [bool]) { Stop-Verification 'Cluster health is missing a boolean timed_out value.' }
                        if ($health.status -notin @('green', 'yellow', 'red')) { Stop-Verification 'Cluster health returned an invalid status.' }
                        if (-not $health.timed_out -and $health.status -ceq 'green') {
                            $summary = [pscustomobject] @{
                                clusterUuid = $info.cluster_uuid
                                version = $info.version.number
                                nori = $nori[0].version
                                caSha256 = $caHash
                                health = $health.status
                            }
                            if ($AsJson) { $summary | ConvertTo-Json } else { $summary }
                            exit 0
                        }
                        if ($health.status -ceq 'yellow') { $lastState = 'Yellow health requires a separate replica-only allocation diagnosis; it is not automatically accepted' }
                        else { $lastState = 'Cluster health is red or timed out' }
                    }
                    else { $lastState = $healthResult.Reason }
                }
                else { $lastState = $pluginsResult.Reason }
            }
            else { $lastState = $infoResult.Reason }
        }
        $pauseMilliseconds = [int] [Math]::Min(2000, [Math]::Floor((Get-RemainingSeconds) * 1000))
        if ($pauseMilliseconds -gt 0) { Start-Sleep -Milliseconds $pauseMilliseconds }
    }
    Stop-Verification ('The 180-second verification deadline was reached. {0}.' -f $lastState)
}
catch {
    [Console]::Error.WriteLine($script:failureReason)
    exit 1
}
finally {
    $script:password = $null
    $script:deadline.Stop()
}
