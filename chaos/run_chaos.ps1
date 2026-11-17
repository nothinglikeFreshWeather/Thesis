# ============================================================
#  Chaos Engineering Test Runner - with Prometheus Metrics
#  Kullanim: powershell -ExecutionPolicy Bypass -File .\chaos\run_chaos.ps1
#  Onkosul: docker compose up -d
# ============================================================

param(
    [string]$AppUrl       = "http://localhost:8080",
    [string]$ToxiproxyUrl = "http://localhost:8666",
    [string]$PrometheusUrl= "http://localhost:9090",
    [string]$GrafanaUrl   = "http://localhost:3001"
)

$Green  = "Green"
$Red    = "Red"
$Yellow = "Yellow"
$Cyan   = "Cyan"
$White  = "White"

$Results        = @()
$DefaultHeaders = @{ "User-Agent" = "curl/7.68.0" }

# ── Yardimci fonksiyonlar ─────────────────────────────────────

function Write-Header([string]$text) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor $Cyan
    Write-Host "  $text" -ForegroundColor $Cyan
    Write-Host ("=" * 60) -ForegroundColor $Cyan
}
function Write-Step([string]$text)  { Write-Host "  > $text" -ForegroundColor $White }
function Write-Pass([string]$text)  { Write-Host "  [PASS] $text" -ForegroundColor $Green }
function Write-Fail([string]$text)  { Write-Host "  [FAIL] $text" -ForegroundColor $Red }
function Write-Info([string]$text)  { Write-Host "    $text" -ForegroundColor $Yellow }
function Write-Metric([string]$text){ Write-Host "    [metric] $text" -ForegroundColor $Cyan }

function Invoke-TimedGet([string]$url, [int]$TimeoutSec = 15) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-WebRequest -Uri $url -TimeoutSec $TimeoutSec -UseBasicParsing -Headers $DefaultHeaders -ErrorAction Stop
        $sw.Stop()
        return @{ Status = [int]$resp.StatusCode; Ms = $sw.ElapsedMilliseconds; Ok = $true }
    } catch {
        $sw.Stop()
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $code; Ms = $sw.ElapsedMilliseconds; Ok = $false }
    }
}

function Invoke-Post([string]$url, [string]$body, [int]$TimeoutSec = 15) {
    try {
        $resp = Invoke-WebRequest -Uri $url -Method POST `
            -ContentType "application/json" -Body $body `
            -TimeoutSec $TimeoutSec -UseBasicParsing -Headers $DefaultHeaders -ErrorAction Stop
        return @{ Status = [int]$resp.StatusCode; Ok = $true }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return @{ Status = $code; Ok = $false }
    }
}

function Add-Toxic([string]$proxy, [string]$jsonBody) {
    try {
        Invoke-RestMethod -Uri "$ToxiproxyUrl/proxies/$proxy/toxics" `
            -Method POST -ContentType "application/json" -Body $jsonBody `
            -Headers $DefaultHeaders -ErrorAction Stop | Out-Null
        return $true
    } catch { return $false }
}

function Remove-Toxic([string]$proxy, [string]$name) {
    try {
        Invoke-RestMethod -Uri "$ToxiproxyUrl/proxies/$proxy/toxics/$name" `
            -Method DELETE -Headers $DefaultHeaders -ErrorAction Stop | Out-Null
    } catch {}
}

function Get-Metric([string]$query) {
    try {
        $enc  = [Uri]::EscapeDataString($query)
        $resp = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$enc" `
            -Headers $DefaultHeaders -ErrorAction Stop
        $val  = $resp.data.result[0].value[1]
        return [math]::Round([double]$val, 4)
    } catch { return -1 }
}

# Tum ilgili metriklerin anlık fotografini çeker
function Get-MetricsSnapshot {
    return [ordered]@{
        cache_hit              = Get-Metric 'cache_hit_miss_total{result="hit"}'
        cache_miss             = Get-Metric 'cache_hit_miss_total{result="miss"}'
        cache_get_duration_p99 = Get-Metric 'histogram_quantile(0.99, rate(cache_operation_duration_seconds_bucket{operation="get"}[1m]))'
        redis_reconnects       = Get-Metric 'redis_reconnect_total'
        kafka_success          = Get-Metric 'kafka_producer_send_total{status="success"}'
        kafka_failure          = Get-Metric 'kafka_producer_send_total{status="failure"}'
        outbox_sent            = Get-Metric 'outbox_events_total{result="sent"}'
        outbox_failed          = Get-Metric 'outbox_events_total{result="failed"}'
        outbox_retried         = Get-Metric 'outbox_events_total{result="retried"}'
        outbox_pending         = Get-Metric 'outbox_pending_events'
        http_req_count         = Get-Metric 'http_server_requests_seconds_count'
        http_req_sum_s         = Get-Metric 'http_server_requests_seconds_sum'
        stock_create_p99       = Get-Metric 'histogram_quantile(0.99, rate(stock_create_duration_seconds_bucket[1m]))'
    }
}

# Delta hesapla ve formatla
function Format-Delta($before, $after, [string]$key) {
    $b = $before[$key]
    $a = $after[$key]
    if ($b -eq -1 -and $a -eq -1) { return "N/A (Prometheus erisim hatasi)" }
    if ($b -eq -1) { return "$a (oncesi alinamadi)" }
    if ($a -eq -1) { return "N/A (sonrasi alinamadi - app DOWN olabilir)" }
    $d = [math]::Round($a - $b, 4)
    $sign = if ($d -gt 0) { "+" } else { "" }
    return "$a ($sign$d)"
}

# Rapor icin markdown metrik tablosu uret
function Build-MetricsTable($before, $after) {
    $rows = @(
        @{ Label = "Cache HIT sayisi";         Key = "cache_hit" },
        @{ Label = "Cache MISS sayisi";        Key = "cache_miss" },
        @{ Label = "Cache GET p99 (sn)";       Key = "cache_get_duration_p99" },
        @{ Label = "Redis reconnect";          Key = "redis_reconnects" },
        @{ Label = "Kafka success";            Key = "kafka_success" },
        @{ Label = "Kafka failure";            Key = "kafka_failure" },
        @{ Label = "Outbox sent";              Key = "outbox_sent" },
        @{ Label = "Outbox failed";            Key = "outbox_failed" },
        @{ Label = "Outbox retried";           Key = "outbox_retried" },
        @{ Label = "Outbox pending (anlik)";   Key = "outbox_pending" },
        @{ Label = "HTTP istek sayisi";        Key = "http_req_count" },
        @{ Label = "HTTP toplam sure (sn)";    Key = "http_req_sum_s" },
        @{ Label = "Stock create p99 (sn)";    Key = "stock_create_p99" }
    )

    $table = "| Metrik | Oncesi | Sonrasi (delta) |`n|--------|--------|-----------------|`n"
    foreach ($row in $rows) {
        $b = $before[$row.Key]
        $a = $after[$row.Key]
        $bStr = if ($b -eq -1) { "N/A" } else { "$b" }
        $dStr = Format-Delta $before $after $row.Key
        $table += "| $($row.Label) | $bStr | $dStr |`n"
    }
    return $table
}

function Create-Stock([string]$name, [int]$TimeoutSec = 15) {
    $bodyJson = (@{ productName = $name; quantity = 5; price = 9.99 } | ConvertTo-Json -Compress)
    return Invoke-Post "$AppUrl/api/stocks" $bodyJson $TimeoutSec
}

function Get-FirstStockId {
    try {
        $stocks = @(Invoke-RestMethod -Uri "$AppUrl/api/stocks" -Headers $DefaultHeaders -ErrorAction Stop)
        if ($stocks.Count -gt 0) { return $stocks[0].id } else { return 1 }
    } catch { return 1 }
}

function Record-Result([string]$scenario, [bool]$passed, [string]$detail, $mBefore, $mAfter, [string]$grafanaLink = "") {
    $script:Results += [PSCustomObject]@{
        Scenario    = $scenario
        Status      = if ($passed) { "PASS" } else { "FAIL" }
        Detail      = $detail
        MetricsBefore = $mBefore
        MetricsAfter  = $mAfter
        GrafanaLink   = $grafanaLink
    }
}

# ─────────────────────────────────────────────────────────────
#  ONKOSUL KONTROLU
# ─────────────────────────────────────────────────────────────
Write-Header "Chaos Engineering - Servis Kontrol"

$health = Invoke-TimedGet "$AppUrl/actuator/health"
if (-not $health.Ok) {
    Write-Fail "Uygulama erisilebilir degil. 'docker compose up -d' calistir."
    exit 1
}
Write-Pass "App saglikli ($($health.Status)) - $($health.Ms)ms"

$toxi = Invoke-TimedGet "$ToxiproxyUrl/proxies"
if (-not $toxi.Ok) {
    Write-Fail "Toxiproxy erisilebilir degil ($ToxiproxyUrl)."
    exit 1
}
Write-Pass "Toxiproxy hazir"

$prom = Invoke-TimedGet "$PrometheusUrl/-/healthy"
if ($prom.Ok) { Write-Pass "Prometheus hazir" } else { Write-Info "Prometheus yok - metrikler N/A" }

Start-Sleep -Seconds 1
$stockId = Get-FirstStockId
if ($stockId -le 0) {
    Create-Stock "ChaosBaseline-$(Get-Random -Maximum 9999)" | Out-Null
    Start-Sleep -Seconds 1
    $stockId = Get-FirstStockId
}

$testStart = Get-Date

# ─────────────────────────────────────────────────────────────
#  SENARYO 1: Redis Partition (AP)
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 1/5 - Redis Partition (AP)"

$s1Start = Get-Date

Write-Step "Baseline olculuyor..."
$baseline = Invoke-TimedGet "$AppUrl/api/stocks/$stockId"
Write-Info "Baseline: $($baseline.Ms)ms (HTTP $($baseline.Status))"

$m1Before = Get-MetricsSnapshot

Write-Step "Redis master partition uygulaniyor (timeout=0)..."
Add-Toxic "redis-master" '{"name":"chaos-redis-down","type":"timeout","attributes":{"timeout":0}}' | Out-Null
Start-Sleep -Seconds 2

Write-Step "Redis kesikken GET /api/stocks/$stockId (timeout: 25s)..."
$r1 = Invoke-TimedGet "$AppUrl/api/stocks/$stockId" 25
Write-Info "Sonuc: HTTP $($r1.Status) - $($r1.Ms)ms"

Start-Sleep -Seconds 2
$m1After = Get-MetricsSnapshot

$pass1 = ($r1.Status -eq 200)
if ($pass1) {
    Write-Pass "AP dogrulandi: Redis kesik, DB fallback calisti"
    Write-Metric "Cache MISS: $($m1Before.cache_miss) -> $($m1After.cache_miss) (delta: +$($m1After.cache_miss - $m1Before.cache_miss))"
    Write-Metric "Redis reconnect: $($m1Before.redis_reconnects) -> $($m1After.redis_reconnects)"
} else {
    Write-Fail "BASARISIZ: Beklenen HTTP 200, alinan $($r1.Status)"
}

Remove-Toxic "redis-master" "chaos-redis-down"
Start-Sleep -Seconds 3

$grafana1 = "$GrafanaUrl/d/stock-metrics?from=$(([DateTimeOffset]$s1Start).ToUnixTimeMilliseconds())&to=$(([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())"
Record-Result "Redis Partition (AP)" $pass1 "HTTP $($r1.Status), $($r1.Ms)ms (baseline: $($baseline.Ms)ms)" $m1Before $m1After $grafana1

# ─────────────────────────────────────────────────────────────
#  SENARYO 2: Redis Latency (Degraded Perf)
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 2/5 - Redis Latency (Degraded Perf)"

$s2Start = Get-Date

# Cache bust: yeni stok olustur -> ID al -> ilk sorguda MISS garantisi
Write-Step "Cache bust: yeni stok olusturuluyor (latency testi icin cache MISS garantisi)..."
Create-Stock "LatencyTest-$(Get-Random -Maximum 99999)" | Out-Null
Start-Sleep -Seconds 1
$stocks = @(Invoke-RestMethod -Uri "$AppUrl/api/stocks" -Headers $DefaultHeaders)
$latencyTestStockId = if ($stocks.Count -gt 0) { $stocks[-1].id } else { $stockId }

$m2Before = Get-MetricsSnapshot

Write-Step "Redis'e 2000ms latency ekleniyor..."
Add-Toxic "redis-master" '{"name":"chaos-redis-latency","type":"latency","attributes":{"latency":2000,"jitter":200}}' | Out-Null
Start-Sleep -Seconds 1

Write-Step "Latency altinda GET /api/stocks/$latencyTestStockId (ilk istek = cache MISS, Redis'e gidecek, timeout: 20s)..."
$r2 = Invoke-TimedGet "$AppUrl/api/stocks/$latencyTestStockId" 20
Write-Info "Sonuc: HTTP $($r2.Status) - $($r2.Ms)ms (beklenen: 2000ms+)"

Start-Sleep -Seconds 2
$m2After = Get-MetricsSnapshot

$pass2 = ($r2.Status -eq 200)
if ($pass2) {
    Write-Pass "Latency altinda sistem calisiyor: $($r2.Ms)ms"
    Write-Metric "Cache GET p99: $($m2Before.cache_get_duration_p99)s -> $($m2After.cache_get_duration_p99)s"
} else {
    Write-Fail "BASARISIZ: HTTP $($r2.Status)"
}

Remove-Toxic "redis-master" "chaos-redis-latency"
Start-Sleep -Seconds 3

$grafana2 = "$GrafanaUrl/d/stock-metrics?from=$(([DateTimeOffset]$s2Start).ToUnixTimeMilliseconds())&to=$(([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())"
Record-Result "Redis Latency 2s" $pass2 "HTTP $($r2.Status), $($r2.Ms)ms" $m2Before $m2After $grafana2

# ─────────────────────────────────────────────────────────────
#  SENARYO 3: Kafka Partition (AP over CP)
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 3/5 - Kafka Partition (AP over CP)"

$s3Start = Get-Date
$m3Before = Get-MetricsSnapshot
Write-Info "Kafka failure oncesi: $($m3Before.kafka_failure)"

Write-Step "Kafka partition uygulaniyor..."
Add-Toxic "kafka" '{"name":"chaos-kafka-down","type":"timeout","attributes":{"timeout":0}}' | Out-Null
Start-Sleep -Seconds 2

Write-Step "Kafka kesikken POST /api/stocks..."
$r3 = Create-Stock "KafkaChaos-$(Get-Random -Maximum 9999)"
Write-Info "Sonuc: HTTP $($r3.Status)"

Start-Sleep -Seconds 6
$m3After = Get-MetricsSnapshot

$pass3 = ($r3.Status -eq 200 -or $r3.Status -eq 201)
if ($pass3) {
    Write-Pass "Graceful degradation: Kafka kesik ama stok olusturuldu"
    $kafkaDelta = $m3After.kafka_failure - $m3Before.kafka_failure
    Write-Metric "Kafka failure: $($m3Before.kafka_failure) -> $($m3After.kafka_failure) (delta: +$kafkaDelta)"
    Write-Metric "Outbox sent: $($m3Before.outbox_sent) -> $($m3After.outbox_sent)"
} else {
    Write-Fail "BASARISIZ: HTTP $($r3.Status)"
}

Remove-Toxic "kafka" "chaos-kafka-down"
Write-Step "Outbox flush bekleniyor (12s - scheduler 5s'de bir calisir, 2 cycle garantisi)..."
Start-Sleep -Seconds 12

$grafana3 = "$GrafanaUrl/d/stock-metrics?from=$(([DateTimeOffset]$s3Start).ToUnixTimeMilliseconds())&to=$(([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())"
Record-Result "Kafka Partition (AP)" $pass3 "HTTP $($r3.Status)" $m3Before $m3After $grafana3

# ─────────────────────────────────────────────────────────────
#  SENARYO 4: MySQL Partition (CP)
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 4/5 - MySQL Partition (CP)"

$s4Start = Get-Date
$m4Before = Get-MetricsSnapshot

Write-Step "MySQL partition uygulaniyor..."
Add-Toxic "mysql" '{"name":"chaos-mysql-down","type":"timeout","attributes":{"timeout":0}}' | Out-Null
Start-Sleep -Seconds 3

Write-Step "MySQL kesikken POST /api/stocks (500 bekleniyor, timeout: 45s)..."
$r4 = Create-Stock "MySQLChaos-$(Get-Random -Maximum 9999)" 45
Write-Info "Sonuc: HTTP $($r4.Status)"

Start-Sleep -Seconds 2
$m4After = Get-MetricsSnapshot

$pass4 = ($r4.Status -eq 500 -or $r4.Status -eq 503)
if ($pass4) {
    Write-Pass "CP dogrulandi: MySQL kesik, 500 dondu - consistency korundu"
    Write-Metric "HTTP istek sayisi delta: +$($m4After.http_req_count - $m4Before.http_req_count)"
} else {
    Write-Fail "Beklenmedik: HTTP $($r4.Status) (500 bekleniyordu)"
}

Remove-Toxic "mysql" "chaos-mysql-down"
Start-Sleep -Seconds 5

$grafana4 = "$GrafanaUrl/d/stock-metrics?from=$(([DateTimeOffset]$s4Start).ToUnixTimeMilliseconds())&to=$(([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())"
Record-Result "MySQL Partition (CP)" $pass4 "HTTP $($r4.Status)" $m4Before $m4After $grafana4

# ─────────────────────────────────────────────────────────────
#  SENARYO 5: Recovery
# ─────────────────────────────────────────────────────────────
Write-Header "Senaryo 5/5 - Recovery Testi"

$s5Start = Get-Date
Remove-Toxic "redis-master" "chaos-redis-down"
Remove-Toxic "redis-master" "chaos-redis-latency"
Remove-Toxic "kafka"        "chaos-kafka-down"
Remove-Toxic "mysql"        "chaos-mysql-down"

$m5Before = Get-MetricsSnapshot

Write-Step "15 saniye recovery bekleniyor..."
Start-Sleep -Seconds 15

Write-Step "Recovery: GET /api/stocks/$stockId..."
$r5a = Invoke-TimedGet "$AppUrl/api/stocks/$stockId"
Write-Info "Read: HTTP $($r5a.Status) - $($r5a.Ms)ms"

Write-Step "Recovery: POST /api/stocks..."
$r5b = Create-Stock "Recovery-$(Get-Random -Maximum 9999)"
Write-Info "Write: HTTP $($r5b.Status)"

$m5After = Get-MetricsSnapshot

$pass5 = ($r5a.Status -eq 200) -and ($r5b.Status -eq 200 -or $r5b.Status -eq 201)
if ($pass5) {
    Write-Pass "Recovery basarili: Read=$($r5a.Status) ($($r5a.Ms)ms), Write=$($r5b.Status)"
    Write-Metric "Cache HIT: $($m5Before.cache_hit) -> $($m5After.cache_hit)"
    Write-Metric "Kafka success: $($m5Before.kafka_success) -> $($m5After.kafka_success)"
} else {
    Write-Fail "Recovery tam degil: Read=$($r5a.Status), Write=$($r5b.Status)"
}

$grafana5 = "$GrafanaUrl/d/stock-metrics?from=$(([DateTimeOffset]$s5Start).ToUnixTimeMilliseconds())&to=$(([DateTimeOffset](Get-Date)).ToUnixTimeMilliseconds())"
Record-Result "Recovery" $pass5 "Read=$($r5a.Status) $($r5a.Ms)ms, Write=$($r5b.Status)" $m5Before $m5After $grafana5

# ─────────────────────────────────────────────────────────────
#  FINAL OZET (terminal)
# ─────────────────────────────────────────────────────────────
Write-Header "Chaos Test Sonuclari"

$passCount  = ($Results | Where-Object { $_.Status -eq "PASS" }).Count
$failCount  = ($Results | Where-Object { $_.Status -eq "FAIL" }).Count
$totalCount = $Results.Count

foreach ($r in $Results) {
    $color = if ($r.Status -eq "PASS") { $Green } else { $Red }
    Write-Host ("  [{0}] {1}" -f $r.Status, $r.Scenario) -ForegroundColor $color
    Write-Host ("       {0}" -f $r.Detail) -ForegroundColor $Yellow
}

Write-Host ""
Write-Host ("  Toplam: $totalCount  |  ") -NoNewline
Write-Host ("PASS: $passCount  ") -NoNewline -ForegroundColor $Green
Write-Host ("FAIL: $failCount") -ForegroundColor $(if ($failCount -gt 0) { $Red } else { $Green })

# ─────────────────────────────────────────────────────────────
#  RAPOR URETIMI (Markdown)
# ─────────────────────────────────────────────────────────────
$outDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$reportPath = Join-Path $outDir "chaos_report_$(Get-Date -Format 'yyyyMMdd_HHmmss').md"
$testEnd    = Get-Date
$duration   = [math]::Round(($testEnd - $testStart).TotalSeconds, 0)

$md  = "# Chaos Engineering Test Raporu`n"
$md += "**Tarih:** $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  |  "
$md += "**Toplam Sure:** ${duration}s`n`n"
$md += "## Ozet`n`n"
$md += "| Senaryo | Sonuc | Detay |`n"
$md += "|---------|-------|-------|`n"
foreach ($r in $Results) {
    $icon = if ($r.Status -eq "PASS") { "PASS" } else { "FAIL" }
    $md += "| $($r.Scenario) | $icon | $($r.Detail) |`n"
}
$md += "`n**Toplam: $totalCount | PASS: $passCount | FAIL: $failCount**`n`n"
$md += "---`n`n"

# Her senaryo icin detayli metrik bolumu
foreach ($r in $Results) {
    $statusEmoji = if ($r.Status -eq "PASS") { "PASS" } else { "FAIL" }
    $md += "## $($r.Scenario) - $statusEmoji`n`n"
    $md += "**Sonuc:** $($r.Detail)`n`n"

    if ($r.MetricsBefore -and $r.MetricsAfter) {
        $md += "### Prometheus Metrik Degisimleri`n`n"
        $md += Build-MetricsTable $r.MetricsBefore $r.MetricsAfter
        $md += "`n"
    }

    if ($r.GrafanaLink) {
        $md += "**Grafana (o anki zaman araliginda):** [$($r.Scenario) Dashboard]($($r.GrafanaLink))`n`n"
    }
    $md += "---`n`n"
}

$md += "## Grafana Dashboardlari`n`n"
$md += "- [Stock Metrics Dashboard]($GrafanaUrl/d/stock-metrics)`n"
$md += "- [IoT Sensor Dashboard]($GrafanaUrl/d/iot-sensors)`n"
$md += "- [Prometheus]($PrometheusUrl)`n"

$md | Set-Content -Path $reportPath -Encoding UTF8

Write-Host ""
Write-Host "  Rapor: $reportPath" -ForegroundColor $Cyan
Write-Host ("-" * 60) -ForegroundColor $Cyan

if ($failCount -gt 0) { exit 1 } else { exit 0 }
