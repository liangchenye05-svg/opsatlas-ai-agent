param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]*$')]
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [switch]$LiveModel,
    [switch]$VerifyRestart
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
. .\deploy\init-local.ps1
. .\deploy\wsl-docker.ps1

$folder = "docs/evidence/resume-quant/$RunId"
if (Test-Path -LiteralPath $folder) { throw "Evidence folder already exists. Choose another RunId." }
New-Item -ItemType Directory -Path $folder | Out-Null
$compose=@('compose','-p','opsatlas','--env-file','.runtime/local.env','-f','deploy/compose.yaml')
$base="http://127.0.0.1:$env:OPSATLAS_PORT"
Invoke-OpsAtlasDocker -Arguments ($compose+@('ps','--format','json')) |
    Set-Content "$folder/compose-before.jsonl" -Encoding utf8NoBOM

$sql=Get-Content -LiteralPath tests/integration/resume_eval.sql -Raw
$rows=Invoke-OpsAtlasDocker -Arguments ($compose+@('exec','-T','db','sh','-c',
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw --skip-column-names -e "$1"','resume-eval',$sql))
$rows | Where-Object {$_ -match '^(dataset|migration|total)\t'} |
    Set-Content "$folder/sql-baseline.tsv" -Encoding utf8NoBOM

$target=Invoke-OpsAtlasDocker -Arguments ($compose+@('exec','-T','app','sh','-c',
    'if [ "$OPSATLAS_MODEL_URL" = https://api.deepseek.com ]; then echo provider=official_deepseek; else echo provider=other; fi; printf "model=%s\nmodel_timeout=%s\n" "$OPSATLAS_MODEL" "$OPSATLAS_MODEL_TIMEOUT"'))
$target | Set-Content "$folder/model-config.txt" -Encoding utf8NoBOM
if($LiveModel -and ($target -notcontains 'provider=official_deepseek' -or $target -notcontains 'model=deepseek-flash')){
    throw 'Running app is not using the expected official DeepSeek Flash configuration.'
}
$runtime=[ordered]@{host = (Get-CimInstance Win32_OperatingSystem).Caption;
    docker = (Invoke-OpsAtlasDocker -Arguments @('info','--format','{{.ServerVersion}} {{.OSType}}'));
    compose = (Invoke-OpsAtlasDocker -Arguments @('compose','version','--short'));
    java = (Invoke-OpsAtlasDocker -Arguments ($compose+@('exec','-T','app','java','-version')));
    python = (python --version); contract = 'opsatlas resume evaluation v1'}
$runtime|ConvertTo-Json | Set-Content "$folder/runtime.json" -Encoding utf8NoBOM

$pythonArgs=@('tests/integration/resume_eval.py','measure',$base,$folder)
if($LiveModel){$pythonArgs+=@('--live')}
python @pythonArgs
if($LASTEXITCODE){throw 'Measurement failed. Partial evidence is kept.'}

if($VerifyRestart){
    python tests/integration/resume_eval.py before-restart $base $folder
    if($LASTEXITCODE){throw 'Before-restart checks failed.'}
    Invoke-OpsAtlasDocker -Arguments ($compose+@('restart','db','qdrant'))
    Invoke-OpsAtlasDocker -Arguments ($compose+@('up','-d','--wait','db','qdrant'))
    python tests/integration/resume_eval.py storage-restart $base $folder
    if($LASTEXITCODE){throw 'Storage restart checks failed.'}
    Invoke-OpsAtlasDocker -Arguments ($compose+@('restart','app'))
    python tests/integration/resume_eval.py after-restart $base $folder
    if($LASTEXITCODE){throw 'After-restart checks failed.'}
    Invoke-OpsAtlasDocker -Arguments ($compose+@('ps','--format','json')) |
        Set-Content "$folder/compose-after.jsonl" -Encoding utf8NoBOM
}
"Evidence in $folder"
