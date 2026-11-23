$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
. .\deploy\init-local.ps1
$base="http://127.0.0.1:$env:OPSATLAS_PORT"
$credentials=@{username='xuhui';password=$env:OPSATLAS_DEMO_PASSWORD}|ConvertTo-Json
$login=Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $credentials
$status=Invoke-RestMethod "$base/api/agent/status" -Headers @{Authorization="Bearer $($login.accessToken)"}
$artifact='docs/evidence/phase-6-live-model.json'
$result=[ordered]@{
    status='blocked'
    provider='DeepSeek'
    expectedModel='deepseek-flash'
    projectCredentialPresent=(-not [string]::IsNullOrWhiteSpace($env:OPSATLAS_MODEL_API_KEY))
    appModelConfigured=[bool]$status.modelConfigured
    browserTestStarted=$false
}
if(!$status.modelConfigured){
    $result.reason='Model is unconfigured in the running container.'
    $result | ConvertTo-Json | Set-Content -LiteralPath $artifact -Encoding utf8NoBOM
    throw 'OpsAtlas live model is unconfigured. Use deploy/set-model-key.ps1 and deploy/start.ps1 -SkipBuild.'
}
. .\deploy\wsl-docker.ps1
$compose=@('compose','-p','opsatlas','--env-file','.runtime/local.env','-f','deploy/compose.yaml')
$target=Invoke-OpsAtlasDocker -Arguments ($compose+@('exec','-T','app','sh','-c',
    'test "$OPSATLAS_MODEL_URL" = https://api.deepseek.com && test "$OPSATLAS_MODEL" = deepseek-flash && echo matched'))
if ($target -notcontains 'matched') { throw 'The running model is not the DeepSeek acceptance target.' }
$result.browserTestStarted=$true
$clock=[System.Diagnostics.Stopwatch]::StartNew()
node tests/e2e/browser.cjs $base live-model
$browserExit=$LASTEXITCODE
$result.browserElapsedMs=$clock.ElapsedMilliseconds
if($browserExit){
    $result.status='failed'
    $result.reason='Live browser and model workflow did not complete.'
    $result | ConvertTo-Json | Set-Content -LiteralPath $artifact -Encoding utf8NoBOM
    throw 'Live browser and model workflow failed.'
}
$proof=Get-Content -LiteralPath docs/evidence/live-model/browser.json -Raw|ConvertFrom-Json
if($proof.analysisMode -ne 'model' -or !$proof.citations.Count){throw 'No actual model and citation evidence.'}
$result.status='passed'
$result.reportId=$proof.reportId
$result.steps=$proof.steps
$result.citations=$proof.citations
$result | ConvertTo-Json | Tee-Object -FilePath $artifact
