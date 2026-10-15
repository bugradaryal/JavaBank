# Windows (PowerShell) için çalıştırıcı. Örnek:
#   .\run.ps1 -Scenario steady -Rate 50 -Duration 2m
param(
    [ValidateSet('smoke','steady','ramp','spike','hot','idempotency')] [string]$Scenario = 'steady',
    [int]$Rate = 50,
    [string]$Duration = '2m',
    [int]$Users = 100
)
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Write-Error "k6 bulunamadı. Kurulum: winget install k6  (veya choco install k6)"
    exit 1
}
New-Item -ItemType Directory -Force -Path results | Out-Null
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
k6 run `
    -e SCENARIO=$Scenario -e RATE=$Rate -e DURATION=$Duration -e USERS=$Users `
    --summary-export "results/$Scenario-$ts.json" `
    transfers.js
