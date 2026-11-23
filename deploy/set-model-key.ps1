$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path (Join-Path $root '.runtime') | Out-Null
$entered = Read-Host 'OpsAtlas DeepSeek API Key' -AsSecureString
if ($entered.Length -eq 0) { throw 'No model key entered.' }
$encrypted = ConvertFrom-SecureString -SecureString $entered
Set-Content -LiteralPath (Join-Path $root '.runtime\model-key.dpapi') -Value $encrypted -Encoding ascii
'OpsAtlas model key saved for this Windows account.'
