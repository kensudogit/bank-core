<#
.SYNOPSIS
  JP1/AJS などのジョブから bank-core のバッチジョブを起動し、その結果を終了コードで返す（Windows 用）。

.DESCRIPTION
  POST /api/batch/jobs/{Job} を呼び、応答の exitCode をこのスクリプトの終了コードにする。
    0  正常（照合合格。締めは承認待ちまたは完了）
    1  承認待ち（closing-status のみ。一定間隔で再判定する）
    4  照合不一致・差戻し（要修復）
    8  ステップの異常終了（再実行で続きから再開できる）／ API に接続できない
   12  起動条件の誤り（不明なジョブ名など）

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File run-job.ps1 -Job daily-closing -Operator JP1
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('daily-closing', 'closing-status', 'reconciliation')]
    [string] $Job,
    [string] $Operator = 'JP1',
    [string] $BaseUrl = $(if ($env:BANKCORE_URL) { $env:BANKCORE_URL } else { 'http://localhost:18080' }),
    [int] $TimeoutSec = 3600
)

$uri = "$BaseUrl/api/batch/jobs/$Job?operator=$([uri]::EscapeDataString($Operator))"
try {
    $result = Invoke-RestMethod -Method Post -Uri $uri -TimeoutSec $TimeoutSec
} catch {
    Write-Error "bank-core job '$Job' could not be started: $($_.Exception.Message)"
    exit 8
}
Write-Output ("job={0} exitCode={1} status={2} message={3}" -f $result.job, $result.exitCode, $result.status, $result.message)
exit [int] $result.exitCode
