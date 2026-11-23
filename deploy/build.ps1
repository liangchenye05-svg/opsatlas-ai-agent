$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
. .\deploy\init-local.ps1
New-Item -ItemType Directory -Force -Path .runtime/m2,.runtime/tmp,docs/evidence,logs-samples | Out-Null
$repo = (Resolve-Path .runtime/m2).Path
$env:TEMP = (Resolve-Path .runtime/tmp).Path
$env:TMP = $env:TEMP
mvn -B -ntp -f backend/pom.xml "-Dmaven.repo.local=$repo" package
if ($LASTEXITCODE) { throw 'OpsAtlas Maven build or tests failed.' }
pnpm --dir frontend install --frozen-lockfile --registry https://registry.npmjs.org --store-dir ../.runtime/pnpm-store --cache-dir ../.runtime/pnpm-cache
if ($LASTEXITCODE) { throw 'OpsAtlas frontend dependency install failed.' }
pnpm --dir frontend build
if ($LASTEXITCODE) { throw 'OpsAtlas frontend build failed.' }
