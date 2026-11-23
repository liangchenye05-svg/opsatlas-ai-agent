$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'deploy/init-local.ps1')
Invoke-RestMethod "http://127.0.0.1:$env:OPSATLAS_PORT/api/health"
