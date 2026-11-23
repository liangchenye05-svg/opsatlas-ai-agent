function Invoke-OpsAtlasDocker {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $env:WSL_UTF8 = '1'
    $wsl = Join-Path $env:WINDIR 'System32\wsl.exe'
    & $wsl -d CampusTrade-Engine -u root --cd /mnt/d/DEMO/OpsAtlas --exec /usr/bin/docker @Arguments 2>&1 | ForEach-Object {
        $line = [string]$_
        if ($line -notmatch '^\s*wsl:.*localhost.*NAT') {
            foreach ($secret in @($env:OPSATLAS_MODEL_API_KEY,$env:OPSATLAS_EMBEDDING_API_KEY,
                                  $env:OPSATLAS_DB_PASSWORD,$env:OPSATLAS_DB_ROOT_PASSWORD,
                                  $env:OPSATLAS_JWT_SECRET,$env:OPSATLAS_DEMO_PASSWORD)) {
                if ($secret) { $line = $line.Replace($secret,'[redacted]') }
            }
            $line
        }
    }
    if ($LASTEXITCODE) { throw "OpsAtlas Docker command exited $LASTEXITCODE. See stderr above." }
}
