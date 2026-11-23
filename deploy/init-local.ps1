$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $root '.runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$localTemp = Join-Path $runtime 'tmp'
New-Item -ItemType Directory -Force -Path $localTemp | Out-Null
$env:TEMP = $localTemp
$env:TMP = $localTemp
$config = Join-Path $runtime 'local.env'
if (!(Test-Path -LiteralPath $config)) {
    $entries = @(
        "OPSATLAS_DB_ROOT_PASSWORD=$([Guid]::NewGuid().ToString('N'))",
        "OPSATLAS_DB_PASSWORD=$([Guid]::NewGuid().ToString('N'))",
        "OPSATLAS_JWT_SECRET=$([Guid]::NewGuid().ToString('N'))$([Guid]::NewGuid().ToString('N'))",
        "OPSATLAS_DEMO_PASSWORD=$([Guid]::NewGuid().ToString('N'))",
        'OPSATLAS_DB_PORT=13392',
        'OPSATLAS_PORT=8092'
    )
    [IO.File]::WriteAllLines($config, $entries, [Text.UTF8Encoding]::new($false))
}
foreach ($entry in Get-Content -LiteralPath $config) {
    if ($entry -match '^(OPSATLAS_[A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
    }
}
$keyFile = Join-Path $runtime 'model-key.dpapi'
if (!$env:OPSATLAS_MODEL_API_KEY -and (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
    $secure = (Get-Content -LiteralPath $keyFile -Raw).Trim() | ConvertTo-SecureString
    $env:OPSATLAS_MODEL_API_KEY = [System.Net.NetworkCredential]::new('', $secure).Password
}
