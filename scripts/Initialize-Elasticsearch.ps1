#requires -Version 5.1
<#
.SYNOPSIS
Creates development-only TLS material and a password for the local Compose service.
.DESCRIPTION
Build the pinned Nori image first. Existing material is never overwritten.
Run Test-Elasticsearch.ps1 after startup to validate TLS, authentication and readiness.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$imageName = 'commerce-search-lab/elasticsearch-nori:9.4.7'
$localRoot = Join-Path $repositoryRoot '.local/elasticsearch'
$certDirectory = Join-Path $localRoot 'certs'
$privateDirectory = Join-Path $localRoot 'ca-private'
$passwordPath = Join-Path $localRoot 'elastic-password.txt'
$instancesPath = Join-Path $repositoryRoot 'infra/elasticsearch/instances.yml'
$runtimeFiles = @('ca.crt', 'es01.crt', 'es01.key') | ForEach-Object { Join-Path $certDirectory $_ }
$requiredFiles = @($runtimeFiles) + @($passwordPath)

Push-Location $repositoryRoot
try {
    foreach ($path in @($requiredFiles) + @((Join-Path $privateDirectory 'ca.zip'))) {
        & git -c "safe.directory=$repositoryRoot" check-ignore --quiet -- $path
        if ($LASTEXITCODE -ne 0) { throw 'Local credential paths must be ignored by Git before initialization.' }
    }
    $trackedLocalFiles = @(& git -c "safe.directory=$repositoryRoot" ls-files -- .local)
    if ($LASTEXITCODE -ne 0 -or $trackedLocalFiles.Count -ne 0) {
        throw 'Initialization requires an untracked .local directory; inspect Git tracking first.'
    }
    $existingFiles = @($requiredFiles | Where-Object { Test-Path -LiteralPath $_ })
    if ($existingFiles.Count -eq $requiredFiles.Count) {
        foreach ($path in $requiredFiles) {
            if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
                throw 'Existing local material is incomplete; it has been preserved for diagnosis.'
            }
        }
        Write-Output 'Existing local material preserved. Start the service and run Test-Elasticsearch.ps1 to validate it.'
        return
    }
    if ($existingFiles.Count -gt 0 -or (Test-Path -LiteralPath $certDirectory) -or (Test-Path -LiteralPath $privateDirectory)) {
        throw 'Partial local initialization exists. Preserve and inspect it before retrying; nothing was overwritten.'
    }

    if (-not (Test-Path -LiteralPath $instancesPath -PathType Leaf)) {
        throw 'The certificate input file is missing; no local material was created.'
    }
    & docker image inspect --format '{{.Id}}' $imageName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Build the pinned Nori image before initializing local material.' }
    [void] [IO.Directory]::CreateDirectory($certDirectory)
    [void] [IO.Directory]::CreateDirectory($privateDirectory)
    $workMount = "type=bind,source=$privateDirectory,target=/work"
    $instancesMount = "type=bind,source=$instancesPath,target=/input/instances.yml,readonly"

    & docker run --rm --network none --mount $workMount $imageName bin/elasticsearch-certutil ca --silent --pem --out /work/ca.zip
    if ($LASTEXITCODE -ne 0) { throw 'CA generation failed. Partial files were preserved for diagnosis.' }
    Expand-Archive -LiteralPath (Join-Path $privateDirectory 'ca.zip') -DestinationPath $privateDirectory

    & docker run --rm --network none --mount $workMount --mount $instancesMount $imageName bin/elasticsearch-certutil cert --silent --pem --in /input/instances.yml --ca-cert /work/ca/ca.crt --ca-key /work/ca/ca.key --out /work/es01.zip
    if ($LASTEXITCODE -ne 0) { throw 'Node certificate generation failed. Partial files were preserved for diagnosis.' }
    Expand-Archive -LiteralPath (Join-Path $privateDirectory 'es01.zip') -DestinationPath $privateDirectory
    [IO.File]::Copy((Join-Path $privateDirectory 'ca/ca.crt'), (Join-Path $certDirectory 'ca.crt'), $false)
    [IO.File]::Copy((Join-Path $privateDirectory 'es01/es01.crt'), (Join-Path $certDirectory 'es01.crt'), $false)
    [IO.File]::Copy((Join-Path $privateDirectory 'es01/es01.key'), (Join-Path $certDirectory 'es01.key'), $false)

    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    try { $random.GetBytes($bytes) }
    finally { $random.Dispose() }
    $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $passwordBytes = [Text.Encoding]::ASCII.GetBytes($password)
    $stream = [IO.File]::Open($passwordPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($passwordBytes, 0, $passwordBytes.Length) }
    finally {
        $stream.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
        $password = $null
    }
    Write-Output 'Local TLS material and password created in Git-ignored paths. No secret values were printed.'
}
finally { Pop-Location }
