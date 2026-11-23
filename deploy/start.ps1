param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. .\deploy\init-local.ps1
. .\deploy\wsl-docker.ps1
$compose=@('compose','-p','opsatlas','--env-file','.runtime/local.env','-f','deploy/compose.yaml')
$inherited=$env:WSLENV
$names=@('OPSATLAS_MODEL_API_KEY','OPSATLAS_MODEL','OPSATLAS_MODEL_URL','OPSATLAS_MODEL_TIMEOUT',
         'OPSATLAS_EMBEDDING_API_KEY','OPSATLAS_EMBEDDING_URL','OPSATLAS_EMBEDDING_MODEL','OPSATLAS_EMBEDDING_DIMENSIONS')
$env:WSLENV=(@($inherited -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -notin $names })+$names) -join ':'
try {
    Invoke-OpsAtlasDocker -Arguments ($compose+@('up','-d','--wait','db','qdrant'))
    if (!$SkipBuild) { & .\deploy\build.ps1 }
    Invoke-OpsAtlasDocker -Arguments ($compose+@('up','-d','--build','--wait','--wait-timeout','120','app'))
    Invoke-OpsAtlasDocker -Arguments ($compose+@('ps'))
} finally {
    $env:WSLENV=$inherited
}
