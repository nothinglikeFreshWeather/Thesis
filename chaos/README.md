# Chaos Engineering Tests

## Hızlı Başlangıç

```powershell
# 1. Servisleri ayağa kaldır
docker compose up -d

# 2. Servislerin tamamen başlamasını bekle (~60 saniye)

# 3. Chaos testlerini çalıştır
.\chaos\run_chaos.ps1
```

## Test Senaryoları

| # | Senaryo | Toxic | Beklenen | CAP |
|---|---------|-------|----------|-----|
| 1 | Redis Partition | timeout=0 | GET 200 (DB fallback) | AP ✅ |
| 2 | Redis Latency | latency=2000ms | GET 200 ama yavaş | AP degraded |
| 3 | Kafka Partition | timeout=0 | POST 200 (graceful) | AP over CP |
| 4 | MySQL Partition | timeout=0 | POST 500 (consistency) | CP ✅ |
| 5 | Recovery | toxic yok | Her şey normal | — |

## Çıktı

Terminal'de renkli pass/fail çıktısı görürsünüz.  
Markdown rapor otomatik olarak `chaos/results/` klasörüne kaydedilir.

## GitHub Actions

`.github/workflows/chaos.yml` dosyası her Pazartesi 02:00 UTC'de
ve elle tetiklendiğinde çalışır (Actions → Run workflow).
