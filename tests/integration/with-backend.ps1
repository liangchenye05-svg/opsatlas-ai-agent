param([Parameter(Mandatory)] [string]$Name, [Parameter(Mandatory)] [scriptblock]$Run)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
. .\deploy\init-local.ps1
$logs = Join-Path $root 'logs-samples'
$server = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList '-jar','backend/target/opsatlas-0.1.0.jar' -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$logs\$Name.out.log" -RedirectStandardError "$logs\$Name.err.log" -PassThru
try {
    $base = "http://127.0.0.1:$env:OPSATLAS_PORT"
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        if ($server.HasExited) { throw "Backend exited; see logs-samples/$Name.out.log." }
        curl.exe --noproxy '*' -fs --max-time 2 "$base/api/health" | Out-Null
        if (!$LASTEXITCODE) { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw "Backend health timed out; see logs-samples/$Name.out.log." }
    & $Run $base
} finally {
    if (!$server.HasExited) { Stop-Process -Id $server.Id }
}
