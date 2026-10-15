# Kaos testi (Windows PowerShell): yük altındayken bir servisi/altyapıyı çökertir, sonra k6'nın otomatik doğrulamasını bekler.
# Ön koşul:  docker compose --profile apps up -d --build   (servisler container olarak çalışmalı)
# Örnek:     .\chaos.ps1 -Fault kill-account
param(
    [Parameter(Mandatory)]
    [ValidateSet('kill-account','kill-transfer','kill-notification','kill-all','rolling','pause-kafka','stop-kafka','restart-postgres')]
    [string]$Fault,
    [int]$Rate = 30,
    [string]$Duration = '3m',
    [int]$FaultAt = 75,        # k6 başladıktan kaç sn sonra (kurulum ~30-40 sn sürer, yük sonra başlar)
    [int]$DownSeconds = 20,    # kesinti süresi
    [int]$Users = 100
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
function Log($m) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $m) -ForegroundColor Cyan }

function Wait-Healthy($port, $name) {
    $t0 = Get-Date
    while ($true) {
        try { if ((Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 2).status -eq 'UP') { break } } catch {}
        Start-Sleep -Seconds 1
    }
    Log ("{0} yeniden sağlıklı (başlatmadan {1} sn sonra)" -f $name, [int]((Get-Date) - $t0).TotalSeconds)
}

$services = @{
    'account'      = @{ container = 'bank-account-service';      port = 8081 }
    'transfer'     = @{ container = 'bank-transfer-service';     port = 8082 }
    'notification' = @{ container = 'bank-notification-service'; port = 8083 }
}

function Kill-Service($key) { Log "SIGKILL -> $($services[$key].container)"; docker kill $services[$key].container | Out-Null }
function Start-Service($key) { Log "başlatılıyor -> $($services[$key].container)"; docker start $services[$key].container | Out-Null; Wait-Healthy $services[$key].port $key }

foreach ($c in @('bank-account-service','bank-transfer-service','bank-notification-service','bank-postgres','bank-kafka')) {
    if (-not (docker ps --filter "name=$c" --filter "status=running" -q)) {
        Write-Error "$c çalışmıyor. Önce: docker compose --profile apps up -d --build"
        exit 1
    }
}

Log "k6 başlatılıyor (hız=$Rate/sn, süre=$Duration, CHAOS=1)"
$k6Args = @('run', '-e', 'SCENARIO=steady', '-e', "RATE=$Rate", '-e', "DURATION=$Duration", '-e', "USERS=$Users", '-e', 'CHAOS=1',
            '--summary-export', "results/chaos-$Fault-$(Get-Date -Format 'yyyyMMdd-HHmmss').json", 'transfers.js')
New-Item -ItemType Directory -Force -Path (Join-Path $root 'load-tests/results') | Out-Null
$k6 = Start-Process -FilePath k6 -ArgumentList $k6Args -WorkingDirectory (Join-Path $root 'load-tests') -PassThru -NoNewWindow

Log "arıza öncesi bekleniyor ($FaultAt sn): kurulum + yük başlangıcı"
Start-Sleep -Seconds $FaultAt

switch ($Fault) {
    'kill-account'      { Kill-Service 'account';      Start-Sleep -Seconds $DownSeconds; Start-Service 'account' }
    'kill-transfer'     { Kill-Service 'transfer';     Start-Sleep -Seconds $DownSeconds; Start-Service 'transfer' }
    'kill-notification' { Kill-Service 'notification'; Start-Sleep -Seconds $DownSeconds; Start-Service 'notification' }
    'kill-all' {
        'account','transfer','notification' | ForEach-Object { Kill-Service $_ }
        Start-Sleep -Seconds $DownSeconds
        'account','transfer','notification' | ForEach-Object { Start-Service $_ }
    }
    'rolling' {
        foreach ($s in 'account','transfer','notification') {
            Kill-Service $s; Start-Sleep -Seconds $DownSeconds; Start-Service $s; Start-Sleep -Seconds 10
        }
    }
    'pause-kafka'      { Log 'Kafka donduruluyor (docker pause)'; docker pause bank-kafka | Out-Null; Start-Sleep -Seconds $DownSeconds; docker unpause bank-kafka | Out-Null; Log 'Kafka devam ediyor' }
    'stop-kafka'       { Log 'Kafka durduruluyor'; docker stop bank-kafka | Out-Null; Start-Sleep -Seconds $DownSeconds; docker start bank-kafka | Out-Null; Log 'Kafka başlatıldı' }
    'restart-postgres' { Log 'PostgreSQL yeniden başlatılıyor'; docker restart bank-postgres | Out-Null; Log 'PostgreSQL döndü' }
}

Log 'arıza giderildi; k6 yükü bitirip doğrulamaları yapıyor (saga boşalması, Σ bakiye, clearing, mutabakat)...'
$k6.WaitForExit()
if ($k6.ExitCode -eq 0) {
    Write-Host "SONUÇ: GEÇTİ. '$Fault' sonrasında para kaybı yok, saga'lar bitti, mutabakat temiz." -ForegroundColor Green
} else {
    Write-Host "SONUÇ: BAŞARISIZ (k6 çıkış kodu $($k6.ExitCode)). Yukarıdaki 'DOĞRULAMA' satırlarına bak." -ForegroundColor Red
}
exit $k6.ExitCode
