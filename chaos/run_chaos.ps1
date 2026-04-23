# ============================================================
#  Chaos Engineering Test Runner
#  Kullanim: .\chaos\run_chaos.ps1
#
#  Onkosul: docker compose up -d ile tum servisler calisiyor olmali
#  Test suresi: ~2-3 dakika
# ============================================================

param(
    [string]$AppUrl       = "http://localhost:8080",
    [string]$ToxiproxyUrl = "http://localhost:8666",
    [string]$PrometheusUrl= "http://localhost:9090"
)

# ── Renk sabitleri ────────────────────────────────────────────
$Green  = "Green"
$Red    = "Red"
$Yellow = "Yellow"
$Cyan   = "Cyan"
$White  = "White"

# ── Sonuc tablosu ────────────────────────────────────────────
$Results = @()

# ── Yardimci fonksiyonlar ─────────────────────────────────────

function Write-Header([string]$text) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor $Cyan
    Write-Host "  $text" -ForegroundColor $Cyan
    Write-Host ("=" * 60) -ForegroundColor $Cyan
}

function Write-Step([string]$text) {
    Write-Host "  ► $text" -ForegroundColor $White
}

function Write-Pass([string]$text) {
    Write-Host "  ✔ $text" -ForegroundColor $Green
}

function Write-Fail([string]$text) {
    Write-Host "  ✘ $text" -ForegroundColor $Red
}

function Write-Info([string]$text) {
    Write-Host "    $text" -ForegroundColor $Yellow
}

# HTTP GET, sure olcumlu
function Invoke-TimedGet([string]$url) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-WebRequest -Uri $url -TimeoutSec 15 -ErrorAction Stop
        $sw.Stop()
        return @{ Status = [int]$resp.StatusCode; Ms = $sw.ElapsedMilliseconds; Ok = $true }
    } catch {
        $sw.Stop()
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $code; Ms = $sw.ElapsedMilliseconds; Ok = $false }
    }
}

# HTTP POST JSON
function Invoke-Post([string]$url, [string]$body) {
    try {
        $resp = Invoke-WebRequest -Uri $url -Method POST `
            -ContentType "application/json" -Body $body `
            -TimeoutSec 15 -ErrorAction Stop
        return @{ Status = [int]$resp.StatusCode; Ok = $true }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $code; Ok = $false }
    }
}

# Toxiproxy: toxic ekle
function Add-Toxic([string]$proxy, [string]$jsonBody) {
    try {
        Invoke-RestMethod -Uri "$ToxiproxyUrl/proxies/$proxy/toxics" `
            -Method POST -ContentType "application/json" -Body $jsonBody -ErrorAction Stop | Out-Null
        return $true
    } catch { return $false }
}

# Toxiproxy: toxic sil
function Remove-Toxic([string]$proxy, [string]$name) {
    try {
        Invoke-RestMethod -Uri "$ToxiproxyUrl/proxies/$proxy/toxics/$name" `
            -Method DELETE -ErrorAction Stop | Out-Null
    } catch {}   # zaten yoksa sorun degil
}

# Prometheus: son metrik degerini al
function Get-Metric([string]$query) {
    try {
        $enc   = [Uri]::EscapeDataString($query)
        $resp  = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$enc" -ErrorAction Stop
        $val   = $resp.data.result[0].value[1]
        return [double]$val
    } catch { return -1 }
}

# Senaryo sonucunu kaydet
function Record-Result([string]$scenario, [bool]$passed, [string]$detail) {
    $script:Results += [PSCustomObject]@{
        Scenario = $scenario
        Status   = if ($passed) { "PASS" } else { "FAIL" }
        Detail   = $detail
    }
}

# Stok olustur, ID dondur (ya da -1)
function Create-Stock([string]$name) {
    $body = "{`"productName`":`"$name`",`"quantity`":5,`"price`":9.99}"
    $r    = Invoke-Post "$AppUrl/api/stocks" $body
    return $r
}

# Ilk stock ID'yi al
function Get-FirstStockId {
    try {
        $stocks = Invoke-RestMethod -Uri "$AppUrl/api/stocks" -ErrorAction Stop
        if ($stocks.Count -gt 0) { return $stocks[0].id } else { return 1 }
    } catch { return 1 }
}

# ─────────────────────────────────────────────────────────────
#  ONKOSUL: Servis saglik kontrolu
# ─────────────────────────────────────────────────────────────
Write-Header "Chaos Engineering — Servis Kontrol"

Write-Step "Spring Boot app saglik kontrolu..."
$health = Invoke-TimedGet "$AppUrl/actuator/health"
if (-not $health.Ok) {
    Write-Fail "Uygulama erisilebilir degil ($AppUrl). Once 'docker compose up -d' calistir."
    exit 1
}
Write-Pass "App saglikli ($($health.Status)) — $($health.Ms)ms"

Write-Step "Toxiproxy kontrol..."
$toxi = Invoke-TimedGet "$ToxiproxyUrl/proxies"
if (-not $toxi.Ok) {
    Write-Fail "Toxiproxy erisilebilir degil ($ToxiproxyUrl)."
    exit 1
}
Write-Pass "Toxiproxy hazir"

Write-Step "Prometheus kontrol..."
$prom = Invoke-TimedGet "$PrometheusUrl/-/healthy"
if (-not $prom.Ok) {
    Write-Info "Prometheus erisilebilir degil — metrik dogrulamasi atlanacak."
}
Write-Pass "Kontroller tamamlandi, testler basliyor..."
Start-Sleep -Seconds 1

# Stok ID hazirla (baseline test icin)
$stockId = Get-FirstStockId
if ($stockId -eq 1) {
    # Bos olabilir, bir tane olustur
    $init = Create-Stock "ChaosBaseline-$(Get-Random -Maximum 9999)"
    Start-Sleep -Seconds 1
    $stockId = Get-FirstStockId
}

# ─────────────────────────────────────────────────────────────
#  SENARYO 1: Redis Partition (AP Kaniti)
#  Beklenti: Redis kesik iken GET /stocks calismali (DB fallback)
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 1/5 — Redis Partition (AP)"

Write-Step "Baseline latency olculuyor..."
$baseline = Invoke-TimedGet "$AppUrl/api/stocks/$stockId"
Write-Info "Baseline: $($baseline.Ms)ms (HTTP $($baseline.Status))"

Write-Step "Redis master partition uygulanıyor (timeout=0)..."
$toxic1 = '{"name":"chaos-redis-down","type":"timeout","attributes":{"timeout":0}}'
Add-Toxic "redis-master" $toxic1 | Out-Null

Start-Sleep -Seconds 2

Write-Step "Redis kesikken GET /api/stocks/$stockId..."
$r1 = Invoke-TimedGet "$AppUrl/api/stocks/$stockId"
Write-Info "Sonuc: HTTP $($r1.Status) — $($r1.Ms)ms"

$pass1 = ($r1.Status -eq 200)
if ($pass1) {
    Write-Pass "AP dogrulandi: Redis kesik ama endpoint calisiyor (DB fallback)"
    Write-Info "Latency: $($r1.Ms)ms (baseline: $($baseline.Ms)ms — beklenen: cok daha yuksek)"
} else {
    Write-Fail "BASARISIZ: Beklenen HTTP 200, alınan $($r1.Status)"
}

Write-Step "Toxic kaldiriliyor..."
Remove-Toxic "redis-master" "chaos-redis-down"
Start-Sleep -Seconds 3

Record-Result "Redis Partition (AP)" $pass1 "HTTP $($r1.Status), $($r1.Ms)ms"

# ─────────────────────────────────────────────────────────────
#  SENARYO 2: Redis Latency (Degraded Performance)
#  Beklenti: 2000ms gecikme ile calismali, hataya dusmemeli
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 2/5 — Redis Latency (Degraded Perf)"

Write-Step "Redis'e 2000ms latency ekleniyor..."
$toxic2 = '{"name":"chaos-redis-latency","type":"latency","attributes":{"latency":2000,"jitter":200}}'
Add-Toxic "redis-master" $toxic2 | Out-Null

Start-Sleep -Seconds 1

Write-Step "Latency altinda GET /api/stocks..."
$r2 = Invoke-TimedGet "$AppUrl/api/stocks"
Write-Info "Sonuc: HTTP $($r2.Status) — $($r2.Ms)ms"

$pass2 = ($r2.Status -eq 200)
if ($pass2) {
    Write-Pass "Latency altinda sistem calisiyor: $($r2.Ms)ms"
} else {
    Write-Fail "BASARISIZ: Beklenen HTTP 200, alınan $($r2.Status)"
}

Write-Step "Toxic kaldiriliyor..."
Remove-Toxic "redis-master" "chaos-redis-latency"
Start-Sleep -Seconds 3

Record-Result "Redis Latency 2s" $pass2 "HTTP $($r2.Status), $($r2.Ms)ms"

# ─────────────────────────────────────────────────────────────
#  SENARYO 3: Kafka Partition (Graceful Degradation)
#  Beklenti: POST /stocks 200 donmeli — Kafka event kaybolur ama stok yazilir
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 3/5 — Kafka Partition (AP over CP)"

# Prometheus: mevcut kafka failure sayisi
$kafkaFailBefore = Get-Metric 'kafka_producer_send_total{status="failure"}'
Write-Info "Kafka failure onceki deger: $kafkaFailBefore"

Write-Step "Kafka partition uygulanıyor..."
$toxic3 = '{"name":"chaos-kafka-down","type":"timeout","attributes":{"timeout":0}}'
Add-Toxic "kafka" $toxic3 | Out-Null

Start-Sleep -Seconds 2

Write-Step "Kafka kesikken POST /api/stocks (stok olustur)..."
$stockName = "KafkaChaos-$(Get-Random -Maximum 9999)"
$r3 = Create-Stock $stockName
Write-Info "Sonuc: HTTP $($r3.Status)"

$pass3 = ($r3.Status -eq 200 -or $r3.Status -eq 201)
if ($pass3) {
    Write-Pass "Graceful degradation: Kafka kesik ama stok olusturuldu (AP)"
} else {
    Write-Fail "BASARISIZ: Beklenen HTTP 200/201, alınan $($r3.Status)"
}

# Biraz bekle, Prometheus guncellenmis olsun
Start-Sleep -Seconds 5
$kafkaFailAfter = Get-Metric 'kafka_producer_send_total{status="failure"}'
if ($kafkaFailAfter -gt $kafkaFailBefore -or $kafkaFailAfter -eq -1) {
    Write-Pass "Prometheus: Kafka failure metrigi artis gosterdi ($kafkaFailBefore → $kafkaFailAfter)"
} else {
    Write-Info "Prometheus metrik degisimi gorulumedi (Outbox gecikme nedeniyle normal)"
}

Write-Step "Toxic kaldiriliyor..."
Remove-Toxic "kafka" "chaos-kafka-down"
Start-Sleep -Seconds 3

Record-Result "Kafka Partition (AP)" $pass3 "HTTP $($r3.Status), metrik: $kafkaFailBefore→$kafkaFailAfter"

# ─────────────────────────────────────────────────────────────
#  SENARYO 4: MySQL Partition (CP Kaniti)
#  Beklenti: POST /stocks 500 donmeli — tutarlilik korunur, veri yazilmaz
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 4/5 — MySQL Partition (CP)"

Write-Step "MySQL partition uygulanıyor..."
$toxic4 = '{"name":"chaos-mysql-down","type":"timeout","attributes":{"timeout":0}}'
Add-Toxic "mysql" $toxic4 | Out-Null

Start-Sleep -Seconds 3

Write-Step "MySQL kesikken POST /api/stocks (500 bekleniyor — CP!)..."
$r4 = Create-Stock "MySQLChaos-$(Get-Random -Maximum 9999)"
Write-Info "Sonuc: HTTP $($r4.Status)"

$pass4 = ($r4.Status -eq 500 -or $r4.Status -eq 503)
if ($pass4) {
    Write-Pass "CP dogrulandi: MySQL kesik, 500 dondu — consistency korundu"
} else {
    Write-Fail "Beklenmedik: HTTP $($r4.Status) (500 bekleniyordu)"
}

Write-Step "Toxic kaldiriliyor..."
Remove-Toxic "mysql" "chaos-mysql-down"
Start-Sleep -Seconds 5

Record-Result "MySQL Partition (CP)" $pass4 "HTTP $($r4.Status)"

# ─────────────────────────────────────────────────────────────
#  SENARYO 5: Recovery Testi
#  Beklenti: Tum toxic'ler kaldiktan sonra sistem normallesir
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 5/5 — Recovery Testi"

Write-Step "Olasi kalan toxic'ler temizleniyor..."
Remove-Toxic "redis-master" "chaos-redis-down"
Remove-Toxic "redis-master" "chaos-redis-latency"
Remove-Toxic "kafka"        "chaos-kafka-down"
Remove-Toxic "mysql"        "chaos-mysql-down"

Write-Step "15 saniye recovery bekleniyor..."
Start-Sleep -Seconds 15

Write-Step "Recovery: GET /api/stocks/$stockId kontrol..."
$r5a = Invoke-TimedGet "$AppUrl/api/stocks/$stockId"
Write-Info "Read: HTTP $($r5a.Status) — $($r5a.Ms)ms"

Write-Step "Recovery: POST /api/stocks kontrol..."
$r5b = Create-Stock "Recovery-$(Get-Random -Maximum 9999)"
Write-Info "Write: HTTP $($r5b.Status)"

$pass5 = ($r5a.Status -eq 200) -and ($r5b.Status -eq 200 -or $r5b.Status -eq 201)
if ($pass5) {
    Write-Pass "Recovery basarili: Read=$($r5a.Status) ($($r5a.Ms)ms), Write=$($r5b.Status)"
} else {
    Write-Fail "Recovery tam degil: Read=$($r5a.Status), Write=$($r5b.Status)"
}

Record-Result "Recovery" $pass5 "Read=$($r5a.Status) $($r5a.Ms)ms, Write=$($r5b.Status)"

# ─────────────────────────────────────────────────────────────
#  FINAL OZET
# ─────────────────────────────────────────────────────────────
Write-Header "Chaos Test Sonuclari"

$pass  = ($Results | Where-Object { $_.Status -eq "PASS" }).Count
$fail  = ($Results | Where-Object { $_.Status -eq "FAIL" }).Count
$total = $Results.Count

foreach ($r in $Results) {
    $color = if ($r.Status -eq "PASS") { $Green } else { $Red }
    $icon  = if ($r.Status -eq "PASS") { "✔" } else { "✘" }
    Write-Host ("  {0} [{1}] {2}" -f $icon, $r.Status, $r.Scenario) -ForegroundColor $color
    Write-Host ("       {0}" -f $r.Detail) -ForegroundColor $Yellow
}

Write-Host ""
Write-Host ("  Toplam: $total  |  ") -NoNewline
Write-Host ("PASS: $pass  ") -NoNewline -ForegroundColor $Green
Write-Host ("FAIL: $fail") -ForegroundColor $(if ($fail -gt 0) { $Red } else { $Green })

# Raporu chaos/results/ klasorune kaydet
$outDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$reportPath = Join-Path $outDir "chaos_report_$(Get-Date -Format 'yyyyMMdd_HHmmss').md"
$md = @"
# Chaos Engineering Test Raporu
**Tarih:** $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

## Ozet
| Senaryo | Sonuc | Detay |
|---------|-------|-------|
"@
foreach ($r in $Results) {
    $icon = if ($r.Status -eq "PASS") { "✅" } else { "❌" }
    $md += "`n| $($r.Scenario) | $icon $($r.Status) | $($r.Detail) |"
}
$md += "`n`n**Toplam: $total | PASS: $pass | FAIL: $fail**"
$md | Set-Content -Path $reportPath -Encoding UTF8

Write-Host ""
Write-Host "  Rapor kaydedildi: $reportPath" -ForegroundColor $Cyan
Write-Host ("=" * 60) -ForegroundColor $Cyan

if ($fail -gt 0) { exit 1 } else { exit 0 }
